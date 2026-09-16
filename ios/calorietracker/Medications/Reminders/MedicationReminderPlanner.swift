import Foundation

/// Pure planning helpers over the reference's `plan_reminders` (docs §10): identifiers, the
/// 52-request budget and the defensive cap that keeps the app under iOS's 64 pending limit.
nonisolated enum MedicationReminderPlanner {
    /// Requests kept for medication reminders (docs §10 `IOS_BUDGET`).
    static let budget = MR.iosBudget
    /// iOS delivers at most this many pending requests per app.
    static let pendingLimit = 64
    /// Reminders are planned this far ahead (a rolling window refilled on every replan).
    static let horizonDays = 7
    static var horizonMs: Int64 { Int64(horizonDays) * 86_400_000 }
    /// Every medication request identifier starts with this prefix.
    static let identifierPrefix = "medication."
    private static let snoozeSuffix = ".snooze"

    /// `"medication.<medicationID>.<scheduledAtMs>[.snooze]"`.
    static func identifier(medicationID: String, scheduledAtMs: Int64, snooze: Bool) -> String {
        identifierPrefix + medicationID + "." + String(scheduledAtMs) + (snooze ? snoozeSuffix : "")
    }

    static func identifier(for reminder: PlannedReminder) -> String {
        identifier(medicationID: reminder.medicationID, scheduledAtMs: reminder.scheduledAtMs, snooze: reminder.isSnooze)
    }

    /// Inverse of `identifier(...)`; `nil` for anything that is not ours.
    static func parse(identifier: String) -> (medicationID: String, scheduledAtMs: Int64, snooze: Bool)? {
        guard identifier.hasPrefix(identifierPrefix) else { return nil }
        var rest = String(identifier.dropFirst(identifierPrefix.count))
        var snooze = false
        if rest.hasSuffix(snoozeSuffix) {
            snooze = true
            rest = String(rest.dropLast(snoozeSuffix.count))
        }
        guard let dot = rest.lastIndex(of: "."), dot > rest.startIndex else { return nil }
        let medicationID = String(rest[rest.startIndex..<dot])
        guard let ms = Int64(rest[rest.index(after: dot)...]), !medicationID.isEmpty else { return nil }
        return (medicationID, ms, snooze)
    }

    static func isMedicationIdentifier(_ identifier: String) -> Bool {
        identifier.hasPrefix(identifierPrefix)
    }

    /// The budget that leaves room for every non-medication request the app already has pending.
    static func effectiveBudget(nonMedicationPending: Int) -> Int {
        max(0, min(budget, pendingLimit - max(0, nonMedicationPending)))
    }

    /// Typed wrapper over the reference planner with the iOS defaults.
    static func plan(medications: [Medication], schedules: [MedicationSchedule], logs: [DoseLog],
                     now: Date, zone: TimeZone = .current, nonMedicationPending: Int = 0) -> ReminderPlan {
        MR.planReminders(medications: medications, schedules: schedules, logs: logs,
                         nowMs: Int64(now.timeIntervalSince1970 * 1000), horizonMs: horizonMs,
                         zone: zone.identifier, budget: effectiveBudget(nonMedicationPending: nonMedicationPending))
    }
}
