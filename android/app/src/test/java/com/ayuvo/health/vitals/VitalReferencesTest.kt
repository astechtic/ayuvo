package com.ayuvo.health.vitals

import com.ayuvo.health.vitals.session.CoachCameraScans
import com.ayuvo.health.vitals.session.VitalCalibrationHooks
import com.ayuvo.health.vitals.session.VitalReference
import com.ayuvo.health.vitals.session.VitalsCompareFlow
import com.ayuvo.health.vitals.session.VitalsValidation
import com.ayuvo.health.vitals.storage.VitalCalibration
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Compare, reference readings, calibration hooks, validation and the Coach lines (docs/camera-vitals.md §6, §7, §7.1). */
class VitalReferencesTest {
    private val cfg = VitalsTestFiles.config
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    private fun env(value: Double?, cls: String = "measured", confidence: Double? = 0.9, extra: String = "") =
        if (value == null) """{"status":"unavailable","value":null,"classification":"$cls","reason":"low_quality"$extra}"""
        else """{"status":"valid","value":$value,"classification":"$cls","confidence":$confidence$extra}"""

    private fun scan(
        id: String,
        mode: String = VitalScanRecord.MODE_FINGER,
        startMs: Long = now - day,
        hr: Double? = 64.0,
        rmssd: Double? = 42.0,
        ibi: Double? = 937.5,
        resp: Double? = 14.0,
        spo2: String = env(null, "experimental"),
        bp: String = env(null, "research"),
        reference: String? = null,
        reject: String? = null,
        session: String? = null,
        updatedMs: Long = startMs + 60_000
    ) = VitalScanRecord(
        id = id, mode = mode, sessionId = session, startMs = startMs, endMs = startMs + 60_000, tzOffsetS = 0, localDay = "2026-09-20",
        durationMs = 60_000, platform = "android", deviceModel = "Google Pixel 7", cameraJson = "{}", qualityScore = 82.0,
        rejectReason = reject, qualityJson = """{"score":82.0,"grade":"good"}""", algoVersion = 1, referenceJson = reference, updatedMs = updatedMs,
        resultsJson = """{"metrics":{"heart_rate":${env(hr)},"hrv_rmssd":${env(rmssd, "calculated", 0.7)},"ibi_mean":${env(ibi, "calculated")},""" +
            """"respiratory_rate":${env(resp, "estimated", 0.5)},"spo2":$spo2,"blood_pressure":$bp}}"""
    )

    // -- Compare ------------------------------------------------------------------------------------------------

    @Test
    fun compareUsesTheEngineAndNeverPicksAWinner() {
        val finger = scan("f", hr = 64.0, rmssd = 42.0)
        val agree = VitalsCompareFlow.compare(finger, scan("c", VitalScanRecord.MODE_FACE, hr = 66.0, rmssd = 50.0, ibi = 909.1), cfg)
        assertEquals("consistent", (agree["status"] as JsonPrimitive).content)
        assertEquals(2.0, (agree["hr_diff"] as JsonPrimitive).double, 0.0)
        assertEquals(8.0, (agree["rmssd_diff"] as JsonPrimitive).double, 0.0)
        assertEquals(28.4, (agree["ibi_mean_diff"] as JsonPrimitive).double, 0.0)
        val apart = VitalsCompareFlow.compare(finger, scan("c", VitalScanRecord.MODE_FACE, hr = 71.0), cfg)
        assertEquals("inconsistent", (apart["status"] as JsonPrimitive).content)
        val missing = VitalsCompareFlow.compare(finger, scan("c", VitalScanRecord.MODE_FACE, hr = null), cfg)
        assertEquals("incomplete", (missing["status"] as JsonPrimitive).content)
        assertEquals("incomplete", (VitalsCompareFlow.compare(finger, null, cfg)["status"] as JsonPrimitive).content)
    }

    @Test
    fun theFaceLegLinksOnlyWithinTheSessionGap() {
        val gapMs = (cfg.compare.maxSessionGapS * 1000).toLong()
        assertEquals(180_000L, gapMs)
        assertTrue(VitalsCompareFlow.linked(fingerSavedMs = 1_000, faceStartMs = 1_000 + gapMs, cfg = cfg))
        assertFalse(VitalsCompareFlow.linked(fingerSavedMs = 1_000, faceStartMs = 1_001 + gapMs, cfg = cfg))
    }

    // -- Reference readings and calibration hooks ------------------------------------------------------------------

    @Test
    fun referenceJsonUsesTheEvalKeysAndRoundTrips() {
        val ref = VitalReference(heartRate = 63.0, rmssd = 40.0, respiratoryRate = 14.5, spo2 = 98.0, systolic = 118.0, diastolic = 76.0, device = " Polar H10 ")
        val o = Json.parseToJsonElement(ref.toJsonText()!!) as JsonObject
        assertEquals(listOf("heart_rate", "hrv_rmssd", "respiratory_rate", "spo2", "blood_pressure", "device"), o.keys.toList())
        assertEquals(JsonArray(listOf(JsonPrimitive(118.0), JsonPrimitive(76.0))), o["blood_pressure"])
        assertEquals("Polar H10", (o["device"] as JsonPrimitive).content)
        assertEquals(ref.copy(device = "Polar H10"), VitalReference.parse(ref.toJsonText()))
        assertNull("nothing entered clears the reference", VitalReference(device = "strap").toJsonText())
        assertEquals("blood_pressure", VitalReference(systolic = 120.0).invalidField())
        assertEquals("blood_pressure", VitalReference(systolic = 80.0, diastolic = 90.0).invalidField())
        assertEquals("heart_rate", VitalReference(heartRate = 400.0).invalidField())
        assertNull(ref.invalidField())
        // The iOS fixture's reference (heart_rate + device only) reads too.
        assertEquals(63.0, VitalReference.parse("""{"device":"Polar H10","heart_rate":63}""")!!.heartRate)
    }

    @Test
    fun calibrationHooksNeedAFingerScanTheValueAndTheToggle() {
        val withRatio = scan("f", spo2 = env(null, "experimental", extra = ""","ratio":0.6512"""))
        assertEquals(0.6512, VitalCalibrationHooks.spo2Ratio(withRatio, experimentalEnabled = true)!!, 0.0)
        assertNull(VitalCalibrationHooks.spo2Ratio(withRatio, experimentalEnabled = false))
        assertNull(VitalCalibrationHooks.spo2Ratio(withRatio.copy(mode = VitalScanRecord.MODE_FACE), experimentalEnabled = true))
        assertNull(VitalCalibrationHooks.spo2Ratio(scan("n"), experimentalEnabled = true))

        val features = ""","features":{"rise_ms":120.0,"decay_ms":500.0,"width50_ms":300.0,"width25_ms":420.0,"hr":64.0}"""
        val withFeatures = scan("f", bp = env(null, "research", extra = features))
        assertEquals(5, VitalCalibrationHooks.bpFeatures(withFeatures, researchEnabled = true)!!.size)
        assertNull(VitalCalibrationHooks.bpFeatures(withFeatures, researchEnabled = false))

        val spo2 = VitalCalibrationHooks.spo2Calibration(withRatio, 0.6512, 98.0, now, id = "local:c1")
        assertEquals(VitalCalibration.KIND_SPO2, spo2.kind)
        assertEquals("Google Pixel 7", spo2.deviceModel)
        assertEquals("f", spo2.scanId)
        assertEquals("""{"spo2":98.0}""", spo2.referenceJson)
        assertEquals("""{"ratio":0.6512}""", spo2.featuresJson)
        assertEquals(listOf(com.ayuvo.health.vitals.engine.VitalsSpo2Calibration(0.6512, 98.0)), VitalCalibrationHooks.spo2Inputs(listOf(spo2)))

        val bp = VitalCalibrationHooks.bpCalibration(withFeatures, VitalCalibrationHooks.bpFeatures(withFeatures, true)!!, 118.0, 76.0, 2.0, now, id = "local:c2")
        assertEquals(VitalCalibration.KIND_BP, bp.kind)
        assertEquals("""{"sbp":118.0,"dbp":76.0,"scan_gap_min":2.0}""", bp.referenceJson)
        val input = VitalCalibrationHooks.bpInputs(listOf(bp)).single()
        assertEquals(2.0, input.scanGapMin, 0.0)
        assertEquals(120.0, input.features!!.getValue("rise_ms"), 0.0)
        assertEquals(now.toDouble(), input.tMs, 0.0)
    }

    // -- Validation -----------------------------------------------------------------------------------------------

    @Test
    fun validationStatsPerModeAndMetricCountFailures() {
        val scans = listOf(
            scan("a", hr = 64.0, reference = """{"heart_rate":63,"blood_pressure":[118,76]}"""),
            scan("b", hr = 70.0, reference = """{"heart_rate":72}"""),
            scan("c", hr = null, reference = """{"heart_rate":66}"""),
            scan("d", hr = 80.0),
            scan("e", VitalScanRecord.MODE_FACE, hr = 61.0, reference = """{"heart_rate":60,"hrv_rmssd":40}""")
        )
        val rows = VitalsValidation.rows(scans, cfg)
        assertEquals(
            listOf("finger_ppg/heart_rate", "finger_ppg/blood_pressure", "face_rppg/heart_rate", "face_rppg/hrv_rmssd"),
            rows.map { "${it.mode}/${it.metric}" }
        )
        val hr = rows[0]
        assertEquals(2, hr.n)
        assertEquals(1.5, hr.mae!!, 1e-9)
        assertEquals(-0.5, hr.bias!!, 1e-9)
        assertEquals(0.333, hr.failureRate!!, 1e-9)
        assertEquals(listOf("high"), hr.byConfidence.map { it.first })
        // BP had a cuff reference but no estimate: one failure, no pairs.
        assertEquals(0, rows[1].n)
        assertEquals(1.0, rows[1].failureRate!!, 0.0)
        assertNull(rows[1].mae)
    }

    @Test
    fun theDatasetHasOnlyReferencedScansInTheEvalFormat() {
        val scans = listOf(scan("a", reference = """{"heart_rate":63,"device":"Polar H10"}"""), scan("b"), scan("c", reference = "{}"))
        val doc = VitalsValidation.dataset(scans, "2026-10-02T10:00:00Z", "1.0")
        assertEquals("ayuvo-vitals-validation", (doc["format"] as JsonPrimitive).content)
        assertEquals("1", (doc["version"] as JsonPrimitive).content)
        val rows = doc["scans"] as JsonArray
        assertEquals(1, rows.size)
        val row = rows[0] as JsonObject
        for (k in listOf("id", "mode", "device_model", "quality", "metrics", "reference")) assertTrue(k, k in row)
        assertEquals("valid", (((row["metrics"] as JsonObject)["heart_rate"] as JsonObject)["status"] as JsonPrimitive).content)
        assertEquals(VitalsValidation.FILE_NAME, "ayuvo-vitals-validation.json")
    }

    // -- Coach ----------------------------------------------------------------------------------------------------

    @Test
    fun coachLinesListRecentValidScansWithTheirLabels() {
        val spo2 = env(97.0, "experimental", 0.6)
        val bp = env(118.0, "research", 0.4, extra = ""","diastolic":76.0""")
        val scans = (0 until 7).map { i -> scan("s$i", startMs = now - (i + 1) * 3_600_000L, spo2 = spo2, bp = bp) } +
            scan("old", startMs = now - 8 * day) + scan("rejected", startMs = now - 60_000, reject = "motion")
        val off = CoachCameraScans.promptLines(scans, now, experimentalEnabled = false, researchEnabled = false, cfg = cfg, zone = ZoneOffset.UTC)
        val scanLines = off.filter { it.startsWith("- ") }
        assertEquals(CoachCameraScans.MAX_SCANS, scanLines.size)
        assertTrue(off.any { it.startsWith("## Camera measurements") })
        val first = scanLines.first()
        assertTrue(first, first.contains("finger scan"))
        assertTrue(first, first.contains("HR 64 bpm (measured)"))
        assertTrue(first, first.contains("RMSSD 42 ms (calculated)"))
        assertTrue(first, first.contains("respiratory rate 14.0 /min (estimated)"))
        assertTrue(first, first.contains("signal quality good"))
        assertFalse(first, first.contains("SpO2"))
        assertFalse(first, first.contains("BP "))
        assertTrue(off.none { it.contains("rejected") })

        val on = CoachCameraScans.promptLines(scans, now, experimentalEnabled = true, researchEnabled = true, cfg = cfg, zone = ZoneOffset.UTC)
        val line = on.first { it.startsWith("- ") }
        assertTrue(line, line.contains("SpO2 97 % (${CoachCameraScans.SPO2_LABEL})"))
        assertTrue(line, line.contains("BP 118/76 mmHg (${CoachCameraScans.BP_LABEL})"))
        // Face scans never carry SpO2 or BP, even with the toggles on.
        val face = CoachCameraScans.line(scan("x", VitalScanRecord.MODE_FACE, spo2 = spo2, bp = bp), true, true, cfg, ZoneOffset.UTC)
        assertTrue(face, face.contains("face scan") && !face.contains("SpO2") && !face.contains("BP "))
        assertTrue(CoachCameraScans.promptLines(emptyList(), now, true, true, cfg).isEmpty())
    }
}
