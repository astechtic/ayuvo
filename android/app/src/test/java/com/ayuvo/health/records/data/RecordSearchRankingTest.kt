package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.search.RecordSearchRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

class RecordSearchRankingTest {
    /** matchinfo 'pcnalx' for 1 phrase, 6 columns, 10 docs. */
    private fun info(tfTitle: Int, tfBody: Int, lenTitle: Int = 3, lenBody: Int = 100, df: Int = 2): IntArray {
        val c = 6
        val out = mutableListOf(1, c, 10)
        out += listOf(4, 5, 5, 200, 3, 2)                       // avg lengths
        out += listOf(lenTitle, 0, 0, lenBody, 0, 0)            // this row's lengths
        for (col in 0 until c) {
            val tf = when (col) { 0 -> tfTitle; 3 -> tfBody; else -> 0 }
            out += listOf(tf, tf * 3, if (tf > 0) df else 0)
        }
        return out.toIntArray()
    }

    @Test
    fun titleHitsOutrankBodyHitsAndMatchinfoDecodes() {
        val weights = RecordsQuerySql.COLUMN_WEIGHTS
        val title = RecordSearchRanking.bm25(info(1, 0), weights)
        val body = RecordSearchRanking.bm25(info(0, 1), weights)
        assertTrue("title $title body $body", title > body)
        assertEquals(0, RecordSearchRanking.bestColumn(info(1, 0), weights))
        assertEquals(3, RecordSearchRanking.bestColumn(info(0, 2), weights))

        val raw = info(1, 1)
        val buffer = ByteBuffer.allocate(raw.size * 4).order(ByteOrder.nativeOrder())
        raw.forEach { buffer.putInt(it) }
        assertEquals(raw.toList(), RecordSearchRanking.decodeMatchinfo(buffer.array()).toList())
    }

    @Test
    fun recencyBoostDecaysOverTwoYears() {
        val today = LocalDate.of(2026, 9, 15)
        assertEquals(0.15, RecordSearchRanking.recencyBoost("2026-09-15", today), 1e-9)
        assertEquals(0.075, RecordSearchRanking.recencyBoost("2025-09-15", today), 0.001)
        assertEquals(0.0, RecordSearchRanking.recencyBoost("2020-01-01", today), 1e-9)
    }

    @Test
    fun advancedFiltersBuildStructuredClauses() {
        val q = RecordQuery(
            filter = RecordFilter.NEEDS_REVIEW,
            advanced = RecordAdvancedFilters(
                dateFrom = "2026-01-01", dateTo = "2026-12-31", types = setOf(RecordType.LAB_REPORT),
                doctor = "mehta", flags = setOf("abnormal"), tagIds = setOf("t1"), aiProcessed = true, userConfirmed = true
            ),
            terms = listOf("hemoglobin")
        )
        val where = RecordsQuerySql.where(q)
        listOf(
            "review_status = 'needs_review'", "sort_date >= ?", "sort_date <= ?", "record_type IN (?)",
            "e.kind = 'doctor'", "field_key = 'test_result'", "tag_id = ?", "ai_mode_used != 'none'", "records_fts MATCH ?"
        ).forEach { assertTrue(it, where.sql.contains(it)) }
        // Phase 3: Doctor is entity-backed, prefix-matched on the normalized name.
        assertTrue(where.args.containsAll(listOf("mehta", "mehta%")))
        val analyte = RecordsQuerySql.where(RecordQuery(advanced = RecordAdvancedFilters(
            analyteConditions = listOf(com.ayuvo.health.records.model.AnalyteCondition("hemoglobin", flag = "low"), com.ayuvo.health.records.model.AnalyteCondition("hba1c", op = ">", value = 7.0)),
            doctorEntityIds = setOf("e1")
        )))
        assertTrue(analyte.sql.contains("flag IN (?, ?)"))
        assertTrue(analyte.sql.contains("canonical_value > ?"))
        assertTrue(analyte.sql.contains("entity_id IN (?)"))
        assertEquals(listOf("hemoglobin", "low", "critical_low", "hba1c", "7.0", "e1").sorted(), analyte.args.sorted())
        assertEquals("hemoglobin*", where.args.first())
        assertTrue(where.args.contains("%\"flag\":\"critical_low\"%"))
        val search = RecordsQuerySql.searchCandidates(q, "hemoglobin*", "seq, id", 50)
        assertTrue(search.sql.contains("matchinfo(records_fts, 'pcnalx')"))
        assertEquals("hemoglobin*", search.args[search.args.size - 2])
        // Parsed-but-empty terms ("abnormal" alone) never fall back to a raw-text MATCH.
        assertTrue(!RecordsQuerySql.where(RecordQuery(search = "abnormal", terms = emptyList())).sql.contains("MATCH"))
        assertEquals(3, RecordAdvancedFilters(dateFrom = "x", tagIds = setOf("a"), aiProcessed = true).activeCount)
    }
}
