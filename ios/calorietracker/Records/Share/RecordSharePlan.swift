import Foundation

// Phase 5 "Sharing" model (docs/health-records.md §34, plan §3.12).
// A `SharePlan` is what the "What will be shared" screen edits and `RecordShareBuilder` executes.

/// Field checkboxes of the structured summary (§34 `summary_fields`).
nonisolated enum RecordSummaryField: String, CaseIterable, Codable, Sendable, Identifiable {
    case doctor
    case facility
    case patientName = "patient_name"
    case dates
    case testResults = "test_results"
    case medications
    case diagnoses
    case recommendations

    var id: String { rawValue }

    /// Everything except the patient's name: sharing a report rarely needs to re-state it.
    static let defaults: [RecordSummaryField] = [.doctor, .facility, .dates, .testResults, .medications, .diagnoses, .recommendations]

    var title: String {
        switch self {
        case .doctor: String(localized: "Doctor")
        case .facility: String(localized: "Hospital or lab")
        case .patientName: String(localized: "Patient name")
        case .dates: String(localized: "Dates")
        case .testResults: String(localized: "Test results")
        case .medications: String(localized: "Medications")
        case .diagnoses: String(localized: "Diagnoses")
        case .recommendations: String(localized: "Recommendations")
        }
    }
}

/// Identifier classes the redactor paints over (§34 `redactions`).
nonisolated enum RecordRedactionClass: String, CaseIterable, Codable, Sendable, Identifiable {
    case name
    case address
    case phone
    case patientID = "patient_id"
    case insuranceID = "insurance_id"
    case otherIDs = "other_ids"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .name: String(localized: "Name")
        case .address: String(localized: "Address")
        case .phone: String(localized: "Phone")
        case .patientID: String(localized: "Patient ID")
        case .insuranceID: String(localized: "Insurance ID")
        case .otherIDs: String(localized: "Other detected IDs")
        }
    }
}

/// Which pages of a record go into the share: every page, or an explicit 0-based subset.
nonisolated enum RecordSharePages: Hashable, Sendable {
    case all
    case pages([Int])

    var explicit: [Int]? {
        if case .pages(let list) = self { return list }
        return nil
    }

    func contains(_ index: Int, pageCount: Int) -> Bool {
        switch self {
        case .all: return true
        case .pages(let list): return list.contains(index)
        }
    }

    func resolved(pageCount: Int) -> [Int] {
        switch self {
        case .all: return Array(0..<max(pageCount, 0))
        case .pages(let list): return list.sorted()
        }
    }
}

/// `SharePlan` of §34. `pages` is keyed by record id; a missing entry means every page.
nonisolated struct RecordSharePlan: Hashable, Sendable {
    var recordIDs: [String]
    var pages: [String: RecordSharePages] = [:]
    var includeOriginal = true
    var includeSummary = true
    var summaryFields: Set<RecordSummaryField> = Set(RecordSummaryField.defaults)
    var includeHighlights = false
    var includeNotes = false
    var redactions: Set<RecordRedactionClass> = []

    init(recordIDs: [String]) {
        self.recordIDs = recordIDs
    }

    var isRedacting: Bool { !redactions.isEmpty }

    func pages(for recordID: String) -> RecordSharePages { pages[recordID] ?? .all }

    /// Sorted raw values, the order the reference and the vectors use.
    var summaryFieldValues: [String] {
        RecordSummaryField.allCases.filter { summaryFields.contains($0) }.map(\.rawValue)
    }

    var redactionValues: [String] {
        RecordRedactionClass.allCases.filter { redactions.contains($0) }.map(\.rawValue)
    }

    /// Nothing selected at all — the Share button stays disabled.
    var isEmpty: Bool { recordIDs.isEmpty || (!includeOriginal && !includeSummary) }
}

/// One file the builder produced, ready for `UIActivityViewController`.
nonisolated struct RecordShareItem: Identifiable, Hashable, Sendable {
    enum Kind: String, Sendable {
        case summary
        case original
        case redacted
        case pageSubset = "page_subset"
    }

    var url: URL
    var kind: Kind
    var recordID: String?
    var title: String
    /// Page-1 preview written next to the file (PNG), when one could be rendered.
    var previewPath: URL?
    var byteCount: Int64

    var id: URL { url }
}

/// Result of `RecordShareBuilder.build`: the files plus any per-page warnings (§34).
nonisolated struct RecordShareBundle: Sendable {
    var items: [RecordShareItem] = []
    /// §38: the summary also travels as the share sheet's own text item.
    var summaryText: String?
    var warnings: [String] = []
    /// Records that contributed at least one file (their `shared_count` is bumped).
    var sharedRecordIDs: [String] = []

    var isEmpty: Bool { items.isEmpty }
}
