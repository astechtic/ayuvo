import Foundation
import UserNotifications

/// Handles a medication notification response (docs §11, §16). Runs from
/// `AppDelegate.userNotificationCenter(_:didReceive:)`, i.e. possibly in the background before any
/// SwiftUI view exists, so it only talks to `MedicationsRuntime.shared` and the coordinator.
enum MedicationActionHandler {
    enum Decision: Equatable, Sendable {
        /// Not a medication notification.
        case notOurs
        /// Plain tap on the banner: open the Meds segment (and the medication when known).
        case open(medicationID: String?)
        /// Swiped away: nothing changes.
        case dismiss
        /// One of the three actions on one occurrence.
        case dose(DoseAction, medicationID: String, scheduleID: String?, scheduledAtMs: Int64)
    }

    /// Pure mapping from the response's identifiers to what should happen.
    nonisolated static func decision(identifier: String, actionID: String, userInfo: [AnyHashable: Any]) -> Decision {
        guard let parsed = MedicationReminderPlanner.parse(identifier: identifier) else { return .notOurs }
        switch actionID {
        case UNNotificationDismissActionIdentifier:
            return .dismiss
        case MedicationNotificationContent.takenActionIdentifier:
            return .dose(.taken, medicationID: parsed.medicationID, scheduleID: scheduleID(userInfo), scheduledAtMs: parsed.scheduledAtMs)
        case MedicationNotificationContent.skipActionIdentifier:
            return .dose(.skipped, medicationID: parsed.medicationID, scheduleID: scheduleID(userInfo), scheduledAtMs: parsed.scheduledAtMs)
        case MedicationNotificationContent.snoozeActionIdentifier:
            return .dose(.snoozed, medicationID: parsed.medicationID, scheduleID: scheduleID(userInfo), scheduledAtMs: parsed.scheduledAtMs)
        default:
            return .open(medicationID: parsed.medicationID)
        }
    }

    private nonisolated static func scheduleID(_ userInfo: [AnyHashable: Any]) -> String? {
        guard let id = userInfo[MedicationNotificationContent.UserInfoKey.scheduleID] as? String, !id.isEmpty else { return nil }
        return id
    }

    /// Returns `false` when the response is not ours so the caller can handle it.
    @MainActor
    static func handle(_ response: UNNotificationResponse, runtime: MedicationsRuntime = .shared,
                       defaults: UserDefaults = .standard, now: Date = .now) async -> Bool {
        let request = response.notification.request
        let decision = decision(identifier: request.identifier, actionID: response.actionIdentifier, userInfo: request.content.userInfo)
        switch decision {
        case .notOurs:
            return false
        case .dismiss:
            return true
        case .open(let medicationID):
            MedicationCoordinator.request(medicationID: medicationID, defaults: defaults)
            return true
        case .dose(let action, let medicationID, let scheduleID, let scheduledAtMs):
            await apply(action, medicationID: medicationID, scheduleID: scheduleID, scheduledAtMs: scheduledAtMs,
                        runtime: runtime, defaults: defaults, now: now)
            return true
        }
    }

    /// Applies the action through the reference; a failure (`dose_missed`, database locked before the
    /// first unlock, …) leaves the row untouched and the reminder is re-planned later.
    @MainActor
    static func apply(_ action: DoseAction, medicationID: String, scheduleID: String?, scheduledAtMs: Int64,
                      runtime: MedicationsRuntime = .shared, defaults: UserDefaults = .standard, now: Date = .now) async {
        let scheduler = MedicationReminderRuntime.shared.scheduler
        guard await runtime.openIfNeeded(), let repository = runtime.repository else { return }
        let occurrence = DoseOccurrence(medicationID: medicationID, scheduleID: scheduleID, scheduledAtMs: scheduledAtMs)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let outcome = try? await repository.act(action, on: occurrence, nowMs: nowMs,
                                                snoozeMinutes: MedicationSettings.snoozeMinutes(defaults),
                                                takenAtMs: nil, note: nil)
        await scheduler.removeDelivered(medicationID: medicationID, scheduledAtMs: scheduledAtMs)
        if outcome?.ok == true {
            NotificationCenter.default.post(name: .medicationDoseDidChange, object: nil)
        }
        await MedicationReminderRuntime.shared.replan()
    }
}
