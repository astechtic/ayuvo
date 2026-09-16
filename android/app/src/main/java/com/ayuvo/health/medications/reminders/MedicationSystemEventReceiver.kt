package com.ayuvo.health.medications.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ayuvo.health.AyuvoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms the medication alarm after the events that drop or invalidate it
 * (docs/medications.md §10): reboot, app update, a time-zone or clock change, and the user
 * flipping the exact-alarm permission. Manifest-registered; runs one planner pass and exits.
 */
class MedicationSystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in handledActions()) return
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

    companion object {
        fun handledActions(): Set<String> {
            val actions = mutableSetOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                actions += AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            }
            return actions
        }
    }
}
