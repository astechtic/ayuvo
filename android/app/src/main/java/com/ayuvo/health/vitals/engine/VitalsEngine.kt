package com.ayuvo.health.vitals.engine

import com.ayuvo.health.vitals.engine.VitalsMath.clamp
import com.ayuvo.health.vitals.engine.VitalsMath.confidenceLabel
import com.ayuvo.health.vitals.engine.VitalsMath.mad
import com.ayuvo.health.vitals.engine.VitalsMath.mean
import com.ayuvo.health.vitals.engine.VitalsMath.median
import com.ayuvo.health.vitals.engine.VitalsMath.pearson
import com.ayuvo.health.vitals.engine.VitalsMath.roundTo
import com.ayuvo.health.vitals.engine.VitalsMath.sampleStd
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** `analyze_finger` input. Frames are `[t_ms, r, g, b, r_std, sat_frac]`. */
data class VitalsFingerInput(
    val frames: List<DoubleArray>,
    val experimentalEnabled: Boolean = false,
    val researchEnabled: Boolean = false,
    val spo2Calibrations: List<VitalsSpo2Calibration> = emptyList(),
    val bpCalibrations: List<VitalsBpCalibration> = emptyList(),
    val nowMs: Double = 0.0,
    val includeSignals: Boolean = false
)

/** `analyze_face` input. */
data class VitalsFaceInput(
    val face: VitalsFaceFrames,
    val experimentalEnabled: Boolean = false,
    val researchEnabled: Boolean = false,
    val includeSignals: Boolean = false
)

/** Signals kept for storage when `include_signals` is set (30 Hz processed pulse signal, mask, beat times). */
class VitalsSignals(val fs: Double, val processed: DoubleArray, val mask: BooleanArray, val peaksMs: DoubleArray)

/**
 * One scan result. [json] is exactly the reference's result object (`algorithm_version`, `mode`, `duration_s`,
 * `frames`, `reject_reason`, `quality`, `metrics`, `ibi` and, with include_signals, `signals`).
 */
class VitalsAnalysis(val json: JsonObject, val signals: VitalsSignals?) {
    val mode: String get() = (json["mode"] as JsonPrimitive).content
    val rejectReason: String? get() = (json["reject_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    val quality: JsonObject get() = json["quality"] as JsonObject
    val qualityScore: Double get() = (quality["score"] as JsonPrimitive).doubleOrNull ?: 0.0
    val metrics: JsonObject get() = json["metrics"] as JsonObject
    fun metric(id: String): JsonObject? = metrics[id] as? JsonObject
    fun value(id: String): Double? = (metric(id)?.get("value") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
}

/** Scan-level entry points of `scripts/vitals_reference.py` (analysis, live control, compare, baselines, ...). */
object VitalsEngine {
    private val CHANNEL_INDEX = mapOf("r" to 0, "g" to 1, "b" to 2)

    // -- Quality and metric envelopes -------------------------------------------------------------

    fun grade(score: Double, cfg: VitalsConfig): String {
        var g = cfg.quality.grades[0].id
        for (item in cfg.quality.grades) if (score >= item.min) g = item.id
        return g
    }

    /** {score, grade, components (rounded, sorted keys)} as a mutable map so callers can append keys. */
    fun qualityScore(components: Map<String, Double>, cfg: VitalsConfig): LinkedHashMap<String, Any?> {
        var acc = 0.0
        for ((k, w) in cfg.quality.weights) acc += w * components.getValue(k)
        val score = roundTo(100.0 * acc, 1)
        val rounded = LinkedHashMap<String, Any?>()
        for (k in components.keys.sorted()) rounded[k] = roundTo(components.getValue(k), 3)
        return linkedMapOf("score" to score, "grade" to grade(score, cfg), "components" to rounded)
    }

    fun envelope(
        cfg: VitalsConfig, metricId: String, source: String, value: Double?, decimals: Int, confidence: Double?,
        reason: String?, extra: Map<String, Any?>? = null
    ): JsonObject {
        val m = cfg.metric(metricId)
        val ok = value != null && reason == null
        val conf = if (ok && confidence != null) roundTo(confidence, 2) else null
        val out = linkedMapOf<String, Any?>(
            "value" to (if (ok) roundTo(value!!, decimals) else null),
            "unit" to m.unit,
            "confidence" to conf,
            "confidence_label" to (if (ok && confidence != null) confidenceLabel(roundTo(confidence, 2)) else null),
            "classification" to m.classification,
            "algorithm_version" to cfg.algoVersion,
            "source" to source,
            "status" to (if (ok) "valid" else "unavailable"),
            "reason" to (if (ok) null else (reason ?: "low_quality"))
        )
        if (!extra.isNullOrEmpty()) for (k in extra.keys.sorted()) out[k] = extra[k]
        return VitalsJson.el(out) as JsonObject
    }

    fun unavailableAll(cfg: VitalsConfig, source: String, reason: String): LinkedHashMap<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for (m in cfg.metrics) {
            if (m.id == "recovery_indicator" || m.id == "stress_indicator") continue
            val r = if (m.id == "spo2" && source == "face_rppg") "face_not_supported" else reason
            out[m.id] = envelope(cfg, m.id, source, null, 0, null, r)
        }
        return out
    }

    // -- Shared tail: pulse signal -> beats -> HR / IBI / HRV / respiration ------------------------

    private class Tail(
        val quality: LinkedHashMap<String, Any?>,
        val metrics: LinkedHashMap<String, JsonObject>,
        val ibi: JsonObject,
        val peaks: List<VitalsPeak>
    )

    private fun beatsAndMetrics(
        sigX: DoubleArray, fs: Double, mask: BooleanArray, t0Ms: Double, source: String, cfg: VitalsConfig,
        signalFraction: Double, maskedFraction: Double, maxMasked: Double, respExtra: DoubleArray?, durationS: Double
    ): Tail {
        val spec = VitalsSignal.hrSpectrum(sigX, fs, cfg)
        val peaks = VitalsBeats.pulseDetect(sigX, fs, mask, cfg, spec.hrBpm)
        val ibi = VitalsBeats.ibiClean(peaks, cfg)
        fun acceptedIbis(): List<Double> {
            val vals = ArrayList<Double>()
            for (k in ibi.ibiMs.indices) if (ibi.accepted[k]) vals.add(ibi.ibiMs[k])
            return vals
        }
        var hrBeats: Double? = null
        if (ibi.acceptedCount >= 5) hrBeats = 60000.0 / mean(acceptedIbis())
        val q = cfg.quality
        val snrC = clamp((spec.snrDb - q.snrDbLow) / (q.snrDbHigh - q.snrDbLow), 0.0, 1.0)
        val corrs = ArrayList<Double>()
        for (p in peaks) if (p.corr != null && !p.masked) corrs.add(p.corr!!)
        val templateC = if (corrs.isNotEmpty()) clamp(mean(corrs), 0.0, 1.0) else 0.0
        var agreementC = 0.0
        if (hrBeats != null) agreementC = 1.0 - clamp(abs(hrBeats - spec.hrBpm) / q.hrAgreementBpm, 0.0, 1.0)
        val motionC = 1.0 - clamp(maskedFraction / maxMasked, 0.0, 1.0)
        val components = mapOf(
            "agreement" to agreementC, "ibi" to ibi.acceptedFraction, "motion" to motionC,
            "signal" to signalFraction, "snr" to snrC, "template" to templateC
        )
        val quality = qualityScore(components, cfg)
        val score = quality["score"] as Double
        quality["snr_db"] = roundTo(spec.snrDb, 2)
        quality["hr_spectral_bpm"] = roundTo(spec.hrBpm, 1)
        quality["hr_beats_bpm"] = roundTo(hrBeats, 1)
        quality["masked_fraction"] = roundTo(maskedFraction, 3)
        quality["beats"] = peaks.size

        val metrics = LinkedHashMap<String, JsonObject>()
        val hrReason = if (score >= q.minHrQuality && agreementC > 0.0) null else "low_quality"
        val hrConf = score / 100.0 * (0.5 + 0.5 * agreementC)
        metrics["heart_rate"] = envelope(cfg, "heart_rate", source, hrBeats, 1, hrConf, hrReason)

        val hv = cfg.hrv
        var hrvReason: String? = null
        if (hrReason != null) hrvReason = hrReason
        else if (durationS < hv.minS) hrvReason = "duration_short"
        else if (ibi.acceptedCount < hv.minBeats || ibi.acceptedFraction < hv.minAcceptedFraction) hrvReason = "few_beats"
        else if (score < hv.minQuality.getValue(source)) hrvReason = "low_quality"
        val ht = if (hrvReason == null) VitalsBeats.hrvTime(ibi.ibiMs, ibi.accepted) else null
        if (hrvReason == null && ht == null) hrvReason = "few_beats"
        val hrvConf = score / 100.0 * ibi.acceptedFraction
        val ibiReason = if (ibi.acceptedCount >= 5 && hrReason == null) null else (hrReason ?: "few_beats")
        val ibiMean = if (ibiReason == null) mean(acceptedIbis()) else null
        metrics["ibi_mean"] = envelope(cfg, "ibi_mean", source, ibiMean, 1, hrvConf, ibiReason, mapOf("count" to ibi.acceptedCount))
        metrics["hrv_rmssd"] = envelope(cfg, "hrv_rmssd", source, ht?.rmssd, 1, hrvConf, hrvReason)
        metrics["hrv_sdnn"] = envelope(cfg, "hrv_sdnn", source, ht?.sdnn, 1, hrvConf, hrvReason)
        metrics["hrv_pnn50"] = envelope(cfg, "hrv_pnn50", source, ht?.pnn50, 1, hrvConf, hrvReason)
        var freqReason = hrvReason
        if (freqReason == null && durationS < hv.freqMinS) freqReason = "duration_short"
        val hf = if (freqReason == null) VitalsBeats.hrvFreq(ibi.ibiMs, ibi.ibiTMs, ibi.accepted, cfg) else null
        if (freqReason == null && hf == null) freqReason = "few_beats"
        metrics["hrv_lf_hf"] = envelope(
            cfg, "hrv_lf_hf", source, hf?.lfHf, 2, hrvConf * 0.7, freqReason,
            mapOf("lf_nu" to hf?.let { roundTo(it.lfNu, 1) }, "hf_nu" to hf?.let { roundTo(it.hfNu, 1) })
        )

        val rp = cfg.respiration
        var respReason: String? = null
        if (hrReason != null) respReason = hrReason
        else if (durationS < rp.minS) respReason = "duration_short"
        else if (score < q.minRespQuality) respReason = "low_quality"
        else if (ibi.acceptedCount < 8) respReason = "few_beats"
        var resp = VitalsResp(null, emptyList(), null)
        if (respReason == null) {
            val accPeaks = ArrayList<VitalsPeak>()
            for (k in 1 until peaks.size) if (ibi.accepted[k - 1]) accPeaks.add(peaks[k])
            if (accPeaks.size >= 8) {
                val tList = DoubleArray(accPeaks.size) { accPeaks[it].tMs }
                val ampList = DoubleArray(accPeaks.size) { accPeaks[it].amp - accPeaks[it].foot }
                val ibiT = ArrayList<Double>()
                val ibiV = ArrayList<Double>()
                for (k in ibi.ibiMs.indices) {
                    if (ibi.accepted[k]) {
                        ibiT.add(ibi.ibiTMs[k])
                        ibiV.add(ibi.ibiMs[k])
                    }
                }
                val series = ArrayList<Pair<DoubleArray, DoubleArray>>()
                series.add(tList to ampList)
                series.add(ibiT.toDoubleArray() to ibiV.toDoubleArray())
                if (respExtra != null) series.add(tList to DoubleArray(accPeaks.size) { respExtra[accPeaks[it].footI] })
                resp = VitalsBeats.respRate(series, cfg)
            }
            if (resp.value == null) respReason = "resp_disagree"
        }
        var respConf: Double? = null
        if (resp.value != null) respConf = score / 100.0 * (1.0 - clamp(resp.spread!! / rp.agreementPerMin, 0.0, 1.0) * 0.5)
        metrics["respiratory_rate"] = envelope(
            cfg, "respiratory_rate", source, resp.value, 1, respConf, respReason, mapOf("estimates" to resp.estimates)
        )
        val ibiOut = VitalsJson.obj(
            "ibi_ms" to ibi.ibiMs, "ibi_quality" to ibi.ibiQuality,
            "ibi_t_ms" to DoubleArray(ibi.ibiTMs.size) { roundTo(ibi.ibiTMs[it] + t0Ms, 1) }
        )
        return Tail(quality, metrics, ibiOut, peaks)
    }

    private fun emptyIbi() = VitalsJson.obj("ibi_ms" to emptyList<Double>(), "ibi_quality" to emptyList<Double>(), "ibi_t_ms" to emptyList<Double>())

    // -- Finger PPG -------------------------------------------------------------------------------

    /** One frame -> "ok" | "no_finger" | "pressure" (saturated). */
    fun fingerDetect(frame: DoubleArray, cfg: VitalsConfig): String {
        val fc = cfg.finger
        val r = frame[1]
        val g = frame[2]
        if (r < fc.minRed || r < g * fc.minRedGreenRatio || frame[4] > fc.maxSpatialStd) return "no_finger"
        if (frame[5] > fc.maxSaturatedFraction) return "pressure"
        return "ok"
    }

    private fun gateFlag(experimental: Boolean, research: Boolean, metricId: String): String? {
        if (metricId == "spo2" && !experimental) return "experimental_off"
        if (metricId == "blood_pressure" && !research) return "research_off"
        return null
    }

    fun analyzeFinger(inp: VitalsFingerInput, cfg: VitalsConfig): VitalsAnalysis {
        val frames = inp.frames
        val fs = cfg.signal.fs
        val source = "finger_ppg"
        if (frames.size < 2) return emptyResult(cfg, source, frames.size)
        val states = frames.map { fingerDetect(it, cfg) }
        val jump = cfg.finger.stepJumpFraction
        val valid = BooleanArray(frames.size)
        for (i in frames.indices) {
            var ok = states[i] == "ok"
            if (ok && i > 0 && frames[i - 1][1] > 0.0) {
                if (abs(frames[i][1] - frames[i - 1][1]) / frames[i - 1][1] > jump) ok = false
            }
            valid[i] = ok
        }
        val tMs = DoubleArray(frames.size) { frames[it][0] }
        val durationS = (tMs[tMs.size - 1] - tMs[0]) / 1000.0
        var okFrames = 0
        for (v in valid) if (v) okFrames += 1
        val signalFraction = okFrames.toDouble() / frames.size
        val grids = LinkedHashMap<String, DoubleArray>()
        var mask = BooleanArray(0)
        for (name in listOf("r", "g", "b")) {
            val col = DoubleArray(frames.size) { frames[it][1 + CHANNEL_INDEX.getValue(name)] }
            val rs = VitalsSignal.resampleUniform(tMs, col, valid, fs, cfg.signal.maxGapMs)
            mask = rs.mask
            grids[name] = rs.values
        }
        val channels = LinkedHashMap<String, DoubleArray>()
        for (name in listOf("r", "g", "b")) channels[name] = VitalsSignal.fillMasked(grids.getValue(name), mask)
        if (signalFraction >= 0.5) {
            val art = VitalsSignal.amplitudeMask(VitalsSignal.preprocess(channels.getValue("r"), fs, cfg, true), fs, cfg)
            mask = VitalsSignal.unionMask(mask, art)
            for (name in listOf("r", "g", "b")) channels[name] = VitalsSignal.fillMasked(grids.getValue(name), mask)
        }
        val maskedFraction = VitalsSignal.maskedShare(mask)
        val result = linkedMapOf<String, Any?>(
            "algorithm_version" to cfg.algoVersion, "mode" to source, "duration_s" to roundTo(durationS, 2),
            "frames" to frames.size, "reject_reason" to null
        )
        var pressure = 0
        for (s in states) if (s == "pressure") pressure += 1
        var reject: String? = null
        if (signalFraction < 0.5) reject = if (pressure * 2 < frames.size - okFrames) "no_finger" else "pressure"
        else if (maskedFraction > cfg.finger.maxMaskedFraction) reject = "motion"
        else if (durationS < cfg.scan.minS) reject = "duration_short"
        if (reject != null) {
            result["reject_reason"] = reject
            result["quality"] = linkedMapOf(
                "score" to 0.0, "grade" to "poor", "components" to mapOf("signal" to roundTo(signalFraction, 3)),
                "masked_fraction" to roundTo(maskedFraction, 3)
            )
            result["metrics"] = unavailableAll(cfg, source, reject)
            result["ibi"] = emptyIbi()
            return VitalsAnalysis(VitalsJson.el(result) as JsonObject, null)
        }
        val candX = LinkedHashMap<String, DoubleArray>()
        val candSnr = LinkedHashMap<String, Double>()
        for (name in listOf("r", "g")) {
            val x = VitalsSignal.preprocess(channels.getValue(name), fs, cfg, true)
            candX[name] = x
            candSnr[name] = VitalsSignal.hrSpectrum(x, fs, cfg).snrDb
        }
        val chosen = if (candSnr.getValue("r") >= candSnr.getValue("g")) "r" else "g"
        val x = candX.getValue(chosen)
        val raw = channels.getValue(chosen)
        val riiv = DoubleArray(raw.size) { -raw[it] }
        val tail = beatsAndMetrics(x, fs, mask, tMs[0], source, cfg, signalFraction, maskedFraction, cfg.finger.maxMaskedFraction, riiv, durationS)
        val quality = tail.quality
        quality["channel"] = chosen
        quality["channel_snr_db"] = linkedMapOf("g" to roundTo(candSnr.getValue("g"), 2), "r" to roundTo(candSnr.getValue("r"), 2))
        val metrics = tail.metrics
        val score = quality["score"] as Double
        val spReason = gateFlag(inp.experimentalEnabled, inp.researchEnabled, "spo2")
        var ratio: Double? = null
        if (score >= cfg.research.spo2MinQuality) ratio = VitalsResearch.spo2Ratio(channels, tail.peaks, fs, cfg)
        val sp = VitalsResearch.spo2Estimate(ratio, score, inp.spo2Calibrations, cfg)
        metrics["spo2"] = envelope(cfg, "spo2", source, sp.value, 0, sp.confidence, spReason ?: sp.reason, mapOf("ratio" to roundTo(ratio, 4)))
        val hrValue = (metrics.getValue("heart_rate")["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
        // Python truthiness: a None or 0.0 heart rate skips the features.
        val feats = if (hrValue != null && hrValue != 0.0) VitalsResearch.bpFeatures(x, tail.peaks, fs, hrValue, cfg) else null
        val bp = VitalsResearch.bpResearch(feats, inp.bpCalibrations, inp.nowMs, score, cfg)
        val bpReason = gateFlag(inp.experimentalEnabled, inp.researchEnabled, "blood_pressure")
        metrics["blood_pressure"] = envelope(
            cfg, "blood_pressure", source, bp.sbp, 0, bp.confidence, bpReason ?: bp.reason,
            mapOf("diastolic" to (if ((bpReason ?: bp.reason) == null) bp.dbp else null), "features" to feats)
        )
        result["quality"] = quality
        result["metrics"] = metrics
        result["ibi"] = tail.ibi
        var signals: VitalsSignals? = null
        if (inp.includeSignals) {
            signals = VitalsSignals(fs, x, mask, DoubleArray(tail.peaks.size) { tail.peaks[it].tMs })
            result["signals"] = signalsJson(cfg, signals)
        }
        return VitalsAnalysis(VitalsJson.el(result) as JsonObject, signals)
    }

    /** `_empty_result`: fewer than two frames (or no face ROI) — nothing to analyse. */
    private fun emptyResult(cfg: VitalsConfig, source: String, frames: Int): VitalsAnalysis {
        val result = linkedMapOf<String, Any?>(
            "algorithm_version" to cfg.algoVersion, "mode" to source, "duration_s" to 0.0, "frames" to frames,
            "reject_reason" to "duration_short",
            "quality" to linkedMapOf("score" to 0.0, "grade" to "poor", "components" to mapOf("signal" to 0.0),
                "masked_fraction" to 0.0),
            "metrics" to unavailableAll(cfg, source, "duration_short"),
            "ibi" to emptyIbi()
        )
        return VitalsAnalysis(VitalsJson.el(result) as JsonObject, null)
    }

    private fun signalsJson(cfg: VitalsConfig, s: VitalsSignals): JsonObject =
        VitalsJson.obj("fs" to cfg.signal.fsJson, "processed" to s.processed, "mask" to s.mask, "peaks_ms" to s.peaksMs)

    // -- Face rPPG --------------------------------------------------------------------------------

    fun analyzeFace(inp: VitalsFaceInput, cfg: VitalsConfig): VitalsAnalysis {
        val face = inp.face
        val fs = cfg.signal.fs
        val source = "face_rppg"
        val roiNames = cfg.face.rois.filter { face.rois.containsKey(it) }
        val tMs = face.tMs
        val nFrames = tMs.size
        if (nFrames < 2 || roiNames.isEmpty()) return emptyResult(cfg, source, nFrames)
        val valid = BooleanArray(nFrames)
        val gates = HashMap<String, Int>()
        for (i in 0 until nFrames) {
            val g = VitalsFace.faceFrameGate(face, i, roiNames, cfg)
            valid[i] = g == null
            if (g != null) gates[g] = (gates[g] ?: 0) + 1
        }
        var ok = 0
        for (v in valid) if (v) ok += 1
        val signalFraction = ok.toDouble() / nFrames
        val durationS = (tMs[tMs.size - 1] - tMs[0]) / 1000.0
        val result = linkedMapOf<String, Any?>(
            "algorithm_version" to cfg.algoVersion, "mode" to source, "duration_s" to roundTo(durationS, 2),
            "frames" to nFrames, "reject_reason" to null
        )
        val gateCounts = LinkedHashMap<String, Int>()
        for (k in gates.keys.sorted()) gateCounts[k] = gates.getValue(k)
        val rawRoi = LinkedHashMap<String, Array<DoubleArray>>()
        var mask: BooleanArray? = null
        for (roi in roiNames) {
            val rows = face.rois.getValue(roi)
            rawRoi[roi] = Array(3) { c ->
                val col = DoubleArray(nFrames) { rows[it][c] }
                val rs = VitalsSignal.resampleUniform(tMs, col, valid, fs, cfg.signal.maxGapMs)
                mask = rs.mask
                rs.values
            }
        }
        var m = mask ?: throw IllegalArgumentException("analyze_face: no ROI of the config in the input")
        if (signalFraction >= 0.5) {
            val green = DoubleArray(m.size)
            for (roi in roiNames) {
                val filled = VitalsSignal.fillMasked(rawRoi.getValue(roi)[1], m)
                for (k in green.indices) green[k] += filled[k] / roiNames.size
            }
            m = VitalsSignal.unionMask(m, VitalsSignal.amplitudeMask(VitalsSignal.preprocess(green, fs, cfg, true), fs, cfg))
        }
        val perRoi = LinkedHashMap<String, Array<DoubleArray>>()
        for (roi in roiNames) perRoi[roi] = Array(3) { c -> VitalsSignal.fillMasked(rawRoi.getValue(roi)[c], m) }
        val maskedFraction = VitalsSignal.maskedShare(m)
        val maxMasked = cfg.face.gates.maxMaskedFraction
        var reject: String? = null
        if (maskedFraction > maxMasked) {
            var dominant: String? = null
            var domN = -1
            for (k in gates.keys.sorted()) {
                if (gates.getValue(k) > domN) {
                    dominant = k
                    domN = gates.getValue(k)
                }
            }
            reject = when (dominant) {
                "light_dark", "light_bright" -> "lighting"
                null, "hold_still" -> "motion"
                else -> "no_face"
            }
        } else if (durationS < cfg.scan.minS) {
            reject = "duration_short"
        }
        if (reject != null) {
            result["reject_reason"] = reject
            result["quality"] = linkedMapOf(
                "score" to 0.0, "grade" to "poor", "components" to mapOf("signal" to roundTo(signalFraction, 3)),
                "masked_fraction" to roundTo(maskedFraction, 3), "gates" to gateCounts
            )
            result["metrics"] = unavailableAll(cfg, source, reject)
            result["ibi"] = emptyIbi()
            return VitalsAnalysis(VitalsJson.el(result) as JsonObject, null)
        }
        val methods = LinkedHashMap<String, Map<String, DoubleArray?>>()
        for (roi in roiNames) methods[roi] = VitalsFace.rppgMethods(perRoi.getValue(roi), fs, cfg)
        val comb = VitalsFace.roiCombine(methods, fs, cfg)
        val tail = beatsAndMetrics(comb.signal, fs, m, tMs[0], source, cfg, signalFraction, maskedFraction, maxMasked, null, durationS)
        val quality = tail.quality
        quality["method"] = comb.method
        quality["rois"] = comb.rois
        quality["roi_weights"] = comb.weights
        quality["method_snr_db"] = comb.snrDb
        quality["gates"] = gateCounts
        val metrics = tail.metrics
        metrics["spo2"] = envelope(cfg, "spo2", source, null, 0, null, "face_not_supported")
        metrics["blood_pressure"] = envelope(
            cfg, "blood_pressure", source, null, 0, null,
            gateFlag(inp.experimentalEnabled, inp.researchEnabled, "blood_pressure") ?: "face_not_supported"
        )
        result["quality"] = quality
        result["metrics"] = metrics
        result["ibi"] = tail.ibi
        var signals: VitalsSignals? = null
        if (inp.includeSignals) {
            signals = VitalsSignals(fs, comb.signal, m, DoubleArray(tail.peaks.size) { tail.peaks[it].tMs })
            result["signals"] = signalsJson(cfg, signals)
        }
        return VitalsAnalysis(VitalsJson.el(result) as JsonObject, signals)
    }

    // -- Live control, comparison, baselines, indicators, validation --------------------------------

    /** "continue" until target_s; past it "extend" while quality is below extend_below_quality and elapsed < max_s. */
    fun scanControl(elapsedS: Double, quality: Double?, cfg: VitalsConfig): String {
        val sc = cfg.scan
        if (elapsedS < sc.targetS) return "continue"
        if (elapsedS >= sc.maxS) return "finish"
        if (quality == null || quality < sc.extendBelowQuality) return "extend"
        return "finish"
    }

    /** Per-mode summary for [compare]; null fields are unavailable values. */
    data class CompareSide(val hr: Double?, val rmssd: Double?, val ibiMean: Double?)

    /** Never picks a winner: reports the differences and whether they agree within the configured limits. */
    fun compare(finger: CompareSide, face: CompareSide, cfg: VitalsConfig): JsonObject {
        val cp = cfg.compare
        val out = linkedMapOf<String, Any?>("hr_diff" to null, "rmssd_diff" to null, "ibi_mean_diff" to null, "status" to "incomplete")
        if (finger.hr == null || face.hr == null) return VitalsJson.el(out) as JsonObject
        val hrDiff = roundTo(abs(finger.hr - face.hr), 1)
        out["hr_diff"] = hrDiff
        var consistent = hrDiff <= cp.maxHrDiffBpm
        if (finger.rmssd != null && face.rmssd != null) {
            val d = roundTo(abs(finger.rmssd - face.rmssd), 1)
            out["rmssd_diff"] = d
            consistent = consistent && d <= cp.maxRmssdDiffMs
        }
        if (finger.ibiMean != null && face.ibiMean != null) out["ibi_mean_diff"] = roundTo(abs(finger.ibiMean - face.ibiMean), 1)
        out["status"] = if (consistent) "consistent" else "inconsistent"
        return VitalsJson.el(out) as JsonObject
    }

    data class TimedValue(val tMs: Double, val value: Double)

    /** Each window: {n, median, mad} or null below min_n. Key "<w>d", or "all" for a null window. */
    fun baselines(values: List<TimedValue>, nowMs: Double, windowsDays: List<Double?>, cfg: VitalsConfig): JsonObject {
        val out = LinkedHashMap<String, Any?>()
        for (w in windowsDays) {
            val vals = ArrayList<Double>()
            for (v in values) {
                if (v.tMs > nowMs) continue
                if (w != null && nowMs - v.tMs > w * 86400000.0) continue
                vals.add(v.value)
            }
            val key = if (w == null) "all" else "${w.toLong()}d"
            out[key] = if (vals.size < cfg.baseline.minN) null
            else linkedMapOf("n" to vals.size, "median" to roundTo(median(vals), 1), "mad" to roundTo(mad(vals), 2))
        }
        return VitalsJson.el(out) as JsonObject
    }

    data class IndicatorScan(val tMs: Double, val hr: Double?, val rmssd: Double?)

    /** Recovery / physiological stress indicator from one scan against earlier scans of the same mode. */
    fun scanIndicator(currentHr: Double?, currentRmssd: Double?, history: List<IndicatorScan>, nowMs: Double, quality: Double, cfg: VitalsConfig): JsonObject {
        val ic = cfg.indicator
        val hist = ArrayList<IndicatorScan>()
        for (h in history) {
            if (h.hr == null || h.rmssd == null || h.rmssd <= 0.0) continue
            if (h.tMs >= nowMs || nowMs - h.tMs > ic.windowDays * 86400000.0) continue
            hist.add(h)
        }
        fun none(reason: String) = VitalsJson.obj(
            "recovery" to null, "stress" to null, "band" to null, "confidence" to null, "reason" to reason, "n" to hist.size
        )
        if (currentHr == null || currentRmssd == null || currentRmssd <= 0.0) return none("few_beats")
        if (hist.size < ic.minHistory) return none("short_history")
        val lnR = ArrayList<Double>()
        val hrs = ArrayList<Double>()
        for (h in hist) {
            lnR.add(ln(h.rmssd!!))
            hrs.add(h.hr!!)
        }
        var spR = 1.4826 * mad(lnR)
        if (spR < ic.minSpreadLnRmssd) spR = ic.minSpreadLnRmssd
        var spH = 1.4826 * mad(hrs)
        if (spH < ic.minSpreadHr) spH = ic.minSpreadHr
        val zR = (ln(currentRmssd) - median(lnR)) / spR
        val zH = (currentHr - median(hrs)) / spH
        var rec = clamp(50.0 + ic.scale * (ic.hrvWeight * zR - ic.hrWeight * zH), 0.0, 100.0)
        rec = roundTo(rec, 0)
        var band = ic.bands[0].id
        for (b in ic.bands) if (rec >= b.min) band = b.id
        val conf = clamp(hist.size / 14.0, 0.0, 1.0) * quality / 100.0
        return VitalsJson.obj(
            "recovery" to rec, "stress" to roundTo(100.0 - rec, 0), "band" to band, "confidence" to roundTo(conf, 2),
            "reason" to null, "n" to hist.size, "z_hr" to roundTo(zH, 2), "z_ln_rmssd" to roundTo(zR, 2)
        )
    }

    data class ValidationPair(val measured: Double, val reference: Double, val confidence: Double?)

    /** MAE, RMSE, bias, SD of differences, Bland-Altman limits, Pearson r, failure rate and MAE per confidence label. */
    fun validationStats(pairs: List<ValidationPair>, failures: Int, cfg: VitalsConfig): JsonObject {
        val n = pairs.size
        val out = linkedMapOf<String, Any?>(
            "n" to n, "failure_rate" to (if (n + failures > 0) roundTo(failures.toDouble() / (n + failures), 3) else null)
        )
        if (n < 2) {
            for (k in listOf("mae", "rmse", "bias", "sd", "loa_low", "loa_high", "r")) out[k] = null
            out["by_confidence"] = emptyMap<String, Any?>()
            return VitalsJson.el(out) as JsonObject
        }
        val diffs = ArrayList<Double>()
        val absd = ArrayList<Double>()
        var sq = 0.0
        val ms = ArrayList<Double>()
        val rs = ArrayList<Double>()
        for (p in pairs) {
            val d = p.measured - p.reference
            diffs.add(d)
            absd.add(abs(d))
            sq += d * d
            ms.add(p.measured)
            rs.add(p.reference)
        }
        val bias = mean(diffs)
        val sd = sampleStd(diffs)!!
        out["mae"] = roundTo(mean(absd), 2)
        out["rmse"] = roundTo(sqrt(sq / n), 2)
        out["bias"] = roundTo(bias, 2)
        out["sd"] = roundTo(sd, 2)
        out["loa_low"] = roundTo(bias - 1.96 * sd, 2)
        out["loa_high"] = roundTo(bias + 1.96 * sd, 2)
        out["r"] = roundTo(pearson(ms, rs), 3)
        val groups = LinkedHashMap<String, ArrayList<Double>>()
        for (p in pairs) {
            val label = confidenceLabel(p.confidence) ?: "unknown"
            groups.getOrPut(label) { ArrayList() }.add(abs(p.measured - p.reference))
        }
        val by = LinkedHashMap<String, Any?>()
        for (label in groups.keys.sorted()) {
            val g = groups.getValue(label)
            by[label] = linkedMapOf("n" to g.size, "mae" to roundTo(mean(g), 2))
        }
        out["by_confidence"] = by
        return VitalsJson.el(out) as JsonObject
    }

    /** One stored scan as `insights_fallback` sees it; [metrics] are the result envelopes keyed by metric id. */
    data class FallbackScan(
        val localDay: String,
        val mode: String,
        val context: String?,
        val qualityScore: Double?,
        val rejectReason: String?,
        val metrics: Map<String, JsonObject>
    )

    /** Series filled by [insightsFallback], sorted like the reference's `sorted(sources.keys())`. */
    val INSIGHTS_FALLBACK_SERIES = listOf("hrv", "respiratory_rate", "resting_heart_rate")

    /**
     * Finger-scan fallback for Recovery / Health Age (docs/camera-vitals.md §7). [platformDays] are the days that already
     * have a wearable value per series; those days are never filled. Only valid scans of `insights_fallback.mode` taken
     * in a configured context with quality at or above min_quality count; the day value is the median of those scans.
     * Returns {series: {day: value}} for resting_heart_rate, hrv and respiratory_rate (empty series kept).
     */
    fun insightsFallback(
        platformDays: Map<String, Collection<String>>,
        scans: List<FallbackScan>,
        hrvKind: String,
        cfg: VitalsConfig
    ): LinkedHashMap<String, LinkedHashMap<String, Double>> {
        val fb = cfg.insightsFallback
        val sources = mapOf(
            "resting_heart_rate" to "heart_rate", "hrv" to "hrv_$hrvKind", "respiratory_rate" to "respiratory_rate"
        )
        val out = LinkedHashMap<String, LinkedHashMap<String, Double>>()
        for (series in sources.keys.sorted()) {
            val have = platformDays[series]?.toSet().orEmpty()
            val perDay = HashMap<String, ArrayList<Double>>()
            for (sc in scans) {
                if (sc.mode != fb.mode || sc.rejectReason != null) continue
                if (sc.context == null || sc.context !in fb.contexts || sc.qualityScore == null) continue
                if (sc.qualityScore < fb.minQuality || sc.localDay in have) continue
                val env = sc.metrics[sources.getValue(series)] ?: continue
                val status = (env["status"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                val value = (env["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
                if (status != "valid" || value == null) continue
                perDay.getOrPut(sc.localDay) { ArrayList() }.add(value)
            }
            val days = LinkedHashMap<String, Double>()
            for (day in perDay.keys.sorted()) days[day] = roundTo(median(perDay.getValue(day)), 2)
            out[series] = days
        }
        return out
    }

    /**
     * Deep-merges `device_overrides[deviceModel]` into the config (§26). [deviceOverrides] defaults to the config's own.
     * Returns the merged sections that changed.
     */
    fun effectiveConfig(deviceModel: String, deviceOverrides: JsonObject?, cfg: VitalsConfig): JsonObject {
        val overrides = deviceOverrides ?: cfg.deviceOverrides
        val patch = overrides[deviceModel]
        if (patch == null || patch is JsonNull) return VitalsJson.obj("device_model" to deviceModel, "overridden" to emptyList<String>())
        val merged = merge(cfg.root, patch)
        val changed = (patch as JsonObject).keys.sorted()
        val sections = LinkedHashMap<String, JsonElement>()
        for (k in changed) sections[k] = (merged as JsonObject).getValue(k)
        return VitalsJson.obj("device_model" to deviceModel, "overridden" to changed, "sections" to JsonObject(sections))
    }

    fun merge(base: JsonElement?, patch: JsonElement): JsonElement {
        if (base !is JsonObject || patch !is JsonObject) return patch
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in base) out[k] = v
        for ((k, v) in patch) out[k] = if (base.containsKey(k)) merge(base[k], v) else v
        return JsonObject(out)
    }

    // -- JSON input decoding (vectors, replay, stored frame_stats) ---------------------------------

    fun parseFingerFrames(e: JsonElement?): List<DoubleArray> =
        (e as JsonArray).map { row -> (row as JsonArray).map { VitalsConfig.num(it)!! }.toDoubleArray() }

    fun parseFaceFrames(o: JsonObject): VitalsFaceFrames {
        fun nums(k: String) = (o[k] as JsonArray).map { VitalsConfig.num(it)!! }.toDoubleArray()
        val rois = LinkedHashMap<String, List<DoubleArray>>()
        for ((name, rows) in (o["rois"] as JsonObject)) {
            rois[name] = (rows as JsonArray).map { r -> (r as JsonArray).map { VitalsConfig.num(it)!! }.toDoubleArray() }
        }
        return VitalsFaceFrames(
            nums("t_ms"), rois, nums("motion"), nums("yaw"), nums("pitch"), nums("luma"),
            nums("face_count").map { it.toInt() }.toIntArray(), nums("face_fraction")
        )
    }
}
