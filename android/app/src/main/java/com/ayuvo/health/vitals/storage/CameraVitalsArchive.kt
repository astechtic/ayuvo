package com.ayuvo.health.vitals.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Section `camera_vitals` of Export All Data (docs/camera-vitals.md §7.2): four NDJSON entries, one JSON object per
 * line. Field names are the column names, except that the `*_json` columns are embedded objects (`camera`, `quality`,
 * `results`, `reference`; calibrations `reference`, `features`; profiles `capability`) and times are ISO-8601 UTC with
 * milliseconds (`start`, `end`, `updated`; calibrations `t`, `updated`). Signals keep their raw-deflate float32 LE blob
 * unchanged as `data_base64`. Deleted scans and calibrations are never written.
 *
 * Pure JVM, so the codec and the merge plan are unit-tested; [VitalScanRepository.importArchive] applies it.
 */
object CameraVitalsArchive {
    const val SECTION = "camera_vitals"
    const val FORMAT = "ayuvo-camera-vitals"
    const val FORMAT_VERSION = 1
    const val DIR = "camera-vitals"
    const val SCANS = "$DIR/scans.ndjson"
    const val SIGNALS = "$DIR/signals.ndjson"
    const val CALIBRATIONS = "$DIR/calibrations.ndjson"
    const val DEVICE_PROFILES = "$DIR/device_profiles.ndjson"
    val ENTRIES = listOf(SCANS, SIGNALS, CALIBRATIONS, DEVICE_PROFILES)

    /** Manifest counts, one per entry. */
    const val COUNT_SCANS = "scans"
    const val COUNT_SIGNALS = "signals"
    const val COUNT_CALIBRATIONS = "calibrations"
    const val COUNT_DEVICE_PROFILES = "device_profiles"

    /** A scan with the signal rows that travel with it. */
    class ScanWithSignals(val record: VitalScanRecord, val signals: List<VitalSignal>)

    /** Everything one archive holds; signals are grouped under their scan. */
    class Bundle(
        val scans: List<ScanWithSignals>,
        val calibrations: List<VitalCalibration>,
        val deviceProfiles: List<VitalDeviceProfile>,
        /** Lines that could not be read (bad JSON, missing fields, signals of an unknown scan). */
        val skippedLines: Int = 0
    ) {
        val signalCount: Int get() = scans.sumOf { it.signals.size }
        val isEmpty: Boolean get() = scans.isEmpty() && calibrations.isEmpty() && deviceProfiles.isEmpty()
    }

    /** What an import did: rows inserted (scans, signals, calibrations) and device profiles written. */
    data class ImportResult(val scans: Int, val signals: Int, val calibrations: Int, val deviceProfiles: Int, val skippedScans: Int, val skippedCalibrations: Int) {
        val total: Long get() = (scans + calibrations).toLong()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))

    /** ISO-8601 with `Z` or an offset, with or without fractions. */
    fun parseIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    // -- Encoding ---------------------------------------------------------------------------------

    private fun parsed(text: String?): JsonElement =
        if (text == null) JsonNull else runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }

    private fun line(fields: Map<String, JsonElement>): String = JsonObject(fields.toSortedMap()).toString()

    fun encodeScan(r: VitalScanRecord): String = line(
        mapOf(
            "id" to JsonPrimitive(r.id),
            "mode" to JsonPrimitive(r.mode),
            "session_id" to (r.sessionId?.let(::JsonPrimitive) ?: JsonNull),
            "start" to JsonPrimitive(iso(r.startMs)),
            "end" to JsonPrimitive(iso(r.endMs)),
            "tz_offset_s" to JsonPrimitive(r.tzOffsetS),
            "local_day" to JsonPrimitive(r.localDay),
            "duration_ms" to JsonPrimitive(r.durationMs),
            "platform" to JsonPrimitive(r.platform),
            "device_model" to JsonPrimitive(r.deviceModel),
            "camera" to parsed(r.cameraJson),
            "context" to JsonPrimitive(r.context),
            "quality_score" to (r.qualityScore?.let(::JsonPrimitive) ?: JsonNull),
            "reject_reason" to (r.rejectReason?.let(::JsonPrimitive) ?: JsonNull),
            "quality" to parsed(r.qualityJson),
            "results" to parsed(r.resultsJson),
            "algo_version" to JsonPrimitive(r.algoVersion),
            "reference" to parsed(r.referenceJson),
            "updated" to JsonPrimitive(iso(r.updatedMs))
        )
    )

    fun encodeSignal(scanId: String, s: VitalSignal): String = line(
        mapOf(
            "scan_id" to JsonPrimitive(scanId),
            "kind" to JsonPrimitive(s.kind),
            "sample_rate" to (s.sampleRate?.let(::JsonPrimitive) ?: JsonNull),
            "encoding" to JsonPrimitive(s.encoding),
            "meta" to parsed(s.metaJson),
            "data_base64" to JsonPrimitive(Base64.getEncoder().encodeToString(s.data))
        )
    )

    fun encodeCalibration(c: VitalCalibration): String = line(
        mapOf(
            "id" to JsonPrimitive(c.id),
            "kind" to JsonPrimitive(c.kind),
            "device_model" to JsonPrimitive(c.deviceModel),
            "scan_id" to (c.scanId?.let(::JsonPrimitive) ?: JsonNull),
            "t" to JsonPrimitive(iso(c.tMs)),
            "reference" to parsed(c.referenceJson),
            "features" to parsed(c.featuresJson),
            "updated" to JsonPrimitive(iso(c.updatedMs))
        )
    )

    fun encodeDeviceProfile(p: VitalDeviceProfile): String = line(
        mapOf(
            "device_model" to JsonPrimitive(p.deviceModel),
            "camera_position" to JsonPrimitive(p.cameraPosition),
            "capability" to parsed(p.capabilityJson),
            "updated" to JsonPrimitive(iso(p.updatedMs))
        )
    )

    /** NDJSON text (one line per row, trailing newline; empty for no rows). */
    fun ndjson(lines: List<String>): String = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")

    /** Entry path -> (NDJSON text, manifest count key, row count), in [ENTRIES] order. */
    fun encode(bundle: Bundle): List<Pair<String, Triple<String, String, Int>>> {
        val signalLines = bundle.scans.flatMap { s -> s.signals.map { encodeSignal(s.record.id, it) } }
        return listOf(
            SCANS to Triple(ndjson(bundle.scans.map { encodeScan(it.record) }), COUNT_SCANS, bundle.scans.size),
            SIGNALS to Triple(ndjson(signalLines), COUNT_SIGNALS, signalLines.size),
            CALIBRATIONS to Triple(ndjson(bundle.calibrations.map(::encodeCalibration)), COUNT_CALIBRATIONS, bundle.calibrations.size),
            DEVICE_PROFILES to Triple(ndjson(bundle.deviceProfiles.map(::encodeDeviceProfile)), COUNT_DEVICE_PROFILES, bundle.deviceProfiles.size)
        )
    }

    // -- Decoding ---------------------------------------------------------------------------------

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }
        ?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    /** An embedded object back to column text; null / absent stays null. */
    private fun JsonObject.text(k: String): String? = this[k]?.takeIf { it !is JsonNull }?.toString()

    private fun objects(text: String?): Pair<List<JsonObject>, Int> {
        if (text.isNullOrEmpty()) return emptyList<JsonObject>() to 0
        val out = ArrayList<JsonObject>()
        var bad = 0
        for (raw in text.split('\n')) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val o = runCatching { json.parseToJsonElement(l) as? JsonObject }.getOrNull()
            if (o == null) bad++ else out += o
        }
        return out to bad
    }

    fun decodeScan(o: JsonObject): VitalScanRecord? {
        val id = o.str("id") ?: return null
        val mode = o.str("mode")?.takeIf { it == VitalScanRecord.MODE_FINGER || it == VitalScanRecord.MODE_FACE } ?: return null
        val start = parseIso(o.str("start")) ?: return null
        val end = parseIso(o.str("end")) ?: start
        return VitalScanRecord(
            id = id,
            mode = mode,
            sessionId = o.str("session_id"),
            startMs = start,
            endMs = end,
            tzOffsetS = (o.long("tz_offset_s") ?: 0L).toInt(),
            localDay = o.str("local_day") ?: return null,
            durationMs = o.long("duration_ms") ?: (end - start).coerceAtLeast(0L),
            platform = o.str("platform") ?: "unknown",
            deviceModel = o.str("device_model") ?: "unknown",
            cameraJson = o.text("camera") ?: "{}",
            context = o.str("context") ?: VitalScanRecord.CONTEXT_RESTING,
            qualityScore = o.num("quality_score"),
            rejectReason = o.str("reject_reason"),
            qualityJson = o.text("quality") ?: "{}",
            resultsJson = o.text("results") ?: "{}",
            algoVersion = (o.long("algo_version") ?: 1L).toInt(),
            referenceJson = o.text("reference"),
            deleted = false,
            updatedMs = parseIso(o.str("updated")) ?: start
        )
    }

    fun decodeSignal(o: JsonObject): Pair<String, VitalSignal>? {
        val scanId = o.str("scan_id") ?: return null
        val kind = o.str("kind") ?: return null
        val data = runCatching { Base64.getDecoder().decode(o.str("data_base64") ?: return null) }.getOrNull() ?: return null
        return scanId to VitalSignal(kind, o.num("sample_rate"), o.str("encoding") ?: VitalSignalCodec.ENCODING, data, o.text("meta"))
    }

    fun decodeCalibration(o: JsonObject): VitalCalibration? {
        val id = o.str("id") ?: return null
        val kind = o.str("kind")?.takeIf { it == VitalCalibration.KIND_SPO2 || it == VitalCalibration.KIND_BP } ?: return null
        val t = parseIso(o.str("t")) ?: return null
        return VitalCalibration(
            id = id, kind = kind, deviceModel = o.str("device_model") ?: return null, scanId = o.str("scan_id"), tMs = t,
            referenceJson = o.text("reference") ?: return null, featuresJson = o.text("features") ?: return null,
            deleted = false, updatedMs = parseIso(o.str("updated")) ?: t
        )
    }

    fun decodeDeviceProfile(o: JsonObject): VitalDeviceProfile? {
        val model = o.str("device_model") ?: return null
        val position = o.str("camera_position") ?: return null
        return VitalDeviceProfile(model, position, o.text("capability") ?: "{}", parseIso(o.str("updated")) ?: 0L)
    }

    /** Reads the four entries (any may be null/missing). Signals of a scan not in [scansText] are dropped. */
    fun read(scansText: String?, signalsText: String?, calibrationsText: String?, profilesText: String?): Bundle {
        var bad = 0
        val (scanObjs, b1) = objects(scansText)
        val (signalObjs, b2) = objects(signalsText)
        val (calObjs, b3) = objects(calibrationsText)
        val (profileObjs, b4) = objects(profilesText)
        bad += b1 + b2 + b3 + b4
        val records = LinkedHashMap<String, VitalScanRecord>()
        for (o in scanObjs) {
            val r = decodeScan(o)
            if (r == null || r.id in records) bad++ else records[r.id] = r
        }
        val signals = HashMap<String, LinkedHashMap<String, VitalSignal>>()
        for (o in signalObjs) {
            val decoded = decodeSignal(o)
            if (decoded == null || decoded.first !in records) {
                bad++
                continue
            }
            // One row per (scan, kind): the first one wins, like the table's primary key.
            val perScan = signals.getOrPut(decoded.first) { LinkedHashMap() }
            if (decoded.second.kind in perScan) bad++ else perScan[decoded.second.kind] = decoded.second
        }
        val cals = LinkedHashMap<String, VitalCalibration>()
        for (o in calObjs) {
            val c = decodeCalibration(o)
            if (c == null || c.id in cals) bad++ else cals[c.id] = c
        }
        val profiles = ArrayList<VitalDeviceProfile>()
        for (o in profileObjs) {
            val p = decodeDeviceProfile(o)
            if (p == null) bad++ else profiles += p
        }
        return Bundle(
            scans = records.values.map { ScanWithSignals(it, signals[it.id]?.values?.toList().orEmpty()) },
            calibrations = cals.values.toList(),
            deviceProfiles = profiles,
            skippedLines = bad
        )
    }

    /**
     * The merge of §7.2: insert by id; an id that already exists locally (live or tombstoned) is skipped together
     * with its signals. Device profiles merge by key and the newer `updated` wins. [existingScanIds] and
     * [existingCalibrationIds] include tombstones; [existingProfiles] maps `device_model|camera_position` to updated_ms.
     */
    class MergePlan(
        val scans: List<ScanWithSignals>,
        val calibrations: List<VitalCalibration>,
        val deviceProfiles: List<VitalDeviceProfile>,
        val skippedScans: Int,
        val skippedCalibrations: Int
    )

    fun profileKey(deviceModel: String, cameraPosition: String) = "$deviceModel|$cameraPosition"

    fun mergePlan(
        bundle: Bundle,
        existingScanIds: Set<String>,
        existingCalibrationIds: Set<String>,
        existingProfiles: Map<String, Long>
    ): MergePlan {
        val scans = bundle.scans.filter { it.record.id !in existingScanIds }
        val cals = bundle.calibrations.filter { it.id !in existingCalibrationIds }
        val newest = LinkedHashMap<String, VitalDeviceProfile>()
        for (p in bundle.deviceProfiles) {
            val key = profileKey(p.deviceModel, p.cameraPosition)
            val seen = newest[key]
            if (seen == null || p.updatedMs > seen.updatedMs) newest[key] = p
        }
        val profiles = newest.filter { (key, p) -> existingProfiles[key]?.let { p.updatedMs > it } ?: true }.values.toList()
        return MergePlan(scans, cals, profiles, bundle.scans.size - scans.size, bundle.calibrations.size - cals.size)
    }
}
