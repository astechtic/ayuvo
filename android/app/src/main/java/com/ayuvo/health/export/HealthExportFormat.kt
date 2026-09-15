package com.ayuvo.health.export

import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesPoint
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthDayAttribution
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `ayuvo-health-data` v1 — the platform-neutral zip both apps read and write
 * (docs/health-data-export.md). Entry order lets a single-pass reader preview:
 * manifest.json → samples.ndjson → series.ndjson (optional) → sources.json → rollups.ndjson
 * (optional) → checksums.json. kotlinx.serialization (existing `$$serializer` keep rules).
 */
object HealthExportFormat {
    const val FORMAT = "ayuvo-health-data"
    const val VERSION = 1
    const val PERCENT_CONVENTION = "0-100"
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val MAX_LINE_BYTES = 64 * 1024

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SAMPLES = "samples.ndjson"
    const val ENTRY_SERIES = "series.ndjson"
    const val ENTRY_SOURCES = "sources.json"
    const val ENTRY_ROLLUPS = "rollups.ndjson"
    const val ENTRY_CHECKSUMS = "checksums.json"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }

    val checksumsSerializer = MapSerializer(String.serializer(), String.serializer())

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** `start_ms + start_offset_s` → ISO-8601 with offset and milliseconds. */
    fun isoWithOffset(ms: Long, offsetS: Int?, zone: ZoneId): String {
        val instant = Instant.ofEpochMilli(ms)
        val offset = offsetS?.let { ZoneOffset.ofTotalSeconds(it) } ?: zone.rules.getOffset(instant)
        return instant.atOffset(offset).format(isoFormatter)
    }

    /** ISO-8601 with offset → epoch ms + offset seconds. Throws on malformed input. */
    fun parseIso(value: String): Pair<Long, Int> {
        val odt = OffsetDateTime.parse(value.trim(), isoFormatter)
        return odt.toInstant().toEpochMilli() to odt.offset.totalSeconds
    }

    fun sampleFromRow(row: HealthSampleRow, sourceName: String?, zone: ZoneId): HealthExportSample = HealthExportSample(
        id = row.id,
        type_id = row.typeId,
        start = isoWithOffset(row.startMs, row.startOffsetS, zone),
        end = isoWithOffset(row.endMs, row.endOffsetS, zone),
        updated = isoWithOffset(row.updatedMs, null, ZoneOffset.UTC),
        value = row.value,
        value2 = row.value2,
        value3 = row.value3,
        value_text = row.valueText,
        unit = row.unit,
        category_value = row.categoryValue,
        title = row.title,
        extra = row.extraJson?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        count = row.count,
        source_id = row.sourceId,
        source_name = sourceName,
        device = row.device,
        device_type = row.deviceType,
        recording_method = row.recordingMethod,
        client_record_id = row.clientRecordId,
        origin = row.origin
    )

    /** Import: `local_day` is recomputed from start/end + the type's day attribution. */
    fun rowFromSample(sample: HealthExportSample, zone: ZoneId, origin: Int = HealthSampleRow.ORIGIN_IMPORT): HealthSampleRow {
        val (startMs, startOff) = parseIso(sample.start)
        val (endMs, endOff) = parseIso(sample.end)
        val updatedMs = runCatching { parseIso(sample.updated).first }.getOrDefault(endMs)
        val attribution = HealthDataType.byId(sample.type_id)?.dayAttribution ?: HealthDayAttribution.START
        return HealthSampleRow(
            id = sample.id,
            typeId = sample.type_id,
            startMs = startMs,
            endMs = endMs,
            startOffsetS = startOff,
            endOffsetS = endOff,
            localDay = HealthDayKeys.localDay(attribution, startMs, endMs, startOff, endOff, zone),
            value = sample.value,
            value2 = sample.value2,
            value3 = sample.value3,
            valueText = sample.value_text,
            unit = sample.unit,
            categoryValue = sample.category_value,
            title = sample.title,
            extraJson = sample.extra?.toString(),
            count = sample.count.coerceAtLeast(1),
            sourceId = sample.source_id,
            device = sample.device,
            deviceType = sample.device_type,
            recordingMethod = sample.recording_method,
            clientRecordId = sample.client_record_id,
            origin = origin,
            updatedMs = updatedMs
        )
    }

    fun seriesFromPoint(point: HealthSeriesPoint) = HealthExportSeriesPoint(s = point.sampleId, t = point.tMs, v = point.value)

    fun sourceFromRow(row: HealthSourceRow) = HealthExportSource(row.id, row.name, row.deviceModel, row.deviceType, row.lastSeenMs)

    fun encodeSample(sample: HealthExportSample): String = json.encodeToString(HealthExportSample.serializer(), sample)
    fun decodeSample(line: String): HealthExportSample = json.decodeFromString(HealthExportSample.serializer(), line)
    fun encodeSeries(point: HealthExportSeriesPoint): String = json.encodeToString(HealthExportSeriesPoint.serializer(), point)
    fun decodeSeries(line: String): HealthExportSeriesPoint = json.decodeFromString(HealthExportSeriesPoint.serializer(), line)
}

@Serializable
data class HealthExportDateRange(val start: String, val end: String)

/** `record_count` / `series_count` are always written: iOS decodes them as required integers. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthExportTypeInfo(
    val type: String,
    val category: String,
    val kind: String,
    val aggregation: String,
    val unit: String,
    val display_name: String? = null,
    val native_id: String? = null,
    @EncodeDefault val record_count: Long = 0,
    @EncodeDefault val series_count: Long = 0
)

/**
 * The manifest always carries every contract field (iOS decodes `registry_version`,
 * `percent_convention` and `types` as required), even though the JSON config skips defaults
 * for the per-row entries.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthExportManifest(
    val format: String,
    val format_version: Int,
    val platform: String,
    val app_version: String,
    val exported_at: String,
    val zone_id: String,
    val date_range: HealthExportDateRange? = null,
    @EncodeDefault val registry_version: Int = HealthDataType.REGISTRY_VERSION,
    @EncodeDefault val percent_convention: String = HealthExportFormat.PERCENT_CONVENTION,
    @EncodeDefault val types: List<HealthExportTypeInfo> = emptyList()
)

@Serializable
data class HealthExportSample(
    val id: String,
    val type_id: String,
    val start: String,
    val end: String,
    val updated: String,
    val value: Double? = null,
    val value2: Double? = null,
    val value3: Double? = null,
    val value_text: String? = null,
    val unit: String,
    val category_value: Int? = null,
    val title: String? = null,
    val extra: JsonObject? = null,
    val count: Int = 1,
    val source_id: String,
    val source_name: String? = null,
    val device: String? = null,
    val device_type: Int? = null,
    val recording_method: Int? = null,
    val client_record_id: String? = null,
    val origin: Int = 0
)

@Serializable
data class HealthExportSeriesPoint(val s: String, val t: Long, val v: Double)

@Serializable
data class HealthExportSource(
    val id: String,
    val name: String,
    val device_model: String? = null,
    val device_type: Int? = null,
    val last_seen_ms: Long? = null
)
