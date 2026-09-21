package com.ayuvo.health.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.ui.metrics.MetricUnits
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
    val medicationsExist: Boolean = false
)

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

        // Opening the medications store creates its database, so only check whether one exists.
        viewModelScope.launch {
            val exists = container.medicationsDatabaseExists()
            _ui.value = _ui.value.copy(medicationsExist = exists)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowseViewModel(container) as T
    }
}
