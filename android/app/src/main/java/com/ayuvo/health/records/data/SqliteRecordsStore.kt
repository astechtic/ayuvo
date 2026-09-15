package com.ayuvo.health.records.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.records.model.DateMethod
import com.ayuvo.health.records.model.DatePrecision
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordPageResult
import com.ayuvo.health.records.model.RecordPatch
import com.ayuvo.health.records.model.RecordProcessingUpdate
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordSortDate
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordTag
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.model.TextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [RecordsStore] over [RecordsDatabase]. Writes run in one transaction on `Dispatchers.IO`
 * and bump [revision] afterwards. The FTS row is rebuilt inside the same transaction whenever
 * the title, notes, tags or pages change (Phase 1 indexes title, notes_tags and body only).
 */
class SqliteRecordsStore(
    private val helper: RecordsDatabase,
    private val files: RecordFileStore
) : RecordsStore {

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        val database = db
        database.beginTransactionNonExclusive()
        return try {
            val result = block(database)
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
            _revision.value = _revision.value + 1
        }
    }

    override suspend fun page(query: RecordQuery, after: RecordCursor?, limit: Int): RecordPageResult =
        withContext(Dispatchers.IO) {
            val built = RecordsQuerySql.page(query, after, limit, COLUMNS)
            val items = db.rawQuery(built.sql, built.args.toTypedArray()).use { it.readAll() }
            val last = items.lastOrNull()
            val next = if (items.size >= limit && last != null) {
                RecordCursor(last.sortDate, last.createdMs, last.seq)
            } else {
                null
            }
            RecordPageResult(items, next)
        }

    override suspend fun recent(limit: Int): List<HealthRecord> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT $COLUMNS FROM records WHERE archived = 0 ORDER BY created_ms DESC, seq DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { it.readAll() }
    }

    override suspend fun record(id: String): HealthRecord? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $COLUMNS FROM records WHERE id = ?", arrayOf(id)).use { it.readAll().firstOrNull() }
    }

    override suspend fun findByChecksum(checksum: String, excludingId: String?): HealthRecord? =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT $COLUMNS FROM records WHERE checksum_sha256 = ? AND id != ? ORDER BY created_ms ASC LIMIT 1",
                arrayOf(checksum, excludingId ?: "")
            ).use { it.readAll().firstOrNull() }
        }

    override suspend fun insert(record: HealthRecord, pages: List<RecordPage>): HealthRecord =
        withContext(Dispatchers.IO) {
            write { database ->
                val seq = database.insertOrThrow("records", null, record.toValues())
                for (page in pages) database.insertOrThrow("record_pages", null, page.toValues(record.id))
                reindex(database, record.id)
                record.copy(seq = seq)
            }
        }

    override suspend fun update(ids: Collection<String>, patch: RecordPatch) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            write { database ->
                val values = ContentValues()
                patch.title?.let { values.put("title", it.trim().ifEmpty { null } ?: return@let) }
                if (patch.clearDocumentDate) {
                    values.putNull("document_date")
                    values.putNull("document_date_precision")
                    values.putNull("document_date_method")
                } else {
                    patch.documentDate?.let {
                        values.put("document_date", it)
                        values.put("document_date_precision", DatePrecision.DAY.raw)
                        values.put("document_date_method", DateMethod.USER.raw)
                        values.put("sort_date", it)
                    }
                }
                patch.recordType?.let { values.put("record_type", it.raw) }
                patch.category?.let { values.put("category", it.raw) }
                patch.notes?.let { notes -> if (notes.isBlank()) values.putNull("notes") else values.put("notes", notes) }
                patch.favorite?.let { values.put("favorite", if (it) 1 else 0) }
                patch.archived?.let { values.put("archived", if (it) 1 else 0) }
                if (values.size() == 0) return@write
                values.put("updated_ms", System.currentTimeMillis())
                for (id in ids) {
                    if (patch.clearDocumentDate) {
                        // Clearing the document date falls back to the device-local import day.
                        createdMsOf(database, id)?.let { values.put("sort_date", RecordSortDate.localDay(it)) }
                    }
                    database.update("records", values, "id = ?", arrayOf(id))
                    if (patch.title != null || patch.notes != null) reindex(database, id)
                }
            }
        }
    }

    override suspend fun updateProcessing(id: String, update: RecordProcessingUpdate) {
        withContext(Dispatchers.IO) {
            write { database ->
                val values = ContentValues().apply {
                    update.pageCount?.let { put("page_count", it) }
                    update.thumbnailPath?.let { put("thumbnail_path", it) }
                    put("processing_status", update.status.raw)
                    if (update.error != null) put("processing_error", update.error) else putNull("processing_error")
                }
                database.update("records", values, "id = ?", arrayOf(id))
                // A detected date never replaces one the user already set.
                if (update.documentDate != null) {
                    val dateValues = ContentValues().apply {
                        put("document_date", update.documentDate)
                        put("document_date_precision", (update.documentDatePrecision ?: DatePrecision.DAY).raw)
                        put("document_date_method", (update.documentDateMethod ?: DateMethod.FILE_METADATA).raw)
                        put("sort_date", update.documentDate)
                    }
                    database.update("records", dateValues, "id = ? AND document_date IS NULL", arrayOf(id))
                }
            }
        }
    }

    override suspend fun pages(id: String): List<RecordPage> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT record_id, page_index, text, text_source, ocr_confidence, width, height, blocks_json FROM record_pages WHERE record_id = ? ORDER BY page_index",
            arrayOf(id)
        ).use { c ->
            val out = mutableListOf<RecordPage>()
            while (c.moveToNext()) {
                out += RecordPage(
                    recordId = c.getString(0),
                    pageIndex = c.getInt(1),
                    text = c.getStringOrNull(2),
                    textSource = TextSource.fromRaw(c.getString(3)),
                    ocrConfidence = if (c.isNull(4)) null else c.getDouble(4),
                    width = if (c.isNull(5)) null else c.getInt(5),
                    height = if (c.isNull(6)) null else c.getInt(6),
                    blocksJson = c.getStringOrNull(7)
                )
            }
            out
        }
    }

    override suspend fun tags(recordId: String): List<RecordTag> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT t.id, t.name FROM tags t JOIN record_tags rt ON rt.tag_id = t.id WHERE rt.record_id = ? ORDER BY t.name COLLATE NOCASE",
            arrayOf(recordId)
        ).use { it.readTags() }
    }

    override suspend fun allTags(): List<RecordTag> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, name FROM tags ORDER BY name COLLATE NOCASE", null).use { it.readTags() }
    }

    override suspend fun addTag(recordId: String, name: String): RecordTag? {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(MAX_TAG_LENGTH)
        if (clean.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            write { database ->
                val existing = database.rawQuery("SELECT id, name FROM tags WHERE name = ? COLLATE NOCASE", arrayOf(clean))
                    .use { it.readTags().firstOrNull() }
                val tag = existing ?: RecordTag(UUID.randomUUID().toString(), clean).also { created ->
                    database.insertOrThrow("tags", null, ContentValues().apply {
                        put("id", created.id)
                        put("name", created.name)
                    })
                }
                database.insertWithOnConflict("record_tags", null, ContentValues().apply {
                    put("record_id", recordId)
                    put("tag_id", tag.id)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                touch(database, recordId)
                reindex(database, recordId)
                tag
            }
        }
    }

    override suspend fun removeTag(recordId: String, tagId: String) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.delete("record_tags", "record_id = ? AND tag_id = ?", arrayOf(recordId, tagId))
                touch(database, recordId)
                reindex(database, recordId)
            }
        }
    }

    override suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            write { database ->
                for (id in ids) {
                    seqOf(database, id)?.let { seq ->
                        database.delete("records_fts", "docid = ?", arrayOf(seq.toString()))
                    }
                    database.delete("records", "id = ?", arrayOf(id))
                }
            }
            ids.forEach(files::deleteRecord)
        }
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT COUNT(*) FROM records", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    override fun close() {
        runCatching { helper.close() }
    }

    // -- FTS ------------------------------------------------------------------

    /** Rebuilds the single FTS row for [recordId] from its title, notes, tags and page text. */
    private fun reindex(database: SQLiteDatabase, recordId: String) {
        var seq = 0L
        var title = ""
        var notes: String? = null
        database.rawQuery("SELECT seq, title, notes FROM records WHERE id = ?", arrayOf(recordId)).use { c ->
            if (!c.moveToFirst()) return
            seq = c.getLong(0)
            title = c.getString(1).orEmpty()
            notes = c.getStringOrNull(2)
        }
        val tagNames = database.rawQuery(
            "SELECT t.name FROM tags t JOIN record_tags rt ON rt.tag_id = t.id WHERE rt.record_id = ?",
            arrayOf(recordId)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val body = database.rawQuery(
            "SELECT text FROM record_pages WHERE record_id = ? ORDER BY page_index",
            arrayOf(recordId)
        ).use { c -> buildList { while (c.moveToNext()) c.getStringOrNull(0)?.let(::add) } }.joinToString("\n")
        database.delete("records_fts", "docid = ?", arrayOf(seq.toString()))
        database.execSQL(
            "INSERT INTO records_fts(docid, title, people, clinical, body, notes_tags, highlights) VALUES (?, ?, '', '', ?, ?, '')",
            arrayOf<Any>(
                seq,
                RecordsSearchText.fold(title),
                RecordsSearchText.fold(body),
                RecordsSearchText.fold(listOfNotNull(notes).plus(tagNames).joinToString(" "))
            )
        )
    }

    private fun touch(database: SQLiteDatabase, recordId: String) {
        database.execSQL("UPDATE records SET updated_ms = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), recordId))
    }

    private fun createdMsOf(database: SQLiteDatabase, id: String): Long? =
        database.rawQuery("SELECT created_ms FROM records WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun seqOf(database: SQLiteDatabase, id: String): Long? =
        database.rawQuery("SELECT seq FROM records WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    // -- Mapping --------------------------------------------------------------

    private fun HealthRecord.toValues() = ContentValues().apply {
        put("id", id)
        if (parentId != null) put("parent_id", parentId) else putNull("parent_id")
        pageStart?.let { put("page_start", it) }
        pageEnd?.let { put("page_end", it) }
        put("title", title)
        put("record_type", recordType.raw)
        put("category", category.raw)
        put("source", source.raw)
        put("import_method", importMethod.raw)
        sourceApp?.let { put("source_app", it) }
        originalFilename?.let { put("original_filename", it) }
        put("created_ms", createdMs)
        put("updated_ms", updatedMs)
        documentDate?.let { put("document_date", it) }
        documentDatePrecision?.let { put("document_date_precision", it.raw) }
        documentDateMethod?.let { put("document_date_method", it.raw) }
        put("sort_date", RecordSortDate.forRecord(documentDate, createdMs))
        put("mime_type", mimeType)
        put("file_type", fileType.raw)
        put("file_size", fileSize)
        put("page_count", pageCount)
        filePath?.let { put("file_path", it) }
        thumbnailPath?.let { put("thumbnail_path", it) }
        checksumSha256?.let { put("checksum_sha256", it) }
        put("processing_status", processingStatus.raw)
        processingError?.let { put("processing_error", it) }
        put("review_status", reviewStatus.raw)
        put("favorite", if (favorite) 1 else 0)
        put("archived", if (archived) 1 else 0)
        notes?.let { put("notes", it) }
    }

    private fun RecordPage.toValues(recordId: String) = ContentValues().apply {
        put("record_id", recordId)
        put("page_index", pageIndex)
        if (text != null) put("text", text) else putNull("text")
        put("text_source", textSource.raw)
        ocrConfidence?.let { put("ocr_confidence", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        blocksJson?.let { put("blocks_json", it) }
    }

    private fun Cursor.readAll(): List<HealthRecord> {
        val out = mutableListOf<HealthRecord>()
        while (moveToNext()) out += readRecord(this)
        return out
    }

    private fun Cursor.readTags(): List<RecordTag> {
        val out = mutableListOf<RecordTag>()
        while (moveToNext()) out += RecordTag(getString(0), getString(1))
        return out
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private fun readRecord(c: Cursor) = HealthRecord(
        seq = c.getLong(0),
        id = c.getString(1),
        parentId = c.getStringOrNull(2),
        pageStart = c.getIntOrNull(3),
        pageEnd = c.getIntOrNull(4),
        title = c.getString(5),
        recordType = RecordType.fromRaw(c.getString(6)),
        category = RecordCategory.fromRaw(c.getString(7)),
        source = RecordSource.fromRaw(c.getString(8)),
        importMethod = ImportMethod.fromRaw(c.getString(9)),
        sourceApp = c.getStringOrNull(10),
        originalFilename = c.getStringOrNull(11),
        createdMs = c.getLong(12),
        updatedMs = c.getLong(13),
        documentDate = c.getStringOrNull(14),
        documentDatePrecision = DatePrecision.fromRaw(c.getStringOrNull(15)),
        documentDateMethod = DateMethod.fromRaw(c.getStringOrNull(16)),
        sortDate = c.getString(17),
        mimeType = c.getString(18),
        fileType = RecordFileType.fromRaw(c.getString(19)),
        fileSize = c.getLong(20),
        pageCount = c.getInt(21),
        filePath = c.getStringOrNull(22),
        thumbnailPath = c.getStringOrNull(23),
        checksumSha256 = c.getStringOrNull(24),
        processingStatus = ProcessingStatus.fromRaw(c.getString(25)),
        processingError = c.getStringOrNull(26),
        reviewStatus = ReviewStatus.fromRaw(c.getString(27)),
        favorite = c.getInt(28) != 0,
        archived = c.getInt(29) != 0,
        notes = c.getStringOrNull(30)
    )

    companion object {
        const val MAX_TAG_LENGTH = 40

        /** Narrow projection in [readRecord] order (never page text). */
        const val COLUMNS = "seq, id, parent_id, page_start, page_end, title, record_type, category, source, " +
            "import_method, source_app, original_filename, created_ms, updated_ms, document_date, " +
            "document_date_precision, document_date_method, sort_date, mime_type, file_type, file_size, page_count, " +
            "file_path, thumbnail_path, checksum_sha256, processing_status, processing_error, review_status, " +
            "favorite, archived, notes"
    }
}
