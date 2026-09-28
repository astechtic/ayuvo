import Foundation
import Testing
@testable import calorietracker

/// Health nutrition types (`dietary_*`, docs/nutrients.md §5a, docs/ui-structure.md §4 / §7.10): the generic health
/// detail draws the same reference lines, About and Learn more as a `nutrient:` chart; the macro types keep the
/// profile goal; every reference nutrient (`app_tracked: false` ones included) has an app chart and can be a
/// supplement nutrient, and the untracked ones count supplements only.
@MainActor
struct HealthNutritionTypeTests {
    private let adult = NutrientsReference.Profile(age: 40, sex: "female", calorieGoal: 2000)

    @Test func vitaminDResolvesToReferenceLinesAndTheAppChart() {
        let resolved = MetricsReference.resolveMetric("dietary_vitamin_d")
        #expect(resolved.source == "health")
        #expect(resolved.goalSource == "nutrient.reference")
        #expect(resolved.nutrientKey == "vitamin_d")
        #expect(resolved.learnSlug == "vitamin-d")
        #expect(resolved.nutrientMetric == "nutrient:vitamin_d")

        let descriptor = MetricCatalog.descriptor(for: .health("dietary_vitamin_d"))
        #expect(descriptor.nutrientKey == "vitamin_d")
        #expect(descriptor.learnSlug == "vitamin-d")
        #expect(descriptor.nutrientMetric == .nutrient("vitamin_d"))

        let lines = NutrientsReference.referenceLines(key: "vitamin_d", profile: adult)
        #expect(lines.recommended == 15)
        #expect(lines.upperLimit == 100)
        let rules = NutrientCatalog.chartLines(lines)
        #expect(rules.map(\.value) == [15, 100])
        #expect(rules.map(\.isWarning) == [false, true])
        #expect(AppLinks.nutrientURL("vitamin-d").absoluteString.hasSuffix("/nutrients/vitamin-d"))
    }

    @Test func copperGetsLinesGuideAndASupplementsOnlyAppChart() {
        let resolved = MetricsReference.resolveMetric("dietary_copper")
        #expect(resolved.goalSource == "nutrient.reference")
        #expect(resolved.nutrientKey == "copper")
        #expect(resolved.learnSlug == "copper")
        #expect(resolved.nutrientMetric == "nutrient:copper", "every reference nutrient has an app chart")
        #expect(resolved.foodTracked == false, "the food log does not record copper")

        let descriptor = MetricCatalog.descriptor(for: .health("dietary_copper"))
        #expect(descriptor.nutrientKey == "copper")
        #expect(descriptor.nutrientMetric == .nutrient("copper"))
        #expect(descriptor.foodTracked == false)
        #expect(MetricCatalog.descriptor(for: .health("dietary_vitamin_d")).foodTracked == true)

        // No custom goal is stored for untracked nutrients, so the lines are the reference values.
        let lines = NutrientCatalog.lines("copper", profile: nil)
        #expect(lines.recommended == 0.9)
        #expect(lines.upperLimit == 10)
        #expect(lines.recommendedLabel == NutrientsReference.labelRecommended)
        #expect(NutrientCatalog.chartLines(lines).count == 2)
        #expect(NutrientCatalog.unit("copper") == "mg")
        #expect(NutrientCatalog.title("copper") == "Copper")
        #expect(MetricKey(pinID: "nutrient:copper") == .nutrient("copper"))

        let app = MetricCatalog.descriptor(for: .nutrient("copper"))
        #expect(app.title == "Copper")
        #expect(app.unitLabel == "mg")
        #expect(app.foodTracked == false)
        #expect(app.learnSlug == "copper")
        #expect(MetricCatalog.descriptor(for: .nutrient("zinc")).foodTracked == true)
        #expect(MetricCatalog.descriptor(for: .nutrient("creatine")).foodTracked == true)
    }

    @Test func dietaryEnergyUsesTheCalorieGoal() {
        let resolved = MetricsReference.resolveMetric("dietary_energy")
        #expect(resolved.goalSource == "profile.calories")
        #expect(resolved.nutrientKey == nil && resolved.learnSlug == nil && resolved.nutrientMetric == nil)
        let descriptor = MetricCatalog.descriptor(for: .health("dietary_energy"))
        #expect(descriptor.nutrientKey == nil)

        let suite = "HealthNutritionTypeTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let profile = ProfileStore()
        let sources = MetricDataSources(
            food: FoodStore(observesExternalChanges: false, defaults: defaults), water: WaterStore(defaults: defaults),
            fasting: FastingStore(defaults: defaults),
            weight: WeightStore(observesExternalChanges: false, defaults: defaults), bodyFat: BodyFatStore(),
            workouts: StrengthWorkoutStore(defaults: defaults), importedWorkouts: ImportedHealthWorkoutStore(defaults: defaults),
            health: HealthDataStore(defaults: defaults), profile: profile
        )
        let goal = sources.goal(for: descriptor.goalSource, defaults: defaults)
        #expect(goal == Double(profile.profile.effectiveCalories))
        let rules = ChartReferenceLine.goal(goal)
        #expect(rules.count == 1 && rules.first?.isWarning == false)

        for (id, source) in [("dietary_protein", "profile.protein"), ("dietary_carbohydrates", "profile.carbs"), ("dietary_fat_total", "profile.fat")] {
            #expect(MetricsReference.resolveMetric(id).goalSource == source, "\(id)")
            #expect(MetricsReference.resolveMetric(id).nutrientKey == nil, "\(id)")
        }
    }

    @Test func everyDietaryTypeExceptMacrosHasAReferenceNutrient() {
        let macros: Set<String> = ["dietary_energy", "dietary_protein", "dietary_carbohydrates", "dietary_fat_total"]
        let dietary = HealthMetricRegistry.iOSTypes.map(\.id).filter { $0.hasPrefix("dietary_") && !macros.contains($0) }
        #expect(!dietary.isEmpty)
        for id in dietary {
            let resolved = MetricsReference.resolveMetric(id)
            #expect(resolved.nutrientKey != nil, "\(id)")
            #expect(resolved.learnSlug != nil, "\(id)")
            let tracked = resolved.nutrientKey.flatMap { NutrientsReference.byKey[$0]?.appTracked }
            #expect(resolved.nutrientMetric == resolved.nutrientKey.map { "nutrient:\($0)" }, "\(id)")
            #expect(resolved.foodTracked == tracked, "\(id)")
            if let metric = resolved.nutrientMetric { #expect(MetricKey(pinID: metric) != nil, "\(id)") }
        }
    }

    @Test func untrackedNutrientsAreSupplementNutrients() {
        #expect(NutrientsReference.nutrientUnit("copper") == "mg")
        #expect(NutrientsReference.nutrientUnit("iodine") == "mcg")
        #expect(NutrientCatalog.supplementKeys == NutrientReferenceData.shared.nutrients.map(\.key) + NutrientReferenceData.shared.sportsSupplements.map(\.key))
        #expect(NutrientCatalog.supplementKeys.count == 37 + 8)
        for key in ["thiamin", "riboflavin", "vitamin_b6", "biotin", "iodine", "manganese", "copper", "chromium", "selenium",
                    "molybdenum", "niacin", "pantothenic_acid", "phosphorus", "chloride"] {
            #expect(NutrientCatalog.supplementKeys.contains(key), "\(key)")
            #expect(!NutrientCatalog.foodTracked(key), "\(key)")
            #expect(MetricKey(pinID: "nutrient:\(key)") != nil, "\(key)")
        }
        #expect(NutrientCatalog.foodTracked("zinc") && NutrientCatalog.foodTracked("creatine"))
        #expect(!NutrientCatalog.foodTracked("grape_seed_extract"))
        // The Add nutrient menu lists every key once, grouped.
        #expect(NutrientCatalog.supplementSections.flatMap(\.keys).sorted() == NutrientCatalog.supplementKeys.sorted())
        #expect(NutrientCatalog.supplementSections.map(\.title) == ["Vitamins", "Minerals", "Other", "Sports Supplements"])
        #expect(NutrientCatalog.labelTitle("thiamin") == "Vitamin B1 (Thiamin)")
        #expect(NutrientCatalog.labelTitle("copper") == "Copper")

        #expect(NutrientsReference.convertAmount(1.7, unit: "mg", key: "copper") == .init(ok: true, amount: 1.7, unit: "mg", error: nil))
        #expect(NutrientsReference.convertAmount(1, unit: "mg", key: "grape_seed_extract").error == "unknown_nutrient")
        #expect(MR.nutrientProblem(key: .str("copper"), amount: .int(1)) == nil)
        #expect(MR.nutrientProblem(key: .str("grape_seed_extract"), amount: .int(1)) == "unknown_nutrient")
        let archive = MR.archiveNutrients(.obj(["id": .str("m1"), "nutrients": .arr([
            .obj(["key": .str("copper"), "amount_per_unit": .int(2)]),
            .obj(["key": .str("grape_seed_extract"), "amount_per_unit": .int(50)]),
            .obj(["key": .str("zinc"), "amount_per_unit": .int(10)]),
        ])]))
        #expect(archive.nutrients?.compactMap { $0["key"].string } == ["copper", "zinc"])
        #expect(archive.skips.first?["reason"].string == "unknown_nutrient")
        var draft = MedicationDraft(startDate: "2026-09-01")
        draft.name = "Multi"
        draft.isPRN = true
        draft.nutrients = [DraftNutrient(key: "copper", amountPerUnit: 2), DraftNutrient(key: "iodine", amountPerUnit: 140)]
        #expect(draft.validationErrors.isEmpty)
        draft.nutrients = [DraftNutrient(key: "grape_seed_extract", amountPerUnit: 2)]
        #expect(draft.validationErrors.map(\.code) == ["nutrient_unknown"])
        let label = NutrientsReference.parseLabelOutput(#"{"items":[{"key":"copper","amount":2,"unit":"mg"},{"key":"grape_seed_extract","amount":50,"unit":"mg"}]}"#)
        #expect(label.items.map(\.key) == ["copper"] && label.rejected.map(\.code) == ["unknown_nutrient"])
    }

    /// Nutrition Details (docs/nutrients.md §5b): untracked rows appear for an active medication's nutrient or a
    /// supplement part that day, after the food rows of their category, in reference order.
    @Test func nutritionDetailsRowsForUntrackedNutrients() {
        let base = NutrientCatalog.detailKeys
        #expect(NutrientCatalog.detailRowKeys(activeNutrientKeys: [], totals: [:]) == base)
        // Food values alone never add a row (food is not recorded for them).
        #expect(NutrientCatalog.detailRowKeys(activeNutrientKeys: [], totals: ["copper": .init(food: nil, supplements: nil, total: nil)]) == base)
        // Active medication lists copper, iodine and thiamin; biotin only has a taken dose today.
        let rows = NutrientCatalog.detailRowKeys(activeNutrientKeys: ["iodine", "copper", "thiamin", "zinc"],
                                                 totals: ["biotin": .init(food: nil, supplements: 30, total: 30)])
        let zinc = rows.firstIndex(of: "zinc")!, folate = rows.firstIndex(of: "folate")!
        #expect(Array(rows[(zinc + 1)...(zinc + 2)]) == ["copper", "iodine"])
        #expect(Array(rows[(folate + 1)...(folate + 2)]) == ["thiamin", "biotin"])
        #expect(rows.count == base.count + 4)
        #expect(Set(rows).count == rows.count)
        // Default goal, no custom goal.
        let goal = NutrientCatalog.detailGoal("iodine", profile: nil, goals: .defaults)
        #expect(goal == 150)
        #expect(NutrientCatalog.detailGoal("monounsaturated_fat", profile: nil, goals: .defaults) == nil)
    }
}
