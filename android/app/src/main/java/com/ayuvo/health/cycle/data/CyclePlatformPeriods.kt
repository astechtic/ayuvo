package com.ayuvo.health.cycle.data

import com.ayuvo.health.cycle.engine.CyclePeriodInput
import com.ayuvo.health.data.health.HealthDayKeys
import java.time.ZoneId

/**
 * Periods read from Health Connect (docs/cycle-tracking.md §4): `menstruation_period` rows and runs of
 * `menstrual_flow` days already mirrored in `health_samples`. Rows Ayuvo wrote itself are skipped, so a synced
 * app period is never counted twice. Pure JVM; [CycleRepository] feeds it the rows.
 */
object CyclePlatformPeriods {
    const val TYPE_PERIOD = "menstruation_period"
    const val TYPE_FLOW = "menstrual_flow"
    const val SOURCE = "health_connect"
    const val CLIENT_PERIOD_PREFIX = "ayuvo:cycle:"
    const val CLIENT_FLOW_PREFIX = "ayuvo:flow:"

    /** Category codes of `menstrual_flow` that mean bleeding (1 unspecified … 4 heavy; 5 = none). */
    private val BLEEDING_CODES = setOf(1, 2, 3, 4)

    /** The `health_samples` columns this needs. */
    data class Sample(
        val id: String,
        val typeId: String,
        val startMs: Long,
        val endMs: Long,
        val startOffsetS: Int?,
        val endOffsetS: Int?,
        val localDay: String,
        val categoryValue: Int?,
        val sourceId: String,
        val clientRecordId: String?,
        val origin: Int
    )

    fun isOwn(s: Sample, ownPackage: String): Boolean =
        s.origin == 2 || s.sourceId == ownPackage ||
            s.clientRecordId?.let { it.startsWith(CLIENT_PERIOD_PREFIX) || it.startsWith(CLIENT_FLOW_PREFIX) } == true

    fun periods(samples: List<Sample>, ownPackage: String, zone: ZoneId = ZoneId.systemDefault()): List<CyclePeriodInput> {
        val out = mutableListOf<CyclePeriodInput>()
        val flowDays = sortedSetOf<String>()
        for (s in samples) {
            if (isOwn(s, ownPackage)) continue
            when (s.typeId) {
                TYPE_PERIOD -> {
                    // End instants are exclusive (a period ending at midnight does not cover the next day).
                    val end = if (s.endMs > s.startMs) s.endMs - 1 else s.startMs
                    val days = HealthDayKeys.daysCovered(s.startMs, end, s.startOffsetS, s.endOffsetS, zone)
                    out += CyclePeriodInput("hc:${s.id}", days.first().toString(), days.last().toString(), SOURCE)
                }
                TYPE_FLOW -> if (s.categoryValue in BLEEDING_CODES) flowDays += s.localDay
            }
        }
        var runStart: String? = null
        var prev: java.time.LocalDate? = null
        for (day in flowDays) {
            val d = java.time.LocalDate.parse(day)
            if (prev != null && d == prev.plusDays(1)) {
                prev = d; continue
            }
            if (runStart != null && prev != null) out += CyclePeriodInput("hcflow:$runStart", runStart, prev.toString(), SOURCE)
            runStart = day
            prev = d
        }
        if (runStart != null && prev != null) out += CyclePeriodInput("hcflow:$runStart", runStart, prev.toString(), SOURCE)
        return out
    }
}
