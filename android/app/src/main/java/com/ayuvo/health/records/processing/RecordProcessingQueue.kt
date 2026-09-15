package com.ayuvo.health.records.processing

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.ProcessingJob
import com.ayuvo.health.records.model.ProcessingStage
import java.util.concurrent.TimeUnit

/**
 * Background processing (docs/health-records.md §9): WorkManager unique work `records_process`
 * (APPEND_OR_REPLACE, record ids only in the input, one record at a time), the stage machine
 * persisted in `processing_jobs`, resumed on app start. AI stages that must wait (offline or
 * retry backoff) are re-run by a separate unique work `records_process_ai`, with a CONNECTED
 * constraint when they need the network. Existing Phase 1 records are backfilled once at low
 * priority (`records_backfill`).
 */
class RecordProcessingQueue(
    private val context: Context,
    private val store: () -> RecordsStore,
    private val pipeline: () -> RecordPipeline
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** New imports (and re-processing) start at [stage]. */
    suspend fun enqueue(ids: Collection<String>, stage: ProcessingStage = ProcessingStage.TEXT) {
        if (ids.isEmpty()) return
        val existing = store().enqueueJobs(ids, stage)
        schedule(existing)
    }

    /** Runs already-stored jobs (e.g. after a consent decision or a split) without resetting them. */
    fun schedule(ids: Collection<String>) {
        ids.chunked(MAX_IDS_PER_REQUEST).forEach { chunk ->
            val request = OneTimeWorkRequestBuilder<RecordProcessingWorker>()
                .setInputData(Data.Builder().putStringArray(KEY_IDS, chunk.toTypedArray()).build())
                .addTag(TAG_WORK)
                .build()
            workManager.enqueueUniqueWork(WORK_PROCESS, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    /** Re-runs the `ai` stage of [ids] later; CONNECTED when [needsNetwork]. */
    fun scheduleAi(ids: Collection<String>, delayMs: Long, needsNetwork: Boolean) {
        if (ids.isEmpty()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<RecordProcessingWorker>()
            .setInputData(Data.Builder().putStringArray(KEY_IDS, ids.toTypedArray()).putBoolean(KEY_AI_RETRY, true).build())
            .setConstraints(constraints)
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_WORK)
            .build()
        workManager.enqueueUniqueWork(WORK_AI, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Record deletion: running work skips the ids; job rows cascade with the records. */
    fun cancel(ids: Collection<String>) {
        pipeline().cancel(ids)
    }

    /**
     * App start: resume every unfinished job (waiting AI retries keep their delay; consent waits
     * stay parked) and, once per database, backfill records imported before Phase 2.
     */
    suspend fun resumeOnStart() {
        val s = store()
        if (s.meta(META_BACKFILL) == null) {
            val legacy = s.recordIdsWithoutJob()
            if (legacy.isNotEmpty()) {
                s.enqueueJobs(legacy, ProcessingStage.TEXT)
                val request = OneTimeWorkRequestBuilder<RecordProcessingWorker>()
                    .setInputData(Data.Builder().putBoolean(KEY_DRAIN, true).build())
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .addTag(TAG_WORK)
                    .build()
                workManager.enqueueUniqueWork(WORK_BACKFILL, ExistingWorkPolicy.KEEP, request)
            }
            s.setMeta(META_BACKFILL, System.currentTimeMillis().toString())
        }
        val now = System.currentTimeMillis()
        val jobs = s.unfinishedJobs().filter { !it.awaitingConsent }
        val (later, due) = jobs.partition { it.stage == ProcessingStage.AI && it.nextAttemptMs > now }
        schedule(due.map(ProcessingJob::recordId))
        later.forEach { scheduleAi(listOf(it.recordId), it.nextAttemptMs - now, needsNetwork = true) }
    }

    /** Runs one batch inside the worker; returns ids whose AI stage must be re-run later. */
    internal suspend fun runBatch(ids: List<String>, drain: Boolean) {
        val s = store()
        val targets = if (drain) s.unfinishedJobs().filter { !it.awaitingConsent && it.nextAttemptMs <= System.currentTimeMillis() }.map { it.recordId } else ids
        for (id in targets) {
            when (val outcome = pipeline().process(id)) {
                is PipelineOutcome.RetryAi -> scheduleAi(listOf(id), outcome.delayMs, outcome.needsNetwork)
                else -> Unit
            }
        }
    }

    companion object {
        const val WORK_PROCESS = "records_process"
        const val WORK_AI = "records_process_ai"
        const val WORK_BACKFILL = "records_backfill"
        const val TAG_WORK = "records_processing"
        const val KEY_IDS = "ids"
        const val KEY_AI_RETRY = "ai_retry"
        const val KEY_DRAIN = "drain"
        const val META_BACKFILL = "p2_backfill_done_ms"
        /** WorkManager Data is capped at 10 KB; 36-char UUIDs fit comfortably in 150s. */
        const val MAX_IDS_PER_REQUEST = 150
    }
}

/** Processes the record ids in its input one at a time through [RecordPipeline]. */
class RecordProcessingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? AyuvoApp ?: return Result.success()
        val ids = inputData.getStringArray(RecordProcessingQueue.KEY_IDS)?.toList().orEmpty()
        val drain = inputData.getBoolean(RecordProcessingQueue.KEY_DRAIN, false)
        return try {
            app.container.recordsQueue.runBatch(ids, drain)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Each record already persists its own stage; a crashed batch resumes on the next start.
            Log.w("AyuvoRecords", "Processing batch failed: ${e.javaClass.simpleName}")
            Result.success()
        }
    }
}
