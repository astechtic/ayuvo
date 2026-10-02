package com.ayuvo.health.cycle.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.cycle.engine.CycleConfig
import com.ayuvo.health.cycle.engine.CycleEngine
import com.ayuvo.health.data.health.HealthDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `ayuvo_cycle.db` against real SQLite (docs/cycle-tracking.md §2): schema, CRUD, tombstones, period-day ops, state. */
@RunWith(AndroidJUnit4::class)
class CycleRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: CycleDatabase
    private lateinit var repo: CycleRepository
    private var clock = 1_000L
    private var ids = 0
    private val engine by lazy {
        CycleEngine(CycleConfig.parse(context.assets.open(CycleConfig.ASSET_PATH).bufferedReader().use { it.readText() }))
    }

    @Before
    fun setUp() {
        CycleDatabase.deleteDatabaseFiles(context, NAME)
        helper = CycleDatabase(context, NAME)
        repo = CycleRepository(helper, healthDatabase = { null }, ownPackage = context.packageName, now = { clock++ }, newId = { "local:t${++ids}" })
    }

    @After
    fun tearDown() {
        helper.close()
        CycleDatabase.deleteDatabaseFiles(context, NAME)
    }

    private fun scalar(sql: String): String = helper.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }

    @Test
    fun freshDatabaseHasEveryTableAndTheSchemaVersion() {
        val tables = helper.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertTrue(tables.containsAll(CycleSchema.TABLES))
        assertEquals("1", scalar("SELECT value FROM cycle_meta WHERE key = 'schema_version'"))
        assertEquals("1", scalar("PRAGMA user_version"))
    }

    @Test
    fun periodsInsertUpdateAndTombstone() = runBlocking {
        val p = repo.insertPeriod("2026-09-01", "2026-09-05")
        assertTrue(repo.updatePeriod(p.id, "2026-09-02", null))
        assertEquals("2026-09-02", repo.period(p.id)!!.startDay)
        assertNull(repo.period(p.id)!!.endDay)
        assertTrue(repo.deletePeriod(p.id))
        assertTrue(repo.periods().isEmpty())
        assertTrue(repo.periods(includeDeleted = true).single().deleted)
        // A tombstone is never edited back to life.
        assertFalse(repo.updatePeriod(p.id, "2026-09-01", "2026-09-03"))
        assertEquals(1, repo.pendingSync().first.size)
    }

    @Test
    fun dayLogsUpsertAndDeleteBlanksTheContents() = runBlocking {
        repo.saveDayLog(CycleDayLog("2026-09-02", flow = "heavy", pain = 12, symptoms = listOf("cramps"), note = "private"))
        val saved = repo.dayLog("2026-09-02")!!
        assertEquals(10, saved.pain)
        assertEquals(listOf("cramps"), saved.symptoms)
        repo.saveDayLog(saved.copy(flow = "medium", note = null))
        assertEquals("medium", repo.dayLog("2026-09-02")!!.flow)
        assertNull(repo.dayLog("2026-09-02")!!.note)
        assertTrue(repo.deleteDayLog("2026-09-02"))
        assertNull(repo.dayLog("2026-09-02"))
        val tomb = repo.dayLogs(includeDeleted = true).single()
        assertTrue(tomb.deleted)
        assertNull(tomb.flow)
        assertTrue(tomb.symptoms.isEmpty())
        // An empty save is a delete; a later save revives the day.
        repo.saveDayLog(CycleDayLog("2026-09-03", moods = listOf("calm")))
        repo.saveDayLog(CycleDayLog("2026-09-03"))
        assertNull(repo.dayLog("2026-09-03"))
        repo.saveDayLog(CycleDayLog("2026-09-02", moods = listOf("happy")))
        assertEquals(listOf("happy"), repo.dayLog("2026-09-02")!!.moods)
    }

    @Test
    fun periodDayOpsFromTheEngineApplyInOneTransaction() = runBlocking {
        val a = repo.insertPeriod("2026-09-01", "2026-09-05")
        val b = repo.insertPeriod("2026-09-07", "2026-09-10")
        val state = repo.state("2026-10-02", includePlatform = false)
        val ops = engine.applyPeriodDay("2026-10-02", "2026-09-06", true, state.periods).ops
        assertNull(repo.applyPeriodOps(ops))
        val live = repo.periods()
        assertEquals(listOf(a.id), live.map { it.id })
        assertEquals("2026-09-10", live.single().endDay)
        assertTrue(repo.periods(includeDeleted = true).first { it.id == b.id }.deleted)
        val inserted = repo.applyPeriodOps(engine.applyPeriodDay("2026-10-02", "2026-10-02", true, repo.state("2026-10-02", false).periods).ops)
        assertEquals("local:t3", inserted)
        assertNull(repo.period(inserted!!)!!.endDay)
    }

    @Test
    fun settingsRowAndEngineState() = runBlocking {
        assertFalse(repo.settings().setupDone)
        repo.saveSettings(CycleSettingsRow(setupDone = true, cycleLength = 30, preferences = CyclePreferences(daily = true, time = "20:30")))
        val s = repo.settings()
        assertTrue(s.setupDone)
        assertEquals(30, s.cycleLength)
        assertEquals("20:30", s.preferences.time)
        repo.insertPeriod("2026-09-20", "2026-09-24")
        repo.saveDayLog(CycleDayLog("2026-09-20", flow = "medium"))
        val snap = engine.snapshot(repo.state("2026-10-02"))
        assertEquals("default", snap.prediction.basis)
        assertEquals("2026-10-20", snap.prediction.nextStart)
        assertEquals("daily_log", snap.reminders.first().kind)
        repo.deleteAll()
        assertFalse(repo.settings().setupDone)
        assertTrue(repo.periods(includeDeleted = true).isEmpty())
    }

    @Test
    fun syncMarksOnlyApplyToTheRowVersionTheyWrote() = runBlocking {
        val p = repo.insertPeriod("2026-09-01", "2026-09-05")
        assertTrue(repo.markPeriodSync(p.id, CycleSyncState.SYNCED, "{\"health_connect\":[\"ayuvo:cycle:${p.id}\"]}", p.updatedMs))
        assertTrue(repo.pendingSync().first.isEmpty())
        repo.updatePeriod(p.id, "2026-09-01", "2026-09-06")
        assertFalse(repo.markPeriodSync(p.id, CycleSyncState.SYNCED, "{}", p.updatedMs))
        val pending = repo.pendingSync().first.single()
        assertTrue(pending.platformIdsJson.contains("ayuvo:cycle:"))
    }

    @Test
    fun platformPeriodsAreEmptyWithoutTheHealthMirror() = runBlocking {
        assertTrue(repo.platformPeriods().isEmpty())
        // With a mirror, Ayuvo's own rows are skipped and Health Connect periods enter the state.
        HealthDatabase.deleteDatabaseFiles(context)
        val health = HealthDatabase(context)
        try {
            val db = health.writableDatabase
            db.execSQL(
                "INSERT INTO health_samples(id, type_id, start_ms, end_ms, start_offset_s, end_offset_s, local_day, unit, source_id, origin, updated_ms) " +
                    "VALUES ('x', 'menstruation_period', 1788566400000, 1788998400000, 0, 0, '2026-09-05', 'days', 'com.other', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO health_samples(id, type_id, start_ms, end_ms, start_offset_s, end_offset_s, local_day, unit, source_id, client_record_id, origin, updated_ms) " +
                    "VALUES ('y', 'menstruation_period', 1786060800000, 1786492800000, 0, 0, '2026-08-07', 'days', 'com.other', 'ayuvo:cycle:local:1', 0, 1)"
            )
            val withHealth = CycleRepository(helper, { health }, context.packageName)
            val got = withHealth.platformPeriods(java.time.ZoneOffset.UTC)
            assertEquals(1, got.size)
            assertEquals("2026-09-05", got.single().start)
            assertEquals("2026-09-09", got.single().end)
            assertEquals("health_connect", got.single().source)
        } finally {
            health.close()
            HealthDatabase.deleteDatabaseFiles(context)
        }
    }

    private companion object {
        const val NAME = "ayuvo_cycle_test.db"
    }
}
