package com.ayuvo.health.vitals.face

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.ayuvo.health.vitals.camera.FaceFrame
import com.ayuvo.health.vitals.camera.YuvFrame
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Face frame statistics from CameraX frames with bundled ML Kit face detection (docs/camera-vitals.md §7.1
 * "Acquisition": `PERFORMANCE_MODE_FAST`, `CONTOUR_MODE_ALL`, tracking enabled).
 *
 * ML Kit returns contours for the most prominent face only, so a second detector without contours counts the faces
 * every [countEvery] frames; the gate uses the larger of the two counts. Statistics are computed from the same
 * [ImageProxy] before it is closed; no pixels leave this class.
 */
class FaceFrameProcessor(
    private val roiNames: List<String>,
    private val executor: Executor,
    private val countEvery: Int = 5
) : Closeable {
    private val contourDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .enableTracking()
            .build()
    )
    private val countDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .build()
    )
    private val rois = SkinRoiExtractor(roiNames)
    private val motion = MotionCompensator()
    private var firstTimestampNs: Long? = null
    private var frameIndex = 0
    @Volatile private var lastCount = 0

    /** Detects, measures and closes [image]; [onFrame] runs on [executor]. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    fun process(image: ImageProxy, onFrame: (FaceFrame) -> Unit) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val ts = image.imageInfo.timestamp
        val t0 = firstTimestampNs ?: ts.also { firstTimestampNs = it }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        val countNow = frameIndex % countEvery == 0
        frameIndex += 1
        val contourTask = contourDetector.process(input)
        val countTask = if (countNow) countDetector.process(input) else null
        // The image may only close once every detector that reads it has finished.
        Tasks.whenAllComplete(listOfNotNull(contourTask, countTask)).addOnCompleteListener(executor) {
            try {
                if (countTask != null && countTask.isSuccessful) lastCount = countTask.result.size
                if (contourTask.isSuccessful) {
                    val frame = YuvFrame.from(image)
                    onFrame(measure(frame, contourTask.result.map(::geometry), (ts - t0) / 1_000_000.0))
                }
            } finally {
                image.close()
            }
        }
    }

    /** Pure part of [process]: one frame from the detected faces (largest first is not assumed). */
    fun measure(frame: YuvFrame, faces: List<FaceGeometry>, tMs: Double): FaceFrame {
        val count = maxOf(faces.size, lastCount)
        val face = faces.maxByOrNull { it.width }
        if (face == null || faces.size != 1 || count != 1) {
            if (face == null) {
                rois.reset()
                motion.reset()
            }
            return FaceFrame(
                tMs = tMs, motion = 0.0, yaw = 0.0, pitch = 0.0,
                luma = MotionCompensator.meanLuma(frame, 0, 0, frame.uprightWidth - 1, frame.uprightHeight - 1, 8),
                faceCount = count, faceFraction = (face?.width ?: 0f).toDouble() / frame.uprightWidth,
                rois = roiNames.associateWith { DoubleArray(4) }
            )
        }
        val polygons = rois.polygons(face)
        latestPolygons = polygons
        return FaceFrame(
            tMs = tMs,
            motion = motion.motion(face),
            yaw = face.yaw.toDouble(),
            pitch = face.pitch.toDouble(),
            luma = MotionCompensator.luma(frame, face),
            faceCount = 1,
            faceFraction = face.width.toDouble() / frame.uprightWidth,
            rois = rois.measure(frame, polygons)
        )
    }

    /** The last smoothed ROI quads (upright coordinates), for debugging overlays only. */
    @Volatile var latestPolygons: Map<String, List<Pt>> = emptyMap()
        private set

    override fun close() {
        runCatching { contourDetector.close() }
        runCatching { countDetector.close() }
    }

    companion object {
        private val CONTOURS = mapOf(
            FaceContour.FACE to FaceGeometry.FACE,
            FaceContour.LEFT_EYEBROW_TOP to FaceGeometry.LEFT_EYEBROW_TOP,
            FaceContour.RIGHT_EYEBROW_TOP to FaceGeometry.RIGHT_EYEBROW_TOP,
            FaceContour.LEFT_EYE to FaceGeometry.LEFT_EYE,
            FaceContour.RIGHT_EYE to FaceGeometry.RIGHT_EYE,
            FaceContour.NOSE_BRIDGE to FaceGeometry.NOSE_BRIDGE,
            FaceContour.NOSE_BOTTOM to FaceGeometry.NOSE_BOTTOM,
            FaceContour.LOWER_LIP_BOTTOM to FaceGeometry.LOWER_LIP_BOTTOM,
            FaceContour.LEFT_CHEEK to FaceGeometry.LEFT_CHEEK,
            FaceContour.RIGHT_CHEEK to FaceGeometry.RIGHT_CHEEK
        )

        fun geometry(face: Face): FaceGeometry {
            val contours = LinkedHashMap<String, List<Pt>>()
            for ((type, name) in CONTOURS) {
                val points = face.getContour(type)?.points ?: continue
                contours[name] = points.map { Pt(it.x, it.y) }
            }
            val box = face.boundingBox
            return FaceGeometry(
                left = box.left.toFloat(), top = box.top.toFloat(), right = box.right.toFloat(), bottom = box.bottom.toFloat(),
                yaw = face.headEulerAngleY, pitch = face.headEulerAngleX, trackingId = face.trackingId, contours = contours
            )
        }
    }
}
