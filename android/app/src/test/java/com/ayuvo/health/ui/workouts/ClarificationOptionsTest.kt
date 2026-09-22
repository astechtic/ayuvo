package com.ayuvo.health.ui.workouts

import com.ayuvo.health.data.ExerciseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClarificationOptionsTest {
    private fun item(id: String, name: String) = ExerciseItem(
        id = id, name = name, bodyPart = "Upper Arms", equipment = "Dumbbell",
        primaryMuscles = listOf("Biceps"), secondaryMuscles = emptyList(), instructions = emptyList(),
        imageUrl = "https://example.invalid/$id.jpg"
    )

    private val library = listOf(
        item("0294", "Dumbbell Alternate Biceps Curl"),
        item("2403", "Dumbbell Alternate Biceps Curl (with Arm Blaster)"),
        item("1649", "Dumbbell Bicep Curl Lunge With Bowling Motion")
    )

    @Test
    fun matchesExactNamesIgnoringCase() {
        assertEquals("0294", ClarificationOptions.exerciseFor("dumbbell alternate biceps curl", library)?.id)
        assertEquals("2403", ClarificationOptions.exerciseFor("Dumbbell Alternate Biceps Curl (with Arm Blaster)", library)?.id)
    }

    @Test
    fun matchesIgnoringPunctuationAndSpacing() {
        assertEquals("1649", ClarificationOptions.exerciseFor("  Dumbbell bicep-curl lunge with bowling motion ", library)?.id)
    }

    @Test
    fun nonExerciseAnswersStayChips() {
        assertNull(ClarificationOptions.exerciseFor("3x10", library))
        assertNull(ClarificationOptions.matches(listOf("3x10", "3x8", "3x12"), library))
        assertNull(ClarificationOptions.matches(listOf("Barbell", "Dumbbells", "Machine"), library))
    }

    @Test
    fun cardsKeepOptionOrderWithGapsForUnknownNames() {
        val found = ClarificationOptions.matches(listOf("Dumbbell Alternate Biceps Curl", "Dumbbell Biceps Curl Squat"), library)
        assertEquals(listOf("0294", null), found?.map { it?.id })
    }
}
