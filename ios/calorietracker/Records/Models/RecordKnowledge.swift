import Foundation

// Phase 3 "Health knowledge base" rows and enumerations (docs/health-records.md §19–§24).
// Raw values are the exact lowercase strings stored in SQLite.

nonisolated enum RecordAnalyteMethod: String, CaseIterable, Codable, Sendable {
    case catalog
    case userAlias = "user_alias"
    case user
}

/// Where `observations.observed_date` came from (§19 order).
nonisolated enum RecordObservedDateMethod: String, CaseIterable, Codable, Sendable {
    case collectionDate = "collection_date"
    case reportDate = "report_date"
    case documentDate = "document_date"
    case sortDate = "sort_date"
    case user
}

/// One `observations` row.
nonisolated struct RecordObservation: Identifiable, Hashable, Sendable {
    var id: String
    var recordID: String
    var fieldID: String?
    var analyteID: String?
    var analyteMethod: RecordAnalyteMethod?
    var rawName: String
    var valueNum: Double?
    var valueText: String
    var unit: String?
    var canonicalValue: Double?
    var canonicalUnit: String?
    var refLow: Double?
    var refHigh: Double?
    var refText: String?
    var flag: RecordResultFlag
    var observedDate: String?
    var observedDateMethod: RecordObservedDateMethod?
    var method: RecordFieldMethod
    var confidence: Double
    var state: RecordFieldState
    var sourcePage: Int?
    var sourceBBox: String?
    var evidence: String?
    var excludedFromTrends: Bool
    var createdMs: Int64
    var updatedMs: Int64

    var bbox: [Double]? { RecordObservation.decodeBox(sourceBBox) }

    var isConfirmed: Bool { state == .confirmed || state == .user }

    /// Display name: the catalog name when mapped, else the printed test name.
    func displayName(catalog: AnalyteCatalog = .shared) -> String {
        analyteID.flatMap { catalog.analyte(id: $0)?.displayName } ?? rawName
    }

    /// `"9.7 g/dL"`.
    var valueWithUnit: String {
        [valueText, unit].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    static func decodeBox(_ json: String?) -> [Double]? {
        guard let json, let data = json.data(using: .utf8),
              let values = try? JSONSerialization.jsonObject(with: data) as? [NSNumber], values.count == 4
        else { return nil }
        return values.map(\.doubleValue)
    }
}

/// A user edit of one observation (§24). `nil` leaves a column untouched.
nonisolated struct RecordObservationEdit: Sendable, Equatable {
    var valueText: String?
    /// `.some(nil)` clears the unit.
    var unit: String??
    var observedDate: String?
    /// `.some(nil)` unmaps.
    var analyteID: String??
    /// `.some(nil)` clears the range.
    var refText: String??
    var excludedFromTrends: Bool?
    /// Remember the mapping for this test name (`analyte_user_aliases`).
    var rememberAlias = false
    /// Also map existing unmapped observations with the same normalized name.
    var applyAliasToExisting = false

    init(valueText: String? = nil, unit: String?? = nil, observedDate: String? = nil, analyteID: String?? = nil, refText: String?? = nil, excludedFromTrends: Bool? = nil, rememberAlias: Bool = false, applyAliasToExisting: Bool = false) {
        self.valueText = valueText
        self.unit = unit
        self.observedDate = observedDate
        self.analyteID = analyteID
        self.refText = refText
        self.excludedFromTrends = excludedFromTrends
        self.rememberAlias = rememberAlias
        self.applyAliasToExisting = applyAliasToExisting
    }

    var changesValue: Bool { valueText != nil || unit != nil || observedDate != nil || analyteID != nil || refText != nil }
}

/// "Add value" (§24): a user observation without a source.
nonisolated struct RecordObservationDraft: Sendable, Equatable {
    var analyteID: String?
    var name: String
    var valueText: String
    var unit: String?
    var observedDate: String
    var refText: String?
}

// MARK: - Entities

nonisolated enum RecordEntityKind: String, CaseIterable, Codable, Sendable {
    case doctor
    case facility
}

nonisolated enum RecordEntityRole: String, CaseIterable, Codable, Sendable {
    case doctor
    case referrer
    case facility
}

/// One `entities` row plus how many records use it (filters).
nonisolated struct RecordEntity: Identifiable, Hashable, Sendable {
    var id: String
    var kind: RecordEntityKind
    var displayName: String
    var normalizedName: String
    var specialty: String?
    var recordCount: Int = 0
}

// MARK: - Links

nonisolated enum RecordLinkKind: String, CaseIterable, Codable, Sendable, Identifiable {
    case followUp = "follow_up"
    case prescriptionFor = "prescription_for"
    case sameEpisode = "same_episode"
    case previousReport = "previous_report"
    case related
    case splitFrom = "split_from"

    var id: String { rawValue }

    /// Kinds a user can choose in the Link record sheet.
    static let userChoices: [RecordLinkKind] = [.followUp, .prescriptionFor, .sameEpisode, .previousReport, .related]

    var title: String {
        switch self {
        case .followUp: String(localized: "Follow-up")
        case .prescriptionFor: String(localized: "Prescription for")
        case .sameEpisode: String(localized: "Same episode")
        case .previousReport: String(localized: "Previous report")
        case .related: String(localized: "Related")
        case .splitFrom: String(localized: "Same document")
        }
    }

    var systemImage: String {
        switch self {
        case .followUp: "arrow.uturn.forward.circle"
        case .prescriptionFor: "pills"
        case .sameEpisode: "link"
        case .previousReport: "clock.arrow.circlepath"
        case .related: "link"
        case .splitFrom: "rectangle.split.3x1"
        }
    }
}

nonisolated enum RecordLinkOrigin: String, CaseIterable, Codable, Sendable {
    case user
    case suggested
}

nonisolated enum RecordLinkStatus: String, CaseIterable, Codable, Sendable {
    case suggested
    case accepted
    case rejected
}

/// One `record_links` row (`aID < bID`).
nonisolated struct RecordLink: Hashable, Sendable, Identifiable {
    var aID: String
    var bID: String
    var kind: RecordLinkKind
    var origin: RecordLinkOrigin
    var status: RecordLinkStatus
    var score: Double
    var reasons: [String]
    var createdMs: Int64
    var updatedMs: Int64

    var id: String { aID + "|" + bID }

    func other(than recordID: String) -> String { aID == recordID ? bID : aID }

    /// §19: one row per unordered pair, `a_id < b_id` in string order.
    static func orderedPair(_ x: String, _ y: String) -> (String, String) { x < y ? (x, y) : (y, x) }
}

/// A link as shown on a record's detail: the other record and the stored row.
nonisolated struct RecordRelated: Hashable, Sendable, Identifiable {
    var link: RecordLink
    var record: HealthRecord
    var id: String { link.id }
}

/// Relation suggestion produced by `RecordRelationSuggester` (§22).
nonisolated struct RecordRelationSuggestion: Hashable, Sendable {
    var otherID: String
    var kind: RecordLinkKind
    var score: Double
    var reasons: [String]
}

// MARK: - Trends & search

/// One charted point (same-day duplicates collapsed, §21).
nonisolated struct RecordTrendPoint: Identifiable, Hashable, Sendable {
    /// Observation ids that collapsed into this point (first = most recent source).
    var observationIDs: [String]
    var recordIDs: [String]
    var date: String
    var value: Double
    var unit: String
    var flag: RecordResultFlag
    var refLow: Double?
    var refHigh: Double?
    var refText: String?
    var sourcePage: Int?
    var sourceBBox: String?
    var evidence: String?
    var isConfirmed: Bool

    var id: String { observationIDs.first ?? date }
    var dayDate: Date { RecordDates.date(fromDay: date) ?? .distantPast }
}

nonisolated struct RecordTrendSeries: Identifiable, Hashable, Sendable {
    /// Unit label of the series (canonical unit, or the printed unit when it can't convert).
    var unit: String
    var isCanonical: Bool
    var points: [RecordTrendPoint]
    /// Most recent point's own range, in the series unit.
    var bandLow: Double?
    var bandHigh: Double?

    var id: String { (isCanonical ? "c:" : "u:") + unit }
}

/// Observation row with its record for trend tables and value hits.
nonisolated struct RecordObservationItem: Identifiable, Hashable, Sendable {
    var observation: RecordObservation
    var record: HealthRecord
    var id: String { observation.id }
}

nonisolated struct RecordAnalyteTrend: Hashable, Sendable {
    var analyteID: String
    var series: [RecordTrendSeries]
    /// Every non-rejected observation of the analyte (excluded ones included), newest first.
    var items: [RecordObservationItem]
}

/// Detail mini trend (§21).
nonisolated struct RecordMiniTrend: Hashable, Sendable {
    var analyteID: String
    /// `"7.2 → 8.4 → 9.7"`.
    var text: String
    /// `"+1.3 g/dL since Aug 10"`, nil when unknown.
    var change: String?
    var pointCount: Int
}

/// Parsed analyte condition (§23).
nonisolated struct RecordAnalyteCondition: Hashable, Sendable, Codable {
    var analyteID: String
    /// `low`, `high`, `abnormal`, `normal`, `critical`, or nil.
    var flag: String?
    /// `>`, `>=`, `<`, `<=`, or nil.
    var op: String?
    var value: Double?
    var unit: String?
    /// The value converted to the analyte's canonical unit (reference `_q_condition_out`).
    var canonicalValue: Double?
    var canonicalUnit: String?

    var isBare: Bool { flag == nil && op == nil }
}

/// A "Values" search hit (§23).
nonisolated struct RecordValueHit: Identifiable, Hashable, Sendable {
    var observation: RecordObservation
    var record: HealthRecord
    var id: String { observation.id }
}
