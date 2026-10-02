package com.ayuvo.health.cycle.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/cycle-tracking.md §7 against real SQLite: import `shared/cycle/fixtures/cycle-sample`, re-import (nothing
 * changes), newer local rows win, and export → import into an empty store round-trips.
 */
@RunWith(AndroidJUnit4::class)
class CycleArchiveImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = listOf("ayuvo_cycle_import_a.db", "ayuvo_cycle_import_b.db")
    private val helpers = mutableListOf<CycleDatabase>()

    @Before
    fun setUp() = names.forEach { CycleDatabase.deleteDatabaseFiles(context, it) }

    @After
    fun tearDown() {
        helpers.forEach { it.close() }
        names.forEach { CycleDatabase.deleteDatabaseFiles(context, it) }
    }

    private fun repo(name: String, now: Long = 2_000_000_000_000L): CycleRepository {
        val helper = CycleDatabase(context, name).also { helpers += it }
        return CycleRepository(helper, { null }, context.packageName, now = { now })
    }

    private fun asset(name: String): String =
        instrumentation.context.assets.open("cycle-sample/$name").bufferedReader().use { it.readText() }

    private fun fixture() = CycleArchive.read(asset("periods.ndjson"), asset("day_logs.ndjson"), asset("settings.json"))

    @Test
    fun importsTheFixtureOnceAndKeepsNewerLocalRows() = runBlocking {
        val repo = repo(names[0])
        assertEquals(CycleArchive.ImportResult(4, 6, true, 0), repo.importArchive(fixture()))
        assertEquals(4, repo.periods().size)
        assertEquals(5, repo.dayLogs().size)
        assertTrue(repo.dayLogs(includeDeleted = true).first { it.day == "2026-09-20" }.deleted)
        assertEquals("Sample note", repo.dayLog("2026-09-05")!!.note)
        assertTrue(repo.settings().setupDone)
        // Imported rows wait for the Health sync.
        assertEquals(4, repo.pendingSync().first.size)
        // Same file again: nothing is newer, nothing changes.
        assertEquals(CycleArchive.ImportResult(0, 0, false, 0), repo.importArchive(fixture()))
        // A local edit (now = 2e12 ms, newer than the fixture) survives a re-import.
        repo.saveDayLog(repo.dayLog("2026-09-05")!!.copy(note = "edited"))
        repo.importArchive(fixture())
        assertEquals("edited", repo.dayLog("2026-09-05")!!.note)
    }

    @Test
    fun exportThenImportIntoAnEmptyStoreRoundTrips() = runBlocking {
        val source = repo(names[0])
        source.importArchive(fixture())
        val exported = source.exportBundle()
        val text = CycleArchive.encode(exported).toMap()
        assertFalse(text.getValue(CycleArchive.PERIODS).first.contains("platform_ids"))
        val bundle = CycleArchive.read(
            text.getValue(CycleArchive.PERIODS).first, text.getValue(CycleArchive.DAY_LOGS).first, text.getValue(CycleArchive.SETTINGS).first
        )
        val target = repo(names[1])
        assertEquals(CycleArchive.ImportResult(4, 6, true, 0), target.importArchive(bundle))
        assertEquals(source.periods(), target.periods())
        assertEquals(source.dayLogs(includeDeleted = true), target.dayLogs(includeDeleted = true))
        assertEquals(source.settings(), target.settings())
    }
}
