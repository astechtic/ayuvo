package com.ayuvo.health.partner

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.partner.sync.SyncTrigger
import java.util.concurrent.TimeUnit

/**
 * Background sync windows (docs/partner-sync.md §11, Android): a periodic 1 h job on an unmetered network, enqueued
 * only while at least one partner exists, plus a one-shot job that WorkManager starts when an unmetered network
 * becomes available after a window found none. Each run opens one ≤ 60 s window and returns; WorkManager's own
 * constraints do the waiting (no callbacks or sockets are kept alive). Doze and App Standby defer these runs.
 */
class PartnerSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? AyuvoApp ?: return Result.success()
        val container = app.container
        if (!container.partnerDatabaseExists()) {
            cancel(applicationContext)
            return Result.success()
        }
        val trigger = if (tags.contains(TAG_NETWORK)) SyncTrigger.NETWORK else SyncTrigger.WORKER
        runCatching { container.partnerManager.runWindow(trigger) }
        return Result.success()
    }

    companion object {
        const val WORK_PERIODIC = "partner_sync_periodic"
        const val WORK_NETWORK = "partner_sync_network"
        private const val TAG_NETWORK = "partner_sync_network"

        private fun unmetered() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        /** Enqueue while partners exist, cancel when none (idempotent). */
        fun update(context: Context, hasPartners: Boolean) {
            if (hasPartners) schedule(context) else cancel(context)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PartnerSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(unmetered())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One window as soon as an unmetered network is available (after a window found no network). */
        fun scheduleOnNetwork(context: Context) {
            val request = OneTimeWorkRequestBuilder<PartnerSyncWorker>()
                .setConstraints(unmetered())
                .addTag(TAG_NETWORK)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NETWORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(WORK_PERIODIC)
            wm.cancelUniqueWork(WORK_NETWORK)
        }
    }
}
