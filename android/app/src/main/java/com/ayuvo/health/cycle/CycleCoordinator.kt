package com.ayuvo.health.cycle

import android.content.Context
import com.ayuvo.health.cycle.data.CycleRepository
import com.ayuvo.health.cycle.engine.CycleEngine
import com.ayuvo.health.cycle.health.CycleHealthConnectWriter
import com.ayuvo.health.cycle.reminders.CycleReminderAlarms
import com.ayuvo.health.cycle.reminders.CycleReminderPlanner
import com.ayuvo.health.data.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background side of cycle tracking (docs/cycle-tracking.md §4, §6): after every write (debounced), on app start and
 * when a reminder fires, it re-plans the reminders and writes pending rows to Health Connect. Started only once the
 * database exists, so users who never open the tracker get nothing scheduled.
 */
class CycleCoordinator(
    private val context: Context,
    private val repository: () -> CycleRepository,
    private val engine: () -> CycleEngine,
    private val prefs: PreferencesStore,
    private val writer: () -> CycleHealthConnectWriter,
    private val canPostNotifications: () -> Boolean,
    private val scope: CoroutineScope
) {
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        CycleReminderAlarms.createChannel(context)
        scope.launch {
            combine(repository().revision, prefs.cycleEnabled, prefs.notificationsEnabled) { r, e, n -> Triple(r, e, n) }
                .debounce(DEBOUNCE_MS)
                .collect { runCatching { refreshNow() } }
        }
    }

    /** Whether a reminder may be posted right now (feature shown, notifications on, permission granted). */
    suspend fun mayPost(): Boolean =
        prefs.cycleEnabled.first() && prefs.notificationsEnabled.first() && canPostNotifications() &&
            repository().settings().setupDone

    suspend fun lockScreenDetails(): Boolean = repository().settings().preferences.lockScreenDetails

    suspend fun refreshNow() = mutex.withLock {
        val repo = repository()
        val settings = repo.settings()
        val enabled = prefs.cycleEnabled.first()
        val notifyOk = prefs.notificationsEnabled.first() && canPostNotifications()
        val now = ZonedDateTime.now()
        val snapshot = if (settings.setupDone && enabled) engine().snapshot(repo.state(LocalDate.now().toString())) else null
        val planned = if (snapshot == null || !notifyOk) emptyList() else CycleReminderPlanner.plan(
            snapshot.reminders, settings.preferences, settings.setupDone, enabled, now, snapshot.prediction.nextStart
        )
        CycleReminderAlarms.apply(context, planned)
        runCatching { writer().syncPending(settings.preferences.healthSync) }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
