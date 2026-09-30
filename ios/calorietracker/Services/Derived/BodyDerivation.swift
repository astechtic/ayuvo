import Foundation

// Body: EWMA weight trend, weekly rate, BMI and goal ETA, and height conflicts between sources. Port of the "Body"
// section of `scripts/derived_reference.py`.

nonisolated enum BodyDerivation {
    struct TrendResult: DerivedOutput {
        var trendKg: Double?
        var rateKgWeek: Double?
        var bmi: Double?
        var bmiCategoryIndex: Int?
        var healthyLowKg: Double?
        var healthyHighKg: Double?
        var etaWeeks: Double?

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["trend_kg": j(trendKg), "rate_kg_week": j(rateKgWeek), "bmi": j(bmi),
                    "bmi_category_index": j(bmiCategoryIndex), "healthy_low_kg": j(healthyLowKg),
                    "healthy_high_kg": j(healthyHighKg), "eta_weeks": j(etaWeeks)]
        }
    }

    /// `weights`: {day: kg} (last weigh-in per day); `scheme` "who" (default) or "asian".
    static func bodyTrend(weights w: [String: Double], day: String, heightM: Double?, goalKg: Double?, scheme: String?,
                          config: DerivedConfig) -> TrendResult {
        let th = config.thresholds
        let days = w.keys.filter { $0 <= day }.sorted()
        var out = TrendResult()
        let scheme = (scheme?.isEmpty ?? true) ? "who" : scheme!
        let h: Double? = (heightM == nil || heightM == 0) ? nil : heightM
        if let h, let range = th.healthyBmi[scheme] {
            out.healthyLowKg = DerivedMath.roundTo(range[0] * h * h, 1)
            out.healthyHighKg = DerivedMath.roundTo(range[1] * h * h, 1)
        }
        guard let firstDay = days.first else { return out }
        var trend: [String: Double?] = [:]
        var cur: Double?
        var d = firstDay
        while d <= day {
            if let v = w[d] { cur = cur == nil ? v : cur! + th.ewmaAlpha * (v - cur!) }
            trend[d] = .some(cur)
            d = DerivedDay.addDays(d, 1)
        }
        let t = trend[day]!!
        out.trendKg = DerivedMath.roundTo(t, 2)
        let start = DerivedDay.addDays(day, -th.rateWindowDays)
        let recent = days.filter { $0 > start }
        if let startTrend = trend[start], recent.count >= th.rateMinWeighins {
            let rate = (t - startTrend!) * 7.0 / Double(th.rateWindowDays)
            out.rateKgWeek = DerivedMath.roundTo(rate, 2)
            if let g = goalKg, abs(rate) >= th.etaMinRate, (g - t) * rate > 0 {
                out.etaWeeks = DerivedMath.roundTo((g - t) / rate, 1)
            }
        }
        if let h, let cuts = th.bmi[scheme] {
            let bmi = t / (h * h)
            var cat = 0
            for c in cuts where bmi >= c { cat += 1 }
            out.bmi = DerivedMath.roundTo(bmi, 1)
            out.bmiCategoryIndex = cat
        }
        return out
    }

    struct HeightConflictResult: DerivedOutput {
        var conflict: Bool
        var values: [Double]
        var jsonObject: [String: Any] { ["conflict": conflict, "values": values] }
    }

    /// `heights`: latest height (m) per source.
    static func heightConflict(heights: [Double], config: DerivedConfig) -> HeightConflictResult {
        let vals = Array(Set(heights.map { DerivedMath.roundTo($0, 2) })).sorted()
        return HeightConflictResult(conflict: vals.count >= 2 && vals[vals.count - 1] - vals[0] > config.thresholds.heightConflictM,
                                    values: vals)
    }
}
