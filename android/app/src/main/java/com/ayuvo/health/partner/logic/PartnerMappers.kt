package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * docs/partner-sync.md §7.7: shared-schema source rows -> envelope parts. Every mapper reads named columns only,
 * so file paths, photos, evidence, bounding boxes and notes of records never reach the wire.
 * Rows are JSON objects keyed by the source column names (null or missing = NULL).
 */
object PartnerMappers {
    private fun JsonObject.col(k: String): JsonElement? = this[k]

    private fun text(e: JsonElement?): String = when {
        e == null || e is JsonNull -> "None"
        e is JsonPrimitive -> e.content
        else -> e.toString()
    }

    private fun intText(e: JsonElement?): String =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()?.toLong()?.toString() ?: text(e)

    /** `health_daily_rollups` row -> metric_day, or null when the type is not shareable or has no unit. */
    fun rollup(row: JsonObject): MappedRecord? {
        val data = PartnerJson.dropNone(
            "type_id" to row.col("type_id"), "day" to row.col("day"), "unit" to row.col("unit"),
            "sum" to row.col("sum"), "avg" to row.col("avg"), "min" to row.col("min"), "max" to row.col("max"),
            "count" to row.col("count"), "last_value" to row.col("last_value"), "last_at_ms" to row.col("last_at_ms"),
            "v2_avg" to row.col("v2_avg"), "v2_min" to row.col("v2_min"), "v2_max" to row.col("v2_max"),
            "duration_s" to row.col("duration_s")
        )
        val cat = PartnerEnvelopes.categoryOf("metric_day", data) ?: return null
        if (PartnerJson.isNone(data["unit"])) return null
        val day = text(row.col("day"))
        return MappedRecord("metric_day", "${text(row.col("type_id"))}:$day", cat, PartnerJson.str(row.col("day")), data)
    }

    /** `health_hourly_rollups` row -> metric_hour. */
    fun hourly(row: JsonObject): MappedRecord? {
        val data = PartnerJson.dropNone(
            "type_id" to row.col("type_id"), "day" to row.col("day"), "hour" to row.col("hour"),
            "hour_start_ms" to row.col("hour_start_ms"), "unit" to row.col("unit"), "sum" to row.col("sum"),
            "avg" to row.col("avg"), "min" to row.col("min"), "max" to row.col("max"), "count" to row.col("count")
        )
        val cat = PartnerEnvelopes.categoryOf("metric_hour", data) ?: return null
        if (PartnerJson.isNone(data["unit"])) return null
        val id = "${text(row.col("type_id"))}:${text(row.col("day"))}:${intText(row.col("hour"))}"
        return MappedRecord("metric_hour", id, cat, PartnerJson.str(row.col("day")), data)
    }

    /** `health_samples` row -> sample (intraday window only; tombstoned rows are not mapped). */
    fun sample(row: JsonObject, nowMs: Long): MappedRecord? {
        if (PartnerJson.truthy(row.col("deleted"))) return null
        val start = PartnerJson.double(row.col("start_ms")) ?: return null
        if (start < (nowMs - PartnerCatalog.current.intradayMs).toDouble()) return null
        val data = PartnerJson.dropNone(
            "type_id" to row.col("type_id"), "start_ms" to row.col("start_ms"), "end_ms" to row.col("end_ms"),
            "unit" to row.col("unit"), "value" to row.col("value"), "value2" to row.col("value2"),
            "value3" to row.col("value3"), "category_value" to row.col("category_value"),
            "source_name" to row.col("source_name")
        )
        val cat = PartnerEnvelopes.categoryOf("sample", data) ?: return null
        return MappedRecord("sample", text(row.col("id")), cat, PartnerJson.str(row.col("local_day")), data)
    }

    /** `analytics_results` row -> analytics_day for single-day results of allow-listed metrics. */
    fun analytics(row: JsonObject): MappedRecord? {
        val metric = PartnerJson.str(row.col("metric_id"))
        if (metric == null || metric !in PartnerCatalog.current.analyticsMetrics) return null
        if (row.col("period_start") != row.col("period_end")) return null
        val data = PartnerJson.dropNone(
            "metric_id" to row.col("metric_id"), "day" to row.col("period_start"), "status" to row.col("status"),
            "classification" to row.col("classification"), "value" to row.col("value"),
            "value2" to row.col("value2"), "value3" to row.col("value3"), "unit" to row.col("unit"),
            "confidence" to row.col("confidence"), "coverage" to row.col("coverage")
        )
        val start = text(row.col("period_start"))
        return MappedRecord("analytics_day", "$metric:$start", "vitals", PartnerJson.str(row.col("period_start")), data)
    }

    /** `medications` row -> medication; photo_path and related_record_id are never read. */
    fun medication(row: JsonObject): MappedRecord {
        val data = PartnerJson.dropNone(
            "name" to row.col("name"), "generic_name" to row.col("generic_name"),
            "brand_name" to row.col("brand_name"), "strength" to row.col("strength"), "form" to row.col("form"),
            "dose_quantity" to row.col("dose_quantity"), "dose_unit" to row.col("dose_unit"),
            "food_relation" to row.col("food_relation"), "instructions" to row.col("instructions"),
            "start_date" to row.col("start_date"), "end_date" to row.col("end_date"), "status" to row.col("status"),
            "is_prn" to JsonPrimitive(PartnerJson.truthy(row.col("is_prn")))
        )
        return MappedRecord("medication", text(row.col("id")), "medicines", null, data)
    }

    private fun jsonArrayColumn(e: JsonElement?): JsonElement {
        val s = PartnerJson.str(e)
        return if (s.isNullOrEmpty()) JsonArray(emptyList()) else PartnerJson.parse(s)
    }

    /** `medication_schedules` row -> medication_schedule; times_json / days_json become arrays. */
    fun schedule(row: JsonObject): MappedRecord {
        val data = PartnerJson.dropNone(
            "medication_id" to row.col("medication_id"), "frequency_kind" to row.col("frequency_kind"),
            "times" to jsonArrayColumn(row.col("times_json")), "days" to jsonArrayColumn(row.col("days_json")),
            "interval_hours" to row.col("interval_hours"), "anchor_time" to row.col("anchor_time"),
            "active_from_ms" to row.col("active_from_ms"), "active_until_ms" to row.col("active_until_ms")
        )
        return MappedRecord("medication_schedule", text(row.col("id")), "medicines", null, data)
    }

    /** `dose_logs` row -> dose_log on the sender's local day of scheduled_at_ms. */
    fun doseLog(row: JsonObject, localDay: String): MappedRecord {
        val data = PartnerJson.dropNone(
            "medication_id" to row.col("medication_id"), "schedule_id" to row.col("schedule_id"),
            "scheduled_at_ms" to row.col("scheduled_at_ms"), "status" to row.col("status"),
            "taken_at_ms" to row.col("taken_at_ms"), "dose_quantity" to row.col("dose_quantity"),
            "dose_unit" to row.col("dose_unit"), "note" to row.col("note")
        )
        return MappedRecord("dose_log", text(row.col("id")), "medicines", localDay, data)
    }

    // -- report_overview -------------------------------------------------------------------------

    private val ABNORMAL = setOf("low", "high", "critical_low", "critical_high", "abnormal")
    private val FIELD_ORDER = mapOf("user" to 0, "confirmed" to 1, "suggested" to 2)

    private fun notRejected(o: JsonObject) = PartnerJson.str(o["state"]) != "rejected"

    private fun idOf(o: JsonObject) = text(o["id"])

    private fun bestField(fields: List<JsonObject>, key: String): JsonObject? =
        fields.filter { PartnerJson.str(it["field_key"]) == key && notRejected(it) }
            .sortedWith(
                compareBy<JsonObject> { FIELD_ORDER[PartnerJson.str(it["state"])] ?: 9 }
                    .thenByDescending { if (PartnerJson.truthy(it["confidence"])) PartnerJson.double(it["confidence"]) ?: 0.0 else 0.0 }
                    .thenComparator { a, b -> PartnerJson.compareCodePoints(idOf(a), idOf(b)) }
            ).firstOrNull()

    private fun fieldValues(fields: List<JsonObject>, key: String): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        fields.filter { PartnerJson.str(it["field_key"]) == key && notRejected(it) }
            .sortedWith { a, b -> PartnerJson.compareCodePoints(idOf(a), idOf(b)) }
            .forEach { f -> val v = f["value_text"] ?: JsonNull; if (v !in out) out += v }
        return out
    }

    private fun listOrNull(l: List<JsonElement>): JsonElement? = if (l.isEmpty()) null else JsonArray(l)

    /**
     * `records` row + `record_fields`, `record_highlights`, `observations` -> report_overview. Archived records
     * are not shared; files, page text, evidence, bounding boxes and notes are never read (docs §7.6).
     */
    fun reportOverview(record: JsonObject, fields: List<JsonObject>, highlights: List<JsonObject>, observations: List<JsonObject>): MappedRecord? {
        if (PartnerJson.truthy(record["archived"])) return null
        val doctor = bestField(fields, "doctor_name")
        val specialty = bestField(fields, "doctor_specialty")
        val facility = bestField(fields, "facility")
        var reportDate: JsonElement? = record["document_date"]
        if (PartnerJson.isNone(reportDate)) {
            val rd = bestField(fields, "report_date")
            val vt = PartnerJson.str(rd?.get("value_text")) ?: ""
            reportDate = if (rd != null && PartnerJson.truthy(rd) && PartnerJson.pyFullMatch(PartnerEnvelopes.RE_DAY, vt)) rd["value_text"] else null
        }
        val live = highlights.filter { !PartnerJson.truthy(it["dismissed"]) }
            .sortedWith(
                compareBy<JsonObject> { if (PartnerJson.truthy(it["position"])) PartnerJson.double(it["position"]) ?: 0.0 else 0.0 }
                    .thenComparator { a, b -> PartnerJson.compareCodePoints(idOf(a), idOf(b)) }
            )
        val summary = live.firstOrNull { PartnerJson.str(it["section"]) == "summary" }?.get("text")
        val important = live.filter { PartnerJson.str(it["section"]) == "important" }.map { it["text"] ?: JsonNull }
        val recs = live.filter { PartnerJson.str(it["section"]) == "recommendations" }.map { it["text"] ?: JsonNull }
        val results = observations.filter(::notRejected)
            .sortedWith { a, b ->
                val c = PartnerJson.compareCodePoints(PartnerJson.str(a["raw_name"]) ?: "", PartnerJson.str(b["raw_name"]) ?: "")
                if (c != 0) c else PartnerJson.compareCodePoints(idOf(a), idOf(b))
            }
            .map { o ->
                PartnerJson.dropNone(
                    "name" to o["raw_name"], "analyte_id" to o["analyte_id"], "value" to o["value_text"],
                    "value_num" to o["value_num"], "unit" to o["unit"], "ref_low" to o["ref_low"],
                    "ref_high" to o["ref_high"], "ref_text" to o["ref_text"],
                    "flag" to (if (PartnerJson.truthy(o["flag"])) o["flag"] else JsonPrimitive("unknown")),
                    "observed_date" to o["observed_date"]
                )
            }
        val abnormal = results.filter { PartnerJson.str(it["flag"]) in ABNORMAL }
        val meds = fields.filter { PartnerJson.str(it["field_key"]) == "medication" && notRejected(it) }
            .sortedWith { a, b -> PartnerJson.compareCodePoints(idOf(a), idOf(b)) }
            .map { f ->
                val vjText = f["value_json"]
                val vj = if (PartnerJson.truthy(vjText)) (PartnerJson.parse(PartnerJson.str(vjText)!!) as? JsonObject ?: JsonObject(emptyMap())) else JsonObject(emptyMap())
                PartnerJson.dropNone(
                    "name" to (if (PartnerJson.truthy(vj["name"])) vj["name"] else f["value_text"]),
                    "strength" to vj["strength"], "dose" to vj["dose"], "frequency" to vj["frequency"],
                    "duration" to vj["duration"]
                )
            }
        val data = PartnerJson.dropNone(
            "title" to record["title"], "record_type" to record["record_type"], "category" to record["category"],
            "report_date" to reportDate,
            "doctor" to doctor?.get("value_text"),
            "doctor_specialty" to specialty?.get("value_text"),
            "facility" to facility?.get("value_text"),
            "summary" to summary, "highlights" to listOrNull(important), "results" to listOrNull(results),
            "abnormal" to listOrNull(abnormal), "diagnoses" to listOrNull(fieldValues(fields, "diagnosis")),
            "medications" to listOrNull(meds), "recommendations" to listOrNull(recs)
        )
        val day = if (PartnerJson.truthy(reportDate)) PartnerJson.str(reportDate)
        else PartnerJson.str(record["sort_date"])?.let { PartnerJson.pyTake(it, 10) }
        return MappedRecord("report_overview", text(record["id"]), "report_overviews", day, data)
    }
}
