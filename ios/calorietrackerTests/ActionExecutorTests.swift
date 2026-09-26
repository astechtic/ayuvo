import Foundation
import Testing
@testable import calorietracker

/// Actions run against isolated stores (docs/actions.md): GET / SET results, confirmation rules,
/// permissions, deep-link routing, entity queries and the Coach proposal tool.
@MainActor
struct ActionExecutorTests {
    struct Fixture {
        let suite: String
        let defaults: UserDefaults
        let executor: ActionExecutor
        let now: Date

        func tearDown() { defaults.removePersistentDomain(forName: suite) }
    }

    static func fixture(water: Bool = true, fasting: Bool = true) throws -> Fixture {
        let suite = "ActionExecutorTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.set(water, forKey: WaterSettings.enabledKey)
        defaults.set(fasting, forKey: FastingSettings.enabledKey)
        defaults.set(WeightUnit.kg.rawValue, forKey: WeightUnit.storageKey)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let now = Date(timeIntervalSince1970: 1_789_560_000) // 2026-09-16 12:00 UTC
        let executor = ActionExecutor(environment: .testing(defaults: defaults, now: now, calendar: calendar))
        return Fixture(suite: suite, defaults: defaults, executor: executor, now: now)
    }

    @Test func waterLogThenGetReturnsStructuredStatus() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let logged = try await f.executor.run("water.log", ["amount": .number(500), "unit": .string("ml")], source: .siri)
        #expect(logged.double("added_ml") == 500)
        #expect(logged.double("intake_ml") == 500)
        #expect(logged.double("remaining_ml") == 1500)
        let floz = try await f.executor.run("water.log", ["amount": .string("8"), "unit": .string("floz")], source: .shortcuts)
        #expect(floz.double("added_ml") == 237)
        let today = try await f.executor.run("water.get", [:], source: .siri)
        #expect(today.double("intake_ml") == 737)
        #expect(today.double("percent") == 37)
        #expect(WaterStore(defaults: f.defaults, observesExternalChanges: false).entries.count == 2)
    }

    @Test func waterLogRespectsTheTrackingSwitch() async throws {
        let f = try Self.fixture(water: false)
        defer { f.tearDown() }
        await #expect(throws: ActionError.self) {
            _ = try await f.executor.run("water.log", ["amount": .number(250)], source: .siri)
        }
        #expect(WaterStore(defaults: f.defaults, observesExternalChanges: false).entries.isEmpty)
    }

    @Test func weightLogConvertsUnitsAndHistoryReportsChange() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let store = WeightStore(observesExternalChanges: false, defaults: f.defaults)
        store.addEntry(WeightEntry(date: f.now.addingTimeInterval(-10 * 86_400), weightKg: 76))
        let logged = try await f.executor.run("weight.log", ["value": .number(165), "unit": .string("lb")], source: .siri)
        #expect(abs((logged.value ?? 0) - 74.84) < 0.01)
        let latest = try await f.executor.run("weight.get", [:], source: .siri)
        #expect(abs((latest.value ?? 0) - 74.84) < 0.01)
        let history = try await f.executor.run("weight.history", ["range": .string("last_30_days")], source: .shortcuts)
        #expect(history.double("count") == 2)
        #expect(history.double("change_kg") == -1.2)
        #expect(history.items?.count == 2)
    }

    @Test func fastingStartStatusStopAndConflict() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let started = try await f.executor.run("fasting.start", ["goal_hours": .number(16)], source: .siri)
        #expect(started.fields["active"]?.bool == true)
        #expect(started.double("goal_s") == 57_600)
        await #expect(throws: ActionError.conflict(String(localized: "A fast is already running."))) {
            _ = try await f.executor.run("fasting.start", [:], source: .siri)
        }
        let status = try await f.executor.run("fasting.status.get", [:], source: .siri)
        #expect(status.fields["active"]?.bool == true)
        let stopped = try await f.executor.run("fasting.stop", [:], source: .siri)
        #expect(stopped.fields["ended_ms"] != nil)
        #expect(FastingStore(defaults: f.defaults, observesExternalChanges: false).activeSession == nil)
    }

    @Test func foodLogWithValuesSkipsConfirmationAndFeedsNutrition() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let validation = try f.executor.validate("nutrition.food.log", ["name": .string("Shake"), "calories": .number(180), "protein": .number(30)], source: .shortcuts).get()
        #expect(!validation.confirm)
        #expect(!validation.ai)
        _ = try await f.executor.perform(validation, source: .shortcuts, confirmed: false)
        let summary = try await f.executor.run("nutrition.summary.get", [:], source: .siri)
        #expect(summary.double("calories") == 180)
        #expect(summary.double("protein_g") == 30)
        let protein = try await f.executor.run("nutrition.nutrient.get", ["nutrient": .string("protein")], source: .siri)
        #expect(protein.value == 30)
        #expect(protein.string("unit") == "g")
        let meals = try await f.executor.run("nutrition.meals.list", [:], source: .shortcuts)
        #expect(meals.items?.first?["name"]?.string == "Shake")
    }

    @Test func nutritionSummaryIncludesEveryNutritionDetailsRow() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let food = FoodStore(observesExternalChanges: false, defaults: f.defaults)
        var entry = FoodEntry(name: "Lentil soup", calories: 320, protein: 18, carbs: 45, fat: 6, timestamp: f.now, source: .manual, mealType: .lunch)
        entry.fiber = 12
        entry.sugar = 4.5
        entry.sodium = 780
        entry.vitaminD = 2.5
        entry.monounsaturatedFat = 1.2
        entry.supplementalNutrients = [SupplementalNutrient.creatine.rawValue: 5]
        #expect(food.addEntry(entry))
        WaterStore(defaults: f.defaults, observesExternalChanges: false).add(milliliters: 750, on: f.now)

        let summary = try await f.executor.run("nutrition.summary.get", [:], source: .siri)
        #expect(summary.double("calories") == 320)
        #expect(summary.double("fiber_g") == 12)
        #expect(summary.double("sugar_g") == 4.5)
        #expect(summary.double("sodium_mg") == 780)
        #expect(summary.double("vitamin_d_mcg") == 2.5)
        #expect(summary.double("monounsaturated_fat_g") == 1.2)
        #expect(summary.double("creatine_g") == 5)
        #expect(summary.double("iron_mg") == 0)
        #expect(summary.double("water_ml") == 750)
        guard case .list(let rows)? = summary.fields["nutrients"] else {
            Issue.record("nutrients list missing")
            return
        }
        #expect(rows.count == ActionExecutor.detailNutrients.count)
        #expect(summary.dialog.contains("sodium 780 mg"))
        #expect(summary.dialog.contains("vitamin d 2.5 mcg"))
        #expect(!summary.dialog.contains("iron"))

        let entity = NutritionSummaryEntity(range: "today", fields: summary.fields)
        #expect(entity.sodium == 780)
        #expect(entity.creatine == 5)
        #expect(entity.water == 750)
        #expect(entity.details.contains("Sodium: 780 of"))
    }

    @Test func aiFoodLogNeedsConfirmationAndActiveFastBlocksFood() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let ai = try f.executor.validate("nutrition.food.log", ["description": .string("two eggs")], source: .siri).get()
        #expect(ai.confirm && ai.ai)
        await #expect(throws: ActionError.confirmationRequired) {
            _ = try await f.executor.perform(ai, confirmed: false)
        }
        _ = try await f.executor.run("fasting.start", [:], source: .siri)
        await #expect(throws: ActionError.self) {
            _ = try await f.executor.run("nutrition.food.log", ["name": .string("Toast"), "calories": .number(90)], source: .shortcuts)
        }
        #expect(FoodStore(observesExternalChanges: false, defaults: f.defaults).entries.isEmpty)
    }

    @Test func workoutSetLogAppendsSetsAndReportsVolume() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let item = try #require(StrengthWorkoutStore(defaults: f.defaults, observesExternalChanges: false).exerciseLibrary.exercises.first)
        let first = try await f.executor.run("workout.set.log", ["exercise": .string(item.id), "reps": .number(10), "weight": .number(60), "unit": .string("kg")], source: .siri)
        #expect(first.double("set_number") == 1)
        let second = try await f.executor.run("workout.set.log", ["exercise": .string(item.id), "reps": .number(8), "weight": .number(62.5), "unit": .string("kg")], source: .siri)
        #expect(second.double("set_number") == 2)
        #expect(second.double("volume_kg") == 1100)
        let today = try await f.executor.run("workout.today.get", [:], source: .siri)
        #expect(today.double("sets_done") == 2)
        #expect(today.double("reps_done") == 18)
    }

    @Test func healthReadsNeedHealthSyncAndAppMetricsDoNot() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        await #expect(throws: ActionError.self) {
            _ = try await f.executor.run("health.metric.get", ["metric": .string("steps")], source: .siri)
        }
        do {
            _ = try await f.executor.run("health.sleep.lastNight", [:], source: .siri)
            Issue.record("expected permission_required")
        } catch let error as ActionError {
            #expect(error.code == "permission_required")
        }
        _ = try await f.executor.run("water.log", ["amount": .number(300)], source: .siri)
        let water = try await f.executor.run("health.metric.get", ["metric": .string("app:water"), "range": .string("today")], source: .shortcuts)
        #expect(water.value == 300)
        #expect(water.string("aggregation") == "sum")
        await #expect(throws: ActionError.self) {
            _ = try await f.executor.run("health.metric.get", ["metric": .string("app:nonsense")], source: .siri)
        }
    }

    @Test func confirmationAndSurfaceRules() throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let dose = try f.executor.validate("medication.dose.mark", ["dose": .string("m|s|1"), "action": .string("taken")], source: .siri).get()
        #expect(dose.confirm)
        #expect(throws: ActionError.self) {
            _ = try f.executor.validate("medication.dose.mark", ["dose": .string("m|s|1"), "action": .string("taken")], source: .coach).get()
        }
        let goals = try f.executor.validate("goals.update", ["goal": .string("water"), "value": .number(3000)], source: .shortcuts).get()
        #expect(goals.confirm)
        let viaLink = try f.executor.validate("water.log", ["amount": .string("500")], source: .deeplink).get()
        #expect(viaLink.confirm)
        let viaSiri = try f.executor.validate("water.log", ["amount": .number(500)], source: .siri).get()
        #expect(!viaSiri.confirm)
        #expect(ActionExecutor.occurrence(fromDoseID: "med-1|sch-1|1789560000000")?.scheduledAtMs == 1_789_560_000_000)
        #expect(ActionExecutor.occurrence(fromDoseID: "nonsense") == nil)
    }

    @Test func goalsUpdateChangesWaterGoal() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        let result = try await f.executor.run("goals.update", ["goal": .string("water"), "value": .number(3000)], source: .shortcuts, confirmed: true)
        #expect(result.double("previous") == Double(WaterSettings.defaultDailyGoalMl))
        #expect(f.defaults.integer(forKey: WaterSettings.dailyGoalKey) == 3000)
    }

    @Test func deepLinksRouteOrAskFirst() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        #expect(await ActionRouteCoordinator.resolve(link: "ayuvo://open/records", executor: f.executor) == .route(.target("section:records")))
        #expect(await ActionRouteCoordinator.resolve(link: "ayuvo://action/health.metric.get?metric=steps", executor: f.executor) == .route(.target("metric:steps")))
        #expect(await ActionRouteCoordinator.resolve(link: "ayuvo://action/open.coach?prompt=Plan+dinner", executor: f.executor) == .route(.coach(prompt: "Plan dinner")))
        guard case .confirm(let pending) = await ActionRouteCoordinator.resolve(link: "ayuvo://action/water.log?amount=500&unit=ml", executor: f.executor) else {
            Issue.record("water.log link must ask first")
            return
        }
        #expect(pending.summary.contains("500"))
        #expect(WaterStore(defaults: f.defaults, observesExternalChanges: false).entries.isEmpty)
        if case .invalid = await ActionRouteCoordinator.resolve(link: "ayuvo://action/medication.dose.mark?dose=x&action=taken", executor: f.executor) {} else {
            Issue.record("dose marking is not available by link")
        }
        #expect(ActionRoute(storageValue: ActionRoute.coach(prompt: "hi").storageValue) == .coach(prompt: "hi"))
    }

    @Test func universalSearchFindsFoodsAndMetrics() async throws {
        let f = try Self.fixture()
        defer { f.tearDown() }
        _ = try await f.executor.run("nutrition.food.log", ["name": .string("Banana bread"), "calories": .number(250)], source: .shortcuts)
        let food = try await f.executor.run("search.universal", ["query": .string("banana"), "domain": .string("food")], source: .siri)
        #expect(food.items?.first?["title"]?.string == "Banana bread")
        let metrics = try await f.executor.run("search.universal", ["query": .string("water"), "domain": .string("metrics")], source: .coach)
        #expect(metrics.items?.contains { $0["id"]?.string == "app:water" } == true)
    }

    @Test func metricEntityQuerySearchesAppAndHealthMetrics() async throws {
        let query = HealthMetricQuery()
        let matches = try await query.entities(matching: "step")
        #expect(matches.contains { $0.id == "steps" })
        let resolved = try await query.entities(for: ["app:water", "not_a_metric"])
        #expect(resolved.map(\.id) == ["app:water"])
        #expect(try await query.suggestedEntities().contains { $0.id == "app:protein" })
    }

    @Test func coachReadToolsComeFromTheCatalogAndProposalsNeverWrite() async throws {
        #expect(CoachTools.actionReadToolNames.contains("get_water_intake"))
        #expect(!CoachTools.proposableActionIDs.contains("medication.dose.mark"))
        #expect(!CoachTools.proposableActionIDs.contains("goals.update"))
        let schema = CoachTools.schema(for: "get_water_intake")
        let range = try #require((schema["properties"] as? [String: Any])?["range"] as? [String: Any])
        #expect((range["enum"] as? [String])?.contains("last_7_days") == true)
        let proposeSchema = CoachTools.schema(for: CoachTools.proposeToolName)
        #expect((proposeSchema["required"] as? [String]) == ["action_id", "params_json"])

        let sink = CoachActionProposalSink()
        let tools = CoachTools(weights: [], bodyFats: [], foods: [], actionProposals: sink)
        #expect(tools.actionToolNames.contains(CoachTools.proposeToolName))
        let reply = await tools.executeActionTool(name: CoachTools.proposeToolName,
                                                  arguments: ["action_id": "water.log", "params_json": "{\"amount\": 250, \"unit\": \"ml\"}"])
        #expect(reply.contains("\"proposed\":true"))
        #expect(sink.proposals.count == 1)
        #expect(sink.proposals.first?.validation.confirm == true)
        let refused = await tools.executeActionTool(name: CoachTools.proposeToolName,
                                                    arguments: ["action_id": "medication.dose.mark", "params_json": "{}"])
        #expect(refused.contains("not_allowed"))
        #expect(sink.proposals.count == 1)
    }
}
