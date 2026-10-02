package com.ayuvo.health.vitals.storage

import com.ayuvo.health.vitals.engine.VitalsFaceFrames
import com.ayuvo.health.vitals.engine.VitalsSignals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * `vital_scan_signals.data` codec (docs/camera-vitals.md §7.1 "Signals"): rows of floats, row-major, as float32
 * little-endian, compressed with RAW deflate (no zlib header, no checksum). iOS writes the same bytes with Apple
 * Compression `COMPRESSION_ZLIB`, which is raw deflate, so the format must stay raw deflate.
 * `meta_json` is `{"columns": [...], "rows": N}`.
 */
object VitalSignalCodec {
    const val ENCODING = "f32le+deflate"

    private val json = Json { ignoreUnknownKeys = true }

    class Table(val columns: List<String>, val rows: List<FloatArray>) {
        fun column(name: String): FloatArray {
            val c = columns.indexOf(name)
            require(c >= 0) { "no column $name" }
            return FloatArray(rows.size) { rows[it][c] }
        }
    }

    fun encode(kind: String, sampleRate: Double?, columns: List<String>, rows: List<FloatArray>, level: Int = Deflater.BEST_COMPRESSION): VitalSignal {
        require(columns.isNotEmpty()) { "signal needs columns" }
        val buf = ByteBuffer.allocate(rows.size * columns.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (row in rows) {
            require(row.size == columns.size) { "row has ${row.size} values for ${columns.size} columns" }
            for (v in row) buf.putFloat(v)
        }
        return VitalSignal(kind, sampleRate, ENCODING, deflateRaw(buf.array(), level), meta(columns, rows.size))
    }

    /** Column-wise convenience: every array must have the same length. */
    fun encodeColumns(kind: String, sampleRate: Double?, columns: List<Pair<String, DoubleArray>>): VitalSignal {
        val n = columns.first().second.size
        require(columns.all { it.second.size == n }) { "columns differ in length" }
        val rows = List(n) { r -> FloatArray(columns.size) { c -> columns[c].second[r].toFloat() } }
        return encode(kind, sampleRate, columns.map { it.first }, rows)
    }

    fun decode(signal: VitalSignal): Table {
        require(signal.encoding == ENCODING) { "unsupported encoding ${signal.encoding}" }
        val meta = json.parseToJsonElement(signal.metaJson ?: error("signal has no meta_json")) as JsonObject
        val columns = (meta["columns"] as JsonArray).map { (it as JsonPrimitive).content }
        val rowCount = (meta["rows"] as JsonPrimitive).int
        val bytes = inflateRaw(signal.data, rowCount * columns.size * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rows = List(rowCount) { FloatArray(columns.size) { buf.float } }
        return Table(columns, rows)
    }

    fun meta(columns: List<String>, rows: Int): String = buildJsonObject {
        putJsonArray("columns") { columns.forEach { add(JsonPrimitive(it)) } }
        put("rows", rows)
    }.toString()

    fun deflateRaw(input: ByteArray, level: Int = Deflater.BEST_COMPRESSION): ByteArray {
        val deflater = Deflater(level, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, input.size / 2))
            val chunk = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Inflates raw deflate; [expectedSize] must match exactly (guards truncated or foreign blobs). */
    fun inflateRaw(input: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            // java.util.zip docs: with nowrap an extra dummy input byte may be needed by zlib; it is never consumed as data.
            inflater.setInput(input + byteArrayOf(0))
            val out = ByteArray(expectedSize)
            var off = 0
            while (!inflater.finished()) {
                if (off == expectedSize) {
                    // Must end here: anything more means the blob is longer than meta_json says.
                    val probe = ByteArray(1)
                    if (inflater.inflate(probe) > 0) throw DataFormatException("signal longer than meta_json rows")
                    if (!inflater.finished()) throw DataFormatException("truncated signal")
                    break
                }
                val n = inflater.inflate(out, off, expectedSize - off)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw DataFormatException("truncated signal")
                off += n
            }
            if (off != expectedSize) throw DataFormatException("signal has $off bytes, meta_json says $expectedSize")
            return out
        } finally {
            inflater.end()
        }
    }

    // -- Builders for the documented kinds --------------------------------------------------------

    /** Finger `frame_stats`: columns t_ms,r,g,b,r_std,sat_frac with t_ms relative to the first frame. */
    fun fingerFrameStats(frames: List<DoubleArray>, sampleRate: Double? = null): VitalSignal {
        val t0 = frames.firstOrNull()?.get(0) ?: 0.0
        val rows = frames.map { f -> FloatArray(6) { c -> (if (c == 0) f[0] - t0 else f[c]).toFloat() } }
        return encode(VitalSignal.KIND_FRAME_STATS, sampleRate, FINGER_COLUMNS, rows)
    }

    /**
     * Face `frame_stats`: t_ms,motion,yaw,pitch,luma,face_count,face_fraction then `<roi>_r,<roi>_g,<roi>_b,<roi>_skin`
     * per ROI in [roiOrder] (config order) that the frames have.
     */
    fun faceFrameStats(face: VitalsFaceFrames, roiOrder: List<String>, sampleRate: Double? = null): VitalSignal {
        val rois = roiOrder.filter { face.rois.containsKey(it) }
        val columns = FACE_COLUMNS + rois.flatMap { listOf("${it}_r", "${it}_g", "${it}_b", "${it}_skin") }
        val t0 = face.tMs.firstOrNull() ?: 0.0
        val rows = List(face.tMs.size) { i ->
            val row = FloatArray(columns.size)
            row[0] = (face.tMs[i] - t0).toFloat()
            row[1] = face.motion[i].toFloat()
            row[2] = face.yaw[i].toFloat()
            row[3] = face.pitch[i].toFloat()
            row[4] = face.luma[i].toFloat()
            row[5] = face.faceCount[i].toFloat()
            row[6] = face.faceFraction[i].toFloat()
            var c = 7
            for (roi in rois) {
                val v = face.rois.getValue(roi)[i]
                for (k in 0 until 4) row[c++] = v[k].toFloat()
            }
            row
        }
        return encode(VitalSignal.KIND_FRAME_STATS, sampleRate, columns, rows)
    }

    /** `processed` (x), `mask` (m, 0/1) and `beats` (t_ms) from an analysis run with include_signals. */
    fun engineSignals(s: VitalsSignals): List<VitalSignal> = listOf(
        encodeColumns(VitalSignal.KIND_PROCESSED, s.fs, listOf("x" to s.processed)),
        encodeColumns(VitalSignal.KIND_MASK, s.fs, listOf("m" to DoubleArray(s.mask.size) { if (s.mask[it]) 1.0 else 0.0 })),
        encodeColumns(VitalSignal.KIND_BEATS, null, listOf("t_ms" to s.peaksMs))
    )

    val FINGER_COLUMNS = listOf("t_ms", "r", "g", "b", "r_std", "sat_frac")
    val FACE_COLUMNS = listOf("t_ms", "motion", "yaw", "pitch", "luma", "face_count", "face_fraction")
}
