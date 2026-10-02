package com.ayuvo.health.vitals.camera

import android.content.Context
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.ayuvo.health.vitals.face.FaceFrameProcessor
import com.ayuvo.health.vitals.finger.FingerFrameProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Real camera frames: rear camera + torch for finger PPG, front camera + ML Kit for face rPPG
 * (docs/camera-vitals.md §7.1). Exposure and white balance lock 2 s after the torch comes on (finger) or 2 s after
 * the face gates first pass (face). [onCapabilities] receives the camera capabilities once the camera is bound
 * (the caller upserts `vital_device_profiles`); [onError] reports a camera that could not be opened.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraFrameSource(
    context: Context,
    override val mode: VitalsMode,
    private val owner: LifecycleOwner,
    private val preview: Preview.SurfaceProvider?,
    private val roiNames: List<String>,
    private val scope: CoroutineScope,
    private val onCapabilities: (CameraCapabilities, JsonObject) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {}
) : FrameSource {
    override val isReplay: Boolean = false
    private val controller = CameraController(
        context.applicationContext,
        if (mode == VitalsMode.FACE) CameraController.POSITION_FRONT else CameraController.POSITION_BACK
    )
    private val finger = FingerFrameProcessor()
    private var face: FaceFrameProcessor? = null
    private var lockJob: Job? = null
    private var torchUsed = false
    @Volatile private var running = false

    override fun start(sink: (VitalFrame) -> Unit) {
        running = true
        scope.launch(Dispatchers.Main) {
            try {
                val processor = if (mode == VitalsMode.FACE) FaceFrameProcessor(roiNames, controller.analysisExecutor) else null
                face = processor
                val caps = controller.start(owner, if (mode == VitalsMode.FACE) preview else null) { image ->
                    if (!running) {
                        image.close()
                    } else if (processor != null) {
                        processor.process(image) { frame -> if (running) sink(frame) }
                    } else {
                        try {
                            sink(FingerFrame(finger.frame(YuvFrame.from(image), image.imageInfo.timestamp)))
                        } finally {
                            image.close()
                        }
                    }
                }
                onCapabilities(caps, caps.toJson(null, null))
                if (mode == VitalsMode.FINGER) {
                    controller.setTorch(true)
                    torchUsed = controller.torchOn
                    lockJob = scope.launch(Dispatchers.Main) {
                        delay(SETTLE_MS)
                        controller.lockExposureAndWhiteBalance()
                    }
                }
            } catch (t: Throwable) {
                running = false
                onError(t)
            }
        }
    }

    override fun onGatesFirstPassed() {
        if (mode != VitalsMode.FACE || lockJob != null) return
        lockJob = scope.launch(Dispatchers.Main) {
            delay(SETTLE_MS)
            controller.lockExposureAndWhiteBalance()
        }
    }

    override fun stop() {
        running = false
        lockJob?.cancel()
        controller.stop()
        face?.close()
        face = null
    }

    override fun cameraJson(frames: Int, durationS: Double): JsonObject = controller.cameraJson(frames, durationS, torchUsed)

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
