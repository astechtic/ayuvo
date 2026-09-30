package com.ayuvo.health.data.intake

import com.ayuvo.health.data.derived.DerivedMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Numbers and time shared by the intake engines, written out exactly as in `scripts/intake_reference.py`:
 * half-up `round_to`, list-order means, Python's `median`, "YYYY-MM-DD" days and wall-clock questions in the input's
 * time zone. Rounding and the mean are the derived-metrics ones, so they delegate.
 */
object IntakeMath {
    fun roundTo(x: Double, decimals: Int): Double = DerivedMath.roundTo(x, decimals)
    fun roundTo(x: Double?, decimals: Int): Double? = DerivedMath.roundTo(x, decimals)
    fun mean(values: List<Double>): Double = DerivedMath.mean(values)
    fun median(values: List<Double>): Double = DerivedMath.median(values)

    fun addDays(day: String, n: Int): String = LocalDate.parse(day).plusDays(n.toLong()).toString()

    fun local(ms: Long, zone: String): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone))

    fun minuteOfDay(ms: Long, zone: String): Int {
        val t = local(ms, zone)
        return t.hour * 60 + t.minute
    }

    /** Python truthiness of an optional number. */
    fun truthy(x: Double?): Boolean = x != null && x != 0.0

    /** Python `sorted()` on str keys. */
    fun <V> sortedByKey(map: Map<String, V>): List<Map.Entry<String, V>> =
        map.entries.sortedWith { a, b -> DerivedMath.CODE_POINT_ORDER.compare(a.key, b.key) }

    fun sortedKeys(keys: Collection<String>): List<String> = keys.sortedWith(DerivedMath.CODE_POINT_ORDER)
}
