import Foundation
import Testing
@testable import calorietracker

/// Health nutrition types (`dietary_*`, docs/nutrients.md §5a, docs/ui-structure.md §4 / §7.10): the generic health
/// detail draws the same reference lines, About and Learn more as a `nutrient:` chart; the macro types keep the
/// profile goal; `app_tracked: false` nutrients never become supplement nutrients.
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

    @Test func copperGetsLinesAndGuideButNoAppChart() {
        let resolved = MetricsReference.resolveMetric("dietary_copper")
        #expect(resolved.goalSource == "nutrient.reference")
        #expect(resolved.nutrientKey == "copper")
        #expect(resolved.learnSlug == "copper")
        #expect(resolved.nutrientMetric == nil, "copper is not app_tracked: no nutrient: chart")

        let descriptor = MetricCatalog.descriptor(for: .health("dietary_copper"))
        #expect(descriptor.nutrientKey == "copper")
        #expect(descriptor.nutrientMetric == nil)

        // No custom goal is stored for untracked nutrients, so the lines are the reference values.
        let lines = NutrientCatalog.lines("copper", profile: nil)
        #expect(lines.recommended == 0.9)
        #expect(lines.upperLimit == 10)
        #expect(lines.recommendedLabel == NutrientsReference.labelRecommended)
        #expect(NutrientCatalog.chartLines(lines).count == 2)
        #expect(NutrientCatalog.unit("copper") == "mg")
        #expect(NutrientCatalog.title("copper") == "Copper")
        #expect(MetricKey(pinID: "nutrient:copper") == nil)
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
            let tracked = resolved.nutrientKey.flatMap { NutrientsReference.byKey[$0]?.appTracked } ?? false
            #expect((resolved.nutrientMetric != nil) == tracked, "\(id)")
            if let metric = resolved.nutrientMetric { #expect(MetricKey(pinID: metric) != nil, "\(id)") }
        }
    }

    @Test func copperIsRejectedAsASupplementNutrient() {
        #expect(NutrientsReference.nutrientUnit("copper") == nil)
        #expect(!NutrientCatalog.supplementKeys.contains("copper"))
        #expect(NutrientCatalog.supplementKeys.count == 23 + 8)
        #expect(NutrientsReference.convertAmount(1, unit: "mg", key: "copper").error == "unknown_nutrient")
        #expect(MR.nutrientProblem(key: .str("copper"), amount: .int(1)) == "unknown_nutrient")
        let archive = MR.archiveNutrients(.obj(["id": .str("m1"), "nutrients": .arr([
            .obj(["key": .str("copper"), "amount_per_unit": .int(2)]),
            .obj(["key": .str("zinc"), "amount_per_unit": .int(10)]),
        ])]))
        #expect(archive.nutrients?.compactMap { $0["key"].string } == ["zinc"])
        #expect(archive.skips.first?["reason"].string == "unknown_nutrient")
        var draft = MedicationDraft(startDate: "2026-09-01")
        draft.name = "Multi"
        draft.isPRN = true
        draft.nutrients = [DraftNutrient(key: "copper", amountPerUnit: 2)]
        #expect(draft.validationErrors.map(\.code) == ["nutrient_unknown"])
        let label = NutrientsReference.parseLabelOutput(#"{"items":[{"key":"copper","amount":2,"unit":"mg"}]}"#)
        #expect(label.items.isEmpty && label.rejected.map(\.code) == ["unknown_nutrient"])
    }
}
