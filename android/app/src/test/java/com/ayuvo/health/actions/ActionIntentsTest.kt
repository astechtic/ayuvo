package com.ayuvo.health.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActionIntentsTest {
    private val validator = ActionValidator(ActionsTestFiles.catalog)

    private fun request(p: ActionIntents.Parsed?): ActionRequest = (p as ActionIntents.Parsed.Request).request

    @Test
    fun shortcutIntentCarriesActionIdAndParams() {
        val r = request(ActionIntents.parse(ActionIntents.ACTION, null, mapOf("action_id" to "fasting.start", "goal_hours" to "16")))
        assertEquals("fasting.start", r.id)
        assertEquals(mapOf("goal_hours" to "16"), r.params)
        assertEquals(ActionSource.ANDROID, r.source)
        assertNull(ActionIntents.parse(ActionIntents.ACTION, null, emptyMap()))
    }

    @Test
    fun deepLinksAreParsedAndOtherLinksIgnored() {
        val r = request(ActionIntents.parse("android.intent.action.VIEW", "ayuvo://action/water.log?amount=750", emptyMap()))
        assertEquals("water.log", r.id)
        assertEquals(ActionSource.DEEPLINK, r.source)
        assertEquals("open.section", request(ActionIntents.parse("android.intent.action.VIEW", "ayuvo://open/records", emptyMap())).id)
        assertNull(ActionIntents.parse("android.intent.action.VIEW", "content://media/1", emptyMap()))
        assertNull(ActionIntents.parse("android.intent.action.VIEW", "ayuvo://log/water", emptyMap()))
        val bad = ActionIntents.parse("android.intent.action.VIEW", "ayuvo://action/water.log?amount=1&amount=2", emptyMap())
        assertEquals(ActionIntents.Parsed.BadLink(ActionDeepLink.LinkError.DUPLICATE_PARAM), bad)
    }

    @Test
    fun externalWritesAlwaysConfirm() {
        fun confirm(id: String, params: Map<String, Any?>, source: ActionSource): Boolean {
            val v = validator.validate(id, params, source, mapOf("volume_unit" to "ml")) as ValidationResult.Ok
            return ActionIntents.needsConfirmation(v.action)
        }
        assertTrue(confirm("water.log", mapOf("amount" to "500"), ActionSource.ANDROID))
        assertTrue(confirm("water.log", mapOf("amount" to "500"), ActionSource.DEEPLINK))
        assertFalse(confirm("water.log", mapOf("amount" to "500"), ActionSource.SIRI))
        assertFalse(confirm("water.get", emptyMap(), ActionSource.ANDROID))
        assertFalse(confirm("workout.start", emptyMap(), ActionSource.ANDROID))
    }

    private val today = LocalDate.of(2026, 9, 16)
    private fun assistant(bii: String, vararg p: Pair<String, String>) =
        AssistantIntents.map(bii, p.toMap(), today, ZoneId.of("UTC"))!!

    @Test
    fun assistantIntentsMapToValidCatalogRequests() {
        val cases = listOf(
            assistant(AssistantIntents.GET_HEALTH_OBSERVATION, "name" to "Weight") to "weight.get",
            assistant(AssistantIntents.GET_HEALTH_OBSERVATION, "name" to "Heart Rate") to "health.metric.latest",
            assistant(AssistantIntents.GET_HEALTH_OBSERVATION, "name" to "Sleep Duration") to "health.sleep.lastNight",
            assistant(AssistantIntents.RECORD_HEALTH_OBSERVATION, "name" to "Weight", "value" to "70", "unitText" to "Kilogram") to "weight.log",
            assistant(AssistantIntents.RECORD_FOOD_OBSERVATION, "aboutNutrientName" to "Water", "value" to "750", "unitText" to "Milliliter") to "water.log",
            assistant(AssistantIntents.RECORD_FOOD_OBSERVATION, "aboutFoodName" to "banana", "forMeal" to "http://schema.googleapis.com/MealTypeBreakfast") to "nutrition.food.log",
            assistant(AssistantIntents.GET_FOOD_OBSERVATION, "aboutNutrientName" to "Protein", "startTime" to "2026-09-15T00:00:00Z") to "nutrition.nutrient.get",
            assistant(AssistantIntents.GET_FOOD_OBSERVATION) to "nutrition.summary.get",
            assistant(AssistantIntents.START_EXERCISE, "exerciseName" to "Running") to "workout.start",
            assistant(AssistantIntents.STOP_EXERCISE) to "workout.finish",
            assistant(AssistantIntents.GET_EXERCISE_OBSERVATION) to "workout.today.get",
            assistant(AssistantIntents.OPEN_APP_FEATURE, "feature" to "Health Records") to "open.section"
        )
        for ((req, id) in cases) {
            assertEquals(id, req.id)
            val v = validator.validate(req.id, req.params, req.source, mapOf("mass_unit" to "kg", "volume_unit" to "ml", "length_unit" to "cm"))
            assertTrue("${req.id} ${req.params} → $v", v is ValidationResult.Ok)
        }
        assertEquals("yesterday", assistant(AssistantIntents.GET_FOOD_OBSERVATION, "aboutNutrientName" to "Protein", "startTime" to "2026-09-15T00:00:00Z").params["range"])
        assertEquals("records", assistant(AssistantIntents.OPEN_APP_FEATURE, "feature" to "Health Records").params["section"])
        assertEquals("breakfast", assistant(AssistantIntents.RECORD_FOOD_OBSERVATION, "aboutFoodName" to "toast", "forMeal" to "http://schema.googleapis.com/MealTypeBreakfast").params["meal"])
        assertNull(AssistantIntents.map("made_up", emptyMap(), today, ZoneId.of("UTC")))
    }

    @Test
    fun recordingOtherVitalsOpensTheAppInsteadOfGuessing() {
        val r = assistant(AssistantIntents.RECORD_HEALTH_OBSERVATION, "name" to "Blood Pressure", "value" to "120")
        assertEquals("open.section", r.id)
    }
}
