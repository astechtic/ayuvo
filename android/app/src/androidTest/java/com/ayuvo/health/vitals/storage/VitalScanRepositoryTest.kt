package com.ayuvo.health.vitals.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.data.health.SqliteHealthDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VitalScanRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: HealthDatabase
    private lateinit var repo: VitalScanRepository
    private var clock = 10_000L

    @Before
    fun setUp() {
        HealthDatabase.deleteDatabaseFiles(context)
        helper = HealthDatabase(context)
        repo = VitalScanRepository(helper) { clock }
    }

    @After
    fun tearDown() {
        helper.close()
        HealthDatabase.deleteDatabaseFiles(context)
    }

    private fun scan(id: String, startMs: Long, mode: String = VitalScanRecord.MODE_FINGER, updatedMs: Long = 1) = VitalScanRecord(
        id = id, mode = mode, sessionId = null, startMs = startMs, endMs = startMs + 60_000, tzOffsetS = 3600, localDay = "2026-10-02",
        durationMs = 60_000, platform = VitalScanRecord.PLATFORM_ANDROID, deviceModel = "Google Pixel 7",
        cameraJson = """{"position":"back","torch":true}""", qualityScore = 82.5, rejectReason = null,
        qualityJson = """{"score":82.5}""", resultsJson = """{"metrics":{}}""", algoVersion = 1, updatedMs = updatedMs
    )

    private fun signals() = listOf(
        VitalSignalCodec.encodeColumns(VitalSignal.KIND_PROCESSED, 30.0, listOf("x" to DoubleArray(300) { kotlin.math.sin(it / 4.0) })),
        VitalSignalCodec.encodeColumns(VitalSignal.KIND_BEATS, null, listOf("t_ms" to doubleArrayOf(400.0, 1210.5, 2033.3)))
    )

    private fun count(sql: String): Long = helper.readableDatabase.compileStatement(sql).simpleQueryForLong()

    @Test
    fun saveRoundTripsEveryColumnAndSignals() = runBlocking {
        val record = scan("local:a", 1_000).copy(sessionId = "s1", context = VitalScanRecord.CONTEXT_AFTER_ACTIVITY, referenceJson = """{"hr":70}""")
        assertTrue(repo.saveScan(record, signals()))
        assertEquals(record, repo.scan("local:a"))
        val stored = repo.signals("local:a")
        assertEquals(listOf("beats", "processed"), stored.map { it.kind })
        assertEquals(signals().sortedBy { it.kind }, stored)
        assertArrayEquals(floatArrayOf(400f, 1210.5f, 2033.3f), VitalSignalCodec.decode(stored[0]).column("t_ms"), 0f)
        // Scans never touch the Health Connect mirror.
        assertEquals(0L, count("SELECT COUNT(*) FROM health_samples"))
        assertEquals(0L, count("SELECT COUNT(*) FROM health_daily_rollups"))
    }

    @Test
    fun resaveUpdatesInPlaceAndReplacesSignals() = runBlocking {
        repo.saveScan(scan("local:a", 1_000), signals())
        val changed = scan("local:a", 1_000, updatedMs = 5).copy(qualityScore = null, rejectReason = "motion")
        assertTrue(repo.saveScan(changed, signals().take(1)))
        assertEquals(changed, repo.scan("local:a"))
        assertEquals(listOf("processed"), repo.signals("local:a").map { it.kind })
        assertEquals(1L, count("SELECT COUNT(*) FROM vital_scans"))
    }

    @Test
    fun queriesFilterByModeRangeAndTombstoneNewestFirst() = runBlocking {
        repo.saveScan(scan("local:1", 1_000))
        repo.saveScan(scan("local:2", 2_000, mode = VitalScanRecord.MODE_FACE))
        repo.saveScan(scan("local:3", 3_000))
        repo.saveScan(scan("local:4", 9_000))
        assertEquals(listOf("local:3", "local:2", "local:1"), repo.scans(null, 0, 9_000).map { it.id })
        assertEquals(listOf("local:3", "local:1"), repo.scans(VitalScanRecord.MODE_FINGER, 0, 9_000).map { it.id })
        assertTrue(repo.deleteScan("local:3"))
        assertEquals(listOf("local:1"), repo.scans(VitalScanRecord.MODE_FINGER, 0, 9_000).map { it.id })
        assertEquals(listOf("local:3", "local:1"), repo.scans(VitalScanRecord.MODE_FINGER, 0, 9_000, includeDeleted = true).map { it.id })
    }

    @Test
    fun deleteTombstonesDropsSignalsAndIsNeverResurrected() = runBlocking {
        repo.saveScan(scan("local:a", 1_000), signals())
        assertTrue(repo.updateReference("local:a", """{"hr":61}"""))
        clock = 50_000
        assertTrue(repo.deleteScan("local:a"))
        assertFalse(repo.deleteScan("local:a"))
        val row = repo.scan("local:a")!!
        assertTrue(row.deleted)
        assertEquals(50_000L, row.updatedMs)
        // Privacy: the tombstone keeps no measurement or reference reading.
        assertEquals("{}", row.resultsJson)
        assertEquals("{}", row.qualityJson)
        assertNull(row.referenceJson)
        assertTrue(repo.signals("local:a").isEmpty())
        assertFalse(repo.saveScan(scan("local:a", 1_000, updatedMs = 99_999), signals()))
        assertTrue(repo.scan("local:a")!!.deleted)
        assertTrue(repo.signals("local:a").isEmpty())
        assertFalse(repo.updateReference("local:a", """{"hr":60}"""))
    }

    @Test
    fun updateReferenceAndDeleteAll() = runBlocking {
        repo.saveScan(scan("local:a", 1_000), signals())
        repo.saveScan(scan("local:b", 2_000), signals())
        clock = 20_000
        assertTrue(repo.updateReference("local:a", """{"hr":61,"device":"strap"}"""))
        assertEquals("""{"hr":61,"device":"strap"}""", repo.scan("local:a")!!.referenceJson)
        assertEquals(20_000L, repo.scan("local:a")!!.updatedMs)
        assertTrue(repo.updateReference("local:a", null))
        assertNull(repo.scan("local:a")!!.referenceJson)
        assertEquals(2, repo.deleteAllScans())
        assertTrue(repo.scans(null, 0, Long.MAX_VALUE).isEmpty())
        assertEquals(0L, count("SELECT COUNT(*) FROM vital_scan_signals"))
    }

    @Test
    fun calibrationsAndDeviceProfiles() = runBlocking {
        val cal = VitalCalibration("local:c1", VitalCalibration.KIND_SPO2, "Google Pixel 7", "local:a", 2_000, """{"spo2":98}""", """{"ratio":0.66}""", updatedMs = 1)
        assertTrue(repo.saveCalibration(cal))
        assertTrue(repo.saveCalibration(cal.copy(id = "local:c0", tMs = 1_000)))
        assertTrue(repo.saveCalibration(cal.copy(id = "local:other", deviceModel = "Other")))
        assertEquals(listOf("local:c0", "local:c1"), repo.calibrations(VitalCalibration.KIND_SPO2, "Google Pixel 7").map { it.id })
        assertTrue(repo.calibrations(VitalCalibration.KIND_BP, "Google Pixel 7").isEmpty())
        assertTrue(repo.saveCalibration(cal.copy(referenceJson = """{"spo2":97}""")))
        assertEquals("""{"spo2":97}""", repo.calibrations(VitalCalibration.KIND_SPO2, "Google Pixel 7").last().referenceJson)
        assertTrue(repo.deleteCalibration("local:c1"))
        assertFalse(repo.saveCalibration(cal))
        assertEquals(listOf("local:c0"), repo.calibrations(VitalCalibration.KIND_SPO2, "Google Pixel 7").map { it.id })

        assertNull(repo.deviceProfile("Google Pixel 7", "back"))
        repo.upsertDeviceProfile(VitalDeviceProfile("Google Pixel 7", "back", """{"fps":[30]}""", 1))
        repo.upsertDeviceProfile(VitalDeviceProfile("Google Pixel 7", "back", """{"fps":[30,60]}""", 2))
        repo.upsertDeviceProfile(VitalDeviceProfile("Google Pixel 7", "front", """{"fps":[30]}""", 3))
        assertEquals(VitalDeviceProfile("Google Pixel 7", "back", """{"fps":[30,60]}""", 2), repo.deviceProfile("Google Pixel 7", "back"))
        assertEquals(2L, count("SELECT COUNT(*) FROM vital_device_profiles"))
    }

    /** "Clear synced health data" keeps camera scans; deleting the database file removes them. */
    @Test
    fun clearSyncedHealthDataKeepsScans() = runBlocking {
        repo.saveScan(scan("local:a", 1_000), signals())
        val store = SqliteHealthDataStore(helper)
        store.deleteAll()
        assertEquals(1, repo.scans(null, 0, Long.MAX_VALUE).size)
        assertEquals(2, repo.signals("local:a").size)
    }
}
