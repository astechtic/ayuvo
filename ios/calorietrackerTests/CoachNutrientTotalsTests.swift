import Foundation
import Testing
@testable import calorietracker

/// `get_nutrient_totals` (docs/coach.md §8). Android's `CoachNutrientTotalsTest` uses the same fixture and numbers.
@MainActor
struct CoachNutrientTotalsTests {
    private let calendar = Calendar.current

    private func at(_ day: Int, _ hour: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour))!
    }

    private var foods: [FoodEntry] {
        [
            FoodEntry(name: "Paneer bowl", calories: 600, protein: 30, carbs: 50, fat: 25, timestamp: at(10, 13), source: .manual, mealType: .lunch,
                      fiber: 8, sodium: 900, calcium: 600, iron: 4, magnesium: 150, vitaminD: 1),
            FoodEntry(name: "Dal rice", calories: 500, protein: 20, carbs: 80, fat: 10, timestamp: at(10, 20), source: .manual, mealType: .dinner,
                      fiber: 10, calcium: 100, iron: 5, magnesium: 120),
            FoodEntry(name: "Oats", calories: 300, protein: 10, carbs: 50, fat: 6, timestamp: at(11, 8), source: .manual, mealType: .breakfast,
                      calcium: 300, magnesium: 100),
        ]
    }

    private var medications: CoachMedicationsContext {
        let taken = NutrientTotals.ms(at(10, 9))
        return CoachMedicationsContext(
            snapshot: .obj(["medications": .arr([])]), timeZone: calendar.timeZone.identifier, nowMs: taken, count: 1, activeCount: 1,
            supplementEntries: [
                NutrientsReference.SupplementEntry(tMs: taken, nutrientKey: "magnesium", value: 200, medicationID: "m1"),
                NutrientsReference.SupplementEntry(tMs: taken, nutrientKey: "copper", value: 0.9, medicationID: "m1"),
            ],
            takenDoseMs: [taken]
        )
    }

    private func tools(medications: CoachMedicationsContext? = nil, health: CoachHealthContext? = nil) -> CoachTools {
        CoachTools(weights: [], bodyFats: [], foods: foods, health: health, healthAccessEnabled: health != nil, medications: medications,
                   nutrientProfile: NutrientsReference.Profile(age: 30, sex: "male", calorieGoal: 2200),
                   nutrientCustomGoals: ["calcium": 1200])
    }

    private func run(_ tools: CoachTools, from: String = "2026-09-10", to: String = "2026-09-11") async throws -> [String: Any] {
        let text = await tools.executeAsync(name: "get_nutrient_totals", arguments: ["from": from, "to": to])
        return try #require(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
    }

    private func nutrient(_ payload: [String: Any], _ key: String) -> [String: Any]? {
        (payload["nutrients"] as? [[String: Any]])?.first { $0["key"] as? String == key }
    }

    @Test func toolIsAFoodToolAdvertisedWithTheDiary() {
        #expect(CoachTools.nutritionToolNames.contains("get_nutrient_totals"))
        #expect(CoachTools(weights: [], bodyFats: [], foods: []).availableToolNames.contains("get_nutrient_totals"))
        #expect(CoachTools.toolDescriptions["get_nutrient_totals"]?.contains("magnesium") == true)
    }

    @Test func foodMicronutrientsHaveTotalsAveragesAndReferences() async throws {
        let payload = try await run(tools())
        #expect(payload["from"] as? String == "2026-09-10")
        #expect(payload["days_in_range"] as? Int == 2)
        #expect(payload["logged_days"] as? Int == 2)
        #expect(payload["food_entries"] as? Int == 3)

        let calcium = try #require(nutrient(payload, "calcium"))
        #expect(calcium["food"] as? Double == 1000)
        #expect(calcium["total"] as? Double == 1000)
        #expect(calcium["average_per_logged_day"] as? Double == 500)
        #expect(calcium["unit"] as? String == "mg")
        // The custom goal replaces the RDA, like the nutrient charts.
        #expect(calcium["recommended"] as? Double == 1200)
        #expect(calcium["recommended_kind"] as? String == "custom_goal")
        #expect(calcium["percent_of_recommended"] as? Int == 42)

        let iron = try #require(nutrient(payload, "iron"))
        #expect(iron["recommended"] as? Double == 8)
        #expect(iron["recommended_kind"] as? String == "RDA")

        let macros = try #require(payload["macros"] as? [String: Any])
        #expect((macros["calories_kcal"] as? [String: Any])?["total"] as? Double == 1400)
        #expect((macros["protein_g"] as? [String: Any])?["average_per_logged_day"] as? Double == 30)

        let days = try #require(payload["days"] as? [[String: Any]])
        #expect(days.count == 2)
        #expect((days[0]["totals"] as? [String: Any])?["calcium"] as? Double == 700)
        #expect((days[1]["totals"] as? [String: Any])?["iron"] == nil)
    }

    @Test func nutrientsWithoutDataAreListedNotZeroed() async throws {
        let payload = try await run(tools())
        #expect(nutrient(payload, "vitamin_c") == nil)
        #expect(nutrient(payload, "copper") == nil)
        let missing = try #require(payload["no_data_logged"] as? [String])
        #expect(missing.contains("vitamin_c"))
        #expect(missing.contains("zinc"))
        #expect(!missing.contains("calcium"))
        let untracked = try #require(payload["not_tracked_by_food_log"] as? [String])
        #expect(untracked.contains("copper"))
        #expect(untracked.contains("vitamin_b6"))
        #expect((payload["notes"] as? [String])?.contains { $0.contains("not zero") } == true)
    }

    @Test func supplementsAreSeparatedFromFood() async throws {
        let payload = try await run(tools(medications: medications))
        let magnesium = try #require(nutrient(payload, "magnesium"))
        #expect(magnesium["food"] as? Double == 370)
        #expect(magnesium["supplements"] as? Double == 200)
        #expect(magnesium["total"] as? Double == 570)
        #expect(magnesium["average_per_logged_day"] as? Double == 285)
        #expect(magnesium["upper_limit_scope"] as? String == "supplements_only")
        // 200 mg of supplements over two logged days is under the 350 mg supplement UL.
        #expect(magnesium["above_upper_limit"] as? Bool == false)

        let copper = try #require(nutrient(payload, "copper"))
        #expect(copper["food_tracked"] as? Bool == false)
        #expect(copper["food"] is NSNull)
        #expect(copper["supplements"] as? Double == 0.9)
        #expect(!((payload["not_tracked_by_food_log"] as? [String]) ?? []).contains("copper"))

        // Without the Medications source the supplements stay out.
        let foodOnly = try await run(tools())
        #expect(nutrient(foodOnly, "magnesium")?["supplements"] is NSNull)
    }

    @Test func healthFillsOnlyNutrientsTheFoodLogDoesNotRecord() async throws {
        let type = HealthCoachDataType(dataType: "dietary_selenium", category: "nutrition", displayName: "Selenium", unit: "mcg",
                                       aggregation: "SUM", count: 1, first: nil, last: nil, latestAt: nil, latestValue: nil,
                                       latestValueText: nil, historyLimitedBefore: nil)
        let summary = HealthCoachSummary(dataType: "dietary_selenium", unit: "mcg", from: "2026-09-10", to: "2026-09-11", total: 40,
                                         average: 40, min: 40, max: 40, latest: 40,
                                         days: [HealthCoachDay(date: "2026-09-11", sum: 40, avg: 40, min: 40, max: 40, count: 1, durationS: nil,
                                                               v2Avg: nil, v2Min: nil, v2Max: nil, ownSum: nil)])
        let health = CoachHealthContext(query: .fixed(dataTypes: [type], summaries: ["dietary_selenium": summary]),
                                        context: HealthCoachContext(enabled: true, typeCount: 1, lastSync: nil, sevenDayLines: []))
        let payload = try await run(tools(health: health))
        let selenium = try #require(nutrient(payload, "selenium"))
        #expect(selenium["health_other_apps"] as? Double == 40)
        #expect(selenium["total"] as? Double == 40)
        #expect(selenium["average_per_logged_day"] as? Double == 40)
        #expect(selenium["recommended"] as? Double == 55)
        let days = try #require(payload["days"] as? [[String: Any]])
        #expect((days[1]["totals"] as? [String: Any])?["selenium"] as? Double == 40)
    }

    @Test func longRangesOmitDailyRows() async throws {
        let payload = try await run(tools(), from: "2026-08-01", to: "2026-09-30")
        #expect(payload["days"] == nil)
        #expect(payload["days_omitted"] is String)
        #expect(nutrient(payload, "calcium")?["total"] as? Double == 1000)
    }
}
