package com.ayuvo.health.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Food edit sheet "Eaten at" row (docs/intake-metrics.md §3). */
class EatenAtEditingTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 9, 20)

    @Test
    fun resolvesToTheNearestDayAndDefaultsToTheLogTime() {
        val loggedAt = date.atTime(0, 30).atZone(zone)
        val logTime = LocalTime.of(0, 30)
        // Untouched: nothing stored.
        assertNull(EatenAtEditing.resolve(null, null, null, date, logTime, loggedAt, zone))
        // 23:15 picked for a 00:30 log → the previous evening.
        assertEquals(
            date.minusDays(1).atTime(23, 15).atZone(zone).toInstant(),
            EatenAtEditing.resolve(null, null, LocalTime.of(23, 15), date, logTime, loggedAt, zone)
        )
        // Same as the log time → null.
        assertNull(EatenAtEditing.resolve(null, null, logTime, date, logTime, loggedAt, zone))
        // Unchanged stored value keeps its instant (seconds included).
        val stored = date.atTime(0, 5, 42).atZone(zone).toInstant()
        assertEquals(stored, EatenAtEditing.resolve(stored, LocalTime.of(0, 5), LocalTime.of(0, 5), date, logTime, loggedAt, zone))
    }
}
