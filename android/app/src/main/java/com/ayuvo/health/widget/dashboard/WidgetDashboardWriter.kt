package com.ayuvo.health.widget.dashboard

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.glance.appwidget.updateAll
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.ui.health.healthUnitPrefsFlow
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.metrics.MetricTileBuilder
import com.ayuvo.health.ui.metrics.MetricUnits
import com.ayuvo.health.ui.theme.AppThemeColor
import com.ayuvo.health.widget.MyMetricsAppWidget
import com.ayuvo.health.widget.QuickLogAppWidget
import com.ayuvo.health.widget.TodayAppWidget
import com.ayuvo.health.widget.WidgetMetric
import com.ayuvo.health.widget.WidgetRefreshScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes [WidgetDashboardSnapshot] for the Today, My Metrics and Quick Log widgets (docs/widgets.md).
 * [observe] republishes (debounced by one second) whenever a source changes while the app runs;
 * [publishOnce] is also called by the widget refresh worker, so widgets update without the app.
 * Nothing is written while none of those widgets is on a home screen.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class WidgetDashboardWriter(private val context: Context, private val container: AppContainer) {
    private val lock = Mutex()

    fun observe(): Flow<Unit> {
        val prefs = container.prefs
        val medsRevision: Flow<Long> = flow {
            if (container.medicationsDatabaseExists()) emitAll(container.medicationsStore.revision) else emit(0L)
        }
        return combine(
            combine(
                container.foodRepository.entries,
                container.waterRepository.entries,
                container.fastingRepository.sessions,
                container.weightRepository.entries,
                container.bodyFatRepository.entries
            ) { a, b, c, d, e -> listOf<Any?>(a, b, c, d, e) },
            combine(
                container.workoutRepository.completedSessions,
                container.profileRepository.profile,
                prefs.waterTrackingEnabled,
                prefs.waterDailyGoalMl,
                prefs.waterUnit
            ) { a, b, c, d, e -> listOf<Any?>(a, b, c, d, e) },
            combine(
                prefs.fastingTrackingEnabled,
                prefs.dailyStepGoal,
                prefs.weightUnit,
                prefs.appThemeColor,
                prefs.healthHubEnabled
            ) { a, b, c, d, e -> listOf<Any?>(a, b, c, d, e) },
            combine(prefs.healthConnectEnabled, container.healthRepository.revision, medsRevision) { a, b, c -> listOf<Any?>(a, b, c) }
        ) { a, b, c, d -> listOf(a, b, c, d) }
            .distinctUntilChanged()
            .debounce(DEBOUNCE_MS)
            .onEach { publishOnce() }
            .map { }
    }

    /** Rebuilds and stores the snapshot, redraws the dashboard widgets and plans the next boundary refresh. */
    suspend fun publishOnce(): Boolean = lock.withLock {
        if (!WidgetRefreshScheduler.hasDashboardWidgets(context)) return@withLock false
        val snapshot = runCatching { build() }.onFailure { Log.e(TAG, "Dashboard snapshot failed", it) }.getOrNull()
            ?: return@withLock false
        val previous = container.prefs.widgetDashboardSnapshot.first()
        if (previous?.copy(generatedAtMs = 0) != snapshot.copy(generatedAtMs = 0)) {
            container.prefs.setWidgetDashboardSnapshot(snapshot)
            runCatching { TodayAppWidget().updateAll(context) }.onFailure { Log.e(TAG, "Today update failed", it) }
            runCatching { MyMetricsAppWidget().updateAll(context) }.onFailure { Log.e(TAG, "My Metrics update failed", it) }
            runCatching { QuickLogAppWidget().updateAll(context) }.onFailure { Log.e(TAG, "Quick Log update failed", it) }
        }
        WidgetRefreshScheduler.scheduleBoundary(context, DashboardRender.nextBoundaryMs(snapshot, System.currentTimeMillis()))
        true
    }

    private suspend fun build(): WidgetDashboardSnapshot {
        val prefs = container.prefs
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val profile = container.profileRepository.profile.first()
        val waterTracking = prefs.waterTrackingEnabled.first()
        val waterGoal = prefs.waterDailyGoalMl.first()
        val waterUnit = prefs.waterUnit.first()
        val weightMetric = prefs.weightUnit.first() != "lbs"
        val fastingTracking = prefs.fastingTrackingEnabled.first()
        val stepGoal = prefs.dailyStepGoal.first()
        val hub = prefs.healthHubEnabled.first()
        val units = MetricUnits(weightMetric = weightMetric, waterUnit = waterUnit)

        val food = container.foodRepository.entries.first()
        val calories = food.filter { it.timestamp.atZone(zone).toLocalDate() == today }.sumOf { it.calories }.toDouble()
        val waterMl = container.waterRepository.entries.first()
            .filter { it.date.atZone(zone).toLocalDate() == today }.sumOf { it.milliliters }.toDouble()
        val activeFast = container.fastingRepository.sessions.first().lastOrNull { it.isActive }
        val workouts = container.workoutRepository.completedSessions.first()
            .filter { MetricsReference.workoutDay(it.diaryDateKey, it.startedAt.toEpochMilli(), zone) == today }
        val steps = liveSteps(today)
        val medications = if (container.medicationsDatabaseExists()) {
            runCatching { container.medicationsStore.today(nowMs, zone.id) }.getOrNull()
        } else null

        val hidden = buildSet {
            if (!waterTracking) add(AppMetricId.WATER)
            if (!fastingTracking) add(AppMetricId.FASTING)
        }
        val keys = WidgetMetric.entries.filter { it.tap != WidgetMetric.Tap.MEDICATIONS }.mapNotNull { MetricKey.parse(it.key) }
        val tiles = MetricTileBuilder.build(
            keys = keys,
            snapshot = container.appMetrics.snapshot(),
            health = container.healthRepository,
            hubEnabled = hub,
            today = today,
            nowMs = nowMs,
            healthUnits = healthUnitPrefsFlow(prefs).first(),
            units = units,
            liveStepsToday = steps?.second,
            hidden = hidden,
            zone = zone
        )
        val goals = buildMap {
            profile?.let {
                put(AppMetricId.CALORIES.key, it.effectiveCalories.toDouble())
                put(AppMetricId.PROTEIN.key, it.effectiveProtein.toDouble())
                put(AppMetricId.CARBS.key, it.effectiveCarbs.toDouble())
                put(AppMetricId.FAT.key, it.effectiveFat.toDouble())
            }
            if (waterTracking) put(AppMetricId.WATER.key, MetricCatalog.display(AppMetricId.WATER, waterGoal.toDouble(), units))
            put(HealthDataType.STEPS.id, stepGoal.toDouble())
        }
        return WidgetDashboardBuilder.build(
            DashboardInputs(
                nowMs = nowMs,
                zone = zone,
                themeHex = AppThemeColor.fromKey(prefs.appThemeColor.first()).start.toArgb() and 0xFFFFFF,
                weightMetric = weightMetric,
                waterUnitRaw = waterUnit.storageValue,
                caloriesToday = calories,
                calorieGoal = profile?.effectiveCalories?.toDouble(),
                stepsToday = steps?.second?.toDouble(),
                stepSource = steps != null,
                stepGoal = stepGoal.toDouble(),
                waterTracking = waterTracking,
                waterTodayMl = waterMl,
                waterGoalMl = waterGoal.toDouble(),
                fastingTracking = fastingTracking,
                activeFast = activeFast,
                medications = medications,
                weights = container.weightRepository.entries.first(),
                bodyFats = container.bodyFatRepository.entries.first(),
                workoutsToday = workouts,
                tiles = tiles,
                tileGoals = goals
            )
        )
    }

    /**
     * Today's steps from Health Connect, like the Summary Move ring: null when sync is off or steps
     * are not readable (Move then shows Connect); `true to null` when readable but the read failed.
     */
    private suspend fun liveSteps(today: LocalDate): Pair<Boolean, Int?>? {
        if (!container.prefs.healthConnectEnabled.first()) return null
        val readable = runCatching { container.health.hasStepsRead() }.getOrDefault(false)
        if (!readable) return null
        return true to runCatching { container.health.readStepsForDay(today) }.getOrNull()
    }

    private companion object {
        const val TAG = "AyuvoWidget"
        const val DEBOUNCE_MS = 1_000L
    }
}
