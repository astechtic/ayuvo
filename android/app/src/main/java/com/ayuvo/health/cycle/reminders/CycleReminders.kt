package com.ayuvo.health.cycle.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.MainActivity
import com.ayuvo.health.R
import com.ayuvo.health.cycle.data.CyclePreferences
import com.ayuvo.health.cycle.engine.CycleReminder
import com.ayuvo.health.services.notifySafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** One alarm to arm: [kind] = period_soon | period_end | daily_log; [daysAhead] feeds the detailed text. */
data class CyclePlannedReminder(val kind: String, val requestCode: Int, val atMs: Long, val daysAhead: Int?)

/**
 * Pure planning of the cycle reminders (docs/cycle-tracking.md §6) from the engine's `snapshot.reminders`: every
 * reminder fires at the user's reminder time on its day; one already in the past moves to the next day
 * (period_end, daily_log) or is dropped (period_soon). Nothing is planned before setup or while the feature is hidden.
 */
object CycleReminderPlanner {
    const val REQUEST_PERIOD_SOON = 3001
    const val REQUEST_PERIOD_END = 3002
    const val REQUEST_DAILY = 3003
    val REQUEST_CODES = listOf(REQUEST_PERIOD_SOON, REQUEST_PERIOD_END, REQUEST_DAILY)

    fun plan(
        reminders: List<CycleReminder>,
        prefs: CyclePreferences,
        setupDone: Boolean,
        enabled: Boolean,
        now: ZonedDateTime,
        nextStart: String?
    ): List<CyclePlannedReminder> {
        if (!setupDone || !enabled) return emptyList()
        val time = runCatching { LocalTime.parse(prefs.time) }.getOrDefault(LocalTime.of(9, 0))
        val zone = now.zone
        val today = now.toLocalDate()
        fun at(day: LocalDate): ZonedDateTime = LocalDateTime.of(day, time).atZone(zone)
        val out = mutableListOf<CyclePlannedReminder>()
        for (r in reminders) {
            when (r.kind) {
                "period_soon" -> {
                    val day = r.day?.let(LocalDate::parse) ?: continue
                    val fire = at(day)
                    if (!fire.isAfter(now)) continue
                    val ahead = nextStart?.let { ChronoUnit.DAYS.between(day, LocalDate.parse(it)).toInt() }
                    out += CyclePlannedReminder(r.kind, REQUEST_PERIOD_SOON, fire.toInstant().toEpochMilli(), ahead)
                }
                "period_end" -> {
                    var fire = at(r.day?.let(LocalDate::parse) ?: today)
                    if (!fire.isAfter(now)) fire = at(today.plusDays(1))
                    out += CyclePlannedReminder(r.kind, REQUEST_PERIOD_END, fire.toInstant().toEpochMilli(), null)
                }
                "daily_log" -> {
                    var fire = at(today)
                    if (!fire.isAfter(now)) fire = at(today.plusDays(1))
                    out += CyclePlannedReminder(r.kind, REQUEST_DAILY, fire.toInstant().toEpochMilli(), null)
                }
            }
        }
        return out
    }

    /** The notification text: discreet unless the user turned on lock-screen details. */
    fun text(context: Context, kind: String, details: Boolean, daysAhead: Int?): Pair<String, String> {
        if (!details) return context.getString(R.string.app_name) to context.getString(R.string.cycle_notif_discreet)
        val body = when (kind) {
            "period_soon" -> context.resources.getQuantityString(R.plurals.cycle_notif_period_soon, daysAhead ?: 2, daysAhead ?: 2)
            "period_end" -> context.getString(R.string.cycle_notif_period_end)
            else -> context.getString(R.string.cycle_notif_daily)
        }
        return context.getString(R.string.app_name) to body
    }
}

/** Inexact alarms (the app's policy outside medications) with their own request codes. */
object CycleReminderAlarms {
    const val CHANNEL = "cycle"
    const val EXTRA_KIND = "cycle_kind"
    const val EXTRA_DAYS = "cycle_days"
    private const val NOTIFICATION_ID_BASE = 3100

    fun createChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL, context.getString(R.string.cycle_notif_channel), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.cycle_notif_channel_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        mgr.createNotificationChannel(channel)
    }

    fun apply(context: Context, planned: List<CyclePlannedReminder>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val byCode = planned.associateBy { it.requestCode }
        for (code in CycleReminderPlanner.REQUEST_CODES) {
            val p = byCode[code]
            if (p == null) {
                pending(context, code, null, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let {
                    am.cancel(it); it.cancel()
                }
                continue
            }
            val pi = pending(context, code, p, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.atMs, pi)
        }
    }

    fun cancelAll(context: Context) = apply(context, emptyList())

    private fun pending(context: Context, code: Int, p: CyclePlannedReminder?, flags: Int): PendingIntent? {
        val intent = Intent(context, CycleReminderReceiver::class.java).apply {
            if (p != null) {
                putExtra(EXTRA_KIND, p.kind)
                p.daysAhead?.let { putExtra(EXTRA_DAYS, it) }
            }
        }
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }

    fun post(context: Context, kind: String, details: Boolean, daysAhead: Int?) {
        val (title, body) = CycleReminderPlanner.text(context, kind, details, daysAhead)
        val (pubTitle, pubBody) = CycleReminderPlanner.text(context, kind, false, null)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID_BASE,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(pubTitle)
            .setContentText(pubBody)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(if (details) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val id = NOTIFICATION_ID_BASE + CycleReminderPlanner.REQUEST_CODES.indexOf(codeOf(kind)).coerceAtLeast(0)
        NotificationManagerCompat.from(context).notifySafely(context, id, notification)
    }

    private fun codeOf(kind: String): Int = when (kind) {
        "period_soon" -> CycleReminderPlanner.REQUEST_PERIOD_SOON
        "period_end" -> CycleReminderPlanner.REQUEST_PERIOD_END
        else -> CycleReminderPlanner.REQUEST_DAILY
    }
}

/** Posts a fired cycle reminder, then re-plans (the daily reminder re-arms for tomorrow). */
class CycleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(CycleReminderAlarms.EXTRA_KIND) ?: return
        val days = if (intent.hasExtra(CycleReminderAlarms.EXTRA_DAYS)) intent.getIntExtra(CycleReminderAlarms.EXTRA_DAYS, 2) else null
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as AyuvoApp
                val coordinator = app.container.cycleCoordinator
                if (coordinator.mayPost()) {
                    CycleReminderAlarms.post(context, kind, coordinator.lockScreenDetails(), days)
                }
                coordinator.refreshNow()
            } catch (_: Exception) {
                // Never crash the receiver; the next app start re-plans.
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are dropped on reboot and shifted by clock / zone changes: re-plan. */
class CycleSystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as AyuvoApp
                if (app.container.cycleDatabaseExists()) app.container.cycleCoordinator.refreshNow()
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
