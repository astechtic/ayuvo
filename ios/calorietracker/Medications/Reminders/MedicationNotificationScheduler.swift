import Foundation
import UserNotifications

/// Owns every `UNUserNotificationCenter` call for medication reminders (docs §10). A replan runs
/// the maintenance rules, computes the rolling plan and replaces the pending `medication.*`
/// requests; it never touches other features' requests.
@MainActor
final class MedicationNotificationScheduler {
    private let runtime: MedicationsRuntime
    private let defaults: UserDefaults
    private let center: UNUserNotificationCenter

    init(runtime: MedicationsRuntime = .shared, defaults: UserDefaults = .standard,
         center: UNUserNotificationCenter = .current()) {
        self.runtime = runtime
        self.defaults = defaults
        self.center = center
    }

    /// Registers the `medication.dose` category; call before the app finishes launching.
    nonisolated static func registerCategory(center: UNUserNotificationCenter = .current()) {
        center.getNotificationCategories { existing in
            var categories = existing.filter { $0.identifier != MedicationNotificationContent.categoryIdentifier }
            categories.insert(MedicationNotificationContent.category())
            center.setNotificationCategories(categories)
        }
    }

    /// The app-wide master switch (`NotificationSettingsView`) and the dose-reminder toggle.
    var remindersWanted: Bool {
        let master = defaults.object(forKey: "notificationsEnabled") as? Bool ?? false
        return master && MedicationSettings.remindersEnabled(defaults)
    }

    /// Materializes missed doses, auto-completes ended medicines, then replaces the pending
    /// medication requests with the plan. Posts `.medicationDoseDidChange` when maintenance wrote rows.
    func replan(now: Date = .now) async {
        guard runtime.databaseExists || runtime.isOpen else {
            await removeMedicationRequests()
            return
        }
        guard await runtime.openIfNeeded(), let repository = runtime.repository else { return }
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let zone = TimeZone.current
        let missed = (try? await repository.materializeMissed(nowMs: nowMs, zone: zone.identifier)) ?? 0
        let completed = (try? await repository.autoComplete(nowMs: nowMs, zone: zone.identifier)) ?? []
        if missed > 0 || !completed.isEmpty {
            NotificationCenter.default.post(name: .medicationDoseDidChange, object: nil)
        }

        let settings = await center.notificationSettings()
        guard remindersWanted, settings.authorizationStatus != .denied else {
            await removeMedicationRequests()
            return
        }

        let pending = await center.pendingNotificationRequests()
        let nonMedication = pending.filter { !MedicationReminderPlanner.isMedicationIdentifier($0.identifier) }.count
        let budget = MedicationReminderPlanner.effectiveBudget(nonMedicationPending: nonMedication)
        let plan = (try? await repository.reminderPlan(nowMs: nowMs, horizonMs: MedicationReminderPlanner.horizonMs,
                                                        zone: zone.identifier, budget: budget)) ?? .empty
        try? await repository.markPlanned(nowMs: nowMs)

        let stale = pending.map(\.identifier).filter(MedicationReminderPlanner.isMedicationIdentifier)
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }

        var medications: [String: Medication] = [:]
        for entry in plan.entries {
            if medications[entry.medicationID] == nil {
                guard let medication = try? await repository.medication(id: entry.medicationID) else { continue }
                medications[entry.medicationID] = medication
            }
            guard let medication = medications[entry.medicationID] else { continue }
            let request = UNNotificationRequest(
                identifier: MedicationReminderPlanner.identifier(for: entry),
                content: MedicationNotificationContent.content(for: entry, medication: medication, zone: zone),
                trigger: Self.trigger(fireAtMs: entry.fireAtMs, nowMs: nowMs)
            )
            try? await center.add(request)
        }
    }

    /// A calendar trigger at the fire instant; anything already due fires in one second.
    nonisolated static func trigger(fireAtMs: Int64, nowMs: Int64, calendar: Calendar = .current) -> UNNotificationTrigger {
        if fireAtMs <= nowMs + 1_000 {
            return UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        }
        let date = Date(timeIntervalSince1970: Double(fireAtMs) / 1000)
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
    }

    /// Removes every pending and delivered medication notification (master toggle off, Delete All Data).
    func cancelAll() async {
        await removeMedicationRequests()
        let delivered = await center.deliveredNotifications()
        let ids = delivered.map(\.request.identifier).filter(MedicationReminderPlanner.isMedicationIdentifier)
        if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
    }

    /// Clears the delivered banner(s) of one occurrence after the user acted on it.
    func removeDelivered(medicationID: String, scheduledAtMs: Int64) async {
        let ids = [
            MedicationReminderPlanner.identifier(medicationID: medicationID, scheduledAtMs: scheduledAtMs, snooze: false),
            MedicationReminderPlanner.identifier(medicationID: medicationID, scheduledAtMs: scheduledAtMs, snooze: true),
        ]
        center.removeDeliveredNotifications(withIdentifiers: ids)
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }

    /// Pending medication requests (DEBUG settings row).
    func pendingCount() async -> Int {
        let pending = await center.pendingNotificationRequests()
        return pending.filter { MedicationReminderPlanner.isMedicationIdentifier($0.identifier) }.count
    }

    private func removeMedicationRequests() async {
        let pending = await center.pendingNotificationRequests()
        let ids = pending.map(\.identifier).filter(MedicationReminderPlanner.isMedicationIdentifier)
        if !ids.isEmpty { center.removePendingNotificationRequests(withIdentifiers: ids) }
    }
}
