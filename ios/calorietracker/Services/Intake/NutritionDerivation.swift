import Foundation

// One day of nutrition: macro shares (Atwater 4/4/9), protein per kg and per meal, saturated fat share, fiber
// density, molar sodium/potassium ratio, caffeine, eating window, last meal to bed and the tea/coffee–iron rule.
// Port of `nutrition_day` in `scripts/intake_reference.py` (docs/intake-metrics.md).

nonisolated enum NutritionDerivation {
    struct Item: Sendable {
        var eatenMs: Int64
        var meal: String?
        var calories: Double?
        var proteinG: Double?
        var carbsG: Double?
        var fatG: Double?
        var saturatedFatG: Double?
        var fiberG: Double?
        var sodiumMg: Double?
        var potassiumMg: Double?
        var ironMg: Double?
        var caffeineMg: Double?
        var isTeaOrCoffee: Bool
    }

    struct DayInput: Sendable {
        var timeZone: String
        var weightKg: Double?
        var items: [Item]
        var bedtimeMs: Int64?
    }

    struct DayResult: DerivedOutput {
        var items = 0
        var calories: Double?
        var proteinPct: Double?
        var carbsPct: Double?
        var fatPct: Double?
        var proteinGPerKg: Double?
        var saturatedFatPct: Double?
        var fiberPer1000kcal: Double?
        var naKRatio: Double?
        var caffeineMg: Double?
        var lastCaffeineMin: Int?
        var eatingWindowMin: Double?
        var lastMealToBedMin: Double?
        var mealsWithProteinTarget: Int?
        var meals = 0
        var ironAbsorptionRisk = 0

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["items": items, "calories": j(calories), "protein_pct": j(proteinPct), "carbs_pct": j(carbsPct),
                    "fat_pct": j(fatPct), "protein_g_per_kg": j(proteinGPerKg), "saturated_fat_pct": j(saturatedFatPct),
                    "fiber_per_1000kcal": j(fiberPer1000kcal), "na_k_ratio": j(naKRatio), "caffeine_mg": j(caffeineMg),
                    "last_caffeine_min": j(lastCaffeineMin), "eating_window_min": j(eatingWindowMin),
                    "last_meal_to_bed_min": j(lastMealToBedMin), "meals_with_protein_target": j(mealsWithProteinTarget),
                    "meals": meals, "iron_absorption_risk": ironAbsorptionRisk]
        }
    }

    static func nutritionDay(_ inp: DayInput, config: IntakeConfig) -> DayResult {
        let th = config.thresholds
        let tz = DerivedDay.timeZone(inp.timeZone)
        let items = IntakeMath.stableSorted(inp.items) { $0.eatenMs }
        var out = DayResult(items: items.count)
        guard let firstItem = items.first, let lastItem = items.last else { return out }

        func total(_ key: (Item) -> Double?) -> Double {
            var t = 0.0
            for i in items { t += key(i) ?? 0.0 }
            return t
        }
        let kcal = total(\.calories)
        let p = total(\.proteinG), c = total(\.carbsG), f = total(\.fatG)
        let energy = 4.0 * p + 4.0 * c + 9.0 * f
        out.calories = DerivedMath.roundTo(kcal, 0)
        if energy > 0 {
            out.proteinPct = DerivedMath.roundTo(400.0 * p / energy, 1)
            out.carbsPct = DerivedMath.roundTo(400.0 * c / energy, 1)
            out.fatPct = DerivedMath.roundTo(900.0 * f / energy, 1)
            out.saturatedFatPct = DerivedMath.roundTo(900.0 * total(\.saturatedFatG) / energy, 1)
        }
        let w: Double? = (inp.weightKg ?? 0) != 0 ? inp.weightKg : nil
        if let w { out.proteinGPerKg = DerivedMath.roundTo(p / w, 2) }
        if kcal > 0 { out.fiberPer1000kcal = DerivedMath.roundTo(total(\.fiberG) * 1000.0 / kcal, 1) }
        let k = total(\.potassiumMg)
        if k > 0 {
            // molar ratio: sodium 22.99 g/mol, potassium 39.10 g/mol
            out.naKRatio = DerivedMath.roundTo((total(\.sodiumMg) / 22.99) / (k / 39.10), 2)
        }
        let caf = items.filter { ($0.caffeineMg ?? 0) > 0 }
        out.caffeineMg = DerivedMath.roundTo(total(\.caffeineMg), 0)
        if let last = caf.last { out.lastCaffeineMin = IntakeMath.minuteOfDay(last.eatenMs, tz) }
        out.eatingWindowMin = DerivedMath.roundTo(Double(lastItem.eatenMs - firstItem.eatenMs) / 60000.0, 0)
        if let bed = inp.bedtimeMs {
            out.lastMealToBedMin = DerivedMath.roundTo(Double(bed - lastItem.eatenMs) / 60000.0, 0)
        }
        var meals: [String: [Item]] = [:]
        for i in items {
            let name = (i.meal?.isEmpty ?? true) ? "other" : i.meal!
            meals[name, default: []].append(i)
        }
        out.meals = meals.count
        if let w {
            let target = th.proteinPerMealGPerKg * w
            var hit = 0
            for name in IntakeMath.sortedKeys(meals) {
                var mp = 0.0
                for i in meals[name] ?? [] { mp += i.proteinG ?? 0.0 }
                if mp >= target { hit += 1 }
            }
            out.mealsWithProteinTarget = hit
        }
        var risk = 0
        let windowMs = th.ironAbsorptionWindowMin * 60000
        for (ti, t) in items.enumerated() where t.isTeaOrCoffee {
            for (ii, i) in items.enumerated()
            where (i.ironMg ?? 0) >= th.ironRichMg && abs(Double(i.eatenMs - t.eatenMs)) <= windowMs && ii != ti {
                risk += 1
                break
            }
        }
        out.ironAbsorptionRisk = risk
        return out
    }
}
