package com.ayuvo.health.vitals.face

import com.ayuvo.health.vitals.camera.YuvColor
import com.ayuvo.health.vitals.camera.YuvFrame
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A point in upright image coordinates (pixels). */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    fun length(): Float = hypot(x, y)
}

/**
 * One detected face, in upright coordinates, independent of ML Kit types so the geometry is unit-testable.
 * [contours] keys are [FaceGeometry] contour names; [leftEye] etc. are the subject's own sides (ML Kit convention).
 */
class FaceGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val yaw: Float,
    val pitch: Float,
    val trackingId: Int?,
    val contours: Map<String, List<Pt>>
) {
    val width: Float get() = right - left

    fun contour(name: String): List<Pt> = contours[name].orEmpty()

    companion object {
        const val FACE = "face"
        const val LEFT_EYEBROW_TOP = "left_eyebrow_top"
        const val RIGHT_EYEBROW_TOP = "right_eyebrow_top"
        const val LEFT_EYE = "left_eye"
        const val RIGHT_EYE = "right_eye"
        const val NOSE_BRIDGE = "nose_bridge"
        const val NOSE_BOTTOM = "nose_bottom"
        const val LOWER_LIP_BOTTOM = "lower_lip_bottom"
        const val LEFT_CHEEK = "left_cheek"
        const val RIGHT_CHEEK = "right_cheek"

        fun mean(points: List<Pt>): Pt? {
            if (points.isEmpty()) return null
            var x = 0f
            var y = 0f
            for (p in points) {
                x += p.x
                y += p.y
            }
            return Pt(x / points.size, y / points.size)
        }
    }
}

/**
 * Skin ROIs of §7.1 "Face ROIs": forehead (above the eyebrows), left and right cheek, nose and chin, as quads built
 * from the contours. Each quad's corners are smoothed over time with an EMA (α [alpha]); the smoothing restarts when
 * the tracked face changes. Inside a quad the YCbCr skin mask (Cb 77–127, Cr 133–173) is applied and the ROI value
 * is `[r, g, b, skin_frac]`, the mean RGB of the skin pixels on a subsampled grid.
 */
class SkinRoiExtractor(
    private val roiNames: List<String>,
    private val alpha: Float = 0.3f,
    private val step: Int = 2
) {
    private val smoothed = HashMap<String, List<Pt>>()
    private var trackedId: Int? = null

    fun reset() {
        smoothed.clear()
        trackedId = null
    }

    /** Smoothed quads per ROI for [face]; a ROI whose contours are missing keeps its last quad (if any). */
    fun polygons(face: FaceGeometry): Map<String, List<Pt>> {
        if (face.trackingId != null && face.trackingId != trackedId) {
            smoothed.clear()
            trackedId = face.trackingId
        }
        val raw = rawPolygons(face)
        val out = LinkedHashMap<String, List<Pt>>()
        for (name in roiNames) {
            val fresh = raw[name]
            val previous = smoothed[name]
            val next = when {
                fresh == null -> previous
                previous == null || previous.size != fresh.size -> fresh
                else -> List(fresh.size) { i -> previous[i] * (1f - alpha) + fresh[i] * alpha }
            } ?: continue
            smoothed[name] = next
            out[name] = next
        }
        return out
    }

    /** `[r, g, b, skin_frac]` per ROI in config order; a ROI without a polygon reads as zeros (skin 0, so it is gated). */
    fun measure(frame: YuvFrame, polygons: Map<String, List<Pt>>): Map<String, DoubleArray> {
        val out = LinkedHashMap<String, DoubleArray>()
        for (name in roiNames) {
            val poly = polygons[name]
            out[name] = if (poly == null) DoubleArray(4) else sample(frame, poly, step)
        }
        return out
    }

    companion object {
        /** Unsmoothed quads (§7.1); null entries when the contours they need are missing. */
        fun rawPolygons(face: FaceGeometry): Map<String, List<Pt>> {
            val leftEye = FaceGeometry.mean(face.contour(FaceGeometry.LEFT_EYE)) ?: return emptyMap()
            val rightEye = FaceGeometry.mean(face.contour(FaceGeometry.RIGHT_EYE)) ?: return emptyMap()
            val eyeVec = rightEye - leftEye
            val d = eyeVec.length()
            if (d < 1f) return emptyMap()
            val e = eyeVec * (1f / d)
            // Perpendicular to the eye line, pointing up the face (negative image y for an upright head).
            var up = Pt(e.y, -e.x)
            if (up.y > 0f) up = up * -1f
            val down = up * -1f
            val out = LinkedHashMap<String, List<Pt>>()

            fun quad(center: Pt, halfAcross: Float, halfUp: Float): List<Pt> = listOf(
                center - e * halfAcross + up * halfUp,
                center + e * halfAcross + up * halfUp,
                center + e * halfAcross - up * halfUp,
                center - e * halfAcross - up * halfUp
            )

            val brows = face.contour(FaceGeometry.LEFT_EYEBROW_TOP) + face.contour(FaceGeometry.RIGHT_EYEBROW_TOP)
            FaceGeometry.mean(brows)?.let { c -> out["forehead"] = quad(c + up * (0.35f * d), 0.45f * d, 0.2f * d) }

            val leftCheek = FaceGeometry.mean(face.contour(FaceGeometry.LEFT_CHEEK)) ?: (leftEye + down * (0.6f * d))
            val rightCheek = FaceGeometry.mean(face.contour(FaceGeometry.RIGHT_CHEEK)) ?: (rightEye + down * (0.6f * d))
            out["left_cheek"] = quad(leftCheek, 0.2f * d, 0.18f * d)
            out["right_cheek"] = quad(rightCheek, 0.2f * d, 0.18f * d)

            val bridge = face.contour(FaceGeometry.NOSE_BRIDGE)
            val noseBottom = FaceGeometry.mean(face.contour(FaceGeometry.NOSE_BOTTOM))
            if (bridge.size >= 2 && noseBottom != null) {
                val top = bridge[bridge.size / 2]
                val center = (top + noseBottom) * 0.5f
                val half = ((noseBottom - top).length() * 0.5f).coerceAtLeast(0.1f * d)
                out["nose"] = quad(center, 0.12f * d, half)
            }

            val lip = FaceGeometry.mean(face.contour(FaceGeometry.LOWER_LIP_BOTTOM))
            val outline = face.contour(FaceGeometry.FACE)
            if (lip != null && outline.isNotEmpty()) {
                val chinBottom = outline.maxBy { it.x * down.x + it.y * down.y }
                val span = (chinBottom - lip).length()
                if (span > 1f) out["chin"] = quad((lip + chinBottom) * 0.5f, 0.28f * d, span * 0.3f)
            }
            return out
        }

        /** Ray-casting point-in-polygon test. */
        fun inside(p: Pt, poly: List<Pt>): Boolean {
            var c = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[j]
                if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
                j = i
            }
            return c
        }

        /** Mean RGB of the skin pixels inside [poly] (all pixels when none pass the mask) and the skin fraction. */
        fun sample(frame: YuvFrame, poly: List<Pt>, step: Int = 2): DoubleArray {
            val minX = poly.minOf { it.x }.roundToInt().coerceIn(0, frame.uprightWidth - 1)
            val maxX = poly.maxOf { it.x }.roundToInt().coerceIn(0, frame.uprightWidth - 1)
            val minY = poly.minOf { it.y }.roundToInt().coerceIn(0, frame.uprightHeight - 1)
            val maxY = poly.maxOf { it.y }.roundToInt().coerceIn(0, frame.uprightHeight - 1)
            var total = 0
            var skin = 0
            var sr = 0.0
            var sg = 0.0
            var sb = 0.0
            var ar = 0.0
            var ag = 0.0
            var ab = 0.0
            var uy = minY
            while (uy <= maxY) {
                var ux = minX
                while (ux <= maxX) {
                    if (inside(Pt(ux.toFloat(), uy.toFloat()), poly)) {
                        val bx = frame.bufferX(ux, uy)
                        val by = frame.bufferY(ux, uy)
                        val l = frame.luma(bx, by)
                        val cb = frame.cb(bx, by)
                        val cr = frame.cr(bx, by)
                        val r = YuvColor.red(l, cr)
                        val g = YuvColor.green(l, cb, cr)
                        val b = YuvColor.blue(l, cb)
                        total += 1
                        ar += r; ag += g; ab += b
                        if (YuvColor.isSkin(cb, cr)) {
                            skin += 1
                            sr += r; sg += g; sb += b
                        }
                    }
                    ux += step
                }
                uy += step
            }
            if (total == 0) return DoubleArray(4)
            return if (skin > 0) doubleArrayOf(sr / skin, sg / skin, sb / skin, skin.toDouble() / total)
            else doubleArrayOf(ar / total, ag / total, ab / total, 0.0)
        }
    }
}

/**
 * Face motion and pose of §7.1 "Face motion": the mean displacement of the face-outline contour points since the
 * previous frame, divided by the inter-ocular distance. A new tracked face (or a different point count) restarts at 0.
 */
class MotionCompensator {
    private var previous: List<Pt>? = null
    private var previousId: Int? = null

    fun reset() {
        previous = null
        previousId = null
    }

    fun motion(face: FaceGeometry): Double {
        val points = face.contour(FaceGeometry.FACE)
        val leftEye = FaceGeometry.mean(face.contour(FaceGeometry.LEFT_EYE))
        val rightEye = FaceGeometry.mean(face.contour(FaceGeometry.RIGHT_EYE))
        val prev = previous
        val sameFace = face.trackingId == null || previousId == null || face.trackingId == previousId
        previous = points
        previousId = face.trackingId
        if (prev == null || !sameFace || prev.size != points.size || points.isEmpty() || leftEye == null || rightEye == null) return 0.0
        val d = (rightEye - leftEye).length()
        if (d < 1f) return 0.0
        var acc = 0.0
        for (i in points.indices) acc += (points[i] - prev[i]).length()
        return acc / points.size / d
    }

    companion object {
        /** Mean luma over the face box (upright coordinates), subsampled. */
        fun luma(frame: YuvFrame, face: FaceGeometry, step: Int = 4): Double = meanLuma(
            frame,
            face.left.roundToInt(), face.top.roundToInt(), face.right.roundToInt(), face.bottom.roundToInt(), step
        )

        fun meanLuma(frame: YuvFrame, left: Int, top: Int, right: Int, bottom: Int, step: Int = 4): Double {
            val x0 = left.coerceIn(0, frame.uprightWidth - 1)
            val x1 = right.coerceIn(0, frame.uprightWidth - 1)
            val y0 = top.coerceIn(0, frame.uprightHeight - 1)
            val y1 = bottom.coerceIn(0, frame.uprightHeight - 1)
            var n = 0
            var acc = 0.0
            var uy = y0
            while (uy <= y1) {
                var ux = x0
                while (ux <= x1) {
                    acc += frame.luma(frame.bufferX(ux, uy), frame.bufferY(ux, uy))
                    n += 1
                    ux += step
                }
                uy += step
            }
            return if (n == 0) 0.0 else acc / n
        }
    }
}
