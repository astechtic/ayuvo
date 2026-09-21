package com.ayuvo.health.data.metrics

import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutSession
import java.time.LocalDate
import java.time.ZoneId

/** Everything the app-metric adapters read, taken from the repositories in one pass. */
data class AppMetricSnapshot(
    val food: List<FoodEntry> = emptyList(),
    val water: List<WaterEntry> = emptyList(),
    val fasting: List<FastingSession> = emptyList(),
    val weight: List<WeightEntry> = emptyList(),
    val bodyFat: List<BodyFatEntry> = emptyList(),
    val workouts: List<WorkoutSession> = emptyList()
) {
    companion object {
        val EMPTY = AppMetricSnapshot()
    }
}

/**
 * Entry adapters of docs/ui-structure.md §4: each app metric becomes `[MetricEntry]` in the
 * canonical unit, then goes through [MetricsReference]. Pure and zone-explicit.
 */
object AppMetricAggregator {

    /** Mirrors the catalog's `aggregation` per key (checked by MetricCatalogContractTest). */
    fun aggregation(id: AppMetricId): MetricAggregation = when (id) {
        AppMetricId.CALORIES, AppMetricId.PROTEIN, AppMetricId.CARBS, AppMetricId.FAT, AppMetricId.FIBER,
        AppMetricId.WATER, AppMetricId.WORKOUT_BURN -> MetricAggregation.SUM
        AppMetricId.FASTING, AppMetricId.WORKOUT_MINUTES -> MetricAggregation.DURATION
        AppMetricId.WEIGHT, AppMetricId.BODY_FAT -> MetricAggregation.LAST
        AppMetricId.WORKOUTS -> MetricAggregation.COUNT
    }

    fun entries(id: AppMetricId, snapshot: AppMetricSnapshot, nowMs: Long, zone: ZoneId): List<MetricEntry> = when (id) {
        AppMetricId.CALORIES -> snapshot.food.map { MetricEntry(it.timestamp.toEpochMilli(), it.calories.toDouble()) }
        AppMetricId.PROTEIN -> snapshot.food.map { MetricEntry(it.timestamp.toEpochMilli(), it.protein) }
        AppMetricId.CARBS -> snapshot.food.map { MetricEntry(it.timestamp.toEpochMilli(), it.carbs) }
        AppMetricId.FAT -> snapshot.food.map { MetricEntry(it.timestamp.toEpochMilli(), it.fat) }
        AppMetricId.FIBER -> snapshot.food.map { MetricEntry(it.timestamp.toEpochMilli(), it.fiber) }
        AppMetricId.WATER -> snapshot.water.map { MetricEntry(it.date.toEpochMilli(), it.milliliters.toDouble()) }
        AppMetricId.FASTING -> MetricsReference.fastingSecondsPerDay(
            snapshot.fasting.map { FastingSpan(it.startedAt.toEpochMilli(), it.endedAt?.toEpochMilli()) }, nowMs, zone
        ).map { (day, seconds) -> MetricEntry(MetricsReference.localMidnight(day, zone), seconds.toDouble()) }
        AppMetricId.WEIGHT -> snapshot.weight.map { MetricEntry(it.date.toEpochMilli(), it.weightKg) }
        AppMetricId.BODY_FAT -> snapshot.bodyFat.map { MetricEntry(it.date.toEpochMilli(), it.bodyFatFraction * 100.0) }
        AppMetricId.WORKOUTS -> snapshot.workouts.map { MetricEntry(workoutMidnight(it, zone), 1.0) }
        AppMetricId.WORKOUT_MINUTES -> snapshot.workouts.map { MetricEntry(workoutMidnight(it, zone), it.durationSeconds.coerceAtLeast(0).toDouble()) }
        AppMetricId.WORKOUT_BURN -> preferredDailyWorkoutBurns(snapshot.workouts)
            .map { (day, kcal) -> MetricEntry(MetricsReference.localMidnight(day, zone), kcal.toDouble()) }
    }

    fun series(id: AppMetricId, snapshot: AppMetricSnapshot, range: MetricRange, anchorMs: Long, nowMs: Long, zone: ZoneId, weekStart: WeekStart): List<MetricSeriesBucket> =
        MetricsReference.bucketSeries(entries(id, snapshot, nowMs, zone), range, anchorMs, zone, weekStart, aggregation(id))

    fun headline(id: AppMetricId, snapshot: AppMetricSnapshot, range: MetricRange, anchorMs: Long, nowMs: Long, zone: ZoneId, weekStart: WeekStart): MetricHeadline =
        MetricsReference.headline(entries(id, snapshot, nowMs, zone), range, anchorMs, zone, weekStart, aggregation(id))

    fun sparkline(id: AppMetricId, snapshot: AppMetricSnapshot, nowMs: Long, zone: ZoneId): MetricSparkline =
        MetricsReference.sparkline7d(entries(id, snapshot, nowMs, zone), nowMs, zone, aggregation(id))

    private fun workoutMidnight(session: WorkoutSession, zone: ZoneId): Long =
        MetricsReference.localMidnight(MetricsReference.workoutDay(session.diaryDateKey, session.startedAt.toEpochMilli(), zone), zone)
}

/**
 * Chooses one reliable calculated-burn snapshot per day. Local/Health Connect restore races can
 * briefly leave duplicate snapshots; the higher sync version wins, then the later completion.
 */
internal fun preferredDailyWorkoutBurns(entries: List<WorkoutSession>): List<Pair<LocalDate, Int>> =
    entries.mapNotNull { session ->
        val date = runCatching { LocalDate.parse(session.diaryDateKey) }.getOrNull()
        val calories = session.caloriesBurned?.takeIf { it in 1..5_000 }
        if (date == null || calories == null) null else date to session
    }.groupBy(Pair<LocalDate, WorkoutSession>::first)
        .mapNotNull { (date, values) ->
            values.map(Pair<LocalDate, WorkoutSession>::second)
                .maxWithOrNull(preferredWorkoutBurnComparator)
                ?.caloriesBurned
                ?.let { date to it }
        }
        .sortedBy(Pair<LocalDate, Int>::first)

private val preferredWorkoutBurnComparator =
    compareBy<WorkoutSession> { it.healthSyncVersion ?: 0 }
        .thenBy(WorkoutSession::completedAt)
