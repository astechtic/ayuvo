package com.ayuvo.health.ui.workouts

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.ExerciseRepository
import com.ayuvo.health.data.ExerciseSort
import com.ayuvo.health.data.WorkoutRepository
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.ExerciseTimerAction
import com.ayuvo.health.models.WorkoutIntensity
import com.ayuvo.health.models.ExerciseLiftDay
import com.ayuvo.health.models.ExerciseLiftHistory
import com.ayuvo.health.models.PlannedExercise
import com.ayuvo.health.models.WorkoutDate
import com.ayuvo.health.models.WorkoutPersistedState
import com.ayuvo.health.models.WorkoutPreferences
import com.ayuvo.health.models.WorkoutSplitGroup
import com.ayuvo.health.models.WorkoutTabMode
import com.ayuvo.health.models.OutdoorActivitySettings
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.services.FoodImageStore
import com.ayuvo.health.ui.home.OutdoorActivityKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

data class WorkoutCopyDayUi(
    val date: LocalDate,
    val exerciseNames: List<String>
)

internal data class WorkoutPickerFilterState(
    val search: String = "",
    val primaryMuscle: String? = null,
    val secondaryMuscle: String? = null,
    val equipment: String? = null,
    val bodyPart: String? = null,
    val sort: ExerciseSort = ExerciseSort.NAME
)

data class WorkoutDiaryUiState(
    val mode: WorkoutTabMode = WorkoutTabMode.Default,
    val selectedDate: LocalDate = LocalDate.now(),
    val exercises: List<PlannedExercise> = emptyList(),
    val workoutCounts: Map<LocalDate, Int> = emptyMap(),
    val caloriesBurned: Int? = null,
    val savedExerciseIds: Set<String> = emptySet(),
    val preferences: WorkoutPreferences = WorkoutPreferences(),
    val splitGroups: List<WorkoutSplitGroup> = emptyList(),
    val copyDays: List<WorkoutCopyDayUi> = emptyList(),
    val weightUnit: WorkoutWeightUnit = WorkoutWeightUnit.LBS,
    val visualGender: Gender = Gender.MALE,
    val isCalculatingBurn: Boolean = false,
    val notice: String? = null
) {
    val performedSetCount: Int
        get() = exercises.sumOf { exercise -> exercise.sets.count { it.reps.isNotBlank() } }

    val repCount: Int
        get() = exercises.sumOf { exercise -> exercise.sets.sumOf { it.reps.toIntOrNull() ?: 0 } }
}

/**
 * Holds the Workouts library filter/sort/search state, mirroring the iOS browser.
 * Persisted to SharedPreferences (the analog of iOS's ExerciseFilterStateStore) so
 * it survives process death, not just tab switches.
 */
class WorkoutsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ayuvo_workouts", Context.MODE_PRIVATE).also { prefs ->
        // Filter values from the retired free-exercise-db catalogue match nothing in the
        // current one, so start clean once per catalogue version.
        if (prefs.getInt(K_CATALOG_VERSION, 0) != CATALOG_VERSION) {
            prefs.edit().clear().putInt(K_CATALOG_VERSION, CATALOG_VERSION).apply()
        }
    }
    /** Peek only in the ctor — never call [ExerciseRepository.get] on the main thread. */
    private var exerciseRepository: ExerciseRepository? = ExerciseRepository.peek()
    private var workoutRepository: WorkoutRepository? = null
    private var exerciseImageStore: FoodImageStore? = null
    private var repositoryJob: Job? = null
    private var latestPersistedState = WorkoutPersistedState()
    private var exerciseLiftSummaries: Map<String, String?> = emptyMap()
    private var exerciseLiftSummaryInputs: ExerciseLiftSummaryInputs? = null
    private var bodyWeightKg = 70.0
    private var workoutWeightUnit = WorkoutWeightUnit.LBS
    private var profileGender = Gender.MALE

    var diaryUiState by mutableStateOf(WorkoutDiaryUiState())
        private set

    init {
        if (exerciseRepository == null) {
            viewModelScope.launch {
                ensureExerciseRepository()
                rebuildDiaryState()
            }
        }
    }

    private suspend fun ensureExerciseRepository(): ExerciseRepository {
        exerciseRepository?.let { return it }
        return withContext(Dispatchers.IO) {
            ExerciseRepository.get(getApplication()).also { exerciseRepository = it }
        }
    }

    private val _searchInput = mutableStateOf(prefs.getString(K_SEARCH, "") ?: "")
    private val _debouncedSearch = mutableStateOf(_searchInput.value)
    private var searchDebounceJob: Job? = null
    private var searchPersistJob: Job? = null

    /** Immediate search field value — keeps typing responsive. */
    var searchInput: String
        get() = _searchInput.value
        set(value) {
            _searchInput.value = value
            scheduleDebouncedSearch(value)
        }

    /** Debounced query used for filtering the exercise list. */
    val debouncedSearch: String
        get() = _debouncedSearch.value

    /** Back-compat alias for library filter bindings. */
    var search: String
        get() = searchInput
        set(value) { searchInput = value }

    private val _bodyParts = mutableStateOf(loadSet(K_BODY_PARTS))
    var bodyParts: Set<String>
        get() = _bodyParts.value
        set(v) { _bodyParts.value = v; saveSet(K_BODY_PARTS, v) }

    private val _equipment = mutableStateOf(loadSet(K_EQUIPMENT))
    var equipment: Set<String>
        get() = _equipment.value
        set(v) { _equipment.value = v; saveSet(K_EQUIPMENT, v) }

    private val _primary = mutableStateOf(loadSet(K_PRIMARY))
    var primaryMuscles: Set<String>
        get() = _primary.value
        set(v) { _primary.value = v; saveSet(K_PRIMARY, v) }

    private val _splitGroups = mutableStateOf(loadSet(K_SPLIT_GROUPS))
    var splitGroupTitles: Set<String>
        get() = _splitGroups.value
        set(v) {
            _splitGroups.value = v
            saveSet(K_SPLIT_GROUPS, v)
            if (v.isNotEmpty()) {
                val selectedMuscles = diaryUiState.splitGroups
                    .filter { it.title in v }
                    .flatMapTo(mutableSetOf()) { it.muscles }
                val hidePrimary = diaryUiState.preferences.split == com.ayuvo.health.models.WorkoutSplit.FULL_BODY
                val normalizedPrimary = if (hidePrimary) emptySet() else primaryMuscles.intersect(selectedMuscles)
                if (normalizedPrimary != primaryMuscles) primaryMuscles = normalizedPrimary
            }
        }

    private val _secondary = mutableStateOf(loadSet(K_SECONDARY))
    var secondaryMuscles: Set<String>
        get() = _secondary.value
        set(v) { _secondary.value = v; saveSet(K_SECONDARY, v) }

    private val _sort = mutableStateOf(
        runCatching { ExerciseSort.valueOf(prefs.getString(K_SORT, "") ?: "") }.getOrDefault(ExerciseSort.NAME)
    )
    var sort: ExerciseSort
        get() = _sort.value
        set(v) { _sort.value = v; prefs.edit().putString(K_SORT, v.name).apply() }

    /** Filtered library results, rendered one page at a time. Null until the first filter pass. */
    var libraryResults by mutableStateOf<PagedResults<ExerciseItem>?>(null)
        private set
    private var libraryFilterJob: Job? = null

    /**
     * Re-filters [repository] off the main thread with the current search, filters, sort and
     * split selection, then resets to the first page. A newer call cancels a pending one.
     */
    fun refreshLibrary(repository: ExerciseRepository) {
        val search = debouncedSearch
        val bodyParts = bodyParts
        val equipment = equipment
        val primary = primaryMuscles
        val secondary = secondaryMuscles
        val sort = sort
        val splitMuscles = diaryUiState.splitGroups
            .filter { it.title in splitGroupTitles }
            .flatMapTo(mutableSetOf()) { it.muscles }
        libraryFilterJob?.cancel()
        libraryFilterJob = viewModelScope.launch {
            val items = withContext(Dispatchers.Default) {
                repository.filtered(
                    bodyParts = bodyParts,
                    equipment = equipment,
                    primaryMuscles = primary,
                    secondaryMuscles = secondary,
                    sort = sort,
                    searchText = search
                ).let { filtered ->
                    if (splitMuscles.isEmpty()) filtered else filtered.filter { item ->
                        item.primaryMuscles.any(splitMuscles::contains) || item.secondaryMuscles.any(splitMuscles::contains)
                    }
                }
            }
            libraryResults = PagedResults.firstPage(items)
        }
    }

    /** Called as library rows appear; grows the rendered window near the end. */
    fun onLibraryRowVisible(index: Int) {
        val current = libraryResults ?: return
        val next = current.loadingNextPageIfNeeded(index)
        if (next !== current) libraryResults = next
    }

    /** Currently open exercise (by id), or null for the list. Not persisted. */
    var openExerciseId by mutableStateOf<String?>(null)

    /** Diary plans snapshot their exercise metadata, so detail still works after dataset changes. */
    var openExerciseSnapshot by mutableStateOf<ExerciseItem?>(null)

    /**
     * Binds the persistent diary without making the legacy library browser depend on DI.
     * Navigation can pass null while wiring is in progress; the final app always supplies the
     * AppContainer repository and therefore persists the default Log mode and every edit.
     */
    fun bindWorkoutRepository(
        repository: WorkoutRepository?,
        currentBodyWeightKg: Double,
        weightUnit: WorkoutWeightUnit,
        profileGender: Gender,
        imageStore: FoodImageStore? = null
    ) {
        bodyWeightKg = currentBodyWeightKg.takeIf { it.isFinite() && it > 0.0 } ?: 70.0
        workoutWeightUnit = weightUnit
        this.profileGender = profileGender
        exerciseImageStore = imageStore
        if (workoutRepository === repository && repositoryJob != null) {
            rebuildDiaryState()
            return
        }

        workoutRepository = repository
        repositoryJob?.cancel()
        if (repository == null) {
            diaryUiState = diaryUiState.copy(weightUnit = weightUnit, visualGender = profileGender)
            return
        }
        repositoryJob = viewModelScope.launch {
            repository.state.collectLatest { persisted ->
                latestPersistedState = persisted
                rebuildDiaryState()
            }
        }
    }

    fun setMode(mode: WorkoutTabMode) {
        if (diaryUiState.mode == mode) return
        diaryUiState = diaryUiState.copy(mode = mode)
        viewModelScope.launch { workoutRepository?.setMode(mode) }
    }

    fun selectDate(date: LocalDate) {
        if (date == diaryUiState.selectedDate) return
        diaryUiState = diaryUiState.copy(selectedDate = date)
        rebuildDiaryState()
    }

    fun moveDate(days: Long) {
        val proposed = diaryUiState.selectedDate.plusDays(days)
        if (days > 0 && proposed.isAfter(LocalDate.now())) return
        selectDate(proposed)
    }

    fun toggleExercise(item: ExerciseItem) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.toggleExercise(item, date) }
    }

    fun removeExercise(exerciseId: UUID) {
        val date = diaryUiState.selectedDate
        val imageStore = exerciseImageStore
        viewModelScope.launch { workoutRepository?.removeExercise(exerciseId, date, imageStore) }
    }

    fun setSetCount(exerciseId: UUID, count: Int) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.setSetCount(count, exerciseId, date) }
    }

    fun updateTimer(exerciseId: UUID, action: ExerciseTimerAction) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.updateTimer(exerciseId, date, action) }
    }

    fun setTimerIntensity(exerciseId: UUID, intensity: WorkoutIntensity) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.setTimerIntensity(exerciseId, date, intensity) }
    }

    fun updateWeight(exerciseId: UUID, setId: UUID, value: String) {
        updateSet(exerciseId, setId, weight = value)
    }

    fun updateReps(exerciseId: UUID, setId: UUID, value: String) {
        updateSet(exerciseId, setId, reps = value)
    }

    fun updateRpe(exerciseId: UUID, setId: UUID, value: String) {
        updateSet(exerciseId, setId, rpe = value)
    }

    private fun updateSet(
        exerciseId: UUID,
        setId: UUID,
        weight: String? = null,
        reps: String? = null,
        rpe: String? = null
    ) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch {
            workoutRepository?.updateSet(
                exerciseId = exerciseId,
                setId = setId,
                date = date,
                weight = weight,
                weightUnit = if (weight != null) workoutWeightUnit else null,
                reps = reps,
                rpe = rpe
            )
        }
    }

    fun toggleSaved(itemId: String) {
        viewModelScope.launch { workoutRepository?.toggleSaved(itemId) }
    }

    internal fun pickerSource(): WorkoutPickerSource = runCatching {
        WorkoutPickerSource.valueOf(prefs.getString(K_PICKER_SOURCE, "") ?: "")
    }.getOrDefault(WorkoutPickerSource.DATASET)

    internal fun setPickerSource(source: WorkoutPickerSource) {
        prefs.edit().putString(K_PICKER_SOURCE, source.name).apply()
    }

    internal fun pickerFilter(contextId: String): WorkoutPickerFilterState {
        val prefix = "$K_PICKER_FILTER_PREFIX$contextId."
        return WorkoutPickerFilterState(
            search = prefs.getString("${prefix}search", "").orEmpty(),
            primaryMuscle = prefs.getString("${prefix}primary", null),
            secondaryMuscle = prefs.getString("${prefix}secondary", null),
            equipment = prefs.getString("${prefix}equipment", null),
            bodyPart = prefs.getString("${prefix}bodyPart", null),
            sort = runCatching {
                ExerciseSort.valueOf(prefs.getString("${prefix}sort", "") ?: "")
            }.getOrDefault(ExerciseSort.NAME)
        )
    }

    internal fun setPickerFilter(contextId: String, state: WorkoutPickerFilterState) {
        val prefix = "$K_PICKER_FILTER_PREFIX$contextId."
        prefs.edit().apply {
            putString("${prefix}search", state.search)
            putString("${prefix}primary", state.primaryMuscle)
            putString("${prefix}secondary", state.secondaryMuscle)
            putString("${prefix}equipment", state.equipment)
            putString("${prefix}bodyPart", state.bodyPart)
            putString("${prefix}sort", state.sort.name)
        }.apply()
    }

    fun copyPlan(sourceDate: LocalDate, includeSetDetails: Boolean = false) {
        val targetDate = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.copyPlan(sourceDate, targetDate, includeSetDetails) }
    }

    fun logQuickOutdoorActivity(kind: OutdoorActivityKind, minutes: Int) {
        if (minutes <= 0) return
        val repository = workoutRepository ?: return
        val date = diaryUiState.selectedDate
        viewModelScope.launch {
            val exerciseId = when (kind) {
                OutdoorActivityKind.WALKING -> OutdoorActivitySettings.WALKING_EXERCISE_ID
                OutdoorActivityKind.RUNNING -> OutdoorActivitySettings.RUNNING_EXERCISE_ID
            }
            val catalog = ensureExerciseRepository()
            val item = catalog.exercises.firstOrNull { it.id == exerciseId } ?: return@launch
            repository.logQuickCardio(item, minutes, date)
            repository.calculateBurn(
                date = date,
                bodyWeightKg = bodyWeightKg,
                weightUnit = workoutWeightUnit
            )
        }
    }

    fun calculateBurn() {
        if (diaryUiState.isCalculatingBurn) return
        if (diaryUiState.exercises.none { it.hasCalculableWork }) {
            diaryUiState = diaryUiState.copy(
                notice = "Stop and save an exercise timer, or enter reps for at least one set, before calculating workout calories."
            )
            return
        }
        val repository = workoutRepository ?: return
        val date = diaryUiState.selectedDate
        diaryUiState = diaryUiState.copy(isCalculatingBurn = true)
        viewModelScope.launch {
            // Keep the state readable instead of flashing between two frames.
            delay(450)
            val saved = repository.calculateBurn(
                date = date,
                bodyWeightKg = bodyWeightKg,
                weightUnit = workoutWeightUnit
            )
            diaryUiState = diaryUiState.copy(
                isCalculatingBurn = false,
                notice = if (saved == null) {
                    "Stop and save an exercise timer, or enter reps for at least one set, before calculating workout calories."
                } else null
            )
        }
    }

    fun dismissNotice() {
        diaryUiState = diaryUiState.copy(notice = null)
    }

    fun lastExerciseLiftSummary(itemId: String, name: String): String? =
        exerciseLiftSummaries[liftSummaryKey(itemId, name)]

    fun exerciseLiftHistory(itemId: String, name: String): List<ExerciseLiftDay> =
        ExerciseLiftHistory.history(
            latestPersistedState,
            itemId,
            name,
            WorkoutDate.key(diaryUiState.selectedDate)
        )

    fun openDiaryExercise(exercise: PlannedExercise) {
        openExerciseSnapshot = exercise.asExerciseItem()
        openExerciseId = exercise.itemId
    }

    fun closeExerciseDetail() {
        openExerciseSnapshot = null
        openExerciseId = null
    }

    private fun rebuildDiaryState() {
        val date = diaryUiState.selectedDate
        val dateKey = WorkoutDate.key(date)
        val exercises = latestPersistedState.dayPlans[dateKey]?.exercises.orEmpty()
        val burn = latestPersistedState.completedSessions
            .asSequence()
            .filter { it.diaryDateKey == dateKey && it.caloriesBurned != null }
            .maxWithOrNull(compareBy({ it.healthSyncVersion ?: 0 }, { it.completedAt }))
            ?.caloriesBurned
        val counts = latestPersistedState.dayPlans.mapNotNull { (key, plan) ->
            WorkoutDate.parse(key)?.let { it to plan.exercises.size }
        }.toMap()
        val copyDays = latestPersistedState.dayPlans.values
            .asSequence()
            .filter { it.exercises.isNotEmpty() && it.dateKey < dateKey }
            .sortedByDescending { it.dateKey }
            .mapNotNull { plan ->
                WorkoutDate.parse(plan.dateKey)?.let { planDate ->
                    WorkoutCopyDayUi(planDate, plan.exercises.map(PlannedExercise::name))
                }
            }
            .toList()
        val preferences = latestPersistedState.preferences
        val catalog = exerciseRepository
        val splitGroups = catalog?.let {
            WorkoutSplitGroup.selectionGroups(
                split = preferences.split,
                availablePrimaryMuscles = it.availablePrimaryMuscles,
                availableSecondaryMuscles = it.availableSecondaryMuscles
            )
        }.orEmpty()
        val storedSplit = prefs.getString(K_SPLIT_IDENTIFIER, null)
        if (storedSplit != preferences.split.name) {
            splitGroupTitles = emptySet()
            prefs.edit().putString(K_SPLIT_IDENTIFIER, preferences.split.name).apply()
        } else {
            val validTitles = splitGroups.mapTo(mutableSetOf()) { it.title }
            val normalized = splitGroupTitles.intersect(validTitles)
            if (normalized != splitGroupTitles) splitGroupTitles = normalized
        }
        diaryUiState = diaryUiState.copy(
            mode = latestPersistedState.mode,
            exercises = exercises,
            workoutCounts = counts,
            caloriesBurned = burn,
            savedExerciseIds = latestPersistedState.savedExerciseIds,
            preferences = preferences,
            splitGroups = splitGroups,
            copyDays = copyDays,
            weightUnit = workoutWeightUnit,
            visualGender = profileGender
        )
        rebuildExerciseLiftSummariesIfNeeded()
    }

    private fun liftSummaryKey(itemId: String, name: String): String =
        "$itemId\u0000${ExerciseLiftHistory.normalizedName(name)}"

    private data class ExerciseLiftSummaryInputs(
        val beforeKey: String,
        val weightUnit: WorkoutWeightUnit,
        val historyToken: Int,
        val exerciseKeys: Set<String>
    )

    private fun completedSessionsHistoryToken(state: WorkoutPersistedState): Int =
        state.completedSessions.fold(0) { token, session ->
            var next = 31 * token + session.diaryDateKey.hashCode()
            next = 31 * next + session.completedAt.hashCode()
            next = 31 * next + (session.healthSyncVersion ?: 0)
            session.exercises.fold(next) { exerciseToken, exercise ->
                var perExercise = 31 * exerciseToken + exercise.itemId.hashCode()
                perExercise = 31 * perExercise + exercise.sets.count { it.isPerformed }
                perExercise
            }
        }

    private fun rebuildExerciseLiftSummariesIfNeeded() {
        val beforeKey = WorkoutDate.key(diaryUiState.selectedDate)
        val exerciseKeys = diaryUiState.exercises
            .asSequence()
            .filterNot { it.isCardio }
            .map { liftSummaryKey(it.itemId, it.name) }
            .toSet()
        val inputs = ExerciseLiftSummaryInputs(
            beforeKey = beforeKey,
            weightUnit = workoutWeightUnit,
            historyToken = completedSessionsHistoryToken(latestPersistedState),
            exerciseKeys = exerciseKeys
        )
        if (inputs == exerciseLiftSummaryInputs) return
        exerciseLiftSummaryInputs = inputs
        exerciseLiftSummaries = diaryUiState.exercises
            .asSequence()
            .filterNot { it.isCardio }
            .associate { exercise ->
                liftSummaryKey(exercise.itemId, exercise.name) to ExerciseLiftHistory.lastSummary(
                    latestPersistedState,
                    exercise.itemId,
                    exercise.name,
                    beforeKey,
                    workoutWeightUnit
                )
            }
    }

    val hasActiveFilters: Boolean
        get() = searchInput.isNotEmpty() || splitGroupTitles.isNotEmpty() || bodyParts.isNotEmpty() || equipment.isNotEmpty() ||
            primaryMuscles.isNotEmpty() || secondaryMuscles.isNotEmpty() || sort != ExerciseSort.NAME

    private fun scheduleDebouncedSearch(value: String) {
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(WORKOUT_SEARCH_DEBOUNCE_MS)
            _debouncedSearch.value = value
        }
        searchPersistJob?.cancel()
        searchPersistJob = viewModelScope.launch {
            delay(WORKOUT_FILTER_PERSIST_MS)
            prefs.edit().putString(K_SEARCH, value).apply()
        }
    }

    fun reset() {
        searchDebounceJob?.cancel()
        searchPersistJob?.cancel()
        _searchInput.value = ""
        _debouncedSearch.value = ""
        prefs.edit().putString(K_SEARCH, "").apply()
        splitGroupTitles = emptySet()
        bodyParts = emptySet()
        equipment = emptySet()
        primaryMuscles = emptySet()
        secondaryMuscles = emptySet()
        sort = ExerciseSort.NAME
    }

    private fun loadSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    private fun saveSet(key: String, v: Set<String>) { prefs.edit().putStringSet(key, v).apply() }

    private companion object {
        const val WORKOUT_SEARCH_DEBOUNCE_MS = 175L
        const val WORKOUT_FILTER_PERSIST_MS = 400L
        const val K_SEARCH = "search"
        const val K_CATALOG_VERSION = "catalog_version"
        /** Bump with [com.ayuvo.health.models.WorkoutPersistedState.CurrentVersion]. */
        const val CATALOG_VERSION = 2
        const val K_BODY_PARTS = "body_parts"
        const val K_EQUIPMENT = "equipment"
        const val K_PRIMARY = "primary"
        const val K_SPLIT_GROUPS = "split_groups"
        const val K_SPLIT_IDENTIFIER = "split_identifier"
        const val K_SECONDARY = "secondary"
        const val K_SORT = "sort"
        const val K_PICKER_SOURCE = "picker.source"
        const val K_PICKER_FILTER_PREFIX = "picker.filter."
    }
}
