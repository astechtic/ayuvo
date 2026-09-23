package com.ayuvo.health.ui.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.AppMetricSeries
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.ui.health.HealthChartRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class AppMetricDetailUi(
    val id: AppMetricId,
    val ranges: List<HealthChartRange>,
    val range: HealthChartRange,
    val anchor: LocalDate = LocalDate.now(),
    val weekStart: WeekStart = WeekStart.MONDAY,
    val series: AppMetricSeries? = null,
    val snapshot: AppMetricSnapshot = AppMetricSnapshot.EMPTY,
    val units: MetricUnits = MetricUnits(),
    /** Goal in canonical units (null = none set). */
    val goal: Double? = null,
    val pinned: Boolean = false,
    val profile: UserProfile? = null,
    val loading: Boolean = true
) {
    val canGoForward: Boolean get() = series?.bounds?.canGoForward ?: false

    /** Water logs inside the shown interval, newest first. */
    fun waterInWindow(): List<WaterEntry> {
        val b = series?.bounds ?: return emptyList()
        return snapshot.water.filter { it.date.toEpochMilli() >= b.startMs && it.date.toEpochMilli() < b.endMs }.sortedByDescending { it.date }
    }
}

/** App-metric detail (docs/ui-structure.md §6); weight/body-fat logging goes through BodyLogViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppMetricDetailViewModel(private val container: AppContainer, private val id: AppMetricId) : ViewModel() {
    private val catalog = container.metricCatalog
    private val ranges = MetricCatalog.ranges(catalog, id)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val selection = MutableStateFlow((ranges.firstOrNull { it == HealthChartRange.WEEK } ?: ranges.first()) to LocalDate.now())
    private val _ui = MutableStateFlow(AppMetricDetailUi(id = id, ranges = ranges, range = selection.value.first))
    val ui: StateFlow<AppMetricDetailUi> = _ui.asStateFlow()

    private data class Inputs(
        val range: HealthChartRange,
        val anchor: LocalDate,
        val revision: Long,
        val weekStart: WeekStart,
        val units: MetricUnits,
        val goals: MetricGoalInputs,
        val pinned: Boolean
    )

    init {
        val units = combine(container.prefs.weightUnit, container.prefs.waterUnit) { w, water -> MetricUnits(weightMetric = w != "lbs", waterUnit = water) }
        val goals = combine(container.profileRepository.profile, container.prefs.waterDailyGoalMl, container.prefs.dailyStepGoal) { p, water, steps ->
            MetricGoalInputs(p, water, steps)
        }
        combine(
            combine(selection, container.appMetrics.revision, container.prefs.weekStartsOnMonday) { sel, rev, monday -> Triple(sel, rev, WeekStart.of(monday)) },
            units,
            goals,
            container.favoritePins.isPinned(MetricKey.App(id))
        ) { (sel, rev, week), u, g, pinned -> Inputs(sel.first, sel.second, rev, week, u, g, pinned) }
            .mapLatest { i ->
                val anchorMs = MetricsReference.localMidnight(i.anchor, zone)
                val series = container.appMetrics.series(id, i.range.metricRange, anchorMs, i.weekStart)
                _ui.value.copy(
                    range = i.range, anchor = i.anchor, weekStart = i.weekStart, series = series,
                    snapshot = container.appMetrics.snapshot(), units = i.units,
                    goal = MetricCatalog.goal(catalog, id, i.goals), pinned = i.pinned,
                    profile = i.goals.profile, loading = false
                )
            }
            .onEach { _ui.value = it }
            .launchIn(viewModelScope)
    }

    fun setRange(range: HealthChartRange) {
        if (range in ranges) selection.value = MetricNavigation.changeRange(selection.value, range, LocalDate.now(zone))
    }

    /** Tap on a W/M day bucket: open the Day chart of that day (docs/charts.md). */
    fun drillTo(day: LocalDate) {
        if (HealthChartRange.DAY in ranges) selection.value = MetricNavigation.changeRange(selection.value.first to day, HealthChartRange.DAY, LocalDate.now(zone))
    }

    fun shiftAnchor(direction: Int) {
        val (range, anchor) = selection.value
        selection.value = range to range.step(anchor, direction, zone, System.currentTimeMillis())
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { container.favoritePins.toggle(MetricKey.App(id), pinned) }
    }

    fun setWeightMetric(metric: Boolean) {
        viewModelScope.launch { container.prefs.setWeightUnit(if (metric) "kg" else "lbs") }
    }

    fun setWaterUnit(unit: WaterUnit) {
        viewModelScope.launch { container.prefs.setWaterUnit(unit) }
    }

    fun addWater(milliliters: Int) {
        viewModelScope.launch { container.waterRepository.add(WaterEntry(milliliters = milliliters)) }
    }

    fun deleteWater(entryId: UUID) {
        viewModelScope.launch { container.waterRepository.delete(entryId) }
    }

    class Factory(private val container: AppContainer, private val id: AppMetricId) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppMetricDetailViewModel(container, id) as T
    }
}
