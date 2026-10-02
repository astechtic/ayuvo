package com.ayuvo.health.ui.summary

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/ui-structure.md §8: "+" sheet order and `log.entry.<tag>` ids. */
class LogEntryOrderTest {
    @Test
    fun entriesFollowTheDocumentedOrder() {
        assertEquals(
            listOf("food", "water", "fasting", "weight", "bodyFat", "bloodGlucose", "bodyTemperature", "workout", "medication", "record"),
            LogEntry.entries.map { it.tag }
        )
    }
}
