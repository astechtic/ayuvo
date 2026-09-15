package com.ayuvo.health.records.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.records.ingest.RecordTitles
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.knowledge.EntityRules
import com.ayuvo.health.records.knowledge.ObservationRules
import com.ayuvo.health.records.knowledge.RelationSuggester
import com.ayuvo.health.records.model.AnalyteCondition
import com.ayuvo.health.records.model.AnalyteMethod
import com.ayuvo.health.records.model.EntityKind
import com.ayuvo.health.records.model.HealthEntity
import com.ayuvo.health.records.model.LinkKind
import com.ayuvo.health.records.model.LinkOrigin
import com.ayuvo.health.records.model.LinkStatus
import com.ayuvo.health.records.model.NewUserObservation
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.ObservationEdit
import com.ayuvo.health.records.model.RecordLink
import com.ayuvo.health.records.model.RelatedRecord
import com.ayuvo.health.records.model.ResultFlag
import com.ayuvo.health.records.model.ValueHit
import com.ayuvo.health.records.processing.UnitsCatalog
import com.ayuvo.health.records.model.AiModeUsed
import com.ayuvo.health.records.model.DateMethod
import com.ayuvo.health.records.model.DatePrecision
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.DuplicateReason
import com.ayuvo.health.records.model.DuplicateResolution
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.ProcessingJob
import com.ayuvo.health.records.model.ProcessingStage
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.RecordIntelligenceColumns
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
import com.ayuvo.health.records.model.SplitProposal
import com.ayuvo.health.records.model.SplitSegment
import com.ayuvo.health.records.model.SplitStatus
import com.ayuvo.health.records.model.TextSource
import com.ayuvo.health.records.processing.ExtractionWriter
import com.ayuvo.health.records.processing.SearchIndexer
import com.ayuvo.health.records.processing.SplitSegmentsJson
import com.ayuvo.health.records.search.RecordSearchRanking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * [RecordsStore] over [RecordsDatabase]. Writes run in one transaction on `Dispatchers.IO`
 * and bump [revision] afterwards. The FTS row is rebuilt inside the same transaction whenever
 * indexed content changes (§17 columns).
 */
class SqliteRecordsStore(
    private val helper: RecordsDatabase,
    private val files: RecordFileStore,
    /** Analyte catalogue for §20 mapping (the bundled asset in the app, injectable in tests). */
    private val catalog: () -> AnalyteCatalog = { AnalyteCatalog.active }
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

    override suspend fun search(query: RecordQuery, limit: Int, today: LocalDate): List<RecordSearchHit> =
        withContext(Dispatchers.IO) {
            val match = RecordsQuerySql.matchExpression(query) ?: return@withContext emptyList()
            val built = RecordsQuerySql.searchCandidates(query, match, COLUMNS, CANDIDATE_LIMIT)
            val columnCount = RecordsQuerySql.SNIPPET_COLUMNS.size
            val hits = db.rawQuery(built.sql, built.args.toTypedArray()).use { c ->
                val out = mutableListOf<RecordSearchHit>()
                while (c.moveToNext()) {
                    val record = readRecord(c)
                    val info = RecordSearchRanking.decodeMatchinfo(c.getBlob(COLUMN_COUNT))
                    val bm25 = RecordSearchRanking.bm25(info, RecordsQuerySql.COLUMN_WEIGHTS)
                    val score = bm25 + RecordSearchRanking.recencyBoost(record.sortDate, today)
                    val bestColumn = RecordSearchRanking.bestColumn(info, RecordsQuerySql.COLUMN_WEIGHTS)
                    val snippet = (0 until columnCount)
                        .map { it to c.getStringOrNull(COLUMN_COUNT + 1 + it) }
                        .let { snippets -> snippets.getOrNull(bestColumn)?.second?.takeIf { '[' in it } ?: snippets.firstOrNull { it.second?.contains('[') == true }?.second }
                    out += RecordSearchHit(record, score, snippet)
                }
                out
            }
            hits.sortedWith(compareByDescending<RecordSearchHit> { it.score }.thenByDescending { it.record.sortDate }.thenByDescending { it.record.seq })
                .take(limit)
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

    override suspend fun records(ids: Collection<String>): List<HealthRecord> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        ids.distinct().chunked(500).flatMap { chunk ->
            db.rawQuery(
                "SELECT $COLUMNS FROM records WHERE id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray()
            ).use { it.readAll() }
        }
    }

    override suspend fun findByChecksum(checksum: String, excludingId: String?): HealthRecord? =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT $COLUMNS FROM records WHERE checksum_sha256 = ? AND id != ? AND parent_id IS NULL ORDER BY created_ms ASC LIMIT 1",
                arrayOf(checksum, excludingId ?: "")
            ).use { it.readAll().firstOrNull() }
        }

    override suspend fun insert(record: HealthRecord, pages: List<RecordPage>): HealthRecord =
        withContext(Dispatchers.IO) {
            write { database ->
                val seq = database.insertOrThrow("records", null, record.toValues())
                for (page in pages) database.insertOrThrow("record_pages", null, page.toValues(record.id))
                reindexIn(database, record.id)
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
                patch.recordType?.let {
                    values.put("record_type", it.raw)
                    values.put("type_method", ExtractionMethod.USER.raw)
                    values.put("type_confidence", 1.0)
                }
                patch.category?.let {
                    values.put("category", it.raw)
                    values.put("type_method", ExtractionMethod.USER.raw)
                }
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
                    // Observed dates follow the record date (§19).
                    if (patch.documentDate != null || patch.clearDocumentDate) knowledgeIn(database, id)
                    if (patch.title != null || patch.notes != null || patch.recordType != null) reindexIn(database, id)
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
                    database.update(
                        "records", dateValues,
                        "id = ? AND document_date IS NULL AND (document_date_method IS NULL OR document_date_method != 'user')",
                        arrayOf(id)
                    )
                }
            }
        }
    }

    override suspend fun pages(id: String): List<RecordPage> = withContext(Dispatchers.IO) { pagesIn(db, id) }

    private fun pagesIn(database: SQLiteDatabase, id: String): List<RecordPage> = database.rawQuery(
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
            write { database -> addTagIn(database, recordId, clean) }
        }
    }

    private fun addTagIn(database: SQLiteDatabase, recordId: String, clean: String): RecordTag {
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
        reindexIn(database, recordId)
        return tag
    }

    override suspend fun removeTag(recordId: String, tagId: String) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.delete("record_tags", "record_id = ? AND tag_id = ?", arrayOf(recordId, tagId))
                touch(database, recordId)
                reindexIn(database, recordId)
            }
        }
    }

    override suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val orphanedDirs = write { database -> deleteIn(database, ids) }
            orphanedDirs.forEach(files::deleteRecord)
        }
    }

    /**
     * Deletes rows and returns the record directories that no remaining record references.
     * Split children share their parent's file, so a directory survives while any row points at it.
     */
    private fun deleteIn(database: SQLiteDatabase, ids: Collection<String>): List<String> {
        val paths = mutableSetOf<String>()
        for (id in ids) {
            database.rawQuery("SELECT seq, file_path FROM records WHERE id = ?", arrayOf(id)).use { c ->
                if (c.moveToFirst()) {
                    database.delete("records_fts", "docid = ?", arrayOf(c.getLong(0).toString()))
                    c.getStringOrNull(1)?.let(paths::add)
                }
            }
            database.delete("records", "id = ?", arrayOf(id))
        }
        deleteOrphanEntities(database)
        val dirs = ids.toMutableSet()
        for (path in paths) {
            val owner = path.substringBefore('/')
            val stillUsed = database.rawQuery("SELECT 1 FROM records WHERE file_path = ? LIMIT 1", arrayOf(path)).use { it.moveToFirst() }
            if (stillUsed) dirs.remove(owner) else dirs.add(owner)
        }
        // A remaining child keeps its own thumbnail directory only when it still exists as a row.
        return dirs.filter { dir -> database.rawQuery("SELECT 1 FROM records WHERE id = ? LIMIT 1", arrayOf(dir)).use { !it.moveToFirst() } }
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT COUNT(*) FROM records", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    // -- Phase 2 ----------------------------------------------------------------

    override suspend fun intelligence(recordId: String): RecordIntelligence? = withContext(Dispatchers.IO) {
        val database = db
        val columns = columnsIn(database, recordId) ?: return@withContext null
        val record = database.rawQuery("SELECT $COLUMNS FROM records WHERE id = ?", arrayOf(recordId)).use { it.readAll().firstOrNull() }
        val parent = record?.parentId?.let { pid ->
            database.rawQuery("SELECT $COLUMNS FROM records WHERE id = ?", arrayOf(pid)).use { it.readAll().firstOrNull() }
        }
        val children = database.rawQuery(
            "SELECT $COLUMNS FROM records WHERE parent_id = ? ORDER BY page_start, seq",
            arrayOf(recordId)
        ).use { it.readAll() }
        RecordIntelligence(
            columns = columns,
            fields = fieldsIn(database, recordId),
            highlights = highlightsIn(database, recordId),
            job = jobIn(database, recordId),
            duplicates = pendingDuplicatesIn(database, recordId),
            split = splitIn(database, recordId),
            parent = parent,
            children = children
        )
    }

    override suspend fun columns(recordId: String): RecordIntelligenceColumns? = withContext(Dispatchers.IO) { columnsIn(db, recordId) }

    private fun columnsIn(database: SQLiteDatabase, recordId: String): RecordIntelligenceColumns? = database.rawQuery(
        "SELECT phash, text_signature, ai_mode_used, ai_provider, type_confidence, type_method FROM records WHERE id = ?",
        arrayOf(recordId)
    ).use { c ->
        if (!c.moveToFirst()) return null
        RecordIntelligenceColumns(
            phash = c.getStringOrNull(0),
            textSignature = c.getStringOrNull(1),
            aiModeUsed = AiModeUsed.fromRaw(c.getStringOrNull(2)),
            aiProvider = c.getStringOrNull(3),
            typeConfidence = if (c.isNull(4)) null else c.getDouble(4),
            typeMethod = c.getStringOrNull(5)?.let(ExtractionMethod::fromRaw)
        )
    }

    override suspend fun fields(recordId: String): List<RecordField> = withContext(Dispatchers.IO) { fieldsIn(db, recordId) }

    private fun fieldsIn(database: SQLiteDatabase, recordId: String): List<RecordField> = database.rawQuery(
        "SELECT $FIELD_COLUMNS FROM record_fields WHERE record_id = ? ORDER BY field_key, source_page, created_ms, rowid",
        arrayOf(recordId)
    ).use { c -> buildList { while (c.moveToNext()) add(readField(c)) } }

    override suspend fun highlights(recordId: String): List<RecordHighlight> = withContext(Dispatchers.IO) { highlightsIn(db, recordId) }

    private fun highlightsIn(database: SQLiteDatabase, recordId: String): List<RecordHighlight> = database.rawQuery(
        "SELECT $HIGHLIGHT_COLUMNS FROM record_highlights WHERE record_id = ? ORDER BY section, position, created_ms",
        arrayOf(recordId)
    ).use { c -> buildList { while (c.moveToNext()) add(readHighlight(c)) } }

    override suspend fun upsertPage(page: RecordPage, pageCount: Int?) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.insertWithOnConflict("record_pages", null, page.toValues(page.recordId), SQLiteDatabase.CONFLICT_REPLACE)
                if (pageCount != null) {
                    database.execSQL("UPDATE records SET page_count = ? WHERE id = ? AND parent_id IS NULL", arrayOf<Any>(pageCount, page.recordId))
                }
            }
        }
    }

    override suspend fun setStatus(id: String, status: ProcessingStatus, error: String?, keepError: Boolean) {
        withContext(Dispatchers.IO) {
            write { database ->
                val values = ContentValues().apply {
                    put("processing_status", status.raw)
                    if (!keepError) {
                        if (error != null) put("processing_error", error) else putNull("processing_error")
                    } else if (error != null) {
                        put("processing_error", error)
                    }
                }
                database.update("records", values, "id = ?", arrayOf(id))
            }
        }
    }

    override suspend fun setClassification(id: String, type: RecordType, category: RecordCategory, confidence: Double, method: ExtractionMethod) {
        withContext(Dispatchers.IO) {
            write { database ->
                val values = ContentValues().apply {
                    put("record_type", type.raw)
                    put("category", category.raw)
                    put("type_confidence", confidence)
                    put("type_method", method.raw)
                }
                database.update("records", values, "id = ? AND (type_method IS NULL OR type_method != 'user')", arrayOf(id))
                reindexIn(database, id)
            }
        }
    }

    override suspend fun applyExtraction(id: String, extracted: List<ExtractedField>, typeLabel: (RecordType) -> String?) {
        withContext(Dispatchers.IO) {
            write { database ->
                val now = System.currentTimeMillis()
                val existing = fieldsIn(database, id)
                for (op in ExtractionWriter.plan(existing, extracted)) {
                    when (op) {
                        is ExtractionWriter.Op.Insert -> database.insertOrThrow("record_fields", null, op.field.toValues(id, UUID.randomUUID().toString(), now, created = true))
                        is ExtractionWriter.Op.Update -> database.update("record_fields", op.field.toValues(id, op.existingId, now, created = false), "id = ? AND state = 'suggested'", arrayOf(op.existingId))
                        is ExtractionWriter.Op.Skip -> Unit
                    }
                }
                deriveColumnsIn(database, id, typeLabel)
                knowledgeIn(database, id)
                reindexIn(database, id)
            }
        }
    }

    /** §8.1 derived columns (title while import-derived, document date while not user/rules). */
    private fun deriveColumnsIn(database: SQLiteDatabase, id: String, typeLabel: (RecordType) -> String?) {
        val record = database.rawQuery("SELECT $COLUMNS FROM records WHERE id = ?", arrayOf(id)).use { it.readAll().firstOrNull() } ?: return
        val fields = fieldsIn(database, id)
        val derived = ExtractionWriter.derive(
            ExtractionWriter.RecordColumns(
                title = record.title,
                titleImportDerived = isImportDerivedTitle(record),
                documentDate = record.documentDate,
                documentDateMethod = record.documentDateMethod
            ),
            fields,
            typeLabel(record.recordType).takeIf { record.recordType != RecordType.OTHER }
        )
        val values = ContentValues()
        derived.title?.let { values.put("title", it.take(RecordTitles.MAX_LENGTH)) }
        derived.documentDate?.let {
            values.put("document_date", it)
            values.put("document_date_precision", DatePrecision.DAY.raw)
            values.put("document_date_method", (derived.documentDateMethod ?: DateMethod.RULES).raw)
            values.put("sort_date", it)
        }
        if (values.size() > 0) {
            values.put("updated_ms", System.currentTimeMillis())
            database.update("records", values, "id = ?", arrayOf(id))
        }
    }

    /** Phase 1 title derivation (§4.4): filename stem, "<Type> — <date>" fallback or Untitled. */
    private fun isImportDerivedTitle(record: HealthRecord): Boolean {
        val title = record.title
        if (title == RecordTitles.UNTITLED) return true
        if (record.originalFilename != null && RecordTitles.fromFilename(record.originalFilename) == title) return true
        // Split children and scan/photo/paste imports get "<label> — <date>" titles.
        if (record.originalFilename == null && record.source in AUTO_TITLE_SOURCES && title.contains(" — ")) return true
        return false
    }

    override suspend fun setFieldState(fieldId: String, state: FieldState, editedValue: String?, typeLabel: (RecordType) -> String?) {
        withContext(Dispatchers.IO) {
            write { database ->
                val recordId = database.rawQuery("SELECT record_id FROM record_fields WHERE id = ?", arrayOf(fieldId)).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: return@write
                val values = ContentValues().apply {
                    if (editedValue != null) {
                        put("value_text", editedValue.trim())
                        put("state", FieldState.USER.raw)
                        put("method", ExtractionMethod.USER.raw)
                        put("confidence", 1.0)
                    } else {
                        put("state", state.raw)
                    }
                    put("updated_ms", System.currentTimeMillis())
                }
                database.update("record_fields", values, "id = ?", arrayOf(fieldId))
                deriveColumnsIn(database, recordId, typeLabel)
                knowledgeIn(database, recordId)
                reindexIn(database, recordId)
            }
        }
    }

    override suspend fun addUserField(recordId: String, key: String, value: String, typeLabel: (RecordType) -> String?) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        withContext(Dispatchers.IO) {
            write { database ->
                val now = System.currentTimeMillis()
                database.insertOrThrow("record_fields", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("record_id", recordId)
                    put("field_key", key)
                    put("value_text", clean)
                    put("method", ExtractionMethod.USER.raw)
                    put("confidence", 1.0)
                    put("state", FieldState.USER.raw)
                    put("created_ms", now)
                    put("updated_ms", now)
                })
                deriveColumnsIn(database, recordId, typeLabel)
                knowledgeIn(database, recordId)
                reindexIn(database, recordId)
            }
        }
    }

    override suspend fun replaceHighlights(
        recordId: String,
        sections: Set<HighlightSection>,
        methods: Set<ExtractionMethod>,
        highlights: List<RecordHighlight>
    ) {
        withContext(Dispatchers.IO) {
            write { database ->
                val dismissed = highlightsIn(database, recordId).filter { it.dismissed }.map { it.section to it.text }.toSet()
                val sectionArgs = sections.map { it.raw }
                val methodArgs = methods.map { it.raw }
                database.delete(
                    "record_highlights",
                    "record_id = ? AND dismissed = 0 AND section IN (${sectionArgs.joinToString(",") { "?" }}) AND method IN (${methodArgs.joinToString(",") { "?" }})",
                    (listOf(recordId) + sectionArgs + methodArgs).toTypedArray()
                )
                val now = System.currentTimeMillis()
                for (h in highlights) {
                    if ((h.section to h.text) in dismissed) continue
                    database.insertOrThrow("record_highlights", null, ContentValues().apply {
                        put("id", h.id)
                        put("record_id", recordId)
                        put("section", h.section.raw)
                        put("text", h.text)
                        put("method", h.method.raw)
                        h.provider?.let { put("provider", it) }
                        h.fieldId?.let { put("field_id", it) }
                        h.sourcePage?.let { put("source_page", it) }
                        put("confidence", h.confidence)
                        put("dismissed", 0)
                        put("position", h.position)
                        put("created_ms", now)
                    })
                }
                reindexIn(database, recordId)
            }
        }
    }

    override suspend fun dismissHighlight(highlightId: String) {
        withContext(Dispatchers.IO) {
            write { database -> database.execSQL("UPDATE record_highlights SET dismissed = 1 WHERE id = ?", arrayOf(highlightId)) }
        }
    }

    override suspend fun setReviewStatus(ids: Collection<String>, status: ReviewStatus) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            write { database ->
                for (id in ids) database.execSQL("UPDATE records SET review_status = ? WHERE id = ?", arrayOf(status.raw, id))
            }
        }
    }

    override suspend fun setAiUsed(id: String, mode: AiModeUsed, provider: String?) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.execSQL("UPDATE records SET ai_mode_used = ?, ai_provider = ? WHERE id = ?", arrayOf(mode.raw, provider, id))
            }
        }
    }

    override suspend fun setHashes(id: String, phash: String?, textSignature: String?) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.execSQL("UPDATE records SET phash = ?, text_signature = ? WHERE id = ?", arrayOf(phash, textSignature, id))
            }
        }
    }

    override suspend fun hashCandidates(excludingId: String): List<Triple<String, String?, String?>> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, phash, text_signature FROM records WHERE id != ? AND parent_id IS NULL AND (phash IS NOT NULL OR text_signature IS NOT NULL)",
            arrayOf(excludingId)
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getStringOrNull(1), c.getStringOrNull(2))) } }
    }

    // Jobs

    override suspend fun job(recordId: String): ProcessingJob? = withContext(Dispatchers.IO) { jobIn(db, recordId) }

    private fun jobIn(database: SQLiteDatabase, recordId: String): ProcessingJob? = database.rawQuery(
        "SELECT record_id, stage, attempts, next_attempt_ms, last_error, requested_mode, awaiting_consent, updated_ms FROM processing_jobs WHERE record_id = ?",
        arrayOf(recordId)
    ).use { c -> if (c.moveToFirst()) readJob(c) else null }

    override suspend fun saveJob(job: ProcessingJob) {
        withContext(Dispatchers.IO) {
            write { database ->
                val exists = database.rawQuery("SELECT 1 FROM records WHERE id = ?", arrayOf(job.recordId)).use { it.moveToFirst() }
                if (exists) database.insertWithOnConflict("processing_jobs", null, job.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    override suspend fun enqueueJobs(ids: Collection<String>, stage: ProcessingStage): List<String> = withContext(Dispatchers.IO) {
        write { database ->
            val now = System.currentTimeMillis()
            ids.distinct().filter { id ->
                val exists = database.rawQuery("SELECT 1 FROM records WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
                if (exists) {
                    val previous = jobIn(database, id)
                    database.insertWithOnConflict(
                        "processing_jobs", null,
                        ProcessingJob(recordId = id, stage = stage, requestedMode = previous?.requestedMode, updatedMs = now).toValues(),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    database.execSQL("UPDATE records SET processing_status = 'queued' WHERE id = ?", arrayOf(id))
                }
                exists
            }
        }
    }

    override suspend fun unfinishedJobs(): List<ProcessingJob> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT record_id, stage, attempts, next_attempt_ms, last_error, requested_mode, awaiting_consent, updated_ms FROM processing_jobs WHERE stage != 'done' ORDER BY updated_ms, record_id",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readJob(c)) } }
    }

    override suspend fun awaitingConsent(): List<String> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT j.record_id FROM processing_jobs j JOIN records r ON r.id = j.record_id WHERE j.awaiting_consent = 1 AND r.archived = 0 ORDER BY r.sort_date DESC",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    override suspend fun recordIdsWithoutJob(): List<String> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id FROM records WHERE id NOT IN (SELECT record_id FROM processing_jobs) ORDER BY created_ms DESC",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    override suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT value FROM records_meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    override suspend fun setMeta(key: String, value: String) {
        withContext(Dispatchers.IO) {
            write { database -> database.execSQL("INSERT OR REPLACE INTO records_meta(key, value) VALUES (?, ?)", arrayOf(key, value)) }
        }
    }

    // Duplicates & splits

    override suspend fun addDuplicateCandidate(candidate: DuplicateCandidate) {
        withContext(Dispatchers.IO) {
            write { database ->
                // An existing (possibly resolved) pair is never re-proposed.
                database.insertWithOnConflict("duplicate_candidates", null, ContentValues().apply {
                    put("record_id", candidate.recordId)
                    put("existing_id", candidate.existingId)
                    put("reason", candidate.reason.raw)
                    put("score", candidate.score)
                    put("resolution", candidate.resolution.raw)
                    put("created_ms", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    override suspend fun pendingDuplicates(recordId: String): List<DuplicateCandidate> = withContext(Dispatchers.IO) {
        pendingDuplicatesIn(db, recordId)
    }

    private fun pendingDuplicatesIn(database: SQLiteDatabase, recordId: String): List<DuplicateCandidate> = database.rawQuery(
        "SELECT record_id, existing_id, reason, score, resolution, created_ms FROM duplicate_candidates WHERE record_id = ? AND resolution = 'pending' ORDER BY score DESC",
        arrayOf(recordId)
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                DuplicateCandidate(
                    recordId = c.getString(0),
                    existingId = c.getString(1),
                    reason = DuplicateReason.fromRaw(c.getString(2)),
                    score = c.getDouble(3),
                    resolution = DuplicateResolution.fromRaw(c.getString(4)),
                    createdMs = c.getLong(5)
                )
            )
        }
    }

    override suspend fun resolveDuplicate(recordId: String, existingId: String, resolution: DuplicateResolution) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.execSQL(
                    "UPDATE duplicate_candidates SET resolution = ? WHERE record_id = ? AND existing_id = ?",
                    arrayOf(resolution.raw, recordId, existingId)
                )
            }
        }
    }

    override suspend fun replaceWithNew(existingId: String, newId: String) {
        withContext(Dispatchers.IO) {
            val existing = record(existingId) ?: return@withContext
            val incoming = record(newId) ?: return@withContext
            val source = files.resolve(incoming.filePath)?.takeIf { it.isFile } ?: return@withContext
            // Move the new original (and thumbnail) into the existing record's directory first, so a
            // failure leaves both records intact.
            val dir = files.recordDir(existingId).apply { mkdirs() }
            val ext = source.extension.ifEmpty { "bin" }
            val staged = File(dir, "original.$ext.replace.tmp")
            source.copyTo(staged, overwrite = true)
            val incomingThumb = files.resolve(incoming.thumbnailPath)?.takeIf { it.isFile }
            val stagedThumb = incomingThumb?.let { File(dir, "thumb.replace.tmp").also { t -> it.copyTo(t, overwrite = true) } }
            val oldOriginal = files.resolve(existing.filePath)
            write { database ->
                deleteIn(database, listOf(newId))
                database.delete("record_pages", "record_id = ?", arrayOf(existingId))
                database.delete("record_highlights", "record_id = ?", arrayOf(existingId))
                database.delete("observations", "record_id = ? AND state = 'suggested' AND field_id IS NOT NULL", arrayOf(existingId))
                database.delete("record_fields", "record_id = ? AND state = 'suggested'", arrayOf(existingId))
                database.delete("split_proposals", "record_id = ?", arrayOf(existingId))
                database.execSQL(
                    "UPDATE duplicate_candidates SET resolution = 'replaced' WHERE record_id = ? AND existing_id = ?",
                    arrayOf(newId, existingId)
                )
                val values = ContentValues().apply {
                    put("mime_type", incoming.mimeType)
                    put("file_type", incoming.fileType.raw)
                    put("file_size", incoming.fileSize)
                    put("page_count", incoming.pageCount)
                    put("file_path", "$existingId/original.$ext")
                    if (stagedThumb != null) put("thumbnail_path", "$existingId/${RecordFileStore.THUMB_NAME}") else putNull("thumbnail_path")
                    incoming.checksumSha256?.let { put("checksum_sha256", it) }
                    putNull("phash")
                    putNull("text_signature")
                    put("ai_mode_used", AiModeUsed.NONE.raw)
                    putNull("ai_provider")
                    put("processing_status", ProcessingStatus.QUEUED.raw)
                    putNull("processing_error")
                    put("updated_ms", System.currentTimeMillis())
                }
                database.update("records", values, "id = ?", arrayOf(existingId))
                knowledgeIn(database, existingId)
                reindexIn(database, existingId)
            }
            if (oldOriginal != null && oldOriginal.name != "original.$ext") oldOriginal.delete()
            staged.renameTo(File(dir, "original.$ext"))
            stagedThumb?.renameTo(File(dir, RecordFileStore.THUMB_NAME))
            files.renderPages.evict(existingId)
            files.deleteRecord(newId)
        }
    }

    override suspend fun mergeInto(existingId: String, newId: String) {
        withContext(Dispatchers.IO) {
            val orphaned = write { database ->
                val newNotes = database.rawQuery("SELECT notes FROM records WHERE id = ?", arrayOf(newId)).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else null }
                val oldNotes = database.rawQuery("SELECT notes FROM records WHERE id = ?", arrayOf(existingId)).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else null }
                val merged = listOfNotNull(oldNotes?.takeIf { it.isNotBlank() }, newNotes?.takeIf { it.isNotBlank() && it != oldNotes }).joinToString("\n\n")
                if (merged.isNotEmpty()) database.execSQL("UPDATE records SET notes = ? WHERE id = ?", arrayOf(merged, existingId))
                database.execSQL(
                    "INSERT OR IGNORE INTO record_tags(record_id, tag_id) SELECT ?, tag_id FROM record_tags WHERE record_id = ?",
                    arrayOf(existingId, newId)
                )
                database.execSQL(
                    "UPDATE duplicate_candidates SET resolution = 'merged' WHERE record_id = ? AND existing_id = ?",
                    arrayOf(newId, existingId)
                )
                moveLinksIn(database, fromId = newId, toId = existingId)
                touch(database, existingId)
                reindexIn(database, existingId)
                deleteIn(database, listOf(newId))
            }
            orphaned.forEach(files::deleteRecord)
        }
    }

    override suspend fun splitProposal(recordId: String): SplitProposal? = withContext(Dispatchers.IO) { splitIn(db, recordId) }

    private fun splitIn(database: SQLiteDatabase, recordId: String): SplitProposal? = database.rawQuery(
        "SELECT segments_json, status, created_ms, updated_ms FROM split_proposals WHERE record_id = ?",
        arrayOf(recordId)
    ).use { c ->
        if (!c.moveToFirst()) return null
        SplitProposal(recordId, SplitSegmentsJson.decode(c.getString(0)), SplitStatus.fromRaw(c.getString(1)), c.getLong(2), c.getLong(3))
    }

    override suspend fun saveSplitProposal(proposal: SplitProposal?, recordId: String) {
        withContext(Dispatchers.IO) {
            write { database ->
                val existing = splitIn(database, recordId)
                if (proposal == null) {
                    // Only a still-pending proposal is withdrawn; a user decision stays recorded.
                    if (existing?.status == SplitStatus.PENDING) database.delete("split_proposals", "record_id = ?", arrayOf(recordId))
                    return@write
                }
                if (existing != null && existing.status != SplitStatus.PENDING) return@write
                val now = System.currentTimeMillis()
                database.insertWithOnConflict("split_proposals", null, ContentValues().apply {
                    put("record_id", recordId)
                    put("segments_json", SplitSegmentsJson.encode(proposal.segments))
                    put("status", proposal.status.raw)
                    put("created_ms", existing?.createdMs ?: now)
                    put("updated_ms", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    override suspend fun setSplitStatus(recordId: String, status: SplitStatus) {
        withContext(Dispatchers.IO) {
            write { database ->
                database.execSQL(
                    "UPDATE split_proposals SET status = ?, updated_ms = ? WHERE record_id = ?",
                    arrayOf<Any>(status.raw, System.currentTimeMillis(), recordId)
                )
            }
        }
    }

    override suspend fun acceptSplit(parentId: String, segments: List<SplitSegment>, titleFallback: (SplitSegment) -> String): List<String> =
        withContext(Dispatchers.IO) {
            write { database ->
                val parent = database.rawQuery("SELECT $COLUMNS FROM records WHERE id = ?", arrayOf(parentId)).use { it.readAll().firstOrNull() }
                    ?: return@write emptyList()
                val pages = pagesIn(database, parentId)
                val fields = fieldsIn(database, parentId)
                val highlights = highlightsIn(database, parentId)
                val parentObservations = observationsIn(database, parentId)
                val tagIds = database.rawQuery("SELECT tag_id FROM record_tags WHERE record_id = ?", arrayOf(parentId))
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                val now = System.currentTimeMillis()
                val childIds = mutableListOf<String>()
                for ((index, segment) in segments.sortedBy { it.pageStart }.withIndex()) {
                    val childId = UUID.randomUUID().toString()
                    childIds += childId
                    val child = parent.copy(
                        seq = 0,
                        id = childId,
                        parentId = parentId,
                        pageStart = segment.pageStart,
                        pageEnd = segment.pageEnd,
                        title = segment.title.trim().ifEmpty { titleFallback(segment) },
                        recordType = segment.recordType,
                        category = segment.recordType.defaultCategory,
                        createdMs = now + index,
                        updatedMs = now,
                        pageCount = segment.pageEnd - segment.pageStart + 1,
                        thumbnailPath = parent.thumbnailPath.takeIf { segment.pageStart == 0 },
                        processingStatus = ProcessingStatus.QUEUED,
                        processingError = null,
                        reviewStatus = ReviewStatus.NONE,
                        favorite = false,
                        archived = false,
                        notes = null
                    )
                    database.insertOrThrow("records", null, child.toValues().apply {
                        put("type_method", ExtractionMethod.USER.raw)
                        put("type_confidence", segment.confidence)
                    })
                    // Pages keep their absolute index in the shared parent file.
                    pages.filter { it.pageIndex in segment.pageStart..segment.pageEnd }.forEach { page ->
                        database.insertOrThrow("record_pages", null, page.toValues(childId))
                    }
                    val fieldIdMap = HashMap<String, String>()
                    fields.filter { it.sourcePage != null && it.sourcePage in segment.pageStart..segment.pageEnd }.forEach { field ->
                        val newId = UUID.randomUUID().toString()
                        fieldIdMap[field.id] = newId
                        database.insertOrThrow("record_fields", null, field.copy(id = newId, recordId = childId).toRowValues())
                    }
                    parentObservations.filter { it.sourcePage != null && it.sourcePage in segment.pageStart..segment.pageEnd }.forEach { o ->
                        database.insertOrThrow(
                            "observations", null,
                            o.copy(id = UUID.randomUUID().toString(), recordId = childId, fieldId = o.fieldId?.let { fieldIdMap[it] }).toValues()
                        )
                    }
                    highlights.filter { it.sourcePage != null && it.sourcePage in segment.pageStart..segment.pageEnd }.forEach { h ->
                        database.insertOrThrow("record_highlights", null, ContentValues().apply {
                            put("id", UUID.randomUUID().toString())
                            put("record_id", childId)
                            put("section", h.section.raw)
                            put("text", h.text)
                            put("method", h.method.raw)
                            h.provider?.let { put("provider", it) }
                            h.fieldId?.let { fid -> fieldIdMap[fid]?.let { put("field_id", it) } }
                            h.sourcePage?.let { put("source_page", it) }
                            put("confidence", h.confidence)
                            put("dismissed", if (h.dismissed) 1 else 0)
                            put("position", h.position)
                            put("created_ms", now)
                        })
                    }
                    tagIds.forEach { tagId ->
                        database.insertWithOnConflict("record_tags", null, ContentValues().apply {
                            put("record_id", childId)
                            put("tag_id", tagId)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    database.insertOrThrow("processing_jobs", null, ProcessingJob(recordId = childId, stage = ProcessingStage.CLASSIFY, updatedMs = now).toValues())
                    rebuildEntitiesIn(database, childId)
                    reindexIn(database, childId)
                }
                // §22/§25: accepted split_from links (origin user) parent↔child and sibling↔sibling.
                val splitPairs = childIds.map { parentId to it } +
                    childIds.indices.flatMap { i -> (i + 1 until childIds.size).map { j -> childIds[i] to childIds[j] } }
                for ((x, y) in splitPairs) {
                    upsertLinkIn(database, x, y, LinkKind.SPLIT_FROM, LinkOrigin.USER, LinkStatus.ACCEPTED, 1.0, emptyList(), now, replace = true)
                }
                database.execSQL("UPDATE records SET archived = 1, review_status = 'reviewed', updated_ms = ? WHERE id = ?", arrayOf<Any>(now, parentId))
                database.execSQL("UPDATE split_proposals SET status = 'accepted', updated_ms = ? WHERE record_id = ?", arrayOf<Any>(now, parentId))
                childIds
            }
        }

    // Home sections

    override suspend fun processingSummary(): ProcessingSummary = withContext(Dispatchers.IO) {
        val database = db
        val processing = database.rawQuery(
            "SELECT COUNT(*) FROM records WHERE processing_status IN ('queued', 'extracting_text', 'analyzing')",
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val waiting = database.rawQuery("SELECT COUNT(*) FROM processing_jobs WHERE awaiting_consent = 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        ProcessingSummary(processing, waiting)
    }

    override suspend fun needsReview(limit: Int): List<HealthRecord> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT $COLUMNS FROM records WHERE (review_status = 'needs_review' OR processing_status = 'ai_pending_consent') AND archived = 0 ORDER BY updated_ms DESC, seq DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { it.readAll() }
    }

    override suspend fun importantHighlights(limit: Int): List<HighlightWithRecord> = withContext(Dispatchers.IO) {
        val database = db
        val rows = database.rawQuery(
            "SELECT h.id, h.record_id, h.section, h.text, h.method, h.provider, h.field_id, h.source_page, h.confidence, h.dismissed, h.position, h.created_ms " +
                "FROM record_highlights h JOIN records r ON r.id = h.record_id " +
                "WHERE h.section = 'important' AND h.dismissed = 0 AND r.archived = 0 " +
                "ORDER BY r.sort_date DESC, r.seq DESC, h.position LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(readHighlight(c)) } }
        val byId = records(rows.map { it.recordId }).associateBy { it.id }
        rows.mapNotNull { h -> byId[h.recordId]?.let { HighlightWithRecord(h, it) } }
    }

    override suspend fun reindex(recordId: String) {
        withContext(Dispatchers.IO) { write { database -> reindexIn(database, recordId) } }
    }

    override fun close() {
        runCatching { helper.close() }
    }

    // -- Phase 3: knowledge base (§19–§24) ----------------------------------------

    override suspend fun observations(recordId: String): List<Observation> = withContext(Dispatchers.IO) { observationsIn(db, recordId) }

    private fun observationsIn(database: SQLiteDatabase, recordId: String): List<Observation> = database.rawQuery(
        "SELECT $OBSERVATION_COLUMNS FROM observations WHERE record_id = ? ORDER BY source_page IS NULL, source_page, created_ms, rowid",
        arrayOf(recordId)
    ).use { c -> buildList { while (c.moveToNext()) add(readObservation(c)) } }

    private fun observationIn(database: SQLiteDatabase, id: String): Observation? = database.rawQuery(
        "SELECT $OBSERVATION_COLUMNS FROM observations WHERE id = ?",
        arrayOf(id)
    ).use { c -> if (c.moveToFirst()) readObservation(c) else null }

    override suspend fun analyteObservations(analyteId: String, includeExcluded: Boolean): List<Observation> = withContext(Dispatchers.IO) {
        // Served by idx_observations_trend (analyte_id, observed_date).
        db.rawQuery(
            "SELECT $OBSERVATION_COLUMNS FROM observations INDEXED BY idx_observations_trend WHERE analyte_id = ? AND state != 'rejected' " +
                (if (includeExcluded) "" else "AND excluded_from_trends = 0 ") +
                "AND observed_date IS NOT NULL ORDER BY observed_date, created_ms",
            arrayOf(analyteId)
        ).use { c -> buildList { while (c.moveToNext()) add(readObservation(c)) } }
    }

    // -- Phase 4: Coach (docs §26–§29) ----------------------------------------------------------

    override suspend fun allRecords(): List<HealthRecord> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $COLUMNS FROM records ORDER BY seq", null).use { it.readAll() }
    }

    private fun <T> byRecord(ids: Collection<String>, sql: (String) -> String, read: (Cursor) -> T, recordIdOf: (T) -> String): Map<String, List<T>> {
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<T>>()
        ids.distinct().chunked(500).forEach { chunk ->
            db.rawQuery(sql(chunk.joinToString(",") { "?" }), chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) read(c).let { out.getOrPut(recordIdOf(it)) { mutableListOf() }.add(it) }
            }
        }
        return out
    }

    override suspend fun fieldsFor(recordIds: Collection<String>): Map<String, List<RecordField>> = withContext(Dispatchers.IO) {
        byRecord(recordIds, { marks -> "SELECT $FIELD_COLUMNS FROM record_fields WHERE record_id IN ($marks) ORDER BY record_id, field_key, source_page, created_ms, rowid" }, ::readField) { it.recordId }
    }

    override suspend fun observationsFor(recordIds: Collection<String>): Map<String, List<Observation>> = withContext(Dispatchers.IO) {
        byRecord(recordIds, { marks -> "SELECT $OBSERVATION_COLUMNS FROM observations WHERE record_id IN ($marks) ORDER BY record_id, source_page IS NULL, source_page, created_ms, rowid" }, ::readObservation) { it.recordId }
    }

    override suspend fun highlightsFor(recordIds: Collection<String>): Map<String, List<RecordHighlight>> = withContext(Dispatchers.IO) {
        byRecord(recordIds, { marks -> "SELECT $HIGHLIGHT_COLUMNS FROM record_highlights WHERE record_id IN ($marks) ORDER BY record_id, section, position, created_ms" }, ::readHighlight) { it.recordId }
    }

    override suspend fun links(recordId: String): List<RecordLink> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $LINK_COLUMNS FROM record_links WHERE a_id = ? OR b_id = ?", arrayOf(recordId, recordId))
            .use { c -> buildList { while (c.moveToNext()) add(readLink(c)) } }
    }

    override suspend fun observationsOfAnalytes(analyteIds: Collection<String>): List<Observation> = withContext(Dispatchers.IO) {
        if (analyteIds.isEmpty()) return@withContext emptyList()
        analyteIds.distinct().chunked(500).flatMap { chunk ->
            db.rawQuery(
                "SELECT $OBSERVATION_COLUMNS FROM observations WHERE analyte_id IN (${chunk.joinToString(",") { "?" }}) ORDER BY observed_date, created_ms, id",
                chunk.toTypedArray()
            ).use { c -> buildList { while (c.moveToNext()) add(readObservation(c)) } }
        }
    }

    override suspend fun unmappedObservations(): List<Observation> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $OBSERVATION_COLUMNS FROM observations WHERE analyte_id IS NULL ORDER BY observed_date, created_ms, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(readObservation(c)) } }
    }

    override suspend fun ftsMatchinfo(terms: List<String>): Map<Long, IntArray> = withContext(Dispatchers.IO) {
        val match = RecordsSearchText.matchTerms(terms) ?: return@withContext emptyMap()
        db.rawQuery("SELECT docid, matchinfo(records_fts, 'pcnalx') FROM records_fts WHERE records_fts MATCH ?", arrayOf(match)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), RecordSearchRanking.decodeMatchinfo(c.getBlob(1))) }
        }
    }

    override suspend fun trendsForRecord(recordId: String): Map<String, List<Observation>> = withContext(Dispatchers.IO) {
        val database = db
        val ids = database.rawQuery(
            "SELECT DISTINCT analyte_id FROM observations WHERE record_id = ? AND analyte_id IS NOT NULL AND state != 'rejected'",
            arrayOf(recordId)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ids.associateWith { analyteId ->
            database.rawQuery(
                "SELECT $OBSERVATION_COLUMNS FROM observations INDEXED BY idx_observations_trend WHERE analyte_id = ? AND state != 'rejected' " +
                    "AND excluded_from_trends = 0 AND observed_date IS NOT NULL ORDER BY observed_date, created_ms",
                arrayOf(analyteId)
            ).use { c -> buildList { while (c.moveToNext()) add(readObservation(c)) } }
        }
    }

    override suspend fun refreshKnowledge(recordId: String) {
        withContext(Dispatchers.IO) {
            write { database ->
                knowledgeIn(database, recordId)
                reindexIn(database, recordId)
            }
        }
    }

    /** §19 promotion + entity rebuild, inside the caller's transaction. */
    private fun knowledgeIn(database: SQLiteDatabase, recordId: String) {
        promoteIn(database, recordId)
        rebuildEntitiesIn(database, recordId)
    }

    private fun promoteIn(database: SQLiteDatabase, recordId: String) {
        val row = database.rawQuery("SELECT document_date, sort_date FROM records WHERE id = ?", arrayOf(recordId)).use { c ->
            if (c.moveToFirst()) c.getStringOrNull(0) to c.getStringOrNull(1) else null
        } ?: return
        val now = System.currentTimeMillis()
        val existing = observationsIn(database, recordId)
        val promotion = ObservationRules.promote(
            fieldsIn(database, recordId), existing, ObservationRules.RecordContext(recordId, row.first, row.second),
            userAliasesIn(database), now, catalog()
        ) { UUID.randomUUID().toString() }
        for ((insert, obs) in promotion.writes()) {
            if (insert) database.insertOrThrow("observations", null, obs.toValues())
            else database.update("observations", obs.toValues(), "id = ?", arrayOf(obs.id))
        }
    }

    private fun userAliasesIn(database: SQLiteDatabase): Map<String, String> =
        database.rawQuery("SELECT normalized_name, analyte_id FROM analyte_user_aliases", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    override suspend fun userAliases(): Map<String, String> = withContext(Dispatchers.IO) { userAliasesIn(db) }

    override suspend fun editObservation(observationId: String, edit: ObservationEdit) {
        withContext(Dispatchers.IO) {
            write { database ->
                val current = observationIn(database, observationId) ?: return@write
                val now = System.currentTimeMillis()
                val result = ObservationRules.edit(current, edit, now, catalog())
                if (result.error != null) return@write
                database.update("observations", result.observation.toValues(), "id = ?", arrayOf(observationId))
                val remapped = edit.setAnalyte && edit.analyteId != null && edit.analyteId != current.analyteId
                if (remapped && edit.rememberAlias) {
                    val key = AnalyteCatalog.normalizeTestName(current.rawName)
                    if (key.isNotEmpty()) {
                        database.insertWithOnConflict("analyte_user_aliases", null, ContentValues().apply {
                            put("normalized_name", key)
                            put("analyte_id", edit.analyteId)
                            put("created_ms", now)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }
                touch(database, current.recordId)
                reindexIn(database, current.recordId)
            }
        }
    }

    override suspend fun removeObservation(observationId: String) {
        withContext(Dispatchers.IO) {
            write { database ->
                val current = observationIn(database, observationId) ?: return@write
                database.execSQL(
                    "UPDATE observations SET state = 'rejected', updated_ms = ? WHERE id = ?",
                    arrayOf<Any>(System.currentTimeMillis(), observationId)
                )
                reindexIn(database, current.recordId)
            }
        }
    }

    override suspend fun addObservation(input: NewUserObservation): Observation? {
        if (input.valueText.isBlank()) return null
        return withContext(Dispatchers.IO) {
            write { database ->
                val record = database.rawQuery("SELECT document_date, sort_date FROM records WHERE id = ?", arrayOf(input.recordId)).use { c ->
                    if (c.moveToFirst()) c.getStringOrNull(0) to c.getStringOrNull(1) else null
                } ?: return@write null
                val now = System.currentTimeMillis()
                // Default date = the record's observed date (§24); an explicit date is user-owned.
                val observed = ObservationRules.observedDate(ObservationRules.RecordContext(input.recordId, record.first, record.second), fieldsIn(database, input.recordId))
                val obs = ObservationRules.newUserObservation(input, UUID.randomUUID().toString(), catalog(), observed, now)
                database.insertOrThrow("observations", null, obs.toValues())
                touch(database, input.recordId)
                reindexIn(database, input.recordId)
                obs
            }
        }
    }

    override suspend fun applyUserAlias(rawName: String, analyteId: String): Int = withContext(Dispatchers.IO) {
        write { database ->
            val targets = database.rawQuery(
                "SELECT $OBSERVATION_COLUMNS FROM observations WHERE analyte_id IS NULL AND state != 'rejected'",
                null
            ).use { c -> buildList { while (c.moveToNext()) add(readObservation(c)) } }
            val result = ObservationRules.applyUserAlias(targets, rawName, analyteId, System.currentTimeMillis(), catalog())
            if (result.error != null) return@write 0
            val updated = result.observations.filter { it.id in result.updatedIds }
            for (o in updated) database.update("observations", o.toValues(), "id = ?", arrayOf(o.id))
            updated.map { it.recordId }.distinct().forEach { reindexIn(database, it) }
            updated.size
        }
    }

    override suspend fun unmappedWithName(rawName: String): Int = withContext(Dispatchers.IO) {
        val key = AnalyteCatalog.normalizeTestName(rawName)
        db.rawQuery("SELECT raw_name FROM observations WHERE analyte_id IS NULL AND state != 'rejected'", null).use { c ->
            var n = 0
            while (c.moveToNext()) if (key in AnalyteCatalog.testNameKeys(c.getString(0))) n++
            n
        }
    }

    // Entities

    private fun rebuildEntitiesIn(database: SQLiteDatabase, recordId: String) {
        database.delete("record_entities", "record_id = ?", arrayOf(recordId))
        val now = System.currentTimeMillis()
        val rebuild = EntityRules.rebuild(fieldsIn(database, recordId))
        val ids = HashMap<Pair<EntityKind, String>, String>()
        for (ref in rebuild.entities) {
            val existing = database.rawQuery(
                "SELECT id, specialty FROM entities WHERE kind = ? AND normalized_name = ?",
                arrayOf(ref.kind.raw, ref.normalizedName)
            ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getStringOrNull(1) else null }
            // Upsert by (kind, normalized_name): an existing display name is kept, a NULL specialty filled.
            val entityId = existing?.first ?: UUID.randomUUID().toString().also { id ->
                database.insertOrThrow("entities", null, ContentValues().apply {
                    put("id", id)
                    put("kind", ref.kind.raw)
                    put("display_name", ref.displayName)
                    put("normalized_name", ref.normalizedName)
                    ref.specialty?.let { put("specialty", it) }
                    put("created_ms", now)
                    put("updated_ms", now)
                })
            }
            if (existing != null && existing.second == null && ref.specialty != null) {
                database.execSQL("UPDATE entities SET specialty = ?, updated_ms = ? WHERE id = ?", arrayOf<Any>(ref.specialty, now, entityId))
            }
            ids[ref.kind to ref.normalizedName] = entityId
        }
        for (link in rebuild.recordEntities) {
            val entityId = ids[link.kind to link.normalizedName] ?: continue
            database.insertWithOnConflict("record_entities", null, ContentValues().apply {
                put("record_id", recordId)
                put("entity_id", entityId)
                put("role", link.role)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        deleteOrphanEntities(database)
    }

    private fun deleteOrphanEntities(database: SQLiteDatabase) {
        database.execSQL("DELETE FROM entities WHERE id NOT IN (SELECT entity_id FROM record_entities)")
    }

    override suspend fun entities(kind: EntityKind): List<HealthEntity> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT e.id, e.kind, e.display_name, e.normalized_name, e.specialty, COUNT(DISTINCT re.record_id) AS n " +
                "FROM entities e JOIN record_entities re ON re.entity_id = e.id JOIN records r ON r.id = re.record_id " +
                "WHERE e.kind = ? AND r.archived = 0 GROUP BY e.id ORDER BY n DESC, e.display_name COLLATE NOCASE",
            arrayOf(kind.raw)
        ).use { c -> buildList { while (c.moveToNext()) add(readEntity(c, c.getInt(5))) } }
    }

    override suspend fun recordEntities(recordId: String): List<Pair<HealthEntity, String>> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT e.id, e.kind, e.display_name, e.normalized_name, e.specialty, re.role FROM record_entities re " +
                "JOIN entities e ON e.id = re.entity_id WHERE re.record_id = ? ORDER BY re.role",
            arrayOf(recordId)
        ).use { c -> buildList { while (c.moveToNext()) add(readEntity(c, 0) to c.getString(5)) } }
    }

    private fun readEntity(c: Cursor, count: Int) = HealthEntity(
        id = c.getString(0),
        kind = EntityKind.fromRaw(c.getString(1)),
        displayName = c.getString(2),
        normalizedName = c.getString(3),
        specialty = c.getStringOrNull(4),
        recordCount = count
    )

    // Links

    private fun linkIn(database: SQLiteDatabase, x: String, y: String): RecordLink? {
        val (a, b) = RecordLink.pair(x, y)
        return database.rawQuery("SELECT $LINK_COLUMNS FROM record_links WHERE a_id = ? AND b_id = ?", arrayOf(a, b))
            .use { c -> if (c.moveToFirst()) readLink(c) else null }
    }

    private fun upsertLinkIn(
        database: SQLiteDatabase,
        x: String,
        y: String,
        kind: LinkKind,
        origin: LinkOrigin,
        status: LinkStatus,
        score: Double,
        reasons: List<String>,
        now: Long,
        replace: Boolean
    ) {
        if (x == y) return
        val (a, b) = RecordLink.pair(x, y)
        val existing = linkIn(database, a, b)
        if (existing != null && !replace) return
        database.insertWithOnConflict("record_links", null, ContentValues().apply {
            put("a_id", a)
            put("b_id", b)
            put("kind", kind.raw)
            put("origin", origin.raw)
            put("status", status.raw)
            put("score", score)
            put("reasons_json", RecordJsonList.encode(reasons))
            put("created_ms", existing?.createdMs ?: now)
            put("updated_ms", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun related(recordId: String): List<RelatedRecord> = withContext(Dispatchers.IO) {
        val database = db
        val links = database.rawQuery(
            "SELECT $LINK_COLUMNS FROM record_links WHERE (a_id = ? OR b_id = ?) AND status != 'rejected'",
            arrayOf(recordId, recordId)
        ).use { c -> buildList { while (c.moveToNext()) add(readLink(c)) } }
        val others = links.map { it.other(recordId) }.distinct().chunked(500).flatMap { chunk ->
            database.rawQuery("SELECT $COLUMNS FROM records WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { it.readAll() }
        }.associateBy { it.id }
        links.mapNotNull { link -> others[link.other(recordId)]?.let { RelatedRecord(link, it) } }
            .sortedWith(
                compareBy<RelatedRecord> { if (it.link.isLinked) 0 else 1 }
                    .thenByDescending { if (it.link.isLinked) it.record.sortDate else "" }
                    .thenByDescending { it.link.score }
                    .thenByDescending { it.record.sortDate }
            )
    }

    override suspend fun link(a: String, b: String, kind: LinkKind) {
        withContext(Dispatchers.IO) {
            write { database ->
                val exists = database.rawQuery("SELECT COUNT(*) FROM records WHERE id IN (?, ?)", arrayOf(a, b)).use { c -> c.moveToFirst() && c.getInt(0) == 2 }
                if (!exists) return@write
                upsertLinkIn(database, a, b, kind, LinkOrigin.USER, LinkStatus.ACCEPTED, 1.0, emptyList(), System.currentTimeMillis(), replace = true)
            }
        }
    }

    override suspend fun unlink(a: String, b: String) {
        withContext(Dispatchers.IO) {
            write { database ->
                val existing = linkIn(database, a, b) ?: return@write
                val (x, y) = RecordLink.pair(a, b)
                if (existing.origin == LinkOrigin.USER) {
                    database.delete("record_links", "a_id = ? AND b_id = ?", arrayOf(x, y))
                } else {
                    database.execSQL("UPDATE record_links SET status = 'rejected', updated_ms = ? WHERE a_id = ? AND b_id = ?", arrayOf<Any>(System.currentTimeMillis(), x, y))
                }
            }
        }
    }

    override suspend fun acceptLink(a: String, b: String) = setLinkStatus(a, b, LinkStatus.ACCEPTED)
    override suspend fun rejectLink(a: String, b: String) = setLinkStatus(a, b, LinkStatus.REJECTED)

    private suspend fun setLinkStatus(a: String, b: String, status: LinkStatus) {
        withContext(Dispatchers.IO) {
            write { database ->
                val (x, y) = RecordLink.pair(a, b)
                database.execSQL(
                    "UPDATE record_links SET status = ?, updated_ms = ? WHERE a_id = ? AND b_id = ? AND origin = 'suggested'",
                    arrayOf<Any>(status.raw, System.currentTimeMillis(), x, y)
                )
            }
        }
    }

    private fun moveLinksIn(database: SQLiteDatabase, fromId: String, toId: String) {
        val links = database.rawQuery("SELECT $LINK_COLUMNS FROM record_links WHERE a_id = ? OR b_id = ?", arrayOf(fromId, fromId))
            .use { c -> buildList { while (c.moveToNext()) add(readLink(c)) } }
        val now = System.currentTimeMillis()
        // §25: links move to the kept record; self-links dropped; on a collision the stronger row wins
        // (user or accepted > suggested > rejected).
        fun rank(l: RecordLink) = when {
            l.origin == LinkOrigin.USER || l.status == LinkStatus.ACCEPTED -> 2
            l.status == LinkStatus.SUGGESTED -> 1
            else -> 0
        }
        for (link in links) {
            val other = link.other(fromId)
            if (other == toId) continue
            val existing = linkIn(database, toId, other)
            if (existing != null && rank(existing) >= rank(link)) continue
            upsertLinkIn(database, toId, other, link.kind, link.origin, link.status, link.score, link.reasons, now, replace = true)
        }
    }

    override suspend fun suggestRelations(recordId: String, today: LocalDate): Int = withContext(Dispatchers.IO) {
        write { database ->
            val target = factsIn(database, listOf(recordId)).firstOrNull() ?: return@write 0
            val date = runCatching { LocalDate.parse(target.sortDate) }.getOrNull() ?: return@write 0
            val from = date.minusDays(RelationSuggester.WINDOW_DAYS).toString()
            val to = date.plusDays(RelationSuggester.WINDOW_DAYS).toString()
            // Candidates by idx_records_timeline range on sort_date.
            val candidateIds = database.rawQuery(
                "SELECT id FROM records WHERE sort_date >= ? AND sort_date <= ? AND id != ? ORDER BY sort_date DESC LIMIT 2000",
                arrayOf(from, to, recordId)
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val existing = database.rawQuery("SELECT a_id, b_id, status FROM record_links WHERE a_id = ? OR b_id = ?", arrayOf(recordId, recordId))
                .use { c -> buildList { while (c.moveToNext()) add(RelationSuggester.ExistingLink(c.getString(0), c.getString(1), c.getString(2))) } }
            val suggestions = RelationSuggester.suggest(target, factsIn(database, candidateIds), existing)
            val now = System.currentTimeMillis()
            for (s in suggestions) {
                upsertLinkIn(database, s.aId, s.bId, LinkKind.fromRaw(s.kind), LinkOrigin.SUGGESTED, LinkStatus.SUGGESTED, s.score, s.reasons, now, replace = false)
            }
            suggestions.size
        }
    }

    /** §22 facts for [ids] in a few grouped queries. */
    private fun factsIn(database: SQLiteDatabase, ids: List<String>): List<RelationSuggester.RecordFacts> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<RelationSuggester.RecordFacts>()
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            val records = database.rawQuery(
                "SELECT id, record_type, sort_date, archived, EXISTS (SELECT 1 FROM records c WHERE c.parent_id = records.id) FROM records WHERE id IN ($marks)",
                args
            ).use { c -> buildList { while (c.moveToNext()) add(arrayOf<Any?>(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4) != 0)) } }
            val fields = database.rawQuery(
                "SELECT $FIELD_COLUMNS FROM record_fields WHERE record_id IN ($marks) AND field_key IN ('report_name', 'follow_up_date', 'test_result') ORDER BY field_key, source_page, created_ms, id",
                args
            ).use { c -> buildList { while (c.moveToNext()) add(readField(c)) } }.groupBy { it.recordId }
            val analytes = database.rawQuery(
                "SELECT record_id, analyte_id FROM observations WHERE record_id IN ($marks) AND analyte_id IS NOT NULL AND state != 'rejected' ORDER BY created_ms",
                args
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }.groupBy({ it.first }, { it.second })
            val entities = database.rawQuery(
                "SELECT re.record_id, e.kind, e.normalized_name FROM record_entities re JOIN entities e ON e.id = re.entity_id WHERE re.record_id IN ($marks)",
                args
            ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }.groupBy { it.first }
            val cat = catalog()
            for (r in records) {
                val id = r[0] as String
                val f = fields[id].orEmpty()
                val ents = entities[id].orEmpty()
                out += RelationSuggester.RecordFacts(
                    id = id,
                    recordType = r[1] as String,
                    sortDate = r[2] as String,
                    archived = r[3] as Boolean,
                    splitParent = r[4] as Boolean,
                    panels = ObservationRules.recordPanels(f, cat),
                    reportName = ExtractionWriter.best(f, FieldKey.REPORT_NAME)?.valueText,
                    analytes = analytes[id].orEmpty().distinct(),
                    doctors = ents.filter { it.second == "doctor" }.map { it.third }.distinct(),
                    facilities = ents.filter { it.second == "facility" }.map { it.third }.distinct(),
                    followUpDates = f.filter { it.key == FieldKey.FOLLOW_UP_DATE && it.state != FieldState.REJECTED }.map { it.valueText }
                )
            }
        }
        return out
    }

    override suspend fun acceptedLinksAmong(ids: Collection<String>): List<RecordLink> = withContext(Dispatchers.IO) {
        if (ids.size < 2) return@withContext emptyList()
        val set = ids.toSet()
        ids.distinct().chunked(400).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT $LINK_COLUMNS FROM record_links WHERE status = 'accepted' AND (a_id IN ($marks) OR b_id IN ($marks))",
                (chunk + chunk).toTypedArray()
            ).use { c -> buildList { while (c.moveToNext()) add(readLink(c)) } }
        }.filter { it.aId in set && it.bId in set }.distinctBy { it.aId to it.bId }
    }

    override suspend fun valueHits(conditions: List<AnalyteCondition>, analyteIds: List<String>, limit: Int): List<ValueHit> = withContext(Dispatchers.IO) {
        val database = db
        val rows = LinkedHashMap<String, Observation>()
        val perQuery = limit.coerceAtLeast(1)
        val queries = conditions.map { RecordsQuerySql.observationCondition(it, "o") } +
            analyteIds.filter { id -> conditions.none { it.analyteId == id } }
                .map { RecordsQuerySql.observationCondition(AnalyteCondition(it), "o") }
        for (q in queries) {
            database.rawQuery(
                "SELECT ${OBSERVATION_COLUMNS.split(", ").joinToString(", ") { "o.$it" }} FROM observations o JOIN records r ON r.id = o.record_id " +
                    "WHERE r.archived = 0 AND ${q.sql} ORDER BY o.observed_date DESC, o.created_ms DESC LIMIT ?",
                (q.args + perQuery.toString()).toTypedArray()
            ).use { c -> while (c.moveToNext()) readObservation(c).let { rows.putIfAbsent(it.id, it) } }
        }
        val records = rows.values.map { it.recordId }.distinct().chunked(500).flatMap { chunk ->
            database.rawQuery("SELECT $COLUMNS FROM records WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { it.readAll() }
        }.associateBy { it.id }
        val cat = catalog()
        rows.values
            .sortedWith(compareByDescending<Observation> { it.observedDate ?: "" }.thenByDescending { it.createdMs })
            .mapNotNull { o -> records[o.recordId]?.let { ValueHit(o, cat.displayName(o.analyteId) ?: o.rawName, it) } }
            .take(limit)
    }

    override suspend fun recordIdsNeedingKnowledge(): List<String> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id FROM records r WHERE EXISTS (SELECT 1 FROM record_fields f WHERE f.record_id = r.id AND f.field_key IN ('test_result', 'doctor_name', 'facility')) " +
                "AND NOT EXISTS (SELECT 1 FROM observations o WHERE o.record_id = r.id) " +
                "AND NOT EXISTS (SELECT 1 FROM record_entities e WHERE e.record_id = r.id) ORDER BY sort_date DESC",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    private fun Observation.toValues() = ContentValues().apply {
        put("id", id)
        put("record_id", recordId)
        if (fieldId != null) put("field_id", fieldId) else putNull("field_id")
        if (analyteId != null) put("analyte_id", analyteId) else putNull("analyte_id")
        if (analyteMethod != null) put("analyte_method", analyteMethod.raw) else putNull("analyte_method")
        put("raw_name", rawName)
        if (valueNum != null) put("value_num", valueNum) else putNull("value_num")
        put("value_text", valueText)
        if (unit != null) put("unit", unit) else putNull("unit")
        if (canonicalValue != null) put("canonical_value", canonicalValue) else putNull("canonical_value")
        if (canonicalUnit != null) put("canonical_unit", canonicalUnit) else putNull("canonical_unit")
        if (refLow != null) put("ref_low", refLow) else putNull("ref_low")
        if (refHigh != null) put("ref_high", refHigh) else putNull("ref_high")
        if (refText != null) put("ref_text", refText) else putNull("ref_text")
        put("flag", flag.raw)
        if (observedDate != null) put("observed_date", observedDate) else putNull("observed_date")
        if (observedDateMethod != null) put("observed_date_method", observedDateMethod) else putNull("observed_date_method")
        put("method", method.raw)
        put("confidence", confidence)
        put("state", state.raw)
        if (sourcePage != null) put("source_page", sourcePage) else putNull("source_page")
        if (sourceBbox != null) put("source_bbox", sourceBbox) else putNull("source_bbox")
        if (evidence != null) put("evidence", evidence) else putNull("evidence")
        put("excluded_from_trends", if (excludedFromTrends) 1 else 0)
        put("created_ms", createdMs)
        put("updated_ms", updatedMs)
    }

    private fun readObservation(c: Cursor) = Observation(
        id = c.getString(0),
        recordId = c.getString(1),
        fieldId = c.getStringOrNull(2),
        analyteId = c.getStringOrNull(3),
        analyteMethod = AnalyteMethod.fromRaw(c.getStringOrNull(4)),
        rawName = c.getString(5),
        valueNum = if (c.isNull(6)) null else c.getDouble(6),
        valueText = c.getString(7),
        unit = c.getStringOrNull(8),
        canonicalValue = if (c.isNull(9)) null else c.getDouble(9),
        canonicalUnit = c.getStringOrNull(10),
        refLow = if (c.isNull(11)) null else c.getDouble(11),
        refHigh = if (c.isNull(12)) null else c.getDouble(12),
        refText = c.getStringOrNull(13),
        flag = ResultFlag.fromRaw(c.getStringOrNull(14)),
        observedDate = c.getStringOrNull(15),
        observedDateMethod = c.getStringOrNull(16),
        method = ExtractionMethod.fromRaw(c.getString(17)),
        confidence = c.getDouble(18),
        state = FieldState.fromRaw(c.getString(19)),
        sourcePage = c.getIntOrNull(20),
        sourceBbox = c.getStringOrNull(21),
        evidence = c.getStringOrNull(22),
        excludedFromTrends = c.getInt(23) != 0,
        createdMs = c.getLong(24),
        updatedMs = c.getLong(25)
    )

    private fun readLink(c: Cursor) = RecordLink(
        aId = c.getString(0),
        bId = c.getString(1),
        kind = LinkKind.fromRaw(c.getString(2)),
        origin = LinkOrigin.fromRaw(c.getString(3)),
        status = LinkStatus.fromRaw(c.getString(4)),
        score = c.getDouble(5),
        reasons = RecordJsonList.decode(c.getStringOrNull(6)),
        createdMs = c.getLong(7),
        updatedMs = c.getLong(8)
    )

    // -- FTS ------------------------------------------------------------------

    /** Rebuilds the single FTS row for [recordId] from the §17 columns. */
    private fun reindexIn(database: SQLiteDatabase, recordId: String) {
        val record = database.rawQuery("SELECT $COLUMNS FROM records WHERE id = ?", arrayOf(recordId)).use { it.readAll().firstOrNull() } ?: return
        val tagNames = database.rawQuery(
            "SELECT t.name FROM tags t JOIN record_tags rt ON rt.tag_id = t.id WHERE rt.record_id = ?",
            arrayOf(recordId)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val pageTexts = database.rawQuery(
            "SELECT text FROM record_pages WHERE record_id = ? ORDER BY page_index",
            arrayOf(recordId)
        ).use { c -> buildList { while (c.moveToNext()) c.getStringOrNull(0)?.let(::add) } }
        val row = SearchIndexer.build(
            title = record.title,
            recordType = record.recordType,
            notes = record.notes,
            tagNames = tagNames,
            pageTexts = pageTexts,
            fields = fieldsIn(database, recordId),
            highlights = highlightsIn(database, recordId),
            analyteNames = observationsIn(database, recordId)
                .filter { it.state != FieldState.REJECTED && catalog().analyte(it.analyteId) != null }
                .mapNotNull { it.analyteId }
                .distinct()
                .sorted()
                .mapNotNull { catalog().displayName(it) }
        )
        database.delete("records_fts", "docid = ?", arrayOf(record.seq.toString()))
        database.execSQL(
            "INSERT INTO records_fts(docid, title, people, clinical, body, notes_tags, highlights) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(record.seq, row.title, row.people, row.clinical, row.body, row.notesTags, row.highlights)
        )
    }

    private fun touch(database: SQLiteDatabase, recordId: String) {
        database.execSQL("UPDATE records SET updated_ms = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), recordId))
    }

    private fun createdMsOf(database: SQLiteDatabase, id: String): Long? =
        database.rawQuery("SELECT created_ms FROM records WHERE id = ?", arrayOf(id)).use { c ->
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

    private fun ExtractedField.toValues(recordId: String, id: String, now: Long, created: Boolean) = ContentValues().apply {
        if (created) {
            put("id", id)
            put("record_id", recordId)
            put("field_key", key)
            put("state", FieldState.SUGGESTED.raw)
            put("created_ms", now)
        }
        put("value_text", valueText.trim())
        if (valueJson != null) put("value_json", valueJson) else putNull("value_json")
        put("method", method.raw)
        put("confidence", confidence)
        if (sourcePage != null) put("source_page", sourcePage) else putNull("source_page")
        if (sourceBbox != null) put("source_bbox", sourceBbox) else putNull("source_bbox")
        if (evidence != null) put("evidence", evidence) else putNull("evidence")
        put("updated_ms", now)
    }

    private fun RecordField.toRowValues() = ContentValues().apply {
        put("id", id)
        put("record_id", recordId)
        put("field_key", key)
        put("value_text", valueText)
        valueJson?.let { put("value_json", it) }
        put("method", method.raw)
        put("confidence", confidence)
        put("state", state.raw)
        sourcePage?.let { put("source_page", it) }
        sourceBbox?.let { put("source_bbox", it) }
        evidence?.let { put("evidence", it) }
        put("created_ms", createdMs)
        put("updated_ms", updatedMs)
    }

    private fun ProcessingJob.toValues() = ContentValues().apply {
        put("record_id", recordId)
        put("stage", stage.raw)
        put("attempts", attempts)
        put("next_attempt_ms", nextAttemptMs)
        if (lastError != null) put("last_error", lastError) else putNull("last_error")
        if (requestedMode != null) put("requested_mode", requestedMode) else putNull("requested_mode")
        put("awaiting_consent", if (awaitingConsent) 1 else 0)
        put("updated_ms", if (updatedMs > 0) updatedMs else System.currentTimeMillis())
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
        notes = c.getStringOrNull(30),
        aiModeUsed = AiModeUsed.fromRaw(c.getStringOrNull(31))
    )

    private fun readField(c: Cursor) = RecordField(
        id = c.getString(0),
        recordId = c.getString(1),
        key = c.getString(2),
        valueText = c.getString(3),
        valueJson = c.getStringOrNull(4),
        method = ExtractionMethod.fromRaw(c.getString(5)),
        confidence = c.getDouble(6),
        state = FieldState.fromRaw(c.getString(7)),
        sourcePage = c.getIntOrNull(8),
        sourceBbox = c.getStringOrNull(9),
        evidence = c.getStringOrNull(10),
        createdMs = c.getLong(11),
        updatedMs = c.getLong(12)
    )

    private fun readHighlight(c: Cursor) = RecordHighlight(
        id = c.getString(0),
        recordId = c.getString(1),
        section = HighlightSection.fromRaw(c.getString(2)),
        text = c.getString(3),
        method = ExtractionMethod.fromRaw(c.getString(4)),
        provider = c.getStringOrNull(5),
        fieldId = c.getStringOrNull(6),
        sourcePage = c.getIntOrNull(7),
        confidence = c.getDouble(8),
        dismissed = c.getInt(9) != 0,
        position = c.getInt(10),
        createdMs = c.getLong(11)
    )

    private fun readJob(c: Cursor) = ProcessingJob(
        recordId = c.getString(0),
        stage = ProcessingStage.fromRaw(c.getString(1)),
        attempts = c.getInt(2),
        nextAttemptMs = c.getLong(3),
        lastError = c.getStringOrNull(4),
        requestedMode = c.getStringOrNull(5),
        awaitingConsent = c.getInt(6) != 0,
        updatedMs = c.getLong(7)
    )

    companion object {
        const val MAX_TAG_LENGTH = 40
        private const val CANDIDATE_LIMIT = 1_000
        private val AUTO_TITLE_SOURCES = setOf(RecordSource.SCAN, RecordSource.CAMERA, RecordSource.PASTE, RecordSource.NOTE, RecordSource.PHOTOS)

        /** Base `records` columns (schema.sql) in [readRecord] order (never page text). */
        const val BASE_COLUMNS = "seq, id, parent_id, page_start, page_end, title, record_type, category, source, " +
            "import_method, source_app, original_filename, created_ms, updated_ms, document_date, " +
            "document_date_precision, document_date_method, sort_date, mime_type, file_type, file_size, page_count, " +
            "file_path, thumbnail_path, checksum_sha256, processing_status, processing_error, review_status, " +
            "favorite, archived, notes"

        /** Narrow list projection: base columns plus `ai_mode_used` (v2). */
        const val COLUMNS = "$BASE_COLUMNS, ai_mode_used"

        /** Number of columns in [COLUMNS]; search appends matchinfo + snippets after them. */
        const val COLUMN_COUNT = 32

        const val FIELD_COLUMNS = "id, record_id, field_key, value_text, value_json, method, confidence, state, source_page, source_bbox, evidence, created_ms, updated_ms"
        const val HIGHLIGHT_COLUMNS = "id, record_id, section, text, method, provider, field_id, source_page, confidence, dismissed, position, created_ms"
        const val OBSERVATION_COLUMNS = "id, record_id, field_id, analyte_id, analyte_method, raw_name, value_num, value_text, unit, " +
            "canonical_value, canonical_unit, ref_low, ref_high, ref_text, flag, observed_date, observed_date_method, method, " +
            "confidence, state, source_page, source_bbox, evidence, excluded_from_trends, created_ms, updated_ms"
        const val LINK_COLUMNS = "a_id, b_id, kind, origin, status, score, reasons_json, created_ms, updated_ms"
    }
}
