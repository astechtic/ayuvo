import Foundation

/// One `cycle_periods` row (docs/cycle-tracking.md §2). `endDay` nil = ongoing.
nonisolated struct CyclePeriodRecord: Sendable, Hashable, Identifiable {
    var id: String
    var startDay: String
    var endDay: String?
    /// `{"healthkit": [uuid…], "health_connect": [clientRecordId…]}` of the samples Ayuvo wrote for this period.
    var platformIDsJSON: String = "{}"
    /// `pending` | `synced` | `failed` | `off`.
    var syncState: String = CycleSyncState.pending
    var createdMs: Int64
    var updatedMs: Int64
    var deleted: Bool = false

    var engineInput: CyclePeriodInput { CyclePeriodInput(id: id, start: startDay, end: endDay, source: "app") }

    static func newID() -> String { "local:" + UUID().uuidString.lowercased() }
}

/// One `cycle_day_logs` row. A deleted row keeps its day as a tombstone with every field blank.
nonisolated struct CycleDayLogRecord: Sendable, Hashable, Identifiable {
    var day: String
    var flow: String?
    var pain: Int?
    var painLocations: [String] = []
    var symptoms: [String] = []
    var moods: [String] = []
    var note: String?
    var platformIDsJSON: String = "{}"
    var syncState: String = CycleSyncState.pending
    var updatedMs: Int64
    var deleted: Bool = false

    var id: String { day }

    /// No flow, pain, symptom, mood or note.
    var isEmpty: Bool {
        flow == nil && pain == nil && painLocations.isEmpty && symptoms.isEmpty && moods.isEmpty
            && (note?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }

    var engineInput: CycleLogInput {
        CycleLogInput(day: day, flow: flow, pain: pain, painLocations: painLocations, symptoms: symptoms, moods: moods)
    }
}

/// The single `cycle_settings` row. Lengths nil = the config default.
nonisolated struct CycleSettingsRecord: Sendable, Hashable {
    var setupDone: Bool = false
    var cycleLength: Int?
    var periodLength: Int?
    var lutealLength: Int?
    /// Reminders, fertility display, Health sync and lock-screen detail (`CycleSettingsOptions`).
    var settingsJSON: String = "{}"
    var updatedMs: Int64 = 0

    var options: CycleSettingsOptions {
        get { CycleSettingsOptions(json: settingsJSON) }
        set { settingsJSON = newValue.jsonText }
    }

    var engineInput: CycleSettingsInput {
        let o = options
        return CycleSettingsInput(cycleLength: cycleLength, periodLength: periodLength, lutealLength: lutealLength,
                                  reminders: CycleReminderInput(periodSoon: o.periodSoon, daysBefore: o.daysBefore,
                                                                periodEnd: o.periodEnd, daily: o.daily))
    }
}

/// `cycle_settings.settings_json`: `{"reminders": {"period_soon", "days_before", "period_end", "daily", "time",
/// "lock_screen_details"}, "show_fertility", "health_sync"}` (docs/cycle-tracking.md §5, §6). Unknown keys survive.
nonisolated struct CycleSettingsOptions: Sendable, Equatable {
    var periodSoon: Bool = true
    var daysBefore: Int = 2
    var periodEnd: Bool = true
    var daily: Bool = false
    /// 'HH:mm' local time of reminders.
    var time: String = "09:00"
    var lockScreenDetails: Bool = false
    var showFertility: Bool = true
    var healthSync: Bool = false
    var extra: [String: RJ] = [:]

    init() {}

    init(json: String) {
        guard let root = try? VitalsJSON.parse(json), var o = root.object else { return }
        if case .obj(let r)? = o["reminders"] {
            periodSoon = r["period_soon"]?.bool ?? periodSoon
            daysBefore = r["days_before"].flatMap { $0.double.map { Int($0) } } ?? daysBefore
            periodEnd = r["period_end"]?.bool ?? periodEnd
            daily = r["daily"]?.bool ?? daily
            time = r["time"]?.string ?? time
            lockScreenDetails = r["lock_screen_details"]?.bool ?? lockScreenDetails
        }
        showFertility = o["show_fertility"]?.bool ?? showFertility
        healthSync = o["health_sync"]?.bool ?? healthSync
        o["reminders"] = nil
        o["show_fertility"] = nil
        o["health_sync"] = nil
        extra = o
    }

    var json: RJ {
        var o = extra
        o["reminders"] = .obj(["period_soon": .bool(periodSoon), "days_before": .int(daysBefore), "period_end": .bool(periodEnd),
                               "daily": .bool(daily), "time": .str(time), "lock_screen_details": .bool(lockScreenDetails)])
        o["show_fertility"] = .bool(showFertility)
        o["health_sync"] = .bool(healthSync)
        return .obj(o)
    }

    var jsonText: String { VitalsJSON.encode(json) }

    static func == (a: CycleSettingsOptions, b: CycleSettingsOptions) -> Bool { a.jsonText == b.jsonText }
}

nonisolated enum CycleSyncState {
    static let pending = "pending"
    static let synced = "synced"
    static let failed = "failed"
    /// Health sync is off; nothing to write.
    static let off = "off"
}
