package com.ayuvo.health.data.derived

import com.ayuvo.health.data.derived.DerivedMath.MINUTE
import com.ayuvo.health.data.derived.DerivedMath.mean
import com.ayuvo.health.data.derived.DerivedMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId

/** One step source for the day: `kind` is "phone" or "wearable"; `hourly` has 24 entries (null = no data). */
data class StepSource(val kind: String, val hourly: List<Double?>)

data class ActivityDayInput(
    val zone: ZoneId,
    val day: LocalDate,
    val sources: Map<String, StepSource>,
    /** Minute steps from one wearable, or null. */
    val minuteSteps: MinuteSeries?,
    /** Minute heart rate used as the wear signal, or null. */
    val wear: MinuteSeries?,
    /** Platform de-duplicated total, or null. */
    val stepsTotal: Double?
)

data class ActivityDayResult(
    val dedupSteps: Double,
    val stepBandIndex: Int,
    val activeHours: Int,
    val briskMinutes: Int?,
    val peak30Cadence: Double?,
    val longestSedentaryMin: Int?,
    val phoneMissingSteps: Double
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "dedup_steps" to dedupSteps, "step_band_index" to stepBandIndex, "active_hours" to activeHours,
        "brisk_minutes" to briskMinutes, "peak30_cadence" to peak30Cadence, "longest_sedentary_min" to longestSedentaryMin,
        "phone_missing_steps" to phoneMissingSteps
    )
}

data class StepStreakInput(val series: Map<LocalDate, Double>, val day: LocalDate, val goal: Double, val windowDays: Int)

data class StepStreakResult(val current: Int, val best: Int) {
    fun toJson(): JsonObject = MedicationJson.obj("current" to current, "best" to best)
}

data class StrideInput(val distanceM: Double?, val steps: Double?, val heightCm: Double?, val sex: String?)

data class StrideResult(val strideM: Double?, val expectedM: Double?, val ratioPct: Double?) {
    fun toJson(): JsonObject = MedicationJson.obj("stride_m" to strideM, "expected_m" to expectedM, "ratio_pct" to ratioPct)
}

/** Activity derivations: ported from `activity_day`, `sum_hours`, `step_streak` and `stride`. */
object ActivityDerivation {

    fun sumHours(values: List<Double?>): Double {
        var t = 0.0
        for (v in values) if (v != null) t += v
        return t
    }

    fun activityDay(inp: ActivityDayInput, cfg: DerivedConfig): ActivityDayResult {
        val th = cfg.thresholds
        val tz = inp.zone
        val srcs = inp.sources
        val names = srcs.keys.sortedWith(DerivedMath.CODE_POINT_ORDER)
        val hourly = ArrayList<Double>(24)
        for (h in 0 until 24) {
            val vals = names.mapNotNull { srcs.getValue(it).hourly[h] }
            hourly += if (vals.isNotEmpty()) vals.max() else 0.0
        }
        var dedup = 0.0
        for (v in hourly) dedup += v
        val total = inp.stepsTotal ?: dedup
        var band = 0
        for (c in th.stepBands) if (total >= c) band += 1
        var active = 0
        for (h in th.activeHourFirst..th.activeHourLast) if (hourly[h] >= th.activeHourSteps) active += 1

        var phoneMissing = 0.0
        val phone = names.filter { srcs.getValue(it).kind == "phone" }
        val wear = names.filter { srcs.getValue(it).kind == "wearable" }
        if (phone.isNotEmpty() && wear.isNotEmpty()) {
            val pt = sumHours(srcs.getValue(phone[0]).hourly)
            val wt = sumHours(srcs.getValue(wear[0]).hourly)
            if (wt >= th.phoneMinWearableSteps && pt < th.phoneRatio * wt) phoneMissing = roundTo(wt - pt, 0)
        }

        var brisk: Int? = null
        var peak: Double? = null
        var longestSedentary: Int? = null
        if (inp.minuteSteps != null) {
            val steps = DerivedMath.minuteMap(inp.minuteSteps)
            val d0 = DerivedMath.dayStartMs(inp.day, tz)
            val d1 = DerivedMath.dayStartMs(DerivedMath.addDays(inp.day, 1), tz)
            val mins = steps.entries.filter { d0 <= it.key && it.key < d1 }.map { it.value }
            brisk = mins.count { it >= th.briskCadence }
            val top = ArrayList(mins.sortedDescending().take(th.peakMinutes))
            while (top.size < th.peakMinutes) top += 0.0
            peak = roundTo(mean(top), 1)
            if (inp.wear != null) {
                val worn = DerivedMath.minuteMap(inp.wear, th.hrValidMin, th.hrValidMax)
                var best = 0
                var run = 0
                var t = d0
                while (t < d1) {
                    val h = DerivedMath.localTime(t, tz).hour
                    if (th.activeHourFirst <= h && h <= th.activeHourLast && worn.containsKey(t) && (steps[t] ?: 0.0) == 0.0) {
                        run += 1
                        best = maxOf(best, run)
                    } else {
                        run = 0
                    }
                    t += MINUTE
                }
                longestSedentary = best
            }
        }
        return ActivityDayResult(
            dedupSteps = roundTo(dedup, 0), stepBandIndex = band, activeHours = active, briskMinutes = brisk,
            peak30Cadence = peak, longestSedentaryMin = longestSedentary, phoneMissingSteps = phoneMissing
        )
    }

    /** Current and best runs of days at or above the goal; a goal ≤ 0 has no streak (0, 0). */
    fun stepStreak(inp: StepStreakInput, cfg: DerivedConfig): StepStreakResult {
        val s = inp.series
        val goal = inp.goal
        if (goal <= 0) return StepStreakResult(0, 0)
        fun v(d: LocalDate): Double = s[d] ?: 0.0
        var cur = 0
        var d = if (v(inp.day) >= goal) inp.day else DerivedMath.addDays(inp.day, -1)
        while (v(d) >= goal) {
            cur += 1
            d = DerivedMath.addDays(d, -1)
        }
        var best = 0
        var run = 0
        for (i in inp.windowDays - 1 downTo 0) {
            if (v(DerivedMath.addDays(inp.day, -i)) >= goal) {
                run += 1
                best = maxOf(best, run)
            } else {
                run = 0
            }
        }
        return StepStreakResult(cur, maxOf(best, cur))
    }

    fun stride(inp: StrideInput, cfg: DerivedConfig): StrideResult {
        val th = cfg.thresholds
        val f = th.bySex(th.strideFactor, inp.sex)
        val h = inp.heightCm
        val expected = if (DerivedMath.truthy(h)) roundTo(f * h!! / 100.0, 2) else null
        val steps = inp.steps
        if (!DerivedMath.truthy(steps) || steps!! < th.strideMinSteps || inp.distanceM == null) {
            return StrideResult(null, expected, null)
        }
        val s = inp.distanceM / steps
        return StrideResult(
            roundTo(s, 2), expected,
            if (DerivedMath.truthy(expected)) roundTo(s * 100.0 / (f * h!! / 100.0), 1) else null
        )
    }
}
