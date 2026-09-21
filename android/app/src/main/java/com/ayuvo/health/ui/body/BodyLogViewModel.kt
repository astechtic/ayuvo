package com.ayuvo.health.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

data class BodyLogUiState(
    val entries: List<WeightEntry> = emptyList(),
    val bodyFatEntries: List<BodyFatEntry> = emptyList(),
    val bodyMeasurements: List<BodyMeasurement> = emptyList(),
    val workoutBurnSessions: List<WorkoutSession> = emptyList(),
    val profile: UserProfile? = null,
    val goalReached: Boolean = false
)

/** Weight, body-fat, measurement and workout-burn logging (formerly BodyLogViewModel). */
class BodyLogViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BodyLogUiState())
    val ui: StateFlow<BodyLogUiState> = _ui.asStateFlow()

    init {
        combine(
            container.profileRepository.profile,
            container.weightRepository.entries,
            container.bodyFatRepository.entries,
            container.bodyMeasurementRepository.entries,
            container.workoutRepository.burnSessions
        ) { p, weights, bodyFats, measurements, workouts ->
            _ui.value.copy(
                profile = p,
                entries = weights,
                bodyFatEntries = bodyFats,
                bodyMeasurements = measurements,
                workoutBurnSessions = workouts
            )
        }.onEach { _ui.value = it }.launchIn(viewModelScope)
    }

    fun addWeight(kg: Double) {
        viewModelScope.launch {
            val event = container.weightRepository.addEntry(WeightEntry(weightKg = kg))
            if (event != null) {
                _ui.value = _ui.value.copy(goalReached = true)
                if (container.prefs.notificationsEnabled.first() &&
                    container.prefs.goalReachedNotificationsEnabled.first()
                ) {
                    container.notifications.showGoalReached()
                }
            }
        }
    }

    fun deleteWeight(id: UUID) {
        viewModelScope.launch { container.weightRepository.deleteEntry(id) }
    }

    fun addBodyFat(fraction: Double) {
        viewModelScope.launch {
            container.bodyFatRepository.addEntry(BodyFatEntry(bodyFatFraction = fraction))
        }
    }

    fun deleteBodyFat(id: UUID) {
        viewModelScope.launch { container.bodyFatRepository.deleteEntry(id) }
    }

    fun addBodyMeasurement(entry: BodyMeasurement) {
        viewModelScope.launch { container.bodyMeasurementRepository.addEntry(entry) }
    }

    fun deleteBodyMeasurement(id: UUID) {
        viewModelScope.launch { container.bodyMeasurementRepository.deleteEntry(id) }
    }

    fun deleteWorkoutBurn(id: UUID) {
        viewModelScope.launch { container.workoutRepository.deleteSession(id) }
    }

    fun dismissGoalReached() {
        _ui.value = _ui.value.copy(goalReached = false)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BodyLogViewModel(container) as T
    }
}
