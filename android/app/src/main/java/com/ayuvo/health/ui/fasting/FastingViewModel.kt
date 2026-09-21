package com.ayuvo.health.ui.fasting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.models.FastingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

data class FastingUiState(
    val loading: Boolean = true,
    val trackingEnabled: Boolean = false,
    val defaultGoalMinutes: Int = 16 * 60,
    val sessions: List<FastingSession> = emptyList(),
    val overlap: Boolean = false
) {
    val active: FastingSession? get() = sessions.lastOrNull { it.isActive }

    /** Completed fasts, newest first (end-day attribution, as in the diary). */
    val history: List<FastingSession> get() = sessions.filter { !it.isActive }.sortedByDescending { it.endedAt }
}

/** Browse › Fasting: the active fast, its controls and the history list. */
class FastingViewModel(private val container: AppContainer) : ViewModel() {
    private val actions = FastingActions(container)
    private val _ui = MutableStateFlow(FastingUiState())
    val ui: StateFlow<FastingUiState> = _ui.asStateFlow()

    init {
        combine(
            container.prefs.fastingTrackingEnabled,
            container.prefs.fastingDefaultGoalMinutes,
            container.fastingRepository.sessions
        ) { enabled, goal, sessions -> Triple(enabled, goal, sessions) }
            .onEach { (enabled, goal, sessions) ->
                _ui.value = _ui.value.copy(loading = false, trackingEnabled = enabled, defaultGoalMinutes = goal, sessions = sessions)
            }
            .launchIn(viewModelScope)
    }

    fun enableTracking() {
        viewModelScope.launch { container.prefs.setFastingTrackingEnabled(true) }
    }

    fun start(goalMinutes: Int) {
        viewModelScope.launch { actions.start(goalMinutes) }
    }

    fun end(updated: FastingSession? = null) {
        viewModelScope.launch { if (!actions.end(updated)) _ui.value = _ui.value.copy(overlap = true) }
    }

    fun cancel() {
        viewModelScope.launch { actions.cancel() }
    }

    fun update(session: FastingSession) {
        viewModelScope.launch { if (!actions.update(session)) _ui.value = _ui.value.copy(overlap = true) }
    }

    fun delete(id: UUID) {
        viewModelScope.launch { actions.delete(id) }
    }

    fun dismissOverlap() {
        _ui.value = _ui.value.copy(overlap = false)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FastingViewModel(container) as T
    }
}
