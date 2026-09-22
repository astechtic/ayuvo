import Foundation
import Testing
@testable import calorietracker

/// Dashboard snapshot rules (docs/widgets.md "Dashboard snapshot"): nothing invented, day-scoped
/// values cleared after midnight, medications absent without a database.
@MainActor
struct WidgetDashboardBuilderTests {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/London")!
        return calendar
    }

    /// 2026-09-22 10:00 London.
    private var now: Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: 22, hour: 10))!
    }

    private func input(
        healthConnected: Bool = true,
        steps: Int? = 4_200,
        water: Bool = true,
        fasting: Bool = true,
        activeFast: FastingSession? = nil,
        medications: MedicationTodayTimeline? = nil,
        metrics: [WidgetDashboardSnapshot.Metric] = []
    ) -> WidgetDashboardBuilder.Input {
        WidgetDashboardBuilder.Input(
            now: now, calendar: calendar,
            caloriesToday: 1_000, calorieGoal: 2_000,
            healthConnected: healthConnected, stepsToday: steps, stepGoal: 10_000,
            waterEnabled: water, waterMlToday: 500, waterGoalMl: 2_000, waterUnit: .milliliters,
            fastingEnabled: fasting, activeFast: activeFast,
            medications: medications,
            weight: WidgetDashboardSnapshot.Row(title: "Weight", valueText: "72.4 kg", at: now),
            bodyFat: nil,
            workoutToday: WidgetDashboardSnapshot.Row(title: "Workout today", valueText: "3 exercises", at: now),
            metrics: metrics
        )
    }

    private func timeline(_ items: [(hour: Int, status: DoseStatus)]) -> MedicationTodayTimeline {
        let slots = items.map { item in
            let at = calendar.date(bySettingHour: item.hour, minute: 0, second: 0, of: now)!
            return MedicationTodayTimeline.Slot(slot: String(format: "%02d:00", item.hour), items: [
                MedicationTodayTimeline.Item(
                    medicationID: "m\(item.hour)", scheduleID: nil, scheduledAtMs: Int64(at.timeIntervalSince1970 * 1000),
                    status: item.status, isLate: false, logID: nil, snoozedUntilMs: nil, doseQuantity: nil, doseUnit: nil,
                    kind: .scheduled
                ),
            ])
        }
        var summary = MedicationTodayTimeline.Summary()
        summary.total = items.count
        summary.taken = items.filter { $0.status == .taken }.count
        return MedicationTodayTimeline(date: "2026-09-22", summary: summary, slots: slots, prn: [], medications: [:])
    }

    @Test func ringsFollowTheSummaryRules() {
        let rings = WidgetDashboardBuilder.build(input()).rings
        #expect(rings.eat.progress == 0.5)
        #expect(rings.eat.state == .value)
        #expect(rings.move.progress == 0.42)
        #expect(rings.drink?.progress == 0.25)
    }

    @Test func moveAsksToConnectAndNeverInventsSteps() {
        let off = WidgetDashboardBuilder.build(input(healthConnected: false, steps: nil)).rings.move
        #expect(off.state == .connect)
        #expect(off.valueText == "—")
        #expect(off.progress == 0)
        let noData = WidgetDashboardBuilder.build(input(steps: nil)).rings.move
        #expect(noData.state == .noData)
        #expect(noData.valueText == "—")
    }

    @Test func drinkIsHiddenWhileWaterTrackingIsOff() {
        let snapshot = WidgetDashboardBuilder.build(input(water: false))
        #expect(snapshot.rings.drink == nil)
        #expect(snapshot.waterTrackingEnabled == false)
    }

    @Test func activeFastOnlyWhileTrackingIsOn() {
        let fast = FastingSession(startedAt: now.addingTimeInterval(-3600), goalMinutes: 16 * 60)
        let on = WidgetDashboardBuilder.build(input(activeFast: fast)).fasting
        #expect(on.activeStartedAt == fast.startedAt)
        #expect(on.goalDate == fast.goalDate)
        let off = WidgetDashboardBuilder.build(input(fasting: false, activeFast: fast)).fasting
        #expect(off.activeStartedAt == nil)
        #expect(off.enabled == false)
    }

    @Test func medicationsAbsentWithoutADatabase() {
        #expect(WidgetDashboardBuilder.build(input(medications: nil)).medications == nil)
        #expect(WidgetDashboardBuilder.build(input(medications: nil)).nextDose(after: now) == nil)
    }

    @Test func nextDoseIsTheFirstPendingDoseAfterNow() {
        let snapshot = WidgetDashboardBuilder.build(input(medications: timeline([(8, .taken), (20, .scheduled), (14, .scheduled)])))
        #expect(snapshot.medications?.doses.count == 3)
        #expect(snapshot.medications?.taken == 1)
        let next = snapshot.nextDose(after: now)
        #expect(next?.scheduledAt == calendar.date(bySettingHour: 14, minute: 0, second: 0, of: now))
        // Past every pending time: the earliest overdue pending dose.
        let late = calendar.date(bySettingHour: 21, minute: 0, second: 0, of: now)!
        #expect(snapshot.nextDose(after: late)?.scheduledAt == calendar.date(bySettingHour: 14, minute: 0, second: 0, of: now))
        // All taken: nothing to show.
        let done = WidgetDashboardBuilder.build(input(medications: timeline([(8, .taken)])))
        #expect(done.nextDose(after: now) == nil)
    }

    @Test func refreshDatesCoverDosesFastingGoalAndMidnight() {
        let fast = FastingSession(startedAt: now.addingTimeInterval(-3600), goalMinutes: 16 * 60)
        let snapshot = WidgetDashboardBuilder.build(input(activeFast: fast, medications: timeline([(14, .scheduled), (8, .scheduled)])))
        let dates = snapshot.refreshDates(after: now, calendar: calendar)
        let dose = calendar.date(bySettingHour: 14, minute: 0, second: 0, of: now)!
        let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now))!
        #expect(dates == [dose, fast.goalDate, midnight].sorted())
    }

    @Test func dayScopedValuesClearAfterMidnight() {
        let metrics = [
            WidgetDashboardSnapshot.Metric(key: "app:calories", title: "Calories", systemImage: "flame.fill", tintHex: "#34C759",
                                           valueText: "1,000", unitText: "kcal", progress: 0.5, dayScoped: true, at: now, caption: nil),
            WidgetDashboardSnapshot.Metric(key: "app:weight", title: "Weight", systemImage: "scalemass", tintHex: "#AF52DE",
                                           valueText: "72.4", unitText: "kg", progress: nil, dayScoped: false, at: now, caption: nil),
        ]
        let fast = FastingSession(startedAt: now.addingTimeInterval(-3600), goalMinutes: 16 * 60)
        let snapshot = WidgetDashboardBuilder.build(input(activeFast: fast, medications: timeline([(20, .scheduled)]), metrics: metrics))
        #expect(snapshot.display(at: now, calendar: calendar) == snapshot)

        let tomorrow = calendar.date(byAdding: .day, value: 1, to: now)!
        let stale = snapshot.display(at: tomorrow, calendar: calendar)
        #expect(stale.rings.eat.valueText == "0")
        #expect(stale.rings.eat.progress == 0)
        #expect(stale.rings.move.valueText == "—")
        #expect(stale.rings.drink?.progress == 0)
        #expect(stale.medications == nil)
        #expect(stale.workoutToday == nil)
        #expect(stale.metric("app:calories")?.valueText == "—")
        #expect(stale.metric("app:calories")?.progress == nil)
        #expect(stale.metric("app:weight")?.valueText == "72.4")
        #expect(stale.weight?.valueText == "72.4 kg")
        #expect(stale.fasting.activeStartedAt == fast.startedAt)
    }

    @Test func contentComparisonIgnoresGeneratedAtAndRoundTrips() throws {
        let snapshot = WidgetDashboardBuilder.build(input(medications: timeline([(20, .scheduled)])))
        var later = snapshot
        later.generatedAt = now.addingTimeInterval(60)
        #expect(snapshot.hasSameContent(as: later))
        later.rings.eat.valueText = "1,100"
        #expect(!snapshot.hasSameContent(as: later))

        let data = try JSONEncoder().encode(snapshot)
        #expect(WidgetDashboardSnapshot.decode(data) == snapshot)
        var future = snapshot
        future.version = WidgetDashboardSnapshot.currentVersion + 1
        #expect(WidgetDashboardSnapshot.decode(try JSONEncoder().encode(future)) == nil)
    }
}
