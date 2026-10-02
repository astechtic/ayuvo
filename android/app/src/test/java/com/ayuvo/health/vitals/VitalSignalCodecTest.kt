package com.ayuvo.health.vitals

import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFingerInput
import com.ayuvo.health.vitals.engine.VitalsSynth
import com.ayuvo.health.vitals.engine.VitalsSynthSpec
import com.ayuvo.health.vitals.storage.VitalSignal
import com.ayuvo.health.vitals.storage.VitalSignalCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** vital_scan_signals codec: float32 LE rows + RAW deflate (iOS COMPRESSION_ZLIB compatible). */
class VitalSignalCodecTest {

    @Test
    fun roundTripKeepsFloat32ValuesAndMeta() {
        val rows = listOf(floatArrayOf(0f, 210.123f, 60.5f), floatArrayOf(33.3f, -1.5f, Float.MIN_VALUE), floatArrayOf(66.7f, 1e9f, 0.25f))
        val s = VitalSignalCodec.encode("frame_stats", 30.0, listOf("t_ms", "r", "g"), rows)
        assertEquals("f32le+deflate", s.encoding)
        assertEquals("""{"columns":["t_ms","r","g"],"rows":3}""", s.metaJson)
        val back = VitalSignalCodec.decode(s)
        assertEquals(listOf("t_ms", "r", "g"), back.columns)
        for (i in rows.indices) assertArrayEquals(rows[i], back.rows[i], 0f)
        assertArrayEquals(floatArrayOf(210.123f, -1.5f, 1e9f), back.column("r"), 0f)
    }

    @Test
    fun blobIsRawDeflateWithoutZlibWrapper() {
        val values = DoubleArray(900) { kotlin.math.sin(it / 5.0) }
        val s = VitalSignalCodec.encodeColumns("processed", 30.0, listOf("x" to values))
        // zlib streams start with 0x78 and end in an Adler-32; a raw stream must not parse as zlib.
        assertNotEquals(0x78, s.data[0].toInt() and 0xff)
        val zlib = Inflater(false)
        try {
            zlib.setInput(s.data)
            zlib.inflate(ByteArray(4 * values.size))
            fail("raw deflate parsed as a zlib stream")
        } catch (_: DataFormatException) {
        } finally {
            zlib.end()
        }
        val raw = VitalSignalCodec.inflateRaw(s.data, 4 * values.size)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) assertEquals(v.toFloat(), buf.float, 0f)
        assertTrue("compresses", s.data.size < 4 * values.size)
    }

    /** Bytes from Python `zlib.compressobj(9, DEFLATED, -15)` over `struct.pack('<6f', ...)`: a foreign raw-deflate writer. */
    @Test
    fun decodesRawDeflateFromAnotherImplementation() {
        val hex = "63600081067b060685030c0c010ef942cd560c0feadd01"
        val data = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val s = VitalSignal("beats", null, VitalSignalCodec.ENCODING, data, VitalSignalCodec.meta(listOf("a", "b"), 3))
        val t = VitalSignalCodec.decode(s)
        assertArrayEquals(floatArrayOf(0f, -2.5f, 1e-3f), t.column("a"), 0f)
        assertArrayEquals(floatArrayOf(1f, 3.25f, 65504f), t.column("b"), 0f)
    }

    @Test
    fun rejectsSizeMismatchAndForeignEncoding() {
        val s = VitalSignalCodec.encodeColumns("beats", null, listOf("t_ms" to doubleArrayOf(1.0, 2.0, 3.0)))
        val lying = VitalSignal(s.kind, s.sampleRate, s.encoding, s.data, VitalSignalCodec.meta(listOf("t_ms"), 4))
        assertThrows { VitalSignalCodec.decode(lying) }
        val longer = VitalSignal(s.kind, s.sampleRate, s.encoding, s.data, VitalSignalCodec.meta(listOf("t_ms"), 2))
        assertThrows { VitalSignalCodec.decode(longer) }
        assertThrows { VitalSignalCodec.decode(VitalSignal(s.kind, null, "f64", s.data, s.metaJson)) }
        assertThrows { VitalSignalCodec.decode(VitalSignal(s.kind, null, s.encoding, s.data.copyOf(s.data.size - 2), s.metaJson)) }
        val empty = VitalSignalCodec.encodeColumns("beats", null, listOf("t_ms" to DoubleArray(0)))
        assertEquals(0, VitalSignalCodec.decode(empty).rows.size)
    }

    @Test
    fun frameStatsReproduceTheEngineInputToFloat32() {
        val spec = VitalsSynthSpec(fs = 30.0, durationS = 3.0, hrBpm = 70.0, jitterMs = 4.0, seed = 5)
        val frames = VitalsSynth.synthFinger(spec)
        val t = VitalSignalCodec.decode(VitalSignalCodec.fingerFrameStats(frames, 30.0))
        assertEquals(VitalSignalCodec.FINGER_COLUMNS, t.columns)
        assertEquals(frames.size, t.rows.size)
        for (i in frames.indices) {
            assertEquals((frames[i][0] - frames[0][0]).toFloat(), t.rows[i][0], 0f)
            for (c in 1 until 6) assertEquals(frames[i][c].toFloat(), t.rows[i][c], 0f)
        }
        val face = VitalsSynth.synthFace(spec.copy(rois = mapOf("forehead" to (0.004 to 0.002), "chin" to (0.001 to 0.002))))
        val order = VitalsTestFiles.config.face.rois
        val ft = VitalSignalCodec.decode(VitalSignalCodec.faceFrameStats(face, order))
        assertEquals(VitalSignalCodec.FACE_COLUMNS + listOf("forehead_r", "forehead_g", "forehead_b", "forehead_skin", "chin_r", "chin_g", "chin_b", "chin_skin"), ft.columns)
        assertEquals(face.rois.getValue("chin")[7][1].toFloat(), ft.rows[7][ft.columns.indexOf("chin_g")], 0f)
    }

    /** include_signals adds `signals` (processed, mask, peaks_ms) and leaves every other key of the result untouched. */
    @Test
    fun includeSignalsOnlyAddsTheSignalsBlock() {
        val cfg = VitalsTestFiles.config
        val frames = VitalsSynth.synthFinger(VitalsSynthSpec(fs = 30.0, durationS = 40.0, hrBpm = 72.0, rsaMs = 30.0, jitterMs = 3.0, seed = 3))
        val plain = VitalsEngine.analyzeFinger(VitalsFingerInput(frames), cfg)
        val full = VitalsEngine.analyzeFinger(VitalsFingerInput(frames, includeSignals = true), cfg)
        assertNull(plain.signals)
        assertEquals(plain.json, JsonObject(full.json - "signals"))
        val sig = full.json["signals"] as JsonObject
        assertEquals(30, (sig["fs"] as JsonPrimitive).int)
        val processed = sig["processed"] as JsonArray
        assertEquals(processed.size, (sig["mask"] as JsonArray).size)
        assertEquals((full.quality["beats"] as JsonPrimitive).int, (sig["peaks_ms"] as JsonArray).size)
        val stored = VitalSignalCodec.engineSignals(full.signals!!).associateBy { it.kind }
        assertEquals(setOf("processed", "mask", "beats"), stored.keys)
        assertEquals(processed.size, VitalSignalCodec.decode(stored.getValue("processed")).rows.size)
        assertEquals("valid", (full.metric("heart_rate")!!["status"] as JsonPrimitive).content)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("expected an exception")
    }
}
