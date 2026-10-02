package com.ayuvo.health.vitals.finger

import com.ayuvo.health.vitals.camera.YuvColor
import com.ayuvo.health.vitals.camera.YuvFrame
import kotlin.math.sqrt

/**
 * Finger frame statistics (docs/camera-vitals.md §2.1, §7.1 "Finger ROI"): mean R/G/B, the spatial SD of red and
 * the fraction of red pixels at 250 or above, over the central 50 % of the frame on a subsampled grid. The grid
 * (every [step]th pixel in both directions) keeps a 640x480 frame at about 4,800 samples, well inside 33 ms.
 */
class FingerFrameProcessor(private val step: Int = 4) {
    private var firstTimestampNs: Long? = null

    /** `[t_ms, r, g, b, r_std, sat_frac]` with `t_ms` relative to the first frame this processor saw. */
    fun frame(yuv: YuvFrame, timestampNs: Long): DoubleArray {
        val t0 = firstTimestampNs ?: timestampNs.also { firstTimestampNs = it }
        val s = stats(yuv, step)
        return doubleArrayOf((timestampNs - t0) / 1_000_000.0, s[0], s[1], s[2], s[3], s[4])
    }

    fun reset() {
        firstTimestampNs = null
    }

    companion object {
        const val SATURATED_RED = 250.0

        /** `[r, g, b, r_std, sat_frac]` of the central ROI (buffer coordinates; rotation does not matter here). */
        fun stats(yuv: YuvFrame, step: Int = 4): DoubleArray {
            val x0 = yuv.width / 4
            val x1 = yuv.width - yuv.width / 4
            val y0 = yuv.height / 4
            val y1 = yuv.height - yuv.height / 4
            var n = 0
            var sr = 0.0
            var sg = 0.0
            var sb = 0.0
            var srr = 0.0
            var sat = 0
            var yy = y0
            while (yy < y1) {
                var x = x0
                while (x < x1) {
                    val l = yuv.luma(x, yy)
                    val cb = yuv.cb(x, yy)
                    val cr = yuv.cr(x, yy)
                    val r = YuvColor.red(l, cr)
                    sr += r
                    srr += r * r
                    sg += YuvColor.green(l, cb, cr)
                    sb += YuvColor.blue(l, cb)
                    if (r >= SATURATED_RED) sat += 1
                    n += 1
                    x += step
                }
                yy += step
            }
            if (n == 0) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
            val mr = sr / n
            val variance = (srr / n - mr * mr).coerceAtLeast(0.0)
            return doubleArrayOf(mr, sg / n, sb / n, sqrt(variance), sat.toDouble() / n)
        }
    }
}
