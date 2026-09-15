package com.ayuvo.health.services.ai

import com.ayuvo.health.data.health.HealthCoachSnapshot
import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.models.HealthDataType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Coach's four health tools over a [HealthCoachSnapshot]. Names, descriptions, schemas and payload
 * shapes are byte-identical with iOS (docs/health-data.md §1.5). Advertised only when the hub is
 * enabled AND `coachHealthDataEnabled`; the snapshot is passed per message, never persisted.
 */
class CoachHealthData(
    private val snapshot: HealthCoachSnapshot,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val zone: ZoneId get() = runCatching { ZoneId.of(snapshot.zoneId) }.getOrDefault(clock.zone)

    fun dataTypes(): Map<String, Any?> = linkedMapOf(
        "health_data_enabled" to true,
        "last_sync" to snapshot.lastSyncMs?.let(::isoInstant),
        "count" to snapshot.types.size,
        "data_types" to snapshot.types.map { t ->
            linkedMapOf<String, Any?>(
                "data_type" to t.typeId,
                "category" to t.category,
                "display_name" to t.displayName,
                "unit" to t.unit,
                "aggregation" to t.aggregation,
                "count" to t.count,
                "first" to t.firstMs?.let(::isoDate),
                "last" to t.lastMs?.let(::isoDate),
                "latest" to t.latest?.let { row ->
                    linkedMapOf(
                        "at" to isoInstant(row.endMs, row.endOffsetS),
                        "value" to latestValue(t.typeId, row),
                        "value_text" to (row.valueText ?: categoryText(t.typeId, row))
                    )
                }
            ).apply {
                t.historyLimitedBeforeMs?.let { put("history_limited_before", isoDate(it)) }
            }
        }
    )

    fun summary(dataType: String?, from: String?, to: String?, limit: Int?): Map<String, Any?> {
        val type = resolveType(dataType) ?: return error("Unknown data_type '${dataType.orEmpty()}'. Call get_health_data_types for valid keys.")
        val range = parseRange(from, to)
        val cap = (limit ?: 400).coerceIn(1, 400)
        val days = snapshot.daily[type.typeId].orEmpty()
            .filter { it.day >= range.first.toString() && it.day <= range.second.toString() && it.count > 0 }
            .sortedBy { it.day }
            .takeLast(cap)
        val durationLike = type.aggregation == "DURATION" || type.kind == "session" || type.kind == "duration"
        val sums = days.mapNotNull { if (durationLike) it.durationS ?: it.sum else it.sum }
        val avgs = days.mapNotNull { it.avg }
        val mins = days.mapNotNull { it.min }
        val maxs = days.mapNotNull { it.max }
        val latestRollup = days.lastOrNull()
        val highlights = linkedMapOf<String, Any?>(
            "total" to sums.takeIf { it.isNotEmpty() && (type.aggregation == "SUM" || durationLike || type.aggregation == "COUNT") }?.sum()?.let(::round2),
            "average" to when {
                type.aggregation == "SUM" || durationLike -> sums.takeIf { it.isNotEmpty() }?.average()?.let(::round2)
                else -> avgs.takeIf { it.isNotEmpty() }?.let { a -> a.zip(days.map { it.count }).sumOf { (v, c) -> v * c } / days.sumOf { it.count }.coerceAtLeast(1) }?.let(::round2)
            },
            "min" to mins.minOrNull()?.let(::round2),
            "max" to maxs.maxOrNull()?.let(::round2),
            "latest" to (latestRollup?.lastValue ?: latestRollup?.sum)?.let(::round2)
        )
        return linkedMapOf(
            "data_type" to type.typeId,
            "unit" to type.unit,
            "from" to range.first.toString(),
            "to" to range.second.toString(),
            "highlights" to highlights,
            "days" to days.map { d -> dayPayload(d, durationLike) }
        )
    }

    fun samples(dataType: String?, from: String?, to: String?, limit: Int?): Map<String, Any?> {
        val type = resolveType(dataType) ?: return error("Unknown data_type '${dataType.orEmpty()}'. Call get_health_data_types for valid keys.")
        val range = parseRange(from, to)
        val cap = (limit ?: 200).coerceIn(1, 200)
        val fromMs = range.first.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = range.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = snapshot.samples[type.typeId].orEmpty()
            .filter { it.endMs >= fromMs && it.startMs < toMs && !it.deleted }
            .sortedWith(compareBy({ it.startMs }, { it.id }))
            .takeLast(cap)
        return linkedMapOf(
            "data_type" to type.typeId,
            "unit" to type.unit,
            "count" to rows.size,
            "records" to rows.map { r ->
                linkedMapOf<String, Any?>(
                    "start" to isoInstant(r.startMs, r.startOffsetS),
                    "end" to isoInstant(r.endMs, r.endOffsetS),
                    "value" to r.value?.let(::round2),
                    "value2" to r.value2?.let(::round2),
                    "value3" to r.value3?.let(::round2),
                    "value_text" to (r.valueText ?: categoryText(type.typeId, r)),
                    "category_value" to r.categoryValue,
                    "title" to r.title,
                    "extra" to HealthRollupMath.parseExtra(r.extraJson)?.let(::plain),
                    "source" to r.sourceId,
                    "device" to r.device
                )
            }
        )
    }

    fun sleepHistory(from: String?, to: String?, limit: Int?): Map<String, Any?> {
        val range = parseRange(from, to)
        val cap = (limit ?: 120).coerceIn(1, 120)
        val nights = snapshot.nights
            .filter { it.nightOf >= range.first.toString() && it.nightOf <= range.second.toString() }
            .sortedBy { it.nightOf }
            .takeLast(cap)
        return linkedMapOf(
            "from" to range.first.toString(),
            "to" to range.second.toString(),
            "count" to nights.size,
            "nights" to nights.map { n ->
                linkedMapOf(
                    "night_of" to n.nightOf,
                    "start" to isoInstant(n.startMs),
                    "end" to isoInstant(n.endMs),
                    "in_bed_s" to n.inBedS.toLong(),
                    "asleep_s" to n.asleepS.toLong(),
                    "light_s" to n.lightS.toLong(),
                    "deep_s" to n.deepS.toLong(),
                    "rem_s" to n.remS.toLong(),
                    "awake_s" to n.awakeS.toLong(),
                    "source" to n.sourceId
                )
            }
        )
    }

    /** ≤12 lines for on-device / no-tool providers: a 7-day digest of the headline types. */
    fun promptSummary(): List<String> {
        val today = LocalDate.now(clock.withZone(zone))
        val since = today.minusDays(6).toString()
        fun recent(id: String) = snapshot.daily[id].orEmpty().filter { it.day >= since && it.count > 0 }
        val lines = mutableListOf<String>()
        lines += "## Health data (last 7 days, ${snapshot.platform})"
        recent("steps").takeIf { it.isNotEmpty() }?.let { lines += "- Steps: avg ${fmt(it.mapNotNull { d -> d.sum }.average())}/day" }
        recent("active_energy").takeIf { it.isNotEmpty() }?.let { d ->
            val own = d.mapNotNull { it.ownSum }.sum()
            lines += "- Active energy: avg ${fmt(d.mapNotNull { it.sum }.average())} kcal/day" + (if (own > 0) " (includes ${fmt(own)} kcal of Ayuvo's own workout estimates over the week)" else "")
        }
        recent("resting_heart_rate").takeIf { it.isNotEmpty() }?.let { lines += "- Resting heart rate: avg ${fmt(it.mapNotNull { d -> d.avg }.average())} bpm" }
        recent("heart_rate").takeIf { it.isNotEmpty() }?.let { lines += "- Heart rate: ${fmt(it.mapNotNull { d -> d.min }.min())}–${fmt(it.mapNotNull { d -> d.max }.max())} bpm" }
        recent("hrv_rmssd").takeIf { it.isNotEmpty() }?.let { lines += "- HRV (RMSSD): avg ${fmt(it.mapNotNull { d -> d.avg }.average())} ms" }
        snapshot.nights.filter { it.nightOf >= since }.takeIf { it.isNotEmpty() }?.let { lines += "- Sleep: avg ${(it.map { n -> n.asleepS }.average() / 3600).let { h -> String.format(Locale.US, "%.1f", h) }} h asleep over ${it.size} nights" }
        recent("blood_oxygen").takeIf { it.isNotEmpty() }?.let { lines += "- Blood oxygen: avg ${fmt(it.mapNotNull { d -> d.avg }.average())} %" }
        recent("respiratory_rate").takeIf { it.isNotEmpty() }?.let { lines += "- Respiratory rate: avg ${fmt(it.mapNotNull { d -> d.avg }.average())} /min" }
        snapshot.types.firstOrNull { it.typeId == "weight" }?.latest?.let { lines += "- Weight (platform): ${fmt(it.value ?: 0.0)} kg on ${isoDate(it.endMs)}" }
        snapshot.types.firstOrNull { it.typeId == "blood_pressure" }?.latest?.let { lines += "- Blood pressure (latest): ${fmt(it.value ?: 0.0)}/${fmt(it.value2 ?: 0.0)} mmHg on ${isoDate(it.endMs)}" }
        recent("blood_glucose").takeIf { it.isNotEmpty() }?.let { lines += "- Blood glucose: avg ${String.format(Locale.US, "%.1f", it.mapNotNull { d -> d.avg }.average())} mmol/L" }
        return lines.take(12)
    }

    // -- helpers ------------------------------------------------------------------

    private fun resolveType(dataType: String?) = dataType?.trim()?.takeIf { it.isNotEmpty() }?.let { id -> snapshot.types.firstOrNull { it.typeId == id } }

    private fun dayPayload(d: HealthDailyRollup, durationLike: Boolean): Map<String, Any?> = linkedMapOf<String, Any?>(
        "date" to d.day,
        "sum" to (if (durationLike) d.durationS ?: d.sum else d.sum)?.let(::round2),
        "avg" to d.avg?.let(::round2),
        "min" to d.min?.let(::round2),
        "max" to d.max?.let(::round2),
        "count" to d.count
    ).apply {
        d.durationS?.let { put("duration_s", round2(it)) }
        d.v2Avg?.let { put("v2_avg", round2(it)) }
        d.v2Min?.let { put("v2_min", round2(it)) }
        d.v2Max?.let { put("v2_max", round2(it)) }
        d.ownSum?.let { put("own_sum", round2(it)) }
    }

    private fun latestValue(typeId: String, row: HealthSampleRow): Any? = when {
        typeId == HealthDataType.BLOOD_PRESSURE.id -> row.value?.let { s -> "${round2(s)}/${row.value2?.let(::round2) ?: "-"}" }
        HealthDataType.byId(typeId)?.kind?.id == "category" -> row.categoryValue
        else -> row.value?.let(::round2)
    }

    private fun categoryText(typeId: String, row: HealthSampleRow): String? {
        val code = row.categoryValue ?: return null
        val type = HealthDataType.byId(typeId) ?: return null
        return type.categoryCodes[code]
    }

    private fun parseRange(from: String?, to: String?): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(clock.withZone(zone))
        val toDate = parseDate(to) ?: today
        val fromDate = parseDate(from) ?: toDate.minusDays(30)
        return if (fromDate.isAfter(toDate)) toDate to fromDate else fromDate to toDate
    }

    private fun parseDate(value: String?): LocalDate? = value?.let {
        runCatching { LocalDate.parse(it.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    private fun isoDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    private fun isoInstant(ms: Long, offsetS: Int? = null): String {
        val instant = Instant.ofEpochMilli(ms)
        val offset = offsetS?.let { ZoneOffset.ofTotalSeconds(it) } ?: zone.rules.getOffset(instant)
        return instant.atOffset(offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun fmt(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    private fun error(message: String): Map<String, Any?> = linkedMapOf("error" to message)

    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull ?: element.content
        }
        is JsonObject -> element.entries.associate { (k, v) -> k to plain(v) }
        is JsonArray -> element.map(::plain)
    }

    companion object {
        val TOOL_NAMES: List<String> = listOf(
            "get_health_data_types",
            "get_health_summary",
            "get_health_samples",
            "get_sleep_history"
        )

        val TOOL_DESCRIPTIONS: Map<String, String> = mapOf(
            "get_health_data_types" to "List the health data types synced from the phone's health platform (steps, heart rate, sleep, blood pressure, ...) with record counts, units, and earliest/latest dates. Call this first before any other health tool, and to learn the exact data_type keys.",
            "get_health_summary" to "Daily statistics for one health data type between two dates (inclusive): per-day sum, average, min, max and record count in the type's unit, plus range highlights. Use for questions like \"how did I sleep this week?\", \"average resting heart rate in March\", or step trends. data_type must be a key returned by get_health_data_types.",
            "get_health_samples" to "Individual health records (start/end time, value, source app and device) for one data type between two dates (inclusive). Use only when per-reading detail matters, e.g. a specific blood-pressure reading or a workout. Prefer get_health_summary for trends.",
            "get_sleep_history" to "Per-night sleep sessions between two dates (inclusive): bedtime, wake time, time in bed, time asleep and light/deep/REM/awake durations in seconds, with the source device. Use for questions about sleep duration, quality or consistency."
        )

        fun parameterSchemaFor(toolName: String): Map<String, Any> = when (toolName) {
            "get_health_data_types" -> linkedMapOf("type" to "object", "properties" to emptyMap<String, Any>())
            "get_health_summary" -> rangeSchema(includeDataType = true, limitMax = 400)
            "get_health_samples" -> rangeSchema(includeDataType = true, limitMax = 200)
            "get_sleep_history" -> rangeSchema(includeDataType = false, limitMax = 120)
            else -> linkedMapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }

        private fun rangeSchema(includeDataType: Boolean, limitMax: Int): Map<String, Any> {
            val properties = linkedMapOf<String, Any>()
            if (includeDataType) {
                properties["data_type"] = linkedMapOf("type" to "string", "description" to "A data_type key returned by get_health_data_types, e.g. steps, heart_rate, sleep")
            }
            properties["from"] = linkedMapOf("type" to "string", "description" to "ISO date yyyy-MM-dd, inclusive start")
            properties["to"] = linkedMapOf("type" to "string", "description" to "ISO date yyyy-MM-dd, inclusive end")
            properties["limit"] = linkedMapOf("type" to "integer", "description" to "Optional max entries to return (cap $limitMax)")
            return linkedMapOf(
                "type" to "object",
                "properties" to properties,
                "required" to if (includeDataType) listOf("data_type", "from", "to") else listOf("from", "to")
            )
        }
    }
}
