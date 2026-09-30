package com.ayuvo.health.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.data.derived.DerivedMetricInfo
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.ui.health.HealthTileUi
import com.ayuvo.health.ui.metrics.DerivedMetricSupport
import com.ayuvo.health.ui.metrics.DerivedTileSource
import com.ayuvo.health.ui.metrics.MetricTileBuilder
import com.ayuvo.health.ui.metrics.MetricUnits
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the app itself holds, for Browse rows (values, dimming) and the Body / Activity pages. */
data class BrowseAppState(
    val loaded: Boolean = false,
    val snapshot: AppMetricSnapshot = AppMetricSnapshot.EMPTY,
    val units: MetricUnits = MetricUnits(),
    val measurements: List<BodyMeasurement> = emptyList(),
    val recordsCount: Long = 0L,
    val medicationsExist: Boolean = false,
    /** Enabled derived metrics with stored values (docs/derived-metrics.md), catalog order. */
    val derived: List<DerivedBrowseRow> = emptyList()
)

/** One derived metric on Browse: its catalog entry and the latest-value tile (native wins). */
data class DerivedBrowseRow(val key: MetricKey.Derived, val info: DerivedMetricInfo, val tile: HealthTileUi) {
    /** Health category whose Browse page lists it (energy sits with Activity). */
    val healthCategory: String get() = DerivedMetricSupport.healthCategory(info)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BrowseAppState())
    val ui: StateFlow<BrowseAppState> = _ui.asStateFlow()

    init {
        val units = combine(container.prefs.weightUnit, container.prefs.waterUnit) { w, water ->
            MetricUnits(weightMetric = w != "lbs", waterUnit = water)
        }
        combine(container.appMetrics.revision, units, container.bodyMeasurementRepository.entries) { _, u, m -> u to m }
            .mapLatest { (u, m) -> Triple(container.appMetrics.snapshot(), u, m) }
            .onEach { (snap, u, m) ->
                _ui.value = _ui.value.copy(loaded = true, snapshot = snap, units = u, measurements = m)
            }
            .launchIn(viewModelScope)

        container.recordsStore.revision
            .onEach {
                // Read first: a suspending argument inside copy() would write back a stale snapshot.
                val count = runCatching { container.recordsStore.count() }.getOrDefault(0L)
                _ui.value = _ui.value.copy(recordsCount = count)
            }
            .launchIn(viewModelScope)

        // Derived metrics: only once the mirror exists (never opened just to list them).
        container.prefs.healthHubEnabled.flatMapLatest { on ->
            if (on && mirrorExists()) {
                combine(container.healthRepository.derivedRevision, container.prefs.derivedMetricsEnabled, container.prefs.derivedMetricsDisabled) { _, master, off ->
                    container.derivedCatalog.enabledIds(master, off)
                }
            } else flowOf(emptySet())
        }
            .mapLatest { enabled -> derivedRows(enabled) }
            .onEach { rows -> _ui.value = _ui.value.copy(derived = rows) }
            .launchIn(viewModelScope)

        // Opening the medications store creates its database, so only check whether one exists.
        viewModelScope.launch {
            val exists = container.medicationsDatabaseExists()
            _ui.value = _ui.value.copy(medicationsExist = exists)
        }
    }

    private fun mirrorExists(): Boolean = container.appContext.getDatabasePath(HealthDatabase.NAME).exists()

    private suspend fun derivedRows(enabled: Set<String>): List<DerivedBrowseRow> {
        if (enabled.isEmpty()) return emptyList()
        val repo = container.healthRepository
        val withValues = runCatching { repo.derivedMetricIdsWithValues() }.getOrDefault(emptyList()).toSet()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val source = DerivedTileSource(
            catalog = container.derivedCatalog,
            enabled = enabled,
            labels = runCatching { container.derivedConfig.displayLabels(container.appContext) }.getOrDefault(emptyMap()),
            is24 = android.text.format.DateFormat.is24HourFormat(container.appContext)
        )
        return container.derivedCatalog.metrics
            .filter { it.id in enabled && it.id in withValues }
            .map { info ->
                val key = MetricKey.Derived(info.id)
                DerivedBrowseRow(key, info, MetricTileBuilder.derivedTile(key, source, repo, today, zone))
            }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowseViewModel(container) as T
    }
}
