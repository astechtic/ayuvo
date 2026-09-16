import Foundation

/// UserDefaults keys and constants for the Medications feature (same shape as `WaterSettings`).
/// Every `medication*` key is excluded from the cloud backup (`CloudBackupPolicy`).
nonisolated enum MedicationSettings {
    /// Master switch for dose reminders (under the app's global `notificationsEnabled`).
    static let remindersEnabledKey = "medicationRemindersEnabled"
    /// Minutes applied by the notification "Snooze" action; the in-app sheet offers every option.
    static let snoozeMinutesKey = "medicationSnoozeMinutes"
    /// Pending route written by the notification tap handler, consumed by `ContentView`.
    static let pendingRouteKey = "medication.pending"

    static let snoozeOptions: [Int] = [10, 30, 60]
    static let defaultSnoozeMinutes = 10
    static let defaultRemindersEnabled = true

    /// DEBUG launch argument: `-ayuvoMedicationsFixture <path to an ayuvo-medications archive>`.
    static let fixtureArgument = "-ayuvoMedicationsFixture"

    static func remindersEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: remindersEnabledKey) as? Bool ?? defaultRemindersEnabled
    }

    static func snoozeMinutes(_ defaults: UserDefaults = .standard) -> Int {
        let stored = defaults.object(forKey: snoozeMinutesKey) as? Int ?? defaultSnoozeMinutes
        return snoozeOptions.contains(stored) ? stored : defaultSnoozeMinutes
    }
}
