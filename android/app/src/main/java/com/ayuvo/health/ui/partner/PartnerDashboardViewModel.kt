package com.ayuvo.health.ui.partner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PartnerDashboardUi(
    val loaded: Boolean = false,
    val dashboard: PartnerDashboard? = null,
    val windowRunning: Boolean = false,
    val weightMetric: Boolean = true
)

/** Partner dashboard state: everything is read from ayuvo_partner.db on Dispatchers.IO and re-read after a sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class PartnerDashboardViewModel(private val container: AppContainer, private val ownerId: String) : ViewModel() {
    private val _ui = MutableStateFlow(PartnerDashboardUi())
    val ui: StateFlow<PartnerDashboardUi> = _ui.asStateFlow()
    private val tick = MutableStateFlow(0)
    private val manager get() = container.partnerManager

    init {
        manager.state.map { it.windowRunning }.distinctUntilChanged()
            .onEach { running -> _ui.value = _ui.value.copy(windowRunning = running) }
            .launchIn(viewModelScope)
        combine(
            manager.state.map { st -> st.partners.firstOrNull { it.partner.ownerId == ownerId }?.let { it.sync to it.recordCount } }.distinctUntilChanged(),
            tick
        ) { _, _ -> Unit }
            .mapLatest {
                if (!container.partnerDatabaseExists()) null
                else runCatching { PartnerDashboardBuilder.load(container.partnerStore, ownerId, LocalDate.now()) }.getOrNull()
            }
            .flowOn(Dispatchers.IO)
            .onEach { d -> _ui.value = _ui.value.copy(loaded = true, dashboard = d) }
            .launchIn(viewModelScope)
        viewModelScope.launch {
            _ui.value = _ui.value.copy(weightMetric = container.prefs.weightUnit.first() != "lbs")
        }
    }

    fun refresh() {
        tick.value += 1
    }

    fun syncNow() {
        manager.syncNow()
    }

    class Factory(private val container: AppContainer, private val ownerId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PartnerDashboardViewModel(container, ownerId) as T
    }
}
