package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordPageResult
import com.ayuvo.health.records.model.RecordPatch
import com.ayuvo.health.records.model.RecordProcessingUpdate
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordTag
import kotlinx.coroutines.flow.StateFlow

/**
 * Health Records persistence. Every call runs off the main thread; [revision] bumps after
 * each committed write so screens re-query instead of holding stale copies.
 */
interface RecordsStore {
    val revision: StateFlow<Long>

    /** Keyset page in timeline order (docs/health-records.md §5). */
    suspend fun page(query: RecordQuery, after: RecordCursor?, limit: Int = PAGE_SIZE): RecordPageResult

    /** Most recently added, non-archived records. */
    suspend fun recent(limit: Int): List<HealthRecord>

    suspend fun record(id: String): HealthRecord?
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

    fun close()

    companion object {
        const val PAGE_SIZE = 60
    }
}
