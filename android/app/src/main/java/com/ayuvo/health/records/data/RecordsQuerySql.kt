package com.ayuvo.health.records.data

import com.ayuvo.health.records.knowledge.EntityRules
import com.ayuvo.health.records.model.AnalyteCondition
import com.ayuvo.health.records.model.EntityKind
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
        // Phase 3: Doctor / Hospital are backed by entities (§19), not raw field text.
        advanced.doctor?.let { EntityRules.normalize(EntityKind.DOCTOR, it) }?.takeIf { it.isNotEmpty() }?.let { prefix ->
            clauses += entityPrefixClause(EntityKind.DOCTOR)
            args += prefix
            args += escapeLike(prefix) + "%"
        }
        advanced.facility?.let { EntityRules.normalize(EntityKind.FACILITY, it) }?.takeIf { it.isNotEmpty() }?.let { prefix ->
            clauses += entityPrefixClause(EntityKind.FACILITY)
            args += prefix
            args += escapeLike(prefix) + "%"
        }
        if (advanced.doctorEntityIds.isNotEmpty()) {
            clauses += "id IN (SELECT record_id FROM record_entities WHERE entity_id IN (${advanced.doctorEntityIds.joinToString(", ") { "?" }}))"
            args += advanced.doctorEntityIds.sorted()
        }
        if (advanced.facilityEntityIds.isNotEmpty()) {
            clauses += "id IN (SELECT record_id FROM record_entities WHERE entity_id IN (${advanced.facilityEntityIds.joinToString(", ") { "?" }}))"
            args += advanced.facilityEntityIds.sorted()
        }
        if (advanced.analyteIds.isNotEmpty()) {
            clauses += "id IN (SELECT record_id FROM observations WHERE state != 'rejected' AND analyte_id IN (${advanced.analyteIds.joinToString(", ") { "?" }}))"
            args += advanced.analyteIds.sorted()
        }
        for (condition in advanced.analyteConditions) {
            val built = observationCondition(condition, alias = null)
            clauses += "id IN (SELECT record_id FROM observations WHERE ${built.sql})"
            args += built.args
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

    private fun entityPrefixClause(kind: EntityKind): String =
        "id IN (SELECT re.record_id FROM record_entities re JOIN entities e ON e.id = re.entity_id " +
            "WHERE e.kind = '${kind.raw}' AND (e.normalized_name = ? OR e.normalized_name LIKE ? ESCAPE '\\'))"

    private fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Flags an analyte flag condition matches (§17 flag families plus `normal`). */
    fun flagValues(flag: String): List<String> = when (flag) {
        "abnormal" -> abnormalFlags
        "critical" -> listOf("critical_low", "critical_high")
        "low" -> listOf("low", "critical_low")
        "high" -> listOf("high", "critical_high")
        else -> listOf(flag)
    }

    /**
     * WHERE fragment over `observations` (optionally aliased) for one §23 condition: the analyte,
     * not rejected, and its flag family or a comparison on `canonical_value`.
     */
    fun observationCondition(condition: AnalyteCondition, alias: String?): Built {
        val p = alias?.let { "$it." } ?: ""
        val parts = mutableListOf("${p}analyte_id = ?", "${p}state != 'rejected'")
        val args = mutableListOf(condition.analyteId)
        if (condition.flag != null) {
            val values = flagValues(condition.flag)
            parts += "${p}flag IN (${values.joinToString(", ") { "?" }})"
            args += values
        }
        val op = condition.op
        if (op != null) {
            // §23: no unit → the typed number is canonical; a unit that doesn't convert matches nothing.
            val value = if (condition.unit == null) condition.canonicalValue ?: condition.value else condition.canonicalValue
            if (value != null && op in setOf(">", ">=", "<", "<=")) {
                parts += "${p}canonical_value $op ?"
                args += value.toString()
            } else {
                parts += "0"
            }
        }
        return Built(parts.joinToString(" AND "), args)
    }

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
