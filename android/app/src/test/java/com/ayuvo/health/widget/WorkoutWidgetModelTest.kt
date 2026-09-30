package com.ayuvo.health.widget

import com.ayuvo.health.models.ActiveStrengthSession
import com.ayuvo.health.services.workout.OutdoorLiveState
import com.ayuvo.health.services.workout.OutdoorPhase
import com.ayuvo.health.services.workout.WorkoutFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkoutWidgetModelTest {
    private val now = 1_700_000_000_000L

    private fun live(
        phase: OutdoorPhase,
        sport: String = "run",
        hasFix: Boolean = true,
        activeMs: Long = 600_000L,
        snapshotMs: Long = now - 5_000L,
        recoveryEndsMs: Long? = null
    ) = OutdoorLiveState(
        phase = phase,
        sessionId = "s1",
        sport = sport,
        sportTitle = if (sport == "cycle") "Cycle" else "Run",
        cooper = false,
        startMs = now - 700_000L,
        activeMs = activeMs,
        snapshotMs = snapshotMs,
        distanceM = 2_410.0,
        avgPaceSPerKm = 337.0,
        avgSpeedMps = 5.0,
        currentSpeedMps = 2.9,
        hasFix = hasFix,
        recoveryEndsMs = recoveryEndsMs
    )

    private fun active(model: WorkoutWidgetModel) = model as WorkoutWidgetModel.Active

    @Test
    fun nothingRunningIsIdle() {
        assertEquals(WorkoutWidgetModel.Idle, WorkoutWidgetMapper.map(null, null, now))
    }

    @Test
    fun recordingTicksFromActiveTimeWithPauseLapEnd() {
        val m = active(WorkoutWidgetMapper.map(live(OutdoorPhase.RECORDING), null, now))
        assertEquals(WorkoutWidgetStatus.RECORDING, m.status)
        assertEquals("run", m.sport)
        assertEquals("Run", m.title)
        // activeMs plus the wall time since the snapshot.
        assertEquals(605_000L, m.elapsedMs)
        assertTrue(m.ticking)
        assertFalse(m.countDown)
        assertEquals(listOf(WorkoutWidgetControl.PAUSE, WorkoutWidgetControl.LAP, WorkoutWidgetControl.END), m.controls)
        assertEquals(WorkoutFormat.distanceKm(2_410.0), m.distanceText)
        assertEquals("5:37 /km", m.paceText)
        assertFalse(m.paceIsSpeed)
        assertFalse(m.waitingForGps)
    }

    @Test
    fun pausedFreezesTheClockAndOffersResumeEnd() {
        val m = active(WorkoutWidgetMapper.map(live(OutdoorPhase.PAUSED), null, now))
        assertEquals(WorkoutWidgetStatus.PAUSED, m.status)
        assertEquals(600_000L, m.elapsedMs)
        assertFalse(m.ticking)
        assertEquals(listOf(WorkoutWidgetControl.RESUME, WorkoutWidgetControl.END), m.controls)
    }

    @Test
    fun savingHasNoControls() {
        val m = active(WorkoutWidgetMapper.map(live(OutdoorPhase.SAVING), null, now))
        assertEquals(WorkoutWidgetStatus.SAVING, m.status)
        assertFalse(m.ticking)
        assertTrue(m.controls.isEmpty())
    }

    @Test
    fun recoveryCountsDownToItsEndWithSkip() {
        val m = active(WorkoutWidgetMapper.map(live(OutdoorPhase.RECOVERY, recoveryEndsMs = now + 42_000L), null, now))
        assertEquals(WorkoutWidgetStatus.RECOVERY, m.status)
        assertEquals(42_000L, m.elapsedMs)
        assertTrue(m.ticking)
        assertTrue(m.countDown)
        assertEquals(listOf(WorkoutWidgetControl.SKIP_RECOVERY), m.controls)
        val late = active(WorkoutWidgetMapper.map(live(OutdoorPhase.RECOVERY, recoveryEndsMs = now - 1_000L), null, now))
        assertEquals(0L, late.elapsedMs)
    }

    @Test
    fun cyclingShowsAverageSpeed() {
        val m = active(WorkoutWidgetMapper.map(live(OutdoorPhase.RECORDING, sport = "cycle"), null, now))
        assertTrue(m.paceIsSpeed)
        assertEquals(WorkoutFormat.speedKmh(5.0), m.paceText)
    }

    @Test
    fun noFixYetWaitsForGps() {
        assertTrue(active(WorkoutWidgetMapper.map(live(OutdoorPhase.RECORDING, hasFix = false), null, now)).waitingForGps)
        assertTrue(active(WorkoutWidgetMapper.map(live(OutdoorPhase.PAUSED, hasFix = false), null, now)).waitingForGps)
        assertFalse(active(WorkoutWidgetMapper.map(live(OutdoorPhase.SAVING, hasFix = false), null, now)).waitingForGps)
    }

    @Test
    fun strengthSessionShowsItsTimerAndFinish() {
        val session = ActiveStrengthSession("2026-09-30", Instant.ofEpochMilli(now - 90_000L))
        val m = active(WorkoutWidgetMapper.map(null, session, now))
        assertEquals(WorkoutWidgetModel.STRENGTH_SPORT, m.sport)
        assertNull(m.title)
        assertEquals(WorkoutWidgetStatus.STRENGTH, m.status)
        assertEquals(90_000L, m.elapsedMs)
        assertTrue(m.ticking)
        assertNull(m.distanceText)
        assertNull(m.paceText)
        assertEquals(listOf(WorkoutWidgetControl.FINISH_STRENGTH), m.controls)
    }

    @Test
    fun gpsWinsOverStrengthAndDoneFallsBack() {
        val session = ActiveStrengthSession("2026-09-30", Instant.ofEpochMilli(now - 90_000L))
        assertEquals("run", active(WorkoutWidgetMapper.map(live(OutdoorPhase.RECORDING), session, now)).sport)
        assertEquals(WorkoutWidgetStatus.STRENGTH, active(WorkoutWidgetMapper.map(live(OutdoorPhase.DONE), session, now)).status)
        assertEquals(WorkoutWidgetModel.Idle, WorkoutWidgetMapper.map(live(OutdoorPhase.DONE), null, now))
    }

    @Test
    fun throttleRendersOnPhaseChangesAndEvery30sWhileRecording() {
        val rec = WorkoutWidgetThrottle.keyOf(live(OutdoorPhase.RECORDING), null)
        val paused = WorkoutWidgetThrottle.keyOf(live(OutdoorPhase.PAUSED), null)
        assertTrue(WorkoutWidgetThrottle.shouldPublish(null, rec, 0L, now))
        assertFalse(WorkoutWidgetThrottle.shouldPublish(rec, rec, now - 29_999L, now))
        assertTrue(WorkoutWidgetThrottle.shouldPublish(rec, rec, now - 30_000L, now))
        assertTrue(WorkoutWidgetThrottle.shouldPublish(rec, paused, now - 1_000L, now))
        // Paused numbers do not move, so no periodic render.
        assertFalse(WorkoutWidgetThrottle.shouldPublish(paused, paused, now - 60_000L, now))
        val idle = WorkoutWidgetThrottle.keyOf(null, null)
        val strength = WorkoutWidgetThrottle.keyOf(null, ActiveStrengthSession("2026-09-30", Instant.ofEpochMilli(now)))
        assertTrue(WorkoutWidgetThrottle.shouldPublish(idle, strength, now, now))
        assertTrue(WorkoutWidgetThrottle.shouldPublish(strength, idle, now, now))
        assertFalse(WorkoutWidgetThrottle.shouldPublish(strength, strength, now - 60_000L, now))
        // The first GPS fix replaces "Waiting for GPS" right away.
        val waiting = WorkoutWidgetThrottle.keyOf(live(OutdoorPhase.RECORDING, hasFix = false), null)
        assertTrue(WorkoutWidgetThrottle.shouldPublish(waiting, rec, now - 1_000L, now))
    }

    @Test
    fun intentContractParsesStartsAndOpens() {
        WorkoutWidgetStart.entries.forEach { start ->
            assertEquals(start, WorkoutWidgetIntentContract.launchFrom(WorkoutWidgetIntentContract.ACTION_START, start.id)?.start)
        }
        val unknown = WorkoutWidgetIntentContract.launchFrom(WorkoutWidgetIntentContract.ACTION_START, "swim")
        assertTrue(unknown != null && unknown.start == null)
        val open = WorkoutWidgetIntentContract.launchFrom(WorkoutWidgetIntentContract.ACTION_OPEN, null)
        assertTrue(open != null && open.start == null)
        assertNull(WorkoutWidgetIntentContract.launchFrom("android.intent.action.MAIN", "run"))
        assertNull(WorkoutWidgetIntentContract.launchFrom(null, null))
        assertEquals(listOf("walk", "run", "cycle", "hike", null), WorkoutWidgetStart.entries.map { it.gpsSport })
    }
}
