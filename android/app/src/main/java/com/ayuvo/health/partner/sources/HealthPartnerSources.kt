package com.ayuvo.health.partner.sources

import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerMappers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only sources over `ayuvo_health.db` (docs/partner-sync.md §7.1–§7.2). Every query names its columns, is
 * restricted to the shareable type allow-list and is keyset-paged ([PAGE] rows at a time).
 */
internal object HealthSql {
    const val PAGE = 1000

    fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

    /** `health_type_meta.unit` per type, falling back to the registry unit. */
    fun units(db: SQLiteDatabase): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery("SELECT type_id, unit FROM health_type_meta", null).use { c ->
            while (c.moveToNext()) if (!c.isNull(1)) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    fun unitOf(units: Map<String, String>, typeId: String): String? =
        units[typeId]?.takeIf { it.isNotBlank() } ?: HealthDataType.byId(typeId)?.unit

    fun withUnit(row: JsonObject, unit: String?): JsonObject =
        JsonObject(LinkedHashMap(row).apply { put("unit", unit?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) })

    /** Splits `<a>:<b>` ids at the last ':' (type ids never contain ':'; days never do either). */
    fun splitLast(id: String): Pair<String, String>? {
        val i = id.lastIndexOf(':')
        return if (i <= 0 || i == id.length - 1) null else id.substring(0, i) to id.substring(i + 1)
    }
}

/** `metric_day` from `health_daily_rollups` (all history; window scope after the first full run). */
class MetricDaySource(private val db: () -> SQLiteDatabase) : PartnerSource {
    override val type = "metric_day"
    private val cols = listOf("type_id", "day", "sum", "avg", "min", "max", "count", "last_value", "last_at_ms", "v2_avg", "v2_min", "v2_max", "duration_s")
    private val select = "SELECT ${cols.joinToString(", ")} FROM health_daily_rollups"

    private fun map(row: JsonObject, units: Map<String, String>): SourceRecord? {
        // A rollup without any value carries no information; it is not shared.
        if (listOf("sum", "avg", "min", "max", "last_value").all { PartnerJson.isNone(row[it]) }) return null
        val typeId = PartnerJson.str(row["type_id"]) ?: return null
        return PartnerMappers.rollup(HealthSql.withUnit(row, HealthSql.unitOf(units, typeId)))?.let(SourceRecord::of)
    }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val d = db()
        val units = HealthSql.units(d)
        val types = PartnerCatalog.current.healthTypeCategory.keys.toList()
        var lastType = ""
        var lastDay = ""
        while (true) {
            val args = ArrayList<String>(types)
            var sql = "$select WHERE type_id IN (${HealthSql.placeholders(types.size)}) AND (type_id > ? OR (type_id = ? AND day > ?))"
            args += listOf(lastType, lastType, lastDay)
            if (dayFrom != null) { sql += " AND day >= ?"; args += dayFrom }
            sql += " ORDER BY type_id, day LIMIT ${HealthSql.PAGE}"
            var n = 0
            d.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    n++
                    val row = CursorJson.row(c, cols)
                    lastType = c.getString(0); lastDay = c.getString(1)
                    map(row, units)?.let(onRecord)
                }
            }
            if (n < HealthSql.PAGE) break
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val d = db()
        val units = HealthSql.units(d)
        val out = HashMap<String, SourceRecord>()
        for (id in ids) {
            val (typeId, day) = HealthSql.splitLast(id) ?: continue
            d.rawQuery("$select WHERE type_id = ? AND day = ?", arrayOf(typeId, day)).use { c ->
                if (c.moveToFirst()) map(CursorJson.row(c, cols), units)?.takeIf { it.recordId == id }?.let { out[id] = it }
            }
        }
        return out
    }
}

/** `metric_hour` from `health_hourly_rollups` for the hourly types, last 7 days. */
class MetricHourSource(private val db: () -> SQLiteDatabase, private val zone: () -> ZoneId) : PartnerSource {
    override val type = "metric_hour"
    private val cols = listOf("type_id", "day", "hour", "sum", "avg", "min", "max", "count")
    private val select = "SELECT ${cols.joinToString(", ")} FROM health_hourly_rollups"

    private fun map(row: JsonObject, units: Map<String, String>): SourceRecord? {
        val typeId = PartnerJson.str(row["type_id"]) ?: return null
        val day = PartnerJson.str(row["day"]) ?: return null
        val hour = PartnerJson.long(row["hour"])?.toInt() ?: return null
        if (hour !in 0..23) return null
        val start = runCatching { LocalDate.parse(day).atTime(hour, 0).atZone(zone()).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val m = LinkedHashMap(HealthSql.withUnit(row, HealthSql.unitOf(units, typeId)))
        m["hour_start_ms"] = JsonPrimitive(start)
        return PartnerMappers.hourly(JsonObject(m))?.let(SourceRecord::of)
    }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val d = db()
        val units = HealthSql.units(d)
        val types = PartnerCatalog.current.hourlyTypes.toList()
        var key = Triple("", "", -1L)
        while (true) {
            val args = ArrayList<String>(types)
            var sql = "$select WHERE type_id IN (${HealthSql.placeholders(types.size)}) AND " +
                "(type_id > ? OR (type_id = ? AND (day > ? OR (day = ? AND hour > ?))))"
            args += listOf(key.first, key.first, key.second, key.second, key.third.toString())
            if (dayFrom != null) { sql += " AND day >= ?"; args += dayFrom }
            sql += " ORDER BY type_id, day, hour LIMIT ${HealthSql.PAGE}"
            var n = 0
            d.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    n++
                    key = Triple(c.getString(0), c.getString(1), c.getLong(2))
                    map(CursorJson.row(c, cols), units)?.let(onRecord)
                }
            }
            if (n < HealthSql.PAGE) break
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val d = db()
        val units = HealthSql.units(d)
        val out = HashMap<String, SourceRecord>()
        for (id in ids) {
            val parts = id.split(':')
            if (parts.size != 3) continue
            d.rawQuery("$select WHERE type_id = ? AND day = ? AND hour = ?", arrayOf(parts[0], parts[1], parts[2])).use { c ->
                if (c.moveToFirst()) map(CursorJson.row(c, cols), units)?.takeIf { it.recordId == id }?.let { out[id] = it }
            }
        }
        return out
    }
}

/** `sample` from `health_samples` (intraday types, not deleted, last 7 days), with the source's display name. */
class SampleSource(private val db: () -> SQLiteDatabase, private val now: () -> Long) : PartnerSource {
    override val type = "sample"
    private val cols = listOf("id", "type_id", "start_ms", "end_ms", "local_day", "unit", "value", "value2", "value3", "category_value", "deleted", "source_name")
    private val select = "SELECT s.id, s.type_id, s.start_ms, s.end_ms, s.local_day, s.unit, s.value, s.value2, s.value3, s.category_value, s.deleted, " +
        "src.name FROM health_samples s LEFT JOIN health_sources src ON src.id = s.source_id"

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val d = db()
        val nowMs = now()
        val types = PartnerCatalog.current.intradayTypes.toList()
        val fromMs = nowMs - PartnerCatalog.current.intradayMs
        var lastStart = Long.MIN_VALUE
        var lastId = ""
        while (true) {
            val args = ArrayList<String>(types)
            var sql = "$select WHERE s.type_id IN (${HealthSql.placeholders(types.size)}) AND s.deleted = 0 AND s.start_ms >= ? " +
                "AND (s.start_ms > ? OR (s.start_ms = ? AND s.id > ?))"
            args += listOf(fromMs.toString(), lastStart.toString(), lastStart.toString(), lastId)
            if (dayFrom != null) { sql += " AND s.local_day >= ?"; args += dayFrom }
            sql += " ORDER BY s.start_ms, s.id LIMIT ${HealthSql.PAGE}"
            var n = 0
            d.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    n++
                    lastStart = c.getLong(2); lastId = c.getString(0)
                    PartnerMappers.sample(CursorJson.row(c, cols), nowMs)?.let { onRecord(SourceRecord.of(it)) }
                }
            }
            if (n < HealthSql.PAGE) break
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val d = db()
        val nowMs = now()
        val out = HashMap<String, SourceRecord>()
        for (chunk in ids.distinct().chunked(200)) {
            d.rawQuery("$select WHERE s.id IN (${HealthSql.placeholders(chunk.size)})", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) PartnerMappers.sample(CursorJson.row(c, cols), nowMs)?.let { out[it.id] = SourceRecord.of(it) }
            }
        }
        return out
    }
}

/**
 * `derived_day` from `derived_daily_values`. [allowed] filters out metrics derived from never-shared types
 * (mobility, hearing).
 */
class DerivedDaySource(private val db: () -> SQLiteDatabase, private val allowed: (String) -> Boolean) : PartnerSource {
    override val type = "derived_day"
    private val select = "SELECT metric_id, day, value, value2, value3, quality, algo_version FROM derived_daily_values"

    private fun map(c: android.database.Cursor): SourceRecord? {
        val metric = c.getString(0)
        if (!allowed(metric)) return null
        val data = PartnerJson.dropNone(
            "metric_id" to JsonPrimitive(metric), "day" to JsonPrimitive(c.getString(1)),
            "value" to CursorJson.value(c, 2), "value2" to CursorJson.value(c, 3), "value3" to CursorJson.value(c, 4),
            "quality" to CursorJson.value(c, 5), "algo_version" to CursorJson.value(c, 6)
        )
        if (listOf("value", "value2", "value3").all { PartnerJson.isNone(data[it]) }) return null
        return SourceRecord(type, "$metric:${c.getString(1)}", "vitals", c.getString(1), data)
    }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val d = db()
        var lastMetric = ""
        var lastDay = ""
        while (true) {
            val args = arrayListOf(lastMetric, lastMetric, lastDay)
            var sql = "$select WHERE (metric_id > ? OR (metric_id = ? AND day > ?))"
            if (dayFrom != null) { sql += " AND day >= ?"; args += dayFrom }
            sql += " ORDER BY metric_id, day LIMIT ${HealthSql.PAGE}"
            var n = 0
            d.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    n++
                    lastMetric = c.getString(0); lastDay = c.getString(1)
                    map(c)?.let(onRecord)
                }
            }
            if (n < HealthSql.PAGE) break
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val d = db()
        val out = HashMap<String, SourceRecord>()
        for (id in ids) {
            val (metric, day) = HealthSql.splitLast(id) ?: continue
            d.rawQuery("$select WHERE metric_id = ? AND day = ?", arrayOf(metric, day)).use { c ->
                if (c.moveToFirst()) map(c)?.let { out[id] = it }
            }
        }
        return out
    }
}

/** `analytics_day`: single-day `analytics_results` of the allow-listed metrics, newest algorithm version per day. */
class AnalyticsDaySource(private val db: () -> SQLiteDatabase) : PartnerSource {
    override val type = "analytics_day"
    private val cols = listOf("metric_id", "period_start", "period_end", "status", "classification", "value", "value2", "value3", "unit", "confidence", "coverage", "algorithm_version")
    private val select = "SELECT ${cols.joinToString(", ")} FROM analytics_results"

    private fun scan(sql: String, args: Array<String>, onRecord: (SourceRecord) -> Unit): Int {
        var n = 0
        var lastKey: String? = null
        db().rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                n++
                val key = c.getString(0) + ":" + c.getString(1)
                if (key == lastKey) continue // rows come newest algorithm version first
                lastKey = key
                PartnerMappers.analytics(CursorJson.row(c, cols))?.let { onRecord(SourceRecord.of(it)) }
            }
        }
        return n
    }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        for (metric in PartnerCatalog.current.analyticsMetrics.sorted()) {
            var lastDay = ""
            while (true) {
                // Page by distinct days so every version of one day is in the same query (newest first).
                val days = ArrayList<String>()
                val dayArgs = arrayListOf(metric, lastDay)
                var daySql = "SELECT DISTINCT period_start FROM analytics_results WHERE metric_id = ? AND period_start = period_end AND period_start > ?"
                if (dayFrom != null) { daySql += " AND period_start >= ?"; dayArgs += dayFrom }
                daySql += " ORDER BY period_start LIMIT ${HealthSql.PAGE}"
                db().rawQuery(daySql, dayArgs.toTypedArray()).use { c -> while (c.moveToNext()) days += c.getString(0) }
                if (days.isEmpty()) break
                scan(
                    "$select WHERE metric_id = ? AND period_start = period_end AND period_start >= ? AND period_start <= ? " +
                        "ORDER BY period_start, algorithm_version DESC",
                    arrayOf(metric, days.first(), days.last()), onRecord
                )
                if (days.size < HealthSql.PAGE) break
                lastDay = days.last()
            }
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val out = HashMap<String, SourceRecord>()
        for (id in ids) {
            val (metric, day) = HealthSql.splitLast(id) ?: continue
            scan("$select WHERE metric_id = ? AND period_start = ? AND period_end = ? ORDER BY algorithm_version DESC LIMIT 1", arrayOf(metric, day, day)) {
                out[id] = it
            }
        }
        return out
    }
}
