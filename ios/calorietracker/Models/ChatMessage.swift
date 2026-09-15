import Foundation

struct ChatMessage: Identifiable, Codable, Equatable {
    enum Role: String, Codable {
        case user
        case assistant
    }

    let id: UUID
    let role: Role
    let content: String
    let timestamp: Date
    let attachmentImageData: Data?
    /// Health Records the reply relied on (docs/health-records.md §26 `record_refs`), shown as
    /// "Used records" chips. Absent in older histories.
    let recordRefs: [ChatRecordRef]?

    enum CodingKeys: String, CodingKey {
        case id, role, content, timestamp, attachmentImageData
        case recordRefs = "record_refs"
    }

    init(
        id: UUID = UUID(),
        role: Role,
        content: String,
        timestamp: Date = .now,
        attachmentImageData: Data? = nil,
        recordRefs: [ChatRecordRef]? = nil
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.attachmentImageData = attachmentImageData
        self.recordRefs = recordRefs
    }
}
