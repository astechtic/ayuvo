import Foundation

// Dietary Reference Intakes by sex and age band, nutrient coverage against goals and supplement averaging over the
// dosing interval. Port of `dri_goals`, `nutrient_coverage` and `supplement_daily` in `scripts/intake_reference.py`.

nonisolated enum NutrientGoals {
    // MARK: dri_goals

    struct DRIResult: DerivedOutput {
        var band: String
        /// Snake keys (`iron_mg`, `vitamin_d_mcg`, …) → RDA or AI.
        var goals: [String: Double]
        /// Tolerable Upper Intake Levels (sodium: CDRR).
        var upper: [String: Double]

        var jsonObject: [String: Any] { ["band": band, "goals": goals, "upper": upper] }
    }

    /// `sex` "male" | "female" | anything else ("other" takes the higher of the two values).
    static func driGoals(sex: String?, age: Double?, config: IntakeConfig) -> DRIResult {
        let dri = config.dri
        var band: String?
        for b in dri.ageBands {
            if let age, b.min <= age, age <= b.max { band = b.id }
        }
        if band == nil, let first = dri.ageBands.first, let last = dri.ageBands.last {
            band = (age != nil && age! < first.min) ? first.id : last.id
        }
        let bandID = band ?? ""
        var goals: [String: Double] = [:]
        for key in IntakeMath.sortedKeys(dri.goals) {
            guard let vals = dri.goals[key]?[bandID], vals.count >= 2 else { continue }
            goals[key] = sex == "male" ? vals[0] : (sex == "female" ? vals[1] : max(vals[0], vals[1]))
        }
        return DRIResult(band: bandID, goals: goals, upper: dri.upper)
    }

    // MARK: nutrient_coverage

    struct CoverageDay: Sendable {
        var day: String
        var totals: [String: Double]
    }

    struct CoverageResult: DerivedOutput {
        var days: Int
        var coverage: [String: Double] = [:]
        var shortfalls: [String] = []
        var excesses: [String] = []

        var jsonObject: [String: Any] {
            ["days": days, "coverage": coverage, "shortfalls": shortfalls, "excesses": excesses]
        }
    }

    static func nutrientCoverage(days: [CoverageDay], goals: [String: Double], limits: [String: Double],
                                 config: IntakeConfig) -> CoverageResult {
        let th = config.thresholds
        var out = CoverageResult(days: days.count)
        if days.count < th.shortfallMinDays { return out }
        for key in IntakeMath.sortedKeys(goals) {
            guard let g = goals[key], g != 0 else { continue }
            var vals: [Double] = []
            for d in days { vals.append((d.totals[key] ?? 0.0) * 100.0 / g) }
            let pct = DerivedMath.mean(vals)
            out.coverage[key] = DerivedMath.roundTo(pct, 0)
            if pct < th.shortfallPct { out.shortfalls.append(key) }
        }
        for key in IntakeMath.sortedKeys(limits) {
            let lim = limits[key] ?? 0
            var vals: [Double] = []
            for d in days { vals.append(d.totals[key] ?? 0.0) }
            if lim != 0, DerivedMath.mean(vals) > lim { out.excesses.append(key) }
        }
        return out
    }

    // MARK: supplement_daily

    struct SupplementResult: DerivedOutput {
        var dailyAverage: Double?
        var doses: Int
        var aboveUpper: Bool

        var jsonObject: [String: Any] {
            ["daily_average": DerivedMath.json(dailyAverage), "doses": doses, "above_upper": aboveUpper]
        }
    }

    /// Averages a supplement over its dosing window: a weekly 60,000 IU (1,500 mcg) vitamin D dose counts 1/7 per day.
    static func supplementDaily(amountPerDose: Double, doses: Int, windowDays: Double, upper: Double?) -> SupplementResult {
        let avg: Double? = windowDays > 0 ? amountPerDose * Double(doses) / windowDays : nil
        var above = false
        if let avg, let upper { above = avg > upper }
        return SupplementResult(dailyAverage: DerivedMath.roundTo(avg, 1), doses: doses, aboveUpper: above)
    }
}
