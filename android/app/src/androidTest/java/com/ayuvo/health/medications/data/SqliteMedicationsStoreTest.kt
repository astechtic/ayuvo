package com.ayuvo.health.medications.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.medications.export.MedicationsArchive
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationFilter
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.ScheduleFrequency
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** [SqliteMedicationsStore] on a throwaway database: the shared rules wired to real SQLite rows. */
@RunWith(AndroidJUnit4::class)
class SqliteMedicationsStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: MedicationsDatabase
    private lateinit var store: SqliteMedicationsStore
    private lateinit var tempRoot: File
    private val zone = "Asia/Kolkata"

    private fun at(date: String, hhmm: String) = MedicationLocalTime.instant(date, hhmm, zone)

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "meds-store-${UUID.randomUUID()}").apply { mkdirs() }
        helper = MedicationsDatabase(context, DB)
        store = SqliteMedicationsStore(helper, MedicationPhotoStore.forTests(tempRoot))
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB)
        tempRoot.deleteRecursively()
    }

    private fun med(id: String, name: String, startDate: String = "2026-09-01", isPrn: Boolean = false, endDate: String? = null) = Medication(
        id = id, name = name, strength = "500 mg", form = MedicationForm.TABLET, doseQuantity = 1.0, doseUnit = DoseUnit.TABLET,
        foodRelation = FoodRelation.WITH, startDate = startDate, endDate = endDate, status = MedicationStatus.ACTIVE, isPrn = isPrn,
        createdMs = at("2026-09-01", "00:00"), updatedMs = at("2026-09-01", "00:00")
    )

    private fun daily(id: String, medicationId: String, vararg times: String) = MedicationSchedule(
        id = id, medicationId = medicationId, frequency = ScheduleFrequency.DAILY, times = times.toList(),
        activeFromMs = at("2026-08-31", "00:00"), createdMs = at("2026-09-01", "00:00"), updatedMs = at("2026-09-01", "00:00")
    )

    @Test
    fun createBuildsTodayTimeline() = runBlocking {
        store.create(med("m1", "Metformin"), daily("s1", "m1", "08:00", "20:00"))
        val t = store.today(at("2026-09-10", "09:00"), zone)
        assertEquals("2026-09-10", t.date)
        assertEquals(2, t.summary.total)
        assertEquals(1, t.summary.due)
        assertEquals(1, t.summary.upcoming)
        assertEquals(listOf("08:00", "20:00"), t.groups.map { it.slot })
        assertEquals("Metformin", t.medications.getValue("m1").name)
        assertEquals(1, store.list(MedicationFilter(status = MedicationStatus.ACTIVE)).size)
        assertEquals(1, store.list(MedicationFilter(query = "met")).size)
        assertEquals(0, store.list(MedicationFilter(query = "%")).size)
    }

    @Test
    fun takeUndoSnoozeSkip() = runBlocking {
        store.create(med("m1", "Metformin"), daily("s1", "m1", "08:00", "20:00"))
        val dose = at("2026-09-10", "08:00")
        val take = store.act("m1", "s1", dose, DoseAction.TAKEN, at("2026-09-10", "08:05"), 10)
        assertTrue(take.error, take.ok)
        assertEquals("insert", take.op)
        assertEquals(DoseStatus.TAKEN, store.today(at("2026-09-10", "08:06"), zone).groups[0].items[0].status)
        assertEquals("already_taken", store.act("m1", "s1", dose, DoseAction.TAKEN, at("2026-09-10", "08:07"), 10).error)

        val undo = store.act("m1", "s1", dose, DoseAction.UNDO, at("2026-09-10", "08:08"), 10)
        assertTrue(undo.ok)
        assertNull(store.doseLog("s1", dose))
        assertEquals(DoseStatus.DUE, store.today(at("2026-09-10", "08:09"), zone).groups[0].items[0].status)

        val snooze = store.act("m1", "s1", dose, DoseAction.SNOOZED, at("2026-09-10", "08:10"), 10)
        assertTrue(snooze.error, snooze.ok)
        assertEquals(at("2026-09-10", "08:20"), store.doseLog("s1", dose)!!.snoozedUntilMs)
        assertEquals(DoseStatus.SNOOZED, store.today(at("2026-09-10", "08:15"), zone).groups[0].items[0].status)
        assertEquals(DoseStatus.DUE, store.today(at("2026-09-10", "08:25"), zone).groups[0].items[0].status)
        assertEquals("bad_snooze", store.act("m1", "s1", dose, DoseAction.SNOOZED, at("2026-09-10", "08:25"), 15).error)

        val skip = store.act("m1", "s1", dose, DoseAction.SKIPPED, at("2026-09-10", "08:30"), 10, note = "felt sick")
        assertTrue(skip.ok)
        assertEquals("update", skip.op)
        val log = store.doseLog("s1", dose)!!
        assertEquals(DoseStatus.SKIPPED, log.status)
        assertEquals("felt sick", log.note)
        assertEquals("not_due_yet", store.act("m1", "s1", at("2026-09-10", "20:00"), DoseAction.SNOOZED, at("2026-09-10", "08:31"), 10).error)
    }

    @Test
    fun missedIsMaterializedAndCanBeTakenLate() = runBlocking {
        store.create(med("m1", "Metformin", startDate = "2026-09-10"), daily("s1", "m1", "08:00", "20:00"))
        val now = at("2026-09-10", "11:00")
        assertEquals(1, store.materializeMissed(now, zone))
        assertEquals(0, store.materializeMissed(now, zone))
        val missed = store.doseLog("s1", at("2026-09-10", "08:00"))!!
        assertEquals(DoseStatus.MISSED, missed.status)
        assertEquals("cannot_undo_missed", store.act("m1", "s1", missed.scheduledAtMs, DoseAction.UNDO, now, 10).error)
        assertEquals("dose_missed", store.act("m1", "s1", missed.scheduledAtMs, DoseAction.SNOOZED, now, 10).error)
        val late = store.act("m1", "s1", missed.scheduledAtMs, DoseAction.TAKEN, now, 10)
        assertTrue(late.ok)
        assertEquals("update", late.op)
        val item = store.today(now, zone).groups[0].items[0]
        assertEquals(DoseStatus.TAKEN, item.status)
        assertTrue(item.isLate)
        assertEquals(missed.id, item.logId)
    }

    @Test
    fun pauseResumeStopPreserveHistory() = runBlocking {
        store.create(med("m1", "Metformin"), daily("s1", "m1", "08:00", "20:00"))
        assertTrue(store.act("m1", "s1", at("2026-09-10", "08:00"), DoseAction.TAKEN, at("2026-09-10", "08:05"), 10).ok)
        assertNull(store.setStatus("m1", LifecycleAction.PAUSE, at("2026-09-10", "09:00")))
        assertEquals(MedicationStatus.PAUSED, store.medication("m1")!!.status)
        assertTrue(store.schedules("m1", openOnly = true).isEmpty())
        assertEquals("invalid_transition", store.setStatus("m1", LifecycleAction.PAUSE, at("2026-09-10", "09:01")))
        val paused = store.today(at("2026-09-10", "10:00"), zone)
        assertEquals(listOf("08:00"), paused.groups.map { it.slot })
        assertEquals(0, paused.summary.upcoming)

        assertNull(store.setStatus("m1", LifecycleAction.RESUME, at("2026-09-10", "12:00")))
        val open = store.schedules("m1", openOnly = true)
        assertEquals(1, open.size)
        assertEquals(at("2026-09-10", "12:00"), open[0].activeFromMs)
        assertEquals(listOf("08:00", "20:00"), open[0].times)
        val resumed = store.today(at("2026-09-10", "13:00"), zone)
        assertEquals(listOf("08:00", "20:00"), resumed.groups.map { it.slot })
        assertEquals(1, resumed.summary.upcoming)

        assertNull(store.setStatus("m1", LifecycleAction.STOP, at("2026-09-10", "14:00")))
        assertEquals(MedicationStatus.STOPPED, store.medication("m1")!!.status)
        assertTrue(store.schedules("m1", openOnly = true).isEmpty())
        // pause closed s1, resume inserted one copy, stop closed that copy: two rows, both closed (docs §12).
        assertEquals(2, store.schedules("m1", openOnly = false).size)
        assertEquals(1, store.history("m1", null).size)
        assertEquals("invalid_transition", store.setStatus("m1", LifecycleAction.RESUME, at("2026-09-10", "15:00")))
    }

    @Test
    fun editVersionsTheScheduleAndAutoCompleteEndsCourses() = runBlocking {
        store.create(med("m1", "Metformin"), daily("s1", "m1", "08:00", "20:00"))
        val edited = daily("ignored", "m1", "09:00", "21:00")
        assertNull(store.update(store.medication("m1")!!, edited, at("2026-09-10", "10:00")))
        val rows = store.schedules("m1", openOnly = false)
        assertEquals(2, rows.size)
        assertEquals(at("2026-09-10", "10:00"), rows[0].activeUntilMs)
        assertEquals(listOf("09:00", "21:00"), rows[1].times)
        assertTrue(rows[1].isOpen)
        // Only the reminder flag changes: no new version.
        assertNull(store.update(store.medication("m1")!!, rows[1].copy(reminderEnabled = false), at("2026-09-10", "10:30")))
        assertEquals(2, store.schedules("m1", openOnly = false).size)
        assertFalse(store.schedules("m1", openOnly = true)[0].reminderEnabled)

        store.create(med("m2", "Amoxicillin", startDate = "2026-09-01", endDate = "2026-09-09"), daily("s2", "m2", "08:00"))
        assertEquals(0, store.autoComplete("2026-09-09", at("2026-09-09", "00:10")))
        assertEquals(1, store.autoComplete("2026-09-10", at("2026-09-10", "00:10")))
        assertEquals(MedicationStatus.COMPLETED, store.medication("m2")!!.status)
        assertTrue(store.schedules("m2", openOnly = true).isEmpty())
        assertEquals(mapOf(MedicationStatus.ACTIVE to 1, MedicationStatus.COMPLETED to 1), store.countByStatus())
    }

    @Test
    fun prnDosesAreLoggedNotScheduled() = runBlocking {
        store.create(med("p1", "Paracetamol", isPrn = true), daily("ignored", "p1", "08:00"))
        assertTrue(store.schedules("p1", openOnly = false).isEmpty())
        val r = store.logPrn("p1", at("2026-09-10", "09:00"), doseQuantity = 2.0, note = "headache")
        assertTrue(r.error, r.ok)
        val t = store.today(at("2026-09-10", "10:00"), zone)
        assertEquals(1, t.prn.size)
        assertEquals(1, t.prn[0].todayCount)
        assertEquals(at("2026-09-10", "09:00"), t.prn[0].lastTakenMs)
        assertEquals(0, t.summary.total)
        assertEquals(1, t.groups.size)
        assertEquals(2.0, t.groups[0].items[0].doseQuantity, 0.0)
        assertEquals("unknown_medication", store.logPrn("missing", at("2026-09-10", "09:00")).error)
        store.create(med("m2", "Metformin"), daily("s2", "m2", "08:00"))
        assertEquals("not_prn", store.logPrn("m2", at("2026-09-10", "09:00")).error)
        val plan = store.planReminders(at("2026-09-10", "10:00"), 86_400_000L, zone)
        assertTrue("PRN medicines never get reminders", plan.entries.none { it.medicationId == "p1" })
        assertTrue("the scheduled medicine still does", plan.entries.any { it.medicationId == "m2" })
    }

    @Test
    fun adherenceEighteenOfTwentyOne() = runBlocking {
        store.create(med("m1", "Metformin", startDate = "2026-09-03"), daily("s1", "m1", "08:00", "14:00", "20:00"))
        var n = 0
        for (day in 3..9) {
            for (slot in listOf("08:00", "14:00", "20:00")) {
                val date = "2026-09-%02d".format(day)
                val action = if (n < 18) DoseAction.TAKEN else DoseAction.SKIPPED
                val r = store.act("m1", "s1", at(date, slot), action, at(date, slot) + 60_000L, 10)
                assertTrue(r.error, r.ok)
                n++
            }
        }
        val now = at("2026-09-10", "00:30")
        val a = store.adherence("m1", now, zone)
        assertEquals(18, a.taken)
        assertEquals(21, a.expected)
        assertEquals(86, a.percent)
        assertTrue(a.hasData)
        assertEquals(21, store.history("m1", null, limit = 100).size)
        val page = store.history(null, null, limit = 5)
        assertEquals(5, page.size)
        val next = store.history(null, com.ayuvo.health.medications.model.DoseLogCursor.after(page.last()), limit = 100)
        assertEquals(16, next.size)
        assertTrue(page.last().scheduledAtMs >= next.first().scheduledAtMs)
    }

    @Test
    fun exportImportRoundTrip() = runBlocking {
        store.create(med("m1", "Metformin"), daily("s1", "m1", "08:00", "20:00"))
        assertTrue(store.act("m1", "s1", at("2026-09-10", "08:00"), DoseAction.TAKEN, at("2026-09-10", "08:05"), 10).ok)
        val snapshot = store.exportSnapshot()
        assertEquals(1, snapshot.medications.size)
        assertEquals(1, snapshot.schedules.size)
        assertEquals(1, snapshot.doseLogs.size)
        val bytes = MedicationsArchive.write(snapshot, at("2026-09-10", "09:00"), zone, "1.0")
        store.delete("m1")
        assertTrue(store.list().isEmpty())
        val result = store.importArchive(MedicationsArchive.read(bytes), at("2026-09-10", "09:30"))
        assertTrue(result.error, result.ok)
        assertEquals(3, result.inserted)
        assertEquals(0, result.updated)
        assertEquals("Metformin", store.medication("m1")!!.name)
        assertEquals(listOf("08:00", "20:00"), store.schedules("m1")[0].times)
        assertNotNull(store.doseLog("s1", at("2026-09-10", "08:00")))
        val again = store.importArchive(MedicationsArchive.read(bytes), at("2026-09-10", "09:40"))
        assertEquals(0, again.inserted)
        assertEquals(3, again.skipped)
    }

    @Test
    fun revisionBumpsOnlyOnWrites() = runBlocking {
        val before = store.revision.value
        store.today(at("2026-09-10", "09:00"), zone)
        assertEquals(before, store.revision.value)
        store.create(med("m1", "Metformin"), daily("s1", "m1", "08:00"))
        assertEquals(before + 1, store.revision.value)
        // The schedule has been active since 08-31 with a 09-01 start date, so the first run materializes
        // the nine unlogged 08:00 doses of 09-01 … 09-09 (a data write → one bump); the second run has
        // nothing to do and must not bump.
        assertEquals(9, store.materializeMissed(at("2026-09-10", "07:00"), zone))
        val afterMissed = store.revision.value
        assertEquals(before + 2, afterMissed)
        assertEquals(0, store.materializeMissed(at("2026-09-10", "07:00"), zone))
        assertEquals(afterMissed, store.revision.value)
        store.delete("m1")
        assertEquals(afterMissed + 1, store.revision.value)
    }

    private companion object {
        const val DB = "ayuvo_medications_store_test.db"
    }
}
