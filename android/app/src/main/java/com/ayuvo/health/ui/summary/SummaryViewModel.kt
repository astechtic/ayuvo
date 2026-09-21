package com.ayuvo.health.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.AddMenuConfig
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.records.data.HighlightWithRecord
import com.ayuvo.health.services.WeightAnalysisService
import com.ayuvo.health.ui.fasting.FastingActions
import com.ayuvo.health.ui.health.healthUnitPrefsFlow
import com.ayuvo.health.ui.metrics.MetricTileBuilder
import com.ayuvo.health.ui.metrics.MetricTileUi
import com.ayuvo.health.ui.metrics.MetricUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** One Summary highlight, only ever built from real data (docs/ui-structure.md §8.5). */
sealed interface SummaryHighlight {
    data class Record(val item: HighlightWithRecord) : SummaryHighlight
    /** Observed weekly change from the weight regression; only when the analysis has enough data. */
    data class WeightTrend(val weeklyChangeKg: Double, val weighIns: Int) : SummaryHighlight
    data class LatestWorkout(val session: WorkoutSession) : SummaryHighlight
}

enum class ChecklistItem { CONNECT_HEALTH, REMINDERS, ADD_RECORD, ADD_MEDICATIONS }

data class SummaryUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val profile: UserProfile? = null,
    val caloriesToday: Double = 0.0,
    val stepsToday: Int? = null,
    val stepSource: Boolean = false,
    val stepGoal: Int = 10_000,
    val waterTracking: Boolean = false,
    val waterTodayMl: Int = 0,
    val waterGoalMl: Int = 2_000,
    val waterUnit: WaterUnit = WaterUnit.Default,
    val weightMetric: Boolean = true,
    val fastingTracking: Boolean = false,
    val fastingDefaultGoalMinutes: Int = 16 * 60,
    val activeFast: FastingSession? = null,
    val workoutsToday: List<WorkoutSession> = emptyList(),
    val medications: TodayTimeline? = null,
    val favourites: List<MetricTileUi> = emptyList(),
    val favouritesLoaded: Boolean = false,
    val highlights: List<SummaryHighlight> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val checklistDismissed: Boolean = true,
    val addMenu: AddMenuConfig = AddMenuConfig.Default,
    val latestWeightKg: Double? = null,
    val latestBodyFatFraction: Double? = null
) {
    val rings: List<SummaryRing>
        get() = SummaryRings.build(
            SummaryRingInputs(
                caloriesToday = caloriesToday,
                calorieGoal = profile?.effectiveCalories?.toDouble(),
                stepsToday = stepsToday?.toDouble(),
                stepGoal = stepGoal.toDouble(),
                stepSource = stepSource,
                waterTodayMl = waterTodayMl.toDouble(),
                waterGoalMl = waterGoalMl.toDouble(),
                waterTracking = waterTracking
            )
        )

    /** Medications card only while an active or paused medication exists (§8.3). */
    val showMedications: Boolean
        get() = medications?.medications?.values?.any { it.status == MedicationStatus.ACTIVE || it.status == MedicationStatus.PAUSED } == true
}

/**
 * Summary tab state (docs/ui-structure.md §8). Every value comes from the stores; missing data
 * stays null / empty so the screen hides the card instead of inventing numbers.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SummaryViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SummaryUiState())
    val ui: StateFlow<SummaryUiState> = _ui.asStateFlow()
    private val refreshTick = MutableStateFlow(0)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val fasting = FastingActions(container)

    private inline fun update(block: (SummaryUiState) -> SummaryUiState) {
        _ui.value = block(_ui.value)
    }

    init {
        val prefs = container.prefs

        combine(container.foodRepository.entries, container.profileRepository.profile, refreshTick) { entries, profile, _ ->
            val today = LocalDate.now(zone)
            profile to entries.filter { it.timestamp.atZone(zone).toLocalDate() == today }.sumOf { it.calories }.toDouble()
        }.onEach { (profile, kcal) -> update { it.copy(loaded = true, today = LocalDate.now(zone), profile = profile, caloriesToday = kcal) } }
            .launchIn(viewModelScope)

        combine(container.waterRepository.entries, prefs.waterTrackingEnabled, prefs.waterDailyGoalMl, prefs.waterUnit, refreshTick) { entries, on, goal, unit, _ ->
            WaterInputs(todayMl(entries), on, goal, unit)
        }.onEach { w -> update { it.copy(waterTodayMl = w.todayMl, waterTracking = w.enabled, waterGoalMl = w.goal, waterUnit = w.unit) } }
            .launchIn(viewModelScope)

        combine(prefs.dailyStepGoal, prefs.weightUnit, prefs.addMenuConfig, prefs.fastingDefaultGoalMinutes) { goal, weightUnit, menu, fastGoal ->
            { s: SummaryUiState -> s.copy(stepGoal = goal, weightMetric = weightUnit != "lbs", addMenu = menu, fastingDefaultGoalMinutes = fastGoal) }
        }.onEach { f -> update(f) }.launchIn(viewModelScope)

        combine(prefs.fastingTrackingEnabled, container.fastingRepository.sessions) { on, sessions ->
            on to sessions.lastOrNull { it.isActive }
        }.onEach { (on, active) -> update { it.copy(fastingTracking = on, activeFast = active) } }
            .launchIn(viewModelScope)

        combine(container.workoutRepository.completedSessions, refreshTick) { sessions, _ ->
            val today = LocalDate.now(zone)
            sessions.filter { MetricsReference.workoutDay(it.diaryDateKey, it.startedAt.toEpochMilli(), zone) == today }
        }.onEach { list -> update { it.copy(workoutsToday = list) } }.launchIn(viewModelScope)

        combine(container.weightRepository.entries, container.bodyFatRepository.entries) { w, b ->
            w.maxByOrNull { it.date }?.weightKg to b.maxByOrNull { it.date }?.bodyFatFraction
        }.onEach { (w, b) -> update { it.copy(latestWeightKg = w, latestBodyFatFraction = b) } }.launchIn(viewModelScope)

        // Move ring: today's steps from Health Connect; no source → the connect state.
        combine(prefs.healthConnectEnabled, refreshTick) { enabled, _ -> enabled }
            .mapLatest { enabled ->
                val readable = enabled && runCatching { container.health.hasStepsRead() }.getOrDefault(false)
                readable to if (readable) runCatching { container.health.readStepsForDay(LocalDate.now(zone)) }.getOrNull() else null
            }
            .onEach { (source, steps) -> update { it.copy(stepSource = source, stepsToday = steps) } }
            .launchIn(viewModelScope)

        // Favourites: app tiles from the shared snapshot, health tiles from the mirror.
        val units = combine(prefs.weightUnit, prefs.waterUnit) { w, water -> MetricUnits(weightMetric = w != "lbs", waterUnit = water) }
        val hidden = combine(prefs.waterTrackingEnabled, prefs.fastingTrackingEnabled) { water, fast ->
            buildSet {
                if (!water) add(AppMetricId.WATER)
                if (!fast) add(AppMetricId.FASTING)
            }
        }
        combine(
            combine(container.favoritePins.keys, container.appMetrics.revision, prefs.healthHubEnabled) { k, _, hub -> k to hub },
            container.healthRepository.revision.debounce(REVISION_DEBOUNCE_MS).onStart { emit(0L) },
            units,
            combine(hidden, healthUnitPrefsFlow(prefs)) { h, hu -> h to hu },
            _ui.map { it.stepsToday }.distinctUntilChanged()
        ) { (keys, hub), _, u, (h, hu), steps -> FavouriteInputs(keys, hub, u, h, hu, steps) }
            .mapLatest { i ->
                val today = LocalDate.now(zone)
                MetricTileBuilder.build(
                    keys = i.keys,
                    snapshot = container.appMetrics.snapshot(),
                    health = container.healthRepository,
                    hubEnabled = i.hub,
                    today = today,
                    nowMs = System.currentTimeMillis(),
                    healthUnits = i.healthUnits,
                    units = i.units,
                    liveStepsToday = i.steps,
                    hidden = i.hidden,
                    zone = zone
                )
            }
            .flowOn(Dispatchers.Default)
            .onEach { tiles -> update { it.copy(favourites = tiles, favouritesLoaded = true) } }
            .launchIn(viewModelScope)

        // Highlights (≤ 3): newest important record highlight, weight trend, latest workout.
        combine(
            container.recordsStore.revision,
            container.weightRepository.entries,
            container.foodRepository.entries,
            container.profileRepository.profile,
            container.workoutRepository.completedSessions
        ) { _, weights, foods, profile, workouts -> HighlightInputs(weights, foods, profile, workouts) }
            .debounce(REVISION_DEBOUNCE_MS)
            .mapLatest { i -> buildHighlights(i) }
            .flowOn(Dispatchers.Default)
            .onEach { h -> update { it.copy(highlights = h) } }
            .launchIn(viewModelScope)

        // Checklist and medications.
        combine(
            combine(prefs.healthHubEnabled, prefs.healthConnectEnabled) { hub, hc -> hub || hc },
            prefs.notificationsEnabled,
            prefs.summaryChecklistDismissed,
            container.recordsStore.revision,
            refreshTick
        ) { health, notifications, dismissed, _, _ -> Triple(health, notifications, dismissed) }
            .mapLatest { (health, notifications, dismissed) ->
                val medsExist = container.medicationsDatabaseExists()
                val timeline = if (medsExist) runCatching { container.medicationsStore.today(System.currentTimeMillis(), zone.id) }.getOrNull() else null
                val anyMedication = medsExist && runCatching { container.medicationsStore.countByStatus().values.sum() > 0 }.getOrDefault(false)
                val anyRecord = runCatching { container.recordsStore.count() > 0 }.getOrDefault(false)
                val checklist = buildList {
                    if (!health && runCatching { container.health.isAvailable() }.getOrDefault(false)) add(ChecklistItem.CONNECT_HEALTH)
                    if (!(notifications && container.notifications.canPostNotifications())) add(ChecklistItem.REMINDERS)
                    if (!anyRecord) add(ChecklistItem.ADD_RECORD)
                    if (!anyMedication) add(ChecklistItem.ADD_MEDICATIONS)
                }
                Triple(timeline, checklist, dismissed)
            }
            .onEach { (timeline, checklist, dismissed) -> update { it.copy(medications = timeline, checklist = checklist, checklistDismissed = dismissed) } }
            .launchIn(viewModelScope)

        if (container.medicationsDatabaseExists()) {
            container.medicationsStore.revision
                .onEach { refreshMedications() }
                .launchIn(viewModelScope)
        }
    }

    private suspend fun refreshMedications() {
        val timeline = runCatching { container.medicationsStore.today(System.currentTimeMillis(), zone.id) }.getOrNull()
        update { it.copy(medications = timeline) }
    }

    private fun todayMl(entries: List<WaterEntry>): Int {
        val today = LocalDate.now(zone)
        return entries.filter { it.date.atZone(zone).toLocalDate() == today }.sumOf { it.milliliters }
    }

    private suspend fun buildHighlights(i: HighlightInputs): List<SummaryHighlight> = buildList {
        runCatching { container.recordsStore.importantHighlights(1) }.getOrNull()?.firstOrNull()?.let { add(SummaryHighlight.Record(it)) }
        val profile = i.profile
        if (profile != null && i.weights.size >= 2) {
            val forecast = WeightAnalysisService.compute(i.weights, i.foods, profile)
            val observed = forecast.observedWeeklyChangeKg
            if (forecast.hasEnoughData && observed != null && observed.isFinite()) {
                add(SummaryHighlight.WeightTrend(observed, forecast.weightEntriesUsed))
            }
        }
        i.workouts.maxByOrNull { it.completedAt }?.let { add(SummaryHighlight.LatestWorkout(it)) }
    }.take(3)

    /** Re-read steps, today's totals and medications (on resume and after midnight). */
    fun refresh() {
        refreshTick.value += 1
    }

    fun dismissChecklist() {
        viewModelScope.launch { container.prefs.setSummaryChecklistDismissed(true) }
    }

    fun setFavourites(keys: List<MetricKey>) {
        viewModelScope.launch { container.favoritePins.set(keys) }
    }

    fun addWater(milliliters: Int) {
        if (milliliters <= 0) return
        viewModelScope.launch { container.waterRepository.add(WaterEntry(milliliters = milliliters)) }
    }

    fun startFast(goalMinutes: Int) {
        viewModelScope.launch { fasting.start(goalMinutes) }
    }

    suspend fun favouriteKeys(): List<MetricKey> = container.favoritePins.keys.first()

    private data class WaterInputs(val todayMl: Int, val enabled: Boolean, val goal: Int, val unit: WaterUnit)

    private data class FavouriteInputs(
        val keys: List<MetricKey>,
        val hub: Boolean,
        val units: MetricUnits,
        val hidden: Set<AppMetricId>,
        val healthUnits: com.ayuvo.health.ui.health.HealthUnitPrefs,
        val steps: Int?
    )

    private data class HighlightInputs(
        val weights: List<com.ayuvo.health.models.WeightEntry>,
        val foods: List<com.ayuvo.health.models.FoodEntry>,
        val profile: UserProfile?,
        val workouts: List<WorkoutSession>
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SummaryViewModel(container) as T
    }

    private companion object {
        const val REVISION_DEBOUNCE_MS = 300L
    }
}
