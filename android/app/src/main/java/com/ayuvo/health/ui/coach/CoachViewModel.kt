package com.ayuvo.health.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.models.ChatMessage
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.services.ai.AiError
import com.ayuvo.health.ui.health.HealthCategoryStyle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Sealed wrapper around chip text — either a resource (for our preset chips,
 * so they translate) or a literal string (for user-typed sends, which already
 * go through the localized input path).
 */
sealed class CoachError {
    data class FromResource(val resId: Int) : CoachError()
    data class Literal(val message: String) : CoachError()
}

data class CoachUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val errorRes: Int? = null,
    val suggestions: List<Int> = emptyList()
)

@OptIn(FlowPreview::class)
class CoachViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    init {
        container.chatRepository.messages
            .onEach { _ui.value = _ui.value.copy(messages = it) }
            .launchIn(viewModelScope)

        // Keep chips current when the user's goal, workout history or synced sleep data changes.
        val sleepAvailable = combine(
            container.prefs.healthHubEnabled,
            container.prefs.coachHealthDataEnabled,
            container.healthRepository.revision.debounce(500)
        ) { hub, coach, _ -> hub && coach }
            .map { allowed -> allowed && runCatching { container.healthRepository.count("sleep") > 0 }.getOrDefault(false) }
        combine(
            container.profileRepository.profile,
            container.workoutRepository.completedSessions,
            sleepAvailable
        ) { profile, sessions, sleep ->
            chipsFor(profile?.goal, hasWorkoutSessions = sessions.isNotEmpty(), hasSleepData = sleep)
        }
            .onEach { suggestions -> _ui.value = _ui.value.copy(suggestions = suggestions) }
            .launchIn(viewModelScope)
    }

    private fun chipsFor(goal: WeightGoal?, hasWorkoutSessions: Boolean, hasSleepData: Boolean = false): List<Int> {
        val goalChips = when (goal) {
            WeightGoal.LOSE -> listOf(
                R.string.coach_chip_predict_30_days,
                R.string.coach_chip_lose_faster,
                R.string.coach_chip_eating_too_much,
                R.string.coach_chip_what_dinner
            )
            WeightGoal.GAIN -> listOf(
                R.string.coach_chip_predict_30_days,
                R.string.coach_chip_gain_healthy,
                R.string.coach_chip_eating_enough,
                R.string.coach_chip_high_protein
            )
            WeightGoal.MAINTAIN -> listOf(
                R.string.coach_chip_holding_weight,
                R.string.coach_chip_average_intake,
                R.string.coach_chip_macro_suggestions,
                R.string.coach_chip_trend
            )
            else -> listOf(
                R.string.coach_chip_doing_this_week,
                R.string.coach_chip_predict_30_days,
                R.string.coach_chip_log_advice
            )
        }
        val withTraining = if (hasWorkoutSessions) listOf(R.string.coach_chip_training) + goalChips else goalChips
        return if (hasSleepData) listOf(R.string.coach_chip_sleep_week) + withTraining else withTraining
    }

    fun send(userText: String, imageBytes: ByteArray? = null, thumbnailBytes: ByteArray? = null) {
        val trimmed = userText.trim()
        if ((trimmed.isBlank() && imageBytes == null) || _ui.value.sending) return
        val text = trimmed.ifEmpty { "Analyze this image." }
        viewModelScope.launch {
            val userMsg = ChatMessage(
                role = ChatMessage.Role.USER,
                content = text,
                attachmentImageBase64 = thumbnailBytes?.let { Base64.getEncoder().encodeToString(it) }
            )
            container.chatRepository.append(userMsg)
            _ui.value = _ui.value.copy(sending = true, error = null, errorRes = null)
            try {
                val history = container.chatRepository.contextMessages(limit = 20).dropLast(1) // exclude the just-appended user msg — it's passed separately
                val profile = container.profileRepository.current()
                    ?: return@launch run {
                        _ui.value = _ui.value.copy(
                            sending = false,
                            errorRes = R.string.coach_no_profile_error
                        )
                    }
                val weights = container.weightRepository.entries.first()
                val bodyFats = container.bodyFatRepository.entries.first()
                val measurements = container.bodyMeasurementRepository.entries.first()
                val foods = container.foodRepository.entries.first()
                val fastingSessions = container.fastingRepository.sessions.first()
                val heightMetric = container.prefs.heightUnit.first() == "cm"
                val weightMetric = container.prefs.weightUnit.first() == "kg"
                val workoutState = container.workoutRepository.snapshot()
                // Health Data hub → Coach only with the hub on AND the visible consent toggle on.
                val healthHubEnabled = container.prefs.healthHubEnabled.first()
                val healthSnapshot = if (healthHubEnabled && container.prefs.coachHealthDataEnabled.first()) {
                    runCatching {
                        container.healthRepository.coachSnapshot(
                            lastSyncMs = container.healthSync.status.value.lastSyncMs ?: container.prefs.lastSyncAtMs(),
                            displayName = { id, hint -> HealthCategoryStyle.typeName(container.appContext, id, hint) }
                        )
                    }.getOrNull()?.takeIf { !it.isEmpty }
                } else null

                val reply = container.chatService.sendMessage(
                    history = history,
                    newUserMessage = text,
                    profile = profile,
                    weights = weights,
                    bodyFats = bodyFats,
                    measurements = measurements,
                    foods = foods,
                    fastingSessions = fastingSessions,
                    heightMetric = heightMetric,
                    weightMetric = weightMetric,
                    imageBytes = imageBytes,
                    workoutSessions = workoutState.completedSessions,
                    workoutPlans = workoutState.dayPlans.values.toList(),
                    workoutPreferences = workoutState.preferences,
                    workoutPlanWeightUnit = if (weightMetric) WorkoutWeightUnit.KG else WorkoutWeightUnit.LBS,
                    healthSnapshot = healthSnapshot,
                    healthHubEnabled = healthHubEnabled
                )
                container.chatRepository.append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply.trim()))
                _ui.value = _ui.value.copy(sending = false)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(sending = false, error = e.message)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(
                    sending = false,
                    error = e.localizedMessage,
                    errorRes = if (e.localizedMessage.isNullOrBlank()) R.string.coach_chat_failed else null
                )
            }
        }
    }

    fun resetConversation() {
        viewModelScope.launch { container.chatRepository.clear() }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null, errorRes = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }
}
