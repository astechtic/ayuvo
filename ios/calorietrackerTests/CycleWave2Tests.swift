import Foundation
import Testing
@testable import calorietracker

/// Cycle tracking Wave 2 (docs/cycle-tracking.md §5–§8): the UI store over an in-memory database, the reminder plan
/// (only `cycle.*`, at most three, discreet text), the Coach summary (never notes), the Apple Health write bookkeeping
/// and the portable `cycle_enabled` preference.
@MainActor
struct CycleWave2Tests {
    static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    static func date(_ text: String, hour: Int = 8) -> Date {
        var c = DateComponents()
        c.year = Int(text.prefix(4))
        c.month = Int(text.dropFirst(5).prefix(2))
        c.day = Int(text.dropFirst(8).prefix(2))
        c.hour = hour
        return calendar.date(from: c)!
    }

    static func makeStore(today: String) async throws -> (CycleStore, CycleRuntime) {
        let runtime = CycleRuntime(database: try await CycleDatabase.inMemory())
        let store = CycleStore(runtime: runtime, loadsPlatformPeriods: false)
        store.afterWrite = nil
        store.clock = { Calendar.current.date(from: DateComponents(year: Int(today.prefix(4)), month: Int(today.dropFirst(5).prefix(2)),
                                                                   day: Int(today.dropFirst(8).prefix(2)), hour: 12))! }
        return (store, runtime)
    }

    // MARK: Store

    @Test func setupThenPeriodStartAndEnd() async throws {
        let (store, _) = try await Self.makeStore(today: "2026-10-02")
        await store.reload()
        #expect(store.hasLoaded && !store.isSetUp)
        await store.completeSetup(lastStart: "2026-09-06", cycleLength: 29, periodLength: 5, options: CycleSettingsOptions())
        #expect(store.isSetUp)
        #expect(store.periods.count == 1)
        #expect(store.periods[0].endDay == "2026-09-10", "an old start is closed at the usual length")
        #expect(store.snapshot?.prediction.basis == "default")
        #expect(store.snapshot?.prediction.nextStart == "2026-10-05")

        await store.periodStarted()
        #expect(store.ongoingPeriod?.startDay == "2026-10-02")
        #expect(store.snapshot?.today.phase == "period")
        await store.periodEnded()
        #expect(store.ongoingPeriod == nil)
        #expect(store.periods.last?.endDay == "2026-10-02")
    }

    @Test func recentSetupStartStaysOngoing() async throws {
        let (store, _) = try await Self.makeStore(today: "2026-10-02")
        await store.completeSetup(lastStart: "2026-10-01", cycleLength: nil, periodLength: nil, options: CycleSettingsOptions())
        #expect(store.ongoingPeriod?.startDay == "2026-10-01")
        #expect(store.settings.cycleLength == nil, "skipped lengths stay on the defaults")
    }

    @Test func overlapIsReportedThenMerged() async throws {
        let (store, _) = try await Self.makeStore(today: "2026-10-02")
        #expect(await store.savePeriod(id: nil, start: "2026-09-01", end: "2026-09-05").isEmpty)
        #expect(await store.savePeriod(id: nil, start: "2026-09-04", end: "2026-09-08") == ["overlap"])
        #expect(await store.mergeSave(id: nil, start: "2026-09-04", end: "2026-09-08").isEmpty)
        #expect(store.periods.count == 1)
        #expect(store.periods[0].startDay == "2026-09-01" && store.periods[0].endDay == "2026-09-08")
        #expect(await store.savePeriod(id: nil, start: "2026-10-04", end: nil) == ["future_start"])
    }

    @Test func dayLogFlowMarksPeriodAndTogglesOff() async throws {
        let (store, _) = try await Self.makeStore(today: "2026-10-02")
        let log = CycleDayLogRecord(day: "2026-09-20", flow: "medium", pain: 4, painLocations: ["lower_back"], symptoms: ["cramps"],
                                    moods: ["calm"], note: "private", updatedMs: 0)
        #expect(await store.saveDayLog(log) == nil)
        #expect(store.status("2026-09-20")?.phase == "period")
        #expect(store.dayLog("2026-09-20")?.symptoms == ["cramps"])
        #expect(await store.setPeriodDay("2026-09-20", on: false) == nil)
        #expect(store.periods.isEmpty)
        #expect(await store.saveDayLog(CycleDayLogRecord(day: "2026-10-09", updatedMs: 0)) == "future")
    }

    @Test func calendarStatusesCoverTheMonth() async throws {
        let (store, _) = try await Self.makeStore(today: "2026-10-02")
        for (i, s) in ["2026-06-10", "2026-07-09", "2026-08-08", "2026-09-06"].enumerated() {
            _ = await store.savePeriod(id: nil, start: s, end: CycleDay.string(CycleDay.o(s) + 4))
            _ = i
        }
        let first = CycleDay.o("2026-10-01")
        let statuses = store.statuses(from: first, to: first + 30)
        #expect(statuses.count == 31)
        #expect(statuses.values.contains { $0.phase == "predicted_period" })
        #expect(statuses.values.contains { $0.phase == "ovulation" })
        #expect(store.trends?.cycles.count == 4)
    }

    // MARK: Reminders

    @Test func reminderPlanIsDiscreetAndCapped() {
        var options = CycleSettingsOptions()
        options.daily = true
        options.time = "20:30"
        let reminders = [CycleReminder(kind: "daily_log", day: nil), CycleReminder(kind: "period_soon", day: "2026-10-05"),
                         CycleReminder(kind: "period_end", day: "2026-10-02")]
        let plan = CycleReminderPlanner.plan(reminders: reminders, options: options, now: Self.date("2026-10-02", hour: 21), calendar: Self.calendar)
        #expect(plan.count <= CycleReminderPlanner.maxPending)
        #expect(plan.allSatisfy { CycleReminderPlanner.isCycleIdentifier($0.identifier) })
        #expect(plan.map(\.identifier) == ["cycle.daily_log", "cycle.period_soon", "cycle.period_end"])
        for item in plan {
            #expect(item.title == "Ayuvo")
            #expect(!item.body.lowercased().contains("period"), "lock-screen text stays discreet by default")
        }
        // The period-end time already passed today, so it moves to tomorrow.
        #expect(plan[2].components.day == 3 && plan[2].components.hour == 20 && plan[2].components.minute == 30)
        #expect(plan[0].repeats && !plan[1].repeats)
    }

    @Test func reminderDetailsOnlyWhenAsked() {
        var options = CycleSettingsOptions()
        options.lockScreenDetails = true
        let plan = CycleReminderPlanner.plan(reminders: [CycleReminder(kind: "period_soon", day: "2026-10-05")], options: options,
                                             now: Self.date("2026-10-02"), calendar: Self.calendar)
        #expect(plan.first?.body.contains("period") == true)
        let past = CycleReminderPlanner.plan(reminders: [CycleReminder(kind: "period_soon", day: "2026-10-01")], options: options,
                                             now: Self.date("2026-10-02"), calendar: Self.calendar)
        #expect(past.isEmpty, "a passed 'period soon' is dropped")
        #expect(CycleReminderPlanner.time("7:5") == (7, 5))
        #expect(CycleReminderPlanner.time("99:00") == (9, 0))
    }

    // MARK: Coach

    @Test func coachSummaryNeverCarriesNotes() throws {
        let coach = try #require(CycleCoachConfig.load())
        let periods = ["2026-06-10", "2026-07-09", "2026-08-08", "2026-09-06"].enumerated().map { i, s in
            CyclePeriodInput(id: "p\(i)", start: s, end: CycleDay.string(CycleDay.o(s) + 4))
        }
        let logs = [CycleLogInput(day: "2026-09-06", flow: "heavy", pain: 8, symptoms: ["cramps"], moods: ["anxious"])]
        let state = CycleState(today: "2026-10-02", periods: periods, logs: logs)
        let model = CycleEngine.model(state, .shared)
        let context = CoachCycleContext.build(snapshot: CycleEngine.snapshot(model: model, logs: logs, .shared),
                                              trends: CycleEngine.trends(model, logs: logs, .shared), coach: coach)
        let text = context.promptLines.joined(separator: "\n")
        #expect(text.contains("Recent cycle lengths"))
        #expect(text.contains("Cramps"))
        #expect(text.lowercased().contains("never diagnose"))
        #expect(!text.contains("private"))
        #expect(context.summaryLines.count <= 10)
        #expect(context.onDeviceBlock.components(separatedBy: "\n").count <= 12)
        #expect(!context.onDeviceBlock.lowercased().contains("note"))
    }

    @Test func coachNotAvailableOnlyWhenAsked() {
        #expect(ChatService.cyclePromptLines(nil, newUserMessage: "What should I eat today?").isEmpty)
        let lines = ChatService.cyclePromptLines(nil, newUserMessage: "Is my period late?")
        #expect(lines.count == 1 && lines[0].contains("No cycle data"))
    }

    @Test func coachAccessIsOffByDefault() throws {
        let suite = "CycleWave2Tests.coach.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(!CycleSettings.coachEnabled(defaults))
        #expect(CycleSettings.enabled(defaults))
        CycleSettings.setCoachEnabled(true, defaults: defaults)
        #expect(CycleSettings.coachEnabled(defaults))
        #expect(defaults.object(forKey: CycleSettings.coachConsentedAtKey) != nil)
    }

    // MARK: Apple Health bookkeeping

    @Test func writtenRangeRoundTrips() {
        let range = CycleDay.o("2026-09-06")...CycleDay.o("2026-09-10")
        #expect(CycleHealthSync.writtenRange(CycleHealthSync.rangeJSON(range)) == range)
        #expect(CycleHealthSync.writtenRange("{}") == nil)
        #expect(CycleHealthSync.rangeJSON(nil) == "{}")
        #expect(CycleHealthKitWriter.flowValue("very_heavy") == 4)
        #expect(CycleHealthKitWriter.flowValue(nil) == 1)
        #expect(CycleHealthKitWriter.shareTypes().count == 2 + CycleConfig.shared.symptoms.filter { $0.healthkit != nil }.count)
    }

    // MARK: Portable

    @Test func cycleEnabledTravelsInPortableData() throws {
        let suite = "CycleWave2Tests.portable.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let backups = FileManager.default.temporaryDirectory.appendingPathComponent("cycle-portable-\(UUID().uuidString)")
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: backups)
        }
        let json = #"""
        {"app": "Ayuvo", "format": "ayuvo-portable-data", "format_version": 1, "created_at": "2026-10-01T10:00:00.000Z",
         "platform": "android", "app_version": "1.0", "preferences": {"cycle_enabled": false, "coach_cycle_enabled": true}}
        """#
        _ = try PortableDataImport.restore(data: Data(json.utf8), defaults: defaults, calendar: Self.calendar, backupDirectory: backups)
        #expect(CycleSettings.enabled(defaults) == false)
        #expect(CycleSettings.coachEnabled(defaults) == false, "an import never turns Coach access on")
        let output = try #require(PortableDataExport.build(defaults: defaults, appVersion: "1", calendar: Self.calendar))
        let root = try #require(try JSONSerialization.jsonObject(with: output.data) as? [String: Any])
        let preferences = try #require(root["preferences"] as? [String: Any])
        #expect(preferences["cycle_enabled"] as? Bool == false)
    }

    // MARK: Browse

    @Test func browseSearchFindsCycleTracking() {
        let results = BrowseFeatureCatalog.search("period")
        #expect(results.contains { $0.id == "cycleTracking" && $0.destination == .browse([.cycle]) })
    }
}
