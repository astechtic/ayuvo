package com.ayuvo.health.vitals

import com.ayuvo.health.records.processing.RecordsVectors
import com.ayuvo.health.vitals.engine.VitalsBeats
import com.ayuvo.health.vitals.engine.VitalsBpCalibration
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFace
import com.ayuvo.health.vitals.engine.VitalsFaceInput
import com.ayuvo.health.vitals.engine.VitalsFingerInput
import com.ayuvo.health.vitals.engine.VitalsJson
import com.ayuvo.health.vitals.engine.VitalsMath
import com.ayuvo.health.vitals.engine.VitalsResearch
import com.ayuvo.health.vitals.engine.VitalsSignal
import com.ayuvo.health.vitals.engine.VitalsSpo2Calibration
import com.ayuvo.health.vitals.engine.VitalsSynth
import com.ayuvo.health.vitals.engine.VitalsSynthSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates the shared vitals contract from the Gradle unit-test working directory (android/app). */
object VitalsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/vitals/$relative", "../shared/vitals/$relative", "shared/vitals/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/vitals/$relative", "app/src/main/assets/vitals/$relative").map(::File).firstOrNull { it.exists() }

    val config: VitalsConfig by lazy { VitalsConfig.parse(shared("vitals_config.json")!!.readText()) }
}

/** Runs `shared/vitals/test-vectors/<file>` through the Kotlin engine (dispatch mirrors `vitals_reference.FUNCTIONS`). */
object VitalsVectors {
    val json = Json { isLenient = false }

    /** Every vector file and the function it must declare; the coverage test pins this to the folder. */
    val FILES = linkedMapOf(
        "analyze_face.json" to "analyze_face",
        "analyze_finger.json" to "analyze_finger",
        "bandpass.json" to "bandpass",
        "baselines.json" to "baselines",
        "bp_research.json" to "bp_research",
        "compare.json" to "compare",
        "effective_config.json" to "effective_config",
        "finger_detect.json" to "finger_detect",
        "hrv_freq.json" to "hrv_freq",
        "insights_fallback.json" to "insights_fallback",
        "jacobi.json" to "jacobi",
        "pulses.json" to "pulses",
        "resp_rate.json" to "resp_rate",
        "scan_control.json" to "scan_control",
        "scan_indicator.json" to "scan_indicator",
        "spectrum.json" to "spectrum",
        "spo2_estimate.json" to "spo2_estimate",
        "synth.json" to "synth",
        "validation_stats.json" to "validation_stats"
    )

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>, val slowest: Pair<String, Long>?)

    fun run(file: String): Outcome {
        val f = VitalsTestFiles.shared("test-vectors/$file") ?: run { fail("shared/vitals/test-vectors/$file missing"); error("") }
        val root = json.parseToJsonElement(f.readText()) as JsonObject
        val function = str(root["function"]) ?: error("$file has no function")
        val cases = root["cases"] as JsonArray
        val failures = mutableListOf<String>()
        var passed = 0
        var slowest: Pair<String, Long>? = null
        for (c in cases) {
            val case = c as JsonObject
            val name = str(case["name"]) ?: "?"
            val started = System.nanoTime()
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (slowest == null || ms > slowest.second) slowest = name to ms
            val expected = case["expected"]!!
            var diff = RecordsVectors.diff(expected, actual, "$")
            val known = KNOWN_LIBM_DIVERGENCES["$file/$name"]
            if (diff != null && known != null) {
                // Everything except the one documented leaf must still match exactly.
                val patched = replaceAt(actual, known, at(expected, known))
                if (RecordsVectors.diff(expected, patched, "$") == null) {
                    println("KNOWN DIVERGENCE vitals/$file/$name at ${known.joinToString(".")}: expected ${at(expected, known)}, got ${at(actual, known)}")
                    diff = null
                }
            }
            if (diff == null) passed++ else failures += "$name: $diff"
        }
        return Outcome(file, passed, cases.size, failures, slowest)
    }

    /**
     * Leaves where the reference is not portable at the ulp level. Empty: FastICA used tanh (1-ulp libm differences
     * across Darwin and fdlibm moved an unconverged IC), and the reference now uses the cube nonlinearity instead.
     * Keep the mechanism for future reports; an entry must name exactly one leaf and still diff everything else.
     */
    val KNOWN_LIBM_DIVERGENCES: Map<String, List<String>> = emptyMap()

    private fun at(e: JsonElement, path: List<String>): JsonElement =
        path.fold(e) { acc, k -> (acc as JsonObject)[k] ?: JsonNull }

    private fun replaceAt(e: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val o = e as? JsonObject ?: return e
        val child = o[path[0]] ?: return e
        return JsonObject(LinkedHashMap(o).also { it[path[0]] = replaceAt(child, path.drop(1), value) })
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS vitals/${o.file}: ${o.passed}/${o.total} (slowest ${o.slowest?.first} ${o.slowest?.second} ms)")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- Decoding --------------------------------------------------------------------------------

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun num(e: JsonElement?): Double? = VitalsConfig.num(e)
    private fun bool(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.booleanOrNull == true
    private fun nums(e: JsonElement?): DoubleArray = (e as JsonArray).map { num(it)!! }.toDoubleArray()
    private fun objs(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().map { it as JsonObject }

    private fun features(e: JsonElement?): Map<String, Double>? =
        (e as? JsonObject)?.entries?.associate { (k, v) -> k to num(v)!! }

    private fun spo2Cals(e: JsonElement?) = objs(e).map { VitalsSpo2Calibration(num(it["ratio"])!!, num(it["spo2"])!!) }

    private fun bpCals(e: JsonElement?) = objs(e).map {
        VitalsBpCalibration(num(it["t_ms"])!!, num(it["scan_gap_min"])!!, features(it["features"]), num(it["sbp"])!!, num(it["dbp"])!!)
    }

    fun runCase(function: String, i: JsonObject): JsonElement {
        val cfg = VitalsTestFiles.config
        val fs = cfg.signal.fs
        return when (function) {
            "analyze_finger" -> {
                val frames = if ("frames" in i) VitalsEngine.parseFingerFrames(i["frames"])
                else VitalsSynth.synthFinger(VitalsSynthSpec.parse(i["synth"] as JsonObject))
                VitalsEngine.analyzeFinger(
                    VitalsFingerInput(
                        frames = frames, experimentalEnabled = bool(i["experimental_enabled"]), researchEnabled = bool(i["research_enabled"]),
                        spo2Calibrations = spo2Cals(i["spo2_calibrations"]), bpCalibrations = bpCals(i["bp_calibrations"]),
                        nowMs = num(i["now_ms"]) ?: 0.0, includeSignals = bool(i["include_signals"])
                    ), cfg
                ).json
            }
            "analyze_face" -> {
                val face = if ("face" in i) VitalsEngine.parseFaceFrames(i["face"] as JsonObject)
                else VitalsSynth.synthFace(VitalsSynthSpec.parse(i["synth"] as JsonObject))
                VitalsEngine.analyzeFace(
                    VitalsFaceInput(face, bool(i["experimental_enabled"]), bool(i["research_enabled"]), bool(i["include_signals"])), cfg
                ).json
            }
            "bandpass" -> VitalsJson.obj(
                "y" to VitalsMath.roundList(VitalsSignal.bandpass(nums(i["x"]), num(i["fs"])!!, num(i["lo"])!!, num(i["hi"])!!), 6)
            )
            "baselines" -> VitalsEngine.baselines(
                objs(i["values"]).map { VitalsEngine.TimedValue(num(it["t_ms"])!!, num(it["value"])!!) },
                num(i["now_ms"])!!, (i["windows_days"] as JsonArray).map { num(it) }, cfg
            )
            "bp_research" -> VitalsResearch.bpResearch(
                features(i["features"]), bpCals(i["calibrations"]), num(i["now_ms"])!!, num(i["quality"])!!, cfg
            ).toJson()
            "compare" -> {
                fun side(o: JsonObject) = VitalsEngine.CompareSide(num(o["hr"]), num(o["rmssd"]), num(o["ibi_mean"]))
                VitalsEngine.compare(side(i["finger"] as JsonObject), side(i["face"] as JsonObject), cfg)
            }
            "effective_config" -> VitalsEngine.effectiveConfig(str(i["device_model"])!!, i["device_overrides"] as? JsonObject, cfg)
            "finger_detect" -> VitalsJson.el(VitalsEngine.parseFingerFrames(i["frames"]).map { VitalsEngine.fingerDetect(it, cfg) })
            "hrv_freq" -> {
                val ibi = nums(i["ibi_ms"])
                val r = VitalsBeats.hrvFreq(ibi, nums(i["ibi_t_ms"]), BooleanArray(ibi.size) { true }, cfg)
                if (r == null) JsonNull
                else VitalsJson.obj(
                    "lf_hf" to VitalsMath.roundTo(r.lfHf, 3), "lf_nu" to VitalsMath.roundTo(r.lfNu, 2), "hf_nu" to VitalsMath.roundTo(r.hfNu, 2)
                )
            }
            "insights_fallback" -> {
                val platform = (i["platform_days"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                    (v as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
                }
                val scans = objs(i["scans"]).map { sc ->
                    VitalsEngine.FallbackScan(
                        str(sc["local_day"])!!, str(sc["mode"])!!, str(sc["context"]), num(sc["quality_score"]), str(sc["reject_reason"]),
                        (sc["metrics"] as JsonObject).mapValues { it.value as JsonObject }
                    )
                }
                VitalsJson.el(VitalsEngine.insightsFallback(platform, scans, str(i["hrv_kind"])!!, cfg))
            }
            "jacobi" -> {
                val m = (i["m"] as JsonArray).map { nums(it) }.toTypedArray()
                val e = VitalsFace.jacobiEigen(m)
                VitalsJson.obj("values" to VitalsMath.roundList(e.values, 6), "vectors" to e.vectors.map { VitalsMath.roundList(it, 6) })
            }
            "pulses" -> {
                val x = nums(i["x"])
                val peaks = VitalsBeats.pulseDetect(x, fs, BooleanArray(x.size), cfg)
                val ibi = VitalsBeats.ibiClean(peaks, cfg)
                val ht = VitalsBeats.hrvTime(ibi.ibiMs, ibi.accepted)
                VitalsJson.obj(
                    "peaks_ms" to peaks.map { VitalsMath.roundTo(it.tMs, 1) },
                    "corr" to peaks.map { VitalsMath.roundTo(it.corr, 3) },
                    "ibi_ms" to ibi.ibiMs, "ibi_quality" to ibi.ibiQuality, "accepted_fraction" to ibi.acceptedFraction,
                    "hrv" to ht?.let {
                        VitalsJson.obj(
                            "mean_nn" to VitalsMath.roundTo(it.meanNn, 1), "n" to it.n, "pnn50" to VitalsMath.roundTo(it.pnn50, 1),
                            "rmssd" to VitalsMath.roundTo(it.rmssd, 1), "sdnn" to VitalsMath.roundTo(it.sdnn, 1)
                        )
                    }
                )
            }
            "resp_rate" -> {
                val series = (i["series"] as JsonArray).map { s -> val a = s as JsonArray; nums(a[0]) to nums(a[1]) }
                val r = VitalsBeats.respRate(series, cfg)
                VitalsJson.obj("value" to VitalsMath.roundTo(r.value, 1), "estimates" to r.estimates, "spread" to VitalsMath.roundTo(r.spread, 2))
            }
            "scan_control" -> VitalsJson.obj("action" to VitalsEngine.scanControl(num(i["elapsed_s"])!!, num(i["quality"]), cfg))
            "scan_indicator" -> {
                val cur = i["current"] as JsonObject
                VitalsEngine.scanIndicator(
                    num(cur["hr"]), num(cur["rmssd"]),
                    objs(i["history"]).map { VitalsEngine.IndicatorScan(num(it["t_ms"])!!, num(it["hr"]), num(it["rmssd"])) },
                    num(i["now_ms"])!!, num(i["quality"])!!, cfg
                )
            }
            "spectrum" -> {
                val s = VitalsSignal.hrSpectrum(nums(i["x"]), fs, cfg)
                VitalsJson.obj("hr_bpm" to VitalsMath.roundTo(s.hrBpm, 2), "snr_db" to VitalsMath.roundTo(s.snrDb, 2))
            }
            "spo2_estimate" -> {
                val r = VitalsResearch.spo2Estimate(num(i["ratio"]), num(i["quality"])!!, spo2Cals(i["calibrations"]), cfg)
                VitalsJson.obj("value" to VitalsMath.roundTo(r.value, 1), "confidence" to VitalsMath.roundTo(r.confidence, 2), "reason" to r.reason)
            }
            "synth" -> {
                val spec = VitalsSynthSpec.parse(i["spec"] as JsonObject)
                if (str(i["kind"]) == "finger") VitalsJson.obj("frames" to VitalsSynth.synthFinger(spec).map { it })
                else VitalsSynth.synthFace(spec).toJson()
            }
            "validation_stats" -> VitalsEngine.validationStats(
                objs(i["pairs"]).map { VitalsEngine.ValidationPair(num(it["measured"])!!, num(it["reference"])!!, num(it["confidence"])) },
                num(i["failures"])?.toInt() ?: 0, cfg
            )
            else -> error("unknown function $function")
        }
    }
}
