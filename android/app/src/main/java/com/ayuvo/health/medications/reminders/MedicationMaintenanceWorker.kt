package com.ayuvo.health.medications.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayuvo.health.AyuvoApp
import java.util.concurrent.TimeUnit

/**
 * Safety net for the alarm path (docs/medications.md §10): every 12 hours it materializes
 * missed doses, auto-completes ended medicines and re-arms the alarm, so a dropped alarm never
 * silences reminders for longer than half a day. Mirrors `WidgetRefreshWorker`.
 */
class MedicationMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AyuvoApp ?: return Result.success()
        if (!app.container.medicationsDatabaseExists()) return Result.success()
        runCatching { app.container.medicationReminders.replan() }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "medication-maintenance"

        fun onAppStarted(context: Context) {
            val request = PeriodicWorkRequestBuilder<MedicationMaintenanceWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
