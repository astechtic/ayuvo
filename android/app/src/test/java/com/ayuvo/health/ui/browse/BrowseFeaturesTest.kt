package com.ayuvo.health.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFeaturesTest {
    private fun ids(query: String, titles: Map<String, String> = emptyMap()) =
        BrowseFeatures.search(query) { titles[it.id].orEmpty() }.map { it.id }

    @Test
    fun idsMatchTheSharedList() {
        assertEquals(
            listOf(
                "workouts", "workoutLog", "exerciseLibrary", "nutrition", "logFood", "water", "fasting", "bodyMeasurements",
                "logWeight", "logBodyFat", "medications", "addMedication", "records", "addRecord",
                "insights", "recovery", "healthAge", "dailyReview", "coach", "settings"
            ),
            BrowseFeatures.all.map { it.id }
        )
    }

    @Test
    fun workoutSynonymsFindWorkoutFeatures() {
        val expected = listOf("workouts", "workoutLog", "exerciseLibrary")
        for (q in listOf("workout", "Workouts", "gym", "exercise", "  GYM ")) assertEquals(q, expected, ids(q))
    }

    @Test
    fun medicationSynonyms() {
        for (q in listOf("meds", "pills", "pill", "medicine", "tablets")) {
            val found = ids(q)
            assertTrue(q, found.containsAll(listOf("medications", "addMedication")))
        }
        assertEquals(listOf("addMedication"), ids("add meds"))
    }

    @Test
    fun recordSynonyms() {
        for (q in listOf("report", "lab", "labs", "blood test", "records")) {
            assertTrue(q, ids(q).containsAll(listOf("records", "addRecord")))
        }
        assertEquals(listOf("addRecord"), ids("upload report"))
    }

    @Test
    fun foodSynonyms() {
        for (q in listOf("food", "meal", "diary", "calories")) {
            assertTrue(q, ids(q).containsAll(listOf("nutrition", "logFood")))
        }
        assertEquals(listOf("logFood"), ids("log meal"))
    }

    @Test
    fun bodyAndOtherFeatures() {
        assertEquals(listOf("logWeight"), ids("weight"))
        assertEquals(listOf("logBodyFat"), ids("body fat"))
        assertEquals(listOf("bodyMeasurements"), ids("waist"))
        assertEquals(listOf("water"), ids("hydration"))
        assertEquals(listOf("fasting"), ids("intermittent"))
        assertEquals(listOf("coach"), ids("chat"))
        assertEquals(listOf("settings"), ids("notifications"))
    }

    @Test
    fun localizedTitleAlsoMatches() {
        assertTrue(ids("entrenamiento", mapOf("workouts" to "Entrenamientos")).contains("workouts"))
        assertTrue(ids("médicaments", mapOf("medications" to "Médicaments")).contains("medications"))
    }

    @Test
    fun blankAndUnknownQueriesFindNothing() {
        assertTrue(ids("").isEmpty())
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("zzzz").isEmpty())
        assertFalse(BrowseFeatures.matches(BrowseFeatures.all.first(), "gym zzzz"))
    }

    @Test
    fun normalizeFoldsCaseAccentsAndPunctuation() {
        assertEquals("medicaments du jour", BrowseFeatures.normalize("Médicaments, du-jour!"))
    }
}
