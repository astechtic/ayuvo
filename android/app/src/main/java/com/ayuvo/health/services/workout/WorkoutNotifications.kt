package com.ayuvo.health.services.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ayuvo.health.MainActivity
import com.ayuvo.health.R
import com.ayuvo.health.models.ActiveStrengthSession

/**
 * The Live Update notification for a recording workout (docs/workouts-gps.md §4): its own low-importance channel,
 * a chronometer, the live numbers and Pause/Resume/Lap/End actions. On API 36 it is built with the platform
 * builder so it can use `Notification.ProgressStyle` and request promotion (a Live Update chip on the status bar
 * and lock screen); older releases use NotificationCompat with the same content.
 */
object WorkoutNotifications {
    const val CHANNEL_ID = "workout_live"
    const val GPS_NOTIFICATION_ID = 7_401
    const val STRENGTH_NOTIFICATION_ID = 7_402
    /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` (API 36.1); a plain extra is harmless on older releases. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    data class Action(@DrawableRes val icon: Int, val title: String, val intent: PendingIntent)

    data class Content(
        val title: String,
        val text: String,
        /** Wall-clock base for the chronometer; null hides it (paused). */
        val chronometerBaseMs: Long? = null,
        val countDown: Boolean = false,
        /** Progress within [0, max], shown as ProgressStyle on API 36. */
        val progress: Int? = null,
        val progressMax: Int = 1000,
        /** Short text for the promoted status-bar chip (API 36). */
        val chip: String? = null,
        val actions: List<Action> = emptyList(),
        @DrawableRes val icon: Int = R.drawable.ic_widget_walk
    )

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.workout_live_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.workout_live_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the workouts diary (ayuvo://open/workouts, handled by the actions deep-link router). */
    fun openWorkoutsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        40,
        Intent(Intent.ACTION_VIEW, Uri.parse("ayuvo://open/workouts"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun build(context: Context, c: Content): Notification {
        ensureChannel(context)
        val open = openWorkoutsIntent(context)
        if (Build.VERSION.SDK_INT >= 36) {
            val b = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(c.icon)
                .setContentTitle(c.title)
                .setContentText(c.text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            if (c.chronometerBaseMs != null) {
                b.setWhen(c.chronometerBaseMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(c.countDown)
            } else {
                b.setShowWhen(false)
            }
            c.chip?.let { b.setShortCriticalText(it) }
            if (c.progress != null) {
                b.setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(c.progressMax)))
                        .setProgress(c.progress.coerceIn(0, c.progressMax))
                )
            } else {
                b.setStyle(Notification.BigTextStyle().bigText(c.text))
            }
            for (a in c.actions) {
                b.addAction(Notification.Action.Builder(Icon.createWithResource(context, a.icon), a.title, a.intent).build())
            }
            b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            return b.build()
        }
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(c.icon)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (c.chronometerBaseMs != null) {
            b.setWhen(c.chronometerBaseMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(c.countDown)
        } else {
            b.setShowWhen(false)
        }
        if (c.progress != null) b.setProgress(c.progressMax, c.progress.coerceIn(0, c.progressMax), false)
        for (a in c.actions) b.addAction(a.icon, a.title, a.intent)
        return b.build()
    }

    // -- Strength session ------------------------------------------------------------------------

    /** Ongoing strength-session notification: elapsed timer plus Finish (docs/workouts-gps.md §1). */
    fun showStrength(context: Context, session: ActiveStrengthSession, text: String? = null) {
        if (!canPost(context)) return
        val finish = PendingIntent.getBroadcast(
            context, 41,
            Intent(context, WorkoutActionReceiver::class.java).setAction(WorkoutActionReceiver.ACTION_FINISH_STRENGTH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = build(
            context,
            Content(
                title = context.getString(R.string.workout_strength_live_title),
                text = text ?: context.getString(R.string.workout_strength_live_text),
                chronometerBaseMs = session.startedAt.toEpochMilli(),
                chip = null,
                actions = listOf(Action(R.drawable.ic_widget_fitness, context.getString(R.string.workout_action_finish), finish)),
                icon = R.drawable.ic_widget_fitness
            )
        )
        runCatching { NotificationManagerCompat.from(context).notify(STRENGTH_NOTIFICATION_ID, n) }
    }

    fun cancelStrength(context: Context) {
        NotificationManagerCompat.from(context).cancel(STRENGTH_NOTIFICATION_ID)
    }
}
