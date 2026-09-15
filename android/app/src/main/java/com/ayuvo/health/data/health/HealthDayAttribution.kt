package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthDayAttribution
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Local-day keys (`yyyy-MM-dd`) for samples. The row's own zone offset wins when the
 * platform supplied one; otherwise the device zone at sync time. Sleep and other
 * `END`-attributed types key by the wake day, `SPAN` types by their start day.
 */
object HealthDayKeys {

    fun dayOf(ms: Long, offsetS: Int?, fallbackZone: ZoneId): LocalDate {
        val instant = Instant.ofEpochMilli(ms)
        return if (offsetS != null) {
            instant.atOffset(ZoneOffset.ofTotalSeconds(offsetS)).toLocalDate()
        } else {
            instant.atZone(fallbackZone).toLocalDate()
        }
    }

    fun localDay(
        attribution: HealthDayAttribution,
        startMs: Long,
        endMs: Long,
        startOffsetS: Int?,
        endOffsetS: Int?,
        fallbackZone: ZoneId
    ): String = when (attribution) {
        HealthDayAttribution.START, HealthDayAttribution.SPAN -> dayOf(startMs, startOffsetS, fallbackZone).toString()
        HealthDayAttribution.END -> dayOf(endMs, endOffsetS ?: startOffsetS, fallbackZone).toString()
    }

    fun localDay(type: HealthDataType?, startMs: Long, endMs: Long, startOffsetS: Int?, endOffsetS: Int?, fallbackZone: ZoneId): String =
        localDay(type?.dayAttribution ?: HealthDayAttribution.START, startMs, endMs, startOffsetS, endOffsetS, fallbackZone)

    /** Every local day a span touches, inclusive — used for `menstruation_period` bands. */
    fun daysCovered(startMs: Long, endMs: Long, startOffsetS: Int?, endOffsetS: Int?, fallbackZone: ZoneId): List<LocalDate> {
        val first = dayOf(startMs, startOffsetS, fallbackZone)
        val last = dayOf(maxOf(startMs, endMs), endOffsetS ?: startOffsetS, fallbackZone)
        if (last.isBefore(first)) return listOf(first)
        val out = ArrayList<LocalDate>()
        var d = first
        while (!d.isAfter(last) && out.size < 400) {
            out += d
            d = d.plusDays(1)
        }
        return out
    }

    /** Local hour (0–23) of [ms] under the row's offset or the fallback zone. */
    fun hourOf(ms: Long, offsetS: Int?, fallbackZone: ZoneId): Int {
        val instant = Instant.ofEpochMilli(ms)
        return if (offsetS != null) instant.atOffset(ZoneOffset.ofTotalSeconds(offsetS)).hour
        else instant.atZone(fallbackZone).hour
    }
}
