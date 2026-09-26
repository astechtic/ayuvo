package com.ayuvo.health.insights

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ayuvo.health.MainActivity
import com.ayuvo.health.R
import com.ayuvo.health.services.notifySafely

/**
 * The two Insights notifications (docs/insights.md §4): they only say that something is ready and
 * never carry a score or any other value, so nothing health-related shows on the lock screen.
 * Taps open the screen through the action deep links (`ayuvo://action/…`).
 */
object InsightsNotifications {
    const val CHANNEL_INSIGHTS = "insights"
    const val RECOVERY_NOTIFICATION_ID = 7101
    const val RECOVERY_LINK = "ayuvo://action/insights.recovery.get"
    const val REVIEW_LINK = "ayuvo://action/insights.dailyReview.get?day=today"
    private const val REQUEST_RECOVERY = 7102
    private const val REQUEST_REVIEW = 7103

    /** Value-free content: string resources only, no format arguments. */
    data class Content(val titleRes: Int, val textRes: Int, val link: String)

    val RECOVERY = Content(R.string.notif_recovery_ready_title, R.string.notif_recovery_ready_text, RECOVERY_LINK)
    val REVIEW = Content(R.string.notif_review_ready_title, R.string.notif_review_ready_text, REVIEW_LINK)

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_INSIGHTS, context.getString(R.string.notif_channel_insights), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notif_channel_insights_desc) }
        )
    }

    /** Opens [link] in MainActivity, or plainly opens the app when Insights is switched off. */
    fun contentIntent(context: Context, link: String?, requestCode: Int): PendingIntent {
        val intent = if (link != null) {
            Intent(Intent.ACTION_VIEW, link.toUri(), context, MainActivity::class.java)
        } else {
            Intent(context, MainActivity::class.java)
        }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun build(context: Context, channel: String, content: Content, link: String?, requestCode: Int): android.app.Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(content.titleRes))
            .setContentText(context.getString(content.textRes))
            .setContentIntent(contentIntent(context, link, requestCode))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

    fun postRecoveryReady(context: Context) {
        ensureChannel(context)
        val n = build(context, CHANNEL_INSIGHTS, RECOVERY, RECOVERY_LINK, REQUEST_RECOVERY)
        NotificationManagerCompat.from(context).notifySafely(context, RECOVERY_NOTIFICATION_ID, n)
    }

    /** The Daily Review notification on the existing daily channel; [insightsOn] picks the deep link. */
    fun dailyReview(context: Context, channel: String, insightsOn: Boolean): android.app.Notification =
        build(context, channel, REVIEW, if (insightsOn) REVIEW_LINK else null, REQUEST_REVIEW)
}
