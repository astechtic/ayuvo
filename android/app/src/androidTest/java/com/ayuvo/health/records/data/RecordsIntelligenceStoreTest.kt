package com.ayuvo.health.records.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.records.model.DateMethod
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.DuplicateReason
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.ProcessingStage
import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.SplitSegment
import com.ayuvo.health.records.model.SplitStatus
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
class RecordsIntelligenceStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: RecordsDatabase
    private lateinit var files: RecordFileStore
    private lateinit var store: SqliteRecordsStore
    private lateinit var tempRoot: File
    private val label: (RecordType) -> String? = { it.raw }

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "records-intel-${UUID.randomUUID()}").apply { mkdirs() }
        helper = RecordsDatabase(context, DB)
        files = RecordFileStore(File(tempRoot, "files"), File(tempRoot, "render"), File(tempRoot, "share"))
        store = SqliteRecordsStore(helper, files)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DB)
        tempRoot.deleteRecursively()
    }

    private suspend fun insert(title: String = "blood report", filename: String? = "blood_report.pdf", pages: List<String> = emptyList(), createdMs: Long = 1_000): HealthRecord {
        val id = UUID.randomUUID().toString()
        val dir = files.recordDir(id).apply { mkdirs() }
        File(dir, "original.pdf").writeText("%PDF-1.4 $id")
        return store.insert(
            HealthRecord(
                id = id, title = title, source = RecordSource.IMPORT, importMethod = ImportMethod.FILE_PICKER,
                originalFilename = filename, createdMs = createdMs, updatedMs = createdMs, mimeType = "application/pdf",
                fileType = RecordFileType.PDF, pageCount = pages.size, filePath = "$id/original.pdf", checksumSha256 = id
            ),
            pages.mapIndexed { i, t -> RecordPage(id, i, t, TextSource.PDF_TEXT) }
        )
    }

    private fun field(key: String, value: String, confidence: Double, page: Int? = 0, json: String? = null, method: ExtractionMethod = ExtractionMethod.RULES) =
        ExtractedField(key, value, json, method, confidence, page, null, value)

    @Test
    fun applyExtractionNeverOverwritesSettledValuesAndDerivesColumns() = runBlocking {
        val r = insert()
        store.applyExtraction(r.id, listOf(
            field(FieldKey.REPORT_NAME, "Complete Blood Count", 0.7),
            field(FieldKey.REPORT_DATE, "2026-09-12", 0.9),
            field(FieldKey.DOCTOR_NAME, "Dr. A Mehta", 0.6)
        ), label)
        var record = store.record(r.id)!!
        assertEquals("Complete Blood Count", record.title)
        assertEquals("2026-09-12", record.documentDate)
        assertEquals("2026-09-12", record.sortDate)
        assertEquals(DateMethod.RULES, record.documentDateMethod)

        val doctor = store.fields(r.id).single { it.key == FieldKey.DOCTOR_NAME }
        store.setFieldState(doctor.id, FieldState.CONFIRMED, typeLabel = label)
        // Same value with higher confidence: settled row untouched.
        store.applyExtraction(r.id, listOf(field(FieldKey.DOCTOR_NAME, "DR A. MEHTA", 0.95, method = ExtractionMethod.AI_CLOUD)), label)
        val doctors = store.fields(r.id).filter { it.key == FieldKey.DOCTOR_NAME }
        assertEquals(1, doctors.size)
        assertEquals(FieldState.CONFIRMED, doctors.single().state)
        assertEquals(0.6, doctors.single().confidence, 1e-9)

        // A suggested row updates only when confidence rises.
        store.applyExtraction(r.id, listOf(field(FieldKey.REPORT_DATE, "2026-09-12", 0.5, page = 2)), label)
        assertEquals(0.9, store.fields(r.id).single { it.key == FieldKey.REPORT_DATE }.confidence, 1e-9)
        store.applyExtraction(r.id, listOf(field(FieldKey.REPORT_DATE, "2026-09-12", 0.95, page = 1)), label)
        assertEquals(1, store.fields(r.id).single { it.key == FieldKey.REPORT_DATE }.sourcePage)

        // A different value for a single-valued key is a second suggested row (conflict), never an overwrite.
        store.applyExtraction(r.id, listOf(field(FieldKey.REPORT_DATE, "2026-09-10", 0.8)), label)
        assertEquals(2, store.fields(r.id).count { it.key == FieldKey.REPORT_DATE })

        // Rejected values never come back.
        val wrong = store.fields(r.id).single { it.valueText == "2026-09-10" }
        store.setFieldState(wrong.id, FieldState.REJECTED, typeLabel = label)
        store.applyExtraction(r.id, listOf(field(FieldKey.REPORT_DATE, "2026-09-10", 0.9)), label)
        assertEquals(FieldState.REJECTED, store.fields(r.id).single { it.valueText == "2026-09-10" }.state)

        // A user-set date is never replaced by detection.
        store.update(listOf(r.id), com.ayuvo.health.records.model.RecordPatch(documentDate = "2025-01-01"))
        store.applyExtraction(r.id, listOf(field(FieldKey.COLLECTION_DATE, "2026-09-11", 0.95)), label)
        record = store.record(r.id)!!
        assertEquals("2025-01-01", record.documentDate)
    }

    @Test
    fun searchIndexesPeopleClinicalAndFlagsFilter() = runBlocking {
        val cbc = insert(pages = listOf("Hemoglobin 7.6 g/dL L"))
        val other = insert(title = "Prescription", filename = null, pages = listOf("Tab Metformin 500 mg BD"))
        store.applyExtraction(cbc.id, listOf(
            field(FieldKey.DOCTOR_NAME, "Dr. Anjali Mehta", 0.85),
            field(FieldKey.TEST_RESULT, "Hemoglobin", 0.9, json = """{"name":"Hemoglobin","value":"7.6","value_num":7.6,"unit":"g/dL","ref_text":"13-17","ref_low":13,"ref_high":17,"flag":"low"}""")
        ), label)
        store.applyExtraction(other.id, listOf(field(FieldKey.MEDICATION, "Metformin", 0.8)), label)

        val byDoctor = store.search(RecordQuery(terms = listOf("mehta")))
        assertEquals(listOf(cbc.id), byDoctor.map { it.record.id })
        assertTrue(byDoctor.single().snippet!!.contains("[mehta]"))
        assertEquals(listOf(other.id), store.search(RecordQuery(terms = listOf("metformin"))).map { it.record.id })

        val abnormal = store.page(RecordQuery(advanced = RecordAdvancedFilters(flags = setOf("abnormal"))), null)
        assertEquals(listOf(cbc.id), abnormal.items.map { it.id })
        val doctorFilter = store.page(RecordQuery(advanced = RecordAdvancedFilters(doctor = "dr. anj")), null)
        assertEquals(listOf(cbc.id), doctorFilter.items.map { it.id })

        // Rejected values leave the index.
        val med = store.fields(other.id).single()
        store.setFieldState(med.id, FieldState.REJECTED, typeLabel = label)
        assertEquals(listOf(other.id), store.search(RecordQuery(terms = listOf("metformin"))).map { it.record.id }) // still in page text
    }

    @Test
    fun acceptSplitCreatesChildrenSharingTheParentFile() = runBlocking {
        val parent = insert(pages = listOf("CBC page", "CBC page 2", "Rx Tab Dolo", "Invoice total"))
        store.applyExtraction(parent.id, listOf(field(FieldKey.MEDICATION, "Dolo", 0.8, page = 2), field(FieldKey.REPORT_NAME, "CBC", 0.8, page = 0)), label)
        store.saveSplitProposal(com.ayuvo.health.records.model.SplitProposal(parent.id, listOf(
            SplitSegment(0, 1, RecordType.LAB_REPORT, "CBC", 0.8), SplitSegment(2, 3, RecordType.PRESCRIPTION, "", 0.7)
        )), parent.id)
        val children = store.acceptSplit(parent.id, store.splitProposal(parent.id)!!.segments) { "Fallback ${it.pageStart}" }
        assertEquals(2, children.size)
        val first = store.record(children[0])!!
        val second = store.record(children[1])!!
        assertEquals(parent.id, first.parentId)
        assertEquals(0, first.pageStart); assertEquals(1, first.pageEnd)
        assertEquals("Fallback 2", second.title)
        assertEquals(parent.filePath, second.filePath)
        assertEquals(listOf(2, 3), store.pages(second.id).map { it.pageIndex })
        assertEquals(listOf("Dolo"), store.fields(second.id).map { it.valueText })
        assertEquals(ProcessingStage.CLASSIFY, store.job(second.id)?.stage)
        assertTrue(store.record(parent.id)!!.archived)
        assertEquals(SplitStatus.ACCEPTED, store.splitProposal(parent.id)?.status)

        // Deleting one child keeps the shared file; deleting everything removes it.
        store.delete(listOf(first.id))
        assertTrue(File(files.root, parent.filePath!!).exists())
        store.delete(listOf(parent.id, second.id))
        assertFalse(File(files.root, parent.filePath!!).exists())
    }

    @Test
    fun mergeAndReplaceResolveDuplicates() = runBlocking {
        val existing = insert(createdMs = 1)
        val incoming = insert(createdMs = 2)
        store.update(listOf(incoming.id), com.ayuvo.health.records.model.RecordPatch(notes = "new note"))
        store.addTag(incoming.id, "Cardio")
        store.addDuplicateCandidate(DuplicateCandidate(incoming.id, existing.id, DuplicateReason.CONTENT, 0.97))
        assertEquals(1, store.pendingDuplicates(incoming.id).size)
        store.mergeInto(existing.id, incoming.id)
        assertNull(store.record(incoming.id))
        assertEquals("new note", store.record(existing.id)!!.notes)
        assertEquals(listOf("Cardio"), store.tags(existing.id).map { it.name })

        val newer = insert(createdMs = 3)
        store.applyExtraction(existing.id, listOf(field(FieldKey.DIAGNOSIS, "Anemia", 0.7)), label)
        val keep = store.fields(existing.id).single()
        store.setFieldState(keep.id, FieldState.CONFIRMED, typeLabel = label)
        store.applyExtraction(existing.id, listOf(field(FieldKey.SYMPTOM, "Fatigue", 0.7)), label)
        store.replaceWithNew(existing.id, newer.id)
        assertNull(store.record(newer.id))
        val replaced = store.record(existing.id)!!
        assertEquals(newer.checksumSha256, replaced.checksumSha256)
        assertEquals(listOf("Anemia"), store.fields(existing.id).map { it.valueText })
        assertEquals(listOf("Cardio"), store.tags(existing.id).map { it.name })
        assertTrue(File(files.root, replaced.filePath!!).readText().contains(newer.id))
        assertNotNull(replaced)
    }

    private companion object {
        const val DB = "ayuvo_records_intel_test.db"
    }
}
