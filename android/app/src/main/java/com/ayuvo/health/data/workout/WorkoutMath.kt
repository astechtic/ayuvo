package com.ayuvo.health.data.workout

import com.ayuvo.health.data.derived.DerivedMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A `[start, end)` pause or window in epoch milliseconds. */
data class TimeSpan(val startMs: Long, val endMs: Long)

/**
 * Numbers shared by the workout engines, written out exactly as in `scripts/workout_reference.py`.
 * Half-up rounding and the list-order mean are identical to the derived-metrics ones, so they delegate.
 */
object WorkoutMath {
    const val EARTH_RADIUS_M = 6371008.8
    /** CPython's `math.radians` multiplies by pi/180 (not divide-then-multiply). */
    private const val DEG_TO_RAD = Math.PI / 180.0

    fun roundTo(x: Double, decimals: Int): Double = DerivedMath.roundTo(x, decimals)
    fun roundTo(x: Double?, decimals: Int): Double? = DerivedMath.roundTo(x, decimals)
    fun mean(values: List<Double>): Double = DerivedMath.mean(values)

    fun radians(deg: Double): Double = deg * DEG_TO_RAD

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = radians(lat1)
        val p2 = radians(lat2)
        val dp = p2 - p1
        val dl = radians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun inPause(t: Long, pauses: List<TimeSpan>): Boolean {
        for (p in pauses) if (p.startMs <= t && t < p.endMs) return true
        return false
    }

    /** `table.get(sex or "other", table["other"])`. */
    fun <T> bySex(table: Map<String, T>, sex: String?): T = table[sex?.takeIf { it.isNotEmpty() } ?: "other"] ?: table.getValue("other")
}
