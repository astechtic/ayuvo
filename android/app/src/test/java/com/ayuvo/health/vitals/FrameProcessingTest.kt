package com.ayuvo.health.vitals

import com.ayuvo.health.vitals.camera.CameraController
import com.ayuvo.health.vitals.camera.YuvColor
import com.ayuvo.health.vitals.camera.YuvFrame
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.face.FaceGeometry
import com.ayuvo.health.vitals.face.MotionCompensator
import com.ayuvo.health.vitals.face.Pt
import com.ayuvo.health.vitals.face.SkinRoiExtractor
import com.ayuvo.health.vitals.finger.FingerFrameProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** YUV_420_888 → RGB (BT.601 full range), the finger ROI statistics and the face ROI / motion geometry. */
class FrameProcessingTest {

    /**
     * A synthetic YUV_420_888 frame with padded rows and interleaved chroma (pixel stride 2, like NV12/NV21 output).
     * [pixel] gives (Y, Cb, Cr) per buffer pixel; chroma is taken at the even pixel of each 2x2 block.
     */
    private fun frame(w: Int, h: Int, rotation: Int = 0, pixel: (Int, Int) -> IntArray): YuvFrame {
        val yStride = w + 16
        val cStride = w + 8
        val y = ByteBuffer.allocate(yStride * h)
        val u = ByteBuffer.allocate(cStride * (h / 2))
        val v = ByteBuffer.allocate(cStride * (h / 2))
        for (yy in 0 until h) for (x in 0 until w) {
            val p = pixel(x, yy)
            y.put(yy * yStride + x, p[0].toByte())
            if (x % 2 == 0 && yy % 2 == 0) {
                u.put((yy / 2) * cStride + (x / 2) * 2, p[1].toByte())
                v.put((yy / 2) * cStride + (x / 2) * 2, p[2].toByte())
            }
        }
        return YuvFrame(w, h, y, yStride, 1, u, cStride, 2, v, cStride, 2, rotation)
    }

    @Test
    fun bt601FullRangeConversion() {
        assertEquals(150.0, YuvColor.red(150, 128), 1e-9)
        assertEquals(150.0, YuvColor.green(150, 128, 128), 1e-9)
        assertEquals(150.0, YuvColor.blue(150, 128), 1e-9)
        assertEquals(120 + 1.402 * 72, YuvColor.red(120, 200), 1e-9)
        assertEquals(120 + 0.344136 * 28 - 0.714136 * 72, YuvColor.green(120, 100, 200), 1e-9)
        assertEquals(120 - 1.772 * 28, YuvColor.blue(120, 100), 1e-9)
        assertEquals(255.0, YuvColor.red(250, 255), 0.0)
        assertEquals(0.0, YuvColor.blue(10, 0), 0.0)
        assertTrue(YuvColor.isSkin(100, 150))
        assertTrue(!YuvColor.isSkin(128, 128))
        assertTrue(YuvColor.isSkin(77, 173) && !YuvColor.isSkin(76, 150) && !YuvColor.isSkin(100, 174))
    }

    @Test
    fun fingerStatsUseOnlyTheCentralHalf() {
        // Black border, coloured centre: the border must not leak into the ROI means.
        val f = frame(64, 48) { x, y ->
            if (x in 16 until 48 && y in 12 until 36) intArrayOf(120, 100, 200) else intArrayOf(0, 128, 128)
        }
        val s = FingerFrameProcessor.stats(f, step = 2)
        assertEquals(120 + 1.402 * 72, s[0], 1e-9)
        assertEquals(120 + 0.344136 * 28 - 0.714136 * 72, s[1], 1e-9)
        assertEquals(120 - 1.772 * 28, s[2], 1e-9)
        assertEquals(0.0, s[3], 1e-6)
        assertEquals(0.0, s[4], 0.0)
    }

    @Test
    fun fingerRedSpreadAndSaturation() {
        // Alternate rows of saturated red (Y 255) and Y 100 inside the ROI.
        val f = frame(64, 48) { _, y -> if ((y / 2) % 2 == 0) intArrayOf(255, 128, 128) else intArrayOf(100, 128, 128) }
        val s = FingerFrameProcessor.stats(f, step = 2)
        assertEquals(177.5, s[0], 1e-9)
        assertEquals(77.5, s[3], 1e-9)
        assertEquals(0.5, s[4], 1e-9)
        // A uniformly saturated fingertip reads as "pressure" in the engine gate.
        val sat = FingerFrameProcessor.stats(frame(64, 48) { _, _ -> intArrayOf(200, 100, 255) }, step = 4)
        assertEquals("pressure", VitalsEngine.fingerDetect(doubleArrayOf(0.0, sat[0], sat[1], sat[2], sat[3], sat[4]), VitalsTestFiles.config))
    }

    @Test
    fun fingerFrameTimestampsAreRelativeMilliseconds() {
        val p = FingerFrameProcessor()
        val f = frame(16, 16) { _, _ -> intArrayOf(120, 100, 200) }
        assertEquals(0.0, p.frame(f, 5_000_000_000L)[0], 0.0)
        assertEquals(33.333, p.frame(f, 5_033_333_000L)[0], 1e-9)
        assertEquals(6, p.frame(f, 5_066_000_000L).size)
    }

    @Test
    fun uprightToBufferMappingForEveryRotation() {
        val w = 6
        val h = 4
        for (rot in listOf(0, 90, 180, 270)) {
            val f = frame(w, h, rot) { _, _ -> intArrayOf(0, 128, 128) }
            val seen = HashSet<Pair<Int, Int>>()
            for (uy in 0 until f.uprightHeight) for (ux in 0 until f.uprightWidth) {
                val bx = f.bufferX(ux, uy)
                val by = f.bufferY(ux, uy)
                assertTrue("rot $rot ($ux,$uy) -> ($bx,$by)", bx in 0 until w && by in 0 until h)
                seen += bx to by
            }
            assertEquals("rot $rot is a bijection", w * h, seen.size)
        }
        // 90° clockwise: the upright top-left is the buffer's bottom-left.
        val r90 = frame(w, h, 90) { _, _ -> intArrayOf(0, 128, 128) }
        assertEquals(0, r90.bufferX(0, 0))
        assertEquals(h - 1, r90.bufferY(0, 0))
        assertEquals(4, r90.uprightWidth)
        val r270 = frame(w, h, 270) { _, _ -> intArrayOf(0, 128, 128) }
        assertEquals(w - 1, r270.bufferX(0, 0))
        assertEquals(0, r270.bufferY(0, 0))
    }

    @Test
    fun skinRoiAveragesSkinPixelsInsideThePolygon() {
        // Left half skin-coloured (Cb 100, Cr 150), right half grey (not skin).
        val f = frame(40, 40) { x, _ -> if (x < 20) intArrayOf(140, 100, 150) else intArrayOf(90, 128, 128) }
        val leftSquare = listOf(Pt(2f, 2f), Pt(16f, 2f), Pt(16f, 16f), Pt(2f, 16f))
        val s = SkinRoiExtractor.sample(f, leftSquare, step = 2)
        assertArrayEquals(
            doubleArrayOf(YuvColor.red(140, 150), YuvColor.green(140, 100, 150), YuvColor.blue(140, 100), 1.0), s, 1e-9
        )
        // Straddling both halves: only skin pixels feed the mean, the skin fraction drops to about one half.
        val wide = listOf(Pt(10f, 10f), Pt(29f, 10f), Pt(29f, 20f), Pt(10f, 20f))
        val m = SkinRoiExtractor.sample(f, wide, step = 1)
        assertEquals(YuvColor.red(140, 150), m[0], 1e-9)
        assertEquals(0.5, m[3], 0.06)
        assertTrue(SkinRoiExtractor.inside(Pt(5f, 5f), leftSquare))
        assertTrue(!SkinRoiExtractor.inside(Pt(25f, 5f), leftSquare))
    }

    private fun face(dx: Float = 0f, id: Int? = 1): FaceGeometry {
        fun pts(vararg xy: Float) = xy.toList().chunked(2).map { Pt(it[0] + dx, it[1]) }
        return FaceGeometry(
            left = 60f + dx, top = 40f, right = 180f + dx, bottom = 200f, yaw = 3f, pitch = -2f, trackingId = id,
            contours = mapOf(
                // Subject's left eye appears on the image right (unmirrored image).
                FaceGeometry.LEFT_EYE to pts(135f, 95f, 145f, 95f),
                FaceGeometry.RIGHT_EYE to pts(95f, 95f, 105f, 95f),
                FaceGeometry.LEFT_EYEBROW_TOP to pts(130f, 80f, 150f, 80f),
                FaceGeometry.RIGHT_EYEBROW_TOP to pts(90f, 80f, 110f, 80f),
                FaceGeometry.NOSE_BRIDGE to pts(120f, 95f, 120f, 110f, 120f, 125f),
                FaceGeometry.NOSE_BOTTOM to pts(110f, 135f, 120f, 138f, 130f, 135f),
                FaceGeometry.LOWER_LIP_BOTTOM to pts(110f, 165f, 120f, 168f, 130f, 165f),
                FaceGeometry.LEFT_CHEEK to pts(145f, 130f),
                FaceGeometry.RIGHT_CHEEK to pts(95f, 130f),
                FaceGeometry.FACE to pts(60f, 100f, 80f, 170f, 120f, 198f, 160f, 170f, 180f, 100f)
            )
        )
    }

    @Test
    fun roiQuadsSitOnTheExpectedFaceParts() {
        val rois = SkinRoiExtractor.rawPolygons(face())
        assertEquals(setOf("forehead", "left_cheek", "right_cheek", "nose", "chin"), rois.keys)
        fun center(name: String) = FaceGeometry.mean(rois.getValue(name))!!
        assertTrue("forehead above brows", center("forehead").y < 80f)
        assertEquals(145f, center("left_cheek").x, 0.01f)
        assertEquals(95f, center("right_cheek").x, 0.01f)
        assertTrue("nose between bridge and tip", center("nose").y in 110f..138f)
        assertTrue("chin below the lip", center("chin").y in 168f..198f)
        // EMA (α 0.3): a jump of 10 px moves the smoothed quad by 3 px; a new tracked face restarts.
        val ex = SkinRoiExtractor(listOf("left_cheek"))
        ex.polygons(face())
        val moved = FaceGeometry.mean(ex.polygons(face(dx = 10f)).getValue("left_cheek"))!!
        assertEquals(148f, moved.x, 0.01f)
        val restarted = FaceGeometry.mean(ex.polygons(face(dx = 10f, id = 2)).getValue("left_cheek"))!!
        assertEquals(155f, restarted.x, 0.01f)
    }

    @Test
    fun motionIsDisplacementOverInterOcularDistance() {
        val m = MotionCompensator()
        assertEquals(0.0, m.motion(face()), 0.0)
        // Eye centres 40 px apart; every outline point moves 2 px -> 0.05.
        assertEquals(0.05, m.motion(face(dx = 2f)), 1e-6)
        // A different tracked face starts over.
        assertEquals(0.0, m.motion(face(dx = 30f, id = 7)), 0.0)
        val f = frame(240, 240) { _, _ -> intArrayOf(100, 128, 128) }
        assertEquals(100.0, MotionCompensator.luma(f, face()), 1e-9)
    }

    @Test
    fun fpsRangeChoice() {
        assertArrayEquals(intArrayOf(30, 30), CameraController.chooseFpsRange(listOf(intArrayOf(15, 30), intArrayOf(30, 30), intArrayOf(7, 60))))
        assertArrayEquals(intArrayOf(24, 30), CameraController.chooseFpsRange(listOf(intArrayOf(15, 30), intArrayOf(24, 30), intArrayOf(60, 60))))
        assertNull(CameraController.chooseFpsRange(listOf(intArrayOf(15, 24))))
        assertEquals(30.0, CameraController.achievedFps(1801, 60.0)!!, 1e-9)
        assertNull(CameraController.achievedFps(1, 0.0))
    }
}
