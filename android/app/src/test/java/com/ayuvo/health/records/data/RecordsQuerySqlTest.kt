package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordSortDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsQuerySqlTest {

    @Test
    fun sortDateUsesDeviceLocalImportDayWhenUndated() {
        // 2026-08-31T19:00Z is 00:30 on 1 September in India (UTC+05:30).
        val createdMs = java.time.Instant.parse("2026-08-31T19:00:00Z").toEpochMilli()
        val kolkata = java.time.ZoneId.of("Asia/Kolkata")
        assertEquals("2026-09-01", RecordSortDate.localDay(createdMs, kolkata))
        assertEquals("2026-09-01", RecordSortDate.forRecord(null, createdMs, kolkata))
        assertEquals("2026-09", RecordSortDate.forRecord(null, createdMs, kolkata).take(7))
        // The contract no longer uses the UTC day.
        assertEquals("2026-08-31", RecordSortDate.localDay(createdMs, java.time.ZoneOffset.UTC))
        // A document date always wins.
        assertEquals("2025-07-18", RecordSortDate.forRecord("2025-07-18", createdMs, kolkata))
    }

    @Test
    fun healthRecordDefaultsSortDateToLocalDay() {
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            val createdMs = java.time.Instant.parse("2026-08-31T19:00:00Z").toEpochMilli()
            val record = com.ayuvo.health.records.model.HealthRecord(
                id = "a", title = "t", source = com.ayuvo.health.records.model.RecordSource.PASTE,
                importMethod = com.ayuvo.health.records.model.ImportMethod.PASTE_TEXT,
                createdMs = createdMs, updatedMs = createdMs, mimeType = "text/plain",
                fileType = com.ayuvo.health.records.model.RecordFileType.TEXT
            )
            assertEquals("2026-09-01", record.sortDate)
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test
    fun foldingLowercasesAndStripsDiacritics() {
        assertEquals("hemoglobine creatinine", RecordsSearchText.fold("Hémoglobine CRÉATININE"))
        assertEquals("sao paulo", RecordsSearchText.fold("São Paulo"))
        assertEquals("", RecordsSearchText.fold(null))
    }

    @Test
    fun matchQueryPrefixesTermsWithImplicitAnd() {
        assertEquals("blood* report*", RecordsSearchText.matchQuery("  Blood  report "))
        assertEquals("hemo*", RecordsSearchText.matchQuery("Hémo"))
        // Quotes, operators and punctuation never reach MATCH syntax.
        assertEquals("cbc* sep* 2026*", RecordsSearchText.matchQuery("\"CBC\" - sep, 2026*"))
        assertEquals("or* not*", RecordsSearchText.matchQuery("OR NOT"))
        assertEquals("dr* mehta*", RecordsSearchText.matchQuery("dr mehta DR"))
        assertNull(RecordsSearchText.matchQuery("  -- ** "))
    }

    @Test
    fun defaultQueryHidesArchivedAndOrdersByTimeline() {
        val built = RecordsQuerySql.page(RecordQuery(), after = null, limit = 60, columns = "id")
        assertTrue(built.sql.contains("archived = 0"))
        assertTrue(
            built.sql.endsWith(
                "ORDER BY sort_date DESC, created_ms DESC, seq DESC LIMIT ?"
            )
        )
        assertEquals(listOf("60"), built.args)
    }

    @Test
    fun archivedFilterShowsOnlyArchived() {
        val where = RecordsQuerySql.where(RecordQuery(filter = RecordFilter.ARCHIVED))
        assertEquals("archived = 1", where.sql)
    }

    @Test
    fun everyFilterHasAClauseExceptAllAndArchived() {
        for (filter in RecordFilter.entries) {
            val clause = RecordsQuerySql.filterClause(filter)
            if (filter == RecordFilter.ALL || filter == RecordFilter.ARCHIVED) assertNull(clause) else assertTrue(filter.name, !clause.isNullOrBlank())
        }
        assertEquals("source IN ('share_in', 'open_in')", RecordsQuerySql.filterClause(RecordFilter.RECEIVED))
        assertEquals("file_type = 'pdf'", RecordsQuerySql.filterClause(RecordFilter.PDFS))
    }

    @Test
    fun searchAddsFtsSubqueryWithFoldedArgument() {
        val built = RecordsQuerySql.page(RecordQuery(filter = RecordFilter.FAVORITES, search = "Crème"), null, 10, "id")
        assertTrue(built.sql.contains("favorite = 1"))
        assertTrue(built.sql.contains("seq IN (SELECT docid FROM records_fts WHERE records_fts MATCH ?)"))
        assertEquals(listOf("creme*", "10"), built.args)
    }

    @Test
    fun blankSearchAddsNoFtsClause() {
        val built = RecordsQuerySql.page(RecordQuery(search = "   "), null, 10, "id")
        assertFalse(built.sql.contains("MATCH"))
    }

    @Test
    fun cursorUsesKeysetOnEffectiveDateCreatedAndSeq() {
        val cursor = RecordCursor(sortDate = "2026-09-12", createdMs = 1_000L, seq = 7L)
        val built = RecordsQuerySql.page(RecordQuery(search = "cbc"), cursor, 60, "id")
        assertTrue(
            built.sql.contains(
                "(sort_date < ? OR (sort_date = ? AND (created_ms < ? OR (created_ms = ? AND seq < ?))))"
            )
        )
        assertEquals(listOf("cbc*", "2026-09-12", "2026-09-12", "1000", "1000", "7", "60"), built.args)
    }
}
