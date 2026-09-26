import Foundation

// Ayuvo Patterns: exposure/outcome pairs over the last 120 days compared with Welch's t and Cohen's d. Port of
// the "Pattern engine" section of `scripts/insights_reference.py`. Wording stays associational.

nonisolated enum PatternEngine {
    /// day → exposed (true), unexposed (false) or not measurable (nil).
    private static func exposure(_ name: String, _ inputs: InsightsInputs, _ config: InsightsConfig,
                                 _ asOf: String) -> (String) -> Bool? {
        let pc = config.patterns
        let tz = InsightsDay.timeZone(inputs.timeZone)
        let targets = inputs.targets
        switch name {
        case "late_intense_workout":
            let t = config.trainingLoad
            var late = Set<String>()
            for s in TrainingLoadEngine.sessions(inputs.workouts, timeZone: tz, config: config) {
                let endLate = InsightsDay.localDay(ms: s.endMs, tz) != s.day
                    || InsightsDay.localMinutes(ms: s.endMs, tz) >= t.lateHour * 60
                let intense = s.intensity >= t.intenseMinIntensity
                    || (!s.hasEffort && s.minutes >= t.intenseMinMinutesWithoutEffort)
                if endLate && intense { late.insert(s.day) }
            }
            return { late.contains($0) }
        case "high_load":
            let loads = TrainingLoadEngine.dailyLoads(inputs.workouts, timeZone: tz, config: config)
            return { TrainingLoadEngine.loadRaw(loads, $0, config: config).category == "high" }
        case "water_goal_met":
            let water = inputs.waterMl
            let goal = targets.waterMl
            return { d in
                guard let goal, goal > 0, let w = water[d], w > 0 else { return nil }
                return w >= goal
            }
        case "protein_target_met":
            let food = inputs.nutrition
            let goal = targets.proteinG
            return { d in
                guard let goal, goal > 0, let x = food[d], (x.calories ?? 0) > 0, let p = x.proteinG else { return nil }
                return p >= goal
            }
        case "short_sleep":
            let nights = BaselineEngine.sleepSeries(inputs, config: config)
            let start = InsightsDay.add(asOf, -pc.windowDays)
            let vals = BaselineEngine.values(nights, start, 0, pc.windowDays - 1).map(\.1)
            if vals.count < (config.metrics["sleep"]?.minPoints ?? 14) { return { _ in nil } }
            let cut = InsightsMath.mean(vals) - pc.shortSleepMarginMin
            return { d in nights[d].map { $0 < cut } }
        default:
            return { _ in nil }
        }
    }

    private static func outcome(_ name: String, _ inputs: InsightsInputs, _ config: InsightsConfig) -> [String: Double] {
        switch name {
        case "sleep_minutes": BaselineEngine.sleepSeries(inputs, config: config)
        case "recovery_score": inputs.recoveryScores
        case "strength_volume": inputs.strengthVolume.filter { $0.value > 0 }
        case "steps": inputs.series["steps"] ?? [:]
        default: [:]
        }
    }

    /// Exposure days asOf−window … asOf−1, each paired with its lagged outcome.
    static func patterns(_ inputs: InsightsInputs, asOf: String, config: InsightsConfig) -> [PatternResult] {
        let pc = config.patterns
        var out: [PatternResult] = []
        for pair in pc.pairs {
            let expo = exposure(pair.exposure, inputs, config, asOf)
            let outc = outcome(pair.outcome, inputs, config)
            let partial = pc.partialTodayOutcomes.contains(pair.outcome)
            var ex: [Double] = [], un: [Double] = []
            for k in stride(from: pc.windowDays, to: 0, by: -1) {
                let d = InsightsDay.add(asOf, -k)
                let o = InsightsDay.add(d, pair.lagDays)
                if InsightsDay.between(o, asOf) < (partial ? 1 : 0) { continue }
                guard let e = expo(d), let y = outc[o] else { continue }
                if e { ex.append(y) } else { un.append(y) }
            }
            var item = PatternResult(id: pair.id, status: "insufficient", nExposed: ex.count, nUnexposed: un.count,
                                     needed: pc.minGroup, surfaced: false, reviewCategory: pair.reviewCategory)
            if ex.count >= pc.minGroup && un.count >= pc.minGroup {
                let me = InsightsMath.mean(ex), mu = InsightsMath.mean(un)
                let sdE = InsightsMath.sampleSD(ex, me), sdU = InsightsMath.sampleSD(un, mu)
                let ve = sdE * sdE, vu = sdU * sdU
                let diff = me - mu
                let se = (ve / Double(ex.count) + vu / Double(un.count)).squareRoot()
                let t: Double? = se > 0 ? diff / se : nil
                let pooled = ((Double(ex.count - 1) * ve + Double(un.count - 1) * vu) / Double(ex.count + un.count - 2)).squareRoot()
                let d: Double? = pooled > 0 ? diff / pooled : nil
                var surfaced = false
                if let t, let d { surfaced = abs(t) >= pc.minAbsT && abs(d) >= pc.minAbsD }
                let params: [String: InsightsParam] = [
                    "abs_diff": .number(InsightsMath.roundTo(abs(diff), pair.decimals)), "unit": .text(pair.unit),
                    "direction_word": .text(diff > 0 ? pair.moreWord : pair.lessWord),
                    "n_exposed": InsightsParam(ex.count), "n_unexposed": InsightsParam(un.count),
                ]
                item.status = "ok"
                item.meanExposed = InsightsMath.roundTo(me, 1)
                item.meanUnexposed = InsightsMath.roundTo(mu, 1)
                item.diff = InsightsMath.roundTo(diff, 1)
                item.t = InsightsMath.roundTo(t, 2)
                item.d = InsightsMath.roundTo(d, 2)
                item.surfaced = surfaced
                item.text = InsightsFormat.fill(pair.template, params)
            }
            out.append(item)
        }
        return out
    }
}
