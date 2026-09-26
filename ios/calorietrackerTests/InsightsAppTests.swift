import Foundation
import Testing
import UserNotifications
@testable import calorietracker

/// Mirror rows for the Insights adapter and action tests: UTC offsets, nights 23:00–07:00.
enum InsightsTestData {
    static let dayMs: Int64 = 86_400_000

    static func midnight(_ day: String) -> Int64 { Int64(InsightsDay.parse(day)!) * dayMs }

    static func row(_ type: String, start: Int64, end: Int64? = nil, value: Double? = nil, category: Int? = nil,
                    source: String = "com.apple.health") -> HealthSampleRow {
        let endMs = end ?? start
        let unit = HealthMetricRegistry.type(id: type)?.unit ?? ""
        let localDay = InsightsDay.localDay(ms: endMs > start ? endMs - 1 : endMs, TimeZone(identifier: "UTC")!)
        return HealthSampleRow(id: UUID().uuidString.lowercased(), typeID: type, startMs: start, endMs: endMs, startOffsetS: 0,
                               endOffsetS: 0, localDay: localDay, value: value, unit: unit, categoryValue: category,
                               sourceID: source, updatedMs: endMs)
    }

    /// `nights` nights ending on `today` (wake day), HRV samples inside each night, resting HR as daily rollups only.
    static func seed(_ database: HealthDatabase, today: String, nights: Int, shortToday: Bool = false) async throws {
        var rows: [HealthSampleRow] = []
        var rollups: [HealthDailyRollupRow] = []
        for k in 0..<nights {
            let day = InsightsDay.add(today, -k)
            let wake = midnight(day) + 7 * 3_600_000
            let asleepHours: Int64 = (shortToday && k == 0) ? 1 : 7
            rows.append(row("sleep", start: wake - asleepHours * 3_600_000, end: wake, category: HealthSleepStage.asleepUnspecified.rawValue))
            let wobble = Double(k % 4)
            rows.append(row("hrv_sdnn", start: wake - 3 * 3_600_000, value: 44 + wobble))
            rows.append(row("hrv_sdnn", start: wake - 2 * 3_600_000, value: 46 + wobble))
            // A daytime reading outside the night must not count as overnight HRV.
            rows.append(row("hrv_sdnn", start: wake + 6 * 3_600_000, value: 90))
            rollups.append(HealthDailyRollupRow(typeID: "hrv_sdnn", day: day, tz: "UTC", avg: 60, count: 3))
            rollups.append(HealthDailyRollupRow(typeID: "resting_heart_rate", day: day, tz: "UTC", avg: 55 + wobble / 2, count: 1))
            // The mirror double-counts phone + watch steps; Insights must not read these.
            rollups.append(HealthDailyRollupRow(typeID: "steps", day: day, tz: "UTC", sum: 20_000, count: 2))
        }
        _ = try await database.upsertSamples(rows)
        try await database.replaceDailyRollups(rollups)
    }

    static func utcCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }
}

@MainActor
struct InsightsDataSourceTests {
    static let today = "2026-09-16"

    func source(defaults: UserDefaults, database: HealthDatabase?, steps: [String: Double]?) -> InsightsDataSource {
        InsightsDataSource(
            food: FoodStore(observesExternalChanges: false, defaults: defaults),
            water: WaterStore(defaults: defaults, observesExternalChanges: false),
            fasting: FastingStore(defaults: defaults, observesExternalChanges: false),
            weight: WeightStore(observesExternalChanges: false, defaults: defaults),
            bodyFat: BodyFatStore(defaults: defaults, observesExternalChanges: false),
            workouts: StrengthWorkoutStore(defaults: defaults, observesExternalChanges: false),
            importedWorkouts: ImportedHealthWorkoutStore(defaults: defaults),
            profile: { .default }, defaults: defaults, calendar: InsightsTestData.utcCalendar(),
            healthDatabase: { database }, dailyTotals: { id, _, _ in id == "steps" ? steps : nil }
        )
    }

    @Test func buildsOvernightValuesStepsAndDiaryInputsWithoutPersistingAnything() async throws {
        let suite = "InsightsDataSourceTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(true, forKey: "healthKitEnabled")
        defaults.set(true, forKey: WaterSettings.enabledKey)
        let database = try await HealthDatabase.inMemory()
        try await InsightsTestData.seed(database, today: Self.today, nights: 20)

        let noon = Date(timeIntervalSince1970: Double(InsightsTestData.midnight(Self.today) + 12 * 3_600_000) / 1000)
        let source = source(defaults: defaults, database: database, steps: [Self.today: 8_432])
        var food = FoodEntry(name: "Oats", calories: 420, protein: 20, carbs: 60, fat: 9, timestamp: noon, source: .manual, mealType: .breakfast)
        food.fiber = 8
        #expect(source.food.addEntry(food))
        _ = source.water.add(milliliters: 750, on: noon)
        let before = Set(defaults.dictionaryRepresentation().keys)

        let inputs = await source.inputs(today: Self.today)

        #expect(["UTC", "GMT"].contains(inputs.timeZone))
        #expect(inputs.hrvKind == "sdnn")
        #expect(inputs.sleep[Self.today]?.asleepMin == 420)
        // Overnight HRV: mean of the two samples inside the night (44, 46), not the rollup and not the daytime 90.
        #expect(inputs.series["hrv"]?[Self.today] == 45)
        // Resting HR has no sample in the night: the daily rollup is used and flagged.
        #expect(inputs.series["resting_heart_rate"]?[Self.today] == 55)
        #expect(inputs.overnightFallback.contains("resting_heart_rate"))
        #expect(!inputs.overnightFallback.contains("hrv"))
        // Steps come from the de-duplicating statistics path, never the mirror's 20,000.
        #expect(inputs.series["steps"] == [Self.today: 8_432])
        #expect(inputs.nutrition[Self.today]?.calories == 420)
        #expect(inputs.nutrition[Self.today]?.fiberG == 8)
        #expect(inputs.nutrition[Self.today]?.sugarG == nil, "an unrecorded nutrient stays absent")
        #expect(inputs.waterMl[Self.today] == 750)
        #expect(inputs.tracking.water)
        #expect(!inputs.tracking.workouts)
        #expect(inputs.targets.steps == Double(ActivitySettings.defaultDailyStepGoal))
        #expect(inputs.series["vo2_max"] == nil, "no data → no series, never zeros")

        let report = await source.report(now: noon)
        #expect(report.recovery.status == "ok")
        #expect(report.recovery.score != nil)
        // Computing never writes a score or health value to preferences.
        #expect(Set(defaults.dictionaryRepresentation().keys) == before)
    }

    @Test func healthSyncOffReadsNoHealthData() async throws {
        let suite = "InsightsDataSourceTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let database = try await HealthDatabase.inMemory()
        try await InsightsTestData.seed(database, today: Self.today, nights: 20)
        let inputs = await source(defaults: defaults, database: database, steps: [Self.today: 1]).inputs(today: Self.today)
        #expect(inputs.sleep.isEmpty)
        #expect(inputs.series.isEmpty)
    }

    @Test func storeHidesWhileHealthSyncIsOff() async throws {
        let suite = "InsightsDataSourceTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = InsightsStore()
        store.attach(source(defaults: defaults, database: nil, steps: nil), healthRevision: { 0 })
        await store.refresh()
        #expect(!store.isAvailable)
        #expect(store.report == nil)
        defaults.set(true, forKey: "healthKitEnabled")
        defaults.set(false, forKey: InsightsSettings.enabledKey)
        await store.refresh()
        #expect(!store.isAvailable)
    }
}

/// The AI payload holds only derived values, the prompt is built from the bundled contract, and the validator
/// keeps unsupported numbers and blocked wording off the screen.
@MainActor
struct InsightsAITests {
    static func summary() -> InsightsSummary {
        let inputs = InsightsEngineTests.history(days: 60)
        let profile = InsightsProfile(birthday: "1990-01-15", sex: "male", heightCm: 180)
        return HealthAnalyticsEngine.report(inputs: inputs, today: InsightsEngineTests.today, profile: profile,
                                            config: .shared).summary()
    }

    static func keys(_ value: RJ) -> Set<String> {
        switch value {
        case .obj(let o): return Set(o.keys).union(o.values.flatMap { keys($0) })
        case .arr(let a): return Set(a.flatMap { keys($0) })
        default: return []
        }
    }

    static func numbers(_ value: RJ) -> [Double] {
        switch value {
        case .obj(let o): return o.values.flatMap { numbers($0) }
        case .arr(let a): return a.flatMap { numbers($0) }
        default: return value.double.map { [$0] } ?? []
        }
    }

    @Test func payloadHasNoRawSamplesTimestampsDatesOrNames() throws {
        let payload = InsightsAI.payload(Self.summary())
        let text = InsightsAI.canonicalJSON(payload)
        let keys = Self.keys(payload)
        for banned in ["day", "as_of", "start_ms", "end_ms", "t_ms", "samples", "series", "value", "name", "birthday", "points", "components"] {
            #expect(!keys.contains(banned), "payload key \(banned)")
        }
        #expect(text.range(of: "[0-9]{4}-[0-9]{2}-[0-9]{2}", options: .regularExpression) == nil, "no dates")
        #expect(Self.numbers(payload).allSatisfy { abs($0) < 100_000 }, "no timestamps or raw counters")
        #expect(payload["recovery"]["score"].double != nil)
    }

    @Test func promptUsesTheCompactSystemPromptOnDevice() throws {
        let prompts = try #require(InsightsAI.bundledPrompts)
        let payload = InsightsAI.payload(Self.summary())
        let local = InsightsAI.buildPrompt(kind: .recovery, payload: payload, variant: .local, config: .shared, prompts: prompts)
        let cloud = InsightsAI.buildPrompt(kind: .recovery, payload: payload, variant: .cloud, config: .shared, prompts: prompts)
        #expect(local.system == prompts.local)
        #expect(cloud.system == prompts.cloud)
        #expect(local.user.contains(InsightsConfig.shared.ai.tasks["recovery"] ?? "?"))
        #expect(!local.user.contains("{payload}") && !local.user.contains("{task}"))
        #expect(!local.user.contains("\"health_age\""), "only the requested section is sent")
    }

    @Test func validatorAcceptsPayloadNumbersAndRejectsTheRest() {
        let payload = RJ.obj(["recovery": .obj(["status": .str("ok"), "score": .int(72), "label": .str("Good Recovery")])])
        let good = InsightsAI.validate("```json\n{\"headline\":\"Your recovery is 72.\",\"bullets\":[\"Sleep was close to usual.\"]}\n```",
                                       payload: payload, config: .shared)
        #expect(good.ok)
        #expect(good.output?.headline == "Your recovery is 72.")
        let invented = InsightsAI.validate("{\"headline\":\"Your recovery is 81.\",\"bullets\":[\"x\"]}", payload: payload, config: .shared)
        #expect(invented.errors == ["unknown_number"])
        let blocked = InsightsAI.validate("{\"headline\":\"You may have an infection.\",\"bullets\":[\"x\"]}", payload: payload, config: .shared)
        #expect(blocked.errors.contains("blocked_term"))
        #expect(InsightsAI.validate("no json", payload: payload, config: .shared).errors == ["parse_error"])
        #expect(InsightsAI.validate("{\"headline\":\"x\",\"bullets\":[]}", payload: payload, config: .shared).errors == ["bullet_count"])
        #expect(InsightsAI.validate("{\"headline\":1,\"bullets\":[]}", payload: payload, config: .shared).errors == ["bad_shape"])
    }

    struct FakeTransport: InsightsAITransport {
        let reply: String
        let fail: Bool

        func complete(prompt: InsightsPrompt, route: InsightsAIRoute, maxOutputTokens: Int) async throws -> String {
            if fail { throw URLError(.notConnectedToInternet) }
            return reply
        }
    }

    @Test func explainerLabelsTheRouteAndNeverFallsBack() async {
        let summary = Self.summary()
        let score = summary.recovery?.score ?? 0
        let reply = "{\"headline\":\"Recovery is \(score) today.\",\"bullets\":[\"Your signals were close to usual.\"]}"

        let local = InsightsExplainer(transport: FakeTransport(reply: reply, fail: false), route: { .gemma })
        await local.explain(kind: .recovery, summary: summary)
        #expect(local.state == .explained(InsightsExplanation(headline: "Recovery is \(score) today.", bullets: ["Your signals were close to usual."]),
                                          status: "Explained on this device"))

        let cloud = InsightsExplainer(transport: FakeTransport(reply: reply, fail: false), route: { .cloud(provider: .openai) })
        await cloud.explain(kind: .recovery, summary: summary)
        if case .explained(_, let status) = cloud.state {
            #expect(status == "Explained using online AI · \(AIProvider.openai.displayName)")
        } else {
            Issue.record("cloud explanation missing")
        }

        let invented = InsightsExplainer(transport: FakeTransport(reply: "{\"headline\":\"Recovery is 12345.\",\"bullets\":[\"x\"]}", fail: false), route: { .gemma })
        await invented.explain(kind: .recovery, summary: summary)
        if case .failed = invented.state {} else { Issue.record("an invented number must not be shown") }

        let offline = InsightsExplainer(transport: FakeTransport(reply: reply, fail: true), route: { .appleIntelligence })
        await offline.explain(kind: .recovery, summary: summary)
        if case .failed = offline.state {} else { Issue.record("a failed call must not fall back") }

        let none = InsightsExplainer(transport: FakeTransport(reply: reply, fail: false), route: { nil })
        #expect(!none.isConfigured)
        await none.explain(kind: .recovery, summary: summary)
        if case .failed(let message) = none.state {
            #expect(message.contains("Settings"))
        } else {
            Issue.record("no AI configured must show the settings hint")
        }
    }
}

/// Insights notifications say that something is ready and nothing else (docs/insights.md §4).
@MainActor
struct InsightsNotificationTests {
    static func assertNoValues(_ content: UNNotificationContent) {
        let text = content.title + " " + content.body + " " + content.subtitle
        #expect(text.rangeOfCharacter(from: .decimalDigits) == nil, "notification shows a value: \(text)")
        #expect(content.userInfo.keys.map { "\($0)" } == [InsightsNotifications.routeKey])
    }

    @Test func recoveryAndReviewNotificationsCarryNoValues() {
        let recovery = InsightsNotifications.recoveryReadyContent()
        let review = InsightsNotifications.dailyReviewContent()
        Self.assertNoValues(recovery)
        Self.assertNoValues(review)
        #expect(recovery.title == "Your recovery is ready")
        #expect(review.title == "Your daily review is ready")
        #expect(InsightsNotifications.route(from: review.userInfo) == .target("screen:insights.review"))
        #expect(InsightsNotifications.route(from: recovery.userInfo) == .target("screen:insights.recovery"))
        #expect(InsightsNotifications.route(from: ["updateURL": "x"]) == nil)
        // The upgrade keeps the old Daily Summary identifier so a pending request is replaced.
        #expect(InsightsNotifications.reviewIdentifier == "smart.summary")
    }

    @Test func morningRefreshIsEligibleFromFive() {
        let calendar = InsightsTestData.utcCalendar()
        let early = Date(timeIntervalSince1970: Double(InsightsTestData.midnight("2026-09-16") + 3 * 3_600_000) / 1000)
        let late = Date(timeIntervalSince1970: Double(InsightsTestData.midnight("2026-09-16") + 9 * 3_600_000) / 1000)
        #expect(InsightsBackgroundRefresh.nextEarliestDate(after: early, calendar: calendar)
                == Date(timeIntervalSince1970: Double(InsightsTestData.midnight("2026-09-16") + 5 * 3_600_000) / 1000))
        #expect(InsightsBackgroundRefresh.nextEarliestDate(after: late, calendar: calendar)
                == Date(timeIntervalSince1970: Double(InsightsTestData.midnight("2026-09-17") + 5 * 3_600_000) / 1000))
    }

    @Test func settingsDefaults() throws {
        let suite = "InsightsNotificationTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(InsightsSettings.isEnabled(defaults))
        #expect(!InsightsSettings.morningRecoveryEnabled(defaults))
    }
}
