package com.ayuvo.health.vitals.session

import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Recent camera scans in the Coach's health context (docs/camera-vitals.md §7, plan Phase 6): valid scans of the last
 * [WINDOW_DAYS] days, newest first, at most [MAX_SCANS]. Each value carries its classification label; SpO₂ appears
 * only with *Experimental estimates* on and BP only with *Research estimates* on, each with its estimate label.
 * English prompt text (the Coach prompt is English, like the rest of `ChatService`).
 */
object CoachCameraScans {
    const val WINDOW_DAYS = 7L
    const val MAX_SCANS = 5
    const val SPO2_LABEL = "experimental camera estimate"
    const val BP_LABEL = "research estimate, not a blood pressure measurement"

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun num(o: JsonObject?, k: String): Double? = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
    private fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** The scans that qualify: live, not rejected, started within the window ending at [nowMs]. Newest first, capped. */
    fun recent(scans: List<VitalScanRecord>, nowMs: Long): List<VitalScanRecord> =
        scans.filter { !it.deleted && it.rejectReason == null && it.startMs <= nowMs && nowMs - it.startMs <= WINDOW_DAYS * 86_400_000L }
            .sortedWith(compareByDescending<VitalScanRecord> { it.startMs }.thenByDescending { it.id })
            .take(MAX_SCANS)

    /** Prompt lines (a section header plus one line per scan); empty when no scan qualifies. */
    fun promptLines(
        scans: List<VitalScanRecord>,
        nowMs: Long,
        experimentalEnabled: Boolean,
        researchEnabled: Boolean,
        cfg: VitalsConfig,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<String> {
        val recent = recent(scans, nowMs)
        if (recent.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        out += ""
        out += "## Camera measurements (last $WINDOW_DAYS days, newest first)"
        out += "Manual phone-camera scans taken in Ayuvo for general wellness; not a medical measurement or diagnosis. " +
            "Finger and face scans are different methods and are never merged. Each value is labelled with its classification."
        for (s in recent) out += "- " + line(s, experimentalEnabled, researchEnabled, cfg, zone)
        return out
    }

    fun line(s: VitalScanRecord, experimentalEnabled: Boolean, researchEnabled: Boolean, cfg: VitalsConfig, zone: ZoneId): String {
        val metrics = s.metricsJson()
        val loc = Locale.US
        fun valid(id: String): JsonObject? = (metrics[id] as? JsonObject)?.takeIf { str(it, "status") == "valid" && num(it, "value") != null }
        fun cls(env: JsonObject): String = str(env, "classification") ?: "measured"
        val parts = ArrayList<String>()
        val day = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        parts += day.toString()
        parts += if (s.mode == VitalScanRecord.MODE_FACE) "face scan" else "finger scan"
        parts += "context ${s.context.replace('_', ' ')}"
        valid("heart_rate")?.let { parts += "HR ${String.format(loc, "%.0f", num(it, "value"))} bpm (${cls(it)})" }
        valid("hrv_rmssd")?.let { parts += "RMSSD ${String.format(loc, "%.0f", num(it, "value"))} ms (${cls(it)})" }
        valid("respiratory_rate")?.let { parts += "respiratory rate ${String.format(loc, "%.1f", num(it, "value"))} /min (${cls(it)})" }
        if (experimentalEnabled && s.mode == VitalScanRecord.MODE_FINGER) {
            valid("spo2")?.let { parts += "SpO2 ${String.format(loc, "%.0f", num(it, "value"))} % ($SPO2_LABEL)" }
        }
        if (researchEnabled && s.mode == VitalScanRecord.MODE_FINGER) {
            valid("blood_pressure")?.let { env ->
                val dia = num(env, "diastolic")
                val v = String.format(loc, "%.0f", num(env, "value")) + (dia?.let { "/" + String.format(loc, "%.0f", it) } ?: "")
                parts += "BP $v mmHg ($BP_LABEL)"
            }
        }
        val grade = str(obj(s.qualityJson), "grade") ?: VitalsEngine.grade(s.qualityScore ?: 0.0, cfg)
        parts += "signal quality $grade"
        return parts.joinToString(" · ")
    }
}
