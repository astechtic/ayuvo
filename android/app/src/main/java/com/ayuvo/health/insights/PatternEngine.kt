package com.ayuvo.health.insights

import com.ayuvo.health.insights.InsightsMath.fill
import com.ayuvo.health.insights.InsightsMath.mean
import com.ayuvo.health.insights.InsightsMath.roundTo
import com.ayuvo.health.insights.InsightsMath.sampleSd
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ayuvo Patterns (docs/insights.md, "How patterns are found"): for each configured
 * exposure/outcome pair, days are split into exposed and unexposed over the last 120 days and
 * compared with Welch's t and Cohen's d. A pattern is surfaced only when both pass their
 * thresholds, and it always reads as an association, never a cause. Ported from `patterns`.
 */
object PatternEngine {

    /** day → exposed (true), unexposed (false) or not measurable (null). */
    private fun exposure(name: String, inputs: InsightsInputs, cfg: InsightsConfig, asOf: LocalDate): (LocalDate) -> Boolean? {
        val pc = cfg.patterns
        val zone = inputs.timeZone
        val targets = inputs.targets
        return when (name) {
            "late_intense_workout" -> {
                val t = cfg.trainingLoad
                val late = HashSet<LocalDate>()
                for (s in TrainingLoad.sessions(inputs.workouts, zone, cfg)) {
                    val endLate = InsightsMath.localDayOf(s.endMs, zone) != s.day || InsightsMath.localMinutesOf(s.endMs, zone) >= t.lateHour * 60
                    val intense = s.intensity >= t.intenseMinIntensity || (!s.hasEffort && s.minutes >= t.intenseMinMinutesWithoutEffort)
                    if (endLate && intense) late += s.day
                }
                return { d -> d in late }
            }
            "high_load" -> {
                val loads = TrainingLoad.dailyLoads(inputs.workouts, zone, cfg)
                return { d -> TrainingLoad.raw(loads, d, cfg).category == "high" }
            }
            "water_goal_met" -> {
                val goal = targets["water_ml"]
                return { d ->
                    val w = inputs.waterMl[d]
                    if (goal == null || goal <= 0 || w == null || w <= 0) null else w >= goal
                }
            }
            "protein_target_met" -> {
                val goal = targets["protein_g"]
                return { d ->
                    val x = inputs.nutrition[d]
                    val cal = x?.get("calories")
                    val protein = x?.get("protein_g")
                    if (goal == null || goal <= 0 || x == null || cal == null || cal <= 0 || protein == null) null else protein >= goal
                }
            }
            "short_sleep" -> {
                val nights = BaselineEngine.sleepSeries(inputs, cfg)
                val start = asOf.minusDays(pc.windowDays.toLong())
                val vals = InsightsMath.values(nights, start, 0, pc.windowDays - 1).map { it.second }
                if (vals.size < cfg.metric("sleep").minPoints) return { null }
                val cut = mean(vals) - pc.shortSleepMarginMin
                return { d -> nights[d]?.let { it < cut } }
            }
            else -> error("unknown exposure $name")
        }
    }

    private fun outcome(name: String, inputs: InsightsInputs, cfg: InsightsConfig): Map<LocalDate, Double> = when (name) {
        "sleep_minutes" -> BaselineEngine.sleepSeries(inputs, cfg)
        "recovery_score" -> inputs.recoveryScores
        "strength_volume" -> inputs.strengthVolume.filterValues { it > 0 }
        "steps" -> inputs.series("steps")
        else -> error("unknown outcome $name")
    }

    /** Exposure days as of − window … as of − 1, each paired with the outcome `lag_days` later. */
    fun patterns(inputs: InsightsInputs, asOf: LocalDate, cfg: InsightsConfig): List<PatternResult> {
        val pc = cfg.patterns
        return pc.pairs.map { pair ->
            val expo = exposure(pair.exposure, inputs, cfg, asOf)
            val outc = outcome(pair.outcome, inputs, cfg)
            val partial = pair.outcome in pc.partialTodayOutcomes
            val ex = ArrayList<Double>()
            val un = ArrayList<Double>()
            for (k in pc.windowDays downTo 1) {
                val d = asOf.minusDays(k.toLong())
                val o = d.plusDays(pair.lagDays.toLong())
                if (InsightsMath.daysBetween(o, asOf) < (if (partial) 1 else 0)) continue
                val e = expo(d) ?: continue
                val y = outc[o] ?: continue
                if (e) ex += y else un += y
            }
            if (ex.size < pc.minGroup || un.size < pc.minGroup) {
                return@map PatternResult(pair.id, "insufficient", ex.size, un.size, pc.minGroup, null, null, null, null, null, false, null, pair.reviewCategory)
            }
            val me = mean(ex)
            val mu = mean(un)
            val sdE = sampleSd(ex, me)
            val sdU = sampleSd(un, mu)
            val ve = sdE * sdE
            val vu = sdU * sdU
            val diff = me - mu
            val se = sqrt(ve / ex.size + vu / un.size)
            val t = if (se > 0) diff / se else null
            val pooled = sqrt(((ex.size - 1) * ve + (un.size - 1) * vu) / (ex.size + un.size - 2))
            val d = if (pooled > 0) diff / pooled else null
            val surfaced = t != null && d != null && abs(t) >= pc.minAbsT && abs(d) >= pc.minAbsD
            val params = mapOf(
                "abs_diff" to roundTo(abs(diff), pair.decimals), "unit" to pair.unit,
                "direction_word" to if (diff > 0) pair.moreWord else pair.lessWord,
                "n_exposed" to ex.size, "n_unexposed" to un.size
            )
            PatternResult(
                id = pair.id, status = "ok", nExposed = ex.size, nUnexposed = un.size, needed = pc.minGroup,
                meanExposed = roundTo(me, 1), meanUnexposed = roundTo(mu, 1), diff = roundTo(diff, 1),
                t = roundTo(t, 2), d = roundTo(d, 2), surfaced = surfaced, text = fill(pair.template, params),
                reviewCategory = pair.reviewCategory
            )
        }
    }
}
