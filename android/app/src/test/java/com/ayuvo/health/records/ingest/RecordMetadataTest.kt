package com.ayuvo.health.records.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RecordMetadataTest {
    private val today = LocalDate.of(2026, 9, 15)

    @Test
    fun titleFromUserThenFilenameThenTypeDateThenUntitled() {
        assertEquals("CBC September", RecordTitles.derive("  CBC September ", "x.pdf", "Scan", "Sep 15"))
        assertEquals("blood report sept", RecordTitles.derive(null, "blood_report-sept.pdf", "Scan", "Sep 15"))
        assertEquals("Scanned document — 15 Sep 2026", RecordTitles.derive("", null, "Scanned document", "15 Sep 2026"))
        assertEquals(RecordTitles.UNTITLED, RecordTitles.derive(null, null, null, "15 Sep 2026"))
        assertEquals(RecordTitles.UNTITLED, RecordTitles.derive(null, "   ", "Photo", null))
    }

    @Test
    fun filenameTitleKeepsDotfilesAndInnerDots() {
        assertEquals("report v1.2", RecordTitles.fromFilename("report_v1.2.pdf"))
        assertEquals(".env", RecordTitles.fromFilename(".env"))
        assertEquals("lab", RecordTitles.fromFilename("/storage/emulated/0/Download/lab.pdf"))
        assertNull(RecordTitles.fromFilename("__.pdf"))
    }

    @Test
    fun filenameDatePatterns() {
        assertEquals(LocalDate.of(2026, 8, 10), RecordDates.fromFilename("CBC_2026-08-10.pdf", today))
        assertEquals(LocalDate.of(2024, 1, 31), RecordDates.fromFilename("IMG_20240131_101500.jpg", today))
        assertEquals(LocalDate.of(2025, 12, 5), RecordDates.fromFilename("report 05-12-2025.pdf", today))
        assertNull(RecordDates.fromFilename("scan_20261399.pdf", today))
        assertNull(RecordDates.fromFilename("id_1234567890.pdf", today))
        assertNull(RecordDates.fromFilename("plan_2030-01-01.pdf", today))
        assertNull(RecordDates.fromFilename(null, today))
    }

    @Test
    fun exifDateTimeOriginal() {
        assertEquals(LocalDate.of(2025, 7, 18), RecordDates.fromExif("2025:07:18 09:41:00", today))
        assertNull(RecordDates.fromExif("0000:00:00 00:00:00", today))
        assertNull(RecordDates.fromExif(null, today))
    }

    @Test
    fun pdfCreationDateScan() {
        val header = "%PDF-1.4\n1 0 obj << /Producer (X) /CreationDate (D:20260912083000+05'30') >> endobj"
        assertEquals(LocalDate.of(2026, 9, 12), RecordDates.fromPdfBytes(header.toByteArray(Charsets.ISO_8859_1), today))
        val noPrefix = byteArrayOf(0x00, 0xFF.toByte()) + "/CreationDate(20250101)".toByteArray()
        assertEquals(LocalDate.of(2025, 1, 1), RecordDates.fromPdfBytes(noPrefix, today))
        assertNull(RecordDates.fromPdfBytes("/ModDate (D:20260101)".toByteArray(), today))
        assertNull(RecordDates.fromPdfBytes(ByteArray(0), today))
    }
}
