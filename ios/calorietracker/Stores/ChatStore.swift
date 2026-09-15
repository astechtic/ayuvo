import Foundation
import SwiftUI

/// Persists the Coach conversation locally in UserDefaults so the history survives app restarts,
/// and exposes a reset to let the user start fresh whenever they want.
@Observable
class ChatStore {
    private(set) var messages: [ChatMessage] = []

    // MARK: Per-conversation Health Records state (§27, §30) — in memory, cleared by a reset.

    /// Records selected for this conversation (0–10).
    private(set) var selectedRecords: [ChatRecordRef] = []
    /// §30 answer for this conversation when the provider is online and the records AI mode is local/off.
    var recordsOnlineDecision: CoachRecordsOnlineDecision?
    /// "Use on-device Coach": the provider this conversation switched to.
    var providerOverride: AIProvider?
    /// Cross-tab hand-off from a Records entry point, consumed by ChatView.
    private(set) var pendingHandoff: CoachRecordsHandoff?
    /// Bumped with every hand-off so ContentView selects the Coach tab.
    private(set) var handoffRequest = 0

    private let storageKey = "coachChatHistory"
    /// We always persist the full history so the user keeps their conversation,
    /// but we cap what we send to the LLM to control token cost.
    static let maxMessagesInContext = 20

    init() {
        load()
    }

    func append(_ message: ChatMessage) {
        messages.append(message)
        save()
    }

    /// Replace the last assistant message. Useful for streaming responses or error-fix retries.
    func replaceLastAssistant(with content: String) {
        guard let idx = messages.lastIndex(where: { $0.role == .assistant }) else { return }
        let old = messages[idx]
        messages[idx] = ChatMessage(id: old.id, role: .assistant, content: content, timestamp: old.timestamp, attachmentImageData: old.attachmentImageData, recordRefs: old.recordRefs)
        save()
    }

    func reloadFromDefaults() {
        load()
    }

    func reset() {
        messages = []
        clearRecordsConversationState()
        save()
    }

    func setSelectedRecords(_ refs: [ChatRecordRef]) {
        var seen = Set<String>()
        let unique = refs.filter { seen.insert($0.recordID).inserted }
        selectedRecords = Array(unique.prefix(RecordsCoach.maxSelected))
    }

    func clearRecordsConversationState() {
        selectedRecords = []
        recordsOnlineDecision = nil
        providerOverride = nil
    }

    /// A Records entry point: select records, prefill the prompt and open Coach.
    func requestHandoff(records: [ChatRecordRef], prompt: String) {
        pendingHandoff = CoachRecordsHandoff(records: Array(records.prefix(RecordsCoach.maxSelected)), prompt: prompt)
        handoffRequest += 1
    }

    func consumeHandoff() -> CoachRecordsHandoff? {
        defer { pendingHandoff = nil }
        return pendingHandoff
    }

    /// Trailing slice of messages to send as conversation history to the LLM. The system prompt
    /// is built separately each turn so the context stays fresh as the user logs more food/weights.
    func contextMessages() -> [ChatMessage] {
        Array(messages.suffix(Self.maxMessagesInContext))
    }

    // MARK: - Persistence

    private func save() {
        if let data = try? JSONEncoder().encode(messages) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([ChatMessage].self, from: data)
        else { return }
        messages = decoded
    }
}

/// Records selection + prefilled prompt handed from the Records tab to Coach (§27).
struct CoachRecordsHandoff: Equatable, Identifiable {
    let id = UUID()
    var records: [ChatRecordRef]
    var prompt: String
}
