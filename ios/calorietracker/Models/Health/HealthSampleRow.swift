import Foundation

/// Canonical sleep stage codes shared with Android (`health_samples.category_value`
/// for `type_id = 'sleep'`).
nonisolated enum HealthSleepStage: Int, Sendable, CaseIterable {
    case inBed = 0
    case asleepUnspecified = 1
    case awake = 2
    case light = 3
    case deep = 4
    case rem = 5
    case outOfBed = 6

    var isAsleep: Bool {
        switch self {
        case .asleepUnspecified, .light, .deep, .rem: return true
        case .inBed, .awake, .outOfBed: return false
        }
    }

    var englishName: String {
        switch self {
        case .inBed: return "In Bed"
        case .asleepUnspecified: return "Asleep"
        case .awake: return "Awake"
        case .light: return "Core"
        case .deep: return "Deep"
        case .rem: return "REM"
        case .outOfBed: return "Out of Bed"
        }
    }
}

nonisolated enum HealthRowOrigin: Int, Sendable {
    case platform = 0
    case fileImport = 1
    case localAdapter = 2
}

/// One `health_samples` row. Field names follow the shared DDL exactly so the SQL
/// layer, the exporter and the importer stay trivially aligned.
nonisolated struct HealthSampleRow: Sendable, Hashable, Identifiable {
    var id: String
    var typeID: String
    var startMs: Int64
    var endMs: Int64
    var startOffsetS: Int?
    var endOffsetS: Int?
    var localDay: String
    var value: Double?
    var value2: Double?
    var value3: Double?
    var valueText: String?
    var unit: String
    var categoryValue: Int?
    var title: String?
    var extraJSON: String?
    var count: Int = 1
    var sourceID: String
    var device: String?
    var deviceType: Int?
    var recordingMethod: Int?
    var clientRecordID: String?
    var origin: Int = HealthRowOrigin.platform.rawValue
    var deleted: Int = 0
    var updatedMs: Int64

    var startDate: Date { Date(timeIntervalSince1970: Double(startMs) / 1000) }
    var endDate: Date { Date(timeIntervalSince1970: Double(endMs) / 1000) }
    var durationSeconds: Double { max(0, Double(endMs - startMs) / 1000) }
    var isDeleted: Bool { deleted != 0 }

    /// Parsed `extra_json`, or an empty object.
    var extra: [String: Any] {
        guard let extraJSON, let data = extraJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return object
    }
}

nonisolated struct HealthSourceRow: Sendable, Hashable, Identifiable {
    var id: String
    var name: String
    var deviceModel: String?
    var deviceType: Int?
    var lastSeenMs: Int64?
}

nonisolated struct HealthDailyRollupRow: Sendable, Hashable {
    var typeID: String
    var day: String
    var tz: String
    var sum: Double?
    var avg: Double?
    var min: Double?
    var max: Double?
    var count: Int = 0
    var lastValue: Double?
    var lastAtMs: Int64?
    var v2Avg: Double?
    var v2Min: Double?
    var v2Max: Double?
    var durationS: Double?
    var ownSum: Double?
    var fromPlatformAggregate: Int = 0
}

nonisolated struct HealthSyncStateRow: Sendable, Hashable {
    var typeID: String
    var cursor: String?
    var cursorIssuedMs: Int64?
    var lastSyncMs: Int64?
    var earliestAuthorizedMs: Int64?
    var earliestProbeMs: Int64?
    var backfillFloorMs: Int64?
    var oldestBackfilledMs: Int64?
    var backfillDone: Int = 0
    var backfillWithHistory: Int = 0
    var status: String = "idle"
    var lastError: String?
    var lastErrorMs: Int64?
    var ipcCallsTotal: Int = 0

    init(typeID: String) {
        self.typeID = typeID
    }

    var isImporting: Bool { status == "importing" || status == "bootstrapping" }
    var isLimited: Bool { status == "limited" }
    var isLocked: Bool { status == "locked" }
    var isError: Bool { status.hasPrefix("error") }
}

nonisolated struct HealthTypeMetaRow: Sendable, Hashable {
    var typeID: String
    var category: String
    var kind: String
    var aggregation: String
    var unit: String
    var displayName: String?
    var platform: String?
    var nativeID: String?
}

/// Aggregate over `health_samples` for one type (hub rows, Coach type list).
nonisolated struct HealthTypeSummary: Sendable, Hashable, Identifiable {
    var typeID: String
    var count: Int
    var firstStartMs: Int64?
    var lastEndMs: Int64?
    var latest: HealthSampleRow?

    var id: String { typeID }
}

/// A night of sleep derived at read time by `HealthSleepAnalysis`.
nonisolated struct HealthSleepNight: Sendable, Hashable, Identifiable {
    var nightOf: String
    var startMs: Int64
    var endMs: Int64
    var inBedS: Double
    var asleepS: Double
    var lightS: Double
    var deepS: Double
    var remS: Double
    var awakeS: Double
    var source: String

    var id: String { nightOf }
    var startDate: Date { Date(timeIntervalSince1970: Double(startMs) / 1000) }
    var endDate: Date { Date(timeIntervalSince1970: Double(endMs) / 1000) }
}

/// Everything one sync page commits atomically.
nonisolated struct HealthCommitPage: Sendable {
    var rows: [HealthSampleRow] = []
    var deletedIDs: [String] = []
    var sources: [HealthSourceRow] = []
    var syncState: HealthSyncStateRow?
    var rollups: [HealthDailyRollupRow] = []
}
