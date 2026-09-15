package com.ayuvo.health.records.data

import com.ayuvo.health.records.model.RecordCursor
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.records.model.RecordsSearchTerms
import com.ayuvo.health.records.processing.RecordText

/**
 * Search text folding shared by FTS writes and MATCH queries (docs/health-records.md §4.7, §10):
 * one [RecordText.fold] definition on both sides, so "Hémoglobine" and "hemoglobine" match.
 */
object RecordsSearchText {
    fun fold(text: String?): String = RecordText.fold(text)

    /** Folded search terms in input order, duplicates removed. */
    fun terms(input: String): List<String> = RecordsSearchTerms.split(input)

    /**
     * FTS4 MATCH expression: each term becomes `term*` joined by spaces (implicit AND).
     * Null when the input has no searchable characters.
     */
    fun matchQuery(input: String): String? = matchTerms(terms(input))

    fun matchTerms(terms: List<String>): String? {
        val clean = terms.flatMap { RecordsSearchTerms.split(it) }.distinct()
        if (clean.isEmpty()) return null
        return clean.joinToString(" ") { "$it*" }
    }
}

/**
 * Builds the Records page query: filter chip + advanced filters + optional FTS search + keyset
 * cursor over the contract's timeline ordering (§5). Pure so the ordering and filters are JVM-tested.
 */
object RecordsQuerySql {
    const val ORDER_BY = "ORDER BY sort_date DESC, created_ms DESC, seq DESC"

    /** `value_json.flag` values that count as abnormal (§17). */
    private val abnormalFlags = listOf("low", "high", "critical_low", "critical_high", "abnormal")

    data class Built(val sql: String, val args: List<String>)

    /** WHERE clause fragments (joined with AND) and their args for [query], without the cursor. */
    fun where(query: RecordQuery, includeMatch: Boolean = true): Built {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        val advanced = query.advanced
        clauses += if (query.filter == RecordFilter.ARCHIVED || advanced.archived) "archived = 1" else "archived = 0"
        filterClause(query.filter)?.let { clauses += it }
        if (includeMatch) {
            matchExpression(query)?.let { match ->
                clauses += "seq IN (SELECT docid FROM records_fts WHERE records_fts MATCH ?)"
                args += match
            }
        }
        advanced.dateFrom?.let { clauses += "sort_date >= ?"; args += it }
        advanced.dateTo?.let { clauses += "sort_date <= ?"; args += it }
        if (advanced.types.isNotEmpty()) {
            clauses += "record_type IN (${advanced.types.joinToString(", ") { "?" }})"
            args += advanced.types.map { it.raw }.sorted()
        }
        if (advanced.categories.isNotEmpty()) {
            clauses += "category IN (${advanced.categories.joinToString(", ") { "?" }})"
            args += advanced.categories.map { it.raw }.sorted()
        }
        advanced.doctor?.let { prefix ->
            clauses += fieldPrefixClause("doctor_name")
            args += prefix
            args += "$prefix%"
        }
        advanced.facility?.let { prefix ->
            clauses += fieldPrefixClause("facility")
            args += prefix
            args += "$prefix%"
        }
        if (advanced.flags.isNotEmpty()) {
            val wanted = advanced.flags.flatMap { flag ->
                when (flag) {
                    "abnormal" -> abnormalFlags
                    "critical" -> listOf("critical_low", "critical_high")
                    "low" -> listOf("low", "critical_low")
                    "high" -> listOf("high", "critical_high")
                    else -> listOf(flag)
                }
            }.distinct().sorted()
            // value_json is written by FieldJson (compact org.json output), so the flag appears as
            // `"flag":"<value>"`; LIKE avoids depending on JSON1 in older framework SQLite builds.
            clauses += "id IN (SELECT record_id FROM record_fields WHERE field_key = 'test_result' AND state != 'rejected' AND (" +
                wanted.joinToString(" OR ") { "value_json LIKE ?" } + "))"
            args += wanted.map { "%\"flag\":\"$it\"%" }
        }
        for (tagId in advanced.tagIds.sorted()) {
            clauses += "id IN (SELECT record_id FROM record_tags WHERE tag_id = ?)"
            args += tagId
        }
        if (advanced.aiProcessed) clauses += "ai_mode_used != 'none'"
        if (advanced.userConfirmed) {
            clauses += "(review_status = 'reviewed' OR id IN (SELECT record_id FROM record_fields WHERE state IN ('confirmed', 'user')))"
        }
        if (advanced.favorites && query.filter != RecordFilter.FAVORITES) clauses += "favorite = 1"
        if (advanced.received && query.filter != RecordFilter.RECEIVED) clauses += "source IN ('share_in', 'open_in')"
        if (advanced.needsReview && query.filter != RecordFilter.NEEDS_REVIEW) clauses += "review_status = 'needs_review'"
        return Built(clauses.joinToString(" AND "), args)
    }

    private fun fieldPrefixClause(key: String): String =
        "id IN (SELECT record_id FROM record_fields WHERE field_key = '$key' AND state != 'rejected' " +
            "AND (lower(value_text) = ? OR lower(value_text) LIKE ?))"

    fun matchExpression(query: RecordQuery): String? =
        // Parsed terms (possibly empty: "abnormal" is only a filter) replace the raw search text.
        if (query.terms != null) RecordsSearchText.matchTerms(query.terms) else RecordsSearchText.matchQuery(query.search)

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
        RecordFilter.NEEDS_REVIEW -> "review_status = 'needs_review'"
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

    /**
     * Ranked search candidates: FTS matches joined with the structured filters, returning
     * `matchinfo('pcnalx')` and a snippet per column so ranking happens in code (§17).
     */
    fun searchCandidates(query: RecordQuery, match: String, columns: String, limit: Int): Built {
        val where = where(query, includeMatch = false)
        val sql = "SELECT r.*, matchinfo(records_fts, 'pcnalx'), " +
            SNIPPET_COLUMNS.indices.joinToString(", ") { "snippet(records_fts, '[', ']', '…', $it, 12)" } +
            " FROM records_fts JOIN (SELECT $columns FROM records WHERE ${where.sql}) r ON r.seq = records_fts.docid" +
            " WHERE records_fts MATCH ? ORDER BY r.sort_date DESC, r.created_ms DESC, r.seq DESC LIMIT ?"
        return Built(sql, where.args + match + limit.toString())
    }

    /** FTS column order of `records_fts` and the §17 BM25 weights. */
    val SNIPPET_COLUMNS = listOf("title", "people", "clinical", "body", "notes_tags", "highlights")
    val COLUMN_WEIGHTS = doubleArrayOf(5.0, 3.0, 3.0, 1.0, 2.0, 2.0)
}
