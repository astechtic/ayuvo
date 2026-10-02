package com.ayuvo.health.vitals

import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFingerInput
import com.ayuvo.health.vitals.storage.CameraVitalsArchive
import com.ayuvo.health.vitals.storage.VitalCalibration
import com.ayuvo.health.vitals.storage.VitalDeviceProfile
import com.ayuvo.health.vitals.storage.VitalScanRecord
import com.ayuvo.health.vitals.storage.VitalSignal
import com.ayuvo.health.vitals.storage.VitalSignalCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** docs/camera-vitals.md §7.2: the `camera_vitals` codec and merge against `shared/vitals/fixtures/camera-vitals-sample`. */
class CameraVitalsArchiveTest {
    private fun fixture(name: String): String = VitalsTestFiles.shared("fixtures/camera-vitals-sample/$name")!!.readText()

    private fun bundle() = CameraVitalsArchive.read(
        fixture("scans.ndjson"), fixture("signals.ndjson"), fixture("calibrations.ndjson"), fixture("device_profiles.ndjson")
    )

    private val manifest: JsonObject by lazy { Json.parseToJsonElement(fixture("manifest.json")) as JsonObject }

    @Test
    fun theFixtureReadsWithTheManifestCountsAndFields() {
        val b = bundle()
        val counts = manifest["counts"] as JsonObject
        assertEquals("ayuvo-camera-vitals", (manifest["format"] as JsonPrimitive).content)
        assertEquals(CameraVitalsArchive.FORMAT, (manifest["format"] as JsonPrimitive).content)
        assertEquals((counts["scans"] as JsonPrimitive).content.toInt(), b.scans.size)
        assertEquals((counts["signals"] as JsonPrimitive).content.toInt(), b.signalCount)
        assertEquals((counts["calibrations"] as JsonPrimitive).content.toInt(), b.calibrations.size)
        assertEquals((counts["device_profiles"] as JsonPrimitive).content.toInt(), b.deviceProfiles.size)
        assertEquals(0, b.skippedLines)

        val finger = b.scans[0].record
        assertEquals("local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11", finger.id)
        assertEquals(VitalScanRecord.MODE_FINGER, finger.mode)
        assertEquals("8c1d2e3f-4a5b-4c6d-8e7f-901a2b3c4d5e", finger.sessionId)
        assertEquals(java.time.Instant.parse("2026-10-01T07:30:00Z").toEpochMilli(), finger.startMs)
        assertEquals(40_000L, finger.durationMs)
        assertEquals(19_800, finger.tzOffsetS)
        assertEquals("ios", finger.platform)
        assertEquals("iPhone15,2", finger.deviceModel)
        assertEquals(93.9, finger.qualityScore!!, 0.0)
        assertNull(finger.rejectReason)
        assertEquals("Polar H10", (Json.parseToJsonElement(finger.referenceJson!!) as JsonObject)["device"].let { (it as JsonPrimitive).content })
        assertTrue((Json.parseToJsonElement(finger.cameraJson) as JsonObject).containsKey("torch"))
        assertEquals(4, b.scans[0].signals.size)

        val face = b.scans[1].record
        assertEquals(VitalScanRecord.MODE_FACE, face.mode)
        assertEquals("duration_short", face.rejectReason)
        assertNull("a null reference stays NULL", face.referenceJson)
        assertTrue(b.scans[1].signals.isEmpty())

        val cal = b.calibrations.single()
        assertEquals(VitalCalibration.KIND_SPO2, cal.kind)
        assertEquals(finger.id, cal.scanId)
        assertEquals(0.6512, (Json.parseToJsonElement(cal.featuresJson) as JsonObject)["ratio"].let { (it as JsonPrimitive).double }, 0.0)
        assertEquals("back", b.deviceProfiles.single().cameraPosition)
    }

    @Test
    fun theDecodedFrameStatsReproduceTheStoredHeartRate() {
        val signals = bundle().scans[0].signals.associateBy { it.kind }
        assertEquals(setOf("frame_stats", "processed", "mask", "beats"), signals.keys)
        val table = VitalSignalCodec.decode(signals.getValue(VitalSignal.KIND_FRAME_STATS))
        assertEquals(listOf("t_ms", "r", "g", "b", "r_std", "sat_frac"), table.columns)
        val frames = table.rows.map { row -> DoubleArray(row.size) { row[it].toDouble() } }
        val analysis = VitalsEngine.analyzeFinger(VitalsFingerInput(frames), VitalsTestFiles.config)
        val expected = ((manifest["expected"] as JsonObject)["scan1_heart_rate"] as JsonPrimitive).double
        val hr = analysis.value("heart_rate")!!
        assertTrue("re-analysed $hr vs stored $expected", abs(hr - expected) <= 0.5)
        // The other stored signals decode too.
        assertEquals(VitalSignalCodec.decode(signals.getValue(VitalSignal.KIND_PROCESSED)).rows.size, VitalSignalCodec.decode(signals.getValue(VitalSignal.KIND_MASK)).rows.size)
        assertEquals(43, VitalSignalCodec.decode(signals.getValue(VitalSignal.KIND_BEATS)).rows.size)
    }

    @Test
    fun exportThenReadRoundTripsEveryRow() {
        val b = bundle()
        val texts = CameraVitalsArchive.encode(b).associate { it.first to it.second }
        assertEquals(CameraVitalsArchive.ENTRIES, texts.keys.toList())
        assertEquals(mapOf("scans" to 2, "signals" to 4, "calibrations" to 1, "device_profiles" to 1), texts.values.associate { it.second to it.third })
        val back = CameraVitalsArchive.read(
            texts.getValue(CameraVitalsArchive.SCANS).first, texts.getValue(CameraVitalsArchive.SIGNALS).first,
            texts.getValue(CameraVitalsArchive.CALIBRATIONS).first, texts.getValue(CameraVitalsArchive.DEVICE_PROFILES).first
        )
        assertEquals(b.scans.map { it.record }, back.scans.map { it.record })
        assertEquals(b.scans.map { it.signals }, back.scans.map { it.signals })
        assertEquals(b.calibrations, back.calibrations)
        assertEquals(b.deviceProfiles, back.deviceProfiles)
        // Times are ISO-8601 UTC with milliseconds.
        assertTrue(texts.getValue(CameraVitalsArchive.SCANS).first.contains("\"start\":\"2026-10-01T07:30:00.000Z\""))
    }

    @Test
    fun mergeSkipsKnownAndTombstonedIdsWithTheirSignalsAndKeepsTheNewerProfile() {
        val b = bundle()
        val first = CameraVitalsArchive.mergePlan(b, emptySet(), emptySet(), emptyMap())
        assertEquals(2, first.scans.size)
        assertEquals(1, first.calibrations.size)
        assertEquals(1, first.deviceProfiles.size)

        // Importing twice adds nothing: every id now exists.
        val ids = b.scans.map { it.record.id }.toSet()
        val profileKey = CameraVitalsArchive.profileKey("iPhone15,2", "back")
        val again = CameraVitalsArchive.mergePlan(b, ids, b.calibrations.map { it.id }.toSet(), mapOf(profileKey to b.deviceProfiles[0].updatedMs))
        assertTrue(again.scans.isEmpty() && again.calibrations.isEmpty() && again.deviceProfiles.isEmpty())
        assertEquals(2, again.skippedScans)

        // A tombstoned local id (still in the table) is skipped together with its signals.
        val tomb = CameraVitalsArchive.mergePlan(b, setOf(b.scans[0].record.id), emptySet(), emptyMap())
        assertEquals(listOf(b.scans[1].record.id), tomb.scans.map { it.record.id })
        assertEquals(0, tomb.scans.sumOf { it.signals.size })

        // Device profiles: the newer `updated` wins.
        val newer = CameraVitalsArchive.mergePlan(b, emptySet(), emptySet(), mapOf(profileKey to b.deviceProfiles[0].updatedMs + 1))
        assertTrue(newer.deviceProfiles.isEmpty())
        val older = CameraVitalsArchive.mergePlan(b, emptySet(), emptySet(), mapOf(profileKey to 0L))
        assertEquals(listOf(VitalDeviceProfile("iPhone15,2", "back", b.deviceProfiles[0].capabilityJson, b.deviceProfiles[0].updatedMs)), older.deviceProfiles)
    }

    @Test
    fun badLinesAreSkippedOneByOne() {
        val scans = fixture("scans.ndjson") + "not json\n{\"id\":\"local:x\"}\n"
        val signals = fixture("signals.ndjson") + "{\"scan_id\":\"local:unknown\",\"kind\":\"beats\",\"data_base64\":\"AA==\"}\n"
        val b = CameraVitalsArchive.read(scans, signals, null, null)
        assertEquals(2, b.scans.size)
        assertEquals(4, b.signalCount)
        assertEquals(3, b.skippedLines)
    }
}
