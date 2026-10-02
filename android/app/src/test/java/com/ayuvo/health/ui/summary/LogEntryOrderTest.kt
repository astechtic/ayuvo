package com.ayuvo.health.ui.summary

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/ui-structure.md §8: "+" sheet order and `log.entry.<tag>` ids. */
class LogEntryOrderTest {
    @Test
    fun entriesFollowTheDocumentedOrder() {
        assertEquals(
            listOf(
                "food", "water", "fasting", "weight", "bodyFat", "bloodGlucose", "bodyTemperature", "period", "workout", "medication", "record",
                "fingerScan", "faceScan", "compareScan"
            ),
            LogEntry.entries.map { it.tag }
        )
    }

    @Test
    fun compareIsOfferedNowThatChainingShips() {
        // docs/camera-vitals.md §6 / §7.1 "Entry points": Measure = Finger scan, Face scan, Compare finger & face.
        assertEquals(true, LogEntry.COMPARE_AVAILABLE)
        assertEquals(
            listOf("fingerScan", "faceScan", "compareScan"),
            LogEntry.entries.takeLast(3).map { it.tag }
        )
    }
}
