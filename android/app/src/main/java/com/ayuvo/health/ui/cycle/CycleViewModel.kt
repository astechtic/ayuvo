package com.ayuvo.health.ui.cycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.cycle.data.CycleDayLog
import com.ayuvo.health.cycle.data.CyclePeriod
import com.ayuvo.health.cycle.data.CyclePreferences
import com.ayuvo.health.cycle.data.CycleRepository
import com.ayuvo.health.cycle.data.CycleSettingsRow
import com.ayuvo.health.cycle.engine.CycleConfig
import com.ayuvo.health.cycle.engine.CycleDayStatus
import com.ayuvo.health.cycle.engine.CycleEngine
import com.ayuvo.health.cycle.engine.CyclePeriodInput
import com.ayuvo.health.cycle.engine.CyclePeriodValidation
import com.ayuvo.health.cycle.engine.CycleSnapshot
import com.ayuvo.health.cycle.engine.CycleState
import com.ayuvo.health.cycle.engine.CycleTrends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Everything the cycle screens show, rebuilt off the main thread after each write. */
data class CycleUi(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val settings: CycleSettingsRow = CycleSettingsRow(),
    val enabled: Boolean = true,
    val showFertility: Boolean = true,
    val snapshot: CycleSnapshot? = null,
    val trends: CycleTrends? = null,
    /** Live app periods (with their Health sync state), oldest first. */
    val periods: List<CyclePeriod> = emptyList(),
    val logs: Map<String, CycleDayLog> = emptyMap(),
    /** Status of every day of the current cycle (first day of the last period up to the next estimated start). */
    val ring: List<CycleDayStatus> = emptyList()
) {
    val setupDone: Boolean get() = settings.setupDone
    /** The app period still running (end not logged), if any. */
    val ongoing: CyclePeriod? get() = periods.lastOrNull { it.endDay == null }
}

/**
 * State and actions of the Period Tracker screens (docs/cycle-tracking.md §3, §5). The engine runs on
 * `Dispatchers.Default` only when the repository revision or a display preference changes; month grids are cached
 * until the next change. Every write goes through [CycleRepository] (which bumps the revision) and wakes the
 * background coordinator (reminders, Health Connect).
 */
class CycleViewModel(private val container: AppContainer) : ViewModel() {
    val config: CycleConfig = container.cycleConfig
    private val engine: CycleEngine = container.cycleEngine
    private val repo: CycleRepository = container.cycleRepository

    private val _ui = MutableStateFlow(CycleUi())
    val ui: StateFlow<CycleUi> = _ui

    private val _months = MutableStateFlow<Map<YearMonth, List<CycleDayStatus>>>(emptyMap())
    val months: StateFlow<Map<YearMonth, List<CycleDayStatus>>> = _months

    @Volatile private var state: CycleState? = null

    init {
        container.cycleCoordinator.start()
        viewModelScope.launch {
            combine(repo.revision, container.prefs.cycleEnabled, container.prefs.cycleShowFertility) { _, e, f -> e to f }
                .collect { (enabled, fertility) -> reload(enabled, fertility) }
        }
    }

    private suspend fun reload(enabled: Boolean, fertility: Boolean) {
        val today = LocalDate.now()
        val settings = repo.settings()
        val s = repo.state(today.toString())
        val periods = repo.periods()
        val logs = repo.dayLogs().associateBy { it.day }
        val (snapshot, trends, ring) = withContext(Dispatchers.Default) {
            val snap = engine.snapshot(s)
            val current = snap.prediction.windows.firstOrNull { !it.predictedStart }
            val ringDays = if (current != null && snap.prediction.nextStart != null) {
                val start = LocalDate.parse(current.cycleStart)
                val last = LocalDate.parse(snap.prediction.nextStart).minusDays(1)
                val capped = if (last.isAfter(start.plusDays(RING_MAX_DAYS - 1L))) start.plusDays(RING_MAX_DAYS - 1L) else last
                engine.dayStatus(s, start.toString(), capped.toString())
            } else emptyList()
            Triple(snap, engine.trends(s), ringDays)
        }
        state = s
        _months.value = emptyMap()
        _ui.value = CycleUi(false, today, settings, enabled, fertility, snapshot, trends, periods, logs, ring)
    }

    /** Computes (once per data change) the status of every day in [month]'s 6-week grid. */
    fun ensureMonth(month: YearMonth, firstDayOfWeek: DayOfWeek) {
        if (_months.value.containsKey(month)) return
        val s = state ?: return
        viewModelScope.launch {
            val (from, to) = gridRange(month, firstDayOfWeek)
            val days = withContext(Dispatchers.Default) { engine.dayStatus(s, from.toString(), to.toString()) }
            if (state === s) _months.update { it + (month to days) }
        }
    }

    private fun appInputs(): List<CyclePeriodInput> = _ui.value.periods.map { CyclePeriodInput(it.id, it.startDay, it.endDay, "app") }

    private fun today(): String = LocalDate.now().toString()

    // -- Period actions ------------------------------------------------------------------------

    /** "This is a period day" on or off for [day] (one tap "Period started" = today on). */
    fun setPeriodDay(day: LocalDate, on: Boolean, done: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val result = engine.applyPeriodDay(today(), day.toString(), on, appInputs())
            if (result.error == null && result.ops.isNotEmpty()) repo.applyPeriodOps(result.ops)
            done(result.error)
        }
    }

    /** "Period ended" on [day] for the running period. */
    fun endPeriod(day: LocalDate, done: (CyclePeriodValidation?) -> Unit = {}) {
        val p = _ui.value.ongoing ?: return
        savePeriod(p.id, LocalDate.parse(p.startDay), day, merge = false, done = done)
    }

    /**
     * Saves a new ([id] null) or edited period after `validatePeriod`. With [merge] an overlap is resolved by merging
     * the overlapping app periods into one; otherwise the validation is handed back for the sheet to show.
     */
    fun savePeriod(id: String?, start: LocalDate, end: LocalDate?, merge: Boolean, done: (CyclePeriodValidation?) -> Unit) {
        viewModelScope.launch {
            val periods = appInputs()
            val v = engine.validatePeriod(today(), id, start.toString(), end?.toString(), periods)
            if (!v.ok) {
                val onlyOverlap = v.errors == listOf("overlap")
                if (!(merge && onlyOverlap && v.merged != null)) {
                    done(v); return@launch
                }
                val keep = id ?: v.overlaps.first()
                for (other in v.overlaps) if (other != keep) repo.deletePeriod(other)
                repo.updatePeriod(keep, v.merged!![0]!!, v.merged!![1])
                done(null); return@launch
            }
            if (id == null) repo.insertPeriod(start.toString(), end?.toString()) else repo.updatePeriod(id, start.toString(), end?.toString())
            done(null)
        }
    }

    fun deletePeriod(id: String) {
        viewModelScope.launch { repo.deletePeriod(id) }
    }

    // -- Day log ---------------------------------------------------------------------------------

    /**
     * Saves the day log. A flow from Light upwards on a day outside any period also marks it a period day
     * (§5); Spotting does not.
     */
    fun saveDayLog(log: CycleDayLog) {
        viewModelScope.launch {
            val trimmed = log.copy(note = log.note?.take(config.limits.noteMaxChars)?.takeIf { it.isNotBlank() })
            repo.saveDayLog(trimmed)
            val isPeriodFlow = config.flowLevels.firstOrNull { it.key == trimmed.flow }?.period == true
            if (isPeriodFlow && !isPeriodDay(LocalDate.parse(trimmed.day))) {
                val r = engine.applyPeriodDay(today(), trimmed.day, true, appInputs())
                if (r.error == null && r.ops.isNotEmpty()) repo.applyPeriodOps(r.ops)
            }
        }
    }

    /** True when [day] lies in a logged period (app or Health Connect, after normalisation). */
    fun isPeriodDay(day: LocalDate): Boolean {
        val today = _ui.value.today
        return _ui.value.snapshot?.periods.orEmpty().any {
            val s = LocalDate.parse(it.start)
            val e = it.end?.let(LocalDate::parse) ?: today
            !day.isBefore(s) && !day.isAfter(e)
        }
    }

    /** True when [day] lies in one of the user's own (editable) app periods. */
    fun isAppPeriodDay(day: LocalDate): Boolean {
        val today = _ui.value.today
        return _ui.value.periods.any {
            val s = LocalDate.parse(it.startDay)
            val e = it.endDay?.let(LocalDate::parse) ?: today
            !day.isBefore(s) && !day.isAfter(e)
        }
    }

    suspend fun platformDay(day: LocalDate): CycleRepository.PlatformDay? = repo.platformDay(day.toString())

    // -- Setup and settings ----------------------------------------------------------------------

    /** Finishes setup; [lastStart] (optional) becomes the first app period. */
    fun completeSetup(lastStart: LocalDate?, cycleLength: Int?, periodLength: Int?, prefs: CyclePreferences, done: () -> Unit) {
        viewModelScope.launch {
            val current = repo.settings()
            repo.saveSettings(current.copy(setupDone = true, cycleLength = cycleLength, periodLength = periodLength, preferences = prefs))
            if (lastStart != null && repo.periods().isEmpty()) {
                val effective = engine.effectiveSettings(repo.settings().toEngineInput())
                val end = lastStart.plusDays((effective.periodLength - 1).toLong())
                repo.insertPeriod(lastStart.toString(), if (!end.isBefore(LocalDate.now())) null else end.toString())
            }
            container.cycleCoordinator.start()
            done()
        }
    }

    fun saveSettings(transform: (CycleSettingsRow) -> CycleSettingsRow) {
        viewModelScope.launch {
            val before = repo.settings()
            val after = transform(before)
            repo.saveSettings(after)
            if (after.preferences.healthSync && !before.preferences.healthSync) repo.markAllPending()
        }
    }

    fun setShowFertility(on: Boolean) {
        viewModelScope.launch {
            container.prefs.setCycleShowFertility(on)
            val s = repo.settings()
            if (s.preferences.showFertility != on) repo.saveSettings(s.copy(preferences = s.preferences.copy(showFertility = on)))
        }
    }

    /** Health Connect is installed and usable on this device. */
    fun healthAvailable(): Boolean = runCatching { container.health.isAvailable() }.getOrDefault(false)

    fun healthPermissionContract() = container.health.permissionRequestContract()

    val healthPermissions: Set<String> get() = com.ayuvo.health.cycle.health.CycleHealthConnectWriter.PERMISSIONS

    /** Turns Health sync on only when the write permission is granted (the permission dialog's result). */
    fun onHealthPermissionResult(granted: Set<String>) {
        val ok = com.ayuvo.health.cycle.health.CycleHealthConnectWriter.WRITE_PERMISSION in granted
        saveSettings { it.copy(preferences = it.preferences.copy(healthSync = ok)) }
    }

    val notificationsEnabled = container.prefs.notificationsEnabled

    fun setCoachEnabled(on: Boolean) {
        viewModelScope.launch { container.prefs.setCoachCycleEnabled(on) }
    }

    val coachEnabled = container.prefs.coachCycleEnabled

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { container.prefs.setCycleEnabled(on) }
    }

    fun deleteAll(done: () -> Unit) {
        viewModelScope.launch {
            repo.deleteAll()
            done()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CycleViewModel(container) as T
    }

    companion object {
        const val RING_MAX_DAYS = 90

        /** First and last day of the 6-week grid that shows [month]. */
        fun gridRange(month: YearMonth, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> {
            val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            return start to start.plusDays(41)
        }
    }
}
