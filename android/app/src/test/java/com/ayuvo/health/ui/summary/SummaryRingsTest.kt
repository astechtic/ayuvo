package com.ayuvo.health.ui.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryRingsTest {
    private val base = SummaryRingInputs(
        caloriesToday = 1_000.0,
        calorieGoal = 2_000.0,
        stepsToday = 5_000.0,
        stepGoal = 10_000.0,
        stepSource = true,
        waterTodayMl = 500.0,
        waterGoalMl = 2_000.0,
        waterTracking = true
    )

    @Test
    fun threeRingsInEatMoveDrinkOrder() {
        val rings = SummaryRings.build(base)
        assertEquals(listOf(SummaryRingId.EAT, SummaryRingId.MOVE, SummaryRingId.DRINK), rings.map { it.id })
        assertEquals(0.5f, rings[0].progress, 0f)
        assertEquals(50, rings[0].percent)
        assertEquals(0.25f, rings[2].progress, 0f)
    }

    @Test
    fun drinkRingIsHiddenWhenWaterTrackingIsOff() {
        val rings = SummaryRings.build(base.copy(waterTracking = false))
        assertEquals(listOf(SummaryRingId.EAT, SummaryRingId.MOVE), rings.map { it.id })
    }

    @Test
    fun moveRingShowsConnectWithoutAStepSource() {
        val move = SummaryRings.build(base.copy(stepSource = false, stepsToday = null)).first { it.id == SummaryRingId.MOVE }
        assertEquals(SummaryRingState.CONNECT, move.state)
        assertEquals(0f, move.progress, 0f)
        assertNull(move.value)
        assertNull(move.percent)
    }

    @Test
    fun unreadStepsAreNoDataNotZero() {
        val move = SummaryRings.build(base.copy(stepsToday = null)).first { it.id == SummaryRingId.MOVE }
        assertEquals(SummaryRingState.NO_DATA, move.state)
        assertNull(move.value)
    }

    @Test
    fun missingGoalIsNoGoalAndOverGoalClampsTheFill() {
        val eat = SummaryRings.ring(SummaryRingId.EAT, 1_500.0, null)
        assertEquals(SummaryRingState.NO_GOAL, eat.state)
        assertNull(eat.goal)
        val over = SummaryRings.ring(SummaryRingId.EAT, 3_000.0, 2_000.0)
        assertEquals(1f, over.progress, 0f)
        assertEquals(150, over.percent)
        assertEquals(SummaryRingState.NO_GOAL, SummaryRings.ring(SummaryRingId.DRINK, 10.0, 0.0).state)
    }
}
