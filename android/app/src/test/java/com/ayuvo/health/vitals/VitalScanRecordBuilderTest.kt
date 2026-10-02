package com.ayuvo.health.vitals

import com.ayuvo.health.vitals.camera.ReplayFrameSource
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFingerInput
import com.ayuvo.health.vitals.engine.VitalsJson
import com.ayuvo.health.vitals.session.ScanSession
import com.ayuvo.health.vitals.session.VitalScanRecordBuilder
import com.ayuvo.health.vitals.session.VitalsAnalysisOptions
import com.ayuvo.health.vitals.session.VitalsAnalyzer
import com.ayuvo.health.vitals.storage.VitalScanRecord
import com.ayuvo.health.vitals.storage.VitalSignal
import com.ayuvo.health.vitals.storage.VitalSignalCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/** docs/camera-vitals.md §7.1 "Saved record" and "Signals". */
class VitalScanRecordBuilderTest {
    private val cfg = VitalsTestFiles.config
    private val zone: ZoneId = ZoneOffset.ofHours(2)
    private val json = Json

    /** The good finger replay as one 60 s scan snapshot, buffered the way ScanSession does. */
    private val input: ScanSession.ScanInput by lazy {
        val frames = ReplayFrameSource.frames(VitalsMode.FINGER, cfg).map { (it as com.ayuvo.health.vitals.camera.FingerFrame).row }
            .filter { it[0] in 2000.0..62000.0 }
        ScanSession.ScanInput(VitalsMode.FINGER, frames, emptyList(), cfg.face.rois)
    }
    private val analysis by lazy { VitalsAnalyzer.analyze(input, VitalsAnalysisOptions(), cfg) }

    private fun build(keep: Boolean, history: List<VitalScanRecord> = emptyList(), start: Long = 1_780_000_000_000L) =
        VitalScanRecordBuilder.build(
            VitalScanRecordBuilder.Input(
                mode = VitalsMode.FINGER, input = input, analysis = analysis, startMs = start, zone = zone,
                deviceModel = "Google Pixel 7", cameraJson = ReplayFrameSource.replayCameraJson(VitalsMode.FINGER, input.size, input.durationS),
                context = VitalScanRecord.CONTEXT_RESTING, sessionId = null, keepSignals = keep, history = history, nowMs = start + 70_000,
                id = "local:test"
            ),
            cfg
        )

    private fun obj(text: String) = json.parseToJsonElement(text) as JsonObject

    @Test
    fun recordFollowsTheSavedRecordContract() {
        val p = build(keep = true)
        val r = p.record
        assertEquals("finger_ppg", r.mode)
        assertEquals("android", r.platform)
        assertEquals(7200, r.tzOffsetS)
        assertEquals(r.startMs + r.durationMs, r.endMs)
        assertEquals(60_000.0, r.durationMs.toDouble(), 100.0)
        assertEquals(cfg.algoVersion, r.algoVersion)
        assertNull(r.rejectReason)
        assertEquals(analysis.qualityScore, r.qualityScore!!, 0.0)
        // results_json = engine output minus quality and signals, plus indicators.
        val results = obj(r.resultsJson)
        assertFalse(results.containsKey("quality"))
        assertFalse(results.containsKey("signals"))
        assertEquals(analysis.json["metrics"], results["metrics"])
        assertEquals(analysis.json["ibi"], results["ibi"])
        val indicators = results["indicators"] as JsonObject
        assertEquals(setOf("recovery_indicator", "stress_indicator"), indicators.keys)
        // quality_json = engine quality.
        assertEquals(analysis.quality, obj(r.qualityJson))
        val camera = obj(r.cameraJson)
        assertEquals(
            setOf("position", "lens", "width", "height", "target_fps", "achieved_fps", "exposure_ms", "iso", "white_balance_locked", "torch"),
            camera.keys
        )
        assertEquals(30.0, (camera["achieved_fps"] as JsonPrimitive).double, 0.5)
    }

    @Test
    fun indicatorsAreUnavailableWithoutHistory() {
        val ind = build(keep = false).indicators
        for (k in listOf("recovery_indicator", "stress_indicator")) {
            val env = ind[k] as JsonObject
            assertEquals("estimated", (env["classification"] as JsonPrimitive).content)
            assertEquals("unavailable", (env["status"] as JsonPrimitive).content)
            assertEquals("short_history", (env["reason"] as JsonPrimitive).content)
        }
    }

    @Test
    fun indicatorsUseEarlierValidSameModeScans() {
        val start = 1_780_000_000_000L
        fun past(i: Int, mode: String = VitalScanRecord.MODE_FINGER, reject: String? = null, hr: Double = 64.0 + i, rmssd: Double = 40.0 + i) =
            VitalScanRecord(
                id = "local:$mode-$i", mode = mode, startMs = start - (i + 1) * 86_400_000L, endMs = start - (i + 1) * 86_400_000L + 60_000,
                tzOffsetS = 7200, localDay = "2026-06-01", durationMs = 60_000, platform = "android", deviceModel = "x", cameraJson = "{}",
                qualityScore = 80.0, rejectReason = reject, qualityJson = "{}",
                resultsJson = VitalsJson.obj(
                    "metrics" to VitalsJson.obj(
                        "heart_rate" to VitalsJson.obj("value" to hr, "status" to "valid"),
                        "hrv_rmssd" to VitalsJson.obj("value" to rmssd, "status" to "valid")
                    )
                ).toString(),
                algoVersion = 1, updatedMs = 0
            )
        // Four valid finger scans are not enough (min_history 5): face scans and rejected scans never count.
        val few = (0 until 4).map { past(it) } + (0 until 6).map { past(it, mode = VitalScanRecord.MODE_FACE) } + past(9, reject = "motion")
        val none = build(keep = false, history = few, start = start).indicators["recovery_indicator"] as JsonObject
        assertEquals("short_history", (none["reason"] as JsonPrimitive).content)
        val enough = (0 until 6).map { past(it) }
        val ind = build(keep = false, history = enough, start = start).indicators
        val rec = ind["recovery_indicator"] as JsonObject
        val stress = ind["stress_indicator"] as JsonObject
        assertEquals("valid", (rec["status"] as JsonPrimitive).content)
        assertEquals("estimated", (rec["classification"] as JsonPrimitive).content)
        assertEquals(100.0, (rec["value"] as JsonPrimitive).double + (stress["value"] as JsonPrimitive).double, 0.0)
        val expected = VitalsEngine.scanIndicator(
            analysis.value("heart_rate"), analysis.value("hrv_rmssd"),
            enough.map { VitalsEngine.IndicatorScan(it.startMs.toDouble(), 64.0 + enough.indexOf(it), 40.0 + enough.indexOf(it)) },
            start.toDouble(), analysis.qualityScore, cfg
        )
        assertEquals((expected["recovery"] as JsonPrimitive).double, (rec["value"] as JsonPrimitive).double, 0.0)
        assertEquals(6, (rec["n"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun signalsOnlyWhenKeepIsOn() {
        assertTrue(build(keep = false).signals.isEmpty())
        val signals = build(keep = true).signals
        assertEquals(listOf("frame_stats", "processed", "mask", "beats"), signals.map { it.kind })
        val stats = VitalSignalCodec.decode(signals.first { it.kind == VitalSignal.KIND_FRAME_STATS })
        assertEquals(VitalSignalCodec.FINGER_COLUMNS, stats.columns)
        assertEquals(input.size, stats.rows.size)
        // t_ms relative to the first frame; values are the engine input to float32 precision.
        assertEquals(0f, stats.rows[0][0], 0f)
        assertEquals(input.finger[10][1].toFloat(), stats.rows[10][1], 0f)
        // Reprocessing the stored frame_stats reproduces the result.
        val again = VitalsEngine.analyzeFinger(VitalsFingerInput(stats.rows.map { r -> DoubleArray(6) { r[it].toDouble() } }), cfg)
        assertEquals(analysis.value("heart_rate")!!, again.value("heart_rate")!!, 0.5)
        val processed = VitalSignalCodec.decode(signals.first { it.kind == VitalSignal.KIND_PROCESSED })
        assertEquals(listOf("x"), processed.columns)
        assertEquals(analysis.signals!!.processed.size, processed.rows.size)
    }
}
