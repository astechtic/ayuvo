package com.ayuvo.health.nutrients

import com.ayuvo.health.data.intake.IntakeConfig
import com.ayuvo.health.data.intake.IntakeDefaults
import com.ayuvo.health.data.intake.IntakeNutrientGoals
import com.ayuvo.health.data.intake.SupplementDailyInput
import com.ayuvo.health.data.intake.SupplementDailyResult
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.ScheduleFrequency
import kotlin.math.floor

/**
 * Supplements averaged over their dosing interval (docs/intake-metrics.md §3, `supplement_daily`): a weekly
 * 60,000 IU vitamin D dose counts as 1/7 on each of the seven days it covers instead of 1,500 mcg on one day. The
 * amount is preserved: each taken dose becomes [intervalDays] entries of `amount ÷ interval`, one per day from the
 * dose onwards. Daily (or more frequent) supplements are unchanged.
 */
object SupplementAveraging {
    private const val DAY_MS = 86_400_000L

    /** Whole days one dose covers (reference `supplement_interval_days`): weekly with n days → 7 ÷ n (half-up), an interval of ≥ 48 h → hours ÷ 24, else 1. */
    fun intervalDays(schedule: MedicationSchedule?): Int {
        if (schedule == null) return 1
        val kind = when (schedule.frequency) {
            ScheduleFrequency.WEEKLY -> "weekly"
            ScheduleFrequency.INTERVAL -> "interval"
            ScheduleFrequency.DAILY -> "daily"
        }
        return intervalDays(kind, schedule.days, schedule.intervalHours)
    }

    fun intervalDays(frequencyKind: String?, days: List<Int>, intervalHours: Int?): Int = when (frequencyKind) {
        "weekly" -> {
            val n = days.distinct().size
            if (n <= 0) 1 else maxOf(1, floor(7.0 / n + 0.5).toInt())
        }
        "interval" -> {
            val h = intervalHours ?: 0
            if (h >= 48) floor(h / 24.0 + 0.5).toInt() else 1
        }
        else -> 1
    }

    /** [entries] with every entry of a medication whose interval is > 1 day spread over that many days. */
    fun spread(entries: List<SupplementEntry>, intervals: Map<String, Int>): List<SupplementEntry> {
        if (intervals.values.none { it > 1 }) return entries
        val out = ArrayList<SupplementEntry>(entries.size)
        for (e in entries) {
            val n = intervals[e.medicationId] ?: 1
            if (n <= 1) {
                out += e
                continue
            }
            val per = e.value / n
            for (k in 0 until n) out += e.copy(tMs = e.tMs + k * DAY_MS, value = per)
        }
        return out.sortedWith(compareBy<SupplementEntry> { it.tMs }.thenBy { it.nutrientKey }.thenBy { it.medicationId })
    }

    /**
     * `supplement_daily` of one regimen: [amountPerDose] of [nutrientKey] every [intervalDays] days against the adult
     * upper limit, if the nutrient has one. Above the limit the copy says to follow the prescriber, never to stop.
     */
    fun regimen(nutrientKey: String, amountPerDose: Double, intervalDays: Int, cfg: IntakeConfig? = IntakeConfig.active): SupplementDailyResult {
        val upper = cfg?.let { c -> IntakeDefaults.driKey(nutrientKey, c)?.let { c.driUpper[it] } }
        return IntakeNutrientGoals.supplementDaily(SupplementDailyInput(amountPerDose, listOf(0L), maxOf(1, intervalDays).toDouble(), upper))
    }

    /** The upper limit of [nutrientKey] (canonical unit), or null. */
    fun upperLimit(nutrientKey: String, cfg: IntakeConfig? = IntakeConfig.active): Double? =
        cfg?.let { c -> IntakeDefaults.driKey(nutrientKey, c)?.let { c.driUpper[it] } }
}
