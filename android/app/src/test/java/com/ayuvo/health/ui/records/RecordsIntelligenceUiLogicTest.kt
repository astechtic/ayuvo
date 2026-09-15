package com.ayuvo.health.ui.records

import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.TextSource
import com.ayuvo.health.records.processing.AiGaps
import com.ayuvo.health.records.search.AiQueryRewriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsIntelligenceUiLogicTest {
    private fun f(key: String, value: String, confidence: Double, state: FieldState = FieldState.SUGGESTED, id: String = "$key-$value") =
        RecordField(id, "r", key, value, null, ExtractionMethod.RULES, confidence, state, 0)

    @Test
    fun reviewSheetShowsOnlyUncertainKeyOrConflictingSuggestions() {
        val items = ReviewSelection.items(listOf(
            f(FieldKey.REPORT_DATE, "2026-09-12", 0.9),
            f(FieldKey.REPORT_DATE, "2026-09-10", 0.6),
            f(FieldKey.DOCTOR_NAME, "Dr A Mehta", 0.95),
            f(FieldKey.DIAGNOSIS, "Anemia", 0.9),              // confident, not key → hidden
            f(FieldKey.SYMPTOM, "Fatigue", 0.7),               // uncertain → shown
            f(FieldKey.FACILITY, "City Care", 0.6, FieldState.CONFIRMED),
            f(FieldKey.PATIENT_NAME, "Ravi", 0.5, FieldState.REJECTED)
        ))
        assertEquals(listOf("2026-09-12", "2026-09-10", "Dr A Mehta", "Fatigue"), items.map { it.field.valueText })
        assertTrue(items[0].conflict && items[1].conflict)
        assertFalse(items[2].conflict)
        // A key the user already confirmed is not asked again.
        val settled = ReviewSelection.items(listOf(f(FieldKey.DOCTOR_NAME, "Dr A", 0.95, FieldState.CONFIRMED), f(FieldKey.DOCTOR_NAME, "Dr A", 0.5)))
        assertTrue(settled.isEmpty())
    }

    @Test
    fun sourceBoxesParseAndLocateEvidence() {
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.04f), SourceBoxes.parse("[0.1,0.2,0.3,0.04]"))
        assertNull(SourceBoxes.parse("[1,2]"))
        val blocks = """[{"t":"City Care Diagnostics","b":[0.1,0.05,0.5,0.03]},{"t":"Hemoglobin  7.6 L  g/dL","b":[0.08,0.3,0.8,0.02]}]"""
        assertEquals(listOf(0.08f, 0.3f, 0.8f, 0.02f), SourceBoxes.locate(blocks, "HEMOGLOBIN 7.6"))
        assertNull(SourceBoxes.locate(blocks, "platelets"))
    }

    @Test
    fun splitBoundariesRebuildSegmentsKeepingTitles() {
        val initial = listOf(SplitDraft(0, 1, RecordType.LAB_REPORT, "CBC", 0.8), SplitDraft(2, 3, RecordType.BILL, "Invoice", 0.7))
        val added = SplitReviewViewModel.rebuild(initial, listOf(2, 3), 4)
        assertEquals(listOf(0 to 1, 2 to 2, 3 to 3), added.map { it.pageStart to it.pageEnd })
        assertEquals("Invoice", added[1].title)
        assertEquals("", added[2].title)
        val merged = SplitReviewViewModel.rebuild(added, emptyList(), 4)
        assertEquals(listOf(0 to 3), merged.map { it.pageStart to it.pageEnd })
        assertEquals("CBC", merged.single().title)
    }

    @Test
    fun aiQueryRewriteIsValidatedAgainstEnumerations() {
        val parsed = AiQueryRewriter.parse("Sure! {\"terms\":[\"thyroid\"],\"record_types\":[\"lab_report\",\"spaceship\"],\"flags\":[\"high\",\"weird\"],\"date_from\":\"2026-01-01\",\"date_to\":\"soon\"}")!!
        assertEquals(setOf(RecordType.LAB_REPORT), parsed.filters.types)
        assertEquals(setOf("high"), parsed.filters.flags)
        assertEquals("2026-01-01", parsed.filters.dateFrom)
        assertNull(parsed.filters.dateTo)
        assertEquals(listOf("thyroid"), parsed.terms)
        assertNull(AiQueryRewriter.parse("no json here"))
        assertNull(AiQueryRewriter.parse("{\"record_types\":[\"nope\"]}"))
    }

    @Test
    fun aiGapsNeverCallAiForACompleteRecord() {
        val record = HealthRecord(
            id = "r", title = "CBC", recordType = RecordType.LAB_REPORT, source = RecordSource.IMPORT,
            importMethod = ImportMethod.FILE_PICKER, createdMs = 1, updatedMs = 1, documentDate = "2026-09-12",
            mimeType = "application/pdf", fileType = RecordFileType.PDF
        )
        val page = RecordPage("r", 0, "Hemoglobin 7.6 g/dL", TextSource.PDF_TEXT)
        val complete = listOf(f(FieldKey.REPORT_DATE, "2026-09-12", 0.9), f(FieldKey.FACILITY, "City Care", 0.8), f(FieldKey.TEST_RESULT, "Hemoglobin", 0.9))
        assertFalse(AiGaps.hasGap(record, complete, listOf(page), 0.9))
        // Result-looking lines without any parsed result are a gap.
        assertTrue(AiGaps.hasGap(record, complete.filter { it.key != FieldKey.TEST_RESULT }, listOf(page), 0.9))
        assertTrue(AiGaps.hasGap(record.copy(recordType = RecordType.OTHER), complete, listOf(page), 0.0))
        assertTrue(AiGaps.hasGap(record.copy(recordType = RecordType.PRESCRIPTION), complete, emptyList(), 0.9))
    }
}

class PageTextAssemblerSkewTest {
    private fun l(t: String, x: Float, y: Float, w: Float, h: Float) = com.ayuvo.health.records.processing.OcrLine(t, x, y, w, h, 0.9f)

    @Test
    fun tiltedTableRowsStayOnOneLine() {
        // A ~1.2° tilt lifts the right end of each row by ~16 px; "< 200" has a short box (24 px).
        val lines = listOf(
            l("Total Cholesterol", 90f, 600f, 250f, 34f),
            l("242 H", 560f, 590f, 80f, 30f),
            l("mg/dL", 760f, 587f, 90f, 34f),
            l("< 200", 960f, 584f, 80f, 24f),
            l("Triglycerides", 90f, 660f, 190f, 34f),
            l("180 H", 560f, 650f, 80f, 30f),
            l("mg/dL", 760f, 647f, 90f, 34f),
            l("< 150", 960f, 644f, 80f, 24f)
        )
        val text = com.ayuvo.health.records.processing.PageTextAssembler.assemble(lines, 1240f, 1754f).text
        assertEquals("Total Cholesterol  242 H  mg/dL  < 200\nTriglycerides  180 H  mg/dL  < 150", text)
    }

    @Test
    fun separateRowsAreNotMerged() {
        val lines = listOf(l("Name: Ravi", 90f, 300f, 300f, 34f), l("Date: 02/08/2026", 90f, 355f, 300f, 34f), l("Age: 34", 700f, 300f, 150f, 34f))
        assertEquals("Name: Ravi  Age: 34\nDate: 02/08/2026", com.ayuvo.health.records.processing.PageTextAssembler.assemble(lines, 1240f, 1754f).text)
    }
}
