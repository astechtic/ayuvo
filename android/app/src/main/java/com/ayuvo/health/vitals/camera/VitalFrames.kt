package com.ayuvo.health.vitals.camera

import com.ayuvo.health.vitals.engine.VitalsFaceFrames
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.serialization.json.JsonObject

/** Scan mode of the camera vitals flow (docs/camera-vitals.md §7.1). [storage] is the `vital_scans.mode` value. */
enum class VitalsMode(val id: String, val storage: String) {
    FINGER("finger", VitalScanRecord.MODE_FINGER),
    FACE("face", VitalScanRecord.MODE_FACE);

    companion object {
        fun fromId(id: String?): VitalsMode? = entries.firstOrNull { it.id == id || it.storage == id }
    }
}

/**
 * One frame's statistics as the engine consumes them (§2.1). Platforms hand the engine statistics, never images:
 * nothing here holds pixels.
 */
sealed interface VitalFrame {
    /** Milliseconds since the source's first frame. */
    val tMs: Double
}

/** Finger frame `[t_ms, r, g, b, r_std, sat_frac]`. */
class FingerFrame(val row: DoubleArray) : VitalFrame {
    init {
        require(row.size == 6) { "finger frame needs 6 values" }
    }

    override val tMs: Double get() = row[0]
}

/** Face frame: pose, motion, luma and gate inputs plus `[r, g, b, skin_frac]` per ROI (config order). */
class FaceFrame(
    override val tMs: Double,
    val motion: Double,
    val yaw: Double,
    val pitch: Double,
    val luma: Double,
    val faceCount: Int,
    val faceFraction: Double,
    val rois: Map<String, DoubleArray>
) : VitalFrame

/** Collects face frames into the engine's parallel-array encoding. */
object FaceFrameBuffer {
    fun toEngine(frames: List<FaceFrame>, roiOrder: List<String>): VitalsFaceFrames {
        val n = frames.size
        val rois = LinkedHashMap<String, List<DoubleArray>>()
        for (name in roiOrder) {
            if (frames.isNotEmpty() && frames.all { it.rois.containsKey(name) }) {
                rois[name] = frames.map { it.rois.getValue(name).copyOf() }
            }
        }
        return VitalsFaceFrames(
            tMs = DoubleArray(n) { frames[it].tMs },
            rois = rois,
            motion = DoubleArray(n) { frames[it].motion },
            yaw = DoubleArray(n) { frames[it].yaw },
            pitch = DoubleArray(n) { frames[it].pitch },
            luma = DoubleArray(n) { frames[it].luma },
            faceCount = IntArray(n) { frames[it].faceCount },
            faceFraction = DoubleArray(n) { frames[it].faceFraction }
        )
    }
}

/**
 * Where frames come from: the camera ([CameraFrameSource]) or synthetic replay ([ReplayFrameSource], debug builds,
 * emulator and UI tests). [start] delivers frames on a background thread, in time order.
 */
interface FrameSource {
    val mode: VitalsMode
    val isReplay: Boolean

    fun start(sink: (VitalFrame) -> Unit)

    fun stop()

    /** The face gates passed for the first time (face mode locks exposure 2 s later, §7.1 "Exposure lock"). */
    fun onGatesFirstPassed() {}

    /** `camera_json` for a scan of [frames] frames over [durationS] seconds (§7.1 "Saved record"). */
    fun cameraJson(frames: Int, durationS: Double): JsonObject
}
