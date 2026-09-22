package com.ayuvo.health.widget

import com.ayuvo.health.models.LogEntryIntents
import com.ayuvo.health.models.WidgetTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogEntryIntentsTest {
    @Test
    fun logActionsParseToTheirAction() {
        QuickLogAction.entries.forEach { action ->
            assertEquals(WidgetTarget.Log(action), LogEntryIntents.targetFrom(LogEntryIntents.ACTION_LOG, action.id, null))
        }
    }

    @Test
    fun unknownOrMissingLogIdOpensSummary() {
        assertEquals(WidgetTarget.Summary, LogEntryIntents.targetFrom(LogEntryIntents.ACTION_LOG, "food.teleport", null))
        assertEquals(WidgetTarget.Summary, LogEntryIntents.targetFrom(LogEntryIntents.ACTION_LOG, null, null))
    }

    @Test
    fun metricKeysPassThroughTrimmed() {
        assertEquals(WidgetTarget.Metric("steps"), LogEntryIntents.targetFrom(LogEntryIntents.ACTION_METRIC, null, " steps "))
        assertEquals(WidgetTarget.Metric("medications:next_dose"), LogEntryIntents.targetFrom(LogEntryIntents.ACTION_METRIC, null, "medications:next_dose"))
        assertEquals(WidgetTarget.Summary, LogEntryIntents.targetFrom(LogEntryIntents.ACTION_METRIC, null, ""))
    }

    @Test
    fun summaryAndForeignActions() {
        assertEquals(WidgetTarget.Summary, LogEntryIntents.targetFrom(LogEntryIntents.ACTION_SUMMARY, null, null))
        assertNull(LogEntryIntents.targetFrom("android.intent.action.MAIN", "water", null))
        assertNull(LogEntryIntents.targetFrom(null, null, null))
    }

    @Test
    fun foodActionsCarryTheirMethodAndOthersASummaryEntry() {
        assertNull(QuickLogAction.FOOD_MENU.foodMethod)
        assertEquals(com.ayuvo.health.models.FoodLogMethod.CAMERA, QuickLogAction.FOOD_CAMERA.foodMethod)
        assertEquals(com.ayuvo.health.ui.summary.LogEntry.WATER, QuickLogAction.WATER.logEntry)
        assertEquals(com.ayuvo.health.ui.summary.LogEntry.RECORD, QuickLogAction.RECORD.logEntry)
    }
}
