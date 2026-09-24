package com.ayuvo.health.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.coach.data.CoachFileStore
import com.ayuvo.health.coach.data.CoachMigration
import com.ayuvo.health.coach.data.CoachRepository
import com.ayuvo.health.coach.logic.CoachCatalogs
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.CoachMessage
import com.ayuvo.health.coach.model.Conversation
import com.ayuvo.health.coach.model.ConversationSummary
import com.ayuvo.health.coach.model.CoachDataSwitches
import com.ayuvo.health.coach.model.CoachExportFile
import com.ayuvo.health.coach.model.CoachExportFormat
import com.ayuvo.health.coach.model.CoachSource
import com.ayuvo.health.coach.processing.CoachAttachmentComposer
import com.ayuvo.health.medications.logic.ArchiveCodec
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.ayuvo.health.coach.processing.CoachAttachmentProcessor
import com.ayuvo.health.medications.coach.CoachMedicationsContext
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
    val messages: List<CoachMessage> = emptyList(),
    /** Replies that have more than one version, so the `‹ 1/2 ›` stepper only appears where it means something (§10). */
    val variantsBySeq: Map<Int, List<CoachMessage>> = emptyMap(),
    val conversations: List<ConversationSummary> = emptyList(),
    val currentConversationId: String? = null,
    /** Bubble thumbnails by attachment id, resolved from the attachment store. */
    val attachmentImages: Map<String, android.graphics.Bitmap> = emptyMap(),
    /** Documents attached to the turn being composed, not yet sent (docs/coach.md §6). */
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val processingAttachment: Boolean = false,
    /** Per-source state for the composer sheet (docs/coach.md §8). */
    val sourceStates: Map<CoachSource, CoachSourceState> = emptyMap(),
    val dataSwitches: CoachDataSwitches = CoachDataSwitches.ALL_ON,
    /** Shown before Coach may read the user's medicines for the first time. */
    val medicationsConsent: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val errorRes: Int? = null,
    /** Gallery ids (docs/coach.md §9); the text comes from `PromptGalleryText`. */
    val suggestions: List<String> = emptyList(),
    /** Settings › AI › Coach › Suggested prompts. Off hides the chips and the empty-state grid (§9). */
    val suggestionsVisible: Boolean = true,
    // Health Records (§26–§30)
    val recordsAccessEnabled: Boolean = false,
    val selectedRecords: List<HealthRecord> = emptyList(),
    val recordChips: List<CoachRecordsChip> = emptyList(),
    val consent: CoachRecordsConsent? = null,
    val modePrompt: CoachRecordsModePrompt? = null,
    val prefill: CoachPrefill? = null,
    val pickerOpen: Boolean = false,
    val pickerCandidates: List<HealthRecord> = emptyList(),
    /** The model this conversation is pinned to, if any (docs/ai-models.md 8). */
    val modelOverrideProfileId: String? = null,
    val modelOverrideProvider: AIProvider? = null,
    /** The saved models the picker offers. */
    val aiProfiles: List<CoachModelChoice> = emptyList()
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
    /**
     * This conversation's model choice, as `conversations.provider_override` holds it
     * (docs/ai-models.md §8). It used to live only here and was never written back, so an on-device
     * conversation silently reverted to the cloud provider after a switch or process death.
     */
    private var modelOverride: ConversationOverride = ConversationOverride()
    private val providerOverride: AIProvider? get() = modelOverride.provider
    private var modeAnswer: CompletableDeferred<CoachRecordsModeChoice>? = null

    init {
        viewModelScope.launch {
            // The saved models the ⋯ › Model picker offers (docs/ai-models.md §8).
            container.prefs.aiModelProfiles.collect { profiles ->
                val choices = profiles.filterIsInstance<JsonObject>().map { p ->
                    val token = p.str("provider").orEmpty()
                    CoachModelChoice(
                        id = p.str("id").orEmpty(),
                        name = p.str("nickname").orEmpty().ifEmpty { token },
                        providerToken = token,
                        provider = AIProvider.fromToken(token),
                        model = p.str("model_id").orEmpty(),
                    )
                }
                _ui.update { it.copy(aiProfiles = choices) }
            }
        }
        viewModelScope.launch {
            // Conversations live in ayuvo_coach.db now; the legacy blob moves in once (docs §12).
            CoachMigration.runIfNeeded(container.coachRepository, container.prefs)
            runCatching { container.coachRepository.purgeUnreferencedAttachments() }
            reloadConversations()
            container.coachRepository.mostRecentConversationId()?.let { selectConversation(it) }
        }
        refreshSourceStates()

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
            chipGoal = profile?.goal?.name?.lowercase()
            chipHasWorkouts = sessions.isNotEmpty()
            chipHasSleep = sleep
        }
            .onEach { refreshChips() }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            container.prefs.coachPromptSuggestions.collectLatest { visible ->
                _ui.update { it.copy(suggestionsVisible = visible) }
            }
        }

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

    // -- Prompt chips and gallery (docs/coach.md §9) ----------------------------------------------

    /** The inputs the shared chip selection takes, kept so any one of them can refresh the row. */
    private var chipGoal: String? = null
    private var chipHasWorkouts = false
    private var chipHasSleep = false

    /**
     * The chips above the composer come from the same catalog as the gallery, gated by the same
     * rule, so a chip can never offer what the gallery hides.
     */
    private fun refreshChips() {
        val states = _ui.value.sourceStates
        val sources = MedicationJson.obj(
            *CoachSource.entries.map { it.raw to (states[it] == CoachSourceState.ON) }.toTypedArray()
        )
        val ids = MedicationJson.strings(
            CoachReference.chipsFor(
                chipGoal, chipHasWorkouts, chipHasSleep, sources, CoachCatalogs.promptGallery
            )["ids"]
        )
        _ui.update { it.copy(suggestions = ids) }
    }

    /** The gallery, grouped by category, for the sources this device can actually answer from. */
    fun galleryGroups(): List<Pair<String, List<String>>> {
        val states = _ui.value.sourceStates
        val sources = MedicationJson.obj(
            *CoachSource.entries.map { it.raw to (states[it] == CoachSourceState.ON) }.toTypedArray()
        )
        val result = CoachReference.galleryFor(sources, CoachCatalogs.promptGallery)
        return (result["categories"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { group ->
                val name = group.str("category") ?: return@mapNotNull null
                name to MedicationJson.strings(group["ids"])
            }
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
                setModelOverride(ConversationOverride(provider = AIProvider.LOCAL_GEMMA))
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
            val conversation = ensureConversation() ?: return@launch
            val documents = _ui.value.pendingAttachments
            _ui.update { it.copy(pendingAttachments = emptyList()) }
            val attachmentIds = storeImageAttachment(thumbnailBytes) + documents.map { it.id }
            appendMessage(
                CoachMessage(
                    conversationId = conversation.id,
                    seq = container.coachRepository.nextSeq(conversation.id),
                    role = CoachMessage.Role.USER,
                    content = text,
                    createdMs = System.currentTimeMillis(),
                    attachmentIds = attachmentIds
                )
            )
            // The excerpts are already redacted and are exactly what the excerpt sheet showed (§6).
            val textForAi = CoachAttachmentComposer.messageWithAttachments(text, documents)
            _ui.value = _ui.value.copy(sending = true, error = null, errorRes = null)
            try {
                // Exclude the just-appended user message; it is passed separately.
                val history = _ui.value.messages.takeLast(MAX_CONTEXT_MESSAGES).dropLast(1)
                val reply = requestReply(history, textForAi, text, imageBytes)
                val current = _ui.value.currentConversationId
                if (current != null) {
                    appendMessage(
                        CoachMessage(
                            conversationId = current,
                            seq = container.coachRepository.nextSeq(current),
                            role = CoachMessage.Role.ASSISTANT,
                            content = reply.text.trim(),
                            createdMs = System.currentTimeMillis(),
                            recordRefs = reply.recordRefs
                        )
                    )
                }
                _ui.value = _ui.value.copy(sending = false)
            } catch (e: CoachNoProfile) {
                _ui.value = _ui.value.copy(sending = false, errorRes = R.string.coach_no_profile_error)
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


    /** Raised when there is no profile yet; the turn cannot be built without one. */
    private class CoachNoProfile : Exception()

    /**
     * One request to the provider, built from everything Coach is allowed to read this turn. Shared
     * by "Send" and by §10 "Regenerate", so a regenerated answer sees exactly what the first one did.
     *
     * [plainText] is the user's own words (what the records turn is matched against); [textForAi] is
     * the same message with the redacted attachment excerpts appended.
     */
    private suspend fun requestReply(
        history: List<CoachMessage>,
        textForAi: String,
        plainText: String,
        imageBytes: ByteArray?
    ): ChatService.CoachReply {
        val profile = container.profileRepository.current() ?: throw CoachNoProfile()
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
                newUserMessage = textForAi,
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
                records = runCatching { recordsTurn(plainText, provider) }.getOrNull(),
                medications = medicationsContext(),
                sources = _ui.value.dataSwitches,
                providerOverride = providerOverride,
                profileOverride = modelOverride.profileId
            )
        }

        return try {
            attempt()
        } catch (switch: RecordsCoachSwitchToOnDevice) {
            attempt()
        }
    }

    // -- Attachments and data sources (docs/coach.md §3, §6, §8) ----------------------------------

    /** Built per turn; null when the user has no medicines or has not turned the source on. */
    private suspend fun medicationsContext(): CoachMedicationsContext? {
        if (!container.prefs.coachMedicationsEnabled.first()) return null
        // The same three tables the archive uses (docs/medications.md §20).
        val snapshot = runCatching {
            ArchiveCodec.snapshotJson(container.medicationsStore.exportSnapshot())
        }.getOrNull() ?: return null
        val rows = (snapshot["medications"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (rows.isEmpty()) return null
        return CoachMedicationsContext(
            snapshot = snapshot,
            timeZone = java.time.ZoneId.systemDefault().id,
            nowMs = System.currentTimeMillis(),
            count = rows.size,
            activeCount = rows.count { row -> (row["status"] as? JsonPrimitive)?.content == "active" }
        )
    }

    /** What the composer sheet shows per source: on, off here, not connected, or nothing to read. */
    fun refreshSourceStates() {
        viewModelScope.launch {
            val switches = _ui.value.dataSwitches
            fun state(available: Boolean, consented: Boolean, source: CoachSource): CoachSourceState = when {
                !available -> CoachSourceState.UNAVAILABLE
                !consented -> CoachSourceState.NOT_CONNECTED
                switches.isOn(source) -> CoachSourceState.ON
                else -> CoachSourceState.OFF
            }
            val hubOn = container.prefs.healthHubEnabled.first()
            val healthConsent = container.prefs.coachHealthDataEnabled.first()
            val medsConsent = container.prefs.coachMedicationsEnabled.first()
            val hasMeds = runCatching {
                container.medicationsStore.countByStatus().values.sum() > 0
            }.getOrDefault(false)
            val recordsConsent = container.prefs.healthRecordsCoachAccessEnabled.first()
            val states = mapOf(
                CoachSource.FOOD to if (switches.isOn(CoachSource.FOOD)) CoachSourceState.ON else CoachSourceState.OFF,
                CoachSource.HEALTH to state(hubOn, healthConsent, CoachSource.HEALTH),
                CoachSource.MEDICATIONS to state(hasMeds, medsConsent, CoachSource.MEDICATIONS),
                CoachSource.RECORDS to state(true, recordsConsent, CoachSource.RECORDS)
            )
            _ui.update { it.copy(sourceStates = states) }
            refreshChips()
        }
    }

    fun setSource(source: CoachSource, on: Boolean) {
        _ui.update { it.copy(dataSwitches = it.dataSwitches.with(source, on)) }
        viewModelScope.launch {
            _ui.value.currentConversationId?.let { id ->
                container.coachRepository.conversation(id)?.let { conversation ->
                    container.coachRepository.updateConversation(
                        conversation.copy(dataSources = _ui.value.dataSwitches),
                        System.currentTimeMillis()
                    )
                }
            }
            refreshSourceStates()
        }
    }

    /** "Connect" never flips the switch itself — it opens the consent that owns that decision. */
    fun connectSource(source: CoachSource) {
        when (source) {
            CoachSource.MEDICATIONS -> _ui.update { it.copy(medicationsConsent = true) }
            // The records consent sheet is already driven by the existing §26 flow.
            CoachSource.RECORDS -> viewModelScope.launch {
                _ui.update { it.copy(consent = consentPrompt()) }
            }
            // Health sync lives in Settings; Coach cannot grant it.
            CoachSource.HEALTH, CoachSource.FOOD -> Unit
        }
    }

    fun answerMedicationsConsent(allow: Boolean) {
        _ui.update { it.copy(medicationsConsent = false) }
        if (!allow) return
        viewModelScope.launch {
            container.prefs.setCoachMedicationsEnabled(true)
            setSource(CoachSource.MEDICATIONS, true)
        }
    }

    /** Documents are read and redacted on this device; only the excerpt is ever sent (§6). */
    fun attachDocuments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(processingAttachment = true) }
            val processor = CoachAttachmentProcessor(
                container.appContext, container.coachRepository.files, container.coachOcrEngine
            )
            for (uri in uris.take(MAX_DOCUMENTS)) {
                try {
                    val outcome = processor.process(uri)
                    container.coachRepository.insertAttachment(outcome.attachment)
                    _ui.update { it.copy(pendingAttachments = it.pendingAttachments + outcome.attachment) }
                    if (outcome.isEmpty) {
                        _ui.update { it.copy(errorRes = R.string.coach_file_unreadable) }
                    }
                } catch (failure: CoachAttachmentProcessor.Failure) {
                    _ui.update { it.copy(errorRes = errorResFor(failure)) }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _ui.update { it.copy(errorRes = R.string.coach_file_unreadable) }
                }
            }
            _ui.update { it.copy(processingAttachment = false) }
        }
    }

    fun attachNote(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val processor = CoachAttachmentProcessor(
                container.appContext, container.coachRepository.files, container.coachOcrEngine
            )
            val outcome = processor.processNote(text.trim())
            container.coachRepository.insertAttachment(outcome.attachment)
            _ui.update { it.copy(pendingAttachments = it.pendingAttachments + outcome.attachment) }
        }
    }

    fun removePendingAttachment(id: String) {
        _ui.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id }) }
    }

    private fun errorResFor(failure: CoachAttachmentProcessor.Failure): Int = when (failure) {
        CoachAttachmentProcessor.Failure.TooLarge -> R.string.coach_file_too_large
        CoachAttachmentProcessor.Failure.Unsupported -> R.string.coach_file_unsupported
        else -> R.string.coach_file_unreadable
    }


    // -- Message actions (docs/coach.md §10) -------------------------------------------------------

    /**
     * Which replies have more than one version. Read off the transcript rather than per row, so the
     * list stays cheap.
     */
    private suspend fun loadVariants() {
        val id = _ui.value.currentConversationId
        if (id == null) {
            _ui.update { it.copy(variantsBySeq = emptyMap()) }
            return
        }
        val rows = runCatching { container.coachRepository.allMessages(id) }.getOrDefault(emptyList())
        val grouped = rows
            .filter { it.role == CoachMessage.Role.ASSISTANT }
            .groupBy { it.seq }
            .filterValues { it.size > 1 }
        _ui.update { it.copy(variantsBySeq = grouped) }
    }

    /** Which version of a reply the transcript shows; the stepper picks, nothing is deleted. */
    fun showVariant(message: CoachMessage) {
        _ui.update { state ->
            val index = state.messages.indexOfFirst { it.seq == message.seq }
            if (index < 0) state
            else state.copy(messages = state.messages.toMutableList().also { it[index] = message })
        }
    }

    /**
     * §10 "Regenerate": re-sends the user turn this reply answered, with the same attachments,
     * records and data switches. The old answer is kept — it becomes version 1 of 2.
     */
    fun regenerate(message: CoachMessage) {
        if (_ui.value.sending) return
        viewModelScope.launch {
            val conversationId = _ui.value.currentConversationId ?: return@launch
            val repository = container.coachRepository
            val all = repository.allMessages(conversationId)
            val plan = CoachReference.regeneratePlan(all.map { m: CoachMessage -> CoachRepository.referenceRow(m) }, message.seq)
            if (!plan.truthy("ok")) return@launch
            val promptId = plan.str("prompt_id") ?: return@launch
            val prompt = all.firstOrNull { it.id == promptId } ?: return@launch

            _ui.value = _ui.value.copy(sending = true, error = null, errorRes = null)
            try {
                // The model gets the same view it had: everything before the prompt, and the prompt
                // itself as the new user message.
                val history = CoachRepository.latestVariants(all.filter { it.seq < prompt.seq })
                    .takeLast(MAX_CONTEXT_MESSAGES)
                val documents = repository.attachments(prompt.attachmentIds)
                    .filter { it.kind != AttachmentKind.IMAGE }
                val textForAi = CoachAttachmentComposer.messageWithAttachments(prompt.content, documents)
                val reply = requestReply(history, textForAi, prompt.content, null)
                val variant = CoachMessage(
                    conversationId = conversationId,
                    seq = plan.int("seq") ?: message.seq,
                    variantIndex = plan.int("variant_index") ?: (message.variantIndex + 1),
                    role = CoachMessage.Role.ASSISTANT,
                    content = reply.text.trim(),
                    createdMs = System.currentTimeMillis(),
                    regeneratedFrom = plan.str("regenerated_from"),
                    recordRefs = reply.recordRefs
                )
                repository.appendMessage(variant)
                _ui.update { state ->
                    val index = state.messages.indexOfFirst { it.seq == variant.seq }
                    val messages =
                        if (index >= 0) state.messages.toMutableList().also { it[index] = variant }
                        else state.messages + variant
                    state.copy(messages = messages, sending = false)
                }
                loadVariants()
                reloadConversations()
            } catch (e: CoachNoProfile) {
                _ui.value = _ui.value.copy(sending = false, errorRes = R.string.coach_no_profile_error)
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

    /**
     * §10 "Export as Markdown" / "Export as JSON". The formats themselves come from the shared
     * reference, so an exported chat reads the same on both platforms.
     */
    suspend fun exportConversation(id: String, format: CoachExportFormat): CoachExportFile? {
        val repository = container.coachRepository
        val conversation = repository.conversation(id) ?: return null
        val messages = repository.allMessages(id)
        val attachments = repository.attachments(messages.flatMap { it.attachmentIds }.distinct())
        val day = localDay(conversation.lastMessageMs ?: conversation.updatedMs)
        val conversationRow = CoachRepository.referenceRow(conversation)
        val messageRows = messages.map { m: CoachMessage -> CoachRepository.referenceRow(m) }
        val attachmentRows = attachments.map { a: ChatAttachment -> CoachRepository.referenceRow(a) }
        return when (format) {
            CoachExportFormat.MARKDOWN -> {
                val result = CoachReference.conversationMarkdown(
                    conversationRow, messageRows, attachmentRows, day, providerName()
                )
                val text = result.str("text") ?: return null
                CoachExportFile(
                    result.str("filename") ?: "chat-$day.md",
                    text.toByteArray(Charsets.UTF_8),
                    format.mimeType
                )
            }
            CoachExportFormat.JSON -> {
                val result = CoachReference.conversationJson(
                    conversationRow, messageRows, attachmentRows, day
                )
                val archive = result["archive"] ?: return null
                CoachExportFile(
                    result.str("filename") ?: "chat-$day.json",
                    EXPORT_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), archive)
                        .toByteArray(Charsets.UTF_8),
                    format.mimeType
                )
            }
        }
    }

    /** The provider named in the exported transcript's header; absent rather than wrong. */
    private suspend fun providerName(): String? = runCatching {
        val provider = providerOverride ?: container.chatService.coachProvider(false)
        container.appContext.getString(provider.displayNameRes)
    }.getOrNull()

    // -- Conversations (docs/coach.md §7) ----------------------------------------------------------

    /**
     * Pins this conversation to a saved model (docs/ai-models.md §8), persisting it so a switch or a
     * process restart does not lose the choice.
     */
    fun setModelOverride(override: ConversationOverride) {
        modelOverride = override
        _ui.update {
            it.copy(modelOverrideProfileId = override.profileId,
                    modelOverrideProvider = override.provider)
        }
        val id = _ui.value.currentConversationId ?: return
        viewModelScope.launch {
            container.coachRepository.setProviderOverride(id, override.encoded(), System.currentTimeMillis())
        }
    }

    /** "Use for new chats too": the picked model becomes the app's primary. */
    fun setModelAsDefault(profileId: String) {
        viewModelScope.launch {
            container.prefs.setAiRolePointer("image", profileId, enabled = true)
        }
    }

    /** "New chat" adds a conversation; it never clears one. */
    fun newConversation() {
        modeDecision = null
        modelOverride = ConversationOverride()
        _ui.update { it.copy(selectedRecords = emptyList()) }
        viewModelScope.launch {
            val conversation = Conversation(createdMs = System.currentTimeMillis())
            container.coachRepository.createConversation(conversation)
            _ui.update { it.copy(messages = emptyList(), currentConversationId = conversation.id) }
            reloadConversations()
        }
    }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            val conversation = container.coachRepository.conversation(id) ?: return@launch
            val messages = container.coachRepository.messages(id)
            // Rehydrate the model this chat is pinned to; without this it silently reverted.
            modelOverride = ConversationOverride.parse(conversation.providerOverride)
            modeDecision = null
            _ui.update {
                it.copy(messages = messages, currentConversationId = conversation.id,
                        selectedRecords = emptyList(),
                        modelOverrideProfileId = modelOverride.profileId,
                        modelOverrideProvider = modelOverride.provider)
            }
            loadAttachmentImages(messages)
            loadVariants()
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            container.coachRepository.renameConversation(id, CoachReference.collapseWs(title), System.currentTimeMillis())
            reloadConversations()
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            container.coachRepository.deleteConversation(id, System.currentTimeMillis())
            if (_ui.value.currentConversationId == id) {
                _ui.update { it.copy(messages = emptyList(), currentConversationId = null) }
            }
            reloadConversations()
            if (_ui.value.currentConversationId == null) {
                _ui.value.conversations.firstOrNull()?.let { selectConversation(it.conversation.id) }
            }
        }
    }

    /** "Start again from this chat": the settings, none of the messages. */
    fun duplicateConversation(id: String) {
        viewModelScope.launch {
            val copy = container.coachRepository.duplicateConversation(id, System.currentTimeMillis()) ?: return@launch
            _ui.update { it.copy(messages = emptyList(), currentConversationId = copy.id) }
            reloadConversations()
        }
    }

    fun setConversationPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            container.coachRepository.setPinned(id, pinned, System.currentTimeMillis())
            reloadConversations()
        }
    }

    suspend fun searchConversations(query: String): List<ConversationSummary> =
        if (query.isBlank()) container.coachRepository.conversationSummaries()
        else container.coachRepository.search(query)

    private suspend fun reloadConversations() {
        // Resolve before update(): StateFlow.update takes a plain lambda, not a suspending one.
        val summaries = container.coachRepository.conversationSummaries()
        _ui.update { it.copy(conversations = summaries) }
    }

    private suspend fun ensureConversation(): Conversation? {
        _ui.value.currentConversationId?.let { id ->
            container.coachRepository.conversation(id)?.let { return it }
        }
        val conversation = Conversation(createdMs = System.currentTimeMillis())
        container.coachRepository.createConversation(conversation)
        _ui.update { it.copy(currentConversationId = conversation.id) }
        return conversation
    }

    private suspend fun appendMessage(message: CoachMessage) {
        container.coachRepository.appendMessage(message)
        _ui.update { it.copy(messages = it.messages + message) }
        titleIfNeeded(message)
        loadAttachmentImages(listOf(message))
        reloadConversations()
    }

    private suspend fun titleIfNeeded(message: CoachMessage) {
        if (message.role != CoachMessage.Role.USER) return
        val conversation = container.coachRepository.conversation(message.conversationId) ?: return
        if (conversation.title.isNotEmpty()) return
        val title = CoachReference.conversationTitle(message.content).let { obj ->
            (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        }
        if (title.isEmpty()) return
        container.coachRepository.renameConversation(conversation.id, title, System.currentTimeMillis())
    }

    /**
     * Writes the bubble thumbnail to the attachment store (docs/coach.md §6). A failure here costs
     * the picture, never the message, so the turn still sends.
     */
    private suspend fun storeImageAttachment(thumbnailBytes: ByteArray?): List<String> {
        if (thumbnailBytes == null || thumbnailBytes.isEmpty()) return emptyList()
        val files = container.coachRepository.files
        val id = java.util.UUID.randomUUID().toString().lowercase()
        val path = files.writeOriginal(thumbnailBytes, id, "jpg") ?: return emptyList()
        val attachment = ChatAttachment(
            id = id,
            kind = AttachmentKind.IMAGE,
            filename = "photo.jpg",
            mimeType = "image/jpeg",
            bytes = thumbnailBytes.size.toLong(),
            sha256 = CoachFileStore.sha256(thumbnailBytes),
            filePath = path,
            createdMs = System.currentTimeMillis()
        )
        return if (runCatching { container.coachRepository.insertAttachment(attachment) }.isSuccess) {
            listOf(id)
        } else {
            files.delete(id)
            emptyList()
        }
    }

    private suspend fun loadAttachmentImages(messages: List<CoachMessage>) {
        val ids = messages.flatMap { it.attachmentIds }.filter { it !in _ui.value.attachmentImages }
        if (ids.isEmpty()) return
        val files = container.coachRepository.files
        val loaded = container.coachRepository.attachments(ids)
            .filter { it.kind == AttachmentKind.IMAGE }
            .mapNotNull { a -> files.bitmap(a.thumbnailPath ?: a.filePath)?.let { a.id to it } }
        if (loaded.isEmpty()) return
        _ui.update { it.copy(attachmentImages = it.attachmentImages + loaded) }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null, errorRes = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }

    companion object {
        val MAX_SELECTED = RecordsCoach.MAX_SELECTED

        /** Readable on a laptop, and stable enough to diff two exports of the same chat. */
        private val EXPORT_JSON = kotlinx.serialization.json.Json { prettyPrint = true }

        /** The archive's day stamp, in the device's own zone (docs/coach.md §10). */
        fun localDay(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        /** Pictures and documents per turn (docs/coach.md §6). */
        const val MAX_IMAGES = 4
        const val MAX_DOCUMENTS = 3

        /** Cap on what we send to the LLM; the full history is always kept on disk. */
        const val MAX_CONTEXT_MESSAGES = 20
    }
}
