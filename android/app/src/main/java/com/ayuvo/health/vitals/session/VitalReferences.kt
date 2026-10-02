package com.ayuvo.health.vitals.session

import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.storage.VitalCalibration
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID

private val vitalsJson = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String?): JsonObject? =
    text?.let { runCatching { vitalsJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

private fun JsonElement?.numOrNull(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
private fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** The `metrics` object of a saved scan's `results_json` (empty when unreadable). */
fun VitalScanRecord.metricsJson(): JsonObject = parseObject(resultsJson)?.get("metrics") as? JsonObject ?: JsonObject(emptyMap())

/** A metric's value when its envelope is `valid`; null otherwise. */
fun VitalScanRecord.validValue(metricId: String): Double? {
    val env = metricsJson()[metricId] as? JsonObject ?: return null
    if (env["status"].strOrNull() != "valid") return null
    return env["value"].numOrNull()
}

/**
 * Reference readings a user attaches to a scan (docs/camera-vitals.md §6, §7.1 "Reference reading sheet"), stored in
 * `reference_json` with the keys `scripts/vitals_eval.py` reads: `heart_rate`, `hrv_rmssd`, `respiratory_rate`,
 * `spo2`, `blood_pressure` ([systolic, diastolic]) and `device`.
 */
data class VitalReference(
    val heartRate: Double? = null,
    val rmssd: Double? = null,
    val respiratoryRate: Double? = null,
    val spo2: Double? = null,
    val systolic: Double? = null,
    val diastolic: Double? = null,
    val device: String? = null
) {
    val isEmpty: Boolean get() = heartRate == null && rmssd == null && respiratoryRate == null && spo2 == null && systolic == null && diastolic == null

    /** First field outside its plausible range, or null when every entered value is usable. */
    fun invalidField(): String? = when {
        heartRate != null && heartRate !in 25.0..250.0 -> "heart_rate"
        rmssd != null && rmssd !in 1.0..400.0 -> "hrv_rmssd"
        respiratoryRate != null && respiratoryRate !in 3.0..70.0 -> "respiratory_rate"
        spo2 != null && spo2 !in 50.0..100.0 -> "spo2"
        (systolic == null) != (diastolic == null) -> "blood_pressure"
        systolic != null && systolic !in 60.0..260.0 -> "blood_pressure"
        diastolic != null && diastolic !in 30.0..160.0 -> "blood_pressure"
        systolic != null && diastolic != null && diastolic >= systolic -> "blood_pressure"
        else -> null
    }

    /** `reference_json` text; null when nothing was entered (clears the reference). */
    fun toJsonText(): String? {
        if (isEmpty) return null
        val out = LinkedHashMap<String, JsonElement>()
        heartRate?.let { out["heart_rate"] = JsonPrimitive(it) }
        rmssd?.let { out["hrv_rmssd"] = JsonPrimitive(it) }
        respiratoryRate?.let { out["respiratory_rate"] = JsonPrimitive(it) }
        spo2?.let { out["spo2"] = JsonPrimitive(it) }
        if (systolic != null && diastolic != null) out["blood_pressure"] = JsonArray(listOf(JsonPrimitive(systolic), JsonPrimitive(diastolic)))
        device?.trim()?.takeIf { it.isNotEmpty() }?.let { out["device"] = JsonPrimitive(it.take(MAX_DEVICE_CHARS)) }
        return JsonObject(out).toString()
    }

    /** Reference value of a validation metric (`blood_pressure` = systolic, like `vitals_eval.py`). */
    fun value(metricId: String): Double? = when (metricId) {
        "heart_rate" -> heartRate
        "hrv_rmssd" -> rmssd
        "respiratory_rate" -> respiratoryRate
        "spo2" -> spo2
        "blood_pressure" -> systolic
        else -> null
    }

    companion object {
        const val MAX_DEVICE_CHARS = 80

        fun parse(text: String?): VitalReference? {
            val o = parseObject(text) ?: return null
            val bp = o["blood_pressure"]
            val (sys, dia) = when (bp) {
                is JsonArray -> bp.getOrNull(0).numOrNull() to bp.getOrNull(1).numOrNull()
                else -> bp.numOrNull() to null
            }
            return VitalReference(
                heartRate = o["heart_rate"].numOrNull(),
                rmssd = o["hrv_rmssd"].numOrNull(),
                respiratoryRate = o["respiratory_rate"].numOrNull(),
                spo2 = o["spo2"].numOrNull(),
                systolic = sys,
                diastolic = dia,
                device = o["device"].strOrNull()
            )
        }
    }
}

/**
 * Calibration hooks of the reference sheet (§7.1): on a finger scan with an SpO₂ `ratio` and *Experimental estimates*
 * on, an oximeter SpO₂ becomes a `spo2` calibration keyed by device model; on a finger scan with BP `features` and
 * *Research estimates* on, a cuff reading becomes a `bp` calibration with its `scan_gap_min`.
 */
object VitalCalibrationHooks {
    /** The scan's SpO₂ ratio-of-ratios when the SpO₂ calibration hook applies. */
    fun spo2Ratio(record: VitalScanRecord, experimentalEnabled: Boolean): Double? {
        if (!experimentalEnabled || record.mode != VitalScanRecord.MODE_FINGER) return null
        return (record.metricsJson()["spo2"] as? JsonObject)?.get("ratio").numOrNull()
    }

    /** The scan's `bp_features` when the BP calibration hook applies. */
    fun bpFeatures(record: VitalScanRecord, researchEnabled: Boolean): JsonObject? {
        if (!researchEnabled || record.mode != VitalScanRecord.MODE_FINGER) return null
        return (record.metricsJson()["blood_pressure"] as? JsonObject)?.get("features") as? JsonObject
    }

    fun spo2Calibration(record: VitalScanRecord, ratio: Double, spo2: Double, nowMs: Long, id: String = newId()) = VitalCalibration(
        id = id, kind = VitalCalibration.KIND_SPO2, deviceModel = record.deviceModel, scanId = record.id, tMs = nowMs,
        referenceJson = JsonObject(mapOf("spo2" to JsonPrimitive(spo2))).toString(),
        featuresJson = JsonObject(mapOf("ratio" to JsonPrimitive(ratio))).toString(),
        updatedMs = nowMs
    )

    fun bpCalibration(record: VitalScanRecord, features: JsonObject, sbp: Double, dbp: Double, scanGapMin: Double, nowMs: Long, id: String = newId()) =
        VitalCalibration(
            id = id, kind = VitalCalibration.KIND_BP, deviceModel = record.deviceModel, scanId = record.id, tMs = nowMs,
            referenceJson = JsonObject(
                linkedMapOf("sbp" to JsonPrimitive(sbp), "dbp" to JsonPrimitive(dbp), "scan_gap_min" to JsonPrimitive(scanGapMin))
            ).toString(),
            featuresJson = features.toString(),
            updatedMs = nowMs
        )

    /** `vital_calibrations` rows to the engine's inputs (rows missing a value are left out). */
    fun spo2Inputs(rows: List<VitalCalibration>) = rows.mapNotNull { c ->
        val ratio = parseObject(c.featuresJson)?.get("ratio").numOrNull() ?: return@mapNotNull null
        val value = parseObject(c.referenceJson)?.get("spo2").numOrNull() ?: return@mapNotNull null
        com.ayuvo.health.vitals.engine.VitalsSpo2Calibration(ratio, value)
    }

    fun bpInputs(rows: List<VitalCalibration>) = rows.mapNotNull { c ->
        val ref = parseObject(c.referenceJson) ?: return@mapNotNull null
        val features = parseObject(c.featuresJson)?.mapNotNull { (k, v) -> v.numOrNull()?.let { k to it } }?.toMap()
        com.ayuvo.health.vitals.engine.VitalsBpCalibration(
            tMs = c.tMs.toDouble(),
            scanGapMin = ref["scan_gap_min"].numOrNull() ?: return@mapNotNull null,
            features = features,
            sbp = ref["sbp"].numOrNull() ?: return@mapNotNull null,
            dbp = ref["dbp"].numOrNull() ?: return@mapNotNull null
        )
    }

    private fun newId() = "local:" + UUID.randomUUID().toString()
}

/** Compare flow (docs/camera-vitals.md §6): linking rule and the engine `compare` over two saved scans. */
object VitalsCompareFlow {
    /** The face scan is linked only when it starts within `compare.max_session_gap_s` of the finger scan's save. */
    fun linked(fingerSavedMs: Long, faceStartMs: Long, cfg: VitalsConfig): Boolean =
        faceStartMs - fingerSavedMs <= (cfg.compare.maxSessionGapS * 1000.0).toLong()

    fun side(record: VitalScanRecord?): VitalsEngine.CompareSide = VitalsEngine.CompareSide(
        record?.validValue("heart_rate"), record?.validValue("hrv_rmssd"), record?.validValue("ibi_mean")
    )

    /** `{hr_diff, rmssd_diff, ibi_mean_diff, status}`; a missing scan makes it `incomplete`. Never picks a winner. */
    fun compare(finger: VitalScanRecord?, face: VitalScanRecord?, cfg: VitalsConfig): JsonObject =
        VitalsEngine.compare(side(finger), side(face), cfg)
}

/**
 * Validation screen and dataset (docs/camera-vitals.md §6, §7.1): `validation_stats` per mode × metric over scans with
 * a reference, counting a scan whose reference exists but whose value is unavailable as a failure; and the
 * `ayuvo-vitals-validation` file `scripts/vitals_eval.py` reads.
 */
object VitalsValidation {
    const val FORMAT = "ayuvo-vitals-validation"
    const val VERSION = 1
    const val FILE_NAME = "ayuvo-vitals-validation.json"

    /** The screen's metrics, in display order (`blood_pressure` = systolic). */
    val METRICS = listOf("heart_rate", "hrv_rmssd", "respiratory_rate", "spo2", "blood_pressure")
    val MODES = listOf(VitalScanRecord.MODE_FINGER, VitalScanRecord.MODE_FACE)

    data class Row(val mode: String, val metric: String, val stats: JsonObject) {
        private fun num(k: String): Double? = stats[k].numOrNull()
        val n: Int get() = num("n")?.toInt() ?: 0
        val mae: Double? get() = num("mae")
        val rmse: Double? get() = num("rmse")
        val bias: Double? get() = num("bias")
        val loaLow: Double? get() = num("loa_low")
        val loaHigh: Double? get() = num("loa_high")
        val r: Double? get() = num("r")
        val failureRate: Double? get() = num("failure_rate")
        /** label -> (n, mae), labels sorted. */
        val byConfidence: List<Triple<String, Int, Double?>>
            get() = (stats["by_confidence"] as? JsonObject).orEmpty().map { (label, v) ->
                val o = v as? JsonObject
                Triple(label, o?.get("n").numOrNull()?.toInt() ?: 0, o?.get("mae").numOrNull())
            }
    }

    fun withReference(scans: List<VitalScanRecord>): List<VitalScanRecord> =
        scans.filter { !it.deleted && VitalReference.parse(it.referenceJson)?.isEmpty == false }

    fun rows(scans: List<VitalScanRecord>, cfg: VitalsConfig): List<Row> {
        val referenced = withReference(scans)
        val out = ArrayList<Row>()
        for (mode in MODES) {
            for (metric in METRICS) {
                val pairs = ArrayList<VitalsEngine.ValidationPair>()
                var failures = 0
                for (scan in referenced) {
                    if (scan.mode != mode) continue
                    val ref = VitalReference.parse(scan.referenceJson)?.value(metric) ?: continue
                    val env = scan.metricsJson()[metric] as? JsonObject
                    val measured = if (env?.get("status").strOrNull() == "valid") env?.get("value").numOrNull() else null
                    if (measured == null) {
                        failures++
                        continue
                    }
                    pairs += VitalsEngine.ValidationPair(measured, ref, env?.get("confidence").numOrNull())
                }
                if (pairs.isNotEmpty() || failures > 0) out += Row(mode, metric, VitalsEngine.validationStats(pairs, failures, cfg))
            }
        }
        return out
    }

    /** The export file: only scans that have a reference. */
    fun dataset(scans: List<VitalScanRecord>, exportedAt: String, appVersion: String): JsonObject {
        val rows = withReference(scans).sortedBy { it.startMs }.map { s ->
            JsonObject(
                linkedMapOf(
                    "id" to JsonPrimitive(s.id),
                    "mode" to JsonPrimitive(s.mode),
                    "start" to JsonPrimitive(com.ayuvo.health.vitals.storage.CameraVitalsArchive.iso(s.startMs)),
                    "device_model" to JsonPrimitive(s.deviceModel),
                    "platform" to JsonPrimitive(s.platform),
                    "context" to JsonPrimitive(s.context),
                    "algo_version" to JsonPrimitive(s.algoVersion),
                    "quality" to (s.qualityScore?.let(::JsonPrimitive) ?: JsonNull),
                    "reject_reason" to (s.rejectReason?.let(::JsonPrimitive) ?: JsonNull),
                    "metrics" to s.metricsJson(),
                    "reference" to (parseObject(s.referenceJson) ?: JsonNull)
                )
            )
        }
        return JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(FORMAT),
                "version" to JsonPrimitive(VERSION),
                "exported_at" to JsonPrimitive(exportedAt),
                "app_version" to JsonPrimitive(appVersion),
                "platform" to JsonPrimitive(VitalScanRecord.PLATFORM_ANDROID),
                "scans" to JsonArray(rows)
            )
        )
    }
}
