package com.ayuvo.health.insights

import com.ayuvo.health.insights.InsightsMath.clamp
import com.ayuvo.health.insights.InsightsMath.roundTo
import java.time.LocalDate
import java.time.ZoneId

/**
 * Training load (docs/insights.md, "Training load"): overlapping workouts merge into sessions, a
 * session's load is minutes × intensity, and a day's load is compared with the mean daily load of
 * the 28 days before it. Ported from `sessions`, `daily_loads`, `_load_raw` and `training_load`.
 */
object TrainingLoad {

    data class Session(
        val startMs: Long,
        var endMs: Long,
        var intensity: Double,
        var hasEffort: Boolean,
        var members: Int
    ) {
        lateinit var day: LocalDate
        var minutes: Double = 0.0
        var load: Double = 0.0
    }

    data class DayLoad(val load: Double, val minutes: Double, val sessions: Int)

    data class Raw(
        val day: LocalDate,
        val load: Double,
        val minutes: Double,
        val sessions: Int,
        val mean28d: Double,
        val ratio: Double?,
        val category: String
    )

    /** Sorted by start then end; a session spans the union of its members and keeps the highest intensity. */
    fun sessions(workouts: List<WorkoutInput>, zone: ZoneId, cfg: InsightsConfig): List<Session> {
        val t = cfg.trainingLoad
        val ws = workouts.filter { it.endMs > it.startMs }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        val out = ArrayList<Session>()
        for (w in ws) {
            val hasEffort = w.effort != null
            val intensity = if (w.effort != null) clamp(w.effort / t.effortDivisor, t.intensityMin, t.intensityMax) else t.defaultIntensity
            val last = out.lastOrNull()
            if (last != null && w.startMs < last.endMs) {
                last.endMs = maxOf(last.endMs, w.endMs)
                last.intensity = maxOf(last.intensity, intensity)
                last.hasEffort = last.hasEffort || hasEffort
                last.members += 1
            } else {
                out += Session(w.startMs, w.endMs, intensity, hasEffort, 1)
            }
        }
        for (s in out) {
            s.day = InsightsMath.localDayOf(s.startMs, zone)
            s.minutes = (s.endMs - s.startMs) / 60000.0
            s.load = s.minutes * s.intensity
        }
        return out
    }

    fun dailyLoads(workouts: List<WorkoutInput>, zone: ZoneId, cfg: InsightsConfig): Map<LocalDate, DayLoad> {
        val days = LinkedHashMap<LocalDate, DayLoad>()
        for (s in sessions(workouts, zone, cfg)) {
            val d = days[s.day] ?: DayLoad(0.0, 0.0, 0)
            days[s.day] = DayLoad(d.load + s.load, d.minutes + s.minutes, d.sessions + 1)
        }
        return days
    }

    fun raw(loads: Map<LocalDate, DayLoad>, day: LocalDate, cfg: InsightsConfig): Raw {
        val t = cfg.trainingLoad
        val today = loads[day] ?: DayLoad(0.0, 0.0, 0)
        var total = 0.0
        for (k in t.meanWindowDays downTo 1) total += loads[day.minusDays(k.toLong())]?.load ?: 0.0
        val avg = total / t.meanWindowDays
        val ratio = if (avg > 0) today.load / avg else null
        val category = when {
            today.load == 0.0 -> "none"
            ratio == null || ratio > t.highAbove -> "high"
            ratio < t.lightBelow -> "light"
            else -> "moderate"
        }
        return Raw(day, today.load, today.minutes, today.sessions, avg, ratio, category)
    }

    fun trainingLoad(workouts: List<WorkoutInput>, day: LocalDate, zone: ZoneId, cfg: InsightsConfig): TrainingLoadResult {
        val r = raw(dailyLoads(workouts, zone, cfg), day, cfg)
        return TrainingLoadResult(
            day = r.day, load = roundTo(r.load, 1), minutes = roundTo(r.minutes, 1), sessions = r.sessions,
            mean28d = roundTo(r.mean28d, 1), ratio = roundTo(r.ratio, 2), category = r.category,
            label = cfg.trainingLoad.labels.getValue(r.category)
        )
    }
}
