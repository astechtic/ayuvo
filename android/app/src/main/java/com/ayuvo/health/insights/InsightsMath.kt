package com.ayuvo.health.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Numbers, days and text shared by every Insights engine, written out exactly as in
 * `scripts/insights_reference.py` so Kotlin and Swift doubles give identical results
 * (docs/insights.md §2.2): half-up rounding by `floor(x·10^d + 0.5)`, chronological sums,
 * sample SD with n − 1, U+2212 for minus signs.
 */
object InsightsMath {
    const val MINUS = "−"
    private val PLACEHOLDER = Regex("[{]([a-z_][a-z0-9_]*)[}]")
    private val SCALES = doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0)

    fun roundTo(x: Double, decimals: Int): Double {
        val scale = SCALES[decimals]
        val v = floor(x * scale + 0.5) / scale
        return if (v == 0.0) 0.0 else v // never -0.0
    }

    fun roundTo(x: Double?, decimals: Int): Double? = x?.let { roundTo(it, decimals) }

    fun roundInt(x: Double): Int = floor(x + 0.5).toInt()

    fun clamp(x: Double, lo: Double, hi: Double): Double = if (x < lo) lo else if (x > hi) hi else x

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

    /** Least-squares slope of `(x, y)`; null when x has no spread. */
    fun olsSlope(points: List<Pair<Double, Double>>): Double? {
        val mx = mean(points.map { it.first })
        val my = mean(points.map { it.second })
        var sxx = 0.0
        var sxy = 0.0
        for ((x, y) in points) {
            sxx += (x - mx) * (x - mx)
            sxy += (x - mx) * (y - my)
        }
        return if (sxx == 0.0) null else sxy / sxx
    }

    /** `b − a` in whole days. */
    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

    fun localDayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun localMinutesOf(ms: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return t.hour * 60 + t.minute
    }

    /**
     * Wall-clock minutes from 12:00 on the day before [wakeDay] to the night's midpoint: a
     * 23:00–07:00 night gives 900 (the same clock as the metrics `sleep_clock_offset`).
     */
    fun sleepMidpointMin(night: SleepInput, wakeDay: LocalDate, zone: ZoneId): Int {
        val mid = night.startMs + Math.floorDiv(night.endMs - night.startMs, 2L)
        val local = Instant.ofEpochMilli(mid).atZone(zone)
        val days = ChronoUnit.DAYS.between(wakeDay.minusDays(1), local.toLocalDate()).toInt()
        return days * 1440 + local.hour * 60 + local.minute - 720
    }

    fun groupInt(n: Long): String {
        var s = abs(n).toString()
        val out = ArrayList<String>()
        while (s.length > 3) {
            out.add(0, s.substring(s.length - 3))
            s = s.substring(0, s.length - 3)
        }
        out.add(0, s)
        return (if (n < 0) "-" else "") + out.joinToString(",")
    }

    /** Template number: integral → grouped integer ("8,432"), otherwise one decimal ("7.5"). */
    fun fmtNumber(x: Double): String =
        if (x == floor(x)) groupInt(x.toLong()) else String.format(Locale.ROOT, "%.1f", roundTo(x, 1))

    fun fmtSigned(x: Double, decimals: Int): String {
        val r = roundTo(x, decimals)
        if (r == 0.0) return "0"
        val body = if (decimals == 0) groupInt(abs(r).toLong()) else String.format(Locale.ROOT, "%.${decimals}f", abs(r))
        return (if (r > 0) "+" else MINUS) + body
    }

    fun fmtDuration(minutes: Double): String {
        val m = roundInt(minutes)
        return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }

    /** Replaces each `{name}` with its param: numbers via [fmtNumber], strings as they are. */
    fun fill(template: String, params: Map<String, Any?>): String =
        PLACEHOLDER.replace(template) { m ->
            when (val v = params[m.groupValues[1]] ?: error("missing param ${m.groupValues[1]}")) {
                is String -> v
                is Number -> fmtNumber(v.toDouble())
                else -> v.toString()
            }
        }

    /** Piecewise-linear y(x) over `[x, y]` points with x ascending; flat beyond the ends. */
    fun interp(points: Points, x: Double): Double {
        if (x <= points[0].first) return points[0].second
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return points.last().second
    }

    /**
     * Age at which a monotonic population curve `[age, value]` equals [value]: inverse linear
     * interpolation, linear extrapolation from the end segments, then clamped to the age range.
     */
    fun inverseAge(table: Points, value: Double, ageMin: Double, ageMax: Double): Double {
        val n = table.size
        var seg = -1
        for (i in 0 until n - 1) {
            val lo = minOf(table[i].second, table[i + 1].second)
            val hi = maxOf(table[i].second, table[i + 1].second)
            if (value in lo..hi) {
                seg = i
                break
            }
        }
        if (seg < 0) {
            val increasing = table[n - 1].second > table[0].second
            val belowFirst = if (increasing) value < table[0].second else value > table[0].second
            seg = if (belowFirst) 0 else n - 2
        }
        val (a0, v0) = table[seg]
        val (a1, v1) = table[seg + 1]
        val age = a0 + (value - v0) * (a1 - a0) / (v1 - v0)
        return clamp(age, ageMin, ageMax)
    }

    /** `(offset, value)` for days `day + first .. day + last` (inclusive, chronological), present values only. */
    fun values(series: Map<LocalDate, Double>, day: LocalDate, first: Int, last: Int): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        for (k in first..last) series[day.plusDays(k.toLong())]?.let { out += k to it }
        return out
    }
}
