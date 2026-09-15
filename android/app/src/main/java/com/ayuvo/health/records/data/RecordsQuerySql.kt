package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordQuery
import java.text.Normalizer
import java.util.Locale

/**
 * Search text folding shared by FTS writes and MATCH queries (docs/health-records.md §4.7):
 * lowercase + NFD with combining marks removed, so "Hémoglobine" and "hemoglobine" match.
 */
object RecordsSearchText {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun fold(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return combiningMarks.replace(decomposed, "")
    }

    /** Folded search terms in input order, duplicates removed. */
    fun terms(input: String): List<String> =
        separators.split(fold(input)).filter { it.isNotEmpty() }.distinct()

    /**
     * FTS4 MATCH expression: each term becomes `term*` joined by spaces (implicit AND).
     * Null when the input has no searchable characters.
     */
    fun matchQuery(input: String): String? {
        val terms = terms(input)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }
}

/**
 * Builds the Records page query: filter chip + optional FTS search + keyset cursor over the
 * contract's timeline ordering (§5). Pure so the ordering and filters are JVM-tested.
 */
object RecordsQuerySql {
    const val ORDER_BY = "ORDER BY sort_date DESC, created_ms DESC, seq DESC"

    data class Built(val sql: String, val args: List<String>)

    /** WHERE clause fragments (joined with AND) and their args for [query], without the cursor. */
    fun where(query: RecordQuery): Built {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        clauses += if (query.filter == RecordFilter.ARCHIVED) "archived = 1" else "archived = 0"
        filterClause(query.filter)?.let { clauses += it }
        RecordsSearchText.matchQuery(query.search)?.let { match ->
            clauses += "seq IN (SELECT docid FROM records_fts WHERE records_fts MATCH ?)"
            args += match
        }
        return Built(clauses.joinToString(" AND "), args)
    }

    fun filterClause(filter: RecordFilter): String? = when (filter) {
        RecordFilter.ALL, RecordFilter.ARCHIVED -> null
        RecordFilter.REPORTS -> "record_type IN ('lab_report', 'diagnostic_report', 'imaging_report')"
        RecordFilter.PRESCRIPTIONS -> "category = 'prescriptions'"
        RecordFilter.LAB -> "category = 'lab_reports'"
        RecordFilter.IMAGING -> "category = 'imaging'"
        RecordFilter.DOCTOR_NOTES -> "category = 'doctor_visits'"
        RecordFilter.DISCHARGE -> "(record_type = 'discharge_summary' OR category = 'hospitalization')"
        RecordFilter.BILLS -> "category = 'insurance_bills'"
        RecordFilter.IMAGES -> "file_type = 'image'"
        RecordFilter.PDFS -> "file_type = 'pdf'"
        RecordFilter.NOTES -> "(file_type = 'text' OR category = 'personal_notes')"
        RecordFilter.RECEIVED -> "source IN ('share_in', 'open_in')"
        RecordFilter.FAVORITES -> "favorite = 1"
    }

    fun page(query: RecordQuery, after: RecordCursor?, limit: Int, columns: String): Built {
        val where = where(query)
        val clauses = mutableListOf(where.sql)
        val args = where.args.toMutableList()
        if (after != null) {
            clauses += "(sort_date < ? OR (sort_date = ? AND (created_ms < ? OR (created_ms = ? AND seq < ?))))"
            args += listOf(
                after.sortDate,
                after.sortDate,
                after.createdMs.toString(),
                after.createdMs.toString(),
                after.seq.toString()
            )
        }
        args += limit.coerceAtLeast(1).toString()
        val sql = "SELECT $columns FROM records WHERE " +
            clauses.joinToString(" AND ") + " $ORDER_BY LIMIT ?"
        return Built(sql, args)
    }
}
