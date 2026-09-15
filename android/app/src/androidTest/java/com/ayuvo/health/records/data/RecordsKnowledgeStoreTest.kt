package com.ayuvo.health.records.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.knowledge.TrendSeries
import com.ayuvo.health.records.model.AnalyteCondition
import com.ayuvo.health.records.model.AnalyteMethod
import com.ayuvo.health.records.model.EntityKind
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.LinkKind
import com.ayuvo.health.records.model.LinkOrigin
import com.ayuvo.health.records.model.LinkStatus
import com.ayuvo.health.records.model.NewUserObservation
import com.ayuvo.health.records.model.ObservationEdit
import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ResultFlag
import com.ayuvo.health.records.model.SplitSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Phase 3 store behaviour (docs/health-records.md §19–§24) on a real framework SQLite. */
@RunWith(AndroidJUnit4::class)
class RecordsKnowledgeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: RecordsDatabase
    private lateinit var files: RecordFileStore
    private lateinit var store: SqliteRecordsStore
    private lateinit var tempRoot: File
    private val label: (RecordType) -> String? = { it.raw }

    /** The bundled catalogue (a byte copy of shared/records/analytes.json). */
    private lateinit var catalog: AnalyteCatalog

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "records-knowledge-${UUID.randomUUID()}").apply { mkdirs() }
        com.ayuvo.health.records.processing.UnitsCatalog.parseOrDefault(context.assets.open("records/units.json").bufferedReader().use { it.readText() })
        catalog = AnalyteCatalog.parse(context.assets.open(AnalyteCatalog.ASSET_PATH).bufferedReader().use { it.readText() })
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

    private suspend fun insert(title: String, date: String, type: RecordType = RecordType.LAB_REPORT, pages: Int = 1): HealthRecord {
        val id = UUID.randomUUID().toString()
        File(files.recordDir(id).apply { mkdirs() }, "original.pdf").writeText("%PDF-1.4 $id")
        return store.insert(
            HealthRecord(
                id = id, title = title, recordType = type, category = type.defaultCategory, source = RecordSource.IMPORT,
                importMethod = ImportMethod.FILE_PICKER, originalFilename = null, createdMs = 1_000, updatedMs = 1_000,
                documentDate = date, mimeType = "application/pdf", fileType = RecordFileType.PDF, pageCount = pages,
                filePath = "$id/original.pdf", checksumSha256 = id
            )
        )
    }

    private fun result(name: String, value: String, unit: String?, ref: String?, lo: Double?, hi: Double?, flag: String, page: Int = 0) = ExtractedField(
        FieldKey.TEST_RESULT, name,
        """{"name":"$name","value":"$value","value_num":$value,"unit":${unit?.let { "\"$it\"" } ?: "null"},"ref_text":${ref?.let { "\"$it\"" } ?: "null"},"ref_low":$lo,"ref_high":$hi,"flag":"$flag"}""",
        ExtractionMethod.RULES, 0.9, page, null, "$name $value"
    )

    private fun field(key: String, value: String, confidence: Double = 0.85) = ExtractedField(key, value, null, ExtractionMethod.RULES, confidence, 0, null, value)

    private suspend fun cbc(date: String, hb: String): HealthRecord {
        val r = insert("CBC $date", date)
        store.applyExtraction(r.id, listOf(
            field(FieldKey.REPORT_DATE, date, 0.9),
            field(FieldKey.FACILITY, "City Care Diagnostics", 0.8),
            result("Hemoglobin", hb, "g/dL", "13.0 - 17.0", 13.0, 17.0, "low"),
            result("Total WBC Count", "7800", "cells/cumm", "4000 - 11000", 4000.0, 11000.0, "normal"),
            result("Platelet Count", "2.5", "lakhs/cumm", "1.5 - 4.5", 1.5, 4.5, "normal")
        ), label)
        return r
    }

    @Test
    fun promotionHappensInsideApplyExtractionAndFollowsFieldStates() = runBlocking {
        val r = cbc("2026-09-12", "9.7")
        val obs = store.observations(r.id)
        assertEquals(3, obs.size)
        val hb = obs.single { it.rawName == "Hemoglobin" }
        assertEquals("hemoglobin", hb.analyteId)
        assertEquals(AnalyteMethod.CATALOG, hb.analyteMethod)
        assertEquals(9.7, hb.canonicalValue!!, 1e-9)
        assertEquals(250.0, obs.single { it.rawName == "Platelet Count" }.canonicalValue!!, 1e-9)
        assertEquals("platelet_count", obs.single { it.rawName == "Platelet Count" }.analyteId)
        assertEquals("g/dL", hb.canonicalUnit)
        assertEquals("2026-09-12", hb.observedDate)
        assertEquals(ResultFlag.LOW, hb.flag)
        assertEquals(0, hb.sourcePage)

        // Re-running extraction is idempotent.
        store.applyExtraction(r.id, listOf(result("Hemoglobin", "9.7", "g/dL", "13.0 - 17.0", 13.0, 17.0, "low")), label)
        assertEquals(3, store.observations(r.id).size)

        // A rejected field rejects its observation; it leaves trends and value search.
        val field = store.fields(r.id).single { it.key == FieldKey.TEST_RESULT && it.valueText == "Hemoglobin" }
        store.setFieldState(field.id, FieldState.REJECTED, typeLabel = label)
        assertEquals(FieldState.REJECTED, store.observations(r.id).single { it.rawName == "Hemoglobin" }.state)
        assertTrue(store.analyteObservations("hemoglobin").isEmpty())

        // FTS clinical carries mapped analyte display names ("WBC Count" is only the catalogue name).
        assertEquals(listOf(r.id), store.search(RecordQuery(terms = listOf("wbc", "count"))).map { it.record.id })
    }

    @Test
    fun userEditsRecomputeAndAreNeverOverwritten() = runBlocking {
        val r = cbc("2026-09-12", "9.7")
        val hb = store.observations(r.id).single { it.analyteId == "hemoglobin" }
        store.editObservation(hb.id, ObservationEdit(setValue = true, value = "97", setUnit = true, unit = "g/L"))
        var edited = store.observations(r.id).single { it.id == hb.id }
        assertEquals(FieldState.USER, edited.state)
        assertEquals(9.7, edited.canonicalValue!!, 1e-9)
        assertEquals("Hemoglobin 9.7", edited.evidence)
        // A unit change converts the printed range (13–17 g/dL → 130–170 g/L); 97 g/L is below it.
        assertEquals(130.0, edited.refLow!!, 1e-9)
        assertEquals(ResultFlag.LOW, edited.flag)

        store.editObservation(hb.id, ObservationEdit(setValue = true, value = "8.1", setUnit = true, unit = "g/dL", setRefText = true, refText = "12 - 15"))
        edited = store.observations(r.id).single { it.id == hb.id }
        assertEquals(12.0, edited.refLow!!, 1e-9)
        assertEquals(ResultFlag.LOW, edited.flag)

        // Extraction never touches the user-edited observation.
        store.applyExtraction(r.id, listOf(result("Hemoglobin", "9.7", "g/dL", "13.0 - 17.0", 13.0, 17.0, "low").copy(confidence = 0.95)), label)
        assertEquals("8.1", store.observations(r.id).single { it.id == hb.id }.valueText)

        // Exclude from trends, then remove.
        store.editObservation(hb.id, ObservationEdit(excludedFromTrends = true, rememberAlias = false))
        assertTrue(store.analyteObservations("hemoglobin").isEmpty())
        assertEquals(1, store.analyteObservations("hemoglobin", includeExcluded = true).size)
        store.removeObservation(hb.id)
        assertEquals(FieldState.REJECTED, store.observations(r.id).single { it.id == hb.id }.state)
    }

    @Test
    fun remappingRemembersUserAliasAndAddValueWorks() = runBlocking {
        val r = insert("Sugar", "2026-09-10")
        store.applyExtraction(r.id, listOf(result("Gluco Level Q", "6.5", "mmol/L", "3.9 - 5.5", 3.9, 5.5, "high")), label)
        val obs = store.observations(r.id).single()
        assertNull(obs.analyteId)
        store.editObservation(obs.id, ObservationEdit(setAnalyte = true, analyteId = "glucose_fasting"))
        assertEquals("glucose_fasting", store.userAliases()[AnalyteCatalog.normalizeTestName("Gluco Level Q")])

        // A later record maps through the user alias.
        val later = insert("Sugar 2", "2026-09-14")
        store.applyExtraction(later.id, listOf(result("GLUCO LEVEL Q", "6.1", "mmol/L", null, null, null, "unknown")), label)
        val mapped = store.observations(later.id).single()
        assertEquals("glucose_fasting", mapped.analyteId)
        assertEquals(AnalyteMethod.USER_ALIAS, mapped.analyteMethod)
        assertEquals(6.1, mapped.canonicalValue!!, 1e-6)

        // Add value: user method, defaults to the record date.
        val added = store.addObservation(NewUserObservation(later.id, "hemoglobin", "", "12.5", "g/dL", null))!!
        assertEquals(ExtractionMethod.USER, added.method)
        assertEquals("2026-09-14", added.observedDate)
        assertEquals(12.5, added.canonicalValue!!, 1e-9)

        val trend = TrendSeries.build("glucose_fasting", store.analyteObservations("glucose_fasting"), catalog)
        assertEquals(listOf(6.5, 6.1), trend.main!!.points.map { it.value })
    }

    @Test
    fun trendsValueSearchAndAnalyteFilters() = runBlocking {
        val jul = cbc("2026-07-18", "7.2")
        val aug = cbc("2026-08-10", "8.4")
        val sep = cbc("2026-09-12", "9.7")
        val rows = store.analyteObservations("hemoglobin")
        assertEquals(listOf("2026-07-18", "2026-08-10", "2026-09-12"), rows.map { it.observedDate })
        val trend = TrendSeries.build("hemoglobin", rows, catalog)
        val sepHb = rows.last()
        assertEquals("7.2 → 8.4 → 9.7", TrendSeries.mini(trend, sepHb.id, catalog).text)
        assertEquals("+1.3 g/dL since 2026-08-10", TrendSeries.mini(trend, sepHb.id, catalog).changeText)
        assertEquals(setOf("hemoglobin", "wbc_count", "platelet_count"), store.trendsForRecord(sep.id).keys)

        val low = store.valueHits(listOf(AnalyteCondition("hemoglobin", flag = "low")), emptyList())
        assertEquals(listOf(sep.id, aug.id, jul.id), low.map { it.record.id })
        assertEquals("Hemoglobin", low.first().displayName)
        val above8 = store.page(RecordQuery(advanced = RecordAdvancedFilters(analyteConditions = listOf(AnalyteCondition("hemoglobin", op = ">", value = 8.0)))), null)
        assertEquals(setOf(sep.id, aug.id), above8.items.map { it.id }.toSet())
        val bare = store.valueHits(emptyList(), listOf("platelet_count"))
        assertEquals(3, bare.size)
    }

    @Test
    fun entitiesBackDoctorAndHospitalFilters() = runBlocking {
        val a = insert("Visit", "2026-09-01", RecordType.CONSULTATION_NOTE)
        store.applyExtraction(a.id, listOf(field(FieldKey.DOCTOR_NAME, "Dr. Anjali Mehta"), field(FieldKey.FACILITY, "City Care Hospital", 0.8)), label)
        val b = insert("Rx", "2026-09-03", RecordType.PRESCRIPTION)
        store.applyExtraction(b.id, listOf(field(FieldKey.DOCTOR_NAME, "ANJALI MEHTA")), label)
        val doctors = store.entities(EntityKind.DOCTOR)
        assertEquals(1, doctors.size)
        assertEquals(2, doctors.single().recordCount)
        assertEquals(setOf(a.id, b.id), store.page(RecordQuery(advanced = RecordAdvancedFilters(doctor = "dr anj")), null).items.map { it.id }.toSet())
        assertEquals(setOf(a.id, b.id), store.page(RecordQuery(advanced = RecordAdvancedFilters(doctorEntityIds = setOf(doctors.single().id))), null).items.map { it.id }.toSet())
        assertEquals(listOf(a.id), store.page(RecordQuery(advanced = RecordAdvancedFilters(facility = "city care")), null).items.map { it.id })

        // Rejecting the doctor rebuilds the record's entities; orphans disappear.
        val docField = store.fields(b.id).single { it.key == FieldKey.DOCTOR_NAME }
        store.setFieldState(docField.id, FieldState.REJECTED, typeLabel = label)
        assertEquals(1, store.entities(EntityKind.DOCTOR).single().recordCount)
        store.delete(listOf(a.id))
        assertTrue(store.entities(EntityKind.DOCTOR).isEmpty())
    }

    @Test
    fun relationSuggestionsLinkAcceptRejectAndUnlink() = runBlocking {
        val visit = insert("Consultation", "2026-09-01", RecordType.CONSULTATION_NOTE)
        store.applyExtraction(visit.id, listOf(field(FieldKey.DOCTOR_NAME, "Dr. Anjali Mehta"), field(FieldKey.FACILITY, "City Care Hospital", 0.8)), label)
        val lab = insert("CBC", "2026-09-03")
        store.applyExtraction(lab.id, listOf(field(FieldKey.DOCTOR_NAME, "Dr Anjali Mehta"), field(FieldKey.FACILITY, "City Care Hospital", 0.8)), label)
        val rx = insert("Prescription", "2026-09-05", RecordType.PRESCRIPTION)
        store.applyExtraction(rx.id, listOf(field(FieldKey.DOCTOR_NAME, "Anjali Mehta")), label)

        store.suggestRelations(rx.id, LocalDate.of(2026, 9, 15))
        var related = store.related(rx.id)
        val toVisit = related.single { it.record.id == visit.id }
        assertEquals(LinkKind.PRESCRIPTION_FOR, toVisit.link.kind)
        assertEquals(LinkStatus.SUGGESTED, toVisit.link.status)
        assertTrue(toVisit.link.score >= 0.6)
        store.suggestRelations(lab.id, LocalDate.of(2026, 9, 15))
        assertNotNull(store.related(lab.id).firstOrNull { it.record.id == visit.id })

        // Accept → Linked; reject → hidden and never re-suggested.
        store.acceptLink(rx.id, visit.id)
        assertTrue(store.related(rx.id).single { it.record.id == visit.id }.link.isLinked)
        store.rejectLink(lab.id, visit.id)
        assertTrue(store.related(lab.id).none { it.record.id == visit.id })
        store.suggestRelations(lab.id, LocalDate.of(2026, 9, 15))
        assertTrue(store.related(lab.id).none { it.record.id == visit.id })

        // A user link replaces a suggestion; unlink deletes a user row and rejects a suggestion.
        store.link(lab.id, rx.id, LinkKind.SAME_EPISODE)
        val user = store.related(lab.id).single { it.record.id == rx.id }.link
        assertEquals(LinkOrigin.USER, user.origin)
        assertEquals(LinkKind.SAME_EPISODE, user.kind)
        assertEquals(setOf(visit.id to rx.id, lab.id to rx.id).map { (x, y) -> com.ayuvo.health.records.model.RecordLink.pair(x, y) }.toSet(),
            store.acceptedLinksAmong(listOf(visit.id, lab.id, rx.id)).map { it.aId to it.bId }.toSet())
        store.unlink(lab.id, rx.id)
        assertTrue(store.related(lab.id).none { it.record.id == rx.id })
        store.unlink(rx.id, visit.id)
        assertTrue(store.related(rx.id).none { it.record.id == visit.id })
        related = store.related(rx.id)
        assertTrue(related.none { it.link.isLinked })
    }

    @Test
    fun splitAcceptanceCopiesObservationsAndLinksParts() = runBlocking {
        val parent = insert("Combined", "2026-09-12", pages = 4)
        store.applyExtraction(parent.id, listOf(
            result("Hemoglobin", "9.7", "g/dL", "13.0 - 17.0", 13.0, 17.0, "low", page = 0),
            result("Platelet Count", "2.5", "lakhs/cumm", "1.5 - 4.5", 1.5, 4.5, "normal", page = 2)
        ), label)
        val children = store.acceptSplit(parent.id, listOf(
            SplitSegment(0, 1, RecordType.LAB_REPORT, "CBC", 0.8), SplitSegment(2, 3, RecordType.LAB_REPORT, "Platelets", 0.8)
        )) { "Part" }
        assertEquals(listOf("hemoglobin"), store.observations(children[0]).map { it.analyteId })
        val copied = store.observations(children[1]).single()
        assertNotNull(copied.fieldId)
        assertEquals(copied.fieldId, store.fields(children[1]).single { it.key == FieldKey.TEST_RESULT }.id)
        assertEquals(LinkKind.SPLIT_FROM, store.related(children[0]).first().link.kind)
        assertTrue(store.related(children[0]).all { it.link.isLinked })
    }

    private companion object {
        const val DB = "ayuvo_records_knowledge_test.db"
    }
}
