package com.ayuvo.health.vitals.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `assets/vitals/vitals_config.json`, a byte copy of `shared/vitals/vitals_config.json` (docs/camera-vitals.md).
 * `scripts/vitals_reference.py` is the normative algorithm; every threshold the port reads comes from here.
 * Pure JVM (no Android classes) so the vector tests run as plain unit tests.
 */
class VitalsConfig(val root: JsonObject) {

    data class Metric(val id: String, val title: String, val unit: String, val classification: String)
    data class Band(val id: String, val min: Double)

    class Signal(o: JsonObject) {
        val fsJson: JsonElement = o["fs"] ?: error("signal.fs missing")
        val fs: Double = o.d("fs")
        val artifactRmsFactor = o.d("artifact_rms_factor")
        val artifactWindowS = o.d("artifact_window_s")
        val detrendWindowS = o.d("detrend_window_s")
        val hrBandHz: DoubleArray = o.nums("hr_band_hz")
        val maxGapMs = o.d("max_gap_ms")
        val peakRefractoryS = o.d("peak_refractory_s")
        val peakWindowS = o.d("peak_window_s")
        val refractoryFraction = o.d("refractory_fraction")
        val beatWindowS = o.d("beat_window_s")
        val peakOffset = o.d("peak_offset")
        val spectrumStepHz = o.d("spectrum_step_hz")
        val welchSegmentS = o.d("welch_segment_s")
        val welchOverlap = o.d("welch_overlap")
    }

    class Ibi(o: JsonObject) {
        val maxMs = o.d("max_ms")
        val maxRelDeviation = o.d("max_rel_deviation")
        val medianWindow: Int = o.d("median_window").toInt()
        val minMs = o.d("min_ms")
        val minTemplateCorr = o.d("min_template_corr")
    }

    class Hrv(o: JsonObject) {
        val freqGridStepHz = o.d("freq_grid_step_hz")
        val freqMinS = o.d("freq_min_s")
        val hfBand: DoubleArray = o.nums("hf_band")
        val lfBand: DoubleArray = o.nums("lf_band")
        val minAcceptedFraction = o.d("min_accepted_fraction")
        val minBeats = o.d("min_beats")
        val minQuality: Map<String, Double> = o.obj("min_quality").entries.associate { it.key to num(it.value)!! }
        val minS = o.d("min_s")
    }

    class Respiration(o: JsonObject) {
        val agreementPerMin = o.d("agreement_per_min")
        val bandHz: DoubleArray = o.nums("band_hz")
        val gridStepHz = o.d("grid_step_hz")
        val minS = o.d("min_s")
        val resampleHz = o.d("resample_hz")
    }

    class Quality(o: JsonObject) {
        val grades: List<Band> = o.arr("grades").map { val g = it as JsonObject; Band(g.s("id"), g.d("min")) }
        val hrAgreementBpm = o.d("hr_agreement_bpm")
        val minHrQuality = o.d("min_hr_quality")
        val minRespQuality = o.d("min_resp_quality")
        val snrDbHigh = o.d("snr_db_high")
        val snrDbLow = o.d("snr_db_low")
        /** Iterated in sorted key order, like `sorted(w.keys())`. */
        val weights: List<Pair<String, Double>> = o.obj("weights").entries.map { it.key to num(it.value)!! }.sortedBy { it.first }
    }

    class Finger(o: JsonObject) {
        val maxMaskedFraction = o.d("max_masked_fraction")
        val maxSaturatedFraction = o.d("max_saturated_fraction")
        val maxSpatialStd = o.d("max_spatial_std")
        val minRed = o.d("min_red")
        val minRedGreenRatio = o.d("min_red_green_ratio")
        val stepJumpFraction = o.d("step_jump_fraction")
    }

    class Gates(o: JsonObject) {
        val lumaMax = o.d("luma_max")
        val lumaMin = o.d("luma_min")
        val maxMaskedFraction = o.d("max_masked_fraction")
        val maxMotion = o.d("max_motion")
        val maxPitchDeg = o.d("max_pitch_deg")
        val maxYawDeg = o.d("max_yaw_deg")
        val maxFaceFraction = o.d("max_face_fraction")
        val minFaceFraction = o.d("min_face_fraction")
        val minSkinFraction = o.d("min_skin_fraction")
    }

    class Face(o: JsonObject) {
        val gates = Gates(o.obj("gates"))
        val methods: List<String> = o.strs("methods")
        val minRoiSnrDb = o.d("min_roi_snr_db")
        val posWindowS = o.d("pos_window_s")
        val rois: List<String> = o.strs("rois")
    }

    class Scan(o: JsonObject) {
        val extendBelowQuality = o.d("extend_below_quality")
        val maxS = o.d("max_s")
        val minS = o.d("min_s")
        val targetS = o.d("target_s")
    }

    class Compare(o: JsonObject) {
        val maxHrDiffBpm = o.d("max_hr_diff_bpm")
        val maxRmssdDiffMs = o.d("max_rmssd_diff_ms")
        val maxSessionGapS = o.d("max_session_gap_s")
    }

    class Baseline(o: JsonObject) {
        val minN = o.d("min_n")
        /** null = all history. */
        val windowsDays: List<Double?> = o.arr("windows_days").map { num(it) }
    }

    class Indicator(o: JsonObject) {
        val bands: List<Band> = o.arr("bands").map { val b = it as JsonObject; Band(b.s("id"), b.d("min")) }
        val hrWeight = o.d("hr_weight")
        val hrvWeight = o.d("hrv_weight")
        val minHistory = o.d("min_history")
        val minSpreadHr = o.d("min_spread_hr")
        val minSpreadLnRmssd = o.d("min_spread_ln_rmssd")
        val scale = o.d("scale")
        val windowDays = o.d("window_days")
    }

    class Research(o: JsonObject) {
        val bpCalibrationMaxAgeDays = o.d("bp_calibration_max_age_days")
        val bpCalibrationMaxGapMin = o.d("bp_calibration_max_gap_min")
        val bpMaxAbsZ = o.d("bp_max_abs_z")
        val bpFeatures: List<String> = o.strs("bp_features")
        val bpMinCalibrations = o.d("bp_min_calibrations")
        val bpRidgeLambda = o.d("bp_ridge_lambda")
        val spo2Channels: List<String> = o.strs("spo2_channels")
        val spo2MaxRMargin = o.d("spo2_max_r_margin")
        val spo2MinCalibrations = o.d("spo2_min_calibrations")
        val spo2MinQuality = o.d("spo2_min_quality")
        val spo2Range: DoubleArray = o.nums("spo2_range")
    }

    class InsightsFallback(o: JsonObject) {
        val contexts: List<String> = o.strs("contexts")
        val minQuality = o.d("min_quality")
        val mode: String = o.s("mode")
    }

    val algoVersion: Int = root.d("algo_version").toInt()
    val configVersion: Int = root.d("config_version").toInt()
    val disclaimer: String = (root["disclaimer"] as? JsonPrimitive)?.content.orEmpty()
    val signal = Signal(root.obj("signal"))
    val ibi = Ibi(root.obj("ibi"))
    val hrv = Hrv(root.obj("hrv"))
    val respiration = Respiration(root.obj("respiration"))
    val quality = Quality(root.obj("quality"))
    val finger = Finger(root.obj("finger"))
    val face = Face(root.obj("face"))
    val scan = Scan(root.obj("scan"))
    val compare = Compare(root.obj("compare"))
    val baseline = Baseline(root.obj("baseline"))
    val indicator = Indicator(root.obj("indicator"))
    val research = Research(root.obj("research"))
    val insightsFallback = InsightsFallback(root.obj("insights_fallback"))
    val metrics: List<Metric> = root.arr("metrics").map {
        val m = it as JsonObject
        Metric(m.s("id"), m.s("title"), m.s("unit"), m.s("classification"))
    }
    val deviceOverrides: JsonObject = (root["device_overrides"] as? JsonObject) ?: JsonObject(emptyMap())
    val reasons: Map<String, String> = strMap("reasons")
    val guidance: Map<String, String> = strMap("guidance")

    /** `metric_class`: KeyError in the reference, IllegalArgumentException here. */
    fun metric(id: String): Metric = metrics.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown metric $id")

    /** The config with `device_overrides[deviceModel]` deep-merged in (§26 calibration hook); this config when none. */
    fun forDevice(deviceModel: String): VitalsConfig {
        val patch = deviceOverrides[deviceModel] ?: return this
        return VitalsConfig(VitalsEngine.merge(root, patch) as JsonObject)
    }

    private fun strMap(key: String): Map<String, String> =
        (root[key] as? JsonObject)?.entries?.associate { it.key to (it.value as JsonPrimitive).content }.orEmpty()

    companion object {
        const val ASSET_PATH = "vitals/vitals_config.json"

        @Volatile
        var active: VitalsConfig? = null

        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(text: String): VitalsConfig = VitalsConfig(json.parseToJsonElement(text) as JsonObject)

        internal fun num(e: JsonElement?): Double? =
            (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

        private fun JsonObject.d(k: String): Double = num(this[k]) ?: error("vitals config: $k missing")
        private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.content ?: error("vitals config: $k missing")
        private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: error("vitals config: $k missing")
        private fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: error("vitals config: $k missing")
        private fun JsonObject.nums(k: String): DoubleArray = arr(k).map { num(it)!! }.toDoubleArray()
        private fun JsonObject.strs(k: String): List<String> = arr(k).map { (it as JsonPrimitive).content }
    }
}
