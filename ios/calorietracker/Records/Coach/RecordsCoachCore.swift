import Foundation

/// A record a Coach reply relied on, persisted with the assistant message (§26 `record_refs`) and used
/// for the per-conversation selection chips (§27).
nonisolated struct ChatRecordRef: Codable, Hashable, Sendable, Identifiable {
    var recordID: String
    var title: String
    /// `sort_date` (selections, `records_get`) or the point's `observed_date` (series refs), `yyyy-MM-dd`.
    var date: String
    /// Present for selections (prompt `type_label`); not part of the persisted `record_refs` shape.
    var recordType: String?

    var id: String { recordID }

    enum CodingKeys: String, CodingKey {
        case recordID = "record_id"
        case title
        case date
        case recordType = "record_type"
    }

    init(recordID: String, title: String, date: String, recordType: String? = nil) {
        self.recordID = recordID
        self.title = title
        self.date = date
        self.recordType = recordType
    }

    init(_ record: HealthRecord) {
        self.init(recordID: record.id, title: record.title, date: record.effectiveDate, recordType: record.recordType.rawValue)
    }

    /// `{record_id, title, date}` (reference `record_refs` item).
    var referenceObject: RJ { .obj(["record_id": .str(recordID), "title": .str(title), "date": .str(date)]) }

    init?(referenceObject o: RJ) {
        guard let id = o["record_id"].string else { return nil }
        self.init(recordID: id, title: o["title"].string ?? "", date: o["date"].string ?? "")
    }
}

/// App-facing helpers around the Phase 4 reference port (`RecordsReferenceCoach.swift`).
nonisolated enum RecordsCoach {
    static let maxSelected = 10
    static let searchDefaultLimit = 10
    static let searchLimitCap = 20
    static let searchValuesCap = 20
    static let highlightsPerRecord = 3
    static let textExcerptLimit = 2_000
    static let seriesPointCap = 100
    static let packMaxRecords = 3
    static let packBudget = 1_800

    /// `type_labels` passed to the prompt / packing builders: the English stored labels (§8.1). The model
    /// reads them, so they are not localized.
    static var typeLabels: [String: String] { RR.typeLabel }

    static func prefixScalars(_ text: String, _ limit: Int) -> String {
        guard text.unicodeScalars.count > limit else { return text }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: text.unicodeScalars.prefix(limit))
        return String(view)
    }

    static func errorObject(_ key: String, _ values: [String: String] = [:]) -> RJ { RR.coachError(key, values) }

    /// Refs one successful tool result adds (§26): `records_get` → its record, series → each point's record.
    static func refs(tool: String, payload: RJ) -> [ChatRecordRef] {
        RR.recordRefs(toolCalls: [.obj(["name": .str(tool), "result": payload])], packed: nil).compactMap(ChatRecordRef.init(referenceObject:))
    }

    static func mergeRefs(_ existing: [ChatRecordRef], _ added: [ChatRecordRef]) -> [ChatRecordRef] {
        var result = existing
        for ref in added where !result.contains(where: { $0.recordID == ref.recordID }) {
            result.append(ref)
        }
        return result
    }

    /// `## Data available` lines (§26) from the reference prompt pieces and the user message.
    static func dataAvailableLines(prompt: RJ, message: String) -> [String] {
        if let line = prompt["available_line"].string { return [line] }
        if let line = prompt["not_available_line"].string, RR.mentionsRecords(message) { return [line] }
        return []
    }
}
