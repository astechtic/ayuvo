package com.ayuvo.health.medications.reminders

import android.content.Context
import android.util.Log
import com.ayuvo.health.medications.data.MedicationsStore
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.ReminderEntry
import com.ayuvo.health.medications.model.ReminderKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Keeps the single medication alarm in step with the database (docs/medications.md §10):
 * every planner run materializes missed doses, auto-completes ended medicines, posts the doses
 * that are due right now and arms [MedicationAlarms] at the next future reminder.
 *
 * Re-plan triggers: [start] (app start, then every store write, debounced), the alarm receiver,
 * the action receiver, boot / package-replaced / time-zone receivers, the maintenance worker and
 * the Settings toggles. All of them end up in [replan], which is serialized by a mutex.
 *
 * @param gate whether reminders may be scheduled at all ([ReminderGate] over the prefs + permission).
 * @param snoozeMinutes the user's default snooze, shown on the Snooze action.
 */
class MedicationReminderCoordinator(
    private val context: Context,
    private val store: () -> MedicationsStore,
    private val scope: CoroutineScope,
    private val databaseExists: () -> Boolean,
    private val gate: suspend () -> Boolean,
    private val snoozeMinutes: suspend () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> String = { ZoneId.systemDefault().id }
) {
    private val mutex = Mutex()
    private var collector: Job? = null

    /**
     * Doses already posted by this process (`identity[:snooze:until]`), so a due dose is shown
     * once and never re-armed at "now" while its 5-minute late-fire window is open. A process
     * restart may repeat one alert — acceptable, and far better than a nag loop.
     */
    private val posted = LinkedHashSet<String>()

    /** Idempotent: starts re-planning on every committed store write (300 ms debounce). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (collector != null) return
        collector = scope.launch {
            store().revision.debounce(DEBOUNCE_MS).collect { replanSafely() }
        }
    }

    fun replanAsync() {
        scope.launch { replanSafely() }
    }

    private suspend fun replanSafely() {
        runCatching { replan() }.onFailure { Log.w(TAG, "Medication re-plan failed: ${it.javaClass.simpleName}") }
    }

    /**
     * One planner run. Safe to call from receivers (inside `goAsync`) and workers; does nothing
     * when no medications database exists so a fresh install never creates one.
     * Returns the instant the alarm was armed for, or null when nothing is pending.
     */
    suspend fun replan(): Long? = mutex.withLock {
        if (!databaseExists()) return null
        val s = store()
        val nowMs = now()
        val zone = zoneId()
        runCatching { s.materializeMissed(nowMs, zone) }
            .onFailure { Log.w(TAG, "materializeMissed failed: ${it.javaClass.simpleName}") }
        runCatching { s.autoComplete(MedicationLocalTime.localDateOf(nowMs, zone), nowMs) }
            .onFailure { Log.w(TAG, "autoComplete failed: ${it.javaClass.simpleName}") }

        if (!gate()) {
            MedicationAlarms.cancel(context)
            MedicationNotifications.cancelAll(context)
            return null
        }

        val plan = s.planReminders(nowMs, HORIZON_MS, zone, null)
        val snooze = snoozeMinutes()
        var nextFire: Long? = null
        for (entry in plan.entries) {
            val key = postedKey(s, entry)
            if (key in posted) continue
            if (entry.fireAtMs <= nowMs + DUE_TOLERANCE_MS) {
                val medication = s.medication(entry.medicationId) ?: continue
                MedicationNotifications.post(context, entry, medication, snooze)
                remember(key)
            } else {
                nextFire = entry.fireAtMs
                break
            }
        }
        if (nextFire != null) MedicationAlarms.arm(context, nextFire) else MedicationAlarms.cancel(context)
        runCatching { s.setMeta(MedicationConstants.META_LAST_PLANNED, nowMs.toString()) }
        nextFire
    }

    /** Forgets posted doses (Delete All Data / tests). */
    fun reset() {
        posted.clear()
    }

    private suspend fun postedKey(s: MedicationsStore, entry: ReminderEntry): String {
        if (entry.kind != ReminderKind.SNOOZE) return entry.identity
        val until = entry.scheduleId?.let { s.doseLog(it, entry.scheduledAtMs) }?.snoozedUntilMs
        return "${entry.identity}:snooze:${until ?: entry.fireAtMs}"
    }

    private fun remember(key: String) {
        posted += key
        if (posted.size > MAX_POSTED) {
            val iterator = posted.iterator()
            repeat(posted.size - MAX_POSTED) { iterator.next(); iterator.remove() }
        }
    }

    companion object {
        private const val TAG = "AyuvoMedications"
        const val HORIZON_MS = 7L * 86_400_000L
        const val DEBOUNCE_MS = 300L
        /** An alarm may land a little late; anything due within a minute is posted in the same run. */
        const val DUE_TOLERANCE_MS = 60_000L
        private const val MAX_POSTED = 500
    }
}
