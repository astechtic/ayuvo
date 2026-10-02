package com.ayuvo.health.vitals.session

import com.ayuvo.health.vitals.camera.FaceFrame
import com.ayuvo.health.vitals.camera.FaceFrameBuffer
import com.ayuvo.health.vitals.camera.FingerFrame
import com.ayuvo.health.vitals.camera.VitalFrame
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsAnalysis
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFace
import com.ayuvo.health.vitals.engine.VitalsFaceFrames
import com.ayuvo.health.vitals.engine.VitalsSignal

/**
 * Live control of one scan (docs/camera-vitals.md §3.2, §7.1 "Live"), as a pure state machine driven by frames so
 * it can be tested with replay frames and no clock:
 *
 * - The guidance is the first failing gate of the latest frame (`finger_detect` / `face_frame_gate`), else `ok`.
 * - The scan clock starts once the gates have passed for [startAfterMs] (2 s); from then on every frame is buffered
 *   for the engine (failing frames too: the engine masks them and reports the honest reject reason).
 * - The clock pauses while the gates fail for more than [pauseAfterMs] (3 s) and resumes when they pass again.
 * - At `target_s` of clock time [onFrame] asks for an analysis ([Action.Analyse]); [onAnalysis] applies
 *   `scan_control`: finish, or extend and re-check every [recheckS] (5 s) up to `max_s`.
 *
 * Not thread-safe: the owner feeds frames and results from one thread at a time.
 */
class ScanSession(
    val mode: VitalsMode,
    private val cfg: VitalsConfig,
    private val startAfterMs: Double = 2_000.0,
    private val pauseAfterMs: Double = 3_000.0,
    private val recheckS: Double = 5.0,
    private val waveformS: Double = 8.0
) {
    enum class Phase { WAITING, MEASURING, PAUSED, FINISHED, CANCELLED }

    sealed interface Action {
        data object None : Action
        /** Analyse this snapshot of the buffered frames (clock time [elapsedS]) and call [onAnalysis]. */
        class Analyse(val input: ScanInput, val elapsedS: Double) : Action
    }

    /** Frames handed to the engine: finger rows or face frames, never both. */
    class ScanInput(val mode: VitalsMode, val finger: List<DoubleArray>, val face: List<FaceFrame>, private val roiOrder: List<String>) {
        val size: Int get() = if (mode == VitalsMode.FINGER) finger.size else face.size
        val durationS: Double get() {
            val t = if (mode == VitalsMode.FINGER) finger.map { it[0] } else face.map { it.tMs }
            return if (t.size < 2) 0.0 else (t.last() - t.first()) / 1000.0
        }
        fun faceFrames(): VitalsFaceFrames = FaceFrameBuffer.toEngine(face, roiOrder)
    }

    var phase: Phase = Phase.WAITING
        private set
    /** Guidance key of the latest frame (`vitals.guidance.<key>`). */
    var guidance: String = GUIDANCE_WAITING
        private set
    /** Clock time in seconds (only advances while measuring). */
    var elapsedS: Double = 0.0
        private set
    var nextCheckS: Double = cfg.scan.targetS
        private set
    /** True once a check decided to extend past target_s. */
    var extending: Boolean = false
        private set
    var analysisPending: Boolean = false
        private set
    /** The analysed input that finished the scan. */
    var finalInput: ScanInput? = null
        private set
    var finalAnalysis: VitalsAnalysis? = null
        private set
    /** True once the gates passed for the first time (face mode locks exposure 2 s later). */
    var gatesEverPassed: Boolean = false
        private set

    private val fingerBuffer = ArrayList<DoubleArray>()
    private val faceBuffer = ArrayList<FaceFrame>()
    private val recent = ArrayDeque<VitalFrame>()
    private val recentPass = ArrayDeque<Pair<Double, Boolean>>()
    private var passSinceMs: Double? = null
    private var failSinceMs: Double? = null
    private var lastTMs: Double? = null
    private val roiOrder: List<String> = cfg.face.rois

    val bufferedFrames: Int get() = if (mode == VitalsMode.FINGER) fingerBuffer.size else faceBuffer.size

    /** Share of the last 5 s of frames that passed the gates (0..1); the live "signal" chip. */
    val signalShare: Double get() = if (recentPass.isEmpty()) 0.0 else recentPass.count { it.second }.toDouble() / recentPass.size

    fun onFrame(frame: VitalFrame): Action {
        if (phase == Phase.FINISHED || phase == Phase.CANCELLED) return Action.None
        val t = frame.tMs
        val gate = gateOf(frame)
        val pass = gate == null
        guidance = gate ?: GUIDANCE_OK
        if (pass) gatesEverPassed = true
        recent.addLast(frame)
        while (recent.isNotEmpty() && t - recent.first().tMs > waveformS * 1000.0) recent.removeFirst()
        recentPass.addLast(t to pass)
        while (recentPass.isNotEmpty() && t - recentPass.first().first > SIGNAL_WINDOW_MS) recentPass.removeFirst()

        if (pass) {
            if (passSinceMs == null) passSinceMs = t
            failSinceMs = null
        } else {
            if (failSinceMs == null) failSinceMs = t
            passSinceMs = null
        }
        val dt = lastTMs?.let { ((t - it) / 1000.0).coerceAtLeast(0.0) } ?: 0.0
        lastTMs = t

        when (phase) {
            Phase.WAITING -> {
                val since = passSinceMs
                if (since != null && t - since >= startAfterMs) {
                    phase = Phase.MEASURING
                    buffer(frame)
                }
                return Action.None
            }
            Phase.MEASURING -> {
                buffer(frame)
                val failing = failSinceMs
                if (failing != null && t - failing > pauseAfterMs) {
                    phase = Phase.PAUSED
                    return Action.None
                }
                elapsedS += dt
            }
            Phase.PAUSED -> {
                buffer(frame)
                if (!pass) return Action.None
                phase = Phase.MEASURING
            }
            else -> return Action.None
        }
        if (!analysisPending && elapsedS >= nextCheckS) {
            analysisPending = true
            return Action.Analyse(snapshot(), elapsedS)
        }
        return Action.None
    }

    /** Applies `scan_control` to an analysis requested at clock time [elapsedAtRequest]; returns the decision. */
    fun onAnalysis(input: ScanInput, analysis: VitalsAnalysis, elapsedAtRequest: Double): String {
        analysisPending = false
        if (phase == Phase.FINISHED || phase == Phase.CANCELLED) return "finish"
        val quality = if (analysis.rejectReason != null) null else analysis.qualityScore
        val decision = VitalsEngine.scanControl(elapsedAtRequest, quality, cfg)
        if (decision == "finish") {
            phase = Phase.FINISHED
            finalInput = input
            finalAnalysis = analysis
        } else {
            extending = true
            nextCheckS = minOf(elapsedAtRequest + recheckS, cfg.scan.maxS)
        }
        return decision
    }

    fun cancel() {
        phase = Phase.CANCELLED
    }

    fun snapshot(): ScanInput = ScanInput(mode, fingerBuffer.map { it.copyOf() }, ArrayList(faceBuffer), roiOrder)

    /** The last ~8 s as a band-passed, normalised pulse waveform (inverted so a beat points up); empty when too short. */
    fun liveWaveform(): DoubleArray {
        if (recent.size < LIVE_MIN_FRAMES) return DoubleArray(0)
        val t = DoubleArray(recent.size) { recent.elementAt(it).tMs }
        val raw = DoubleArray(recent.size) { i ->
            when (val f = recent.elementAt(i)) {
                is FingerFrame -> f.row[1]
                is FaceFrame -> {
                    var acc = 0.0
                    var n = 0
                    for (name in roiOrder) {
                        val v = f.rois[name] ?: continue
                        acc += v[1]
                        n += 1
                    }
                    if (n == 0) 0.0 else acc / n
                }
            }
        }
        if (t.last() - t.first() < 1000.0) return DoubleArray(0)
        val valid = BooleanArray(raw.size) { true }
        val rs = VitalsSignal.resampleUniform(t, raw, valid, cfg.signal.fs, Double.MAX_VALUE)
        if (rs.values.size < 3) return DoubleArray(0)
        return VitalsSignal.preprocess(rs.values, cfg.signal.fs, cfg, true)
    }

    private fun buffer(frame: VitalFrame) {
        when (frame) {
            is FingerFrame -> fingerBuffer.add(frame.row)
            is FaceFrame -> faceBuffer.add(frame)
        }
    }

    /** Guidance key of the first failing gate, or null when the frame passes. */
    private fun gateOf(frame: VitalFrame): String? = when (frame) {
        is FingerFrame -> VitalsEngine.fingerDetect(frame.row, cfg).takeIf { it != "ok" }
        is FaceFrame -> {
            val one = FaceFrameBuffer.toEngine(listOf(frame), roiOrder)
            val names = roiOrder.filter { one.rois.containsKey(it) }
            if (names.isEmpty()) "face_none" else VitalsFace.faceFrameGate(one, 0, names, cfg)
        }
    }

    companion object {
        const val GUIDANCE_OK = "ok"
        /** Before the first frame: the mode's own placement hint is shown. */
        const val GUIDANCE_WAITING = "waiting"
        private const val SIGNAL_WINDOW_MS = 5_000.0
        private const val LIVE_MIN_FRAMES = 30
    }
}
