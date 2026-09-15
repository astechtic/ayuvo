package com.ayuvo.health.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.ChatMessage
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.records.coach.CoachRecordsRequest
import com.ayuvo.health.records.coach.RecordsCoach
import com.ayuvo.health.records.coach.RecordsCoachSelection
import com.ayuvo.health.records.coach.RecordsCoachSwitchToOnDevice
import com.ayuvo.health.records.coach.RecordsCoachTools
import com.ayuvo.health.records.coach.StoreCoachData
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.records.model.RecordsSearchTerms
import com.ayuvo.health.records.processing.RecordRules
import com.ayuvo.health.services.ai.AiError
import com.ayuvo.health.services.ai.ChatService
import com.ayuvo.health.services.ondevice.LocalModelId
import com.ayuvo.health.ui.health.HealthCategoryStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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

/** Coach suggestion chips over Health Records (§27). */
enum class CoachRecordsChip(val labelRes: Int) {
    ANALYZE_LATEST(R.string.records_coach_chip_latest),
    FIND_ABNORMAL(R.string.records_coach_chip_abnormal),
    COMPARE_PREVIOUS(R.string.records_coach_chip_compare)
}

/** §26 consent sheet: [providerName] is the online provider, or null for on-device Coach. */
data class CoachRecordsConsent(val providerName: String?)

/** §30 prompt: shown before records' details go to an online provider while records AI is local/off. */
data class CoachRecordsModePrompt(val providerName: String, val onDeviceAvailable: Boolean)

enum class CoachRecordsModeChoice { SEND, CANCEL, ON_DEVICE }

data class CoachPrefill(val text: String, val id: Long = System.nanoTime())

data class CoachUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val errorRes: Int? = null,
    val suggestions: List<Int> = emptyList(),
    // Health Records (§26–§30)
    val recordsAccessEnabled: Boolean = false,
    val selectedRecords: List<HealthRecord> = emptyList(),
    val recordChips: List<CoachRecordsChip> = emptyList(),
    val consent: CoachRecordsConsent? = null,
    val modePrompt: CoachRecordsModePrompt? = null,
    val prefill: CoachPrefill? = null,
    val pickerOpen: Boolean = false,
    val pickerCandidates: List<HealthRecord> = emptyList()
)

@OptIn(FlowPreview::class)
class CoachViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    // -- Conversation-scoped records state (in memory; cleared by "New chat") --------------------
    private var chipSelections: RecordsCoachSelection.Chips? = null
    private var pendingRequest: CoachRecordsRequest? = null
    /** §30 decision for this conversation: null = not asked yet. */
    private var modeDecision: CoachRecordsModeChoice? = null
    private var providerOverride: AIProvider? = null
    private var modeAnswer: CompletableDeferred<CoachRecordsModeChoice>? = null

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

        viewModelScope.launch {
            container.prefs.healthRecordsCoachAccessEnabled.collectLatest { enabled ->
                _ui.update { it.copy(recordsAccessEnabled = enabled) }
                // Turning access off removes the tools and the selection immediately (§26).
                if (!enabled) {
                    chipSelections = null
                    _ui.update { it.copy(selectedRecords = emptyList(), recordChips = emptyList()) }
                    return@collectLatest
                }
                if (!container.recordsDatabaseExists()) return@collectLatest
                container.recordsStore.revision.debounce(300).collect { refreshRecordChips() }
            }
        }

        container.coachRecordsRequests.filterNotNull()
            .onEach { request ->
                container.coachRecordsRequests.compareAndSet(request, null)
                handleRequest(request)
            }
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

    private suspend fun refreshRecordChips() {
        val chips = runCatching { RecordsCoachSelection.chips(container.recordsStore) { container.analyteCatalog } }.getOrNull()
        chipSelections = chips
        val list = if (chips == null) emptyList() else buildList {
            add(CoachRecordsChip.ANALYZE_LATEST)
            add(CoachRecordsChip.FIND_ABNORMAL)
            if (chips.comparePair != null) add(CoachRecordsChip.COMPARE_PREVIOUS)
        }
        _ui.update { it.copy(recordChips = list) }
    }

    // -- Entry points (§27) ----------------------------------------------------------------------

    private suspend fun handleRequest(request: CoachRecordsRequest) {
        if (container.prefs.healthRecordsCoachAccessEnabled.first()) {
            applySelection(request.recordIds)
            _ui.update { it.copy(prefill = CoachPrefill(request.prompt)) }
        } else {
            pendingRequest = request
            _ui.update { it.copy(consent = consentPrompt()) }
        }
    }

    private suspend fun consentPrompt(): CoachRecordsConsent {
        val provider = providerOverride ?: container.chatService.coachProvider(hasImage = false)
        return CoachRecordsConsent(if (provider == AIProvider.LOCAL_GEMMA) null else container.appContext.getString(provider.displayNameRes))
    }

    fun allowRecordsAccess() {
        viewModelScope.launch {
            container.prefs.setHealthRecordsCoachAccess(true, java.time.Instant.now().toString())
            val request = pendingRequest
            pendingRequest = null
            _ui.update { it.copy(consent = null) }
            if (request != null) {
                applySelection(request.recordIds)
                _ui.update { it.copy(prefill = CoachPrefill(request.prompt)) }
            }
        }
    }

    /** "Not now": the prompt is still prefilled, but no records are selected. */
    fun declineRecordsAccess() {
        val request = pendingRequest
        pendingRequest = null
        _ui.update { it.copy(consent = null, prefill = request?.prompt?.takeIf { p -> p.isNotBlank() }?.let { p -> CoachPrefill(p) } ?: it.prefill) }
    }

    fun consumePrefill(id: Long) {
        _ui.update { if (it.prefill?.id == id) it.copy(prefill = null) else it }
    }

    private suspend fun applySelection(ids: List<String>) {
        val normalized = RecordsCoach.normalizeSelection(ids)
        val records = if (normalized.isEmpty() || !container.recordsDatabaseExists()) emptyList() else {
            val byId = runCatching { container.recordsStore.records(normalized) }.getOrDefault(emptyList()).associateBy { it.id }
            normalized.mapNotNull { byId[it] }
        }
        _ui.update { it.copy(selectedRecords = records) }
    }

    fun removeSelectedRecord(id: String) {
        _ui.update { s -> s.copy(selectedRecords = s.selectedRecords.filter { it.id != id }) }
    }

    fun openPicker() {
        _ui.update { it.copy(pickerOpen = true) }
        searchPicker("")
    }

    fun closePicker(selectedIds: List<String>?) {
        _ui.update { it.copy(pickerOpen = false) }
        if (selectedIds != null) viewModelScope.launch { applySelection(selectedIds) }
    }

    fun searchPicker(text: String) {
        if (!container.recordsDatabaseExists()) return
        viewModelScope.launch {
            runCatching {
                val terms = RecordsSearchTerms.split(text)
                if (terms.isEmpty()) container.recordsStore.recent(40)
                else container.recordsStore.search(RecordQuery(terms = terms), limit = 40).map { it.record }
            }.onSuccess { list -> _ui.update { it.copy(pickerCandidates = list) } }
        }
    }

    fun sendRecordsChip(chip: CoachRecordsChip, text: String) {
        viewModelScope.launch {
            val chips = chipSelections
            when (chip) {
                CoachRecordsChip.ANALYZE_LATEST -> applySelection(chips?.latestLabs.orEmpty())
                CoachRecordsChip.FIND_ABNORMAL -> _ui.update { it.copy(selectedRecords = emptyList()) }
                CoachRecordsChip.COMPARE_PREVIOUS -> applySelection(chips?.comparePair.orEmpty())
            }
            send(text)
        }
    }

    // -- §30 mode interplay ------------------------------------------------------------------------

    fun answerModePrompt(choice: CoachRecordsModeChoice) {
        _ui.update { it.copy(modePrompt = null) }
        modeAnswer?.complete(choice)
    }

    private suspend fun needsModePrompt(provider: AIProvider): Boolean {
        if (provider == AIProvider.LOCAL_GEMMA || modeDecision != null) return false
        val mode = RecordsAiMode.fromRaw(container.prefs.healthRecordsAiMode.first())
        return mode == RecordsAiMode.LOCAL || mode == RecordsAiMode.OFF
    }

    private suspend fun askMode(provider: AIProvider): CoachRecordsModeChoice {
        val deferred = CompletableDeferred<CoachRecordsModeChoice>()
        modeAnswer = deferred
        _ui.update {
            it.copy(modePrompt = CoachRecordsModePrompt(
                providerName = container.appContext.getString(provider.displayNameRes),
                onDeviceAvailable = container.localModels.isExecutable(LocalModelId.GEMMA_4_E2B)
            ))
        }
        val choice = deferred.await()
        modeAnswer = null
        when (choice) {
            CoachRecordsModeChoice.SEND -> modeDecision = CoachRecordsModeChoice.SEND
            CoachRecordsModeChoice.CANCEL -> {
                modeDecision = CoachRecordsModeChoice.CANCEL
                _ui.update { it.copy(selectedRecords = emptyList()) }
            }
            CoachRecordsModeChoice.ON_DEVICE -> {
                modeDecision = CoachRecordsModeChoice.ON_DEVICE
                providerOverride = AIProvider.LOCAL_GEMMA
            }
        }
        return choice
    }

    private suspend fun recordsTurn(text: String, provider: AIProvider): ChatService.RecordsTurn? {
        val access = container.prefs.healthRecordsCoachAccessEnabled.first()
        val blocked = modeDecision == CoachRecordsModeChoice.CANCEL
        val contract = container.recordsCoachContract
        if (blocked) return null
        if (!access || !container.recordsDatabaseExists()) {
            // §26: the not-available line only when access is off and the message names records.
            return if (!access && RecordsCoach.mentionsRecords(text)) ChatService.RecordsTurn(notAvailableLine = contract.prompt.notAvailableLine) else null
        }
        val store = container.recordsStore
        val data = StoreCoachData(store) { container.analyteCatalog }
        val selectedIds = _ui.value.selectedRecords.map { it.id }
        val lines = RecordsCoach.promptLines(data, contract, accessEnabled = true, selectedIds = selectedIds)
        val onDevice = provider == AIProvider.LOCAL_GEMMA
        val tools = if (lines.advertiseTools && !onDevice) {
            val gate: (suspend () -> Boolean)? = if (needsModePrompt(provider)) {
                {
                    when (modeDecision ?: askMode(provider)) {
                        CoachRecordsModeChoice.SEND -> true
                        CoachRecordsModeChoice.CANCEL -> false
                        CoachRecordsModeChoice.ON_DEVICE -> throw RecordsCoachSwitchToOnDevice()
                    }
                }
            } else null
            RecordsCoachTools(
                contract = contract,
                data = data,
                selectedIds = selectedIds,
                dateOrder = RecordRules.deviceDateOrder(),
                stillAvailable = {
                    container.prefs.healthRecordsCoachAccessEnabled.first() && store.allRecords().any { !it.archived }
                },
                beforeFirstCall = gate
            )
        } else null
        val packed = if (selectedIds.isEmpty()) null else RecordsCoach.pack(data, selectedIds)
        return ChatService.RecordsTurn(
            tools = tools,
            availableLine = lines.availableLine,
            selectedLines = lines.selectedLines,
            guardrails = lines.guardrails,
            onDeviceBlock = packed?.text,
            onDeviceRefs = packed?.records.orEmpty().map(RecordsCoach::ref)
        )
    }

    // -- Send --------------------------------------------------------------------------------------

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

                // §30: selected records going to an online provider while records AI is local/off.
                val initialProvider = providerOverride ?: container.chatService.coachProvider(imageBytes != null)
                if (_ui.value.selectedRecords.isNotEmpty() && container.prefs.healthRecordsCoachAccessEnabled.first() && needsModePrompt(initialProvider)) {
                    askMode(initialProvider)
                }

                suspend fun attempt(): ChatService.CoachReply {
                    val provider = providerOverride ?: container.chatService.coachProvider(imageBytes != null)
                    return container.chatService.send(
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
                        healthHubEnabled = healthHubEnabled,
                        records = runCatching { recordsTurn(text, provider) }.getOrNull(),
                        providerOverride = providerOverride
                    )
                }

                val reply = try {
                    attempt()
                } catch (switch: RecordsCoachSwitchToOnDevice) {
                    attempt()
                }
                container.chatRepository.append(
                    ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply.text.trim(), recordRefs = reply.recordRefs)
                )
                _ui.value = _ui.value.copy(sending = false)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(sending = false, error = e.message)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.value = _ui.value.copy(
                    sending = false,
                    error = e.localizedMessage,
                    errorRes = if (e.localizedMessage.isNullOrBlank()) R.string.coach_chat_failed else null
                )
            }
        }
    }

    fun resetConversation() {
        modeDecision = null
        providerOverride = null
        _ui.update { it.copy(selectedRecords = emptyList()) }
        viewModelScope.launch { container.chatRepository.clear() }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null, errorRes = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }

    companion object {
        val MAX_SELECTED = RecordsCoach.MAX_SELECTED
    }
}
