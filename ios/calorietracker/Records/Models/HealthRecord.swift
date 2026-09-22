import Foundation

/// One row of `records` (schema v1). `seq` is the SQLite rowid alias and the FTS docid.
nonisolated struct HealthRecord: Identifiable, Hashable, Sendable {
    var seq: Int64 = 0
    var id: String
    var parentID: String?
    var pageStart: Int?
    var pageEnd: Int?
    var title: String
    var recordType: RecordType = .other
    var category: RecordCategory = .other
    var source: RecordSource
    var importMethod: RecordImportMethod
    var sourceApp: String?
    var originalFilename: String?
    var createdMs: Int64
    var updatedMs: Int64
    /// `yyyy-MM-dd`, nil when unknown.
    var documentDate: String?
    var documentDatePrecision: RecordDatePrecision?
    var documentDateMethod: RecordDateMethod?
    /// Contract §5: `document_date`, else the device-local day of `created_ms` at import.
    /// Empty means "not resolved yet"; `RecordsDatabase.insert` fills it.
    var sortDate: String = ""
    var mimeType: String
    var fileType: RecordFileType
    var fileSize: Int64 = 0
    var pageCount: Int = 0
    /// Relative to the records files root.
    var filePath: String?
    var thumbnailPath: String?
    var checksumSHA256: String?
    var processingStatus: RecordProcessingStatus = .saved
    var processingError: String?
    var reviewStatus: RecordReviewStatus = .none
    var favorite: Bool = false
    var archived: Bool = false
    var notes: String?
    // Schema v2 (migrations/002_intelligence.sql)
    /// 64-bit dHash of the first page, 16 hex digits.
    var phash: String?
    /// 64 MinHash values (hex, comma-separated).
    var textSignature: String?
    var aiModeUsed: RecordAIModeUsed = .none
    var aiProvider: String?
    var typeConfidence: Double?
    var typeMethod: RecordFieldMethod?
    // Schema v4 (migrations/004_sharing.sql, §33)
    /// Times this record was included in a completed share.
    var sharedCount: Int = 0
    var lastSharedMs: Int64?

    /// Split child (shares the parent's file; the viewer shows `page_start…page_end`).
    var isSplitChild: Bool { parentID != nil && pageStart != nil }

    var isProcessing: Bool {
        switch processingStatus {
        case .queued, .extractingText, .analyzing: true
        default: false
        }
    }

    /// The stored timeline day (`sort_date`), falling back to the same rule for unsaved rows.
    var effectiveDate: String {
        if !sortDate.isEmpty { return sortDate }
        return documentDate ?? RecordDates.localDayString(ms: createdMs)
    }

    /// `yyyy-MM` of `sort_date`, the timeline month group.
    var monthKey: String { String(effectiveDate.prefix(7)) }

    var isReceived: Bool { source.isReceived }

    var displayDate: Date? { RecordDates.date(fromDay: effectiveDate) }
}

/// One `record_pages` row.
nonisolated struct RecordPage: Hashable, Sendable {
    var recordID: String
    var pageIndex: Int
    var text: String?
    var textSource: RecordPageTextSource
    var ocrConfidence: Double?
    var width: Int?
    var height: Int?
    var blocksJSON: String?
}

/// Record plus the rows the detail screen needs.
nonisolated struct RecordDetail: Hashable, Sendable {
    var record: HealthRecord
    var tags: [String]
    var pages: [RecordPage]
    var fields: [RecordField] = []
    var highlights: [RecordHighlight] = []
    var job: RecordProcessingJob?
    var duplicates: [RecordDuplicateCandidate] = []
    var splitProposal: RecordSplitProposal?
    /// Parent of a split child, or children of an accepted split parent.
    var parent: HealthRecord?
    var children: [HealthRecord] = []
    // Phase 3
    /// Non-rejected observations in field order, user-added ones last.
    var observations: [RecordObservation] = []
    /// Mini trends keyed by observation id (analytes with ≥ 2 trend points).
    var miniTrends: [String: RecordMiniTrend] = [:]
    /// Links other than rejected suggestions: accepted first, then suggestions by score.
    var related: [RecordRelated] = []

    var visibleFields: [RecordField] { fields.filter { $0.state != .rejected } }
    var awaitingConsent: Bool { job?.awaitingConsent == true }
}

/// Editable fields of the detail screen. `nil` leaves a column untouched.
nonisolated struct RecordPatch: Sendable, Equatable {
    var title: String?
    var recordType: RecordType?
    var category: RecordCategory?
    /// `.some(nil)` clears the date.
    var documentDate: String??
    var notes: String??
    var favorite: Bool?
    var archived: Bool?

    init(
        title: String? = nil,
        recordType: RecordType? = nil,
        category: RecordCategory? = nil,
        documentDate: String?? = nil,
        notes: String?? = nil,
        favorite: Bool? = nil,
        archived: Bool? = nil
    ) {
        self.title = title
        self.recordType = recordType
        self.category = category
        self.documentDate = documentDate
        self.notes = notes
        self.favorite = favorite
        self.archived = archived
    }
}

/// Browse filters (Phase 1 chips + search text).
nonisolated struct RecordQuery: Hashable, Sendable {
    var text: String = ""
    var recordTypes: Set<RecordType> = []
    var categories: Set<RecordCategory> = []
    var fileTypes: Set<RecordFileType> = []
    var favoritesOnly = false
    /// Archived records are hidden unless this is on (then only archived rows show).
    var archivedOnly = false
    var receivedOnly = false
    // Phase 2 filters (Filters sheet + parsed universal-search chips).
    var needsReviewOnly = false
    /// Inclusive `sort_date` bounds, `yyyy-MM-dd`.
    var dateFrom: String?
    var dateTo: String?
    /// Folded prefix match against `doctor_name` / `facility` fields.
    var doctor: String?
    var facility: String?
    var flags: Set<RecordQueryFlag> = []
    var tags: Set<String> = []
    var aiProcessedOnly = false
    var userConfirmedOnly = false
    // Phase 3: entity pickers and analyte conditions (§23).
    var doctorEntityID: String?
    var facilityEntityID: String?
    /// Records with a matching observation for every non-bare condition.
    var analyteConditions: [RecordAnalyteCondition] = []

    var filteringAnalyteConditions: [RecordAnalyteCondition] { analyteConditions.filter { !$0.isBare } }

    static let all = RecordQuery()

    var trimmedText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var hasAdvancedFilter: Bool {
        needsReviewOnly || dateFrom != nil || dateTo != nil || !(doctor ?? "").isEmpty || !(facility ?? "").isEmpty
            || !flags.isEmpty || !tags.isEmpty || aiProcessedOnly || userConfirmedOnly
            || doctorEntityID != nil || facilityEntityID != nil || !filteringAnalyteConditions.isEmpty
    }

    var hasChipFilter: Bool {
        !recordTypes.isEmpty || !categories.isEmpty || !fileTypes.isEmpty || favoritesOnly || archivedOnly || receivedOnly || hasAdvancedFilter
    }

    var isUnfiltered: Bool { !hasChipFilter && trimmedText.isEmpty }
}

/// `flags` filter of a query (§17): abnormal matches any non-normal flag.
nonisolated enum RecordQueryFlag: String, CaseIterable, Codable, Sendable {
    case abnormal
    case low
    case high
    case critical

    var storedFlags: [RecordResultFlag] {
        switch self {
        case .abnormal: [.low, .high, .criticalLow, .criticalHigh, .abnormal]
        case .low: [.low, .criticalLow]
        case .high: [.high, .criticalHigh]
        case .critical: [.criticalLow, .criticalHigh]
        }
    }

    var title: String {
        switch self {
        case .abnormal: String(localized: "Abnormal")
        case .low: String(localized: "Low")
        case .high: String(localized: "High")
        case .critical: String(localized: "Critical")
        }
    }
}

/// Keyset cursor `(sort_date, created_ms, seq)` for contract §5 ordering.
nonisolated struct RecordCursor: Hashable, Sendable {
    var sortDate: String
    var createdMs: Int64
    var seq: Int64

    init(sortDate: String, createdMs: Int64, seq: Int64) {
        self.sortDate = sortDate
        self.createdMs = createdMs
        self.seq = seq
    }

    init(_ record: HealthRecord) {
        self.init(sortDate: record.effectiveDate, createdMs: record.createdMs, seq: record.seq)
    }
}

nonisolated enum RecordDates {
    static func makeFormatter(timeZone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }

    /// Device-local calendar day of an epoch-ms instant (contract §5 `sort_date` fallback).
    static func localDayString(ms: Int64, timeZone: TimeZone = .current) -> String {
        dayString(from: Date(timeIntervalSince1970: Double(ms) / 1000), timeZone: timeZone)
    }

    /// Local-calendar day string for dates the user sees (metadata dates, edited dates).
    static func dayString(from date: Date, timeZone: TimeZone = .current) -> String {
        makeFormatter(timeZone: timeZone).string(from: date)
    }

    /// Noon of that day in the current zone, so formatting never slips a day.
    static func date(fromDay day: String, timeZone: TimeZone = .current) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12))
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Months of records whose "Important highlights" still show (contract §15 display window).
    static let highlightWindowMonths = 6

    /// `yyyy-MM-dd` plus `months` calendar months (negative allowed); the day clamps to the target
    /// month's last day (2026-08-31 − 6 months → 2026-02-28), like `Calendar.date(byAdding:)`.
    static func addingMonths(_ months: Int, to day: String) -> String? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        let total = parts[0] * 12 + (parts[1] - 1) + months
        let year = Int((Double(total) / 12).rounded(.down))
        let month = total - year * 12 + 1
        let day = min(parts[2], daysInMonth(year: year, month: month))
        return String(format: "%04d-%02d-%02d", year, month, day)
    }

    private static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 2: return (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// First local day whose records still show important highlights: today minus 6 calendar months.
    static func highlightsSince(today: String) -> String? {
        addingMonths(-highlightWindowMonths, to: today)
    }

    /// `highlightsSince` for the device-local today.
    static func highlightsSince(now: Date = Date(), timeZone: TimeZone = .current) -> String? {
        highlightsSince(today: dayString(from: now, timeZone: timeZone))
    }
}
