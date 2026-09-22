package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.AiModeUsed
import com.ayuvo.health.records.model.AnalyteCondition
import com.ayuvo.health.records.model.EntityKind
import com.ayuvo.health.records.model.HealthEntity
import com.ayuvo.health.records.model.LinkKind
import com.ayuvo.health.records.model.NewUserObservation
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.ObservationEdit
import com.ayuvo.health.records.model.RecordLink
import com.ayuvo.health.records.model.RelatedRecord
import com.ayuvo.health.records.model.ValueHit
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
    /**
     * Non-dismissed `important` highlights of live records, newest record first (§15). With [since], only
     * records whose `sort_date` (record date, else import day) is on or after it — callers pass
     * `HighlightBuilder.since(today)` for the 6-month display window.
     */
    suspend fun importantHighlights(limit: Int, since: java.time.LocalDate? = null): List<HighlightWithRecord>

    /** Rebuilds the FTS row (§17 columns). */
    suspend fun reindex(recordId: String)

    // -- Phase 3: knowledge base (§19–§24) --------------------------------------

    /** Observations of one record (every state), in source order. */
    suspend fun observations(recordId: String): List<Observation>

    /**
     * §21 trend rows of [analyteId] (`idx_observations_trend`): non-rejected with a date, ordered by
     * `observed_date, created_ms`; [includeExcluded] also returns rows excluded from trends (table).
     */
    suspend fun analyteObservations(analyteId: String, includeExcluded: Boolean = false): List<Observation>

    // -- Phase 4: Coach (§26–§29) ---------------------------------------------------------------

    /** Every record, archived included (Coach snapshot rows). */
    suspend fun allRecords(): List<HealthRecord>
    suspend fun fieldsFor(recordIds: Collection<String>): Map<String, List<RecordField>>
    suspend fun observationsFor(recordIds: Collection<String>): Map<String, List<Observation>>
    suspend fun highlightsFor(recordIds: Collection<String>): Map<String, List<RecordHighlight>>
    /** Every `record_links` row of [recordId], any status. */
    suspend fun links(recordId: String): List<RecordLink>
    /** Observations (any state) of these analytes. */
    suspend fun observationsOfAnalytes(analyteIds: Collection<String>): List<Observation>
    /** Observations (any state) without an analyte. */
    suspend fun unmappedObservations(): List<Observation>
    /** `docid → matchinfo('pcnalx')` of every FTS row matching all `term*` prefixes. */
    suspend fun ftsMatchinfo(terms: List<String>): Map<Long, IntArray>

    /** Trend rows of every analyte that [recordId] has, keyed by analyte id (detail mini trends). */
    suspend fun trendsForRecord(recordId: String): Map<String, List<Observation>>

    /** Re-runs §19 promotion + mapping, entity rebuild and the FTS row for one record. */
    suspend fun refreshKnowledge(recordId: String)

    /** §24 user edit; remembers a remapped test name in `analyte_user_aliases` when asked. */
    suspend fun editObservation(observationId: String, edit: ObservationEdit)

    /** Remove = `state rejected` (§24). */
    suspend fun removeObservation(observationId: String)

    suspend fun addObservation(input: NewUserObservation): Observation?

    /** `normalized name → analyte id` of `analyte_user_aliases`. */
    suspend fun userAliases(): Map<String, String>

    /** §19 "This is <analyte>" on confirmation: maps every unmapped observation with a matching name key. */
    suspend fun applyUserAlias(rawName: String, analyteId: String): Int

    /** Unmapped, non-rejected observations sharing [rawName]'s normalized name (the "apply to others" prompt). */
    suspend fun unmappedWithName(rawName: String): Int

    /** Doctor / facility entities referenced by non-archived records, most used first. */
    suspend fun entities(kind: EntityKind): List<HealthEntity>

    suspend fun recordEntities(recordId: String): List<Pair<HealthEntity, String>>

    /** Non-rejected links of [recordId] with the other record (Linked first, then Suggested by score). */
    suspend fun related(recordId: String): List<RelatedRecord>

    /** User link (§19): replaces a suggestion on the pair, `origin user`, `status accepted`. */
    suspend fun link(a: String, b: String, kind: LinkKind)

    /** Unlink: deletes a user row, or rejects a suggestion (kept so it never reappears). */
    suspend fun unlink(a: String, b: String)

    suspend fun acceptLink(a: String, b: String)
    suspend fun rejectLink(a: String, b: String)

    /** §22 suggestions for [recordId]; returns how many were created. */
    suspend fun suggestRelations(recordId: String, today: java.time.LocalDate = java.time.LocalDate.now()): Int

    /** Accepted links whose both ends are in [ids] (timeline episode connectors). */
    suspend fun acceptedLinksAmong(ids: Collection<String>): List<RecordLink>

    /** §23 Values group: observation hits for analyte conditions and bare analytes. */
    suspend fun valueHits(conditions: List<AnalyteCondition>, analyteIds: List<String>, limit: Int = VALUE_HITS_LIMIT): List<ValueHit>

    /** Record ids with any promoted knowledge missing (v2 backfill). */
    suspend fun recordIdsNeedingKnowledge(): List<String>

    // -- Phase 5: sharing & backup (§33–§37) -----------------------------------

    /** One `records_backup_state` value (§33), or null. */
    suspend fun backupState(key: String): String?
    suspend fun setBackupState(key: String, value: String)
    suspend fun backupStateAll(): Map<String, String>

    /** §34: `shared_count` + 1 and `last_shared_ms` after a successful share. */
    suspend fun markShared(ids: Collection<String>, nowMs: Long = System.currentTimeMillis())

    /** Stored files of every record (storage sizing, thumbnail rebuild). */
    suspend fun fileRefs(): List<RecordFileRef>

    /**
     * A durable "records changed since" marker (§36 `drive_last_revision`): the newest `updated_ms`
     * folded with the row count, so a deletion also moves it.
     */
    suspend fun contentRevision(): Long

    /** Total `record_pages` rows (§37 storage counts). */
    suspend fun pageCount(): Long

    /** §37 "Rebuild search index": rebuilds every FTS row; returns how many records were indexed. */
    suspend fun reindexAll(): Int

    /** §35 Replace / Delete All: removes every record row, derived row and file. */
    suspend fun deleteAllRecords()

    /** Bumps [revision] so screens re-query after an out-of-store write (archive import). */
    fun bumpRevision()

    fun close()

    companion object {
        const val PAGE_SIZE = 60
        const val SEARCH_LIMIT = 200
        const val VALUE_HITS_LIMIT = 30
    }
}
