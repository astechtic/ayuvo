package com.ayuvo.health.records.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** A v1 database file with data upgrades to v2 in place, keeping every row (docs §8). */
@RunWith(AndroidJUnit4::class)
class RecordsMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "records-migration-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
        tempRoot.deleteRecursively()
    }

    private fun createV1WithData(): String {
        val path = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        val id = UUID.randomUUID().toString()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.beginTransaction()
            try {
                RecordsSchema.STATEMENTS.forEach(db::execSQL)
                db.execSQL("INSERT INTO records_meta(key, value) VALUES ('schema_version', '1')")
                val seq = db.insertOrThrow("records", null, ContentValues().apply {
                    put("id", id)
                    put("title", "CBC September")
                    put("record_type", "lab_report")
                    put("category", "lab_reports")
                    put("source", "import")
                    put("import_method", "file_picker")
                    put("created_ms", 1_000L)
                    put("updated_ms", 1_000L)
                    put("sort_date", "2026-09-12")
                    put("document_date", "2026-09-12")
                    put("mime_type", "application/pdf")
                    put("file_type", "pdf")
                    put("processing_status", "ready")
                    put("notes", "fasting sample")
                })
                db.insertOrThrow("record_pages", null, ContentValues().apply {
                    put("record_id", id)
                    put("page_index", 0)
                    put("text", "Hemoglobin 7.6 g/dL")
                    put("text_source", "pdf_text")
                })
                db.execSQL("INSERT INTO tags(id, name) VALUES ('t1', 'Blood')")
                db.execSQL("INSERT INTO record_tags(record_id, tag_id) VALUES (?, 't1')", arrayOf(id))
                db.execSQL(
                    "INSERT INTO records_fts(docid, title, people, clinical, body, notes_tags, highlights) VALUES (?, 'cbc september', '', '', 'hemoglobin 7.6 g/dl', 'fasting sample blood', '')",
                    arrayOf<Any>(seq)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = 1
        }
        return id
    }

    @Test
    fun upgradeFromV1KeepsDataAndAddsIntelligenceSchema() = runBlocking {
        val id = createV1WithData()
        val helper = RecordsDatabase(context, DB)
        val store = SqliteRecordsStore(helper, RecordFileStore(File(tempRoot, "f"), File(tempRoot, "r"), File(tempRoot, "s")))
        try {
            val db = helper.writableDatabase
            assertEquals(RecordsSchema.VERSION, db.version)
            val record = store.record(id)!!
            assertEquals("CBC September", record.title)
            assertEquals("fasting sample", record.notes)
            assertEquals("2026-09-12", record.sortDate)
            assertEquals(com.ayuvo.health.records.model.AiModeUsed.NONE, record.aiModeUsed)
            assertEquals(1, store.pages(id).size)
            assertEquals(listOf("Blood"), store.tags(id).map { it.name })
            assertEquals(listOf(id), store.page(com.ayuvo.health.records.model.RecordQuery(search = "hemoglobin"), null).items.map { it.id })

            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            assertTrue(tables.containsAll(RecordsSchema.MIGRATION_TABLES))
            assertTrue(tables.containsAll(listOf("idx_records_status", "idx_records_review", "idx_record_fields_record", "idx_processing_jobs_next")))
            db.rawQuery("SELECT value FROM records_meta WHERE key = 'schema_version'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(RecordsSchema.VERSION.toString(), c.getString(0))
            }
            // Pre-v2 records have no job: the one-time backfill picks them up.
            assertEquals(listOf(id), store.recordIdsWithoutJob())
        } finally {
            store.close()
        }
    }

    /** A v2 database with processed data upgrades to v3 in place (docs §19) and backfills knowledge. */
    @Test
    fun upgradeFromV2KeepsDataAndAddsKnowledgeSchema() = runBlocking {
        val path = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        val id = UUID.randomUUID().toString()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.beginTransaction()
            try {
                RecordsSchema.STATEMENTS.forEach(db::execSQL)
                RecordsSchema.MIGRATION_002.forEach(db::execSQL)
                db.execSQL("INSERT INTO records_meta(key, value) VALUES ('schema_version', '2')")
                db.insertOrThrow("records", null, ContentValues().apply {
                    put("id", id)
                    put("title", "Complete Blood Count")
                    put("record_type", "lab_report")
                    put("category", "lab_reports")
                    put("source", "import")
                    put("import_method", "file_picker")
                    put("created_ms", 1_000L)
                    put("updated_ms", 1_000L)
                    put("sort_date", "2026-09-12")
                    put("document_date", "2026-09-12")
                    put("mime_type", "application/pdf")
                    put("file_type", "pdf")
                    put("processing_status", "ready")
                    put("ai_mode_used", "none")
                    put("type_method", "rules")
                })
                db.insertOrThrow("record_fields", null, ContentValues().apply {
                    put("id", "f1")
                    put("record_id", id)
                    put("field_key", "test_result")
                    put("value_text", "Hemoglobin")
                    put("value_json", """{"name":"Hemoglobin","value":"7.6","value_num":7.6,"unit":"g/dL","ref_text":"13.0 - 17.0","ref_low":13,"ref_high":17,"flag":"low"}""")
                    put("method", "rules")
                    put("confidence", 0.9)
                    put("state", "suggested")
                    put("source_page", 0)
                    put("evidence", "Hemoglobin  7.6  g/dL  13.0 - 17.0")
                    put("created_ms", 1_000L)
                    put("updated_ms", 1_000L)
                })
                db.insertOrThrow("record_fields", null, ContentValues().apply {
                    put("id", "f2")
                    put("record_id", id)
                    put("field_key", "doctor_name")
                    put("value_text", "Anjali Mehta")
                    put("method", "rules")
                    put("confidence", 0.85)
                    put("state", "suggested")
                    put("created_ms", 1_000L)
                    put("updated_ms", 1_000L)
                })
                db.execSQL("INSERT INTO processing_jobs(record_id, stage, updated_ms) VALUES (?, 'done', 1000)", arrayOf(id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = 2
        }
        val helper = RecordsDatabase(context, DB)
        val store = SqliteRecordsStore(helper, RecordFileStore(File(tempRoot, "f"), File(tempRoot, "r"), File(tempRoot, "s")))
        try {
            val db = helper.writableDatabase
            assertEquals(RecordsSchema.VERSION, db.version)
            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            assertTrue(tables.containsAll(listOf("observations", "analyte_user_aliases", "entities", "record_entities", "record_links")))
            assertTrue(tables.containsAll(listOf("idx_observations_trend", "idx_entities_kind_name", "idx_record_links_b")))
            // v2 rows survive untouched.
            assertEquals("Complete Blood Count", store.record(id)!!.title)
            assertEquals(2, store.fields(id).size)
            assertEquals("done", store.job(id)!!.stage.raw)
            // The one-time backfill finds the record and promotes its values and entities.
            assertEquals(listOf(id), store.recordIdsNeedingKnowledge())
            store.refreshKnowledge(id)
            val obs = store.observations(id).single()
            assertEquals("f1", obs.fieldId)
            assertEquals("7.6", obs.valueText)
            assertEquals("2026-09-12", obs.observedDate)
            assertEquals(com.ayuvo.health.records.model.ResultFlag.LOW, obs.flag)
            assertEquals(listOf("anjali mehta"), store.recordEntities(id).map { it.first.normalizedName })
            assertTrue(store.recordIdsNeedingKnowledge().isEmpty())
            db.rawQuery("SELECT value FROM records_meta WHERE key = 'schema_version'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(RecordsSchema.VERSION.toString(), c.getString(0))
            }
        } finally {
            store.close()
        }
    }

    /**
     * A v3 database with records, pages, fields, observations, entities and links upgrades to v4
     * (docs §33) keeping every row; the new share columns default and `records_backup_state` exists.
     */
    @Test
    fun upgradeFromV3KeepsDataAndAddsSharingSchema() = runBlocking {
        val path = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.beginTransaction()
            try {
                RecordsSchema.STATEMENTS.forEach(db::execSQL)
                RecordsSchema.MIGRATION_002.forEach(db::execSQL)
                RecordsSchema.MIGRATION_003.forEach(db::execSQL)
                db.execSQL("INSERT INTO records_meta(key, value) VALUES ('schema_version', '3')")
                for ((rid, title, date) in listOf(
                    Triple(id, "Complete Blood Count", "2026-09-12"),
                    Triple(other, "Prescription", "2026-09-14")
                )) {
                    db.insertOrThrow("records", null, ContentValues().apply {
                        put("id", rid)
                        put("title", title)
                        put("record_type", "lab_report")
                        put("category", "lab_reports")
                        put("source", "import")
                        put("import_method", "file_picker")
                        put("created_ms", 1_000L)
                        put("updated_ms", 1_000L)
                        put("sort_date", date)
                        put("document_date", date)
                        put("mime_type", "application/pdf")
                        put("file_type", "pdf")
                        put("processing_status", "ready")
                        put("ai_mode_used", "none")
                        put("notes", "fasting sample")
                    })
                }
                db.execSQL("INSERT INTO record_pages(record_id, page_index, text, text_source) VALUES (?, 0, 'Hemoglobin 7.6 g/dL', 'pdf_text')", arrayOf(id))
                db.insertOrThrow("record_fields", null, ContentValues().apply {
                    put("id", "f1")
                    put("record_id", id)
                    put("field_key", "test_result")
                    put("value_text", "Hemoglobin")
                    put("value_json", """{"name":"Hemoglobin","value":"7.6","value_num":7.6,"unit":"g/dL","flag":"low"}""")
                    put("method", "rules")
                    put("confidence", 0.9)
                    put("state", "confirmed")
                    put("source_page", 0)
                    put("created_ms", 1_000L)
                    put("updated_ms", 1_000L)
                })
                db.execSQL(
                    "INSERT INTO observations(id, record_id, field_id, raw_name, value_text, flag, method, state, created_ms, updated_ms) " +
                        "VALUES ('o1', ?, 'f1', 'Hemoglobin', '7.6', 'low', 'rules', 'confirmed', 1000, 1000)",
                    arrayOf(id)
                )
                db.execSQL("INSERT INTO entities(id, kind, display_name, normalized_name, created_ms, updated_ms) VALUES ('e1', 'doctor', 'Anjali Mehta', 'anjali mehta', 1000, 1000)")
                db.execSQL("INSERT INTO record_entities(record_id, entity_id, role) VALUES (?, 'e1', 'doctor')", arrayOf(id))
                val (a, b) = listOf(id, other).sorted()
                db.execSQL(
                    "INSERT INTO record_links(a_id, b_id, kind, origin, status, score, created_ms, updated_ms) VALUES (?, ?, 'previous_report', 'user', 'accepted', 1.0, 1000, 1000)",
                    arrayOf(a, b)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = 3
        }
        val helper = RecordsDatabase(context, DB)
        val store = SqliteRecordsStore(helper, RecordFileStore(File(tempRoot, "f"), File(tempRoot, "r"), File(tempRoot, "s")))
        try {
            val db = helper.writableDatabase
            assertEquals(4, db.version)
            assertEquals(RecordsSchema.VERSION, db.version)
            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            assertTrue(tables.contains("records_backup_state"))
            assertTrue(tables.containsAll(RecordsSchema.MIGRATION_TABLES))
            // Every v3 row survives, and the new columns take their defaults.
            val record = store.record(id)!!
            assertEquals("Complete Blood Count", record.title)
            assertEquals("fasting sample", record.notes)
            assertEquals(0, record.sharedCount)
            assertEquals(null, record.lastSharedMs)
            assertEquals(1, store.pages(id).size)
            assertEquals(1, store.fields(id).size)
            assertEquals(listOf("7.6"), store.observations(id).map { it.valueText })
            assertEquals(listOf("anjali mehta"), store.recordEntities(id).map { it.first.normalizedName })
            assertEquals(1, store.links(id).size)
            // v4 state table round-trips.
            assertEquals(null, store.backupState(RecordsBackupKeys.LAST_ARCHIVE_MS))
            store.setBackupState(RecordsBackupKeys.LAST_ARCHIVE_MS, "1700000000000")
            assertEquals("1700000000000", store.backupState(RecordsBackupKeys.LAST_ARCHIVE_MS))
            // §34 share bookkeeping.
            store.markShared(listOf(id), 1_700_000_000_000L)
            store.markShared(listOf(id), 1_700_000_001_000L)
            val shared = store.record(id)!!
            assertEquals(2, shared.sharedCount)
            assertEquals(1_700_000_001_000L, shared.lastSharedMs)
            db.rawQuery("SELECT value FROM records_meta WHERE key = 'schema_version'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("4", c.getString(0))
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun freshInstallEqualsV1PlusMigrations() {
        val helper = RecordsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            assertEquals(RecordsSchema.VERSION, db.version)
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info(records)", null).use { c -> while (c.moveToNext()) columns += c.getString(1) }
            assertTrue(columns.containsAll(listOf("phash", "text_signature", "ai_mode_used", "ai_provider", "type_confidence", "type_method")))
            assertTrue(columns.containsAll(listOf("shared_count", "last_shared_ms")))
            // Every projected column exists, in the declared order of the base table first.
            assertEquals(columns.take(31), SqliteRecordsStore.BASE_COLUMNS.split(',').map { it.trim() })
            assertTrue(columns.containsAll(SqliteRecordsStore.COLUMNS.split(',').map { it.trim() }))
        } finally {
            helper.close()
        }
    }

    private companion object {
        const val DB = "ayuvo_records_migration_test.db"
    }
}
