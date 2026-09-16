import Foundation
import Testing
import UserNotifications
@testable import calorietracker

/// The pure response → decision mapping (`UNNotificationResponse` cannot be constructed in tests).
struct MedicationActionHandlerTests {
    private let identifier = MedicationReminderPlanner.identifier(medicationID: "med-1", scheduledAtMs: 1_800_000_000_000, snooze: false)
    private let userInfo: [AnyHashable: Any] = ["medicationID": "med-1", "scheduleID": "sch-1", "scheduledAtMs": NSNumber(value: 1_800_000_000_000)]

    @Test func foreignNotificationsAreNotOurs() {
        #expect(MedicationActionHandler.decision(identifier: "app.update", actionID: UNNotificationDefaultActionIdentifier, userInfo: [:]) == .notOurs)
        #expect(MedicationActionHandler.decision(identifier: "meal.breakfast", actionID: "medication.taken", userInfo: [:]) == .notOurs)
    }

    @Test func tapOpensTheMedicationAndDismissDoesNothing() {
        #expect(MedicationActionHandler.decision(identifier: identifier, actionID: UNNotificationDefaultActionIdentifier, userInfo: userInfo)
                == .open(medicationID: "med-1"))
        #expect(MedicationActionHandler.decision(identifier: identifier, actionID: UNNotificationDismissActionIdentifier, userInfo: userInfo)
                == .dismiss)
        #expect(MedicationActionHandler.decision(identifier: identifier, actionID: "some.unknown.action", userInfo: userInfo)
                == .open(medicationID: "med-1"))
    }

    @Test func actionsMapToDoseActionsWithTheOccurrence() {
        #expect(MedicationActionHandler.decision(identifier: identifier, actionID: "medication.taken", userInfo: userInfo)
                == .dose(.taken, medicationID: "med-1", scheduleID: "sch-1", scheduledAtMs: 1_800_000_000_000))
        #expect(MedicationActionHandler.decision(identifier: identifier, actionID: "medication.skip", userInfo: userInfo)
                == .dose(.skipped, medicationID: "med-1", scheduleID: "sch-1", scheduledAtMs: 1_800_000_000_000))
        #expect(MedicationActionHandler.decision(identifier: identifier, actionID: "medication.snooze", userInfo: userInfo)
                == .dose(.snoozed, medicationID: "med-1", scheduleID: "sch-1", scheduledAtMs: 1_800_000_000_000))
    }

    @Test func snoozeNotificationsResolveTheOriginalOccurrenceAndMissingScheduleIsNil() {
        let snoozeID = MedicationReminderPlanner.identifier(medicationID: "med-1", scheduledAtMs: 42, snooze: true)
        #expect(MedicationActionHandler.decision(identifier: snoozeID, actionID: "medication.taken", userInfo: [:])
                == .dose(.taken, medicationID: "med-1", scheduleID: nil, scheduledAtMs: 42))
        #expect(MedicationActionHandler.decision(identifier: snoozeID, actionID: "medication.taken", userInfo: ["scheduleID": ""])
                == .dose(.taken, medicationID: "med-1", scheduleID: nil, scheduledAtMs: 42))
    }
}
