package com.ayuvo.health.cycle

import com.ayuvo.health.cycle.data.CyclePlatformPeriods
import com.ayuvo.health.cycle.data.CyclePlatformPeriods.Sample
import com.ayuvo.health.cycle.engine.CyclePeriodInput
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Health Connect periods for the engine (docs/cycle-tracking.md §4): own rows skipped, flow days grouped in runs. */
class CyclePlatformPeriodsTest {
    private val zone = ZoneOffset.UTC
    private fun ms(day: String) = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun period(id: String, start: String, endExclusive: String, source: String = "com.other", client: String? = null, origin: Int = 0) =
        Sample(id, "menstruation_period", ms(start), ms(endExclusive), 0, 0, start, null, source, client, origin)

    private fun flow(day: String, code: Int?, source: String = "com.other", client: String? = null) =
        Sample("f$day", "menstrual_flow", ms(day), ms(day), 0, 0, day, code, source, client, 0)

    @Test
    fun periodsUseExclusiveEndsAndSkipAyuvoRows() {
        val got = CyclePlatformPeriods.periods(
            listOf(
                period("a", "2026-09-01", "2026-09-06"),
                period("own", "2026-08-01", "2026-08-05", source = "com.ayuvo.health"),
                period("client", "2026-07-01", "2026-07-05", client = "ayuvo:cycle:local:1"),
                period("local", "2026-06-01", "2026-06-05", origin = 2)
            ),
            ownPackage = "com.ayuvo.health", zone = zone
        )
        assertEquals(listOf(CyclePeriodInput("hc:a", "2026-09-01", "2026-09-05", "health_connect")), got)
    }

    @Test
    fun flowDaysFormRunsAndNoneOrOwnFlowIsIgnored() {
        val got = CyclePlatformPeriods.periods(
            listOf(
                flow("2026-08-31", 2), flow("2026-09-01", 3), flow("2026-09-02", 4),
                flow("2026-09-03", 5), // none
                flow("2026-09-10", 1),
                flow("2026-09-20", 3, client = "ayuvo:flow:2026-09-20"),
                flow("2026-12-31", 2), flow("2027-01-01", 2)
            ),
            ownPackage = "com.ayuvo.health", zone = zone
        )
        assertEquals(
            listOf(
                CyclePeriodInput("hcflow:2026-08-31", "2026-08-31", "2026-09-02", "health_connect"),
                CyclePeriodInput("hcflow:2026-09-10", "2026-09-10", "2026-09-10", "health_connect"),
                CyclePeriodInput("hcflow:2026-12-31", "2026-12-31", "2027-01-01", "health_connect")
            ),
            got
        )
    }
}
