package com.ayuvo.health.widget.dashboard

import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.ui.metrics.MetricTileUi
import com.ayuvo.health.ui.health.HealthTileUi
import java.time.Instant
import java.time.ZoneId

/** Everything the snapshot is made of, already read from the stores. */
data class DashboardInputs(
    val nowMs: Long,
    val zone: ZoneId,
    val themeHex: Int?,
    val weightMetric: Boolean,
    val waterUnitRaw: String,
    val caloriesToday: Double?,
    val calorieGoal: Double?,
    val stepsToday: Double?,
    val stepSource: Boolean,
    val stepGoal: Double?,
    val waterTracking: Boolean,
    val waterTodayMl: Double?,
    val waterGoalMl: Double?,
    val fastingTracking: Boolean,
    val activeFast: FastingSession?,
    /** Null when the medications database does not exist. */
    val medications: TodayTimeline?,
    val weights: List<WeightEntry>,
    val bodyFats: List<BodyFatEntry>,
    val workoutsToday: List<WorkoutSession>,
    val tiles: List<MetricTileUi>,
    /** Goals per metric key in the tile's display unit, for the tile progress bar. */
    val tileGoals: Map<String, Double> = emptyMap()
)

/** Pure snapshot assembly (docs/widgets.md). Missing data stays null; nothing is invented. */
object WidgetDashboardBuilder {

    fun build(i: DashboardInputs): WidgetDashboardSnapshot {
        val dayKey = Instant.ofEpochMilli(i.nowMs).atZone(i.zone).toLocalDate().toString()
        return WidgetDashboardSnapshot(
            generatedAtMs = i.nowMs,
            dayKey = dayKey,
            themeHex = i.themeHex,
            weightMetric = i.weightMetric,
            waterUnitRaw = i.waterUnitRaw,
            eat = RingValue(i.caloriesToday, i.calorieGoal?.takeIf { it > 0 }),
            move = MoveRing(
                steps = if (i.stepSource) i.stepsToday else null,
                goal = i.stepGoal?.takeIf { it > 0 },
                connected = i.stepSource
            ),
            drink = DrinkRing(
                ml = if (i.waterTracking) i.waterTodayMl else null,
                goal = i.waterGoalMl?.takeIf { it > 0 },
                enabled = i.waterTracking
            ),
            fasting = FastingInfo(
                enabled = i.fastingTracking,
                activeStartedAtMs = i.activeFast?.startedAt?.toEpochMilli(),
                goalMinutes = i.activeFast?.goalMinutes
            ),
            medications = i.medications?.let(::medications),
            weight = i.weights.maxByOrNull { it.date }?.let { BodyReading(it.weightKg, it.date.toEpochMilli()) },
            bodyFat = i.bodyFats.maxByOrNull { it.date }?.let { BodyReading(it.bodyFatFraction * 100.0, it.date.toEpochMilli()) },
            workouts = i.workoutsToday.takeIf { it.isNotEmpty() }?.let { list ->
                WorkoutsToday(
                    count = list.size,
                    minutes = list.sumOf { it.durationMinutes },
                    burnKcal = list.mapNotNull { it.caloriesBurned }.takeIf { it.isNotEmpty() }?.sum(),
                    firstTitle = list.minByOrNull { it.startedAt }?.exercises?.firstOrNull()?.name
                )
            },
            tiles = i.tiles.associate { t -> t.key.storageId to tile(t.tile, i.tileGoals[t.key.storageId]) }
        )
    }

    fun tile(t: HealthTileUi, goal: Double?): WidgetTile = WidgetTile(
        number = t.number,
        unit = t.unit,
        caption = t.captionKind.name,
        captionMs = t.captionMs,
        hasData = t.hasData,
        progress = if (t.hasData && goal != null && goal > 0 && t.numeric != null) {
            MetricsReference.ringProgress(t.numeric, goal).progress
        } else null
    )

    private fun medications(timeline: TodayTimeline): MedicationsInfo {
        val doses = timeline.groups.flatMap { it.items }
            .sortedBy { it.scheduledAtMs }
            .map { item -> DoseInfo(timeline.medications[item.medicationId]?.name.orEmpty(), item.scheduledAtMs, item.status.raw) }
        return MedicationsInfo(doses = doses, taken = timeline.summary.taken, total = timeline.summary.total)
    }
}
