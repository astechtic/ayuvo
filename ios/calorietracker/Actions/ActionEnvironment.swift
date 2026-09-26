import Foundation

/// What an action runs against. `live()` is the app; tests pass an isolated `UserDefaults` suite, a
/// fixed clock and no side effects. Stores are always fresh, non-observing instances (the
/// `SiriLoggingService` pattern): intents may run before any SwiftUI view exists, and the app's live
/// stores reload through each store's external-change notification after a write.
@MainActor
struct ActionEnvironment {
    var defaults: UserDefaults
    var now: () -> Date
    var calendar: Calendar
    /// HealthKit writes, widget snapshots, notification rescheduling and change broadcasts.
    var sideEffects: Bool
    var healthRuntime: HealthDataRuntime?
    var medicationsRuntime: MedicationsRuntime?
    var recordsDatabase: () async -> RecordsDatabase?
    var analyzeFood: (String) async throws -> GeminiService.FoodAnalysis

    static func live() -> ActionEnvironment {
        ActionEnvironment(
            defaults: .standard,
            now: { Date() },
            calendar: .current,
            sideEffects: true,
            healthRuntime: .shared,
            medicationsRuntime: .shared,
            recordsDatabase: { await ActionLiveContext.shared.recordsDatabase() },
            analyzeFood: { try await GeminiService.analyzeTextInput(description: $0) }
        )
    }

    static func testing(defaults: UserDefaults, now: Date, calendar: Calendar) -> ActionEnvironment {
        ActionEnvironment(
            defaults: defaults,
            now: { now },
            calendar: calendar,
            sideEffects: false,
            healthRuntime: nil,
            medicationsRuntime: nil,
            recordsDatabase: { nil },
            analyzeFood: { _ in throw ActionError.unavailable(String(localized: "No AI provider in tests.")) }
        )
    }

    var nowDate: Date { now() }
    var nowMs: Int64 { Self.ms(now()) }
    var zone: MetricsReference.Zone { MetricsReference.Zone(calendar: calendar) }
    var weekStart: MetricsReference.WeekStart { ActivitySettings.weekStart(defaults: defaults) }

    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    // MARK: - Unit preferences (catalog enum values)

    var massUnit: String {
        (WeightUnit(rawValue: defaults.string(forKey: WeightUnit.storageKey) ?? "") ?? .lbs) == .kg ? "kg" : "lb"
    }

    var volumeUnit: String {
        (WaterUnit(rawValue: defaults.string(forKey: WaterSettings.unitKey) ?? "") ?? .defaultUnit) == .fluidOunces ? "floz" : "ml"
    }

    var lengthUnit: String {
        (HeightUnit(rawValue: defaults.string(forKey: HeightUnit.storageKey) ?? "") ?? .ftin) == .cm ? "cm" : "in"
    }

    var prefs: [String: String] {
        ["mass_unit": massUnit, "volume_unit": volumeUnit, "length_unit": lengthUnit]
    }

    // MARK: - Stores

    func foodStore() -> FoodStore { FoodStore(observesExternalChanges: false, defaults: defaults) }
    func waterStore() -> WaterStore { WaterStore(defaults: defaults, observesExternalChanges: false) }
    func fastingStore() -> FastingStore { FastingStore(defaults: defaults, observesExternalChanges: false) }
    func weightStore() -> WeightStore { WeightStore(observesExternalChanges: false, defaults: defaults) }
    func bodyFatStore() -> BodyFatStore { BodyFatStore(defaults: defaults, observesExternalChanges: false) }
    func measurementStore() -> BodyMeasurementStore { BodyMeasurementStore(defaults: defaults, observesExternalChanges: false) }
    func workoutStore() -> StrengthWorkoutStore { StrengthWorkoutStore(defaults: defaults, observesExternalChanges: false) }
    func importedWorkoutStore() -> ImportedHealthWorkoutStore { ImportedHealthWorkoutStore(defaults: defaults) }

    var profile: UserProfile? { UserProfile.load() }

    var waterGoalMl: Int {
        let stored = defaults.integer(forKey: WaterSettings.dailyGoalKey)
        return stored > 0 ? stored : WaterSettings.defaultDailyGoalMl
    }

    var healthSyncEnabled: Bool { defaults.bool(forKey: "healthKitEnabled") }
}

/// Live app objects an action may use when the app is running (registered by `ContentView`).
@MainActor
final class ActionLiveContext {
    static let shared = ActionLiveContext()

    weak var recordsStore: RecordsStore?
    private var fallbackRecords: RecordsDatabase?

    /// The app's records database, or one opened just for this action when the UI never launched.
    func recordsDatabase() async -> RecordsDatabase? {
        if let store = recordsStore, let repository = await store.openIfNeeded() { return repository.database }
        if let fallbackRecords { return fallbackRecords }
        let url = RecordsLocation.databaseURL()
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        fallbackRecords = try? await RecordsDatabase.open(url: url)
        return fallbackRecords
    }
}
