package com.ayuvo.health.widget.history

import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.MealType
import com.ayuvo.health.models.WorkoutSession
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Workout / Food history heatmaps (docs/widgets.md "History widgets"). The levels and the grid follow
 * `shared/widgets/history_heatmap.json`; `HistoryHeatmapContractTest` runs its vectors. Pure Kotlin.
 */
object HistoryHeatmap {
    const val DAYS = 371
    const val LEVELS = 4

    /** A day's level starts at these seconds trained; any workout is at least level 1. */
    val WORKOUT_MIN_SECONDS = longArrayOf(1, 900, 1800, 3600)

    /** A day's level starts at these distinct meal types; any entry is at least level 1. */
    val FOOD_MIN_MEALS = intArrayOf(1, 2, 3, 4)

    fun workoutLevel(workouts: Int, seconds: Long): Int {
        if (workouts <= 0) return 0
        return WORKOUT_MIN_SECONDS.count { seconds >= it }.coerceAtLeast(1)
    }

    fun foodLevel(meals: Collection<MealType>): Int {
        if (meals.isEmpty()) return 0
        val distinct = meals.toSet().size
        return FOOD_MIN_MEALS.count { distinct >= it }.coerceAtLeast(1)
    }

    /** Where the grid starts for [columns] weeks ending with the week of [today]. */
    data class Grid(val firstDay: LocalDate, val columns: Int, val todayRow: Int, val hiddenAfterToday: Int) {
        fun day(column: Int, row: Int): LocalDate = firstDay.plusDays(column * 7L + row)
    }

    fun grid(today: LocalDate, weekStart: WeekStart, columns: Int): Grid {
        val cols = columns.coerceAtLeast(1)
        val weekFirst = MetricsReference.weekStartOf(today, weekStart)
        val row = ChronoUnit.DAYS.between(weekFirst, today).toInt()
        return Grid(weekFirst.minusWeeks(cols - 1L), cols, row, 6 - row)
    }

    /** [DAYS] digits, oldest first, the last one is [lastDay]. */
    fun encode(levels: Map<LocalDate, Int>, lastDay: LocalDate): String {
        val first = lastDay.minusDays(DAYS - 1L)
        return buildString(DAYS) {
            for (i in 0 until DAYS) append(levels[first.plusDays(i.toLong())]?.coerceIn(0, LEVELS) ?: 0)
        }
    }

    /** Level of [day] in [series]; 0 for any day the snapshot does not cover. */
    fun levelAt(series: HistorySeries, lastDay: LocalDate, day: LocalDate): Int {
        val back = ChronoUnit.DAYS.between(day, lastDay)
        if (back < 0 || back >= series.levels.length) return 0
        return series.levels[series.levels.length - 1 - back.toInt()].digitToIntOrNull()?.coerceIn(0, LEVELS) ?: 0
    }

    /** Months the grid reaches back, for the title ("last 6 months"). */
    fun monthsShown(columns: Int): Int = Math.round(columns * 7 / 30.44).toInt().coerceAtLeast(1)

    fun build(
        workouts: List<WorkoutSession>,
        food: List<FoodEntry>,
        today: LocalDate,
        zone: ZoneId,
        weekStart: WeekStart,
        nowMs: Long
    ): HistoryHeatmapSnapshot {
        val first = today.minusDays(DAYS - 1L)
        // Same day attribution and durations as the app:workout_minutes metric (AppMetricAggregator).
        val counts = HashMap<LocalDate, Int>()
        val seconds = HashMap<LocalDate, Long>()
        for (w in workouts) {
            val day = MetricsReference.workoutDay(w.diaryDateKey, w.startedAt.toEpochMilli(), zone)
            if (day < first || day > today) continue
            counts[day] = (counts[day] ?: 0) + 1
            seconds[day] = (seconds[day] ?: 0L) + w.durationSeconds.coerceAtLeast(0).toLong()
        }
        val workoutLevels = counts.mapValues { (day, n) -> workoutLevel(n, seconds[day] ?: 0L) }

        // Same day as the diary's calorie totals: the entry's timestamp in the local zone.
        val meals = HashMap<LocalDate, MutableSet<MealType>>()
        for (e in food) {
            val day = e.timestamp.atZone(zone).toLocalDate()
            if (day < first || day > today) continue
            meals.getOrPut(day) { mutableSetOf() } += e.mealType
        }
        val foodLevels = meals.mapValues { foodLevel(it.value) }

        return HistoryHeatmapSnapshot(
            generatedAtMs = nowMs,
            lastDayKey = today.toString(),
            weekStartsOnMonday = weekStart == WeekStart.MONDAY,
            workout = HistorySeries(encode(workoutLevels, today), workoutLevels.size, seconds.values.sum()),
            food = HistorySeries(encode(foodLevels, today), foodLevels.size)
        )
    }
}

/** What the history widgets read; written with the dashboard snapshot, device-local, never backed up. */
@Serializable
data class HistoryHeatmapSnapshot(
    val version: Int = VERSION,
    val generatedAtMs: Long,
    /** Local day of the last digit, `yyyy-MM-dd`. */
    val lastDayKey: String,
    val weekStartsOnMonday: Boolean = true,
    val workout: HistorySeries,
    val food: HistorySeries
) {
    val lastDay: LocalDate? get() = runCatching { LocalDate.parse(lastDayKey) }.getOrNull()
    val weekStart: WeekStart get() = if (weekStartsOnMonday) WeekStart.MONDAY else WeekStart.SUNDAY

    companion object {
        const val VERSION = 1
    }
}

/** One digit 0–4 per day, oldest first; [activeDays] = days above 0; [totalSeconds] for workouts only. */
@Serializable
data class HistorySeries(val levels: String, val activeDays: Int, val totalSeconds: Long? = null)
