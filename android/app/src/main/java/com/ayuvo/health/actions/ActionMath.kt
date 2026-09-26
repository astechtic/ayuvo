package com.ayuvo.health.actions

import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/** Half-open `[fromMs, toMs)` in the user's zone. */
data class ActionDateRange(val fromMs: Long, val toMs: Long) {
    companion object {
        /** `resolve_date_range` (shared/actions/test-vectors/date_ranges.json). */
        fun resolve(preset: String, nowMs: Long, zone: ZoneId, weekStart: WeekStart): ActionDateRange {
            val d = MetricsReference.localDateOf(nowMs, zone)
            val (a, b) = when (preset) {
                "today" -> d to d.plusDays(1)
                "yesterday" -> d.minusDays(1) to d
                "this_week", "last_week" -> {
                    val ws = MetricsReference.weekStartOf(d, weekStart)
                    if (preset == "this_week") ws to ws.plusDays(7) else ws.minusDays(7) to ws
                }
                "last_7_days" -> d.minusDays(6) to d.plusDays(1)
                "last_30_days" -> d.minusDays(29) to d.plusDays(1)
                "this_month", "last_month" -> {
                    val first = LocalDate.of(d.year, d.month, 1)
                    if (preset == "this_month") first to first.plusMonths(1) else first.minusMonths(1) to first
                }
                else -> throw IllegalArgumentException("bad date range $preset")
            }
            return ActionDateRange(MetricsReference.localMidnight(a, zone), MetricsReference.localMidnight(b, zone))
        }
    }

    operator fun contains(ms: Long): Boolean = ms in fromMs until toMs
}

/**
 * The small calculations action outputs share with iOS (shared/actions/test-vectors/compute.json).
 * Rounding is half up by `floor(x * 10^d + 0.5) / 10^d` on every platform.
 */
object ActionMath {
    fun roundTo(x: Double, decimals: Int): Double {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return floor(x * scale + 0.5) / scale
    }

    fun percent(part: Double, whole: Double): Int = floor(part * 100.0 / whole + 0.5).toInt()

    fun toCanonical(catalog: ActionCatalog, family: String, unit: String, value: Double): Double =
        value * catalog.units.getValue(family).factors.getValue(unit)

    fun convert(catalog: ActionCatalog, family: String, from: String, to: String, value: Double): Double {
        val factors = catalog.units.getValue(family).factors
        return roundTo(value * factors.getValue(from) / factors.getValue(to), 3)
    }

    data class Sample(val tMs: Long, val value: Double?)

    /** `aggregate`: `value` is null without samples (0 for count); average is rounded to 2 decimals. */
    fun aggregate(entries: List<Sample>, aggregation: String): Map<String, Any?> {
        val vals = entries.filter { it.value != null }
        val n = vals.size
        if (aggregation == "count") return linkedMapOf("value" to n.toLong(), "count" to n.toLong())
        if (n == 0) return linkedMapOf("value" to null, "count" to 0L)
        val value: Double = when (aggregation) {
            "latest" -> {
                var best = vals[0]
                for (s in vals.drop(1)) if (s.tMs >= best.tMs) best = s
                best.value!!
            }
            "sum" -> {
                var total = 0.0
                for (s in vals) total += s.value!!
                total
            }
            "average" -> {
                var total = 0.0
                for (s in vals) total += s.value!!
                roundTo(total / n, 2)
            }
            "min" -> vals.minOf { it.value!! }
            "max" -> vals.maxOf { it.value!! }
            else -> throw IllegalArgumentException("bad aggregation $aggregation")
        }
        return linkedMapOf("value" to value, "count" to n.toLong())
    }

    fun waterStatus(intakeMl: Long, goalMl: Long?): Map<String, Any?> {
        val hasGoal = goalMl != null && goalMl > 0
        return linkedMapOf(
            "intake_ml" to intakeMl,
            "goal_ml" to if (hasGoal) goalMl else null,
            "remaining_ml" to if (hasGoal) maxOf(0L, goalMl!! - intakeMl) else null,
            "percent" to if (hasGoal) percent(intakeMl.toDouble(), goalMl!!.toDouble()).toLong() else null
        )
    }

    fun progress(value: Double, target: Double?): Map<String, Any?> {
        val has = target != null && target > 0
        return linkedMapOf(
            "value" to roundTo(value, 1),
            "target" to if (has) roundTo(target!!, 1) else null,
            "remaining" to if (has) roundTo(maxOf(0.0, target!! - value), 1) else null,
            "percent" to if (has) percent(value, target!!).toLong() else null
        )
    }

    fun bmi(weightKg: Double?, heightCm: Double?): Double? {
        if (weightKg == null || heightCm == null || weightKg <= 0 || heightCm <= 0) return null
        val m = heightCm / 100.0
        return roundTo(weightKg / (m * m), 1)
    }

    data class WeightPoint(val tMs: Long, val kg: Double)

    fun weightChange(entries: List<WeightPoint>): Map<String, Any?> {
        val ordered = entries.sortedBy { it.tMs }
        if (ordered.isEmpty()) return linkedMapOf("first_kg" to null, "last_kg" to null, "change_kg" to null, "count" to 0L)
        val first = ordered.first().kg
        val last = ordered.last().kg
        return linkedMapOf(
            "first_kg" to roundTo(first, 1),
            "last_kg" to roundTo(last, 1),
            "change_kg" to roundTo(last - first, 1),
            "count" to ordered.size.toLong()
        )
    }

    data class SetWork(val weightKg: Double, val reps: Int)

    fun setVolume(sets: List<SetWork>): Map<String, Any?> {
        var reps = 0L
        var volume = 0.0
        for (s in sets) {
            reps += s.reps
            volume += s.weightKg * s.reps
        }
        return linkedMapOf("sets" to sets.size.toLong(), "reps" to reps, "volume_kg" to roundTo(volume, 1))
    }

    fun fastingProgress(startedMs: Long, goalMinutes: Long, nowMs: Long): Map<String, Any?> {
        val elapsed = maxOf(0L, Math.floorDiv(nowMs - startedMs, 1000L))
        val goal = goalMinutes * 60
        return linkedMapOf(
            "elapsed_s" to elapsed,
            "goal_s" to goal,
            "remaining_s" to maxOf(0L, goal - elapsed),
            "percent" to if (goal > 0) percent(elapsed.toDouble(), goal.toDouble()).toLong() else null,
            "reached" to (goal > 0 && elapsed >= goal)
        )
    }
}
