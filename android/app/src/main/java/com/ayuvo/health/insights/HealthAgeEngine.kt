package com.ayuvo.health.insights

import com.ayuvo.health.insights.InsightsMath.clamp
import com.ayuvo.health.insights.InsightsMath.interp
import com.ayuvo.health.insights.InsightsMath.mean
import com.ayuvo.health.insights.InsightsMath.roundTo
import com.ayuvo.health.insights.InsightsMath.sampleSd
import com.ayuvo.health.insights.InsightsMath.sleepMidpointMin
import java.time.LocalDate

/**
 * Ayuvo Health Age (docs/insights.md, "How Ayuvo Health Age is estimated"): Ayuvo's own estimate,
 * not a clinical or biological age. Each marker's 90-day average becomes a capped years offset;
 * the weighted mean of the available offsets (capped at ±10) is added to the actual age. Ported
 * from `_marker`, `_health_age_raw`, `health_age` and `health_age_pace`.
 */
object HealthAgeEngine {

    private class MarkerValue(val value: Double?, val secondary: Double?, val basis: String?, val days: Int, val equivalentAge: Double?, val offset: Double?)

    private fun sexTable(tables: Map<String, Points>, sex: String?): Points {
        if (sex == "male" || sex == "female") return tables.getValue(sex)
        val m = tables.getValue("male")
        val f = tables.getValue("female")
        return m.indices.map { i -> m[i].first to (m[i].second + f[i].second) / 2.0 }
    }

    private fun window(series: Map<LocalDate, Double>, asOf: LocalDate, days: Int): List<Double> =
        InsightsMath.values(series, asOf, -(days - 1), 0).map { it.second }

    private fun marker(mk: InsightsConfig.Marker, inputs: InsightsInputs, asOf: LocalDate, profile: InsightsProfile, actual: Double, cfg: InsightsConfig): MarkerValue {
        val ha = cfg.healthAge
        val win = ha.windowDays
        val sex = profile.sex
        return when (mk.method) {
            "age_norm", "dose_response" -> {
                val vals = window(inputs.series(mk.series!!), asOf, win)
                if (vals.size < mk.minDays) return MarkerValue(null, null, null, vals.size, null, null)
                val v = mean(vals)
                if (mk.method == "dose_response") return MarkerValue(v, null, null, vals.size, null, interp(mk.points, v))
                val tables = if (mk.tablesByKind.isNotEmpty()) mk.tablesByKind.getValue(inputs.hrvKind) else mk.tables
                val eq = InsightsMath.inverseAge(sexTable(tables, sex), v, ha.ageMin, ha.ageMax)
                MarkerValue(v, null, null, vals.size, eq, eq - actual)
            }
            "sleep" -> {
                val nights = BaselineEngine.validNights(inputs, cfg)
                val use = (win - 1 downTo 0).map { asOf.minusDays(it.toLong()) }.filter { it in nights }
                if (use.size < mk.minDays) return MarkerValue(null, null, null, use.size, null, null)
                val hours = mean(use.map { nights.getValue(it).asleepMin!! / 60.0 })
                val mids = use.map { sleepMidpointMin(nights.getValue(it), it, inputs.timeZone).toDouble() }
                val sd = sampleSd(mids, mean(mids))
                MarkerValue(hours, sd, null, use.size, null, interp(mk.durationPoints, hours) + interp(mk.regularityPoints, sd))
            }
            "workouts" -> {
                val wear = window(inputs.series("steps"), asOf, win).size
                if (!inputs.tracking.workouts || wear < mk.minDays) return MarkerValue(null, null, null, wear, null, null)
                val loads = TrainingLoad.dailyLoads(inputs.workouts, inputs.timeZone, cfg)
                var total = 0.0
                var active = 0
                for (w in mk.weeks - 1 downTo 0) {
                    var block = 0.0
                    for (k in 6 downTo 0) block += loads[asOf.minusDays((7 * w + k).toLong())]?.minutes ?: 0.0
                    total += block
                    if (block > 0) active++
                }
                val perWeek = total / mk.weeks
                val share = active / mk.weeks.toDouble()
                MarkerValue(perWeek, share, null, wear, null, interp(mk.minutesPoints, perWeek) + interp(mk.activeSharePoints, share))
            }
            "body_composition" -> {
                val fat = window(inputs.series("body_fat"), asOf, win)
                if ((sex == "male" || sex == "female") && fat.size >= mk.minDays) {
                    val v = mean(fat)
                    return MarkerValue(v, null, "body_fat", fat.size, null, interp(mk.bodyFatPoints.getValue(sex), v))
                }
                var bmis = window(inputs.series("bmi"), asOf, win)
                val height = profile.heightCm
                if (bmis.isEmpty() && height != null && height != 0.0) {
                    val h = height / 100.0
                    bmis = window(inputs.series("weight"), asOf, win).map { it / (h * h) }
                }
                if (bmis.size < mk.minDays) return MarkerValue(null, null, null, bmis.size, null, null)
                val v = mean(bmis)
                MarkerValue(v, null, "bmi", bmis.size, null, interp(mk.bmiPoints, v))
            }
            else -> error("unknown marker method ${mk.method}")
        }
    }

    /** Unrounded result; [HealthAgeResult] fields hold full precision here. */
    private fun raw(inputs: InsightsInputs, asOf: LocalDate, profile: InsightsProfile, cfg: InsightsConfig): HealthAgeResult {
        val ha = cfg.healthAge
        fun out(status: String, actual: Double? = null, markers: List<HealthAgeMarker> = emptyList(), collecting: Collecting? = null, available: Int = 0) =
            HealthAgeResult(asOf, status, actual, null, null, null, markers, collecting, available, ha.minMarkers)
        val birthday = profile.birthday ?: return out("no_birthday")
        val actual = InsightsMath.daysBetween(birthday, asOf) / ha.daysPerYear
        if (actual < ha.minActualAge) return out("unsupported_age", actual)
        var wsum = 0.0
        val markers = ArrayList<HealthAgeMarker>()
        for (mk in ha.markers) {
            val v = marker(mk, inputs, asOf, profile, actual, cfg)
            val offset = v.offset?.let { clamp(it, -mk.capYears, mk.capYears) }
            if (offset != null) wsum += mk.weight
            markers += HealthAgeMarker(
                id = mk.id, method = mk.method, available = offset != null, value = v.value, secondaryValue = v.secondary,
                basis = v.basis, days = v.days, neededDays = mk.minDays, equivalentAge = v.equivalentAge, offsetYears = offset,
                weight = mk.weight, contributionYears = null
            )
        }
        val avail = markers.filter { it.available }
        val core = ha.coreMarkers.toSet()
        if (avail.size < ha.minMarkers || avail.none { it.id in core }) {
            val need = ha.collectingDays
            val best = (markers.filter { it.id in core }.map { it.days } + 0).max()
            return out("collecting", actual, markers, Collecting(minOf(best, need), need), avail.size)
        }
        var acc = 0.0
        val withContribution = markers.map { m ->
            if (!m.available) m else {
                val c = m.weight * m.offsetYears!! / wsum
                acc += c
                m.copy(contributionYears = c)
            }
        }
        val diff = clamp(acc, -ha.totalCapYears, ha.totalCapYears)
        val ids = avail.map { it.id }.toSet()
        val confidence = when {
            avail.size >= ha.highMinMarkers && ha.highRequires in ids -> "high"
            avail.size >= ha.mediumMinMarkers -> "medium"
            else -> "low"
        }
        return HealthAgeResult(asOf, "ok", actual, actual + diff, diff, confidence, withContribution, null, avail.size, ha.minMarkers)
    }

    /** Ayuvo Health Age as of [asOf] (inclusive 90-day window). */
    fun healthAge(inputs: InsightsInputs, asOf: LocalDate, profile: InsightsProfile, cfg: InsightsConfig): HealthAgeResult {
        val r = raw(inputs, asOf, profile, cfg)
        return r.copy(
            actualAge = roundTo(r.actualAge, 1),
            healthAge = roundTo(r.healthAge, 1),
            difference = roundTo(r.difference, 1),
            markers = r.markers.map {
                it.copy(
                    value = roundTo(it.value, 2), secondaryValue = roundTo(it.secondaryValue, 2), equivalentAge = roundTo(it.equivalentAge, 1),
                    offsetYears = roundTo(it.offsetYears, 2), contributionYears = roundTo(it.contributionYears, 2)
                )
            }
        )
    }

    /**
     * Health Age recomputed as of each of the last `weeks` week-ends (as of, as of − 7 … as of −
     * 7·weeks); pace is the least-squares slope in years of Health Age per calendar year.
     */
    fun pace(inputs: InsightsInputs, asOf: LocalDate, profile: InsightsProfile, cfg: InsightsConfig): HealthAgePace {
        val p = cfg.healthAge
        val pts = ArrayList<Triple<Int, LocalDate, HealthAgeResult>>()
        for (k in p.paceWeeks downTo 0) {
            val d = asOf.minusDays(7L * k)
            val r = raw(inputs, d, profile, cfg)
            if (r.ok) pts += Triple(-7 * k, d, r)
        }
        val points = pts.map { (_, d, r) -> HealthAgePoint(d, roundTo(r.healthAge!!, 1), roundTo(r.difference!!, 1)) }
        if (pts.size < p.paceMinPoints) return HealthAgePace("insufficient", pts.size, p.paceMinPoints, null, null, points)
        val slope = InsightsMath.olsSlope(pts.map { (x, _, r) -> x.toDouble() to r.healthAge!! })!!
        val pace = slope * p.daysPerYear
        val direction = if (pace < p.paceImprovingBelow) "improving" else if (pace > p.paceDecliningAbove) "declining" else "stable"
        return HealthAgePace("ok", pts.size, p.paceMinPoints, roundTo(pace, 2), direction, points)
    }
}
