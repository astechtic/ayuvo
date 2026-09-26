package com.ayuvo.health.insights

import com.ayuvo.health.insights.InsightsMath.clamp
import com.ayuvo.health.insights.InsightsMath.fill
import com.ayuvo.health.insights.InsightsMath.fmtDuration
import com.ayuvo.health.insights.InsightsMath.mean
import com.ayuvo.health.insights.InsightsMath.roundInt
import com.ayuvo.health.insights.InsightsMath.roundTo
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The Daily Health Review (docs/insights.md, "How the Daily Review works"): area scores for what
 * was tracked and logged, a weighted Day Score, and config rules that produce "went well", "needs
 * attention", "what to improve" and "consider reducing" items. Areas without data become a neutral
 * "not logged" note and never lower the score. Ported from `daily_review` and `RULES`.
 */
object DailyReviewEngine {

    private class Ctx(
        val food: Map<String, Double>?,
        val targets: Map<String, Double>,
        val water: Double?,
        val hydration: Boolean,
        val steps: Double?,
        val activity: Boolean,
        val training: Boolean,
        val minutes: Double,
        val prev: TrainingLoad.Raw,
        val night: SleepInput?,
        val sleepB: BaselineEngine.Raw,
        val rec: RecoveryResult?,
        val fast: Double?,
        val fasting: Boolean,
        val cfg: InsightsConfig,
        val inputs: InsightsInputs,
        val day: LocalDate
    ) {
        fun food(key: String): Double? = food?.get(key)
        fun target(key: String): Double? = targets[key]?.takeIf { it > 0 }
    }

    private fun pos(x: Double?): Boolean = x != null && x > 0

    private fun pctDev(value: Double, target: Double): Double = (value - target) / target * 100.0

    private fun linearScore(devAbs: Double, fullWithin: Double, zeroAt: Double): Double =
        if (devAbs <= fullWithin) 100.0 else clamp(100.0 * (zeroAt - devAbs) / (zeroAt - fullWithin), 0.0, 100.0)

    fun review(inputs: InsightsInputs, day: LocalDate, cfg: InsightsConfig): DailyReviewResult {
        val rv = cfg.dailyReview
        val tracking = inputs.tracking
        val targets = inputs.targets
        val food = inputs.nutrition[day]
        val logged = food != null && pos(food["calories"])
        val water = inputs.waterMl[day]
        val fast = inputs.fastingHours[day]
        val steps = inputs.series("steps")[day]
        val nights = BaselineEngine.validNights(inputs, cfg)
        val night = nights[day]
        val sleepB = BaselineEngine.raw(nights.mapValues { it.value.asleepMin!! }, day, cfg.metric("sleep"))
        val rec = inputs.recovery?.takeIf { it.ok }
        val loads = TrainingLoad.dailyLoads(inputs.workouts, inputs.timeZone, cfg)
        val todayLoad = loads[day] ?: TrainingLoad.DayLoad(0.0, 0.0, 0)
        val prev = TrainingLoad.raw(loads, day.minusDays(1), cfg)

        // -- Area scores --
        val scores = LinkedHashMap<String, Double>()
        val details = HashMap<String, Map<String, Any?>>()
        if (tracking.nutrition && logged) {
            val checks = ArrayList<Double>()
            val det = LinkedHashMap<String, Any?>()
            det["calories"] = food!!["calories"]
            val calTarget = targets["calories"]
            if (pos(calTarget)) {
                val dev = pctDev(food.getValue("calories"), calTarget!!)
                checks += linearScore(abs(dev), rv.calorieTolerancePct, rv.calorieZeroAtPct)
                det["calorie_dev_pct"] = roundTo(dev, 1)
            }
            val protein = food["protein_g"]
            if (pos(targets["protein_g"]) && protein != null) {
                checks += minOf(100.0, 100.0 * protein / (rv.proteinMinShare * targets.getValue("protein_g")))
                det["protein_g"] = protein
            }
            val fiber = food["fiber_g"]
            if (pos(targets["fiber_g"]) && fiber != null) {
                checks += minOf(100.0, 100.0 * fiber / targets.getValue("fiber_g"))
                det["fiber_g"] = fiber
            }
            for (key in listOf("carbs_g", "fat_g")) {
                val v = food[key]
                if (pos(targets[key]) && v != null) {
                    checks += linearScore(abs(pctDev(v, targets.getValue(key))), rv.macroTolerancePct, rv.macroZeroAtPct)
                    det[key] = v
                }
            }
            if (checks.isNotEmpty()) {
                scores["nutrition"] = mean(checks)
                details["nutrition"] = det
            }
        }
        if (tracking.water && pos(water) && pos(targets["water_ml"])) {
            scores["hydration"] = minOf(100.0, 100.0 * water!! / targets.getValue("water_ml"))
            details["hydration"] = linkedMapOf("water_ml" to water, "goal_ml" to targets["water_ml"])
        }
        if (steps != null && pos(targets["steps"])) {
            scores["activity"] = minOf(100.0, 100.0 * steps / targets.getValue("steps"))
            details["activity"] = linkedMapOf("steps" to steps, "goal" to targets["steps"])
        }
        if (tracking.workouts) {
            scores["training"] = when {
                todayLoad.minutes > 0 -> 100.0
                prev.category == "high" -> 100.0
                else -> rv.trainingRestScore
            }
            details["training"] = linkedMapOf(
                "minutes" to roundTo(todayLoad.minutes, 1), "sessions" to todayLoad.sessions, "previous_day_category" to prev.category
            )
        }
        if (night != null) {
            scores["sleep"] = minOf(100.0, 100.0 * night.asleepMin!! / rv.sleepTargetMin)
            details["sleep"] = linkedMapOf("asleep_min" to night.asleepMin, "baseline_min" to if (sleepB.ok) roundTo(sleepB.mean!!, 1) else null)
        }
        if (rec != null) {
            scores["recovery"] = rec.score!!.toDouble()
            details["recovery"] = linkedMapOf("score" to rec.score, "label" to rec.label)
        }
        if (tracking.fasting && pos(fast) && pos(targets["fasting_hours"])) {
            scores["fasting"] = minOf(100.0, 100.0 * fast!! / targets.getValue("fasting_hours"))
            details["fasting"] = linkedMapOf("hours" to fast, "goal_hours" to targets["fasting_hours"])
        }

        val tracked = mapOf(
            "nutrition" to tracking.nutrition, "hydration" to tracking.water, "activity" to true, "training" to tracking.workouts,
            "sleep" to true, "recovery" to true, "fasting" to tracking.fasting
        )
        val areas = ArrayList<ReviewArea>()
        val notLogged = ArrayList<ReviewItem>()
        var wsum = 0.0
        var acc = 0.0
        for (a in rv.areas) {
            val score = scores[a.id]
            areas += ReviewArea(a.id, score != null, score?.let { roundInt(it) }, a.weight, details[a.id])
            if (score != null) {
                wsum += a.weight
                acc += a.weight * score
            } else if (tracked[a.id] == true) {
                val params = mapOf<String, Any>("area" to a.id, "area_label" to a.label)
                notLogged += ReviewItem("not_logged", params, fill(rv.notLoggedTemplate, params))
            }
        }
        val dayScore = if (wsum > 0) roundInt(acc / wsum) else null

        // -- Rules --
        val ctx = Ctx(
            food = if (logged && "nutrition" in scores) food else null, targets = targets, water = water,
            hydration = "hydration" in scores, steps = steps, activity = "activity" in scores, training = "training" in scores,
            minutes = todayLoad.minutes, prev = prev, night = night, sleepB = sleepB, rec = rec, fast = fast,
            fasting = "fasting" in scores, cfg = cfg, inputs = inputs, day = day
        )
        val items = DailyReviewResult.CATEGORIES.associateWith { ArrayList<ReviewItem>() }
        for (rule in rv.rules) {
            for (params in apply(rule, ctx)) {
                items.getValue(rule.category) += ReviewItem(rule.id, params, fill(rule.template, params))
            }
        }
        val cap = rv.maxItemsPerCategory
        return DailyReviewResult(
            day = day, dayScore = dayScore, areas = areas, notLogged = notLogged,
            wentWell = items.getValue("went_well").take(cap), needsAttention = items.getValue("needs_attention").take(cap),
            improve = items.getValue("improve").take(cap), reduce = items.getValue("reduce").take(cap)
        )
    }

    private fun apply(rule: InsightsConfig.Rule, c: Ctx): List<Map<String, Any>> {
        val none = emptyList<Map<String, Any>>()
        val tol = c.cfg.dailyReview.calorieTolerancePct
        fun calDev(): Double? {
            val cal = c.food("calories")
            val t = c.target("calories")
            return if (cal == null || t == null) null else pctDev(cal, t)
        }
        fun calParams(): MutableMap<String, Any> =
            linkedMapOf("calories" to roundInt(c.food("calories")!!), "target" to roundInt(c.target("calories")!!))
        fun protein(): Pair<Double, Double>? {
            val p = c.food("protein_g")
            val t = c.target("protein_g")
            return if (p == null || t == null) null else p to t
        }
        fun fiber(): Pair<Double, Double>? {
            val f = c.food("fiber_g")
            val g = c.target("fiber_g")
            return if (f == null || g == null) null else f to g
        }
        val waterGoal = c.targets["water_ml"] ?: 0.0
        val stepGoal = c.targets["steps"] ?: 0.0
        return when (rule.id) {
            "calories_on_target" -> {
                val d = calDev()
                if (d == null || abs(d) > tol) none else listOf(calParams())
            }
            "calories_over" -> {
                val d = calDev()
                if (d == null || d <= tol) none else listOf(calParams().apply { put("over_pct", roundInt(d)) })
            }
            "calories_under" -> {
                val d = calDev()
                if (d == null || d >= -tol) none else listOf(calParams().apply { put("under_pct", roundInt(-d)) })
            }
            "protein_met" -> protein().let { pt ->
                if (pt == null || pt.first < rule.num("min_share") * pt.second) none
                else listOf(mapOf("protein_g" to roundInt(pt.first), "pct" to roundInt(100.0 * pt.first / pt.second)))
            }
            "protein_low" -> protein().let { pt ->
                if (pt == null || pt.first >= rule.num("below_share") * pt.second) none
                else listOf(mapOf("protein_g" to roundInt(pt.first), "pct" to roundInt(100.0 * pt.first / pt.second)))
            }
            "improve_protein" -> protein().let { pt ->
                if (pt == null || pt.first >= rule.num("below_share") * pt.second) none else listOf(mapOf("gap_g" to roundInt(pt.second - pt.first)))
            }
            "fiber_met" -> fiber().let { fg ->
                if (fg == null || fg.first < fg.second) none else listOf(mapOf("fiber_g" to roundInt(fg.first), "goal_g" to roundInt(fg.second)))
            }
            "improve_fiber" -> fiber().let { fg ->
                if (fg == null || fg.first >= fg.second) none else listOf(mapOf("gap_g" to roundInt(fg.second - fg.first)))
            }
            "water_goal_met" -> if (!c.hydration || c.water!! < waterGoal) none
                else listOf(mapOf("water_ml" to roundInt(c.water), "goal_ml" to roundInt(waterGoal)))
            "water_low" -> if (!c.hydration || c.water!! >= rule.num("below_share") * waterGoal) none
                else listOf(mapOf("water_ml" to roundInt(c.water), "goal_ml" to roundInt(waterGoal)))
            "improve_water" -> if (!c.hydration || c.water!! >= waterGoal) none else listOf(mapOf("gap_ml" to roundInt(waterGoal - c.water)))
            "steps_goal_met" -> if (!c.activity || c.steps!! < stepGoal) none
                else listOf(mapOf("steps" to roundInt(c.steps), "goal" to roundInt(stepGoal)))
            "steps_low" -> if (!c.activity || c.steps!! >= rule.num("below_share") * stepGoal) none
                else listOf(mapOf("steps" to roundInt(c.steps), "goal" to roundInt(stepGoal)))
            "improve_steps" -> {
                if (!c.activity || c.steps!! >= stepGoal) none else {
                    val gap = stepGoal - c.steps
                    val step = rule.num("walk_round_min").toInt()
                    val walk = maxOf(step, ceil(gap / rule.num("steps_per_minute") / step).toInt() * step)
                    listOf(mapOf("gap" to roundInt(gap), "walk_min" to walk))
                }
            }
            "workout_done" -> if (c.training && c.minutes > 0) listOf(mapOf("minutes" to roundInt(c.minutes))) else none
            "planned_rest" -> if (c.training && c.minutes == 0.0 && c.prev.category == "high") listOf(emptyMap()) else none
            "lighter_tomorrow" -> {
                if (!c.training || c.minutes == 0.0) none else {
                    val today = TrainingLoad.raw(TrainingLoad.dailyLoads(c.inputs.workouts, c.inputs.timeZone, c.cfg), c.day, c.cfg)
                    if (today.category == "high") listOf(emptyMap()) else none
                }
            }
            "sleep_good" -> c.night.let { n -> if (n == null || n.asleepMin!! < rule.num("min_minutes")) none else listOf(mapOf("duration" to fmtDuration(n.asleepMin))) }
            "sleep_short" -> c.night.let { n -> if (n == null || n.asleepMin!! >= rule.num("below_minutes")) none else listOf(mapOf("duration" to fmtDuration(n.asleepMin))) }
            "sleep_below_usual" -> {
                val n = c.night
                val b = c.sleepB
                if (n == null || !b.ok || n.asleepMin!! >= b.mean!! - rule.num("margin_minutes")) none
                else listOf(mapOf("duration" to fmtDuration(n.asleepMin), "diff_min" to roundInt(b.mean - n.asleepMin)))
            }
            "improve_sleep" -> {
                val n = c.night
                val target = c.cfg.dailyReview.sleepTargetMin
                if (n == null || n.asleepMin!! >= target) none else {
                    val step = rule.num("round_min").toInt()
                    listOf(mapOf("minutes" to minOf(rule.num("max_minutes").toInt(), ceil((target - n.asleepMin) / step).toInt() * step)))
                }
            }
            "recovery_good" -> c.rec?.takeIf { it.label == "good" }?.let { listOf(mapOf("score" to it.score!!)) } ?: none
            "recovery_low" -> c.rec?.takeIf { it.label == "low" }?.let { listOf(mapOf("score" to it.score!!)) } ?: none
            "recovery_focus" -> if (c.rec?.label == "low") listOf(emptyMap()) else none
            "fasting_goal_met" -> {
                val goal = c.targets["fasting_hours"] ?: 0.0
                if (!c.fasting || c.fast!! < goal) none else listOf(mapOf("hours" to roundTo(c.fast, 1), "goal_hours" to roundTo(goal, 1)))
            }
            "reduce_nutrient_vs_average" -> reduce(c, withAverage = true)
            "reduce_nutrient" -> reduce(c, withAverage = false)
            "reduce_pattern" -> {
                val ids = c.cfg.patterns.pairs.filter { it.reviewCategory == "reduce" }.map { it.id }.toSet()
                c.inputs.patterns.orEmpty().filter { it.surfaced && it.id in ids }.map { mapOf("pattern_text" to it.text!!) }
            }
            else -> error("no rule ${rule.id}")
        }
    }

    private fun nutrientAverage(c: Ctx, key: String): Double? {
        val rv = c.cfg.dailyReview
        val vals = ArrayList<Double>()
        for (k in rv.averageWindowDays downTo 1) {
            val f = c.inputs.nutrition[c.day.minusDays(k.toLong())] ?: continue
            val v = f[key]
            if (pos(f["calories"]) && v != null) vals += v
        }
        return if (vals.size >= rv.averageMinDays) mean(vals) else null
    }

    private fun reduce(c: Ctx, withAverage: Boolean): List<Map<String, Any>> {
        val out = ArrayList<Map<String, Any>>()
        for (n in c.cfg.dailyReview.reduceNutrients) {
            val v = c.food(n.id)
            val g = c.target(n.goalKey)
            if (v == null || g == null || v <= g) continue
            val avg = nutrientAverage(c, n.id)
            val aboveAvg = avg != null && v > avg
            if (aboveAvg != withAverage) continue
            val p = linkedMapOf<String, Any>("nutrient" to n.label, "value" to roundInt(v), "goal" to roundInt(g), "unit" to n.unit)
            if (withAverage) p["average"] = roundInt(avg!!)
            out += p
        }
        return out
    }
}
