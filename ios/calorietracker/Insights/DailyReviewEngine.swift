import Foundation

// Daily Health Review: area scores, a Day Score and rule items from the config templates. Port of the "Daily
// Health Review" section of `scripts/insights_reference.py`; rule functions follow `RULES` one to one.

nonisolated enum DailyReviewEngine {
    static let categories = ["went_well", "needs_attention", "improve", "reduce"]

    /// Rule ids this engine implements (every config rule must be one of them).
    static let ruleIDs: Set<String> = [
        "calories_on_target", "protein_met", "fiber_met", "water_goal_met", "steps_goal_met", "workout_done",
        "planned_rest", "sleep_good", "recovery_good", "fasting_goal_met", "calories_over", "calories_under",
        "protein_low", "sleep_short", "sleep_below_usual", "recovery_low", "steps_low", "water_low",
        "improve_protein", "improve_fiber", "improve_steps", "improve_water", "improve_sleep", "recovery_focus",
        "lighter_tomorrow", "reduce_nutrient_vs_average", "reduce_nutrient", "reduce_pattern",
    ]

    private typealias Params = [String: InsightsParam]

    private struct Context {
        var food: InsightsNutritionDay?
        var targets: InsightsTargets
        var water: Double?
        var hydration: Bool
        var steps: Double?
        var activity: Bool
        var training: Bool
        var minutes: Double
        var prev: TrainingLoadEngine.Raw
        var night: InsightsNight?
        var sleepB: BaselineEngine.Raw
        var rec: RecoveryResult?
        var fast: Double?
        var fasting: Bool
        var loads: [String: TrainingLoadEngine.DayLoad]
        var config: InsightsConfig
        var inputs: InsightsInputs
        var day: String
    }

    private static func pos(_ x: Double?) -> Bool { (x ?? 0) > 0 }

    private static func pctDev(_ value: Double, _ target: Double) -> Double { (value - target) / target * 100.0 }

    private static func linearScore(_ devAbs: Double, _ fullWithin: Double, _ zeroAt: Double) -> Double {
        if devAbs <= fullWithin { return 100.0 }
        return InsightsMath.clamp(100.0 * (zeroAt - devAbs) / (zeroAt - fullWithin), 0.0, 100.0)
    }

    private static func int(_ x: Double) -> InsightsParam { .number(Double(InsightsMath.roundInt(x))) }

    /// End-of-day review of `day`. See docs/insights.md.
    static func review(_ inputs: InsightsInputs, day: String, config: InsightsConfig) -> DailyReviewResult {
        let rv = config.dailyReview
        let tz = InsightsDay.timeZone(inputs.timeZone)
        let tracking = inputs.tracking
        let targets = inputs.targets
        let food = inputs.nutrition[day]
        let logged = food != nil && pos(food?.calories)
        let water = inputs.waterMl[day]
        let fast = inputs.fastingHours[day]
        let steps = inputs.series["steps"]?[day]
        let nights = BaselineEngine.validNights(inputs, config: config)
        let night = nights[day]
        let sleepB = BaselineEngine.baselineRaw(nights.mapValues(\.asleepMin), day, config.metrics["sleep"]!)
        let rec = inputs.recovery.flatMap { $0.status == "ok" ? $0 : nil }
        let loads = TrainingLoadEngine.dailyLoads(inputs.workouts, timeZone: tz, config: config)
        let todayLoad = loads[day] ?? TrainingLoadEngine.DayLoad()
        let prev = TrainingLoadEngine.loadRaw(loads, InsightsDay.add(day, -1), config: config)
        let nut = rv.nutrition

        // Area scores
        var scores: [String: Double] = [:]
        var details: [String: Params] = [:]
        if tracking.nutrition, logged, let food {
            var checks: [Double] = []
            var det: Params = ["calories": InsightsParam(food.calories)]
            if let t = targets.calories, t > 0, let c = food.calories {
                let dev = pctDev(c, t)
                checks.append(linearScore(abs(dev), nut.calorieTolerancePct, nut.calorieZeroAtPct))
                det["calorie_dev_pct"] = .number(InsightsMath.roundTo(dev, 1))
            }
            if let t = targets.proteinG, t > 0, let p = food.proteinG {
                checks.append(min(100.0, 100.0 * p / (nut.proteinMinShare * t)))
                det["protein_g"] = .number(p)
            }
            if let t = targets.fiberG, t > 0, let f = food.fiberG {
                checks.append(min(100.0, 100.0 * f / t))
                det["fiber_g"] = .number(f)
            }
            for key in ["carbs_g", "fat_g"] {
                if let t = targets.value(key), t > 0, let v = food.value(key) {
                    checks.append(linearScore(abs(pctDev(v, t)), nut.macroTolerancePct, nut.macroZeroAtPct))
                    det[key] = .number(v)
                }
            }
            if !checks.isEmpty {
                scores["nutrition"] = InsightsMath.mean(checks)
                details["nutrition"] = det
            }
        }
        if tracking.water, let water, water > 0, let goal = targets.waterMl, goal > 0 {
            scores["hydration"] = min(100.0, 100.0 * water / goal)
            details["hydration"] = ["water_ml": .number(water), "goal_ml": .number(goal)]
        }
        if let steps, let goal = targets.steps, goal > 0 {
            scores["activity"] = min(100.0, 100.0 * steps / goal)
            details["activity"] = ["steps": .number(steps), "goal": .number(goal)]
        }
        if tracking.workouts {
            let s: Double
            if todayLoad.minutes > 0 {
                s = 100.0
            } else if prev.category == "high" {
                s = 100.0
            } else {
                s = rv.trainingRestScore
            }
            scores["training"] = s
            details["training"] = ["minutes": .number(InsightsMath.roundTo(todayLoad.minutes, 1)),
                                   "sessions": InsightsParam(todayLoad.sessions),
                                   "previous_day_category": .text(prev.category)]
        }
        if let night {
            scores["sleep"] = min(100.0, 100.0 * night.asleepMin / rv.sleepTargetMin)
            details["sleep"] = ["asleep_min": .number(night.asleepMin),
                                "baseline_min": sleepB.isOK ? InsightsParam(InsightsMath.roundTo(sleepB.mean, 1)) : .null]
        }
        if let rec, let score = rec.score {
            scores["recovery"] = Double(score)
            details["recovery"] = ["score": InsightsParam(score), "label": rec.label.map(InsightsParam.text) ?? .null]
        }
        if tracking.fasting, let fast, fast > 0, let goal = targets.fastingHours, goal > 0 {
            scores["fasting"] = min(100.0, 100.0 * fast / goal)
            details["fasting"] = ["hours": .number(fast), "goal_hours": .number(goal)]
        }

        var areas: [ReviewArea] = []
        var notLogged: [ReviewItem] = []
        var wsum = 0.0, acc = 0.0
        let tracked: [String: Bool] = [
            "nutrition": tracking.nutrition, "hydration": tracking.water, "activity": true,
            "training": tracking.workouts, "sleep": true, "recovery": true, "fasting": tracking.fasting,
        ]
        for a in rv.areas {
            let score = scores[a.id]
            areas.append(ReviewArea(id: a.id, included: score != nil, score: InsightsMath.roundInt(score),
                                    weight: a.weight, detail: details[a.id]))
            if let score {
                wsum += a.weight
                acc += a.weight * score
            } else if tracked[a.id] ?? false {
                let params: Params = ["area": .text(a.id), "area_label": .text(a.label)]
                notLogged.append(ReviewItem(ruleId: "not_logged", params: params,
                                            text: InsightsFormat.fill(rv.notLoggedTemplate, params)))
            }
        }
        let dayScore = wsum > 0 ? InsightsMath.roundInt(acc / wsum) : nil

        // Rules
        let ctx = Context(
            food: logged && scores["nutrition"] != nil ? food : nil, targets: targets, water: water,
            hydration: scores["hydration"] != nil, steps: steps, activity: scores["activity"] != nil,
            training: scores["training"] != nil, minutes: todayLoad.minutes, prev: prev, night: night, sleepB: sleepB,
            rec: rec, fast: fast, fasting: scores["fasting"] != nil, loads: loads, config: config, inputs: inputs,
            day: day)
        var items: [String: [ReviewItem]] = [:]
        for rule in rv.rules {
            for params in apply(rule, ctx) {
                items[rule.category, default: []].append(
                    ReviewItem(ruleId: rule.id, params: params, text: InsightsFormat.fill(rule.template, params)))
            }
        }
        let cap = rv.maxItemsPerCategory
        func capped(_ c: String) -> [ReviewItem] { Array((items[c] ?? []).prefix(cap)) }
        return DailyReviewResult(day: day, dayScore: dayScore, areas: areas, notLogged: notLogged,
                                 wentWell: capped("went_well"), needsAttention: capped("needs_attention"),
                                 improve: capped("improve"), reduce: capped("reduce"))
    }

    // MARK: Rules

    private static func target(_ ctx: Context, _ key: String) -> Double? {
        let t = ctx.targets.value(key)
        return pos(t) ? t : nil
    }

    private static func calDev(_ ctx: Context) -> Double? {
        guard let c = ctx.food?.calories, let t = target(ctx, "calories") else { return nil }
        return pctDev(c, t)
    }

    private static func protein(_ ctx: Context) -> (Double, Double)? {
        guard let p = ctx.food?.proteinG, let t = target(ctx, "protein_g") else { return nil }
        return (p, t)
    }

    private static func fiber(_ ctx: Context) -> (Double, Double)? {
        guard let f = ctx.food?.fiberG, let g = target(ctx, "fiber_g") else { return nil }
        return (f, g)
    }

    private static func calorieParams(_ ctx: Context) -> Params {
        ["calories": int(ctx.food?.calories ?? 0), "target": int(target(ctx, "calories") ?? 0)]
    }

    private static func apply(_ rule: InsightsConfig.DailyReview.Rule, _ ctx: Context) -> [Params] {
        let tol = ctx.config.dailyReview.nutrition.calorieTolerancePct
        let t = ctx.targets
        switch rule.id {
        case "calories_on_target":
            guard let d = calDev(ctx), abs(d) <= tol else { return [] }
            return [calorieParams(ctx)]
        case "calories_over":
            guard let d = calDev(ctx), d > tol else { return [] }
            var p = calorieParams(ctx)
            p["over_pct"] = int(d)
            return [p]
        case "calories_under":
            guard let d = calDev(ctx), d < -tol else { return [] }
            var p = calorieParams(ctx)
            p["under_pct"] = int(-d)
            return [p]
        case "protein_met":
            guard let (p, g) = protein(ctx), p >= (rule.minShare ?? 0) * g else { return [] }
            return [["protein_g": int(p), "pct": int(100.0 * p / g)]]
        case "protein_low":
            guard let (p, g) = protein(ctx), p < (rule.belowShare ?? 0) * g else { return [] }
            return [["protein_g": int(p), "pct": int(100.0 * p / g)]]
        case "improve_protein":
            guard let (p, g) = protein(ctx), p < (rule.belowShare ?? 0) * g else { return [] }
            return [["gap_g": int(g - p)]]
        case "fiber_met":
            guard let (f, g) = fiber(ctx), f >= g else { return [] }
            return [["fiber_g": int(f), "goal_g": int(g)]]
        case "improve_fiber":
            guard let (f, g) = fiber(ctx), f < g else { return [] }
            return [["gap_g": int(g - f)]]
        case "water_goal_met":
            guard ctx.hydration, let w = ctx.water, let goal = t.waterMl, w >= goal else { return [] }
            return [["water_ml": int(w), "goal_ml": int(goal)]]
        case "water_low":
            guard ctx.hydration, let w = ctx.water, let goal = t.waterMl, w < (rule.belowShare ?? 0) * goal else { return [] }
            return [["water_ml": int(w), "goal_ml": int(goal)]]
        case "improve_water":
            guard ctx.hydration, let w = ctx.water, let goal = t.waterMl, w < goal else { return [] }
            return [["gap_ml": int(goal - w)]]
        case "steps_goal_met":
            guard ctx.activity, let s = ctx.steps, let goal = t.steps, s >= goal else { return [] }
            return [["steps": int(s), "goal": int(goal)]]
        case "steps_low":
            guard ctx.activity, let s = ctx.steps, let goal = t.steps, s < (rule.belowShare ?? 0) * goal else { return [] }
            return [["steps": int(s), "goal": int(goal)]]
        case "improve_steps":
            guard ctx.activity, let s = ctx.steps, let goal = t.steps, s < goal else { return [] }
            let gap = goal - s
            let step = rule.walkRoundMin ?? 5
            let walk = max(step, Int((gap / (rule.stepsPerMinute ?? 100) / Double(step)).rounded(.up)) * step)
            return [["gap": int(gap), "walk_min": InsightsParam(walk)]]
        case "workout_done":
            guard ctx.training, ctx.minutes > 0 else { return [] }
            return [["minutes": int(ctx.minutes)]]
        case "planned_rest":
            guard ctx.training, ctx.minutes == 0, ctx.prev.category == "high" else { return [] }
            return [[:]]
        case "lighter_tomorrow":
            guard ctx.training, ctx.minutes != 0 else { return [] }
            let today = TrainingLoadEngine.loadRaw(ctx.loads, ctx.day, config: ctx.config)
            return today.category == "high" ? [[:]] : []
        case "sleep_good":
            guard let n = ctx.night, n.asleepMin >= (rule.minMinutes ?? 0) else { return [] }
            return [["duration": .text(InsightsFormat.duration(n.asleepMin))]]
        case "sleep_short":
            guard let n = ctx.night, n.asleepMin < (rule.belowMinutes ?? 0) else { return [] }
            return [["duration": .text(InsightsFormat.duration(n.asleepMin))]]
        case "sleep_below_usual":
            guard let n = ctx.night, ctx.sleepB.isOK, let mean = ctx.sleepB.mean,
                  n.asleepMin < mean - (rule.marginMinutes ?? 0) else { return [] }
            return [["duration": .text(InsightsFormat.duration(n.asleepMin)), "diff_min": int(mean - n.asleepMin)]]
        case "improve_sleep":
            let goal = ctx.config.dailyReview.sleepTargetMin
            guard let n = ctx.night, n.asleepMin < goal else { return [] }
            let step = rule.roundMin ?? 15
            let mins = min(rule.maxMinutes ?? 60, Int(((goal - n.asleepMin) / Double(step)).rounded(.up)) * step)
            return [["minutes": InsightsParam(mins)]]
        case "recovery_good":
            guard let r = ctx.rec, r.label == "good", let s = r.score else { return [] }
            return [["score": InsightsParam(s)]]
        case "recovery_low":
            guard let r = ctx.rec, r.label == "low", let s = r.score else { return [] }
            return [["score": InsightsParam(s)]]
        case "recovery_focus":
            guard let r = ctx.rec, r.label == "low" else { return [] }
            return [[:]]
        case "fasting_goal_met":
            guard ctx.fasting, let f = ctx.fast, let goal = t.fastingHours, f >= goal else { return [] }
            return [["hours": .number(InsightsMath.roundTo(f, 1)), "goal_hours": .number(InsightsMath.roundTo(goal, 1))]]
        case "reduce_nutrient_vs_average":
            return reduce(ctx, withAverage: true)
        case "reduce_nutrient":
            return reduce(ctx, withAverage: false)
        case "reduce_pattern":
            let ids = Set(ctx.config.patterns.pairs.filter { $0.reviewCategory == "reduce" }.map(\.id))
            return (ctx.inputs.patterns ?? []).filter { $0.surfaced && ids.contains($0.id) }
                .map { ["pattern_text": .text($0.text ?? "")] }
        default:
            return []
        }
    }

    private static func nutrientAverage(_ ctx: Context, _ key: String) -> Double? {
        let rv = ctx.config.dailyReview
        var vals: [Double] = []
        for k in stride(from: rv.averageWindowDays, to: 0, by: -1) {
            if let f = ctx.inputs.nutrition[InsightsDay.add(ctx.day, -k)], pos(f.calories), let v = f.value(key) {
                vals.append(v)
            }
        }
        return vals.count >= rv.averageMinDays ? InsightsMath.mean(vals) : nil
    }

    private static func reduce(_ ctx: Context, withAverage: Bool) -> [Params] {
        var out: [Params] = []
        for n in ctx.config.dailyReview.reduceNutrients {
            guard let v = ctx.food?.value(n.id), let g = target(ctx, n.goalKey), v > g else { continue }
            let avg = nutrientAverage(ctx, n.id)
            let aboveAvg = avg.map { v > $0 } ?? false
            if aboveAvg != withAverage { continue }
            var p: Params = ["nutrient": .text(n.label), "value": int(v), "goal": int(g), "unit": .text(n.unit)]
            if withAverage, let avg { p["average"] = int(avg) }
            out.append(p)
        }
        return out
    }
}
