package com.ayuvo.health.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSearchTest {
    private val cablePushdown = ExerciseItem(
        id = "0201",
        name = "Cable Pushdown",
        bodyPart = "Upper Arms",
        equipment = "Cable",
        primaryMuscles = listOf("Triceps"),
        secondaryMuscles = listOf("Forearms"),
        instructions = listOf("Attach a bar to a high pulley.")
    )

    @Test
    fun contiguousQueryMatches() {
        assertTrue(
            ExerciseSearch.matches(cablePushdown.searchableText, "cable pushdown", cablePushdown.id)
        )
    }

    @Test
    fun tokenizedNonContiguousQueryMatches() {
        assertTrue(
            ExerciseSearch.matches(cablePushdown.searchableText, "triceps cable pushdown", cablePushdown.id)
        )
    }

    @Test
    fun aliasQueryMatches() {
        assertTrue(
            ExerciseSearch.matches(cablePushdown.searchableText, "tricep pushdown", cablePushdown.id)
        )
    }

    @Test
    fun missingTokenFails() {
        assertFalse(
            ExerciseSearch.matches(cablePushdown.searchableText, "triceps rope", cablePushdown.id)
        )
    }

    @Test
    fun instructionsAreNotSearched() {
        assertFalse(
            ExerciseSearch.matches(cablePushdown.searchableText, "pulley", cablePushdown.id)
        )
    }
}
