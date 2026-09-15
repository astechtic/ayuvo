package com.ayuvo.health.records.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.ayuvo.health.records.ai.AiExtractionRequest
import com.ayuvo.health.records.ai.AiPageImage
import com.ayuvo.health.records.ai.AiPageText
import com.ayuvo.health.records.ai.AiResolution
import com.ayuvo.health.records.ai.RecordsAiException
import com.ayuvo.health.records.ai.RecordsAiExtractor
import com.ayuvo.health.records.ai.RecordsAiModeResolver
import com.ayuvo.health.records.ai.RecordsAiTarget
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.AiModeUsed
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.ProcessingError
import com.ayuvo.health.records.model.ProcessingJob
import com.ayuvo.health.records.model.ProcessingStage
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.model.SplitProposal
import com.ayuvo.health.records.model.SplitStatus
import com.ayuvo.health.services.FoodImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Collections
import java.util.UUID

/** What the queue should do after [RecordPipeline.process] returns. */
sealed interface PipelineOutcome {
    data object Done : PipelineOutcome
    data object Missing : PipelineOutcome
    data object Cancelled : PipelineOutcome
    /** The `ai` stage waits: re-run it after [delayMs], only when online if [needsNetwork]. */
    data class RetryAi(val delayMs: Long, val needsNetwork: Boolean) : PipelineOutcome
    data object AwaitingConsent : PipelineOutcome
}

/** Progress of the record being processed, for the home strip ("2 of 14 pages"). */
data class PipelineProgress(val recordId: String, val pagesDone: Int, val pagesTotal: Int)

/**
 * Stage machine of docs/health-records.md §9. Each stage commits through the store (its own
 * transaction, bumping the revision) and then advances `processing_jobs.stage`, so a crash
 * resumes at the stored stage. One record at a time across every caller ([lock]).
 *
 * The `ai` stage, when it cannot run now (Ask mode, offline, retry backoff), lets every later
 * non-AI stage run and then parks the job back at `ai`; running it later repeats the cheap,
 * idempotent tail. The `validate` stage is executed inside the `ai` stage's commit (raw AI replies
 * are never persisted), so on its own it only advances.
 */
class RecordPipeline(
    private val store: () -> RecordsStore,
    private val files: RecordFileStore,
    private val textStage: () -> TextStage,
    private val rules: () -> RecordRules,
    private val aiResolver: () -> RecordsAiModeResolver,
    private val aiExtractor: () -> RecordsAiExtractor,
    private val aiPreference: suspend () -> RecordsAiMode?,
    private val isOnline: () -> Boolean,
    private val typeLabel: (RecordType) -> String?,
    /** User-facing provider name stored in `records.ai_provider` at processing time (§8). */
    private val providerLabel: (com.ayuvo.health.models.AIProvider) -> String = { it.name },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val dateOrder: () -> DateOrder = { RecordRules.deviceDateOrder() }
) {
    private val lock = Mutex()
    private val cancelled = Collections.synchronizedSet(HashSet<String>())

    private val _progress = MutableStateFlow<PipelineProgress?>(null)
    val progress: StateFlow<PipelineProgress?> = _progress.asStateFlow()

    /** Record deletion: stop between stages/pages; the job row cascades with the record. */
    fun cancel(ids: Collection<String>) {
        cancelled.addAll(ids)
    }

    suspend fun process(recordId: String): PipelineOutcome = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                runStages(recordId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecordDeletedException) {
                PipelineOutcome.Cancelled
            } catch (e: Exception) {
                if (store().record(recordId) == null) return@withContext PipelineOutcome.Missing
                Log.w(TAG, "Processing stopped: ${e.javaClass.simpleName}", e)
                // Never lose the record: mark it usable and leave the job where it stopped for the next start.
                runCatching {
                    val s = store()
                    s.job(recordId)?.let { job -> s.saveJob(job.copy(attempts = job.attempts + 1, lastError = e.javaClass.simpleName, updatedMs = now())) }
                    val job = s.job(recordId)
                    if (job != null && job.attempts >= MAX_STAGE_CRASHES) {
                        s.saveJob(job.copy(stage = ProcessingStage.DONE, updatedMs = now()))
                        s.setStatus(recordId, ProcessingStatus.FAILED_PARTIAL, s.record(recordId)?.processingError ?: ProcessingError.TEXT_UNAVAILABLE)
                    } else {
                        s.setStatus(recordId, ProcessingStatus.READY, keepError = true)
                    }
                }
                PipelineOutcome.Done
            } finally {
                _progress.value = null
                cancelled.remove(recordId)
            }
        }
    }

    private suspend fun runStages(recordId: String): PipelineOutcome {
        val s = store()
        var deferred: PipelineOutcome? = null
        while (true) {
            if (recordId in cancelled) return PipelineOutcome.Cancelled
            val job = s.job(recordId) ?: return PipelineOutcome.Missing
            val record = s.record(recordId) ?: return PipelineOutcome.Missing
            if (job.stage == ProcessingStage.DONE) return PipelineOutcome.Done
            val next: ProcessingStage = when (job.stage) {
                ProcessingStage.TEXT -> {
                    s.setStatus(recordId, ProcessingStatus.EXTRACTING_TEXT, keepError = true)
                    val outcome = textStage().run(record) { done, total ->
                        _progress.value = PipelineProgress(recordId, done, total)
                        if (recordId in cancelled) throw RecordDeletedException()
                    }
                    if (outcome.error != null) s.setStatus(recordId, ProcessingStatus.EXTRACTING_TEXT, outcome.error)
                    else if (record.processingError in TEXT_ERRORS || record.processingError == LEGACY_UNREADABLE) s.setStatus(recordId, ProcessingStatus.EXTRACTING_TEXT, null)
                    ProcessingStage.CLASSIFY
                }
                ProcessingStage.CLASSIFY -> {
                    s.setStatus(recordId, ProcessingStatus.ANALYZING, keepError = true)
                    val texts = pageTexts(s.pages(recordId))
                    if (texts.isNotEmpty() && s.columns(recordId)?.typeMethod != ExtractionMethod.USER) {
                        val result = rules().classify(texts.map { it.second })
                        s.setClassification(recordId, result.type, result.type.defaultCategory, result.confidence, ExtractionMethod.RULES)
                    }
                    ProcessingStage.BOUNDARIES
                }
                ProcessingStage.BOUNDARIES -> {
                    val eligible = !record.isSplitChild && (record.fileType == RecordFileType.PDF || record.source == RecordSource.SCAN)
                    if (eligible) {
                        val pages = s.pages(recordId).sortedBy { it.pageIndex }
                        if (pages.size >= 3) {
                            val segments = rules().boundaries(pages.map { it.text.orEmpty() })
                            s.saveSplitProposal(if (segments.size >= 2) SplitProposal(recordId, segments) else null, recordId)
                        }
                    }
                    ProcessingStage.RULES
                }
                ProcessingStage.RULES -> {
                    val texts = pageTexts(s.pages(recordId))
                    if (texts.isNotEmpty()) {
                        val fresh = s.record(recordId) ?: return PipelineOutcome.Missing
                        val extracted = rules().extract(texts.map { it.second }, fresh.recordType, today(), dateOrder())
                            .map { f -> f.copy(sourcePage = f.sourcePage?.let { texts.getOrNull(it)?.first ?: it }) }
                        s.applyExtraction(recordId, extracted, typeLabel)
                    }
                    ProcessingStage.AI
                }
                ProcessingStage.AI -> {
                    val aiOutcome = runAi(record, job)
                    if (aiOutcome != null) deferred = aiOutcome
                    ProcessingStage.VALIDATE
                }
                ProcessingStage.VALIDATE -> ProcessingStage.HIGHLIGHTS
                ProcessingStage.HIGHLIGHTS -> {
                    val fields = s.fields(recordId)
                    val drafts = HighlightBuilder.build(fields)
                    s.replaceHighlights(
                        recordId,
                        setOf(HighlightSection.IMPORTANT, HighlightSection.MEDICATIONS, HighlightSection.RECOMMENDATIONS),
                        setOf(ExtractionMethod.RULES, ExtractionMethod.AI_LOCAL, ExtractionMethod.AI_CLOUD),
                        drafts.map { d ->
                            RecordHighlight(
                                id = UUID.randomUUID().toString(),
                                recordId = recordId,
                                section = d.section,
                                text = d.text,
                                method = ExtractionMethod.RULES,
                                fieldId = d.fieldId,
                                sourcePage = d.sourcePage,
                                confidence = d.confidence,
                                position = d.position
                            )
                        }
                    )
                    ProcessingStage.REVIEW
                }
                ProcessingStage.OBSERVATIONS -> {
                    // §19 promotion + mapping (also done inside applyExtraction; re-run for aliases and dates).
                    s.refreshKnowledge(recordId)
                    ProcessingStage.RELATIONS
                }
                ProcessingStage.REVIEW -> {
                    evaluateReview(recordId)
                    ProcessingStage.NEAR_DUPLICATE
                }
                ProcessingStage.NEAR_DUPLICATE -> {
                    runCatching { nearDuplicates(record) }.onFailure { if (it is CancellationException) throw it }
                    ProcessingStage.OBSERVATIONS
                }
                ProcessingStage.RELATIONS -> {
                    runCatching { s.suggestRelations(recordId, today()) }.onFailure { if (it is CancellationException) throw it }
                    ProcessingStage.INDEX
                }
                ProcessingStage.INDEX -> {
                    s.reindex(recordId)
                    ProcessingStage.DONE
                }
                ProcessingStage.DONE -> ProcessingStage.DONE
            }
            if (next == ProcessingStage.DONE) {
                return finish(recordId, deferred)
            }
            val current = s.job(recordId) ?: return PipelineOutcome.Missing
            s.saveJob(current.copy(stage = next, updatedMs = now()))
        }
    }

    /** Ends a pass: parks a deferred `ai` stage, or marks the job done and the record ready. */
    private suspend fun finish(recordId: String, deferred: PipelineOutcome?): PipelineOutcome {
        val s = store()
        val job = s.job(recordId) ?: return PipelineOutcome.Missing
        val record = s.record(recordId) ?: return PipelineOutcome.Missing
        val error = record.processingError
        val failed = error != null && error != ProcessingError.AI_UNAVAILABLE && error != LEGACY_UNREADABLE
        return when (deferred) {
            is PipelineOutcome.AwaitingConsent -> {
                s.saveJob(job.copy(stage = ProcessingStage.AI, awaitingConsent = true, updatedMs = now()))
                s.setStatus(recordId, ProcessingStatus.AI_PENDING_CONSENT, keepError = true)
                deferred
            }
            is PipelineOutcome.RetryAi -> {
                s.saveJob(job.copy(stage = ProcessingStage.AI, nextAttemptMs = now() + deferred.delayMs, updatedMs = now()))
                s.setStatus(recordId, if (failed) ProcessingStatus.FAILED_PARTIAL else ProcessingStatus.READY, keepError = true)
                deferred
            }
            else -> {
                s.saveJob(job.copy(stage = ProcessingStage.DONE, awaitingConsent = false, nextAttemptMs = 0, updatedMs = now()))
                s.setStatus(recordId, if (failed) ProcessingStatus.FAILED_PARTIAL else ProcessingStatus.READY, keepError = true)
                PipelineOutcome.Done
            }
        }
    }

    /** Returns a deferral, or null when the stage is finished (with or without AI). */
    private suspend fun runAi(record: HealthRecord, job: ProcessingJob): PipelineOutcome? {
        val s = store()
        val pages = s.pages(record.id).sortedBy { it.pageIndex }
        val fields = s.fields(record.id)
        val fresh = s.record(record.id) ?: record
        if (!AiGaps.hasGap(fresh, fields, pages, s.columns(record.id)?.typeConfidence)) {
            if (job.awaitingConsent) s.saveJob(job.copy(awaitingConsent = false, updatedMs = now()))
            return null
        }
        val resolution = aiResolver().resolve(aiPreference(), job.requestedMode)
        return when (resolution) {
            is AiResolution.Off -> {
                if (job.awaitingConsent) s.saveJob(job.copy(awaitingConsent = false, updatedMs = now()))
                null
            }
            is AiResolution.Unavailable -> {
                if (fresh.processingError == null) s.setStatus(record.id, ProcessingStatus.ANALYZING, ProcessingError.AI_UNAVAILABLE)
                null
            }
            is AiResolution.Ask -> PipelineOutcome.AwaitingConsent
            is AiResolution.Run -> {
                if (resolution.target == RecordsAiTarget.CLOUD && !isOnline()) {
                    return PipelineOutcome.RetryAi(0, needsNetwork = true)
                }
                if (job.nextAttemptMs > now()) return PipelineOutcome.RetryAi(job.nextAttemptMs - now(), needsNetwork = resolution.target == RecordsAiTarget.CLOUD)
                try {
                    applyAi(fresh, pages, resolution)
                    s.saveJob((s.job(record.id) ?: job).copy(attempts = 0, nextAttemptMs = 0, lastError = null, awaitingConsent = false, updatedMs = now()))
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val kind = (e as? RecordsAiException)?.kind
                    if (kind == RecordsAiException.Kind.OFFLINE) return PipelineOutcome.RetryAi(0, needsNetwork = true)
                    if (kind == RecordsAiException.Kind.UNAVAILABLE) {
                        if (fresh.processingError == null) s.setStatus(record.id, ProcessingStatus.ANALYZING, ProcessingError.AI_UNAVAILABLE)
                        return null
                    }
                    val attempts = job.attempts + 1
                    val delay = AI_RETRY_DELAYS_MS.getOrNull(attempts - 1)
                    s.saveJob(job.copy(attempts = attempts, lastError = kind?.name ?: e.javaClass.simpleName, awaitingConsent = false, updatedMs = now()))
                    if (delay != null) {
                        PipelineOutcome.RetryAi(delay, needsNetwork = resolution.target == RecordsAiTarget.CLOUD)
                    } else {
                        if (fresh.processingError == null) s.setStatus(record.id, ProcessingStatus.ANALYZING, ProcessingError.AI_FAILED)
                        null
                    }
                }
            }
        }
    }

    private suspend fun applyAi(record: HealthRecord, pages: List<RecordPage>, run: AiResolution.Run) {
        val s = store()
        // Positions in the record's (or segment's) page list: the prompt numbers them 1-based and the
        // validator answers with those positions, mapped back to page indexes below.
        val ordered = pages.sortedBy { it.pageIndex }
        val textPositions = ordered.indices.filter { RecordText.letterCount(ordered[it].text) >= TextStage.MIN_TEXT_LETTERS }
        val imagePositions = ordered.indices.filter { it !in textPositions }
        val images = if (imagePositions.isEmpty()) emptyList() else renderAiImages(record, imagePositions.associateBy { ordered[it].pageIndex })
        val reply = aiExtractor().extract(
            AiExtractionRequest(
                recordType = record.recordType.raw,
                pages = textPositions.map { AiPageText(it, ordered[it].text.orEmpty()) },
                imagePages = images
            ),
            run.target
        )
        val method = if (run.target == RecordsAiTarget.LOCAL) ExtractionMethod.AI_LOCAL else ExtractionMethod.AI_CLOUD
        val pageTexts = ordered.indices.associateWith { ordered[it].text.orEmpty() }
        val imageSet = images.map { it.index }.toSet()
        val validatedFields = mutableListOf<ExtractedField>()
        var summary: String? = null
        var aiType: RecordType? = null
        for (response in reply.responses) {
            val validated = ExtractionValidator.validate(response, pageTexts, imageSet, method, reply.providerName, today(), dateOrder())
            validatedFields += validated.fields.map { f -> f.copy(sourcePage = f.sourcePage?.let { pos -> ordered.getOrNull(pos)?.pageIndex ?: pos }) }
            if (summary == null) summary = validated.summary
            if (aiType == null) aiType = validated.recordType?.takeIf { it != RecordType.OTHER }
        }
        s.applyExtraction(record.id, validatedFields, typeLabel)
        val providerDisplay = providerLabel(reply.provider)
        s.setAiUsed(record.id, if (run.target == RecordsAiTarget.LOCAL) AiModeUsed.LOCAL else AiModeUsed.CLOUD, providerDisplay)
        s.replaceHighlights(
            record.id,
            setOf(HighlightSection.SUMMARY),
            setOf(ExtractionMethod.AI_LOCAL, ExtractionMethod.AI_CLOUD),
            listOfNotNull(summary?.let {
                RecordHighlight(
                    id = UUID.randomUUID().toString(),
                    recordId = record.id,
                    section = HighlightSection.SUMMARY,
                    text = it,
                    method = method,
                    provider = providerDisplay,
                    confidence = 0.5
                )
            })
        )
        // Classification from AI only fills an unknown type; rules and the user always win.
        val type = aiType
        if (record.recordType == RecordType.OTHER && type != null) {
            s.setClassification(record.id, type, type.defaultCategory, AI_TYPE_CONFIDENCE, method)
        }
    }

    /** JPEGs (≤ 1600 px) for pages without usable text; [positions] maps page index → list position. */
    private fun renderAiImages(record: HealthRecord, positions: Map<Int, Int>): List<AiPageImage> {
        val file = files.resolve(record.filePath)?.takeIf { it.isFile } ?: return emptyList()
        return when (record.fileType) {
            RecordFileType.IMAGE -> FoodImageDecoder.decode(file, AI_IMAGE_DIMENSION)?.let { listOf(AiPageImage(0, it.toJpeg())) }.orEmpty()
            RecordFileType.PDF -> runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        positions.entries.filter { it.key in 0 until renderer.pageCount }.take(MAX_AI_IMAGE_PAGES).map { (index, position) ->
                            renderer.openPage(index).use { page ->
                                val longest = maxOf(page.width, page.height).coerceAtLeast(1)
                                val scale = AI_IMAGE_DIMENSION.toFloat() / longest
                                val bmp = createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1))
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                AiPageImage(position, bmp.toJpeg())
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }

    private fun Bitmap.toJpeg(): ByteArray = try {
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    } finally {
        recycle()
    }

    /**
     * One-time Phase 3 backfill for a record processed before v3: promotion, entities, relation
     * suggestions and the FTS row, without re-running text or rules.
     */
    suspend fun backfillKnowledge(recordId: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val s = store()
            if (s.record(recordId) == null) return@withContext
            s.refreshKnowledge(recordId)
            runCatching { s.suggestRelations(recordId, today()) }
        }
    }

    /** Recomputes `review_status` (§15); an explicit `reviewed` survives unless something new is pending. */
    suspend fun evaluateReview(recordId: String) {
        val s = store()
        val record = s.record(recordId) ?: return
        val intelligence = s.intelligence(recordId) ?: return
        val status = ReviewRules.evaluate(
            ReviewInput(
                recordType = record.recordType,
                typeConfidence = intelligence.columns.typeConfidence,
                documentDate = record.documentDate,
                fields = intelligence.fields,
                pendingSplit = intelligence.split?.status == SplitStatus.PENDING,
                pendingDuplicate = intelligence.duplicates.isNotEmpty(),
                processingError = record.processingError,
                hasAiImageItems = intelligence.fields.any { it.method.isAi && it.state == FieldState.SUGGESTED && it.confidence <= AI_IMAGE_CONFIDENCE },
                currentStatus = record.reviewStatus,
                typeMethod = intelligence.columns.typeMethod?.raw,
                ocrConfidenceMean = s.pages(recordId).filter { it.textSource == com.ayuvo.health.records.model.TextSource.OCR }
                    .mapNotNull { it.ocrConfidence }.takeIf { it.isNotEmpty() }?.average()
            )
        )
        if (status != record.reviewStatus) s.setReviewStatus(listOf(recordId), status)
    }

    private suspend fun nearDuplicates(record: HealthRecord) {
        if (record.isSplitChild) return
        val s = store()
        val folded = RecordText.fold(s.pages(record.id).sortedBy { it.pageIndex }.joinToString("\n") { it.text.orEmpty() })
        val signature = NearDuplicate.textSignature(folded)
        val phash = firstPageGray(record)?.let(NearDuplicate::dHash)
        s.setHashes(record.id, phash, signature)
        val matches = s.hashCandidates(record.id).mapNotNull { (otherId, otherHash, otherSig) ->
            NearDuplicate.isCandidate(phash, otherHash, signature, otherSig)?.let { otherId to it }
        }
        if (matches.isEmpty()) return
        val others = s.records(matches.map { it.first }).associateBy { it.id }
        var added = false
        for ((otherId, match) in matches) {
            val other = others[otherId] ?: continue
            // Byte-identical files are the import-time prompt (§15), not a near duplicate.
            if (other.checksumSha256 != null && other.checksumSha256 == record.checksumSha256) continue
            val (newer, older) = if (other.createdMs <= record.createdMs) record to other else other to record
            s.addDuplicateCandidate(DuplicateCandidate(newer.id, older.id, match.first, match.second))
            if (s.pendingDuplicates(newer.id).isNotEmpty()) {
                added = true
                if (newer.reviewStatus != ReviewStatus.NEEDS_REVIEW) s.setReviewStatus(listOf(newer.id), ReviewStatus.NEEDS_REVIEW)
            }
        }
        if (added) Log.i(TAG, "Near-duplicate candidates recorded")
    }

    /** 9×8 greyscale of the first page render / image, row-wise (§15 dHash input). */
    private fun firstPageGray(record: HealthRecord): IntArray? {
        val file = files.resolve(record.filePath)?.takeIf { it.isFile } ?: return null
        val bitmap: Bitmap = when (record.fileType) {
            RecordFileType.IMAGE -> FoodImageDecoder.decode(file, HASH_RENDER_DIMENSION)
            RecordFileType.PDF -> runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount == 0) return null
                        renderer.openPage(0).use { page ->
                            val longest = maxOf(page.width, page.height).coerceAtLeast(1)
                            val scale = HASH_RENDER_DIMENSION.toFloat() / longest
                            createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1)).also { bmp ->
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
            else -> null
        } ?: return null
        val small = bitmap.scale(9, 8, filter = true)
        if (small !== bitmap) bitmap.recycle()
        val out = IntArray(72)
        for (y in 0 until 8) for (x in 0 until 9) {
            val c = small.getPixel(x, y)
            out[y * 9 + x] = ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000)
        }
        small.recycle()
        return out
    }

    /** `(pageIndex, text)` of pages with any text, in page order. */
    private fun pageTexts(pages: List<RecordPage>): List<Pair<Int, String>> =
        pages.sortedBy { it.pageIndex }.map { it.pageIndex to it.text.orEmpty() }.takeIf { list -> list.any { it.second.isNotBlank() } }.orEmpty()

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "AyuvoRecords"
        /** Retries after a failed AI call: 30 s, 5 min, 1 h; then continue without AI (§9). */
        val AI_RETRY_DELAYS_MS = listOf(30_000L, 5 * 60_000L, 60 * 60_000L)
        private const val MAX_STAGE_CRASHES = 3
        private const val AI_IMAGE_DIMENSION = 1600
        private const val MAX_AI_IMAGE_PAGES = 10
        private const val HASH_RENDER_DIMENSION = 256
        private const val AI_IMAGE_CONFIDENCE = 0.5
        private const val AI_TYPE_CONFIDENCE = 0.6
        private const val LEGACY_UNREADABLE = "unreadable"
        private val TEXT_ERRORS = setOf(ProcessingError.TEXT_UNAVAILABLE, ProcessingError.OCR_FAILED, ProcessingError.PROTECTED_PDF, ProcessingError.UNSUPPORTED)
    }
}

private class RecordDeletedException : Exception()

/** §9.3 gaps that justify an AI call; a record without any gap never calls AI. */
object AiGaps {
    private val resultLine = Regex("\\d+(?:\\.\\d+)?\\s*(?:g/dl|mg/dl|mmol/l|iu/l|u/l|miu/l|pg/ml|ng/ml|µg/dl|mcg/dl|µmol/l|umol/l|meq/l|fl|pg|%|cells/cumm|/cumm|mm/hr)\\b")

    fun hasGap(record: HealthRecord, fields: List<RecordField>, pages: List<RecordPage>, typeConfidence: Double?): Boolean {
        val usable = fields.filter { it.state != FieldState.REJECTED }
        fun has(key: String) = usable.any { it.key == key }
        if (record.recordType == RecordType.OTHER || (typeConfidence != null && typeConfidence < 0.5)) return true
        if (usable.none { it.key in FieldKey.DATE_KEYS } && record.documentDate == null) return true
        if (!has(FieldKey.DOCTOR_NAME) && !has(FieldKey.FACILITY)) return true
        if (!has(FieldKey.TEST_RESULT) && pages.any { page -> RecordText.fold(page.text).lineSequence().any { resultLine.containsMatchIn(it) } }) return true
        if (record.recordType == RecordType.PRESCRIPTION && !has(FieldKey.MEDICATION)) return true
        if (record.recordType in NARRATIVE_TYPES && !has(FieldKey.DIAGNOSIS) && !has(FieldKey.RECOMMENDATION)) return true
        return false
    }

    private val NARRATIVE_TYPES = setOf(RecordType.CONSULTATION_NOTE, RecordType.DISCHARGE_SUMMARY, RecordType.IMAGING_REPORT)
}
