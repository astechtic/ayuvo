package com.ayuvo.health.records.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsDatabase
import com.ayuvo.health.records.data.SqliteRecordsStore
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.processing.UnitsCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The shared fixture archive (docs/health-records.md §35.1) imports into the real SQLite store with
 * exactly the documented contents, and a round-trip export → import reproduces them.
 */
@RunWith(AndroidJUnit4::class)
class RecordsArchiveInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var helper: RecordsDatabase
    private lateinit var store: SqliteRecordsStore
    private lateinit var files: RecordFileStore
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "records-archive-${UUID.randomUUID()}").apply { mkdirs() }
        val units = UnitsCatalog.parseOrDefault(context.assets.open("records/units.json").bufferedReader().use { it.readText() })
        val catalog = AnalyteCatalog.parse(context.assets.open(AnalyteCatalog.ASSET_PATH).bufferedReader().use { it.readText() }, units)
        helper = RecordsDatabase(context, DB)
        files = RecordFileStore(File(tempRoot, "files"), File(tempRoot, "render"), File(tempRoot, "share"))
        store = SqliteRecordsStore(helper, files, catalog = { catalog })
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DB)
        tempRoot.deleteRecursively()
    }

    private fun fixture(): File {
        val target = File(tempRoot, FIXTURE)
        testContext.assets.open(FIXTURE).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun counts(): Map<String, Int> = mapOf(
        "records" to count("records"), "record_pages" to count("record_pages"),
        "record_fields" to count("record_fields"), "observations" to count("observations"),
        "record_highlights" to count("record_highlights"), "record_links" to count("record_links"),
        "entities" to count("entities"), "record_entities" to count("record_entities"),
        "tags" to count("tags"), "record_tags" to count("record_tags"),
        "analyte_user_aliases" to count("analyte_user_aliases")
    )

    private fun count(table: String): Int =
        helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** §35.1: the documented contents land in the store, files and all. */
    @Test
    fun importsTheSharedFixtureArchive() = runBlocking {
        val result = RecordsArchiveReader(helper, files).import(fixture(), RecordsArchiveFormat.ImportMode.MERGE)
        assertEquals(4, result.importedRecords)
        assertEquals(0, result.skippedRecords)
        assertEquals("No reader warnings expected: " + result.warnings.map { it.text }, emptyList<String>(), result.warnings.map { it.text })
        assertEquals(0, result.missingFiles)
        assertEquals(7, result.importedFiles)

        assertEquals(
            mapOf(
                "records" to 4, "record_pages" to 4, "record_fields" to 32, "observations" to 8,
                "record_highlights" to 10, "record_links" to 2, "entities" to 4, "record_entities" to 7,
                "tags" to 2, "record_tags" to 4, "analyte_user_aliases" to 1
            ),
            counts()
        )

        // Manifest values of §35.1.
        val manifest = result.manifest!!
        assertEquals("ayuvo-records", (manifest["format"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("1.4", (manifest["app_version"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Asia/Kolkata", (manifest["time_zone"] as kotlinx.serialization.json.JsonPrimitive).content)

        // Records, their user state and their originals.
        val cbc = store.record("rec-cbc-0001")!!
        assertEquals("Complete Blood Count", cbc.title)
        assertEquals("2026-09-13", cbc.sortDate)
        assertTrue(cbc.favorite)
        assertEquals(1, cbc.sharedCount)
        assertEquals(com.ayuvo.health.records.model.ReviewStatus.NEEDS_REVIEW, cbc.reviewStatus)
        assertEquals(com.ayuvo.health.records.model.ProcessingStatus.READY, cbc.processingStatus)
        assertTrue("original.pdf must be on disk", files.resolve(cbc.filePath)!!.isFile)
        assertTrue("thumb.jpg must be on disk", files.resolve(cbc.thumbnailPath)!!.isFile)
        assertTrue(store.record("rec-lipid-0004")!!.archived)
        val note = store.record("rec-note-0003")!!
        assertEquals(null, note.thumbnailPath)
        assertEquals(138L, files.resolve(note.filePath)!!.length())

        // §38: value_json is exported as a nested object with sorted keys and comes back intact.
        val collection = store.fields("rec-cbc-0001").single { it.key == "collection_date" }
        assertEquals(
            mapOf("precision" to "day"),
            com.ayuvo.health.records.processing.RecordJson.parseObject(collection.valueJson)!!
                .mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }
        )
        assertTrue(
            "medication value_json must survive",
            store.fields("rec-rx-0002").any { it.key == "medication" && it.valueJson?.contains("\"name\"") == true }
        )

        // Knowledge rows survive the round trip.
        assertEquals(3, store.observations("rec-cbc-0001").size)
        assertEquals(5, store.observations("rec-lipid-0004").size)
        assertEquals(setOf("Anemia", "Follow up"), store.allTags().map { it.name }.toSet())
        assertEquals(mapOf("hb estimation" to "hemoglobin"), store.userAliases())
        assertEquals(2, store.links("rec-cbc-0001").size)

        // §35: the FTS index is rebuilt at the end, so search finds the imported text.
        store.reindexAll()
        val hits = store.page(RecordQuery(search = "hemoglobin"), null).items.map { it.id }
        assertTrue("hemoglobin should match the imported CBC, got $hits", "rec-cbc-0001" in hits)
    }

    /** §35: exporting the imported rows and importing them again reproduces the same store. */
    @Test
    fun roundTripsExportThenImport() = runBlocking {
        RecordsArchiveReader(helper, files).import(fixture(), RecordsArchiveFormat.ImportMode.MERGE)
        val before = counts()
        val exported = File(tempRoot, "round-trip.zip")
        val export = RecordsArchiveWriter(helper, files, "1.4").export(exported)
        assertEquals(4, export.recordCount)
        assertEquals(7, export.fileCount)
        assertTrue(exported.length() > 0)

        // Merge skips everything that is already there (§35 id / checksum rules).
        val merged = RecordsArchiveReader(helper, files).import(exported, RecordsArchiveFormat.ImportMode.MERGE)
        assertEquals(0, merged.importedRecords)
        assertEquals(4, merged.skippedRecords)
        assertEquals(before, counts())

        // Replace wipes first and imports the archive again, ending at the same contents.
        val replaced = RecordsArchiveReader(helper, files).import(exported, RecordsArchiveFormat.ImportMode.REPLACE)
        assertEquals(4, replaced.importedRecords)
        assertEquals(0, replaced.skippedRecords)
        assertEquals(emptyList<String>(), replaced.warnings.map { it.text })
        assertEquals(before, counts())
        assertNotNull(store.record("rec-rx-0002"))
        assertTrue(files.resolve(store.record("rec-rx-0002")!!.filePath)!!.isFile)
        // §38: the round trip is compared structurally; value_json still parses after export + import.
        assertTrue(
            store.fields("rec-rx-0002").filter { it.key == "medication" }
                .all { com.ayuvo.health.records.processing.RecordJson.parseObject(it.valueJson) != null }
        )
    }

    /** A records-backup-state round trip and the §34 share bookkeeping the archive preserves. */
    @Test
    fun keepsShareCountsAcrossExportAndImport() = runBlocking {
        RecordsArchiveReader(helper, files).import(fixture(), RecordsArchiveFormat.ImportMode.MERGE)
        store.markShared(listOf("rec-note-0003"), 1_700_000_000_000L)
        val exported = File(tempRoot, "shared-counts.zip")
        RecordsArchiveWriter(helper, files, "1.4").export(exported)
        RecordsArchiveReader(helper, files).import(exported, RecordsArchiveFormat.ImportMode.REPLACE)
        val note = store.record("rec-note-0003")!!
        assertEquals(1, note.sharedCount)
        assertEquals(1_700_000_000_000L, note.lastSharedMs)
        assertEquals(1, store.record("rec-cbc-0001")!!.sharedCount)
    }

    /**
     * §34/§35: everything handed to the share sheet lives under the FileProvider `records_share`
     * path, so `getUriForFile` must resolve both the share build folder and the archive folder.
     */
    @Test
    fun shareFoldersAreCoveredByTheFileProvider() {
        val real = com.ayuvo.health.records.data.RecordFileStore(context)
        val authority = context.packageName + ".fileprovider"
        try {
            for (file in listOf(File(real.freshShareBuildDir(), "a.pdf"), File(real.archiveDir(), "ayuvo-records-2026-09-16.zip"))) {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                assertTrue("expected a records_share uri, got " + uri, uri.toString().contains("records_share"))
            }
        } finally {
            real.clearShareTemp()
        }
    }

    private companion object {
        const val DB = "ayuvo_records_archive_test.db"
        const val FIXTURE = "ayuvo-records-fixture.zip"
    }
}
