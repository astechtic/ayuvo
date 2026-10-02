package com.ayuvo.health.services.health

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ayuvo.health.data.PreferencesStore
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only IDs enter WorkManager; retries always load the latest local value, never an old payload. */
class HealthWriteRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        if (kind == KIND_GOOGLE_HEALTH) return retryGoogleHealth()
        val id = runCatching { UUID.fromString(inputData.getString("id")) }.getOrNull()
            ?: return Result.failure()
        com.ayuvo.health.data.health.ManualVitalType.byRetryKind(kind)?.let { return retryManualVital(it, id) }
        val prefs = PreferencesStore(applicationContext)
        val health = HealthConnectManager(applicationContext, scheduleRetries = false)
        val enabled = prefs.healthConnectEnabled.first()
        val deletionRequested = inputData.getBoolean("delete", false)
        if (!enabled && !deletionRequested) return Result.success()
        if (!health.isAvailable()) {
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
        val success = when (kind) {
            "nutrition" -> retryLatestHealthEntry(
                enabled, deletionRequested,
                permitted = { health.hasNutritionWrite() },
                latest = { prefs.foodEntries.first().firstOrNull { it.id == id } },
                upsert = { health.writeNutrition(it) },
                delete = { health.deleteNutrition(id) }
            )
            "weight" -> retryLatestHealthEntry(
                enabled, deletionRequested,
                permitted = { health.hasWeightWrite() },
                latest = { prefs.weightEntries.first().firstOrNull { it.id == id } },
                upsert = { health.writeWeight(it) },
                delete = { health.deleteWeight(id) }
            )
            "bodyFat" -> retryLatestHealthEntry(
                enabled, deletionRequested,
                permitted = { health.hasBodyFatWrite() },
                latest = { prefs.bodyFatEntries.first().firstOrNull { it.id == id } },
                upsert = { health.writeBodyFat(it) },
                delete = { health.deleteBodyFat(id) }
            )
            else -> return Result.failure()
        }
        if (success) return Result.success()
        if (runAttemptCount < 5) return Result.retry()
        Log.w("AyuvoHealth", "Health Connect write retries exhausted ($kind)")
        return Result.failure()
    }

    /** Manual glucose / temperature (docs/health-data.md §2.2): the health_samples row is the latest value. */
    private suspend fun retryManualVital(type: com.ayuvo.health.data.health.ManualVitalType, id: UUID): Result {
        val container = (applicationContext as? com.ayuvo.health.AyuvoApp)?.container ?: return Result.failure()
        val health = HealthConnectManager(applicationContext, scheduleRetries = false)
        val enabled = container.prefs.healthConnectEnabled.first()
        val deletionRequested = inputData.getBoolean("delete", false)
        if (!enabled && !deletionRequested) return Result.success()
        if (!health.isAvailable()) return if (runAttemptCount < 5) Result.retry() else Result.failure()
        val success = retryLatestHealthEntry(
            enabled, deletionRequested,
            permitted = { health.hasManualVitalWrite(type) },
            latest = { container.manualVitals.liveRow(id) },
            upsert = { health.writeManualVital(type, it) },
            delete = { health.deleteManualVital(type, id) }
        )
        if (success) return Result.success()
        return if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    /** Google Health write-back (docs/google-health.md §4): re-flushes whatever is still pending. */
    private suspend fun retryGoogleHealth(): Result {
        val container = (applicationContext as? com.ayuvo.health.AyuvoApp)?.container ?: return Result.failure()
        val result = container.googleHealth.flushMirror() ?: return if (runAttemptCount < 5) Result.retry() else Result.failure()
        if (!result.deferred) return Result.success()
        return if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        private const val KIND_GOOGLE_HEALTH = "googleHealth"
        /** One serial queue for the whole Google Health write-back (it is keyed by rows, not entries). */
        private val GOOGLE_HEALTH_ID: UUID = UUID.fromString("6f1c5d2e-9a4b-4c3d-8e7f-0a1b2c3d4e5f")

        fun enqueueGoogleHealth(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthWriteRetryWorker>()
                .setInputData(workDataOf("kind" to KIND_GOOGLE_HEALTH, "id" to GOOGLE_HEALTH_ID.toString(), "delete" to false))
                .setInitialDelay(60, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "health_write_${KIND_GOOGLE_HEALTH}", ExistingWorkPolicy.KEEP, request
            )
        }

        fun enqueue(context: Context, kind: String, id: UUID, delete: Boolean) {
            val request = OneTimeWorkRequestBuilder<HealthWriteRetryWorker>()
                .setInputData(workDataOf("kind" to kind, "id" to id.toString(), "delete" to delete))
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            // Serial work for an entity prevents concurrent retry workers. New
            // mutations can still enqueue after a failed or cancelled retry.
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "health_write_${kind}_$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
