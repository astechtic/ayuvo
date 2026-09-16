package com.ayuvo.health.medications.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The single "next wake" alarm behind medication reminders (docs/medications.md §10): the
 * planner arms it at the earliest pending `fire_at_ms`; when it fires, [MedicationAlarmReceiver]
 * posts every due dose and re-plans.
 *
 * Exact when the user granted `SCHEDULE_EXACT_ALARM` (a deliberate exception to the app's
 * inexact-alarm policy, for medication reminders only); otherwise the inexact
 * `setAndAllowWhileIdle` fallback that the rest of the app uses.
 */
object MedicationAlarms {
    /** Request code of the wake alarm; the app's other reminders use 1001–1007, 4242, 5555. */
    const val REQUEST_NEXT_WAKE = 2001

    fun arm(context: Context, atMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (exactAllowed(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                return
            } catch (_: SecurityException) {
                // The grant can be revoked between the check and the call; fall through.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    /** True when an alarm PendingIntent currently exists (tests and diagnostics). */
    fun isArmed(context: Context): Boolean =
        pendingIntent(context, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null

    /** Below Android 12 exact alarms need no permission. */
    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** Opens the system "Alarms & reminders" toggle for this app (Android 12+), else null. */
    fun requestExactIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, REQUEST_NEXT_WAKE, Intent(context, MedicationAlarmReceiver::class.java), flags)
}
