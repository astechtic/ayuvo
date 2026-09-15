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

    static let all = RecordQuery()

    var trimmedText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var hasChipFilter: Bool {
        !recordTypes.isEmpty || !categories.isEmpty || !fileTypes.isEmpty || favoritesOnly || archivedOnly || receivedOnly
    }

    var isUnfiltered: Bool { !hasChipFilter && trimmedText.isEmpty }
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
}
