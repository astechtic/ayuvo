package com.ayuvo.health.vitals.camera

import android.content.Context
import android.content.pm.ApplicationInfo
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsSynth
import com.ayuvo.health.vitals.engine.VitalsSynthSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Synthetic frames from the engine's `synth_finger` / `synth_face` generators, delivered in real time at the
 * config's 30 Hz (docs/camera-vitals.md §7.1 "Replay source"). Debug builds, the emulator and UI tests run the whole
 * flow with it; it never touches the camera.
 */
class ReplayFrameSource(
    override val mode: VitalsMode,
    private val cfg: VitalsConfig,
    private val scope: CoroutineScope,
    /** 1.0 = real time; tests may run faster. */
    private val speed: Double = 1.0
) : FrameSource {
    override val isReplay: Boolean = true
    private var job: Job? = null

    override fun start(sink: (VitalFrame) -> Unit) {
        job = scope.launch(Dispatchers.Default) {
            val frames = frames(mode, cfg)
            val startNs = System.nanoTime()
            for (frame in frames) {
                val dueMs = frame.tMs / speed
                val waitMs = dueMs - (System.nanoTime() - startNs) / 1_000_000.0
                if (waitMs > 0) delay(waitMs.toLong())
                if (!isActive) break
                sink(frame)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun cameraJson(frames: Int, durationS: Double): JsonObject = replayCameraJson(mode, frames, durationS)

    companion object {
        /** Long enough for a full extension to max_s plus the 2 s gate pre-roll. */
        private fun durationS(cfg: VitalsConfig): Double = cfg.scan.maxS + 15.0

        fun fingerSpec(cfg: VitalsConfig): VitalsSynthSpec = VitalsSynthSpec(
            fs = cfg.signal.fs, durationS = durationS(cfg), hrBpm = 66.0, rsaMs = 45.0, ibiJitterMs = 12.0, respBpm = 14.0, seed = 11
        )

        fun faceSpec(cfg: VitalsConfig): VitalsSynthSpec = VitalsSynthSpec(
            fs = cfg.signal.fs, durationS = durationS(cfg), hrBpm = 72.0, rsaMs = 30.0, ibiJitterMs = 10.0, respBpm = 15.0, seed = 23,
            rois = cfg.face.rois.associateWith { (if (it == "forehead" || it.endsWith("cheek")) 0.012 else 0.008) to 0.0008 }
        )

        fun frames(mode: VitalsMode, cfg: VitalsConfig): List<VitalFrame> = when (mode) {
            VitalsMode.FINGER -> VitalsSynth.synthFinger(fingerSpec(cfg)).map { FingerFrame(it) }
            VitalsMode.FACE -> {
                val f = VitalsSynth.synthFace(faceSpec(cfg))
                List(f.tMs.size) { i ->
                    FaceFrame(
                        tMs = f.tMs[i], motion = f.motion[i], yaw = f.yaw[i], pitch = f.pitch[i], luma = f.luma[i],
                        faceCount = f.faceCount[i], faceFraction = f.faceFraction[i],
                        rois = f.rois.mapValues { (_, rows) -> rows[i] }
                    )
                }
            }
        }

        fun replayCameraJson(mode: VitalsMode, frames: Int, durationS: Double): JsonObject = com.ayuvo.health.vitals.engine.VitalsJson.obj(
            "position" to (if (mode == VitalsMode.FACE) CameraController.POSITION_FRONT else CameraController.POSITION_BACK),
            "lens" to "replay",
            "width" to null,
            "height" to null,
            "target_fps" to CameraController.TARGET_FPS,
            "achieved_fps" to CameraController.achievedFps(frames, durationS),
            "exposure_ms" to null,
            "iso" to null,
            "white_balance_locked" to false,
            "torch" to false
        )
    }
}

/**
 * Debug-only replay switch (§7.1 "Replay source"): the `vitals_replay` intent extra (`finger` | `face` | `off`)
 * stores the setting; while it is set, every scan of that mode runs on [ReplayFrameSource] instead of the camera.
 * Release builds (not debuggable) ignore it.
 */
object VitalsReplay {
    const val EXTRA = "vitals_replay"
    private const val PREFS = "ayuvo_vitals_debug"
    private const val KEY = "vitals_replay"

    fun isDebuggable(context: Context): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Modes that replay, from the stored debug setting. */
    fun modes(context: Context): Set<VitalsMode> {
        if (!isDebuggable(context)) return emptySet()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptySet()
        return raw.split(',').mapNotNull { VitalsMode.fromId(it.trim()) }.toSet()
    }

    fun replays(context: Context, mode: VitalsMode): Boolean = mode in modes(context)

    /** Applies an intent extra value; `off` (or blank) clears the setting. */
    fun apply(context: Context, value: String?) {
        if (value == null || !isDebuggable(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val v = value.trim().lowercase()
        if (v.isEmpty() || v == "off" || v == "none") prefs.remove(KEY) else prefs.putString(KEY, if (v == "both" || v == "all") "finger,face" else v)
        prefs.apply()
    }
}
