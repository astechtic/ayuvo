package com.ayuvo.health.medications.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ayuvo.health.AyuvoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired by the single next-wake alarm ([MedicationAlarms]): posts every dose that is due now
 * and arms the alarm for the next one (docs/medications.md §10). Posting and re-arming are one
 * planner run so a posted dose is never re-armed at "now".
 */
class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AyuvoApp ?: return
        if (!app.container.medicationsDatabaseExists()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.medicationReminders.replan()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
