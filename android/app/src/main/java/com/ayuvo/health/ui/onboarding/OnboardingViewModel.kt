package com.ayuvo.health.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.models.ActivityLevel
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.services.ondevice.LocalModelId
import com.ayuvo.health.services.ondevice.LocalModelInstallStatus
import com.ayuvo.health.services.ondevice.LocalModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class OnboardingStep {
    WELCOME, GENDER, BIRTHDAY, HEIGHT_WEIGHT, BODY_FAT,
    ACTIVITY, GOAL, GOAL_WEIGHT, GOAL_SPEED,
    NOTIFICATIONS, HEALTH_CONNECT, PROVIDER,
    BUILDING_PLAN, PLAN_READY
}

/** Steps that still run after "Restore from a backup" (docs/cloud-backup.md › Onboarding restore). */
val RESTORED_STEPS = listOf(OnboardingStep.NOTIFICATIONS, OnboardingStep.HEALTH_CONNECT, OnboardingStep.PROVIDER)

/** Why "Restore from a backup" on the Welcome step did not continue into the restored steps. */
enum class RestoreError { SIGN_IN_FAILED, NO_DRIVE_BACKUP, NO_PROFILE, FAILED }

/** Android onboarding AI step: choice, then key setup (BYOK) or the on-device model (LOCAL). */
enum class OnboardingAiPhase {
    CHOICE,
    BYOK,
    LOCAL
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val gender: Gender = Gender.MALE,
    val birthday: LocalDate = LocalDate.now().minusYears(25),
    val heightCm: Int = 175,
    val weightKg: Double = 70.0,
    val bodyFatPercentage: Double? = null,
    /** Optional target body-fat fraction. Only meaningful when bodyFatPercentage
     *  is non-null (i.e. user picked "Yes I know my body fat" + opted into
     *  setting a goal). Display-only — does NOT participate in BMR/TDEE/macro math. */
    val goalBodyFatPercentage: Double? = null,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
    val goal: WeightGoal = WeightGoal.MAINTAIN,
    val goalWeightKg: Double = 70.0,
    /** 0.25 (slow), 0.5 (moderate), 1.0 (fast) kg/week */
    val weeklyChangeKg: Double = 0.5,
    /** iOS defaults onboarding to Imperial; match that. Seeded from the persisted
     *  heightUnit/weightUnit prefs in init, and the single Imperial|Metric toggle
     *  writes both together so they stay coherent during onboarding. */
    val heightMetric: Boolean = false,
    val weightMetric: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val healthConnectEnabled: Boolean = false,
    /** Any Health Data hub read type granted on the Health Connect step. */
    val healthHubEnabled: Boolean = false,
    /** Visible, pre-checked consent toggle on the connect step; persisted only when the hub is granted. */
    val coachHealthDataEnabled: Boolean = true,
    val aiPhase: OnboardingAiPhase = OnboardingAiPhase.CHOICE,
    val aiProvider: AIProvider = AIProvider.GEMINI,
    val aiModel: String = AIProvider.GEMINI.defaultModel,
    val apiKey: String = "",
    /** On-device Gemma eligibility + download state, mirrored from LocalModelManager. */
    val localModel: LocalModelState? = null,
    val submitting: Boolean = false,
    /** Manual overrides applied on the Plan Ready step. Null = use formula default. */
    val customCalories: Int? = null,
    val customProtein: Int? = null,
    val customCarbs: Int? = null,
    val customFat: Int? = null,
    /** Health Records AI mode picked on the AI step; null = the preselection below (§16). */
    val recordsAiModeChoice: com.ayuvo.health.records.model.RecordsAiMode? = null,
    /**
     * A backup was restored from the Welcome step: the profile, goals and settings came from it, so
     * only [RESTORED_STEPS] (notifications, Health Connect, AI setup) run and the profile steps,
     * Building Plan and Plan Ready are skipped.
     */
    val restored: Boolean = false,
    val restoreBusy: Boolean = false,
    val restoreError: RestoreError? = null,
    val restoreErrorDetail: String? = null
) {
    /** Preselected Local for the on-device path, Ask otherwise (docs/health-records.md §16). */
    val recordsAiMode: com.ayuvo.health.records.model.RecordsAiMode
        get() = recordsAiModeChoice ?: if (aiPhase == OnboardingAiPhase.LOCAL) {
            com.ayuvo.health.records.model.RecordsAiMode.LOCAL
        } else {
            com.ayuvo.health.records.model.RecordsAiMode.ASK
        }

    /** PLAN_READY is the final step (the old Rate-app review step was removed). */
    val isLastStep: Boolean get() = step == OnboardingStep.PLAN_READY

    /** After a restore the first remaining step has nothing behind it: the profile steps are skipped. */
    val canGoBack: Boolean
        get() = step != OnboardingStep.WELCOME && step != OnboardingStep.BUILDING_PLAN &&
            !(restored && step == RESTORED_STEPS.first())

    /** Progress bar fraction; a restored onboarding measures itself against [RESTORED_STEPS] only. */
    val progress: Float
        get() = if (restored) {
            (RESTORED_STEPS.indexOf(step) + 1f) / (RESTORED_STEPS.size + 1f)
        } else {
            step.ordinal.toFloat() / (OnboardingStep.values().size - 1).toFloat()
        }

    /** True once BYOK fields satisfy the same gate as pre-choice onboarding. */
    val byokSetupComplete: Boolean
        get() = !aiProvider.requiresApiKey || apiKey.trim().isNotEmpty()

    /** AI is required for goal calculation, so BYOK users must enter an API key before leaving
     *  the provider step (Ollama needs none). All other steps advance freely. */
    val canAdvance: Boolean get() = when (step) {
        OnboardingStep.PROVIDER -> when (aiPhase) {
            OnboardingAiPhase.CHOICE -> false
            OnboardingAiPhase.BYOK -> byokSetupComplete
            OnboardingAiPhase.LOCAL -> localSetupComplete
        }
        else -> true
    }

    /** On-device path needs no key: an eligible device whose model is downloading or installed.
     *  A still-downloading model is selected automatically once the verified download completes. */
    val localSetupComplete: Boolean
        get() = localModel?.eligible == true && (
            localModel.status is LocalModelInstallStatus.Downloading ||
                localModel.status == LocalModelInstallStatus.Installed
            )

    /** When returning to the provider step, reopen the path the user already configured. */
    fun aiPhaseForProviderStep(): OnboardingAiPhase = when {
        aiPhase == OnboardingAiPhase.LOCAL -> OnboardingAiPhase.LOCAL
        byokSetupComplete -> OnboardingAiPhase.BYOK
        else -> OnboardingAiPhase.CHOICE
    }

    fun buildProfile(): UserProfile = UserProfile(
        gender = gender,
        birthday = birthday.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        heightCm = heightCm.toDouble(),
        weightKg = weightKg,
        activityLevel = activity,
        goal = goal,
        bodyFatPercentage = bodyFatPercentage,
        goalBodyFatPercentage = if (bodyFatPercentage != null) goalBodyFatPercentage else null,
        weeklyChangeKg = if (goal == WeightGoal.MAINTAIN) null else weeklyChangeKg,
        goalWeightKg = if (goal == WeightGoal.MAINTAIN) null else goalWeightKg,
        customCalories = customCalories,
        customProtein = customProtein,
        customCarbs = customCarbs,
        customFat = customFat
    )
}

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(OnboardingState())
    val ui: StateFlow<OnboardingState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val heightMetric = container.prefs.heightUnit.first() == "cm"
            val weightMetric = container.prefs.weightUnit.first() == "kg"
            _ui.value = _ui.value.copy(heightMetric = heightMetric, weightMetric = weightMetric)
        }
        viewModelScope.launch {
            container.localModels.states.collect { states ->
                _ui.value = _ui.value.copy(localModel = states[LocalModelId.GEMMA_4_E2B])
            }
        }
    }

    fun setGender(v: Gender) { _ui.value = _ui.value.copy(gender = v) }
    fun setBirthday(v: LocalDate) { _ui.value = _ui.value.copy(birthday = v) }
    fun setHeight(cm: Int) { _ui.value = _ui.value.copy(heightCm = cm) }
    fun setWeight(kg: Double) { _ui.value = _ui.value.copy(weightKg = kg, goalWeightKg = kg) }
    fun setBodyFat(pct: Double?) {
        // Clear the goal alongside the current value so a stale goal doesn't
        // linger when the user backs out of "Yes I know my body fat".
        _ui.value = _ui.value.copy(
            bodyFatPercentage = pct,
            goalBodyFatPercentage = if (pct == null) null else _ui.value.goalBodyFatPercentage
        )
    }
    fun setGoalBodyFat(pct: Double?) { _ui.value = _ui.value.copy(goalBodyFatPercentage = pct) }
    fun setActivity(v: ActivityLevel) { _ui.value = _ui.value.copy(activity = v) }
    fun setGoal(v: WeightGoal) {
        val defaultGoalWeight = when (v) {
            WeightGoal.LOSE -> _ui.value.weightKg - 5
            WeightGoal.GAIN -> _ui.value.weightKg + 5
            WeightGoal.MAINTAIN -> _ui.value.weightKg
        }
        _ui.value = _ui.value.copy(goal = v, goalWeightKg = defaultGoalWeight)
    }
    fun setGoalWeight(v: Double) { _ui.value = _ui.value.copy(goalWeightKg = v) }
    fun setWeeklyChange(v: Double) { _ui.value = _ui.value.copy(weeklyChangeKg = v) }
    fun setNotificationsEnabled(v: Boolean) {
        _ui.value = _ui.value.copy(notificationsEnabled = v)
    }
    fun setHealthConnectEnabled(v: Boolean) {
        _ui.value = _ui.value.copy(healthConnectEnabled = v)
    }
    fun setHealthHubEnabled(v: Boolean) {
        // A hub grant is a valid connection even without the legacy write set.
        _ui.value = _ui.value.copy(healthHubEnabled = v, healthConnectEnabled = _ui.value.healthConnectEnabled || v)
    }
    fun setCoachHealthDataEnabled(v: Boolean) {
        _ui.value = _ui.value.copy(coachHealthDataEnabled = v)
    }
    fun setAiProvider(p: AIProvider) {
        // Update state synchronously so a slow prefs write can't overwrite a key the user typed
        // while the picker was open. Persist in the background for the Building Plan AI call.
        val existing = container.keyStore.apiKey(p) ?: ""
        _ui.value = _ui.value.copy(aiProvider = p, aiModel = p.defaultModel, apiKey = existing)
        viewModelScope.launch {
            container.prefs.setSelectedAIProvider(p)
            container.prefs.setSelectedAIModel(p.defaultModel)
        }
    }
    fun setAiModel(m: String) {
        _ui.value = _ui.value.copy(aiModel = m)
        viewModelScope.launch { container.prefs.setSelectedAIModel(m) }
    }
    fun setApiKey(key: String) {
        _ui.value = _ui.value.copy(apiKey = key)
        // Persist immediately so the in-onboarding AI plan calc can use it.
        viewModelScope.launch {
            container.keyStore.setApiKey(_ui.value.aiProvider, key.trim().takeIf { it.isNotBlank() })
        }
    }

    fun selectByokSetup() {
        // Flip to BYOK immediately so the form and Continue CTA can react; hydrate from prefs/keystore next.
        _ui.value = _ui.value.copy(aiPhase = OnboardingAiPhase.BYOK)
        viewModelScope.launch {
            val provider = container.prefs.selectedAIProvider.first()
            val storedModel = container.prefs.selectedAIModel.first()
            val existing = container.keyStore.apiKey(provider) ?: ""
            val current = _ui.value
            if (current.aiPhase != OnboardingAiPhase.BYOK) return@launch
            _ui.value = current.copy(
                aiProvider = provider,
                aiModel = provider.supportedModelOrDefault(storedModel),
                apiKey = existing.ifEmpty { current.apiKey }
            )
        }
    }
    fun selectLocalSetup() {
        _ui.value = _ui.value.copy(aiPhase = OnboardingAiPhase.LOCAL)
    }

    fun setRecordsAiMode(mode: com.ayuvo.health.records.model.RecordsAiMode) {
        _ui.value = _ui.value.copy(recordsAiModeChoice = mode)
    }

    fun downloadLocalModel() {
        container.localModels.download(LocalModelId.GEMMA_4_E2B)
    }

    /** Cancels an in-flight download (or removes the installed model) through the shared manager. */
    fun removeLocalModel() {
        viewModelScope.launch { container.localModels.delete(LocalModelId.GEMMA_4_E2B) }
    }

    /** The single Imperial|Metric segmented control writes BOTH unit prefs coherently:
     *  Imperial -> ftin + lbs, Metric -> cm + kg. */
    fun setUseMetric(v: Boolean) {
        _ui.value = _ui.value.copy(heightMetric = v, weightMetric = v)
        viewModelScope.launch {
            container.prefs.setHeightUnit(if (v) "cm" else "ftin")
            container.prefs.setWeightUnit(if (v) "kg" else "lbs")
        }
    }

    fun setCustomCalories(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customCalories = v) }
    fun setCustomProtein(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customProtein = v) }
    fun setCustomCarbs(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customCarbs = v) }
    fun setCustomFat(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customFat = v) }

    /** The user hand-tuned the plan — Adaptive Goals then stays off at completion
     *  so its first weekly run can't overwrite their numbers. */
    private var planEdited = false

    /** Building Plan step: compute calorie + macro targets with AI (forecast is null for a new
     *  user). On success seeds the custom targets the Plan Ready screen shows; on failure leaves
     *  them null so the formula values are used. Calls [onDone] either way. */
    fun buildPlanWithAI(onDone: () -> Unit) {
        viewModelScope.launch {
            val state = _ui.value
            val result = runCatching {
                container.foodAnalysis.calculateGoals(state.buildProfile(), heightMetric = state.heightMetric, weightMetric = state.weightMetric)
            }.getOrNull()
            if (result != null) {
                val carbs = maxOf(0, (result.calories - result.protein * 4 - result.fat * 9) / 4)
                _ui.value = _ui.value.copy(
                    customCalories = result.calories,
                    customProtein = result.protein,
                    customFat = result.fat,
                    customCarbs = carbs
                )
            }
            onDone()
        }
    }

    fun next() {
        if (_ui.value.step == OnboardingStep.PLAN_READY) return
        if (_ui.value.step == OnboardingStep.PROVIDER && _ui.value.aiPhase == OnboardingAiPhase.LOCAL) {
            // Selected now when ready; otherwise applied by AyuvoApp when the download completes.
            viewModelScope.launch { container.prefs.selectLocalGemmaOrMarkPending() }
        }
        val nextStep = OnboardingStep.values().getOrNull(_ui.value.step.ordinal + 1) ?: return
        _ui.value = _ui.value.copy(step = nextStep)
    }

    /** The Drive account picker or sign-in was cancelled or failed. */
    fun restoreSignInFailed() {
        _ui.value = _ui.value.copy(restoreBusy = false, restoreError = RestoreError.SIGN_IN_FAILED, restoreErrorDetail = null)
    }

    fun dismissRestoreError() {
        _ui.value = _ui.value.copy(restoreError = null, restoreErrorDetail = null)
    }

    /**
     * Welcome › Restore from a backup › Google Drive, after sign-in: downloads the Drive backup and
     * applies it (the same restore as Settings › Backup & Export › Restore now).
     */
    fun restoreFromDrive() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(restoreBusy = true, restoreError = null, restoreErrorDetail = null)
            container.cloudBackup.refresh()
            if (!container.cloudBackup.ui.value.existingCloudBackup) {
                // Nothing to restore: don't leave a Google session behind that was only for this.
                runCatching { container.cloudBackup.signOut() }
                _ui.value = _ui.value.copy(restoreBusy = false, restoreError = RestoreError.NO_DRIVE_BACKUP)
                return@launch
            }
            container.cloudBackup.restoreNow()
                .onSuccess { adoptRestoredBackup() }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        restoreBusy = false,
                        restoreError = RestoreError.FAILED,
                        restoreErrorDetail = it.localizedMessage
                    )
                }
        }
    }

    /** Import All Data finished on the Welcome step; only a restored app backup skips the profile steps. */
    fun onImportFinished(appBackupRestored: Boolean) {
        if (!appBackupRestored) return
        viewModelScope.launch { adoptRestoredBackup() }
    }

    /**
     * The backup replaced the profile, goals and settings. Re-read what the remaining steps show and
     * jump to them; OS grants, the on-device model and API keys never travel in a backup, so those
     * steps still run. Without a profile in the backup the normal onboarding carries on.
     */
    private suspend fun adoptRestoredBackup() {
        if (container.profileRepository.current() == null) {
            _ui.value = _ui.value.copy(restoreBusy = false, restoreError = RestoreError.NO_PROFILE)
            return
        }
        val prefs = container.prefs
        val provider = prefs.selectedAIProvider.first()
        _ui.value = _ui.value.copy(
            restored = true,
            restoreBusy = false,
            restoreError = null,
            restoreErrorDetail = null,
            step = RESTORED_STEPS.first(),
            aiPhase = OnboardingAiPhase.CHOICE,
            heightMetric = prefs.heightUnit.first() == "cm",
            weightMetric = prefs.weightUnit.first() == "kg",
            aiProvider = provider,
            aiModel = provider.supportedModelOrDefault(prefs.selectedAIModel.first()),
            apiKey = container.keyStore.apiKey(provider) ?: "",
            coachHealthDataEnabled = prefs.coachHealthDataEnabled.first(),
            notificationsEnabled = false,
            healthConnectEnabled = false,
            healthHubEnabled = false
        )
    }

    fun back() {
        if (!_ui.value.canGoBack) return
        if (_ui.value.step == OnboardingStep.PROVIDER && _ui.value.aiPhase != OnboardingAiPhase.CHOICE) {
            _ui.value = _ui.value.copy(aiPhase = OnboardingAiPhase.CHOICE)
            return
        }
        // PLAN_READY's previous ordinal is BUILDING_PLAN, which auto-reruns AI and can
        // overwrite edited targets — skip it and return to PROVIDER instead.
        if (_ui.value.step == OnboardingStep.PLAN_READY) {
            val state = _ui.value
            _ui.value = state.copy(
                step = OnboardingStep.PROVIDER,
                aiPhase = state.aiPhaseForProviderStep()
            )
            return
        }
        val prevStep = OnboardingStep.values().getOrNull(_ui.value.step.ordinal - 1) ?: return
        if (prevStep == OnboardingStep.BUILDING_PLAN) {
            val state = _ui.value
            _ui.value = state.copy(
                step = OnboardingStep.PROVIDER,
                aiPhase = state.aiPhaseForProviderStep()
            )
            return
        }
        _ui.value = _ui.value.copy(
            step = prevStep,
            aiPhase = if (prevStep == OnboardingStep.PROVIDER) {
                _ui.value.aiPhaseForProviderStep()
            } else {
                _ui.value.aiPhase
            }
        )
    }

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(submitting = true)
            val state = _ui.value
            // A restored backup brought the real profile, weight history and goals: keep them.
            if (!state.restored) {
                val profile = state.buildProfile()
                container.profileRepository.save(profile)
                container.weightRepository.seedInitialWeightIfEmpty(profile.weightKg)
                // Only seed body fat when the user actually entered one in onboarding
                // (the "Yes I know my body fat %" branch); the "No" branch leaves
                // bodyFatPercentage null and the store stays empty.
                profile.bodyFatPercentage?.let {
                    container.bodyFatRepository.seedInitialBodyFatIfEmpty(it)
                }
            }
            container.prefs.setNotificationsEnabled(state.notificationsEnabled)
            container.prefs.setHealthConnectEnabled(state.healthConnectEnabled)
            // Second persisted flag: the Health Data hub. Coach access is recorded only through
            // the visible consent toggle the user confirmed with Connect — never a silent flip.
            container.prefs.setHealthHubEnabled(state.healthHubEnabled)
            if (state.healthHubEnabled) {
                container.prefs.setHealthHubPromptedVersion(
                    com.ayuvo.health.services.health.HealthHubPermissions.HUB_PERMISSIONS_VERSION
                )
                container.prefs.setCoachHealthDataEnabled(state.coachHealthDataEnabled)
                container.prefs.setCoachHealthDataConsentedAt(
                    if (state.coachHealthDataEnabled) java.time.Instant.now().toString() else null
                )
            }
            // A restored backup already carries the speech provider choice; only new installs get
            // the default that matches the AI provider.
            if (state.aiPhase == OnboardingAiPhase.LOCAL) {
                container.prefs.selectLocalGemmaOrMarkPending()
                if (!state.restored) container.prefs.setInitialSpeechProviderForAIProvider(AIProvider.LOCAL_GEMMA)
            } else {
                container.prefs.setSelectedAIProvider(state.aiProvider)
                container.prefs.setSelectedAIModel(state.aiModel)
                if (state.apiKey.isNotBlank()) {
                    container.keyStore.setApiKey(state.aiProvider, state.apiKey.trim())
                }
                if (!state.restored) container.prefs.setInitialSpeechProviderForAIProvider(state.aiProvider)
            }
            // New installs start with Energy Burn on, and Adaptive Goals on unless the
            // user hand-tuned their plan (adaptive would overwrite it). Existing users are
            // untouched — these prefs are only written here and by the Settings toggles.
            // Onboarding just calculated goals, so stamp the weekly adaptive check as done;
            // the first auto-run lands next week.
            // Never overrides a mode chosen earlier (e.g. a re-run onboarding after restore).
            container.prefs.setHealthRecordsAiModeIfUnset(state.recordsAiMode.raw)
            // A restored backup keeps its own Adaptive Goals / Energy Burn settings and check day.
            if (!state.restored) {
                container.prefs.setAdaptiveGoalsEnabled(!planEdited)
                container.prefs.setHealthEnergyGoalsEnabled(true)
                container.prefs.setAdaptiveGoalsLastCheckDay(LocalDate.now().toString())
            }
            container.prefs.setOnboardingCompleted(true)
            if (state.healthHubEnabled) {
                container.requestHealthSync(
                    com.ayuvo.health.services.health.HealthSyncTrigger.PERMISSIONS_CHANGED
                )
            }
            onDone()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnboardingViewModel(container) as T
    }
}
