package com.ayuvo.health.vitals.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ayuvo.health.vitals.engine.VitalsJson
import com.ayuvo.health.vitals.engine.VitalsMath
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Camera capability info for `vital_device_profiles.capability_json`. */
class CameraCapabilities(
    val position: String,
    val aeFpsRanges: List<IntArray>,
    val chosenFpsRange: IntArray?,
    val torchAvailable: Boolean,
    val hardwareLevel: Int?,
    val sensorOrientation: Int?
) {
    fun toJson(width: Int?, height: Int?): JsonObject = VitalsJson.obj(
        "position" to position,
        "ae_fps_ranges" to aeFpsRanges.map { listOf(it[0], it[1]) },
        "chosen_fps_range" to chosenFpsRange?.let { listOf(it[0], it[1]) },
        "torch" to torchAvailable,
        "hardware_level" to hardwareLevel,
        "sensor_orientation" to sensorOrientation,
        "analysis_width" to width,
        "analysis_height" to height
    )
}

/**
 * CameraX acquisition for camera vitals (docs/camera-vitals.md §7.1 "Acquisition"): `ImageAnalysis` with
 * YUV_420_888 near 640x480 and keep-only-latest on its own executor; Camera2 interop sets
 * `CONTROL_AE_TARGET_FPS_RANGE` to [30,30] (or the closest supported range ending at 30) and a session capture
 * callback records exposure, ISO and frame duration. Torch through `enableTorch`, AE/AWB lock through
 * `Camera2CameraControl` when the flow asks for it. Frames are analysed and closed; nothing is recorded.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraController(private val context: Context, val position: String) {
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ayuvo-vitals-camera") }

    @Volatile var exposureNs: Long? = null
        private set
    @Volatile var iso: Int? = null
        private set
    @Volatile var frameDurationNs: Long? = null
        private set
    @Volatile var whiteBalanceLocked: Boolean = false
        private set
    @Volatile var torchOn: Boolean = false
        private set
    @Volatile var analysisSize: Size? = null
        private set
    var capabilities: CameraCapabilities? = null
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
            result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { frameDurationNs = it }
        }
    }

    private val selector: CameraSelector
        get() = if (position == POSITION_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    /** Binds the use cases to [owner]; [preview] is only used in face mode. Throws when no camera can be bound. */
    suspend fun start(owner: LifecycleOwner, preview: Preview.SurfaceProvider?, analyzer: ImageAnalysis.Analyzer): CameraCapabilities {
        val cameraProvider = awaitProvider(context)
        provider = cameraProvider
        val info = cameraProvider.availableCameraInfos.firstOrNull { selector.filter(listOf(it)).isNotEmpty() }
            ?: error("no ${position} camera")
        val caps = capabilitiesOf(info)
        capabilities = caps
        val range = caps.chosenFpsRange?.let { Range(it[0], it[1]) }

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolution)
        Camera2Interop.Extender(analysisBuilder).apply {
            if (range != null) setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            setSessionCaptureCallback(captureCallback)
        }
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            if (analysisSize == null) analysisSize = Size(image.width, image.height)
            analyzer.analyze(image)
        }
        val useCases = mutableListOf<UseCase>(analysis)
        if (preview != null) {
            val previewBuilder = Preview.Builder().setResolutionSelector(resolution)
            if (range != null) Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            useCases += previewBuilder.build().also { it.surfaceProvider = preview }
        }
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
        return caps
    }

    fun setTorch(on: Boolean) {
        val cam = camera ?: return
        if (capabilities?.torchAvailable != true) return
        cam.cameraControl.enableTorch(on)
        torchOn = on
    }

    /** CONTROL_AE_LOCK + CONTROL_AWB_LOCK through Camera2CameraControl (§7.1 "Exposure lock"). */
    fun lockExposureAndWhiteBalance() {
        val cam = camera ?: return
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build()
            )
            whiteBalanceLocked = true
        }
    }

    fun stop() {
        runCatching { if (torchOn) camera?.cameraControl?.enableTorch(false) }
        torchOn = false
        runCatching { provider?.unbindAll() }
        camera = null
        analysisExecutor.shutdown()
    }

    /** `camera_json` of §7.1: position, lens, width, height, target_fps, achieved_fps, exposure_ms, iso, white_balance_locked, torch. */
    fun cameraJson(frames: Int, durationS: Double, torchUsed: Boolean): JsonObject {
        val size = analysisSize
        return VitalsJson.obj(
            "position" to position,
            "lens" to (if (position == POSITION_FRONT) "front_wide" else "back_wide"),
            "width" to size?.width,
            "height" to size?.height,
            "target_fps" to TARGET_FPS,
            "achieved_fps" to achievedFps(frames, durationS),
            "exposure_ms" to exposureNs?.let { VitalsMath.roundTo(it / 1_000_000.0, 3) },
            "iso" to iso,
            "white_balance_locked" to whiteBalanceLocked,
            "torch" to torchUsed
        )
    }

    private fun capabilitiesOf(info: CameraInfo): CameraCapabilities {
        val c2 = Camera2CameraInfo.from(info)
        val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { intArrayOf(it.lower, it.upper) }.orEmpty()
        return CameraCapabilities(
            position = position,
            aeFpsRanges = ranges,
            chosenFpsRange = chooseFpsRange(ranges),
            torchAvailable = info.hasFlashUnit(),
            hardwareLevel = c2.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            sensorOrientation = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
        )
    }

    companion object {
        const val POSITION_BACK = "back"
        const val POSITION_FRONT = "front"
        const val TARGET_FPS = 30

        /** `Build.MANUFACTURER + " " + Build.MODEL` (§7.1 "Saved record"). */
        val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

        /** [30,30] when supported, else the supported range ending at 30 with the highest floor; null when none ends at 30. */
        fun chooseFpsRange(ranges: List<IntArray>): IntArray? {
            ranges.firstOrNull { it[0] == TARGET_FPS && it[1] == TARGET_FPS }?.let { return it }
            return ranges.filter { it[1] == TARGET_FPS }.maxByOrNull { it[0] }
        }

        fun achievedFps(frames: Int, durationS: Double): Double? =
            if (frames < 2 || durationS <= 0.0) null else VitalsMath.roundTo((frames - 1) / durationS, 2)

        private suspend fun awaitProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
}
