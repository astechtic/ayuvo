package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthDayAttribution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HealthDayAttributionTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun rowOffsetWinsOverDeviceZone() {
        val ms = Instant.parse("2026-09-14T03:00:00Z").toEpochMilli()
        // UTC-5 → still Sep 13; device zone (Berlin, UTC+2) would say Sep 14.
        assertEquals("2026-09-13", HealthDayKeys.localDay(HealthDayAttribution.START, ms, ms, -5 * 3600, -5 * 3600, berlin))
        assertEquals("2026-09-14", HealthDayKeys.localDay(HealthDayAttribution.START, ms, ms, null, null, berlin))
    }

    @Test
    fun sleepIsAttributedToTheWakeDay() {
        val start = Instant.parse("2026-09-12T21:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-09-13T05:30:00Z").toEpochMilli()
        assertEquals("2026-09-13", HealthDayKeys.localDay(HealthDataType.SLEEP, start, end, 0, 0, ZoneOffset.UTC))
        assertEquals("2026-09-12", HealthDayKeys.localDay(HealthDataType.WORKOUT, start, end, 0, 0, ZoneOffset.UTC))
    }

    @Test
    fun endAttributionFallsBackToStartOffsetWhenEndOffsetIsMissing() {
        val start = Instant.parse("2026-09-12T21:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-09-13T01:30:00Z").toEpochMilli()
        // Offset −3h: end is 22:30 on Sep 12 locally.
        assertEquals("2026-09-12", HealthDayKeys.localDay(HealthDayAttribution.END, start, end, -3 * 3600, null, ZoneOffset.UTC))
    }

    @Test
    fun dstTransitionKeepsBothSidesOnTheSameLocalDay() {
        // Berlin springs forward 2026-03-29 02:00 → 03:00.
        val before = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli() // 01:30 CET
        val after = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()  // 03:30 CEST
        assertEquals("2026-03-29", HealthDayKeys.dayOf(before, null, berlin).toString())
        assertEquals("2026-03-29", HealthDayKeys.dayOf(after, null, berlin).toString())
        assertEquals(1, HealthDayKeys.hourOf(before, null, berlin))
        assertEquals(3, HealthDayKeys.hourOf(after, null, berlin))
    }

    @Test
    fun spanCoversEveryDayInclusive() {
        val start = Instant.parse("2026-09-10T20:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-09-13T04:00:00Z").toEpochMilli()
        assertEquals(
            listOf(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13)),
            HealthDayKeys.daysCovered(start, end, 0, 0, ZoneOffset.UTC)
        )
        assertEquals("2026-09-10", HealthDayKeys.localDay(HealthDataType.MENSTRUATION_PERIOD, start, end, 0, 0, ZoneOffset.UTC))
    }
}
