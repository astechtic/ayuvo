package com.ayuvo.health.medications.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** `local_instant` semantics (docs/medications.md §5): gap → shifted forward, overlap → earlier instant. */
class MedicationLocalTimeTest {
    private val newYork = "America/New_York"

    private fun utcMs(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun `spring-forward gap lands one hour later on the wall clock`() {
        // 2026-03-08 02:00 EST -> 03:00 EDT; 02:30 does not exist and becomes 03:30 EDT = 07:30 UTC.
        assertEquals(utcMs(2026, 3, 8, 7, 30), MedicationLocalTime.instant("2026-03-08", "02:30", newYork))
        assertEquals("03:30", MedicationLocalTime.localHhmmOf(MedicationLocalTime.instant("2026-03-08", "02:30", newYork), newYork))
    }

    @Test
    fun `fall-back overlap resolves to the earlier daylight instant`() {
        // 2026-11-01 01:30 exists twice; the earlier (EDT, UTC-4) instant is 05:30 UTC.
        assertEquals(utcMs(2026, 11, 1, 5, 30), MedicationLocalTime.instant("2026-11-01", "01:30", newYork))
    }

    @Test
    fun `half-hour zones and day windows`() {
        assertEquals(utcMs(2026, 9, 10, 2, 30), MedicationLocalTime.instant("2026-09-10", "08:00", "Asia/Kolkata"))
        val (start, end) = MedicationLocalTime.dayWindow("2026-09-10", "Asia/Kolkata")
        assertEquals(utcMs(2026, 9, 9, 18, 30), start)
        assertEquals(utcMs(2026, 9, 10, 18, 30), end)
        assertEquals("2026-09-10", MedicationLocalTime.localDateOf(start, "Asia/Kolkata"))
        assertEquals("2026-09-09", MedicationLocalTime.localDateOf(start, "UTC"))
    }

    @Test
    fun `parsers reject malformed and impossible values`() {
        assertNull(MedicationLocalTime.parseHhmm("24:00"))
        assertNull(MedicationLocalTime.parseHhmm("8:00"))
        assertEquals(8 to 5, MedicationLocalTime.parseHhmm("08:05"))
        assertNull(MedicationLocalTime.parseDate("2026-02-30"))
        assertNull(MedicationLocalTime.parseDate("2026-1-05"))
        assertEquals("2026-03-01", MedicationLocalTime.nextDate("2026-02-28"))
        assertEquals("metformin 500", MedicationLocalTime.foldName("  Metformin   500 "))
    }
}
