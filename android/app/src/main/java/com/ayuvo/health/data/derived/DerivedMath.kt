package com.ayuvo.health.data.derived

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlin.math.floor
import kotlin.math.sqrt

/** A minute series: value i belongs to minute `startMs + i · 60000`; null = no reading. */
data class MinuteSeries(val startMs: Long, val values: List<Double?>)

/** A `[start, end)` instant span in epoch milliseconds. */
data class Span(val startMs: Long, val endMs: Long)

/**
 * Numbers and time shared by every derivation, written out exactly as in
 * `scripts/derived_reference.py` (docs/derived-metrics.md): half-up rounding by
 * `floor(x·10^d + 0.5)`, list-order sums, sample SD with n − 1, and wall-clock questions answered
 * in the input's time zone with java.time (same tz database rules as Python's zoneinfo).
 */
object DerivedMath {
    const val MINUTE = 60000L
    const val HOUR = 3600000L
    /** Sleep codes counted as asleep (metric_registry.json "sleep"): asleep, light/core, deep, REM. */
    val ASLEEP = setOf(1, 3, 4, 5)
    private val SCALES = doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0)

    fun roundTo(x: Double, decimals: Int): Double {
        val scale = SCALES[decimals]
        val v = floor(x * scale + 0.5) / scale
        return if (v == 0.0) 0.0 else v // never -0.0
    }

    fun roundTo(x: Double?, decimals: Int): Double? = x?.let { roundTo(it, decimals) }

    fun mean(values: List<Double>): Double {
        var total = 0.0
        for (v in values) total += v
        return total / values.size
    }

    /** Sample standard deviation (divides by n − 1); needs n ≥ 2. */
    fun sampleSd(values: List<Double>, m: Double): Double {
        var total = 0.0
        for (v in values) total += (v - m) * (v - m)
        return sqrt(total / (values.size - 1))
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        if (n % 2 == 1) return s[n / 2]
        return (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Merged `[start, end)` spans sorted by (start, end); empty spans are dropped. */
    fun union(intervals: List<Span>): List<Span> {
        val s = intervals.filter { it.endMs > it.startMs }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        val out = ArrayList<Span>()
        for (x in s) {
            val last = out.lastOrNull()
            if (last != null && x.startMs <= last.endMs) {
                if (x.endMs > last.endMs) out[out.size - 1] = Span(last.startMs, x.endMs)
            } else {
                out += x
            }
        }
        return out
    }

    fun totalMs(intervals: List<Span>): Long {
        var t = 0L
        for (x in intervals) t += x.endMs - x.startMs
        return t
    }

    /** `{minute_ms: value}` sorted by minute; nulls and values outside `[lo, hi]` dropped. */
    fun minuteMap(series: MinuteSeries?, lo: Double? = null, hi: Double? = null): TreeMap<Long, Double> {
        val out = TreeMap<Long, Double>()
        if (series == null) return out
        series.values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (lo != null && hi != null && (v < lo || v > hi)) return@forEachIndexed
            out[series.startMs + i * MINUTE] = v
        }
        return out
    }

    // -- days and wall clock ---------------------------------------------------------------------

    /**
     * Epoch ms of local midnight. `ZonedDateTime.of` resolves a DST gap forward by the gap length and
     * an overlap to the earlier offset, which is the same instant Python's zoneinfo gives with fold=0.
     */
    fun dayStartMs(day: LocalDate, zone: ZoneId): Long = ZonedDateTime.of(day.atStartOfDay(), zone).toEpochSecond() * 1000L

    fun localTime(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    fun localDayOf(ms: Long, zone: ZoneId): LocalDate = localTime(ms, zone).toLocalDate()

    fun addDays(day: LocalDate, n: Int): LocalDate = day.plusDays(n.toLong())

    /** ISO weekday: Monday 1 … Sunday 7. */
    fun isoWeekday(day: LocalDate): Int = day.dayOfWeek.value

    /** Wall-clock minutes after 12:00 of the day before [wakeDay] (23:00 → 660, 07:00 → 1140). */
    fun clockMin(ms: Long, wakeDay: LocalDate, zone: ZoneId): Int {
        val t = localTime(ms, zone)
        val days = ChronoUnit.DAYS.between(wakeDay.minusDays(1), t.toLocalDate()).toInt()
        return days * 1440 + t.hour * 60 + t.minute - 720
    }

    /** Python `sorted()` order on str: by Unicode code point, not UTF-16 unit. */
    val CODE_POINT_ORDER = Comparator<String> { a, b ->
        val ai = a.codePoints().toArray()
        val bi = b.codePoints().toArray()
        for (i in 0 until minOf(ai.size, bi.size)) if (ai[i] != bi[i]) return@Comparator ai[i].compareTo(bi[i])
        ai.size.compareTo(bi.size)
    }

    /** Python truthiness of an optional number: null and 0 are false. */
    fun truthy(x: Double?): Boolean = x != null && x != 0.0
}
