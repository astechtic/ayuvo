import Foundation

// Activity: de-duplicated hourly steps, cadence and sedentary bouts, step streaks and stride length. Port of the
// "Activity" section of `scripts/derived_reference.py`.

nonisolated enum ActivityDerivation {
    struct Source: Sendable {
        /// "phone" or "wearable".
        var kind: String
        /// 24 hourly step counts (nil = no data for that hour).
        var hourly: [Double?]
    }

    struct DayInput: Sendable {
        var timeZone: String
        var day: String
        var sources: [String: Source] = [:]
        /// Minute steps from one wearable.
        var minuteSteps: DerivedMinuteSeries?
        /// Minute heart rate, used as the wear signal.
        var wear: DerivedMinuteSeries?
        /// Platform de-duplicated total.
        var stepsTotal: Double?
    }

    struct DayResult: DerivedOutput {
        var dedupSteps: Double
        var stepBandIndex: Int
        var activeHours: Int
        var briskMinutes: Int?
        var peak30Cadence: Double?
        var longestSedentaryMin: Int?
        var phoneMissingSteps: Double = 0

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["dedup_steps": dedupSteps, "step_band_index": stepBandIndex, "active_hours": activeHours,
                    "brisk_minutes": j(briskMinutes), "peak30_cadence": j(peak30Cadence),
                    "longest_sedentary_min": j(longestSedentaryMin), "phone_missing_steps": phoneMissingSteps]
        }
    }

    static func sumHours(_ values: [Double?]) -> Double {
        var t = 0.0
        for v in values { if let v { t += v } }
        return t
    }

    static func activityDay(_ inp: DayInput, config: DerivedConfig) -> DayResult {
        let th = config.thresholds
        let tz = DerivedDay.timeZone(inp.timeZone)
        let srcs = inp.sources
        let names = srcs.keys.sorted(by: DerivedMath.pyLess)
        var hourly: [Double] = []
        for h in 0..<24 {
            let vals = names.compactMap { srcs[$0]!.hourly[h] }
            hourly.append(vals.max() ?? 0.0)
        }
        var dedup = 0.0
        for v in hourly { dedup += v }
        let total = inp.stepsTotal ?? dedup
        var band = 0
        for c in th.stepBands where total >= c { band += 1 }
        var active = 0
        for h in th.activeHourFirst...th.activeHourLast where hourly[h] >= th.activeHourSteps { active += 1 }
        var out = DayResult(dedupSteps: DerivedMath.roundTo(dedup, 0), stepBandIndex: band, activeHours: active)
        let phone = names.filter { srcs[$0]!.kind == "phone" }
        let wear = names.filter { srcs[$0]!.kind == "wearable" }
        if let p = phone.first, let w = wear.first {
            let pt = sumHours(srcs[p]!.hourly)
            let wt = sumHours(srcs[w]!.hourly)
            if wt >= th.phoneMinWearableSteps && pt < th.phoneRatio * wt {
                out.phoneMissingSteps = DerivedMath.roundTo(wt - pt, 0)
            }
        }
        if let ms = inp.minuteSteps {
            let steps = DerivedMath.minuteMap(ms)
            let d0 = DerivedDay.dayStartMs(inp.day, tz)
            let d1 = DerivedDay.dayStartMs(DerivedDay.addDays(inp.day, 1), tz)
            let mins = steps.keys.sorted().filter { d0 <= $0 && $0 < d1 }.map { steps[$0]! }
            out.briskMinutes = mins.filter { $0 >= th.briskCadence }.count
            var top = Array(mins.sorted(by: >).prefix(th.peakMinutes))
            while top.count < th.peakMinutes { top.append(0.0) }
            out.peak30Cadence = DerivedMath.roundTo(DerivedMath.mean(top), 1)
            if let wearSeries = inp.wear {
                let worn = DerivedMath.minuteMap(wearSeries, lo: th.hrValidMin, hi: th.hrValidMax)
                var best = 0, run = 0
                var t = d0
                while t < d1 {
                    let h = DerivedDay.localTime(t, tz).hour
                    if th.activeHourFirst <= h && h <= th.activeHourLast && worn[t] != nil && (steps[t] ?? 0.0) == 0 {
                        run += 1
                        best = max(best, run)
                    } else {
                        run = 0
                    }
                    t += DerivedMath.minute
                }
                out.longestSedentaryMin = best
            }
        }
        return out
    }

    // MARK: Streaks

    struct StreakResult: DerivedOutput {
        var current: Int
        var best: Int
        var jsonObject: [String: Any] { ["current": current, "best": best] }
    }

    /// `series`: {day: steps}.
    static func stepStreak(series s: [String: Double], day: String, goal: Double, windowDays: Int) -> StreakResult {
        if goal <= 0 { return StreakResult(current: 0, best: 0) }
        func met(_ d: String) -> Bool { (s[d] ?? 0) >= goal }
        var cur = 0
        var d = met(day) ? day : DerivedDay.addDays(day, -1)
        while met(d) {
            cur += 1
            d = DerivedDay.addDays(d, -1)
        }
        var best = 0, run = 0
        for i in Swift.stride(from: windowDays - 1, through: 0, by: -1) {
            if met(DerivedDay.addDays(day, -i)) {
                run += 1
                best = max(best, run)
            } else {
                run = 0
            }
        }
        return StreakResult(current: cur, best: max(best, cur))
    }

    // MARK: Stride

    struct StrideResult: DerivedOutput {
        var strideM: Double?
        var expectedM: Double?
        var ratioPct: Double?

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["stride_m": j(strideM), "expected_m": j(expectedM), "ratio_pct": j(ratioPct)]
        }
    }

    /// `distanceM` and `steps` from the same phone source.
    static func stride(distanceM: Double?, steps: Double?, heightCm: Double?, sex: String?, config: DerivedConfig) -> StrideResult {
        let th = config.thresholds
        let f = DerivedConfig.Thresholds.bySex(th.strideFactor, sex)
        var height: Double?
        if let h = heightCm, h != 0 { height = h }
        let expected = height.map { DerivedMath.roundTo(f * $0 / 100.0, 2) }
        guard let steps, steps != 0, steps >= th.strideMinSteps, let distanceM else {
            return StrideResult(expectedM: expected)
        }
        let s = distanceM / steps
        let ratio: Double? = (expected != nil && expected != 0) ? DerivedMath.roundTo(s * 100.0 / (f * height! / 100.0), 1) : nil
        return StrideResult(strideM: DerivedMath.roundTo(s, 2), expectedM: expected, ratioPct: ratio)
    }
}
