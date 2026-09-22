package com.ayuvo.health.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayuvo.health.AyuvoApp
import java.util.concurrent.TimeUnit

/** Retries Glance rendering after launcher/OEM background scheduling interruptions. */
class WidgetRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "Widget refresh started (attempt ${runAttemptCount + 1})")
        if (!WidgetRefreshScheduler.hasInstalledWidgets(applicationContext)) {
            Log.i(TAG, "Widget refresh skipped because no widgets are installed")
            return Result.success()
        }

        // Today / My Metrics read a snapshot the app writes; rebuild it here so they stay current
        // while the app is not running (docs/widgets.md "Freshness").
        (applicationContext as? AyuvoApp)?.container?.widgetDashboardWriter?.let { writer ->
            runCatching { writer.publishOnce() }.onFailure { Log.e(TAG, "Dashboard snapshot refresh failed", it) }
        }

        return if (WidgetUpdateCoordinator.updateAll(applicationContext)) {
            Log.i(TAG, "Widget refresh requests completed")
            Result.success()
        } else if (runAttemptCount < MAX_RETRIES) {
            Log.w(TAG, "Widget refresh will retry")
            Result.retry()
        } else {
            Log.e(TAG, "Widget refresh exhausted retries")
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "AyuvoWidget"
        const val MAX_RETRIES = 3
    }
}

object WidgetRefreshScheduler {
    private const val MIN_BOUNDARY_DELAY_MS = 60_000L
    private const val TAG = "AyuvoWidget"
    private const val IMMEDIATE_WORK = "ayuvo_widget_refresh"
    private const val PERIODIC_WORK = "ayuvo_widget_periodic_refresh"
    private const val BOUNDARY_WORK = "ayuvo_widget_boundary_refresh"

    private val receiverClasses = listOf(
        CalorieWidgetReceiver::class.java,
        ProteinWidgetReceiver::class.java,
        AllMetricsWidgetReceiver::class.java,
        WaterWidgetReceiver::class.java,
        TodayWidgetReceiver::class.java,
        MyMetricsWidgetReceiver::class.java,
        QuickLogWidgetReceiver::class.java
    )

    /** Widgets that read the dashboard snapshot (Quick Log uses its water/fasting state). */
    private val dashboardReceivers = listOf(
        TodayWidgetReceiver::class.java,
        MyMetricsWidgetReceiver::class.java,
        QuickLogWidgetReceiver::class.java
    )

    fun onAppStarted(context: Context) {
        if (!hasInstalledWidgets(context)) return
        ensurePeriodic(context)
        enqueueImmediate(context, "app_start")
    }

    fun onWidgetEnabled(context: Context) {
        ensurePeriodic(context)
        enqueueImmediate(context, "widget_enabled")
    }

    fun enqueueImmediate(context: Context, reason: String) {
        Log.i(TAG, "Enqueueing widget refresh: $reason")
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * One-time refresh at the next dose time, fasting goal or midnight, so Today and My Metrics
     * roll over without the app. Replaces any earlier boundary request.
     */
    fun scheduleBoundary(context: Context, atMs: Long) {
        val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(MIN_BOUNDARY_DELAY_MS)
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(BOUNDARY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun stopIfUnused(context: Context) {
        if (hasInstalledWidgets(context)) return
        val work = WorkManager.getInstance(context.applicationContext)
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelUniqueWork(BOUNDARY_WORK)
    }

    fun hasDashboardWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return dashboardReceivers.any { receiver ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }

    fun hasInstalledWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return receiverClasses.any { receiver ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }
}
