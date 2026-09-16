import Foundation
import Testing
import UserNotifications
@testable import calorietracker

/// Identifier scheme, the iOS budget and the planner wrapper (docs/medications.md §10).
struct MedicationReminderPlannerTests {
    static let zone = TimeZone(identifier: "Asia/Kolkata")!

    static func ms(_ date: String, _ hhmm: String) -> Int64 {
        Int64(MR.localInstant(date: date, hhmm: hhmm, zone: zone.identifier)!)
    }

    static func medication(id: String, name: String = "Metformin", status: MedicationStatus = .active) -> Medication {
        Medication(id: id, name: name, strength: "500 mg", form: .tablet, doseQuantity: 1, doseUnit: .tablet,
                   foodRelation: .with, startDate: "2026-09-01", status: status, createdMs: 1, updatedMs: 1)
    }

    static func schedule(id: String, medicationID: String, times: [String], reminders: Bool = true, from: Int64 = 0) -> MedicationSchedule {
        MedicationSchedule(id: id, medicationID: medicationID, frequency: .daily, times: times, reminderEnabled: reminders,
                           activeFromMs: from, createdMs: 1, updatedMs: 1)
    }

    @Test func identifierRoundTripsWithAndWithoutSnooze() throws {
        let plain = MedicationReminderPlanner.identifier(medicationID: "med-a.b", scheduledAtMs: 1_800_000_000_000, snooze: false)
        #expect(plain == "medication.med-a.b.1800000000000")
        let parsedPlain = try #require(MedicationReminderPlanner.parse(identifier: plain))
        #expect(parsedPlain.medicationID == "med-a.b")
        #expect(parsedPlain.scheduledAtMs == 1_800_000_000_000)
        #expect(parsedPlain.snooze == false)

        let snoozed = MedicationReminderPlanner.identifier(medicationID: "med-1", scheduledAtMs: 42, snooze: true)
        #expect(snoozed == "medication.med-1.42.snooze")
        let parsedSnooze = try #require(MedicationReminderPlanner.parse(identifier: snoozed))
        #expect(parsedSnooze.medicationID == "med-1")
        #expect(parsedSnooze.scheduledAtMs == 42)
        #expect(parsedSnooze.snooze)

        #expect(MedicationReminderPlanner.parse(identifier: "water.reminder") == nil)
        #expect(MedicationReminderPlanner.parse(identifier: "medication.") == nil)
        #expect(MedicationReminderPlanner.parse(identifier: "medication.med-1") == nil)
        #expect(MedicationReminderPlanner.parse(identifier: "medication.med-1.notanumber") == nil)
        #expect(MedicationReminderPlanner.isMedicationIdentifier("medication.x.1"))
        #expect(!MedicationReminderPlanner.isMedicationIdentifier("meal.breakfast"))
    }

    @Test func budgetLeavesRoomForOtherPendingRequests() {
        #expect(MedicationReminderPlanner.budget == 52)
        #expect(MedicationReminderPlanner.effectiveBudget(nonMedicationPending: 0) == 52)
        #expect(MedicationReminderPlanner.effectiveBudget(nonMedicationPending: 10) == 52)
        #expect(MedicationReminderPlanner.effectiveBudget(nonMedicationPending: 20) == 44)
        #expect(MedicationReminderPlanner.effectiveBudget(nonMedicationPending: 64) == 0)
        #expect(MedicationReminderPlanner.effectiveBudget(nonMedicationPending: -5) == 52)
    }

    @Test func planTrimsToTheBudgetAndOrdersByFireTime() {
        // 10 medicines × 12 doses/day × 7 days ≫ 52.
        var medications: [Medication] = []
        var schedules: [MedicationSchedule] = []
        let times = (0..<12).map { String(format: "%02d:00", $0 * 2) }
        for n in 0..<10 {
            let id = "med-\(n)"
            medications.append(Self.medication(id: id, name: "Med \(n)"))
            schedules.append(Self.schedule(id: "sch-\(n)", medicationID: id, times: times))
        }
        let now = Date(timeIntervalSince1970: Double(Self.ms("2026-09-16", "09:30")) / 1000)
        let plan = MedicationReminderPlanner.plan(medications: medications, schedules: schedules, logs: [], now: now, zone: Self.zone)
        #expect(plan.entries.count == 52)
        #expect(plan.truncated)
        #expect(plan.nextFireMs == Self.ms("2026-09-16", "10:00"))
        let fireTimes = plan.entries.map(\.fireAtMs)
        #expect(fireTimes == fireTimes.sorted())
        let identifiers = Set(plan.entries.map(MedicationReminderPlanner.identifier(for:)))
        #expect(identifiers.count == 52, "identifiers are unique per occurrence")

        let capped = MedicationReminderPlanner.plan(medications: medications, schedules: schedules, logs: [], now: now,
                                                    zone: Self.zone, nonMedicationPending: 30)
        #expect(capped.entries.count == 34)
    }

    @Test func snoozeReplacesTheScheduledEntryAndPausedMedicinesAreExcluded() {
        let active = Self.medication(id: "med-a", name: "Alpha")
        let paused = Self.medication(id: "med-p", name: "Paused", status: .paused)
        let stopped = Self.medication(id: "med-s", name: "Stopped", status: .stopped)
        let schedules = [
            Self.schedule(id: "sch-a", medicationID: "med-a", times: ["08:00", "20:00"]),
            Self.schedule(id: "sch-p", medicationID: "med-p", times: ["08:00"]),
            Self.schedule(id: "sch-s", medicationID: "med-s", times: ["08:00"]),
        ]
        let scheduledAt = Self.ms("2026-09-16", "08:00")
        let snoozedUntil = Self.ms("2026-09-16", "08:40")
        let log = DoseLog(id: "log-1", medicationID: "med-a", scheduleID: "sch-a", scheduledAtMs: scheduledAt, status: .snoozed,
                          snoozedUntilMs: snoozedUntil, doseQuantity: 1, doseUnit: .tablet, createdMs: 1, updatedMs: 1)
        let now = Date(timeIntervalSince1970: Double(Self.ms("2026-09-16", "08:30")) / 1000)
        let plan = MedicationReminderPlanner.plan(medications: [active, paused, stopped], schedules: schedules, logs: [log],
                                                  now: now, zone: Self.zone)
        #expect(plan.entries.allSatisfy { $0.medicationID == "med-a" }, "paused and stopped medicines never get reminders")
        let first = plan.entries.first
        #expect(first?.isSnooze == true)
        #expect(first?.fireAtMs == snoozedUntil)
        #expect(first?.scheduledAtMs == scheduledAt)
        #expect(plan.entries.filter { $0.scheduledAtMs == scheduledAt }.count == 1, "the snooze replaces the scheduled entry")
        #expect(MedicationReminderPlanner.identifier(for: first!) == "medication.med-a.\(scheduledAt).snooze")
    }

    @Test func remindersOffOnTheRowRemovesScheduledEntriesOnly() {
        let active = Self.medication(id: "med-a")
        let schedule = Self.schedule(id: "sch-a", medicationID: "med-a", times: ["08:00", "20:00"], reminders: false)
        let now = Date(timeIntervalSince1970: Double(Self.ms("2026-09-16", "07:00")) / 1000)
        let plan = MedicationReminderPlanner.plan(medications: [active], schedules: [schedule], logs: [], now: now, zone: Self.zone)
        #expect(plan.entries.isEmpty)
        #expect(plan.nextFireMs == nil)
    }

    @Test func triggerIsImmediateForDueDosesAndCalendarOtherwise() {
        let nowMs: Int64 = 1_800_000_000_000
        let immediate = MedicationNotificationScheduler.trigger(fireAtMs: nowMs, nowMs: nowMs)
        #expect(immediate is UNTimeIntervalNotificationTrigger)
        let later = MedicationNotificationScheduler.trigger(fireAtMs: nowMs + 3_600_000, nowMs: nowMs)
        let calendar = later as? UNCalendarNotificationTrigger
        #expect(calendar != nil)
        #expect(calendar?.repeats == false)
        let expected = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second],
                                                       from: Date(timeIntervalSince1970: Double(nowMs + 3_600_000) / 1000))
        #expect(calendar?.dateComponents == expected)
    }

    @Test func notificationContentFollowsTheContract() {
        let medication = Self.medication(id: "med-a")
        let reminder = PlannedReminder(medicationID: "med-a", scheduleID: "sch-a", scheduledAtMs: Self.ms("2026-09-16", "20:00"),
                                       fireAtMs: Self.ms("2026-09-16", "20:00"), isSnooze: false)
        let content = MedicationNotificationContent.content(for: reminder, medication: medication, zone: Self.zone)
        #expect(content.title == "Metformin 500 mg")
        #expect(content.body.hasPrefix("Take 1 tablet · "))
        #expect(content.body.hasSuffix(" · with food"))
        #expect(content.categoryIdentifier == "medication.dose")
        #expect(content.threadIdentifier == "medications")
        #expect(content.interruptionLevel == .timeSensitive)
        #expect(content.userInfo["medicationID"] as? String == "med-a")
        #expect(content.userInfo["scheduleID"] as? String == "sch-a")
        #expect((content.userInfo["scheduledAtMs"] as? NSNumber)?.int64Value == reminder.scheduledAtMs)
        #expect(content.userInfo["snooze"] as? Bool == false)

        #expect(MedicationNotificationContent.doseText(quantity: 0.5, unit: .tablet) == "½ tablet")
        #expect(MedicationNotificationContent.doseText(quantity: 2, unit: .capsule) == "2 capsules")
        #expect(MedicationNotificationContent.doseText(quantity: 5, unit: .ml) == "5 mL")
        #expect(MedicationNotificationContent.doseText(quantity: 1.25, unit: .tablet) == "1.25 tablets")

        var anytime = medication
        anytime.foodRelation = .anytime
        #expect(!MedicationNotificationContent.body(medication: anytime, scheduledAtMs: reminder.scheduledAtMs, zone: Self.zone).contains(" · with"))

        let category = MedicationNotificationContent.category()
        #expect(category.identifier == "medication.dose")
        #expect(category.actions.map(\.identifier) == ["medication.taken", "medication.skip", "medication.snooze"])
        #expect(category.options.contains(.customDismissAction))
    }
}
