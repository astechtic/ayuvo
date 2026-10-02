package com.ayuvo.health.ui.vitals

import android.content.Context
import com.ayuvo.health.R
import com.ayuvo.health.l10n.ContractStrings
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.storage.VitalScanRecord
import com.ayuvo.health.vitals.storage.VitalSignal
import com.ayuvo.health.vitals.storage.VitalSignalCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/** One metric envelope as shown on the results screen (§7.1 "Results"). */
data class VitalMetricUi(
    val id: String,
    val value: Double?,
    /** Blood pressure diastolic value. */
    val secondValue: Double?,
    val unit: String,
    val classification: String,
    val confidenceLabel: String?,
    val valid: Boolean,
    val reason: String?,
    /** Indicator band id (low | typical | high). */
    val band: String?
)

/** A scan result for the results and detail screens, from a fresh analysis or a saved record. */
class VitalResultUi(
    val mode: VitalsMode,
    val rejectReason: String?,
    val qualityScore: Double,
    val grade: String,
    /** Quality components (0..1) in config weight order. */
    val components: List<Pair<String, Double>>,
    val metrics: List<VitalMetricUi>,
    /** Processed 30 Hz pulse signal (empty when signals were not kept). */
    val waveform: FloatArray,
    val mask: BooleanArray,
    /** Beat times in ms on the processed signal's time base. */
    val beatsMs: FloatArray,
    val fs: Double
) {
    fun metric(id: String): VitalMetricUi? = metrics.firstOrNull { it.id == id }

    companion object {
        /** Results order of §7.1: HR, HRV (RMSSD, SDNN), mean interval, respiration, indicators, SpO₂, BP. */
        val ORDER = listOf(
            "heart_rate", "hrv_rmssd", "hrv_sdnn", "ibi_mean", "respiratory_rate",
            "recovery_indicator", "stress_indicator", "spo2", "blood_pressure"
        )
        private val json = Json { ignoreUnknownKeys = true }

        fun from(
            mode: VitalsMode,
            results: JsonObject,
            quality: JsonObject,
            waveform: FloatArray,
            mask: BooleanArray,
            beatsMs: FloatArray,
            cfg: VitalsConfig
        ): VitalResultUi {
            val metrics = results["metrics"] as? JsonObject ?: JsonObject(emptyMap())
            val indicators = results["indicators"] as? JsonObject ?: JsonObject(emptyMap())
            val rows = ORDER.mapNotNull { id ->
                val env = (metrics[id] ?: indicators[id]) as? JsonObject ?: return@mapNotNull null
                metricUi(id, env)
            }
            val score = num(quality["score"]) ?: 0.0
            val comps = quality["components"] as? JsonObject
            val components = cfg.quality.weights.mapNotNull { (k, _) -> num(comps?.get(k))?.let { k to it } }
            return VitalResultUi(
                mode = mode,
                rejectReason = str(results["reject_reason"]),
                qualityScore = score,
                grade = str(quality["grade"]) ?: VitalsEngine.grade(score, cfg),
                components = components,
                metrics = rows,
                waveform = waveform,
                mask = mask,
                beatsMs = beatsMs,
                fs = cfg.signal.fs
            )
        }

        /** From a saved row and its signal rows (empty when raw signals were not kept). */
        fun from(record: VitalScanRecord, signals: List<VitalSignal>, cfg: VitalsConfig): VitalResultUi {
            val results = runCatching { json.parseToJsonElement(record.resultsJson) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            val quality = runCatching { json.parseToJsonElement(record.qualityJson) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            fun column(kind: String, name: String): FloatArray? = signals.firstOrNull { it.kind == kind }
                ?.let { runCatching { VitalSignalCodec.decode(it).column(name) }.getOrNull() }
            val wave = column(VitalSignal.KIND_PROCESSED, "x") ?: FloatArray(0)
            val maskF = column(VitalSignal.KIND_MASK, "m") ?: FloatArray(0)
            val beats = column(VitalSignal.KIND_BEATS, "t_ms") ?: FloatArray(0)
            return from(
                VitalsMode.fromId(record.mode) ?: VitalsMode.FINGER, results, quality,
                wave, BooleanArray(maskF.size) { maskF[it] >= 0.5f }, beats, cfg
            )
        }

        fun metricUi(id: String, env: JsonObject): VitalMetricUi {
            val valid = str(env["status"]) == "valid"
            return VitalMetricUi(
                id = id,
                value = if (valid) num(env["value"]) else null,
                secondValue = if (valid) num(env["diastolic"]) else null,
                unit = str(env["unit"]).orEmpty(),
                classification = str(env["classification"]).orEmpty(),
                confidenceLabel = str(env["confidence_label"]),
                valid = valid,
                reason = str(env["reason"]),
                band = str(env["band"])
            )
        }

        fun num(e: Any?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
        fun str(e: Any?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}

/** Display text for the shared vitals contract (ContractStrings, English from the config as the fallback). */
object VitalsText {
    private fun cfgString(cfg: VitalsConfig, vararg path: String): String? {
        var node: Any? = cfg.root
        for (p in path) node = (node as? JsonObject)?.get(p)
        return (node as? JsonPrimitive)?.content
    }

    private fun bandLabel(cfg: VitalsConfig, section: String, list: String, id: String): String? =
        ((cfg.root[section] as? JsonObject)?.get(list) as? JsonArray)
            ?.firstOrNull { VitalResultUi.str((it as? JsonObject)?.get("id")) == id }
            ?.let { ((it as JsonObject)["label"] as? JsonPrimitive)?.content }

    fun metricTitle(context: Context, cfg: VitalsConfig, id: String): String =
        ContractStrings.text(context, "vitals.metrics.$id.title", runCatching { cfg.metric(id).title }.getOrDefault(id))

    fun reason(context: Context, cfg: VitalsConfig, reason: String): String =
        ContractStrings.text(context, "vitals.reasons.$reason", cfg.reasons[reason] ?: reason)

    fun guidance(context: Context, cfg: VitalsConfig, key: String): String =
        ContractStrings.text(context, "vitals.guidance.$key", cfg.guidance[key] ?: key)

    fun classification(context: Context, cfg: VitalsConfig, id: String): String =
        ContractStrings.text(context, "vitals.classifications.$id.label", cfgString(cfg, "classifications", id, "label") ?: id)

    fun grade(context: Context, cfg: VitalsConfig, id: String): String =
        ContractStrings.text(context, "vitals.quality.grades.$id.label", bandLabel(cfg, "quality", "grades", id) ?: id)

    fun band(context: Context, cfg: VitalsConfig, id: String): String =
        ContractStrings.text(context, "vitals.indicator.bands.$id.label", bandLabel(cfg, "indicator", "bands", id) ?: id)

    fun disclaimer(context: Context, cfg: VitalsConfig): String = ContractStrings.text(context, "vitals.disclaimer", cfg.disclaimer)

    fun confidence(context: Context, label: String?): String? = when (label) {
        "high" -> context.getString(R.string.camvitals_confidence_high)
        "medium" -> context.getString(R.string.camvitals_confidence_medium)
        "low" -> context.getString(R.string.camvitals_confidence_low)
        else -> null
    }

    fun component(context: Context, id: String): String = context.getString(
        when (id) {
            "snr" -> R.string.camvitals_component_snr
            "template" -> R.string.camvitals_component_template
            "ibi" -> R.string.camvitals_component_ibi
            "agreement" -> R.string.camvitals_component_agreement
            "motion" -> R.string.camvitals_component_motion
            else -> R.string.camvitals_component_signal
        }
    )

    fun unit(context: Context, unit: String): String = when (unit) {
        "bpm" -> context.getString(R.string.camvitals_unit_bpm)
        "ms" -> context.getString(R.string.camvitals_unit_ms)
        "/min" -> context.getString(R.string.camvitals_unit_per_min)
        "%" -> context.getString(R.string.camvitals_unit_percent)
        "mmHg" -> context.getString(R.string.camvitals_unit_mmhg)
        "score" -> context.getString(R.string.camvitals_unit_score)
        else -> unit
    }

    fun modeName(context: Context, mode: VitalsMode): String =
        context.getString(if (mode == VitalsMode.FACE) R.string.camvitals_mode_face else R.string.camvitals_mode_finger)

    fun contextName(context: Context, id: String): String = context.getString(
        when (id) {
            VitalScanRecord.CONTEXT_AFTER_ACTIVITY -> R.string.camvitals_context_after_activity
            VitalScanRecord.CONTEXT_OTHER -> R.string.camvitals_context_other
            else -> R.string.camvitals_context_resting
        }
    )

    /** "72", "14.5", "118/76" in the device locale. */
    fun value(m: VitalMetricUi): String? {
        val v = m.value ?: return null
        val locale = Locale.getDefault()
        return when (m.id) {
            "blood_pressure" -> m.secondValue?.let { String.format(locale, "%.0f/%.0f", v, it) } ?: String.format(locale, "%.0f", v)
            "respiratory_rate" -> String.format(locale, "%.1f", v)
            else -> String.format(locale, "%.0f", v)
        }
    }
}
