import Foundation

/// One Coach conversation (docs/coach.md §7). The per-conversation state that used to live in
/// memory on `ChatStore` — the selected records, the provider override and the §30 online decision —
/// is stored here, so switching away and back restores it.
nonisolated struct Conversation: Identifiable, Equatable, Sendable {
    var id: String
    var title: String
    var createdMs: Int64
    var updatedMs: Int64
    var lastMessageMs: Int64?
    var pinned: Bool
    var archived: Bool
    var dataSources: CoachDataSwitches
    var selectedRecordIDs: [String]
    var providerOverride: String?
    var recordsOnlineDecision: String?

    init(
        id: String = UUID().uuidString.lowercased(),
        title: String = "",
        createdMs: Int64,
        updatedMs: Int64? = nil,
        lastMessageMs: Int64? = nil,
        pinned: Bool = false,
        archived: Bool = false,
        dataSources: CoachDataSwitches = .allOn,
        selectedRecordIDs: [String] = [],
        providerOverride: String? = nil,
        recordsOnlineDecision: String? = nil
    ) {
        self.id = id
        self.title = title
        self.createdMs = createdMs
        self.updatedMs = updatedMs ?? createdMs
        self.lastMessageMs = lastMessageMs
        self.pinned = pinned
        self.archived = archived
        self.dataSources = dataSources
        self.selectedRecordIDs = selectedRecordIDs
        self.providerOverride = providerOverride
        self.recordsOnlineDecision = recordsOnlineDecision
    }

    /// What the list row shows when the user has not renamed the conversation and the title could not
    /// be derived (docs/coach.md §7: an empty title is the platform's localized "New chat").
    var displayTitle: String {
        title.isEmpty ? String(localized: "New chat") : title
    }
}

/// The per-conversation data switches (docs/coach.md §8). An absent key means **on**; the switch can
/// only ever narrow what the source's own consent already permits, never grant it.
nonisolated struct CoachDataSwitches: Equatable, Sendable {
    /// Only the sources the user has explicitly turned off are stored.
    private(set) var values: [String: Bool]

    static let allOn = CoachDataSwitches(values: [:])

    init(values: [String: Bool] = [:]) {
        self.values = values.filter { CoachSource.allCases.map(\.rawValue).contains($0.key) }
    }

    func isOn(_ source: CoachSource) -> Bool { values[source.rawValue] != false }

    mutating func set(_ source: CoachSource, _ on: Bool) {
        if on {
            values.removeValue(forKey: source.rawValue)
        } else {
            values[source.rawValue] = false
        }
    }

    /// The shape `CR.resolveDataSources` expects: only the off switches, so a source added later
    /// defaults to on.
    var referenceValue: RJ {
        .obj(values.mapValues { RJ.bool($0) })
    }

    /// `{"health": false}` — only the off switches, so a new source added later defaults to on.
    var json: String? {
        guard !values.isEmpty, let data = try? JSONEncoder().encode(values) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func fromJSON(_ text: String?) -> CoachDataSwitches {
        guard let text, let data = text.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([String: Bool].self, from: data)
        else { return .allOn }
        return CoachDataSwitches(values: decoded)
    }
}

/// The four data sources Coach can read (docs/coach.md §3).
nonisolated enum CoachSource: String, CaseIterable, Sendable {
    case food
    case health
    case medications
    case records

    var title: String {
        switch self {
        case .food: String(localized: "Food & activity")
        case .health: String(localized: "Health data")
        case .medications: String(localized: "Medications")
        case .records: String(localized: "Health records")
        }
    }

    var systemImage: String {
        switch self {
        case .food: "fork.knife"
        case .health: "heart.fill"
        case .medications: "pills.fill"
        case .records: "list.clipboard"
        }
    }
}

/// A file the user attached to a turn (docs/coach.md §6). The blob lives in `CoachFileStore`; only
/// the excerpt — already redacted — is stored here, and it is exactly what was sent to the provider.
nonisolated struct ChatAttachment: Identifiable, Equatable, Sendable {
    nonisolated enum Kind: String, Sendable, CaseIterable {
        case image
        case pdf
        case text
        case note

        var systemImage: String {
            switch self {
            case .image: "photo"
            case .pdf: "doc.richtext"
            case .text: "doc.text"
            case .note: "note.text"
            }
        }

        init(raw: String?) {
            self = Kind(rawValue: raw ?? "") ?? .text
        }
    }

    var id: String
    var kind: Kind
    var filename: String
    var mimeType: String?
    var bytes: Int
    var sha256: String?
    var pageCount: Int?
    var charCount: Int?
    /// The redacted text sent to the provider; nil for an image.
    var excerpt: String?
    /// Relative to `CoachLocation.filesDirectory`, nil when the blob is gone (an import without files).
    var filePath: String?
    var thumbnailPath: String?
    var createdMs: Int64

    init(
        id: String = UUID().uuidString.lowercased(),
        kind: Kind,
        filename: String,
        mimeType: String? = nil,
        bytes: Int = 0,
        sha256: String? = nil,
        pageCount: Int? = nil,
        charCount: Int? = nil,
        excerpt: String? = nil,
        filePath: String? = nil,
        thumbnailPath: String? = nil,
        createdMs: Int64
    ) {
        self.id = id
        self.kind = kind
        self.filename = filename
        self.mimeType = mimeType
        self.bytes = bytes
        self.sha256 = sha256
        self.pageCount = pageCount
        self.charCount = charCount
        self.excerpt = excerpt
        self.filePath = filePath
        self.thumbnailPath = thumbnailPath
        self.createdMs = createdMs
    }

    /// "report.pdf · 6 pages · 8,400 characters" — what the composer chip shows (§6).
    var subtitle: String {
        var parts: [String] = []
        if let pageCount, pageCount > 0 {
            parts.append(String(localized: "\(pageCount) pages"))
        }
        if let charCount, charCount > 0 {
            parts.append(String(localized: "\(charCount) characters"))
        }
        if parts.isEmpty, bytes > 0 {
            parts.append(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file))
        }
        return parts.joined(separator: " · ")
    }
}

/// One row of the conversation list.
nonisolated struct ConversationSummary: Identifiable, Equatable, Sendable {
    var conversation: Conversation
    var snippet: String
    var messageCount: Int
    var attachmentCount: Int

    var id: String { conversation.id }
}

// MARK: - Reference views
//
// `CR` works on plain JSON rows so the same code runs on both platforms. These convert the typed
// models into exactly the shape `scripts/coach_reference.py` expects.

nonisolated extension Conversation {
    var referenceValue: RJ {
        .obj([
            "id": .str(id), "title": .str(title), "created_ms": .int(Int(createdMs)),
            "updated_ms": .int(Int(updatedMs)),
            "last_message_ms": lastMessageMs.map { RJ.int(Int($0)) } ?? .null,
            "pinned": .int(pinned ? 1 : 0), "archived": .int(archived ? 1 : 0),
            "data_sources": dataSources.referenceValue,
            "selected_record_ids": .arr(selectedRecordIDs.map { RJ.str($0) }),
            "provider_override": RJ.string(providerOverride),
        ])
    }
}

nonisolated extension ChatMessage {
    var referenceValue: RJ {
        .obj([
            "id": .str(id), "conversation_id": .str(conversationID), "seq": .int(seq),
            "variant_index": .int(variantIndex), "role": .str(role.rawValue), "content": .str(content),
            "created_ms": .int(Int(createdMs)), "updated_ms": .int(Int(updatedMs)),
            "regenerated_from": RJ.string(regeneratedFrom),
            "record_refs": .arr((recordRefs ?? []).map(\.referenceObject)),
            "attachment_ids": .arr(attachmentIDs.map { RJ.str($0) }),
        ])
    }
}

nonisolated extension ChatAttachment {
    var referenceValue: RJ {
        .obj([
            "id": .str(id), "kind": .str(kind.rawValue), "filename": .str(filename),
            "mime_type": RJ.string(mimeType), "bytes": .int(bytes), "sha256": RJ.string(sha256),
            "page_count": pageCount.map { RJ.int($0) } ?? .null,
            "char_count": charCount.map { RJ.int($0) } ?? .null,
            "excerpt": RJ.string(excerpt), "created_ms": .int(Int(createdMs)),
        ])
    }
}
