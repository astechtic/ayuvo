import Foundation
import UIKit

/// One-time move of the legacy `coachChatHistory` preference into `coach.sqlite` (docs/coach.md §12).
///
/// Runs once, guarded by a `coach_meta` row, on the first launch after the update. A corrupt blob
/// yields an empty store rather than a crash: the user loses a history they could not read anyway,
/// and the app still starts.
nonisolated enum CoachMigration {
    static let legacyKey = "coachChatHistory"
    static let metaKey = "legacy_history_migrated"

    nonisolated struct Result: Equatable, Sendable {
        var conversationID: String?
        var messageCount: Int
        var attachmentCount: Int
        /// The key held bytes that did not decode; the store is left empty and the key cleared.
        var wasCorrupt: Bool

        static let nothingToDo = Result(conversationID: nil, messageCount: 0, attachmentCount: 0, wasCorrupt: false)
    }

    /// Imports the legacy blob if it is still there. Safe to call on every launch.
    @discardableResult
    static func runIfNeeded(
        repository: CoachRepository,
        defaults: UserDefaults = .standard,
        nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) async -> Result {
        if let done = try? await repository.meta(metaKey), done == "1" {
            return .nothingToDo
        }
        defer { Task { try? await repository.setMeta(metaKey, "1") } }

        guard let data = defaults.data(forKey: legacyKey) else {
            return .nothingToDo
        }
        defaults.removeObject(forKey: legacyKey)

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .deferredToDate
        guard let legacy = try? decoder.decode([LegacyChatMessage].self, from: data) else {
            return Result(conversationID: nil, messageCount: 0, attachmentCount: 0, wasCorrupt: true)
        }
        guard !legacy.isEmpty else { return .nothingToDo }

        let firstUserText = legacy.first(where: { $0.role == "user" })?.content ?? ""
        let title = CR.conversationTitle(firstUserText)["title"].string ?? ""
        let createdMs = legacy.first?.timestamp.map { Int64($0.timeIntervalSince1970 * 1000) } ?? nowMs
        let conversation = Conversation(title: title, createdMs: createdMs, updatedMs: nowMs)

        do {
            try await repository.createConversation(conversation)
        } catch {
            return Result(conversationID: nil, messageCount: 0, attachmentCount: 0, wasCorrupt: false)
        }

        var attachments = 0
        var stored = 0
        for (index, row) in legacy.enumerated() {
            let at = row.timestamp.map { Int64($0.timeIntervalSince1970 * 1000) } ?? (createdMs + Int64(index))
            var attachmentIDs: [String] = []
            if let imageData = row.attachmentImageData, !imageData.isEmpty {
                if let id = await storeLegacyImage(imageData, repository: repository, createdMs: at) {
                    attachmentIDs.append(id)
                    attachments += 1
                }
            }
            let message = ChatMessage(
                conversationID: conversation.id,
                seq: index + 1,
                role: row.role == "assistant" ? .assistant : .user,
                content: row.content,
                createdMs: at,
                recordRefs: row.recordRefs,
                attachmentIDs: attachmentIDs
            )
            if (try? await repository.appendMessage(message)) != nil { stored += 1 }
        }
        return Result(conversationID: conversation.id, messageCount: stored,
                      attachmentCount: attachments, wasCorrupt: false)
    }

    /// The old history inlined a JPEG thumbnail per message; it becomes a real attachment so the new
    /// bubble can load it the same way as everything else.
    private static func storeLegacyImage(
        _ data: Data,
        repository: CoachRepository,
        createdMs: Int64
    ) async -> String? {
        let attachmentID = UUID().uuidString.lowercased()
        let files = repository.files
        guard let path = try? files.writeOriginal(data, attachmentID: attachmentID, fileExtension: "jpg") else {
            return nil
        }
        var thumbnailPath: String?
        if let image = UIImage(data: data) {
            thumbnailPath = files.writeThumbnail(image, attachmentID: attachmentID)
        }
        let attachment = ChatAttachment(
            id: attachmentID,
            kind: .image,
            filename: "photo.jpg",
            mimeType: "image/jpeg",
            bytes: data.count,
            sha256: CoachFileStore.sha256(data),
            filePath: path,
            thumbnailPath: thumbnailPath,
            createdMs: createdMs
        )
        guard (try? await repository.insertAttachment(attachment)) != nil else {
            files.delete(attachmentID: attachmentID)
            return nil
        }
        return attachmentID
    }
}
