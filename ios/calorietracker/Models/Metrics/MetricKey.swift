import Foundation

/// App-owned metrics of the shared catalog (`app:<rawValue>`), docs/ui-structure.md §4.
nonisolated enum AppMetric: String, CaseIterable, Hashable, Sendable {
    case calories
    case protein
    case carbs
    case fat
    case fiber
    case water
    case fasting
    case weight
    case bodyFat = "body_fat"
    case workouts
    case workoutMinutes = "workout_minutes"
    case workoutBurn = "workout_burn"

    var key: String { "app:\(rawValue)" }

    init?(key: String) {
        guard key.hasPrefix("app:") else { return nil }
        self.init(rawValue: String(key.dropFirst(4)))
    }
}

/// Any metric the UI can chart: an app metric or a health registry id (unprefixed, e.g. `steps`).
nonisolated enum MetricKey: Hashable, Sendable, Identifiable {
    case app(AppMetric)
    case health(String)

    /// Storage / pin id: `app:calories` or `steps`.
    var id: String {
        switch self {
        case .app(let metric): metric.key
        case .health(let typeID): typeID
        }
    }

    /// Nil for blank strings and unknown `app:` keys.
    init?(pinID: String) {
        let trimmed = pinID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.hasPrefix("app:") {
            guard let metric = AppMetric(key: trimmed) else { return nil }
            self = .app(metric)
        } else {
            self = .health(trimmed)
        }
    }
}

/// Activity / Summary preferences (cloud-backed, docs/ui-structure.md §4 `prefs`).
nonisolated enum ActivitySettings {
    static let dailyStepGoalKey = "dailyStepGoal"
    static let defaultDailyStepGoal = 10_000
    static let stepGoalRange = 1_000...50_000
    static let stepGoalStep = 500
    static let summaryChecklistDismissedKey = "summaryChecklistDismissed"
    static let weekStartsOnMondayKey = "weekStartsOnMonday"

    static func dailyStepGoal(defaults: UserDefaults = .standard) -> Int {
        guard defaults.object(forKey: dailyStepGoalKey) != nil else { return defaultDailyStepGoal }
        return min(max(defaults.integer(forKey: dailyStepGoalKey), stepGoalRange.lowerBound), stepGoalRange.upperBound)
    }

    /// The app's week-start preference (default Monday); never the locale.
    static func weekStart(defaults: UserDefaults = .standard) -> MetricsReference.WeekStart {
        guard defaults.object(forKey: weekStartsOnMondayKey) != nil else { return .monday }
        return defaults.bool(forKey: weekStartsOnMondayKey) ? .monday : .sunday
    }
}

/// Summary favourites (`summaryFavourites`, cloud-backed `[String]`), migrated once from the legacy
/// `healthHomeTiles` list with the shared `favourite_pins_migrate` rule (docs §7.9).
nonisolated enum MetricPins {
    static let key = "summaryFavourites"
    static let legacyKey = "healthHomeTiles"

    static var defaultIDs: [String] {
        MetricsReference.favouritePinsMigrate(newRaw: nil, legacyRaw: nil, knownHealthIDs: knownHealthIDs, max: max).favourites
    }

    static var max: Int { MetricCatalogData.shared.favourites.max }

    static var knownHealthIDs: [String] { HealthMetricRegistry.iOSTypes.map(\.id) }

    /// Reads the favourites, running the one-time migration (and persisting its result) when the
    /// new key has never been written.
    static func load(defaults: UserDefaults) -> [String] {
        let newRaw = defaults.object(forKey: key) == nil ? nil : (defaults.stringArray(forKey: key) ?? []).joined(separator: ",")
        let legacyRaw = defaults.object(forKey: legacyKey) == nil ? nil : (defaults.stringArray(forKey: legacyKey) ?? []).joined(separator: ",")
        let result = MetricsReference.favouritePinsMigrate(newRaw: newRaw, legacyRaw: legacyRaw, knownHealthIDs: knownHealthIDs, max: max)
        if newRaw == nil {
            defaults.set(result.favourites, forKey: key)
        }
        return result.favourites
    }

    static func save(_ ids: [String], defaults: UserDefaults) {
        defaults.set(Array(ids.prefix(max)), forKey: key)
    }
}
