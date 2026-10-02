package com.ayuvo.health.vitals.session

import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsAnalysis
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsSignals
import com.ayuvo.health.vitals.storage.VitalScanRecord
import com.ayuvo.health.vitals.storage.VitalSignal
import com.ayuvo.health.vitals.storage.VitalSignalCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A finished, not yet saved scan: the record and the signal rows it would write. */
class PendingVitalScan(val record: VitalScanRecord, val signals: List<VitalSignal>, val analysis: VitalsAnalysis, val indicators: JsonObject)

/**
 * Builds the `vital_scans` row of docs/camera-vitals.md §7.1 "Saved record":
 * - `results_json`: the engine output without `quality` and `signals`, plus
 *   `"indicators": {"recovery_indicator": envelope, "stress_indicator": envelope}` from `scan_indicator` against
 *   earlier valid scans of the same mode (classification "estimated"; unavailable with a reason otherwise);
 * - `quality_json`: the engine `quality`; `camera_json`, context, local day and zone offset;
 * - signal rows (`frame_stats`, `processed`, `mask`, `beats`) only when [keepSignals] is on.
 * Scans never go to `health_samples` or Health Connect.
 */
object VitalScanRecordBuilder {
    private val json = Json { ignoreUnknownKeys = true }

    class Input(
        val mode: VitalsMode,
        val input: ScanSession.ScanInput,
        /** Must be run with include_signals so the waveform and signal rows exist. */
        val analysis: VitalsAnalysis,
        val startMs: Long,
        val zone: ZoneId,
        val deviceModel: String,
        val cameraJson: JsonObject,
        val context: String,
        val sessionId: String?,
        val keepSignals: Boolean,
        /** Earlier scans (any mode; filtered here). */
        val history: List<VitalScanRecord>,
        val nowMs: Long,
        val id: String = "local:" + UUID.randomUUID().toString()
    )

    fun build(inp: Input, cfg: VitalsConfig): PendingVitalScan {
        val analysis = inp.analysis
        val indicators = indicators(analysis, inp.mode, inp.history, inp.startMs, cfg)
        val results = resultsJson(analysis, indicators)
        val durationMs = (inp.input.durationS * 1000.0).toLong().coerceAtLeast(0L)
        val endMs = inp.startMs + durationMs
        val offset = inp.zone.rules.getOffset(Instant.ofEpochMilli(inp.startMs)).totalSeconds
        val record = VitalScanRecord(
            id = inp.id,
            mode = inp.mode.storage,
            sessionId = inp.sessionId,
            startMs = inp.startMs,
            endMs = endMs,
            tzOffsetS = offset,
            localDay = HealthDayKeys.dayOf(inp.startMs, offset, inp.zone).toString(),
            durationMs = durationMs,
            platform = VitalScanRecord.PLATFORM_ANDROID,
            deviceModel = inp.deviceModel,
            cameraJson = inp.cameraJson.toString(),
            context = inp.context,
            qualityScore = analysis.qualityScore,
            rejectReason = analysis.rejectReason,
            qualityJson = analysis.quality.toString(),
            resultsJson = results.toString(),
            algoVersion = cfg.algoVersion,
            referenceJson = null,
            deleted = false,
            updatedMs = inp.nowMs
        )
        val signals = if (inp.keepSignals) signalRows(inp.input, analysis.signals, cfg) else emptyList()
        return PendingVitalScan(record, signals, analysis, indicators)
    }

    /** Engine output minus `quality` and `signals`, plus `indicators`. */
    fun resultsJson(analysis: VitalsAnalysis, indicators: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in analysis.json) if (k != "quality" && k != "signals") out[k] = v
        out["indicators"] = indicators
        return JsonObject(out)
    }

    /** `frame_stats` (engine input to float32) plus `processed`, `mask` and `beats` when the engine kept signals. */
    fun signalRows(input: ScanSession.ScanInput, signals: VitalsSignals?, cfg: VitalsConfig): List<VitalSignal> {
        val rows = ArrayList<VitalSignal>()
        rows += if (input.mode == VitalsMode.FINGER) VitalSignalCodec.fingerFrameStats(input.finger)
        else VitalSignalCodec.faceFrameStats(input.faceFrames(), cfg.face.rois)
        if (signals != null) rows += VitalSignalCodec.engineSignals(signals)
        return rows
    }

    /**
     * `{"recovery_indicator": envelope, "stress_indicator": envelope}` from `scan_indicator` against earlier valid
     * scans of [mode]. When this scan has no valid HR / RMSSD the envelopes are unavailable with the RMSSD reason.
     */
    fun indicators(analysis: VitalsAnalysis, mode: VitalsMode, history: List<VitalScanRecord>, startMs: Long, cfg: VitalsConfig): JsonObject {
        val source = mode.storage
        val hr = analysis.value("heart_rate")
        val rmssd = analysis.value("hrv_rmssd")
        val earlier = history.filter { it.mode == mode.storage && !it.deleted && it.rejectReason == null && it.startMs < startMs }
            .mapNotNull { r ->
                val metrics = runCatching { (json.parseToJsonElement(r.resultsJson) as JsonObject)["metrics"] as? JsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val h = validValue(metrics, "heart_rate") ?: return@mapNotNull null
                VitalsEngine.IndicatorScan(r.startMs.toDouble(), h, validValue(metrics, "hrv_rmssd"))
            }
        val ind = VitalsEngine.scanIndicator(hr, rmssd, earlier, startMs.toDouble(), analysis.qualityScore, cfg)
        val reason = (ind["reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val ownReason = if (hr == null || rmssd == null) {
            val m = analysis.metric("hrv_rmssd") ?: analysis.metric("heart_rate")
            (m?.get("reason") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        } else null
        val finalReason = ownReason ?: reason
        fun num(k: String): Double? = (ind[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
        val extra = mapOf("band" to (ind["band"] ?: JsonNull), "n" to (ind["n"] ?: JsonNull))
        val confidence = num("confidence")
        return JsonObject(
            linkedMapOf(
                "recovery_indicator" to VitalsEngine.envelope(cfg, "recovery_indicator", source, num("recovery"), 0, confidence, finalReason, extra),
                "stress_indicator" to VitalsEngine.envelope(cfg, "stress_indicator", source, num("stress"), 0, confidence, finalReason, extra)
            )
        )
    }

    private fun validValue(metrics: JsonObject, id: String): Double? {
        val m = metrics[id] as? JsonObject ?: return null
        if ((m["status"] as? JsonPrimitive)?.content != "valid") return null
        return (m["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    }
}
