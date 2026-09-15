package com.ayuvo.health.records.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordPatch
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordSortDate
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.TextSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordsDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: RecordsDatabase
    private lateinit var files: RecordFileStore
    private lateinit var store: SqliteRecordsStore
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        tempRoot = File(context.cacheDir, "records-test-${UUID.randomUUID()}").apply { mkdirs() }
        helper = RecordsDatabase(context, TEST_DB)
        files = RecordFileStore(File(tempRoot, "files"), File(tempRoot, "render"), File(tempRoot, "share"))
        store = SqliteRecordsStore(helper, files)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(TEST_DB)
        tempRoot.deleteRecursively()
    }

    private fun record(
        title: String,
        createdMs: Long,
        documentDate: String? = null,
        fileType: RecordFileType = RecordFileType.PDF,
        source: RecordSource = RecordSource.IMPORT,
        checksum: String? = null
    ) = HealthRecord(
        id = UUID.randomUUID().toString(),
        title = title,
        source = source,
        importMethod = ImportMethod.FILE_PICKER,
        createdMs = createdMs,
        updatedMs = createdMs,
        documentDate = documentDate,
        mimeType = if (fileType == RecordFileType.PDF) "application/pdf" else "text/plain",
        fileType = fileType,
        checksumSha256 = checksum
    )

    @Test
    fun createsSchemaWithConnectionSettings() {
        val db = helper.readableDatabase
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        assertTrue(tables.containsAll(RecordsSchema.TABLES))
        db.rawQuery("PRAGMA foreign_keys", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.rawQuery("PRAGMA journal_mode", null).use { c -> c.moveToFirst(); assertEquals("wal", c.getString(0).lowercase()) }
        db.rawQuery("SELECT value FROM records_meta WHERE key = 'schema_version'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(RecordsSchema.VERSION.toString(), c.getString(0))
        }
    }

    @Test
    fun insertSearchFoldedTitleNotesTagsAndBody() = runBlocking {
        val cbc = store.insert(record("Hémogramme CBC", createdMs = 1_000))
        val note = store.insert(record("Visit note", createdMs = 2_000, fileType = RecordFileType.TEXT))
        val text = record("Pasted", createdMs = 3_000, fileType = RecordFileType.TEXT)
        store.insert(text, listOf(RecordPage(text.id, 0, "Follow up with Dr. Mehta", TextSource.PLAIN)))

        assertEquals(listOf(cbc.id), store.page(RecordQuery(search = "hemogram"), null).items.map { it.id })
        assertEquals(listOf(text.id), store.page(RecordQuery(search = "MEHTA follow"), null).items.map { it.id })

        store.update(listOf(note.id), RecordPatch(notes = "Bring fasting sugar report"))
        assertEquals(listOf(note.id), store.page(RecordQuery(search = "fasting"), null).items.map { it.id })

        val tag = store.addTag(cbc.id, "Thyroid")
        assertNotNull(tag)
        assertEquals(listOf(cbc.id), store.page(RecordQuery(search = "thyr"), null).items.map { it.id })
        store.removeTag(cbc.id, tag!!.id)
        assertTrue(store.page(RecordQuery(search = "thyr"), null).items.isEmpty())
        assertTrue(store.page(RecordQuery(search = "zzz"), null).items.isEmpty())
    }

    @Test
    fun pagesInTimelineOrderWithKeysetCursor() = runBlocking {
        // sort_date = document_date, else the device-local day of created_ms. Noon UTC keeps the
        // undated rows on the same local day in any emulator time zone.
        val day = 86_400_000L
        val noon = day / 2
        val a = store.insert(record("A undated newest", createdMs = 20_000 * day + noon))          // 2024-10-04
        val b = store.insert(record("B dated", createdMs = 1_000, documentDate = "2025-01-01"))
        val c = store.insert(record("C same day later", createdMs = 19_000 * day + noon + 5_000))  // 2022-01-08
        val d = store.insert(record("D same day earlier", createdMs = 19_000 * day + noon + 1_000))
        val e = store.insert(record("E same day same ms", createdMs = 19_000 * day + noon + 1_000))
        assertEquals(RecordSortDate.localDay(a.createdMs), store.record(a.id)?.sortDate)
        assertEquals("2025-01-01", store.record(b.id)?.sortDate)

        val expected = listOf(b.id, a.id, c.id, e.id, d.id)
        val first = store.page(RecordQuery(), null, limit = 2)
        assertEquals(expected.take(2), first.items.map { it.id })
        assertNotNull(first.next)
        val second = store.page(RecordQuery(), first.next, limit = 2)
        assertEquals(expected.subList(2, 4), second.items.map { it.id })
        val third = store.page(RecordQuery(), second.next, limit = 2)
        assertEquals(expected.subList(4, 5), third.items.map { it.id })
        assertNull(third.next)

        store.update(listOf(b.id), RecordPatch(archived = true))
        assertFalse(store.page(RecordQuery(), null).items.any { it.id == b.id })
        assertEquals(listOf(b.id), store.page(RecordQuery(filter = RecordFilter.ARCHIVED), null).items.map { it.id })
    }

    @Test
    fun editingDocumentDateUpdatesSortDate() = runBlocking {
        val createdMs = 1_780_000_000_000L
        val rec = store.insert(record("Undated", createdMs = createdMs))
        val importDay = RecordSortDate.localDay(createdMs)
        assertEquals(importDay, store.record(rec.id)?.sortDate)

        store.update(listOf(rec.id), RecordPatch(documentDate = "2020-02-29"))
        assertEquals("2020-02-29", store.record(rec.id)?.sortDate)
        assertEquals("2020-02-29", store.page(RecordQuery(), null).items.single().sortDate)

        store.update(listOf(rec.id), RecordPatch(clearDocumentDate = true))
        val cleared = store.record(rec.id)
        assertNull(cleared?.documentDate)
        assertEquals(importDay, cleared?.sortDate)

        // A detected metadata date sets sort_date too, but never overrides a user date.
        store.updateProcessing(rec.id, com.ayuvo.health.records.model.RecordProcessingUpdate(
            documentDate = "2019-05-01", status = com.ayuvo.health.records.model.ProcessingStatus.READY
        ))
        assertEquals("2019-05-01", store.record(rec.id)?.sortDate)
        store.update(listOf(rec.id), RecordPatch(documentDate = "2021-01-01"))
        store.updateProcessing(rec.id, com.ayuvo.health.records.model.RecordProcessingUpdate(
            documentDate = "2018-01-01", status = com.ayuvo.health.records.model.ProcessingStatus.READY
        ))
        assertEquals("2021-01-01", store.record(rec.id)?.sortDate)
    }

    @Test
    fun filtersAndDuplicateLookup() = runBlocking {
        val pdf = store.insert(record("Pdf", createdMs = 1, checksum = "abc"))
        val shared = store.insert(record("Shared", createdMs = 2, fileType = RecordFileType.TEXT, source = RecordSource.SHARE_IN, checksum = "abc"))
        assertEquals(listOf(pdf.id), store.page(RecordQuery(filter = RecordFilter.PDFS), null).items.map { it.id })
        assertEquals(listOf(shared.id), store.page(RecordQuery(filter = RecordFilter.RECEIVED), null).items.map { it.id })
        assertEquals(pdf.id, store.findByChecksum("abc", excludingId = shared.id)?.id)
        store.update(listOf(pdf.id), RecordPatch(favorite = true))
        assertEquals(listOf(pdf.id), store.page(RecordQuery(filter = RecordFilter.FAVORITES), null).items.map { it.id })
    }

    @Test
    fun deleteCascadesPagesTagsFtsAndFiles() = runBlocking {
        val text = record("To delete", createdMs = 1, fileType = RecordFileType.TEXT)
        store.insert(text, listOf(RecordPage(text.id, 0, "unique body words", TextSource.PLAIN)))
        store.addTag(text.id, "Cardio")
        val dir = files.recordDir(text.id).apply { mkdirs() }
        File(dir, "original.txt").writeText("unique body words")

        store.delete(listOf(text.id))

        assertNull(store.record(text.id))
        val db = helper.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM record_pages WHERE record_id = ?", arrayOf(text.id)).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.rawQuery("SELECT COUNT(*) FROM record_tags WHERE record_id = ?", arrayOf(text.id)).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.rawQuery("SELECT COUNT(*) FROM records_fts WHERE records_fts MATCH 'unique*'", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        assertFalse(dir.exists())
        assertEquals(0L, store.count())
    }

    private companion object {
        const val TEST_DB = "ayuvo_records_test.db"
    }
}
