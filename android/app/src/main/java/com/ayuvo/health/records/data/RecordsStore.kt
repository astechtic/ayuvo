package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.AiModeUsed
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.DuplicateResolution
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.ProcessingJob
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.RecordIntelligenceColumns
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordPageResult
import com.ayuvo.health.records.model.RecordPatch
import com.ayuvo.health.records.model.RecordProcessingUpdate
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordTag
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.model.SplitProposal
import com.ayuvo.health.records.model.SplitSegment
import com.ayuvo.health.records.model.SplitStatus
import kotlinx.coroutines.flow.StateFlow

/** A ranked universal-search hit (§17). */
data class RecordSearchHit(
    val record: HealthRecord,
    val score: Double,
    /** Best-column snippet with `[`/`]` around matched terms, or null. */
    val snippet: String?
)

/** An `important` highlight with its record, for the Records home section. */
data class HighlightWithRecord(val highlight: RecordHighlight, val record: HealthRecord)

/** Home-screen processing strip numbers. */
data class ProcessingSummary(val processing: Int, val awaitingConsent: Int)

/** Everything the detail screen shows besides the viewer. */
data class RecordIntelligence(
    val columns: RecordIntelligenceColumns,
    val fields: List<RecordField>,
    val highlights: List<RecordHighlight>,
    val job: ProcessingJob?,
    val duplicates: List<DuplicateCandidate>,
    val split: SplitProposal?,
    val parent: HealthRecord?,
    val children: List<HealthRecord>
)

/**
 * Health Records persistence. Every call runs off the main thread; [revision] bumps after
 * each committed write so screens re-query instead of holding stale copies.
 */
interface RecordsStore {
    val revision: StateFlow<Long>

    /** Keyset page in timeline order (docs/health-records.md §5). */
    suspend fun page(query: RecordQuery, after: RecordCursor?, limit: Int = PAGE_SIZE): RecordPageResult

    /** BM25-ranked FTS results with snippets (§17); empty when the query has no MATCH terms. */
    suspend fun search(query: RecordQuery, limit: Int = SEARCH_LIMIT, today: java.time.LocalDate = java.time.LocalDate.now()): List<RecordSearchHit>

    /** Most recently added, non-archived records. */
    suspend fun recent(limit: Int): List<HealthRecord>

    suspend fun record(id: String): HealthRecord?
    suspend fun records(ids: Collection<String>): List<HealthRecord>
    suspend fun findByChecksum(checksum: String, excludingId: String? = null): HealthRecord?

    /** Inserts the row, its pages and the FTS row in one transaction; returns it with `seq`. */
    suspend fun insert(record: HealthRecord, pages: List<RecordPage> = emptyList()): HealthRecord

    suspend fun update(ids: Collection<String>, patch: RecordPatch)
    suspend fun updateProcessing(id: String, update: RecordProcessingUpdate)

    suspend fun pages(id: String): List<RecordPage>

    suspend fun tags(recordId: String): List<RecordTag>
    suspend fun allTags(): List<RecordTag>
    suspend fun addTag(recordId: String, name: String): RecordTag?
    suspend fun removeTag(recordId: String, tagId: String)

    /** Deletes the rows (pages and tag links cascade) and their files. */
    suspend fun delete(ids: Collection<String>)

    suspend fun count(): Long

    // -- Phase 2: intelligence ------------------------------------------------

    suspend fun intelligence(recordId: String): RecordIntelligence?
    suspend fun columns(recordId: String): RecordIntelligenceColumns?
    suspend fun fields(recordId: String): List<RecordField>
    suspend fun highlights(recordId: String): List<RecordHighlight>

    /** Commits one text-stage page (and the page count when known). */
    suspend fun upsertPage(page: RecordPage, pageCount: Int? = null)

    suspend fun setStatus(id: String, status: ProcessingStatus, error: String? = null, keepError: Boolean = false)

    /** Classifier result; ignored when the user set the type (`type_method = user`). */
    suspend fun setClassification(id: String, type: RecordType, category: RecordCategory, confidence: Double, method: ExtractionMethod)

    /**
     * §8.1 write rule + derived record columns in one transaction.
     * [typeLabel] names the record type for the derived "<Type> — <facility>" title.
     */
    suspend fun applyExtraction(id: String, extracted: List<ExtractedField>, typeLabel: (RecordType) -> String?)

    /** Review sheet actions; [editedValue] turns the row into a `user` value. */
    suspend fun setFieldState(fieldId: String, state: FieldState, editedValue: String? = null, typeLabel: (RecordType) -> String? = { null })
    suspend fun addUserField(recordId: String, key: String, value: String, typeLabel: (RecordType) -> String? = { null })

    /** Replaces the highlights of [sections] produced by [methods]; dismissed texts stay dismissed. */
    suspend fun replaceHighlights(recordId: String, sections: Set<HighlightSection>, methods: Set<ExtractionMethod>, highlights: List<RecordHighlight>)
    suspend fun dismissHighlight(highlightId: String)

    suspend fun setReviewStatus(ids: Collection<String>, status: ReviewStatus)
    suspend fun setAiUsed(id: String, mode: AiModeUsed, provider: String?)
    suspend fun setHashes(id: String, phash: String?, textSignature: String?)

    /** `(id, phash, text_signature)` of every other record that has either hash. */
    suspend fun hashCandidates(excludingId: String): List<Triple<String, String?, String?>>

    // Jobs
    suspend fun job(recordId: String): ProcessingJob?
    suspend fun saveJob(job: ProcessingJob)
    /** (Re)starts processing of [ids] at [stage]; status → queued. Returns ids that exist. */
    suspend fun enqueueJobs(ids: Collection<String>, stage: com.ayuvo.health.records.model.ProcessingStage): List<String>
    /** Jobs not `done`, oldest first. */
    suspend fun unfinishedJobs(): List<ProcessingJob>
    suspend fun awaitingConsent(): List<String>
    suspend fun recordIdsWithoutJob(): List<String>
    suspend fun meta(key: String): String?
    suspend fun setMeta(key: String, value: String)

    // Duplicates & splits
    suspend fun addDuplicateCandidate(candidate: DuplicateCandidate)
    suspend fun pendingDuplicates(recordId: String): List<DuplicateCandidate>
    suspend fun resolveDuplicate(recordId: String, existingId: String, resolution: DuplicateResolution)
    /** Swaps [existingId]'s original for [newId]'s file, keeps notes/tags/settled fields, removes [newId]. */
    suspend fun replaceWithNew(existingId: String, newId: String)
    /** Moves notes and tags of [newId] into [existingId] and deletes [newId]. */
    suspend fun mergeInto(existingId: String, newId: String)

    suspend fun splitProposal(recordId: String): SplitProposal?
    suspend fun saveSplitProposal(proposal: SplitProposal?, recordId: String)
    suspend fun setSplitStatus(recordId: String, status: SplitStatus)
    /** Creates child records sharing the parent file, archives the parent; returns child ids. */
    suspend fun acceptSplit(parentId: String, segments: List<SplitSegment>, titleFallback: (SplitSegment) -> String): List<String>

    // Home sections
    suspend fun processingSummary(): ProcessingSummary
    suspend fun needsReview(limit: Int): List<HealthRecord>
    suspend fun importantHighlights(limit: Int): List<HighlightWithRecord>

    /** Rebuilds the FTS row (§17 columns). */
    suspend fun reindex(recordId: String)

    fun close()

    companion object {
        const val PAGE_SIZE = 60
        const val SEARCH_LIMIT = 200
    }
}
