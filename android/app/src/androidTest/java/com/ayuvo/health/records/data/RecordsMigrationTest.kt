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

    @Test
    fun freshInstallEqualsV1PlusMigrations() {
        val helper = RecordsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            assertEquals(RecordsSchema.VERSION, db.version)
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info(records)", null).use { c -> while (c.moveToNext()) columns += c.getString(1) }
            assertTrue(columns.containsAll(listOf("phash", "text_signature", "ai_mode_used", "ai_provider", "type_confidence", "type_method")))
            assertEquals(SqliteRecordsStore.COLUMNS.split(',').map { it.trim() }, columns.take(SqliteRecordsStore.COLUMN_COUNT - 1) + "ai_mode_used")
        } finally {
            helper.close()
        }
    }

    private companion object {
        const val DB = "ayuvo_records_migration_test.db"
    }
}
