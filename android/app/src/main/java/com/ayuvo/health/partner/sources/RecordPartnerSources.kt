package com.ayuvo.health.partner.sources

import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.partner.logic.MappedRecord
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerMappers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * Sources over one table keyed by a text `id`, paged by id. [db] returns null when the database does not exist yet
 * (nothing to share); it is never created just to look.
 */
abstract class IdTableSource(private val db: () -> SQLiteDatabase?) : PartnerSource {
    protected abstract val table: String
    protected abstract val columns: List<String>
    protected open val where: String = "1"
    protected abstract fun map(row: JsonObject): SourceRecord?

    private fun inScope(r: SourceRecord, dayFrom: String?) =
        dayFrom == null || r.day == null || PartnerJson.compareCodePoints(r.day, dayFrom) >= 0

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val d = db() ?: return
        var lastId = ""
        while (true) {
            var n = 0
            d.rawQuery(
                "SELECT ${columns.joinToString(", ")} FROM $table WHERE ($where) AND id > ? ORDER BY id LIMIT ${HealthSql.PAGE}",
                arrayOf(lastId)
            ).use { c ->
                while (c.moveToNext()) {
                    n++
                    val row = CursorJson.row(c, columns)
                    lastId = c.getString(0)
                    map(row)?.takeIf { inScope(it, dayFrom) }?.let(onRecord)
                }
            }
            if (n < HealthSql.PAGE) break
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val d = db() ?: return emptyMap()
        val out = HashMap<String, SourceRecord>()
        for (chunk in ids.distinct().chunked(200)) {
            d.rawQuery(
                "SELECT ${columns.joinToString(", ")} FROM $table WHERE ($where) AND id IN (${HealthSql.placeholders(chunk.size)})",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) map(CursorJson.row(c, columns))?.let { out[it.recordId] = it } }
        }
        return out
    }
}

/** `medication` (photo_path and related_record_id are never selected). */
class MedicationSource(db: () -> SQLiteDatabase?) : IdTableSource(db) {
    override val type = "medication"
    override val table = "medications"
    override val columns = listOf(
        "id", "name", "generic_name", "brand_name", "strength", "form", "dose_quantity", "dose_unit", "food_relation",
        "instructions", "start_date", "end_date", "status", "is_prn"
    )
    override fun map(row: JsonObject) = SourceRecord.of(PartnerMappers.medication(row))
}

/** `medication_schedule` (times_json / days_json become arrays). */
class MedicationScheduleSource(db: () -> SQLiteDatabase?) : IdTableSource(db) {
    override val type = "medication_schedule"
    override val table = "medication_schedules"
    override val columns = listOf(
        "id", "medication_id", "frequency_kind", "times_json", "days_json", "interval_hours", "anchor_time",
        "active_from_ms", "active_until_ms"
    )
    override fun map(row: JsonObject): SourceRecord? = runCatching { SourceRecord.of(PartnerMappers.schedule(row)) }.getOrNull()
}

/**
 * `dose_log` on the local day of `scheduled_at_ms`. The user's own short dose `note` is shared (docs §7.5), trimmed and
 * capped at [NOTE_MAX] characters; record `notes` (the forbidden key) are never read here.
 */
class DoseLogSource(db: () -> SQLiteDatabase?, private val zone: () -> ZoneId) : IdTableSource(db) {
    override val type = "dose_log"
    override val table = "dose_logs"
    override val columns = listOf("id", "medication_id", "schedule_id", "scheduled_at_ms", "status", "taken_at_ms", "dose_quantity", "dose_unit", "note")
    override fun map(row: JsonObject): SourceRecord? {
        val at = PartnerJson.long(row["scheduled_at_ms"]) ?: return null
        val day = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate().toString()
        val note = PartnerJson.str(row["note"])?.trim()?.takeIf { it.isNotEmpty() }?.let { PartnerJson.pyTake(it, NOTE_MAX) }
        val cleaned = JsonObject(row.filterKeys { it != "note" } + (note?.let { mapOf("note" to kotlinx.serialization.json.JsonPrimitive(it)) } ?: emptyMap()))
        return SourceRecord.of(PartnerMappers.doseLog(cleaned, day))
    }

    companion object {
        /** docs §7.5: the dose note is at most 200 characters. */
        const val NOTE_MAX = 200
    }
}

/**
 * `report_overview`: a non-archived `records` row plus its fields, highlights and observations, read by named
 * columns only (never files, pages, OCR text, evidence, bounding boxes, source pages or notes).
 */
class ReportOverviewSource(private val db: () -> SQLiteDatabase?) : PartnerSource {
    override val type = "report_overview"
    private val recordCols = listOf("id", "title", "record_type", "category", "document_date", "sort_date", "archived")
    private val fieldCols = listOf("id", "field_key", "value_text", "value_json", "confidence", "state")
    private val highlightCols = listOf("id", "section", "text", "dismissed", "position")
    private val observationCols = listOf("id", "raw_name", "analyte_id", "value_text", "value_num", "unit", "ref_low", "ref_high", "ref_text", "flag", "observed_date", "state")
    private val fieldKeys = listOf("doctor_name", "doctor_specialty", "facility", "report_date", "diagnosis", "medication")

    private fun children(d: SQLiteDatabase, table: String, cols: List<String>, recordId: String, extra: String = "", extraArgs: List<String> = emptyList()): List<JsonObject> =
        d.rawQuery("SELECT ${cols.joinToString(", ")} FROM $table WHERE record_id = ?$extra", (listOf(recordId) + extraArgs).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(CursorJson.row(c, cols)) }
        }

    private fun build(d: SQLiteDatabase, record: JsonObject): SourceRecord? {
        val id = PartnerJson.str(record["id"]) ?: return null
        val fields = children(d, "record_fields", fieldCols, id, " AND field_key IN (${HealthSql.placeholders(fieldKeys.size)})", fieldKeys)
        val highlights = children(d, "record_highlights", highlightCols, id)
        val observations = children(d, "observations", observationCols, id)
        val mapped = runCatching { PartnerMappers.reportOverview(record, fields, highlights, observations) }.getOrNull() ?: return null
        return SourceRecord.of(fitFrame(mapped))
    }

    /**
     * A record must fit one Noise frame on the wire (docs §5). A very long lab panel keeps every abnormal result and
     * the first results by name (the `abnormal` list is never trimmed); this only happens to panels of hundreds of rows.
     */
    private fun fitFrame(m: MappedRecord): MappedRecord {
        var data = m.data
        fun size(o: JsonObject) = PartnerJson.dumpsCompactSorted(o).toByteArray(Charsets.UTF_8).size
        while (size(data) > MAX_RECORD_BYTES) {
            val results = data["results"] as? JsonArray ?: break
            if (results.isEmpty()) break
            val keep = (results.size * 9) / 10
            val next = LinkedHashMap(data)
            if (keep == 0) next.remove("results") else next["results"] = JsonArray(results.take(keep))
            data = JsonObject(next)
        }
        return m.copy(data = data)
    }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val d = db() ?: return
        var lastSeq = Long.MIN_VALUE
        while (true) {
            val page = ArrayList<JsonObject>()
            d.rawQuery(
                "SELECT seq, ${recordCols.joinToString(", ")} FROM records WHERE archived = 0 AND seq > ? ORDER BY seq LIMIT 200",
                arrayOf(lastSeq.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    lastSeq = c.getLong(0)
                    page += JsonObject(recordCols.withIndex().associate { (i, name) -> name to CursorJson.value(c, i + 1) })
                }
            }
            for (r in page) build(d, r)?.takeIf { dayFrom == null || it.day == null || it.day >= dayFrom }?.let(onRecord)
            if (page.size < 200) break
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val d = db() ?: return emptyMap()
        val out = HashMap<String, SourceRecord>()
        for (id in ids.distinct()) {
            val rec = d.rawQuery("SELECT ${recordCols.joinToString(", ")} FROM records WHERE id = ? AND archived = 0", arrayOf(id)).use { c ->
                if (c.moveToFirst()) CursorJson.row(c, recordCols) else null
            } ?: continue
            build(d, rec)?.let { out[id] = it }
        }
        return out
    }

    companion object {
        /** Leaves room in a 65535-byte frame for the envelope and the CHANGES wrapper. */
        const val MAX_RECORD_BYTES = 56_000
    }
}
