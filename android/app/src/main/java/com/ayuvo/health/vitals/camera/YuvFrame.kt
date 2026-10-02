package com.ayuvo.health.vitals.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Read-only view of a YUV_420_888 frame (plane buffers, strides) plus the rotation that makes it upright.
 * Reads use absolute indexing, so the planes' positions are never touched. Pixels are only read to compute
 * statistics; nothing is copied or kept.
 *
 * Coordinates: "buffer" coordinates are the sensor buffer ([width] x [height]); "upright" coordinates are the
 * image rotated by [rotationDegrees] clockwise, which is what ML Kit reports face contours in.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    private val y: ByteBuffer,
    private val yRowStride: Int,
    private val yPixelStride: Int,
    private val u: ByteBuffer,
    private val uRowStride: Int,
    private val uPixelStride: Int,
    private val v: ByteBuffer,
    private val vRowStride: Int,
    private val vPixelStride: Int,
    val rotationDegrees: Int = 0
) {
    val uprightWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val uprightHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    fun luma(x: Int, yy: Int): Int = y.get(yy * yRowStride + x * yPixelStride).toInt() and 0xff
    fun cb(x: Int, yy: Int): Int = u.get((yy shr 1) * uRowStride + (x shr 1) * uPixelStride).toInt() and 0xff
    fun cr(x: Int, yy: Int): Int = v.get((yy shr 1) * vRowStride + (x shr 1) * vPixelStride).toInt() and 0xff

    /** Buffer x of the upright point ([ux], [uy]); see [bufferY]. */
    fun bufferX(ux: Int, uy: Int): Int = when (rotationDegrees) {
        90 -> uy
        180 -> width - 1 - ux
        270 -> width - 1 - uy
        else -> ux
    }

    fun bufferY(ux: Int, uy: Int): Int = when (rotationDegrees) {
        90 -> height - 1 - ux
        180 -> height - 1 - uy
        270 -> ux
        else -> uy
    }

    companion object {
        fun from(image: ImageProxy): YuvFrame {
            val p = image.planes
            return YuvFrame(
                image.width, image.height,
                p[0].buffer, p[0].rowStride, p[0].pixelStride,
                p[1].buffer, p[1].rowStride, p[1].pixelStride,
                p[2].buffer, p[2].rowStride, p[2].pixelStride,
                image.imageInfo.rotationDegrees
            )
        }
    }
}

/** BT.601 full-range (JFIF) YCbCr -> RGB, clamped to 0..255. */
object YuvColor {
    fun red(y: Int, cr: Int): Double = clamp(y + 1.402 * (cr - 128))
    fun green(y: Int, cb: Int, cr: Int): Double = clamp(y - 0.344136 * (cb - 128) - 0.714136 * (cr - 128))
    fun blue(y: Int, cb: Int): Double = clamp(y + 1.772 * (cb - 128))

    /** YCbCr skin mask of §7.1: Cb 77–127 and Cr 133–173. */
    fun isSkin(cb: Int, cr: Int): Boolean = cb in 77..127 && cr in 133..173

    private fun clamp(v: Double): Double = if (v < 0.0) 0.0 else if (v > 255.0) 255.0 else v
}
