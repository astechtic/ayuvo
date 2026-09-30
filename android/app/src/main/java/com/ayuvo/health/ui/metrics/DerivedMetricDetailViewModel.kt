package com.ayuvo.health.ui.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.derived.DerivedMetricInfo
import com.ayuvo.health.data.health.DerivedPoint
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.data.metrics.MetricBucketBounds
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricSeriesBucket
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.ui.health.HealthChartRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class DerivedMetricDetailUi(
    val key: MetricKey.Derived,
    val info: DerivedMetricInfo?,
    val ranges: List<HealthChartRange>,
    val range: HealthChartRange,
    val anchor: LocalDate = LocalDate.now(),
    val weekStart: WeekStart = WeekStart.MONDAY,
    val bounds: MetricBucketBounds? = null,
    val buckets: List<MetricSeriesBucket> = emptyList(),
    /** Every day in the interval after native wins, ascending. */
    val points: List<DerivedPoint> = emptyList(),
    val headline: DerivedHeadline? = null,
    /** Derived Metrics master switch. */
    val masterOn: Boolean = true,
    /** This metric's own switch (not in `derivedMetricsDisabled`). */
    val metricOn: Boolean = true,
    /** Titles of the metrics that also stop when this one is off (`requires`, transitively). */
    val dependents: List<String> = emptyList(),
    /** `labels` of the config for band metrics (`step_band`). */
    val labels: List<String>? = null,
    val pinned: Boolean = false,
    val loading: Boolean = true
) {
    val canGoForward: Boolean get() = bounds?.canGoForward ?: false
    val calculated: Boolean get() = masterOn && metricOn
}

/**
 * `derived:<id>` detail (docs/derived-metrics.md): one value per day from `derived_daily_values` with native wins applied,
 * bucketed by the metric's `aggregation`. The switch writes `derivedMetricsDisabled`; the service then deletes or
 * recomputes the stored values and bumps `derivedRevision`, which reloads this screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DerivedMetricDetailViewModel(private val container: AppContainer, private val key: MetricKey.Derived) : ViewModel() {
    private val catalog = container.derivedCatalog
    private val info = catalog.byId[key.id]
    private val ranges = HealthChartRange.entries.toList()
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val selection = MutableStateFlow(HealthChartRange.WEEK to LocalDate.now())
    private val _ui = MutableStateFlow(
        DerivedMetricDetailUi(
            key = key,
            info = info,
            ranges = ranges,
            range = selection.value.first,
            dependents = catalog.dependentsOf(key.id).map { it.title },
            labels = runCatching { container.derivedConfig.labels[key.id] }.getOrNull()
        )
    )
    val ui: StateFlow<DerivedMetricDetailUi> = _ui.asStateFlow()

    private data class Inputs(
        val range: HealthChartRange,
        val anchor: LocalDate,
        val weekStart: WeekStart,
        val masterOn: Boolean,
        val disabled: Set<String>,
        val pinned: Boolean
    )

    private fun mirrorExists(): Boolean = container.appContext.getDatabasePath(HealthDatabase.NAME).exists()

    /** Mirror and derived revisions while the hub is on; never opens the mirror just to watch it. */
    private fun revisions(): Flow<Pair<Long, Long>> = container.prefs.healthHubEnabled.flatMapLatest { on ->
        if (on && mirrorExists()) {
            combine(container.healthRepository.revision, container.healthRepository.derivedRevision) { a, b -> a to b }
        } else flowOf(-1L to -1L)
    }

    init {
        combine(
            combine(selection, container.prefs.weekStartsOnMonday, revisions()) { sel, monday, _ -> sel to WeekStart.of(monday) },
            container.prefs.derivedMetricsEnabled,
            container.prefs.derivedMetricsDisabled,
            container.favoritePins.isPinned(key)
        ) { (sel, week), master, disabled, pinned -> Inputs(sel.first, sel.second, week, master, disabled, pinned) }
            .mapLatest { i -> load(i) }
            .onEach { _ui.value = it }
            .launchIn(viewModelScope)
    }

    private suspend fun load(i: Inputs): DerivedMetricDetailUi {
        val z = zone
        val bounds = DerivedMetricSupport.bounds(i.range.metricRange, i.anchor, z, i.weekStart, System.currentTimeMillis())
        val from = MetricsReference.localDateOf(bounds.startMs, z)
        val to = MetricsReference.localDateOf(bounds.endMs, z).minusDays(1)
        val hubOn = container.prefs.healthHubEnabled.first()
        val metric = info
        val points = if (metric != null && hubOn && mirrorExists()) {
            runCatching { container.healthRepository.derivedSeries(metric.id, metric.nativeTypeId, from, to) }.getOrDefault(emptyList())
        } else emptyList()
        val aggregation = info?.let(DerivedMetricSupport::aggregation) ?: DerivedAggregation.AVERAGE
        return _ui.value.copy(
            range = i.range,
            anchor = i.anchor,
            weekStart = i.weekStart,
            bounds = bounds,
            buckets = DerivedMetricSupport.series(points, bounds, aggregation, z),
            points = points,
            headline = DerivedMetricSupport.headline(points, i.range.metricRange, aggregation),
            masterOn = i.masterOn,
            metricOn = key.id !in i.disabled,
            pinned = i.pinned,
            loading = false
        )
    }

    fun setRange(range: HealthChartRange) {
        if (range in ranges) selection.value = MetricNavigation.changeRange(selection.value, range, LocalDate.now(zone))
    }

    fun drillTo(day: LocalDate) {
        selection.value = MetricNavigation.changeRange(selection.value.first to day, HealthChartRange.DAY, LocalDate.now(zone))
    }

    fun shiftAnchor(direction: Int) {
        val (range, anchor) = selection.value
        selection.value = range to range.step(anchor, direction, zone, System.currentTimeMillis())
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { container.favoritePins.toggle(key, pinned) }
    }

    /** "Calculate this metric": the same switch as Settings › Derived Metrics. */
    fun setCalculated(on: Boolean) {
        viewModelScope.launch {
            val disabled = container.prefs.derivedMetricsDisabled.first()
            container.prefs.setDerivedMetricsDisabled(if (on) disabled - key.id else disabled + key.id)
        }
    }

    class Factory(private val container: AppContainer, private val key: MetricKey.Derived) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DerivedMetricDetailViewModel(container, key) as T
    }
}
