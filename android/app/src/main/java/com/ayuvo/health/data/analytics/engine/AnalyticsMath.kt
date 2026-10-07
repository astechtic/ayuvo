package com.ayuvo.health.data.analytics.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * A plain JSON value as the reference sees it: `null`, [Boolean], [Double] (or [Int] / [Long] for counts), [String],
 * `List<Any?>` or `Map<String, Any?>`. The analytics engine works on these so it reads line by line like
 * `scripts/analytics_reference.py` (docs/health-analytics.md).
 */
typealias JMap = Map<String, Any?>

/** Numbers, days and JSON helpers shared by every analytics function (portability rules of the reference). */
object AnalyticsMath {
    private val SCALES = DoubleArray(10) { var s = 1.0; repeat(it) { s *= 10.0 }; s }

    fun roundTo(x: Double, decimals: Int): Double {
        val scale = SCALES[decimals]
        val v = floor(x * scale + 0.5) / scale
        return if (v == 0.0) 0.0 else v
    }

    fun roundTo(x: Double?, decimals: Int): Double? = x?.let { roundTo(it, decimals) }

    fun roundInt(x: Double): Int = floor(x + 0.5).toInt()

    fun clamp(x: Double, lo: Double, hi: Double): Double = if (x < lo) lo else if (x > hi) hi else x

    fun total(values: List<Double>): Double {
        var t = 0.0
        for (v in values) t += v
        return t
    }

    fun mean(values: List<Double>): Double = total(values) / values.size

    fun sampleSd(values: List<Double>, m: Double): Double {
        var t = 0.0
        for (v in values) t += (v - m) * (v - m)
        return StrictMath.sqrt(t / (values.size - 1))
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        if (n % 2 == 1) return s[n / 2]
        return (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Hyndman & Fan type 7. */
    fun percentile(values: List<Double>, p: Double): Double {
        val s = values.sorted()
        val n = s.size
        if (n == 1) return s[0]
        val h = (n - 1) * p / 100.0
        val lo = floor(h).toInt()
        val hi = if (lo + 1 < n) lo + 1 else n - 1
        return s[lo] + (h - lo) * (s[hi] - s[lo])
    }

    fun mad(values: List<Double>): Double {
        val m = median(values)
        return median(values.map { abs(it - m) })
    }

    // -- days ------------------------------------------------------------------------------------

    fun parseDay(s: String): LocalDate = LocalDate.parse(s)

    fun addDays(day: String, n: Int): String = LocalDate.parse(day).plusDays(n.toLong()).toString()

    fun daysBetween(a: String, b: String): Int = (LocalDate.parse(b).toEpochDay() - LocalDate.parse(a).toEpochDay()).toInt()

    fun localDayOf(ms: Long, timeZone: String): String = Instant.ofEpochMilli(ms).atZone(ZoneId.of(timeZone)).toLocalDate().toString()

    /** Days day+first .. day+last inclusive, oldest first. */
    fun windowDays(day: String, first: Int, last: Int): List<String> {
        val d = LocalDate.parse(day)
        return (first..last).map { d.plusDays(it.toLong()).toString() }
    }

    // -- transcendental, written out as in the reference --------------------------------------------

    fun ln(x: Double): Double = StrictMath.log(x)
    fun exp(x: Double): Double = StrictMath.exp(x)
    fun sqrt(x: Double): Double = StrictMath.sqrt(x)
    fun atanh(r: Double): Double = 0.5 * StrictMath.log((1.0 + r) / (1.0 - r))
    fun tanh(z: Double): Double {
        val e = StrictMath.exp(2.0 * z)
        return (e - 1.0) / (e + 1.0)
    }

    /** Abramowitz & Stegun 7.1.26. */
    fun normalCdf(x: Double): Double {
        val z = abs(x) / StrictMath.sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1.0 - poly * StrictMath.exp(-z * z)
        return if (x >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /** Python `sorted()` order on str: by Unicode code point. */
    val CODE_POINT_ORDER = Comparator<String> { a, b ->
        val ai = a.codePoints().toArray()
        val bi = b.codePoints().toArray()
        for (i in 0 until minOf(ai.size, bi.size)) if (ai[i] != bi[i]) return@Comparator ai[i].compareTo(bi[i])
        ai.size.compareTo(bi.size)
    }

    // -- plain JSON --------------------------------------------------------------------------------

    fun plain(e: JsonElement?): Any? = when (e) {
        null, is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in e) m[k] = plain(v) }
        is JsonArray -> e.map { plain(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.doubleOrNull
        }
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toDouble())
        is String -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to toJson(x) })
        is List<*> -> JsonArray(v.map { toJson(it) })
        else -> error("not JSON: $v")
    }

    /** Compact JSON with code-point-sorted keys; integral -> digits, otherwise %.6f trimmed (the reference canonical). */
    fun canonical(obj: Any?): String = when (obj) {
        null -> "null"
        true -> "true"
        false -> "false"
        is Number -> {
            val x = obj.toDouble()
            if (x == floor(x) && abs(x) < 1e15) String.format(Locale.ROOT, "%d", x.toLong())
            else {
                var s = String.format(Locale.ROOT, "%.6f", roundTo(x, 6))
                s = s.trimEnd('0').trimEnd('.')
                if (s == "-0" || s.isEmpty()) "0" else s
            }
        }
        is String -> buildString {
            append('"')
            for (ch in obj) {
                val c = ch.code
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    c < 0x20 -> append(String.format(Locale.ROOT, "\\u%04x", c))
                    else -> append(ch)
                }
            }
            append('"')
        }
        is List<*> -> obj.joinToString(",", "[", "]") { canonical(it) }
        is Map<*, *> -> obj.keys.map { it as String }.sortedWith(CODE_POINT_ORDER)
            .joinToString(",", "{", "}") { canonical(it) + ":" + canonical(obj[it]) }
        else -> error("not JSON: $obj")
    }

    fun fnv1a64(text: String): String {
        var h = 0xcbf29ce484222325UL
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toUByte().toULong())
            h *= 0x100000001b3UL
        }
        return h.toString(16).padStart(16, '0')
    }

    // -- typed access over plain maps (missing / null key -> null, numbers as Double) ---------------

    @Suppress("UNCHECKED_CAST")
    fun JMap.obj(k: String): JMap = this[k] as JMap

    @Suppress("UNCHECKED_CAST")
    fun JMap.objOrNull(k: String): JMap? = this[k] as? JMap

    fun JMap.num(k: String): Double = (this[k] as Number).toDouble()
    fun JMap.numOrNull(k: String): Double? = (this[k] as? Number)?.toDouble()
    fun JMap.int(k: String): Int = (this[k] as Number).toInt()
    fun JMap.str(k: String): String = this[k] as String
    fun JMap.strOrNull(k: String): String? = this[k] as? String

    @Suppress("UNCHECKED_CAST")
    fun JMap.list(k: String): List<Any?> = (this[k] as? List<Any?>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun JMap.maps(k: String): List<JMap> = ((this[k] as? List<Any?>) ?: emptyList()).map { it as JMap }

    fun JMap.nums(k: String): List<Double> = list(k).map { (it as Number).toDouble() }

    /** `{day: number}` with null values dropped (how the reference reads `series.get(d) is not None`). */
    @Suppress("UNCHECKED_CAST")
    fun JMap.series(k: String): Map<String, Double> =
        ((this[k] as? Map<String, Any?>) ?: emptyMap()).entries.mapNotNull { (d, v) -> (v as? Number)?.let { d to it.toDouble() } }.toMap()

    @Suppress("UNCHECKED_CAST")
    fun JMap.strSeries(k: String): Map<String, String>? =
        (this[k] as? Map<String, Any?>)?.entries?.mapNotNull { (d, v) -> (v as? String)?.let { d to it } }?.toMap()

    /** Python truthiness of a JSON value. */
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }
}
