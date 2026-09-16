package com.ayuvo.health.medications.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.medications.data.MedicationPhotoStore
import com.ayuvo.health.medications.data.MedicationsDatabase
import com.ayuvo.health.medications.data.SqliteMedicationsStore
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.logic.MedicationNotificationIds
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.ScheduleFrequency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.time.ZoneId
import java.util.UUID

/**
 * The planner on a throwaway database: a due dose is posted once and never re-armed at "now", the
 * next future dose arms the alarm, the gate cancels everything, and notification actions land in
 * the store (docs/medications.md §10–§11).
 */
@RunWith(AndroidJUnit4::class)
class MedicationReminderCoordinatorTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.systemDefault().id
    private lateinit var helper: MedicationsDatabase
    private lateinit var store: SqliteMedicationsStore
    private lateinit var tempRoot: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gateOpen = true
    private var clock = 0L

    private fun at(date: String, hhmm: String) = MedicationLocalTime.instant(date, hhmm, zone)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MedicationNotifications.createChannel(context)
        context.deleteDatabase(DB)
        tempRoot = File(context.cacheDir, "meds-reminders-${UUID.randomUUID()}").apply { mkdirs() }
        helper = MedicationsDatabase(context, DB)
        store = SqliteMedicationsStore(helper, MedicationPhotoStore.forTests(tempRoot))
        MedicationAlarms.cancel(context)
        MedicationNotifications.cancelAll(context)
    }

    @After
    fun tearDown() {
        scope.cancel()
        MedicationAlarms.cancel(context)
        MedicationNotifications.cancelAll(context)
        helper.close()
        context.deleteDatabase(DB)
        tempRoot.deleteRecursively()
    }

    private fun coordinator() = MedicationReminderCoordinator(
        context = context,
        store = { store },
        scope = scope,
        databaseExists = { true },
        gate = { gateOpen },
        snoozeMinutes = { 10 },
        now = { clock },
        zoneId = { zone }
    )

    private fun med(id: String, name: String, startDate: String) = Medication(
        id = id, name = name, strength = "500 mg", form = MedicationForm.TABLET, doseQuantity = 1.0, doseUnit = DoseUnit.TABLET,
        foodRelation = FoodRelation.WITH, startDate = startDate, status = MedicationStatus.ACTIVE,
        createdMs = at(startDate, "00:00"), updatedMs = at(startDate, "00:00")
    )

    private fun daily(id: String, medicationId: String, from: String, vararg times: String) = MedicationSchedule(
        id = id, medicationId = medicationId, frequency = ScheduleFrequency.DAILY, times = times.toList(),
        activeFromMs = at(from, "00:00"), createdMs = at(from, "00:00"), updatedMs = at(from, "00:00")
    )

    private fun activeTags(): List<String> {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return mgr.activeNotifications.filter { it.notification.channelId == MedicationNotifications.CHANNEL }.map { it.tag }
    }

    /** `NotificationManager.cancel` is applied by the system service asynchronously; wait for the shade to settle. */
    private fun awaitEmptyShade(timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (activeTags().isEmpty()) return true
            Thread.sleep(100)
        }
        return activeTags().isEmpty()
    }

    @Test
    fun dueDoseIsPostedOnceAndNextDoseArmsTheAlarm() = runBlocking {
        store.create(med("m1", "Metformin", "2026-09-10"), daily("s1", "m1", "2026-09-09", "08:00", "20:00"))
        clock = at("2026-09-10", "08:00") + 30_000L
        val c = coordinator()

        val next = c.replan()
        assertEquals(at("2026-09-10", "20:00"), next)
        assertTrue(MedicationAlarms.isArmed(context))
        assertEquals(listOf("m1"), activeTags())

        // A second run inside the late-fire window must not re-post or re-arm at "now".
        clock += 60_000L
        MedicationNotifications.cancelAll(context)
        assertEquals(at("2026-09-10", "20:00"), c.replan())
        assertTrue(activeTags().isEmpty())
    }

    @Test
    fun takenActionRemovesTheDoseFromThePlan() = runBlocking {
        store.create(med("m1", "Metformin", "2026-09-10"), daily("s1", "m1", "2026-09-09", "08:00", "20:00"))
        clock = at("2026-09-10", "08:00") + 30_000L
        val c = coordinator()
        c.replan()

        val request = DoseActionRequest("m1", "s1", at("2026-09-10", "08:00"), DoseAction.TAKEN)
        val result = MedicationActionHandler.apply(store, request, clock, 10)
        assertTrue(result.error, result.ok)
        assertEquals(DoseStatus.TAKEN, store.doseLog("s1", at("2026-09-10", "08:00"))!!.status)
        assertEquals(at("2026-09-10", "20:00"), c.replan())
    }

    @Test
    fun snoozeActionArmsTheSnoozeInstant() = runBlocking {
        store.create(med("m1", "Metformin", "2026-09-10"), daily("s1", "m1", "2026-09-09", "08:00", "20:00"))
        clock = at("2026-09-10", "08:00") + 30_000L
        val c = coordinator()
        c.replan()

        val request = DoseActionRequest("m1", "s1", at("2026-09-10", "08:00"), DoseAction.SNOOZED)
        val result = MedicationActionHandler.apply(store, request, clock, 10)
        assertTrue(result.error, result.ok)
        val until = store.doseLog("s1", at("2026-09-10", "08:00"))!!.snoozedUntilMs
        assertNotNull(until)
        assertEquals(clock + 10 * 60_000L, until)
        assertEquals(until, c.replan())

        // When the snooze fires it is posted once, then the evening dose is next.
        clock = until!! + 5_000L
        MedicationNotifications.cancelAll(context)
        assertEquals(at("2026-09-10", "20:00"), c.replan())
        assertEquals(listOf("m1"), activeTags())
    }

    @Test
    fun closedGateCancelsAlarmAndNotifications() = runBlocking {
        store.create(med("m1", "Metformin", "2026-09-10"), daily("s1", "m1", "2026-09-09", "08:00", "20:00"))
        clock = at("2026-09-10", "08:00") + 30_000L
        val c = coordinator()
        c.replan()
        assertTrue(MedicationAlarms.isArmed(context))

        gateOpen = false
        assertNull(c.replan())
        assertFalse(MedicationAlarms.isArmed(context))
        assertTrue("closing the gate clears every medication reminder from the shade", awaitEmptyShade())
    }

    @Test
    fun missedDosesAreMaterializedByThePlanner() = runBlocking {
        store.create(med("m1", "Metformin", "2026-09-10"), daily("s1", "m1", "2026-09-09", "08:00", "20:00"))
        clock = at("2026-09-10", "11:00")
        coordinator().replan()
        assertEquals(DoseStatus.MISSED, store.doseLog("s1", at("2026-09-10", "08:00"))!!.status)
        assertTrue(activeTags().isEmpty())
    }

    @Test
    fun actionIntentRoundTrips() {
        val entry = com.ayuvo.health.medications.model.ReminderEntry("m1", "s1", 1_700_000_000_000L, 1_700_000_000_000L, com.ayuvo.health.medications.model.ReminderKind.SCHEDULED)
        val medication = med("m1", "Metformin", "2026-09-10")
        assertEquals("Take 1 tablet", MedicationNotifications.body(context, medication, entry.scheduledAtMs).substringBefore(" · "))
        assertEquals("0.5", MedicationNotifications.formatQuantity(0.5))
        assertEquals("2", MedicationNotifications.formatQuantity(2.0))
        assertTrue(MedicationNotificationIds.id("m1", 1_700_000_000_000L) >= MedicationNotificationIds.MIN)

        val intent = android.content.Intent(MedicationNotifications.ACTION_DOSE).apply {
            putExtra(MedicationNotifications.EXTRA_MEDICATION_ID, "m1")
            putExtra(MedicationNotifications.EXTRA_SCHEDULE_ID, "s1")
            putExtra(MedicationNotifications.EXTRA_SCHEDULED_AT, 1_700_000_000_000L)
            putExtra(MedicationNotifications.EXTRA_ACTION, DoseAction.SKIPPED.raw)
        }
        assertEquals(DoseActionRequest("m1", "s1", 1_700_000_000_000L, DoseAction.SKIPPED), MedicationActionHandler.parse(intent))
        assertNull(MedicationActionHandler.parse(android.content.Intent("other")))
    }

    private companion object {
        const val DB = "ayuvo_medications_reminders_test.db"
    }
}
