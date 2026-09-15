package com.ayuvo.health.records.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.max

/** §17 ranking: BM25 from `matchinfo(records_fts, 'pcnalx')` plus a recency boost. */
object RecordSearchRanking {
    const val K1 = 1.2
    const val B = 0.75

    /** matchinfo arrays of 32-bit unsigned integers in native (little-endian on Android) order. */
    fun decodeMatchinfo(blob: ByteArray?): IntArray {
        if (blob == null || blob.size < 4) return IntArray(0)
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        return IntArray(blob.size / 4) { buffer.getInt(it * 4) }
    }

    /**
     * `p c n a[c] l[c] x[3·c·p]` → Σ phrases Σ columns weight·idf·tf(k1+1)/(tf + k1(1 − b + b·l/a)).
     * idf = ln((n − df + 0.5)/(df + 0.5)) floored at a small positive value so common terms still count.
     */
    fun bm25(info: IntArray, weights: DoubleArray, k1: Double = K1, b: Double = B): Double {
        if (info.size < 3) return 0.0
        val p = info[0]
        val c = info[1]
        val n = info[2].toDouble()
        if (info.size < 3 + 2 * c + 3 * c * p) return 0.0
        val avg = IntArray(c) { info[3 + it] }
        val len = IntArray(c) { info[3 + c + it] }
        val xBase = 3 + 2 * c
        var score = 0.0
        for (phrase in 0 until p) {
            for (col in 0 until c) {
                val offset = xBase + 3 * (col + phrase * c)
                val tf = info[offset].toDouble()
                if (tf <= 0) continue
                val df = info[offset + 2].toDouble()
                val idf = max(ln((n - df + 0.5) / (df + 0.5)), MIN_IDF)
                val a = max(avg[col].toDouble(), 1.0)
                val norm = tf + k1 * (1 - b + b * len[col] / a)
                score += weights.getOrElse(col) { 1.0 } * idf * (tf * (k1 + 1)) / norm
            }
        }
        return score
    }

    /** Column with the largest weighted hit count, for the snippet. */
    fun bestColumn(info: IntArray, weights: DoubleArray): Int {
        if (info.size < 3) return 0
        val p = info[0]
        val c = info[1]
        if (info.size < 3 + 2 * c + 3 * c * p) return 0
        val xBase = 3 + 2 * c
        return (0 until c).maxByOrNull { col ->
            (0 until p).sumOf { phrase -> info[xBase + 3 * (col + phrase * c)].toDouble() } * weights.getOrElse(col) { 1.0 }
        } ?: 0
    }

    /** `0.15 × max(0, 1 − days/730)` with days = max(0, today − sort_date) (a future date counts as today, §28). */
    fun recencyBoost(sortDate: String, today: LocalDate): Double {
        val date = runCatching { LocalDate.parse(sortDate) }.getOrNull() ?: return 0.0
        val days = max(0L, ChronoUnit.DAYS.between(date, today)).toDouble()
        return 0.15 * max(0.0, 1 - days / 730.0)
    }

    private const val MIN_IDF = 1e-6
}
