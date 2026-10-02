package com.ayuvo.health.vitals.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Small helpers of `scripts/vitals_reference.py`, written out with the reference's operation order
 * (accumulation order, `(a*b)/c` vs `a*(b/c)`), so results are bit-identical on the JVM.
 */
object VitalsMath {
    const val TWO_PI: Double = 2.0 * Math.PI

    private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0, 10000000.0, 100000000.0)

    /** Half-up `round_to`; -0.0 becomes 0.0. */
    fun roundTo(x: Double, decimals: Int): Double {
        val scale = POW10[decimals]
        val v = floor(x * scale + 0.5) / scale
        return if (v == 0.0) 0.0 else v
    }

    fun roundTo(x: Double?, decimals: Int): Double? = x?.let { roundTo(it, decimals) }

    fun roundList(xs: DoubleArray, decimals: Int): DoubleArray = DoubleArray(xs.size) { roundTo(xs[it], decimals) }
    fun roundList(xs: List<Double>, decimals: Int): List<Double> = xs.map { roundTo(it, decimals) }

    fun clamp(x: Double, lo: Double, hi: Double): Double = if (x < lo) lo else (if (x > hi) hi else x)

    fun total(values: DoubleArray): Double {
        var t = 0.0
        for (v in values) t += v
        return t
    }

    fun total(values: List<Double>): Double {
        var t = 0.0
        for (v in values) t += v
        return t
    }

    fun mean(values: DoubleArray): Double = total(values) / values.size
    fun mean(values: List<Double>): Double = total(values) / values.size

    /** Population variance (divides by n). */
    fun variance(values: DoubleArray): Double {
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return acc / values.size
    }

    fun variance(values: List<Double>): Double {
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return acc / values.size
    }

    fun std(values: DoubleArray): Double = sqrt(variance(values))
    fun std(values: List<Double>): Double = sqrt(variance(values))

    fun sampleStd(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return sqrt(acc / (values.size - 1))
    }

    /** Python `sorted` on floats: stable, compares with `<` (so -0.0 and 0.0 keep their order). */
    val PY_ORDER: Comparator<Double> = Comparator { a, b -> if (a < b) -1 else if (a > b) 1 else 0 }

    fun sortedPy(values: List<Double>): List<Double> = values.sortedWith(PY_ORDER)

    fun median(values: List<Double>): Double {
        val s = sortedPy(values)
        val n = s.size
        if (n % 2 == 1) return s[n / 2]
        return (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun median(values: DoubleArray): Double = median(values.toList())

    fun mad(values: List<Double>): Double {
        val m = median(values)
        val dev = ArrayList<Double>(values.size)
        for (v in values) dev.add(Math.abs(v - m))
        return median(dev)
    }

    fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        val ma = mean(a)
        val mb = mean(b)
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            sab += da * db
            saa += da * da
            sbb += db * db
        }
        if (saa <= 0.0 || sbb <= 0.0) return 0.0
        return sab / sqrt(saa * sbb)
    }

    fun pearson(a: List<Double>, b: List<Double>): Double = pearson(a.toDoubleArray(), b.toDoubleArray())

    fun confidenceLabel(c: Double?): String? {
        if (c == null) return null
        if (c >= 0.8) return "high"
        if (c >= 0.6) return "medium"
        return "low"
    }

    fun oddWindow(seconds: Double, fs: Double): Int {
        var w = floor(seconds * fs + 0.5).toInt()
        if (w < 1) w = 1
        if (w % 2 == 0) w += 1
        return w
    }

    /** `int(math.floor(x))`. */
    fun ifloor(x: Double): Int = floor(x).toInt()
}

/** JSON building with the reference's shape: explicit nulls, floats stay floats, ints stay ints. */
object VitalsJson {
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in pairs) m[k] = el(v)
        return JsonObject(m)
    }

    fun el(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Double -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v.toDouble())
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is DoubleArray -> JsonArray(v.map { JsonPrimitive(it) })
        is BooleanArray -> JsonArray(v.map { JsonPrimitive(it) })
        is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
        is List<*> -> JsonArray(v.map { el(it) })
        is Map<*, *> -> {
            val m = LinkedHashMap<String, JsonElement>()
            for ((k, x) in v) m[k.toString()] = el(x)
            JsonObject(m)
        }
        else -> error("unsupported JSON value ${v.javaClass}")
    }
}
