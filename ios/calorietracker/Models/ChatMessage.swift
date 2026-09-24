import Foundation

/// One turn of a Coach conversation (docs/coach.md §7). Rows live in `coach.sqlite`; only the
/// visible text, the records the reply relied on and the attachment ids persist — tool payloads never
/// do (docs/health-records.md §26).
struct ChatMessage: Identifiable, Codable, Equatable {
    enum Role: String, Codable {
        case user
        case assistant
    }

    let id: String
    /// The conversation this turn belongs to. Empty only for a message built before it is stored.
    var conversationID: String
    /// Position in the conversation, 1-based. A regenerated reply keeps the seq it replaces.
    var seq: Int
    /// Which version of that reply this is; the transcript shows the highest, the stepper the rest.
    var variantIndex: Int
    let role: Role
    var content: String
    var createdMs: Int64
    var updatedMs: Int64
    /// The first variant's id when this reply came from "Regenerate".
    var regeneratedFrom: String?
    /// Health Records the reply relied on (docs/health-records.md §26 `record_refs`), shown as
    /// "Used records" chips. Absent in older histories.
    var recordRefs: [ChatRecordRef]?
    /// Attachments of this turn, in the order the user added them (docs/coach.md §6).
    var attachmentIDs: [String]

    enum CodingKeys: String, CodingKey {
        case id, role, content, seq
        case conversationID = "conversation_id"
        case variantIndex = "variant_index"
        case createdMs = "created_ms"
        case updatedMs = "updated_ms"
        case regeneratedFrom = "regenerated_from"
        case recordRefs = "record_refs"
        case attachmentIDs = "attachment_ids"
    }

    init(
        id: String = UUID().uuidString.lowercased(),
        conversationID: String = "",
        seq: Int = 1,
        variantIndex: Int = 0,
        role: Role,
        content: String,
        createdMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        updatedMs: Int64? = nil,
        regeneratedFrom: String? = nil,
        recordRefs: [ChatRecordRef]? = nil,
        attachmentIDs: [String] = []
    ) {
        self.id = id
        self.conversationID = conversationID
        self.seq = seq
        self.variantIndex = variantIndex
        self.role = role
        self.content = content
        self.createdMs = createdMs
        self.updatedMs = updatedMs ?? createdMs
        self.regeneratedFrom = regeneratedFrom
        self.recordRefs = recordRefs
        self.attachmentIDs = attachmentIDs
    }

    var timestamp: Date { Date(timeIntervalSince1970: TimeInterval(createdMs) / 1000) }
}

/// The legacy `coachChatHistory` shape, decoded once by `CoachMigration` and then discarded
/// (docs/coach.md §12). Kept deliberately minimal: it only has to read what the old app wrote.
struct LegacyChatMessage: Codable {
    var id: UUID?
    var role: String
    var content: String
    var timestamp: Date?
    var attachmentImageData: Data?
    var recordRefs: [ChatRecordRef]?

    enum CodingKeys: String, CodingKey {
        case id, role, content, timestamp, attachmentImageData
        case recordRefs = "record_refs"
    }
}
