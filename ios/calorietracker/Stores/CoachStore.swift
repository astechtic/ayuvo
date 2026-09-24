import Foundation
import SwiftUI
import UIKit

/// Observable state for the Coach tab, on top of `CoachRepository` (docs/coach.md §7).
///
/// Replaces the old `ChatStore`, which kept one `[ChatMessage]` array in `UserDefaults`. There is now
/// a current conversation, a list of the others, and per-conversation state (selected records, the
/// provider override, the §30 online decision) that survives switching away and back.
@Observable
@MainActor
final class CoachStore {
    // MARK: Published state

    private(set) var conversations: [ConversationSummary] = []
    private(set) var current: Conversation?
    private(set) var messages: [ChatMessage] = []
    private(set) var attachmentsByID: [String: ChatAttachment] = [:]
    /// Decoded thumbnails for the bubbles, keyed by attachment id. Thumbnails are <= 320 px JPEGs and
    /// there are a handful per conversation, so they are decoded inline rather than through a cache.
    private(set) var attachmentImages: [String: UIImage] = [:]
    private(set) var isReady = false
    /// Set when the store could not be opened; the UI shows it instead of pretending to work.
    private(set) var openError: String?

    /// Cross-tab hand-off from a Records entry point, consumed by `ChatView` (docs/health-records.md §27).
    private(set) var pendingHandoff: CoachRecordsHandoff?
    /// Bumped with every hand-off so `ContentView` selects the Coach tab.
    private(set) var handoffRequest = 0

    /// We always persist the full history so the user keeps their conversation, but we cap what we
    /// send to the LLM to control token cost.
    static let maxMessagesInContext = 20

    private var repository: CoachRepository?
    private var openTask: Task<Void, Never>?

    // MARK: - Lifecycle

    init() {
        openTask = Task { await self.open() }
    }

    /// Opens the database, runs the one-time legacy migration and loads the most recent conversation.
    func open() async {
        guard repository == nil else { return }
        do {
            let (database, quarantined) = try await CoachDatabase.openQuarantiningCorruption(
                url: CoachLocation.databaseURL())
            if quarantined != nil {
                openError = String(localized: "Your chat history could not be read and was reset.")
            }
            let repository = CoachRepository(database: database, files: CoachFileStore())
            self.repository = repository
            await CoachMigration.runIfNeeded(repository: repository)
            try? await repository.purgeUnreferencedAttachments(nowMs: Self.nowMs())
            await reloadConversations()
            if let id = try? await repository.mostRecentConversationID() {
                await select(conversationID: id)
            }
            isReady = true
        } catch {
            openError = error.localizedDescription
            isReady = true
        }
    }

    private func ready() async -> CoachRepository? {
        if let repository { return repository }
        await openTask?.value
        return repository
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// The store for the archive writers (docs/coach.md §11), opening it if it is not open yet.
    func repositoryIfOpen() async -> CoachRepository? { await ready() }

    // MARK: - Conversations

    func reloadConversations() async {
        guard let repository = await ready() else { return }
        conversations = (try? await repository.conversationSummaries()) ?? []
    }

    func select(conversationID: String) async {
        guard let repository = await ready() else { return }
        guard let conversation = try? await repository.conversation(id: conversationID) else { return }
        current = conversation
        messages = (try? await repository.messages(conversationID: conversationID)) ?? []
        await loadAttachments(for: messages)
    }

    /// Creates an empty conversation and makes it current. Nothing is ever cleared — "New chat" adds.
    @discardableResult
    func startNewConversation() async -> Conversation? {
        guard let repository = await ready() else { return nil }
        let conversation = Conversation(createdMs: Self.nowMs())
        guard (try? await repository.createConversation(conversation)) != nil else { return nil }
        current = conversation
        messages = []
        attachmentsByID = [:]
        await reloadConversations()
        return conversation
    }

    /// The conversation to append to, creating one on the first message of a fresh install.
    @discardableResult
    func ensureConversation() async -> Conversation? {
        if let current { return current }
        return await startNewConversation()
    }

    func rename(conversationID: String, to title: String) async {
        guard let repository = await ready() else { return }
        let trimmed = CR.collapseWS(title)
        try? await repository.renameConversation(id: conversationID, title: trimmed, nowMs: Self.nowMs())
        if current?.id == conversationID { current?.title = trimmed }
        await reloadConversations()
    }

    func delete(conversationID: String) async {
        guard let repository = await ready() else { return }
        try? await repository.deleteConversation(id: conversationID, nowMs: Self.nowMs())
        if current?.id == conversationID {
            current = nil
            messages = []
            attachmentsByID = [:]
        }
        await reloadConversations()
        if current == nil, let next = conversations.first {
            await select(conversationID: next.id)
        }
    }

    /// "Start again from this chat" (§7): a fresh, empty conversation with the same title, switches
    /// and record selection.
    @discardableResult
    func duplicate(conversationID: String) async -> Conversation? {
        guard let repository = await ready() else { return nil }
        guard let copy = try? await repository.duplicateConversation(id: conversationID, nowMs: Self.nowMs())
        else { return nil }
        current = copy
        messages = []
        attachmentsByID = [:]
        await reloadConversations()
        return copy
    }

    func setPinned(conversationID: String, pinned: Bool) async {
        guard let repository = await ready() else { return }
        try? await repository.setPinned(id: conversationID, pinned: pinned, nowMs: Self.nowMs())
        if current?.id == conversationID { current?.pinned = pinned }
        await reloadConversations()
    }

    func deleteAllConversations() async {
        guard let repository = await ready() else { return }
        try? await repository.deleteEverything()
        current = nil
        messages = []
        attachmentsByID = [:]
        conversations = []
    }

    func search(_ query: String) async -> [ConversationSummary] {
        guard let repository = await ready() else { return [] }
        return (try? await repository.search(query)) ?? []
    }

    // MARK: - Messages

    func append(_ message: ChatMessage) async {
        guard let repository = await ready(), let conversation = await ensureConversation() else { return }
        var row = message
        row.conversationID = conversation.id
        if row.seq <= 0 || messages.contains(where: { $0.seq == row.seq && $0.variantIndex == row.variantIndex }) {
            row.seq = (try? await repository.nextSeq(conversationID: conversation.id)) ?? (messages.count + 1)
        }
        guard (try? await repository.appendMessage(row)) != nil else { return }
        messages.append(row)
        current?.lastMessageMs = row.createdMs
        await loadAttachments(for: [row])
        await titleIfNeeded(conversation: conversation, from: row)
        await reloadConversations()
    }

    /// Replace the last assistant message's text (streaming responses and error-fix retries).
    func replaceLastAssistant(with content: String) async {
        guard let repository = await ready() else { return }
        guard let index = messages.lastIndex(where: { $0.role == .assistant }) else { return }
        let id = messages[index].id
        let now = Self.nowMs()
        try? await repository.updateMessageContent(id: id, content: content, nowMs: now)
        messages[index].content = content
        messages[index].updatedMs = now
    }

    /// The trailing slice sent as conversation history. The system prompt is rebuilt each turn, so
    /// the context stays fresh as the user logs more food and weights.
    func contextMessages() -> [ChatMessage] {
        Array(messages.suffix(Self.maxMessagesInContext))
    }

    private func titleIfNeeded(conversation: Conversation, from message: ChatMessage) async {
        guard conversation.title.isEmpty, message.role == .user else { return }
        let title = CR.conversationTitle(message.content)["title"].string ?? ""
        guard !title.isEmpty else { return }
        await rename(conversationID: conversation.id, to: title)
    }

    /// Every stored version of one reply, oldest first (§10).
    func variants(seq: Int) async -> [ChatMessage] {
        guard let repository = await ready(), let id = current?.id else { return [] }
        return (try? await repository.variants(conversationID: id, seq: seq)) ?? []
    }

    /// Which version of a reply the transcript is showing; defaults to the newest.
    func showVariant(_ message: ChatMessage) {
        guard let index = messages.firstIndex(where: { $0.seq == message.seq }) else { return }
        messages[index] = message
    }

    /// §10 "Regenerate": what to re-send and what the new row looks like. Nothing is deleted.
    func regeneratePlan(for message: ChatMessage) async -> RJ? {
        guard let repository = await ready(), let id = current?.id else { return nil }
        let all = (try? await repository.allMessages(conversationID: id)) ?? []
        let plan = CR.regeneratePlan(messages: all.map(\.referenceValue), seq: message.seq)
        return plan["ok"].bool == true ? plan : nil
    }

    /// Stores a regenerated reply and shows it in place of the one it replaces.
    func appendVariant(_ message: ChatMessage) async {
        guard let repository = await ready() else { return }
        guard (try? await repository.appendMessage(message)) != nil else { return }
        if let index = messages.firstIndex(where: { $0.seq == message.seq }) {
            messages[index] = message
        } else {
            messages.append(message)
        }
        await reloadConversations()
    }

    // MARK: - §10 Export

    /// A readable transcript and a machine-readable copy of one conversation.
    func export(conversationID: String, format: CoachExportFormat, provider: String? = nil) async -> CoachExportFile? {
        guard let repository = await ready(),
              let conversation = try? await repository.conversation(id: conversationID)
        else { return nil }
        let messages = (try? await repository.allMessages(conversationID: conversationID)) ?? []
        let attachmentIDs = Array(Set(messages.flatMap(\.attachmentIDs)))
        let attachments = (try? await repository.attachments(ids: attachmentIDs)) ?? []
        let day = Self.localDay(conversation.lastMessageMs ?? conversation.updatedMs)

        switch format {
        case .markdown:
            let result = CR.conversationMarkdown(
                conversation: conversation.referenceValue,
                messages: messages.map(\.referenceValue),
                attachments: attachments.map(\.referenceValue),
                localDay: day,
                provider: provider
            )
            guard let text = result["text"].string, let name = result["filename"].string else { return nil }
            return CoachExportFile(filename: name, data: Data(text.utf8))
        case .json:
            let result = CR.conversationJSON(
                conversation: conversation.referenceValue,
                messages: messages.map(\.referenceValue),
                attachments: attachments.map(\.referenceValue),
                localDay: day
            )
            guard let name = result["filename"].string else { return nil }
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            guard JSONSerialization.isValidJSONObject(result["archive"].anyValue),
                  let data = try? JSONSerialization.data(withJSONObject: result["archive"].anyValue,
                                                         options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
            else { return nil }
            return CoachExportFile(filename: name, data: data)
        }
    }

    static func localDay(_ ms: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }

    // MARK: - Attachments

    func attachment(_ id: String) -> ChatAttachment? { attachmentsByID[id] }

    /// Attachment rows by id, loading any the transcript has not needed yet.
    func attachments(ids: [String]) async -> [ChatAttachment] {
        guard !ids.isEmpty, let repository = await ready() else { return [] }
        let rows = (try? await repository.attachments(ids: ids)) ?? []
        for row in rows { attachmentsByID[row.id] = row }
        return rows
    }

    func attachments(for message: ChatMessage) -> [ChatAttachment] {
        message.attachmentIDs.compactMap { attachmentsByID[$0] }
    }

    @discardableResult
    func store(_ attachment: ChatAttachment) async -> ChatAttachment? {
        guard let repository = await ready() else { return nil }
        guard (try? await repository.insertAttachment(attachment)) != nil else { return nil }
        attachmentsByID[attachment.id] = attachment
        return attachment
    }

    func fileStore() async -> CoachFileStore? { await ready()?.files }

    private func loadAttachments(for messages: [ChatMessage]) async {
        guard let repository = await ready() else { return }
        let ids = messages.flatMap(\.attachmentIDs).filter { attachmentsByID[$0] == nil }
        guard !ids.isEmpty else { return }
        let loaded = (try? await repository.attachments(ids: ids)) ?? []
        for attachment in loaded {
            attachmentsByID[attachment.id] = attachment
        }
        let files = repository.files
        for attachment in loaded where attachment.kind == .image {
            guard attachmentImages[attachment.id] == nil else { continue }
            if let image = files.image(at: attachment.thumbnailPath ?? attachment.filePath) {
                attachmentImages[attachment.id] = image
            }
        }
    }

    /// The bubble image for one attachment, or nil when the blob is gone (an import without files).
    func image(for attachmentID: String) -> UIImage? { attachmentImages[attachmentID] }

    func cacheImage(_ image: UIImage, for attachmentID: String) { attachmentImages[attachmentID] = image }

    // MARK: - Per-conversation Coach state (docs/health-records.md §27, §30)

    var selectedRecords: [ChatRecordRef] {
        get { pendingSelection }
        set { pendingSelection = newValue }
    }

    /// Record refs of the current conversation. Kept in memory as full refs (title and date come from
    /// the records store); only the ids are persisted.
    private var pendingSelection: [ChatRecordRef] = []

    var recordsOnlineDecision: CoachRecordsOnlineDecision? {
        get { Self.decision(from: current?.recordsOnlineDecision) }
        set {
            current?.recordsOnlineDecision = Self.stored(newValue)
            persistCurrent()
        }
    }

    /// `CoachRecordsOnlineDecision` has no raw value (it is part of the §30 contract), so the stored
    /// spelling lives here rather than changing that type.
    private static func stored(_ decision: CoachRecordsOnlineDecision?) -> String? {
        switch decision {
        case .send: "send"
        case .cancel: "cancel"
        case .useOnDevice: "use_on_device"
        case nil: nil
        }
    }

    private static func decision(from text: String?) -> CoachRecordsOnlineDecision? {
        switch text {
        case "send": .send
        case "cancel": .cancel
        case "use_on_device": .useOnDevice
        default: nil
        }
    }

    var providerOverride: AIProvider? {
        get { current?.providerOverride.flatMap { AIProvider(rawValue: $0) } }
        set {
            current?.providerOverride = newValue?.rawValue
            persistCurrent()
        }
    }

    var dataSwitches: CoachDataSwitches {
        get { current?.dataSources ?? .allOn }
        set {
            current?.dataSources = newValue
            persistCurrent()
        }
    }

    func setSelectedRecords(_ refs: [ChatRecordRef]) {
        var seen = Set<String>()
        let unique = refs.filter { seen.insert($0.recordID).inserted }
        pendingSelection = Array(unique.prefix(RecordsCoach.maxSelected))
        current?.selectedRecordIDs = pendingSelection.map(\.recordID)
        persistCurrent()
    }

    func clearRecordsConversationState() {
        pendingSelection = []
        current?.selectedRecordIDs = []
        current?.recordsOnlineDecision = nil
        current?.providerOverride = nil
        persistCurrent()
    }

    private func persistCurrent() {
        guard let conversation = current else { return }
        Task { [repository] in
            try? await repository?.updateConversation(conversation, nowMs: Self.nowMs())
        }
    }

    // MARK: - Records hand-off

    /// A Records entry point: select records, prefill the prompt and open Coach.
    func requestHandoff(records: [ChatRecordRef], prompt: String) {
        pendingHandoff = CoachRecordsHandoff(records: Array(records.prefix(RecordsCoach.maxSelected)), prompt: prompt)
        handoffRequest += 1
    }

    func consumeHandoff() -> CoachRecordsHandoff? {
        defer { pendingHandoff = nil }
        return pendingHandoff
    }
}

/// Records selection + prefilled prompt handed from the Records tab to Coach (docs/health-records.md §27).
struct CoachRecordsHandoff: Equatable, Identifiable {
    let id = UUID()
    var records: [ChatRecordRef]
    var prompt: String
}
