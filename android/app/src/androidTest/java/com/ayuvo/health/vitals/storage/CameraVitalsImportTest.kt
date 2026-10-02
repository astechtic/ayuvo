package com.ayuvo.health.vitals.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFingerInput
import com.ayuvo.health.vitals.session.validValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * docs/camera-vitals.md §7.2 against real SQLite: import `shared/vitals/fixtures/camera-vitals-sample`, re-analyse its
 * stored frame_stats, import twice, skip a tombstoned id, and round-trip export → import into an empty store.
 */
@RunWith(AndroidJUnit4::class)
class CameraVitalsImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var helper: HealthDatabase
    private lateinit var repo: VitalScanRepository

    @Before
    fun setUp() {
        HealthDatabase.deleteDatabaseFiles(context)
        helper = HealthDatabase(context)
        repo = VitalScanRepository(helper) { 1_000L }
    }

    @After
    fun tearDown() {
        helper.close()
        HealthDatabase.deleteDatabaseFiles(context)
    }

    private fun asset(name: String): String =
        instrumentation.context.assets.open("camera-vitals-sample/$name").bufferedReader().use { it.readText() }

    private fun fixture() = CameraVitalsArchive.read(
        asset("scans.ndjson"), asset("signals.ndjson"), asset("calibrations.ndjson"), asset("device_profiles.ndjson")
    )

    private fun count(sql: String): Long = helper.readableDatabase.compileStatement(sql).simpleQueryForLong()

    @Test
    fun importsTheFixtureAndReproducesTheStoredHeartRate() = runBlocking {
        val result = repo.importArchive(fixture())
        assertEquals(CameraVitalsArchive.ImportResult(2, 4, 1, 1, 0, 0), result)
        val finger = repo.scan("local:3f2b6c1e-9a4d-4e0b-8f2e-1c5a7d9e0b11")!!
        assertEquals("iPhone15,2", finger.deviceModel)
        assertEquals("ios", finger.platform)
        assertEquals(64.0, finger.validValue("heart_rate")!!, 0.0)
        assertEquals(1, repo.calibrations(VitalCalibration.KIND_SPO2, "iPhone15,2").size)
        assertEquals("back", repo.deviceProfiles().single().cameraPosition)
        // Scans never reach the Health Connect mirror.
        assertEquals(0L, count("SELECT COUNT(*) FROM health_samples"))

        val frameStats = repo.signals(finger.id).first { it.kind == VitalSignal.KIND_FRAME_STATS }
        val frames = VitalSignalCodec.decode(frameStats).rows.map { r -> DoubleArray(r.size) { r[it].toDouble() } }
        val cfg = VitalsConfig.parse(context.assets.open(VitalsConfig.ASSET_PATH).bufferedReader().use { it.readText() })
        val hr = VitalsEngine.analyzeFinger(VitalsFingerInput(frames), cfg).value("heart_rate")!!
        assertTrue("re-analysed $hr", abs(hr - 64.0) <= 0.5)
    }

    @Test
    fun importingTwiceAddsNothing() = runBlocking {
        repo.importArchive(fixture())
        val again = repo.importArchive(fixture())
        assertEquals(CameraVitalsArchive.ImportResult(0, 0, 0, 0, 2, 1), again)
        assertEquals(2L, count("SELECT COUNT(*) FROM vital_scans"))
        assertEquals(4L, count("SELECT COUNT(*) FROM vital_scan_signals"))
        assertEquals(1L, count("SELECT COUNT(*) FROM vital_calibrations"))
    }

    @Test
    fun aTombstonedIdIsSkippedWithItsSignals() = runBlocking {
        val bundle = fixture()
        val first = bundle.scans[0]
        repo.saveScan(first.record.copy(platform = "android"), first.signals)
        assertTrue(repo.deleteScan(first.record.id))
        val result = repo.importArchive(bundle)
        assertEquals(1, result.scans)
        assertEquals(0, result.signals)
        assertTrue(repo.scan(first.record.id)!!.deleted)
        assertEquals(0L, count("SELECT COUNT(*) FROM vital_scan_signals"))
    }

    @Test
    fun exportThenImportIntoAnEmptyStoreRoundTrips() = runBlocking {
        repo.importArchive(fixture())
        val exported = repo.exportBundle(includeSignals = true)
        val texts = CameraVitalsArchive.encode(exported).associate { it.first to it.second.first }
        helper.close()
        HealthDatabase.deleteDatabaseFiles(context)
        helper = HealthDatabase(context)
        repo = VitalScanRepository(helper) { 2_000L }
        val result = repo.importArchive(
            CameraVitalsArchive.read(
                texts[CameraVitalsArchive.SCANS], texts[CameraVitalsArchive.SIGNALS],
                texts[CameraVitalsArchive.CALIBRATIONS], texts[CameraVitalsArchive.DEVICE_PROFILES]
            )
        )
        assertEquals(CameraVitalsArchive.ImportResult(2, 4, 1, 1, 0, 0), result)
        val reexported = repo.exportBundle(includeSignals = true)
        assertEquals(exported.scans.map { it.record }, reexported.scans.map { it.record })
        assertEquals(exported.scans.map { it.signals }, reexported.scans.map { it.signals })
        assertEquals(exported.calibrations, reexported.calibrations)
        assertEquals(exported.deviceProfiles, reexported.deviceProfiles)
        // Keep raw signals off: the export carries no signal rows.
        assertEquals(0, repo.exportBundle(includeSignals = false).signalCount)
    }
}
