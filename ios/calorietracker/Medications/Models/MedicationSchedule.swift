import Foundation

/// `frequency_kind` (docs §3). As-needed medicines have `is_prn = 1` and no schedule row.
nonisolated enum ScheduleFrequency: String, CaseIterable, Codable, Sendable, Identifiable {
    /// Every day at `times`.
    case daily
    /// `days` (ISO 1=Mon…7=Sun) at `times`.
    case weekly
    /// Every `intervalHours` from `anchorTime`; the effective slots are derived (`(anchor + k·interval) mod 24h`).
    case interval

    var id: String { rawValue }

    init(raw: String?) {
        self = raw.flatMap(ScheduleFrequency.init(rawValue:)) ?? .daily
    }

    var title: String {
        switch self {
        case .daily: String(localized: "Daily")
        case .weekly: String(localized: "Specific days")
        case .interval: String(localized: "Every few hours")
        }
    }

    /// `interval_hours` must divide 24 (docs §3).
    static let allowedIntervalHours: [Int] = [1, 2, 3, 4, 6, 8, 12, 24]
}

/// Compact JSON for `times_json` / `days_json` (`["08:00","20:00"]`, `[1,3,5]`).
nonisolated enum MedicationJSON {
    static func encode(strings: [String]) -> String {
        encode(array: strings)
    }

    static func encode(ints: [Int]) -> String {
        encode(array: ints)
    }

    static func decodeStrings(_ json: String?) -> [String] {
        guard let json, let data = json.data(using: .utf8),
              let values = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return [] }
        return values.compactMap { $0 as? String }
    }

    static func decodeInts(_ json: String?) -> [Int] {
        guard let json, let data = json.data(using: .utf8),
              let values = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return [] }
        return values.compactMap { value in
            if let number = value as? NSNumber { return number.intValue }
            if let text = value as? String { return Int(text) }
            return nil
        }
    }

    private static func encode(array: [Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: array, options: []),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }
}

/// One row of `medication_schedules`. Rows are versioned: at most one open row (`activeUntilMs == nil`)
/// per medication; edits close the current row and insert a new one, so dose logs keep the row that
/// produced them.
nonisolated struct MedicationSchedule: Identifiable, Hashable, Sendable, Codable {
    var id: String
    var medicationID: String
    var frequency: ScheduleFrequency
    /// `HH:mm` local wall-clock, unique, ascending.
    var times: [String]
    /// ISO weekdays 1=Mon…7=Sun; empty unless `frequency == .weekly`.
    var days: [Int]
    var intervalHours: Int?
    var anchorTime: String?
    var reminderEnabled: Bool
    var activeFromMs: Int64
    var activeUntilMs: Int64?
    var createdMs: Int64
    var updatedMs: Int64

    init(
        id: String = UUID().uuidString.lowercased(),
        medicationID: String,
        frequency: ScheduleFrequency = .daily,
        times: [String] = [],
        days: [Int] = [],
        intervalHours: Int? = nil,
        anchorTime: String? = nil,
        reminderEnabled: Bool = true,
        activeFromMs: Int64,
        activeUntilMs: Int64? = nil,
        createdMs: Int64,
        updatedMs: Int64
    ) {
        self.id = id
        self.medicationID = medicationID
        self.frequency = frequency
        self.times = times
        self.days = days
        self.intervalHours = intervalHours
        self.anchorTime = anchorTime
        self.reminderEnabled = reminderEnabled
        self.activeFromMs = activeFromMs
        self.activeUntilMs = activeUntilMs
        self.createdMs = createdMs
        self.updatedMs = updatedMs
    }

    var isOpen: Bool { activeUntilMs == nil }

    var timesJSON: String { MedicationJSON.encode(strings: times) }
    var daysJSON: String { MedicationJSON.encode(ints: days) }
}
