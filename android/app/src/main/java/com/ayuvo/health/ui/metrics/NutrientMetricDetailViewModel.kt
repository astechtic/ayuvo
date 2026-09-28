package com.ayuvo.health.ui.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.NutrientMetricSeries
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientReference
import com.ayuvo.health.nutrients.Nutrients
import com.ayuvo.health.nutrients.ReferenceLines
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

data class NutrientMetricDetailUi(
    val key: MetricKey.Nutrient,
    val ranges: List<HealthChartRange>,
    val range: HealthChartRange,
    val anchor: LocalDate = LocalDate.now(),
    val weekStart: WeekStart = WeekStart.MONDAY,
    val series: NutrientMetricSeries? = null,
    /** Reference lines for the user's age, sex, calorie goal and custom goal (null without a reference). */
    val lines: ReferenceLines? = null,
    val customGoal: Int? = null,
    val profile: UserProfile? = null,
    val pinned: Boolean = false,
    val loading: Boolean = true
) {
    val canGoForward: Boolean get() = series?.bounds?.canGoForward ?: false
}

/** `nutrient:<key>` detail (docs/nutrients.md §5): food + supplement series with the reference lines. */
@OptIn(ExperimentalCoroutinesApi::class)
class NutrientMetricDetailViewModel(private val container: AppContainer, private val key: MetricKey.Nutrient) : ViewModel() {
    private val ranges = MetricCatalog.ranges(container.metricCatalog, key)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val selection = MutableStateFlow((ranges.firstOrNull { it == HealthChartRange.WEEK } ?: ranges.first()) to LocalDate.now())
    private val _ui = MutableStateFlow(NutrientMetricDetailUi(key = key, ranges = ranges, range = selection.value.first))
    val ui: StateFlow<NutrientMetricDetailUi> = _ui.asStateFlow()

    private data class Inputs(
        val range: HealthChartRange,
        val anchor: LocalDate,
        val revision: Long,
        val weekStart: WeekStart,
        val profile: UserProfile?,
        val goals: OptionalNutrientGoals,
        val pinned: Boolean
    )

    init {
        combine(
            combine(selection, container.appMetrics.revision, container.prefs.weekStartsOnMonday) { sel, rev, monday -> Triple(sel, rev, WeekStart.of(monday)) },
            container.profileRepository.profile,
            container.prefs.optionalNutrientGoals,
            container.favoritePins.isPinned(key)
        ) { (sel, rev, week), profile, goals, pinned -> Inputs(sel.first, sel.second, rev, week, profile, goals, pinned) }
            .mapLatest { i ->
                val anchorMs = MetricsReference.localMidnight(i.anchor, zone)
                val series = container.appMetrics.nutrientSeries(key.key, i.range.metricRange, anchorMs, i.weekStart)
                // Untracked nutrients (copper, thiamin, …) never have a custom goal: reference lines only.
                val custom = NutrientFields.optionalNutrient(key.key)?.takeIf { NutrientFields.foodTracked(key.key) }?.let { i.goals.customGoal(it) }
                val lines = if (NutrientReference.active?.unitOf(key.key) != null) {
                    Nutrients.referenceLines(key.key, NutrientFields.profile(i.profile), custom?.toDouble())
                } else null
                _ui.value.copy(
                    range = i.range, anchor = i.anchor, weekStart = i.weekStart, series = series, lines = lines,
                    customGoal = custom, profile = i.profile, pinned = i.pinned, loading = false
                )
            }
            .onEach { _ui.value = it }
            .launchIn(viewModelScope)
    }

    fun setRange(range: HealthChartRange) {
        if (range in ranges) selection.value = MetricNavigation.changeRange(selection.value, range, LocalDate.now(zone))
    }

    fun drillTo(day: LocalDate) {
        if (HealthChartRange.DAY in ranges) selection.value = MetricNavigation.changeRange(selection.value.first to day, HealthChartRange.DAY, LocalDate.now(zone))
    }

    fun shiftAnchor(direction: Int) {
        val (range, anchor) = selection.value
        selection.value = range to range.step(anchor, direction, zone, System.currentTimeMillis())
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { container.favoritePins.toggle(key, pinned) }
    }

    class Factory(private val container: AppContainer, private val key: MetricKey.Nutrient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NutrientMetricDetailViewModel(container, key) as T
    }
}
