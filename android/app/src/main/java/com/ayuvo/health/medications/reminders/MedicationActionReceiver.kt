package com.ayuvo.health.medications.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.medications.data.MedicationsStore
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One Taken / Skip / Snooze button press, decoded from the action intent's extras. */
data class DoseActionRequest(
    val medicationId: String,
    val scheduleId: String?,
    val scheduledAtMs: Long,
    val action: DoseAction
)

/** The receiver's logic, separated so instrumented tests can run it on a throwaway store. */
object MedicationActionHandler {
    fun parse(intent: Intent?): DoseActionRequest? {
        if (intent?.action != MedicationNotifications.ACTION_DOSE) return null
        val medicationId = intent.getStringExtra(MedicationNotifications.EXTRA_MEDICATION_ID) ?: return null
        if (!intent.hasExtra(MedicationNotifications.EXTRA_SCHEDULED_AT)) return null
        val action = DoseAction.fromRaw(intent.getStringExtra(MedicationNotifications.EXTRA_ACTION)) ?: return null
        return DoseActionRequest(
            medicationId = medicationId,
            scheduleId = intent.getStringExtra(MedicationNotifications.EXTRA_SCHEDULE_ID),
            scheduledAtMs = intent.getLongExtra(MedicationNotifications.EXTRA_SCHEDULED_AT, 0L),
            action = action
        )
    }

    /** Applies the action (docs/medications.md §11); a rejected action leaves the row untouched. */
    suspend fun apply(store: MedicationsStore, request: DoseActionRequest, nowMs: Long, snoozeMinutes: Int): DoseActionResult =
        store.act(
            medicationId = request.medicationId,
            scheduleId = request.scheduleId,
            scheduledAtMs = request.scheduledAtMs,
            action = request.action,
            nowMs = nowMs,
            snoozeMinutes = snoozeMinutes
        )
}

/**
 * Taken / Skip / Snooze buttons of a reminder (docs/medications.md §11, §16). Applies the
 * action through the store without opening the app, dismisses the notification and re-plans.
 */
class MedicationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = MedicationActionHandler.parse(intent) ?: return
        MedicationNotifications.cancel(context, request.medicationId, request.scheduledAtMs)

        val app = context.applicationContext as? AyuvoApp ?: return
        if (!app.container.medicationsDatabaseExists()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = app.container
                val snooze = container.prefs.medicationSnoozeMinutes.first()
                MedicationActionHandler.apply(container.medicationsStore, request, System.currentTimeMillis(), snooze)
                container.medicationReminders.replan()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
