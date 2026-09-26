package com.ayuvo.health.insights

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.services.health.HealthSyncTrigger
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Morning Recovery refresh (docs/insights.md §6). Runs about hourly while the battery is not low,
 * but acts only between 05:00 and 12:00 local time and only with background Health Connect reads
 * granted: an incremental mirror sync, then today's Recovery, then (opt-in, once per day) the
 * value-free "Your recovery is ready" notification. Android decides when this actually runs.
 */
class InsightsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AyuvoApp ?: return Result.success()
        val c = app.container
        val now = LocalTime.now()
        if (!inMorningWindow(now)) return Result.success()
        if (!c.prefs.insightsEnabled.first() || !c.prefs.healthHubEnabled.first()) return Result.success()
        val backgroundRead = runCatching { c.health.isAvailable() && c.health.hasBackgroundRead() }.getOrDefault(false)
        if (!backgroundRead) return Result.success()
        runCatching { c.requestHealthSync(HealthSyncTrigger.APP_OPEN).await() }
            .onFailure { Log.w(TAG, "Background sync failed: ${it.javaClass.simpleName}") }
        val recovery = runCatching { c.insightsRepository.recoveryToday() }.getOrNull() ?: return Result.success()
        val today = LocalDate.now().toString()
        val post = shouldNotify(
            recoveryReady = recovery.ok,
            morningEnabled = c.prefs.insightsMorningNotification.first(),
            notificationsEnabled = c.prefs.notificationsEnabled.first(),
            canPost = c.notifications.canPostNotifications(),
            lastNotifiedDay = c.prefs.insightsMorningNotifiedDay.first(),
            today = today
        )
        if (post) {
            InsightsNotifications.postRecoveryReady(applicationContext)
            c.prefs.setInsightsMorningNotifiedDay(today)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "insights-morning-recovery"
        private const val TAG = "AyuvoInsights"
        const val WINDOW_START_HOUR = 5
        const val WINDOW_END_HOUR = 12

        fun inMorningWindow(t: LocalTime): Boolean = t.hour in WINDOW_START_HOUR until WINDOW_END_HOUR

        /** Once per day, only when opted in, allowed to post and Recovery is actually ready. */
        fun shouldNotify(
            recoveryReady: Boolean,
            morningEnabled: Boolean,
            notificationsEnabled: Boolean,
            canPost: Boolean,
            lastNotifiedDay: String?,
            today: String
        ): Boolean = recoveryReady && morningEnabled && notificationsEnabled && canPost && lastNotifiedDay != today

        fun onAppStarted(context: Context) {
            val request = PeriodicWorkRequestBuilder<InsightsWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
