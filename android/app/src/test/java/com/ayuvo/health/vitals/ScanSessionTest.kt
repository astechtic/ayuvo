package com.ayuvo.health.vitals

import com.ayuvo.health.vitals.camera.FingerFrame
import com.ayuvo.health.vitals.camera.ReplayFrameSource
import com.ayuvo.health.vitals.camera.VitalFrame
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsSynth
import com.ayuvo.health.vitals.engine.VitalsSynthEvent
import com.ayuvo.health.vitals.session.ScanSession
import com.ayuvo.health.vitals.session.VitalsAnalysisOptions
import com.ayuvo.health.vitals.session.VitalsAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/camera-vitals.md §3.2 / §7.1 "Live": the scan state machine driven by injected replay frames. */
class ScanSessionTest {
    private val cfg = VitalsTestFiles.config

    private class Run(val session: ScanSession, val decisions: List<Pair<Double, String>>, val phases: List<ScanSession.Phase>)

    /** Feeds [frames] in order, running each requested analysis synchronously, until the session finishes. */
    private fun drive(mode: VitalsMode, frames: List<VitalFrame>): Run {
        val session = ScanSession(mode, cfg)
        val decisions = ArrayList<Pair<Double, String>>()
        val phases = ArrayList<ScanSession.Phase>()
        for (f in frames) {
            val action = session.onFrame(f)
            phases += session.phase
            if (action is ScanSession.Action.Analyse) {
                val analysis = VitalsAnalyzer.analyze(action.input, VitalsAnalysisOptions(), cfg)
                decisions += action.elapsedS to session.onAnalysis(action.input, analysis, action.elapsedS)
            }
            if (session.phase == ScanSession.Phase.FINISHED) break
        }
        return Run(session, decisions, phases)
    }

    @Test
    fun goodFingerReplayFinishesAtTarget() {
        val run = drive(VitalsMode.FINGER, ReplayFrameSource.frames(VitalsMode.FINGER, cfg))
        val s = run.session
        assertEquals(ScanSession.Phase.FINISHED, s.phase)
        assertEquals(1, run.decisions.size)
        assertEquals("finish", run.decisions[0].second)
        assertEquals(cfg.scan.targetS, run.decisions[0].first, 0.05)
        val analysis = s.finalAnalysis!!
        assertNull(analysis.rejectReason)
        assertTrue("quality ${analysis.qualityScore}", analysis.qualityScore >= cfg.scan.extendBelowQuality)
        assertEquals(66.0, analysis.value("heart_rate")!!, 2.0)
        assertNotNull(analysis.value("hrv_rmssd"))
        assertEquals(ScanSession.GUIDANCE_OK, s.guidance)
        // The clock started after 2 s of passing gates, so the buffer begins at ~2 s.
        val first = s.finalInput!!.finger.first()[0]
        assertTrue("first buffered frame at $first ms", first in 1900.0..2100.0)
    }

    @Test
    fun goodFaceReplayFinishesAtTarget() {
        val run = drive(VitalsMode.FACE, ReplayFrameSource.frames(VitalsMode.FACE, cfg))
        val s = run.session
        assertEquals(ScanSession.Phase.FINISHED, s.phase)
        assertEquals(listOf("finish"), run.decisions.map { it.second })
        val analysis = s.finalAnalysis!!
        assertNull(analysis.rejectReason)
        assertEquals(72.0, analysis.value("heart_rate")!!, 2.0)
        assertEquals("face_not_supported", analysis.metric("spo2")!!["reason"].toString().trim('"'))
    }

    @Test
    fun poorSignalExtendsEveryFiveSecondsUntilMax() {
        // Strong periodic motion keeps the quality below extend_below_quality without failing the finger gate.
        val spec = ReplayFrameSource.fingerSpec(cfg).copy(noise = 0.004, events = List(12) { k ->
            VitalsSynthEvent("motion", 6.0 + k * 8.0, 9.0 + k * 8.0, 0.03)
        })
        val frames = VitalsSynth.synthFinger(spec).map { FingerFrame(it) }
        val run = drive(VitalsMode.FINGER, frames)
        val decisions = run.decisions.map { it.second }
        assertTrue("decisions $decisions", decisions.first() == "extend")
        assertEquals("finish", decisions.last())
        val times = run.decisions.map { it.first }
        assertEquals(cfg.scan.targetS, times.first(), 0.05)
        // Re-checks every 5 s; the last one is capped at max_s.
        for (k in 1 until times.size - 1) assertEquals(5.0, times[k] - times[k - 1], 0.05)
        assertTrue(times.last() - times[times.size - 2] <= 5.05)
        assertEquals(cfg.scan.maxS, times.last(), 0.05)
        assertTrue(run.session.extending)
    }

    @Test
    fun removedFingerPausesTheClockAndShowsGuidance() {
        val spec = ReplayFrameSource.fingerSpec(cfg).copy(events = listOf(VitalsSynthEvent("no_finger", 20.0, 28.0, 0.0)))
        val frames = VitalsSynth.synthFinger(spec).map { FingerFrame(it) }
        val session = ScanSession(VitalsMode.FINGER, cfg)
        var elapsedAtPause = -1.0
        var sawPause = false
        for (f in frames) {
            val action = session.onFrame(f)
            if (action is ScanSession.Action.Analyse) break
            val t = f.tMs / 1000.0
            if (t in 21.0..23.0) assertEquals("no_finger", session.guidance)
            if (session.phase == ScanSession.Phase.PAUSED) {
                if (!sawPause) elapsedAtPause = session.elapsedS
                sawPause = true
                // Paused: the clock stands still.
                assertEquals(elapsedAtPause, session.elapsedS, 1e-9)
            }
            if (t in 23.5..27.5) assertEquals(ScanSession.Phase.PAUSED, session.phase)
            if (t > 28.5) assertEquals(ScanSession.Phase.MEASURING, session.phase)
        }
        assertTrue(sawPause)
        // 2 s pre-roll, then 18 s of clock to the removal plus the 3 s grace.
        assertEquals(21.0, elapsedAtPause, 0.2)
    }

    @Test
    fun clockWaitsForTwoSecondsOfPassingGates() {
        val spec = ReplayFrameSource.fingerSpec(cfg).copy(events = listOf(VitalsSynthEvent("no_finger", 0.0, 5.0, 0.0)))
        val session = ScanSession(VitalsMode.FINGER, cfg)
        for (row in VitalsSynth.synthFinger(spec)) {
            session.onFrame(FingerFrame(row))
            val t = row[0] / 1000.0
            if (t < 6.9) assertEquals(ScanSession.Phase.WAITING, session.phase)
            if (t > 7.1) {
                assertEquals(ScanSession.Phase.MEASURING, session.phase)
                break
            }
        }
        assertTrue("live waveform after the pre-roll", session.liveWaveform().isNotEmpty())
    }

    @Test
    fun cancelStopsTheSession() {
        val session = ScanSession(VitalsMode.FINGER, cfg)
        val frames = ReplayFrameSource.frames(VitalsMode.FINGER, cfg)
        for (f in frames.take(200)) session.onFrame(f)
        session.cancel()
        assertEquals(ScanSession.Action.None, session.onFrame(frames[200]))
        assertEquals(ScanSession.Phase.CANCELLED, session.phase)
    }
}
