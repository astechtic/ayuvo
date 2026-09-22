package com.ayuvo.health.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickLogConfigTest {
    @Test
    fun emptyStateUsesDefaults() {
        assertEquals(QuickLogAction.Defaults, QuickLogAction.slots(emptyList()))
        assertEquals(WidgetMetric.Defaults, WidgetMetric.slots(listOf(null, null, null, null)))
    }

    @Test
    fun unknownIdFallsBackToThatSlotsDefault() {
        val slots = QuickLogAction.slots(listOf("record", "nope", null, "food.voice"))
        assertEquals(listOf(QuickLogAction.RECORD, QuickLogAction.Defaults[1], QuickLogAction.Defaults[2], QuickLogAction.FOOD_VOICE), slots)
        val metrics = WidgetMetric.slots(listOf("sleep", "app:unknown"))
        assertEquals(listOf(WidgetMetric.SLEEP, WidgetMetric.Defaults[1], WidgetMetric.Defaults[2], WidgetMetric.Defaults[3]), metrics)
    }

    @Test
    fun duplicatesAreKeptAndExtraSlotsIgnored() {
        assertEquals(List(4) { QuickLogAction.WATER }, QuickLogAction.slots(List(6) { "water" }))
        assertEquals(List(4) { WidgetMetric.NEXT_DOSE }, WidgetMetric.slots(List(5) { "medications:next_dose" }))
    }
}
