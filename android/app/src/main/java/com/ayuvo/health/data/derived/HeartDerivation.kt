package com.ayuvo.health.data.derived

import com.ayuvo.health.data.derived.DerivedMath.MINUTE
import com.ayuvo.health.data.derived.DerivedMath.mean
import com.ayuvo.health.data.derived.DerivedMath.roundTo
import com.ayuvo.health.data.derived.DerivedMath.sampleSd
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp

data class HeartDayInput(
    val zone: ZoneId,
    val day: LocalDate,
    val sex: String?,
    val hr: MinuteSeries?,
    val steps: MinuteSeries?,
    /** The main night whose wake day is [day], or null. */
    val night: Span?,
    val hrMax: Double,
    /** Resting heart rate used for zones and TRIMP, or null (then %HRmax zones and no TRIMP). */
    val rhrRef: Double?
)

data class HeartDayResult(
    val restingHr: Double?,
    val sleepingHr: Double?,
    val lowestHr: Double?,
    val dipPct: Double?,
    val sedentaryHr: Double?,
    val dayAvg: Double?,
    val dayMin: Double?,
    val dayMax: Double?,
    val observedMax: Double?,
    val wearMinutes: Int,
    val completenessPct: Double,
    val validDay: Boolean,
    val zoneMethod: String,
    val lightMin: Int,
    val moderateMin: Int,
    val vigorousMin: Int,
    val maxMin: Int,
    val moderateEquivalent: Int,
    val trimp: Double?,
    val walkingHr: Double?,
    val rhrStatus: String
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "resting_hr" to restingHr, "sleeping_hr" to sleepingHr, "lowest_hr" to lowestHr, "dip_pct" to dipPct,
        "sedentary_hr" to sedentaryHr, "day_avg" to dayAvg, "day_min" to dayMin, "day_max" to dayMax,
        "observed_max" to observedMax, "wear_minutes" to wearMinutes, "completeness_pct" to completenessPct,
        "valid_day" to validDay, "zone_method" to zoneMethod, "light_min" to lightMin, "moderate_min" to moderateMin,
        "vigorous_min" to vigorousMin, "max_min" to maxMin, "moderate_equivalent" to moderateEquivalent, "trimp" to trimp,
        "walking_hr" to walkingHr, "rhr_status" to rhrStatus
    )
}

data class HrMaxInput(val age: Double, val observed: List<Double?>, val rhr: Double?)

data class HrMaxResult(val hrMax: Double, val method: String, val hrReserve: Double?) {
    fun toJson(): JsonObject = MedicationJson.obj("hr_max" to hrMax, "method" to method, "hr_reserve" to hrReserve)
}

data class Vo2MaxInput(val hrMax: Double?, val hrMaxMethod: String?, val rhr: Double?)

data class Vo2MaxResult(val vo2max: Double?, val confidence: String?) {
    fun toJson(): JsonObject = MedicationJson.obj("vo2max" to vo2max, "confidence" to confidence)
}

data class RhrStrainInput(val series: Map<LocalDate, Double>, val day: LocalDate)

data class RhrStrainResult(val status: String, val baseline: Double?, val deviation: Double?, val z: Double?, val flag: Boolean) {
    fun toJson(): JsonObject =
        MedicationJson.obj("status" to status, "baseline" to baseline, "deviation" to deviation, "z" to z, "flag" to flag)
}

/** Heart derivations: ported from `heart_day`, `hr_zone`, `hr_max`, `vo2max_uth` and `rhr_strain`. */
object HeartDerivation {

    /** 0 below light, 1 light, 2 moderate, 3 vigorous, 4 near-maximal. %HRR when rhr is known, else %HRmax. */
    fun hrZone(hr: Double, hrMax: Double, rhr: Double?, th: DerivedConfig.Thresholds): Int {
        val x: Double
        val cuts: List<Double>
        if (rhr != null && hrMax > rhr) {
            x = (hr - rhr) / (hrMax - rhr)
            cuts = th.hrrZones
        } else {
            x = hr / hrMax
            cuts = th.hrmaxZones
        }
        var z = 0
        for (c in cuts) if (x >= c) z += 1
        return z
    }

    fun heartDay(inp: HeartDayInput, cfg: DerivedConfig): HeartDayResult {
        val th = cfg.thresholds
        val tz = inp.zone
        val hr = DerivedMath.minuteMap(inp.hr, th.hrValidMin, th.hrValidMax)
        val steps = inp.steps?.let { DerivedMath.minuteMap(it) }
        val d0 = DerivedMath.dayStartMs(inp.day, tz)
        val d1 = DerivedMath.dayStartMs(DerivedMath.addDays(inp.day, 1), tz)
        val night = inp.night

        var restingHr: Double? = null
        var sleepingHr: Double? = null
        var lowestHr: Double? = null
        var dipPct: Double? = null
        var sedentaryHr: Double? = null
        var dayAvg: Double? = null
        var dayMin: Double? = null
        var dayMax: Double? = null
        var walkingHr: Double? = null
        var rhrStatus = "no_night"

        fun inNight(t: Long): Boolean = night != null && night.startMs <= t && t < night.endMs

        // night
        if (night != null) {
            val window = hr.keys.filter { night.startMs <= it && it < night.endMs }
            val spanMin = Math.floorDiv(night.endMs - night.startMs, MINUTE)
            val coverage = if (spanMin > 0) window.size.toDouble() / spanMin.toDouble() else 0.0
            if (spanMin < th.rhrMinSleepMin) {
                rhrStatus = "short_night"
            } else if (coverage < th.rhrMinCoverage) {
                rhrStatus = "low_coverage"
            } else {
                val n = th.rhrWindowMin
                var best: Double? = null
                for (t in window) {
                    var vals: ArrayList<Double>? = ArrayList()
                    for (k in 0 until n) {
                        val at = t + k * MINUTE
                        val v = hr[at]
                        if (v == null || at >= night.endMs) {
                            vals = null
                            break
                        }
                        vals!!.add(v)
                    }
                    if (vals != null) {
                        val m = mean(vals)
                        if (best == null || m < best) best = m
                    }
                }
                restingHr = roundTo(best, 1)
                rhrStatus = if (best != null) "ok" else "low_coverage"
            }
            if (window.isNotEmpty()) {
                val vals = window.map { hr.getValue(it) }
                sleepingHr = roundTo(mean(vals), 1)
                lowestHr = roundTo(vals.min(), 1)
                val wakeEnd = night.endMs + th.wakeWindowHours * 3600000
                val wake = hr.entries.filter { night.endMs <= it.key && it.key < wakeEnd }.map { it.value }
                if (wake.size >= th.dipMinWakeMin && rhrStatus == "ok") {
                    dipPct = roundTo((1.0 - mean(vals) / mean(wake)) * 100.0, 1)
                }
            }
        }

        val dayMinutes = hr.keys.filter { d0 <= it && it < d1 }
        val wearMinutes = dayMinutes.size
        val completenessPct = roundTo(dayMinutes.size * 100.0 / Math.floorDiv(d1 - d0, MINUTE).toDouble(), 1)
        val validDay = dayMinutes.size >= th.validWearMin

        // daytime
        val awake = dayMinutes.filter { !inNight(it) }.map { hr.getValue(it) }
        if (awake.size >= th.daytimeMinMinutes) {
            dayAvg = roundTo(mean(awake), 1)
            dayMin = roundTo(awake.min(), 1)
            dayMax = roundTo(awake.max(), 1)
        }

        // observed max: highest 2-minute mean
        var best2: Double? = null
        for (t in dayMinutes) {
            val v2 = hr[t + MINUTE] ?: continue
            val m = (hr.getValue(t) + v2) / 2.0
            if (best2 == null || m > best2) best2 = m
        }
        val observedMax = roundTo(best2, 1)

        // sedentary daytime and walking heart rate
        if (steps != null) {
            val sed = ArrayList<Double>()
            for (t in dayMinutes) {
                val hour = DerivedMath.localTime(t, tz).hour
                if (!(th.sedentaryStartHour <= hour && hour < th.sedentaryEndHour) || inNight(t)) continue
                var still = true
                for (k in 0..th.sedentaryStillMin) {
                    if ((steps[t - k * MINUTE] ?: 0.0) > 0) {
                        still = false
                        break
                    }
                }
                if (still) sed += hr.getValue(t)
            }
            if (sed.size >= th.sedentaryMinMinutes) sedentaryHr = roundTo(mean(sed), 1)

            // runs of >= walking_run_min minutes with walking_min_steps..walking_max_steps
            val walk = ArrayList<Double>()
            val run = ArrayList<Long>()
            var t = d0
            while (t < d1) {
                val s = steps[t] ?: 0.0
                if (th.walkingMinSteps <= s && s <= th.walkingMaxSteps) {
                    run += t
                } else {
                    if (run.size >= th.walkingRunMin) for (x in run) hr[x]?.let { walk += it }
                    run.clear()
                }
                t += MINUTE
            }
            if (run.size >= th.walkingRunMin) for (x in run) hr[x]?.let { walk += it }
            if (walk.size >= th.walkingMinMinutes) walkingHr = roundTo(mean(walk), 1)
        }

        // zones and TRIMP
        val hrMax = inp.hrMax
        val rhr = inp.rhrRef
        val zoneMethod = if (rhr != null && hrMax > rhr) "hrr" else "hrmax"
        val counts = IntArray(5)
        var trimp = 0.0
        val k = th.bySex(th.trimpK, inp.sex)
        for (t in dayMinutes) {
            val v = hr.getValue(t)
            counts[hrZone(v, hrMax, rhr, th)] += 1
            if (zoneMethod == "hrr") {
                val x = (v - rhr!!) / (hrMax - rhr)
                if (x >= th.trimpMinHrr) trimp += x * 0.64 * exp(k * x)
            }
        }
        return HeartDayResult(
            restingHr = restingHr, sleepingHr = sleepingHr, lowestHr = lowestHr, dipPct = dipPct, sedentaryHr = sedentaryHr,
            dayAvg = dayAvg, dayMin = dayMin, dayMax = dayMax, observedMax = observedMax, wearMinutes = wearMinutes,
            completenessPct = completenessPct, validDay = validDay, zoneMethod = zoneMethod,
            lightMin = counts[1], moderateMin = counts[2], vigorousMin = counts[3], maxMin = counts[4],
            moderateEquivalent = counts[2] + 2 * (counts[3] + counts[4]),
            trimp = if (zoneMethod == "hrr") roundTo(trimp, 1) else null,
            walkingHr = walkingHr, rhrStatus = rhrStatus
        )
    }

    fun hrMax(inp: HrMaxInput, cfg: DerivedConfig): HrMaxResult {
        val th = cfg.thresholds
        val tanaka = th.tanaka[0] - th.tanaka[1] * inp.age
        val obs = inp.observed.filterNotNull()
        val top = if (obs.isNotEmpty()) obs.max() else null
        val value: Double
        val method: String
        if (top != null && top > tanaka) {
            value = top
            method = "observed"
        } else {
            value = tanaka
            method = "tanaka"
        }
        val rhr = inp.rhr
        return HrMaxResult(roundTo(value, 1), method, if (rhr != null) roundTo(value - rhr, 1) else null)
    }

    fun vo2maxUth(inp: Vo2MaxInput, cfg: DerivedConfig): Vo2MaxResult {
        val rhr = inp.rhr
        if (rhr == null || rhr <= 0 || inp.hrMax == null) return Vo2MaxResult(null, null)
        val v = cfg.thresholds.uthFactor * inp.hrMax / rhr
        return Vo2MaxResult(roundTo(v, 1), if (inp.hrMaxMethod == "observed") "medium" else "low")
    }

    fun rhrStrain(inp: RhrStrainInput, cfg: DerivedConfig): RhrStrainResult {
        val th = cfg.thresholds
        val s = inp.series
        val day = inp.day

        fun base(asOf: LocalDate): Pair<Double, Double>? {
            val vals = ArrayList<Double>()
            for (i in th.strainWindowDays downTo 1) s[DerivedMath.addDays(asOf, -i)]?.let { vals += it }
            if (vals.size < th.strainMinDays) return null
            val m = mean(vals)
            return m to maxOf(sampleSd(vals, m), th.strainSdFloor)
        }

        val today = s[day]
        val b = base(day)
        if (today == null || b == null) return RhrStrainResult("insufficient", null, null, null, false)
        val (m, sd) = b
        val dev = today - m
        val y = s[DerivedMath.addDays(day, -1)]
        val yb = base(DerivedMath.addDays(day, -1))
        val flag = dev >= th.strainDeltaBpm && y != null && yb != null && y - yb.first >= th.strainDeltaBpm
        return RhrStrainResult("ok", roundTo(m, 1), roundTo(dev, 1), roundTo(dev / sd, 2), flag)
    }
}
