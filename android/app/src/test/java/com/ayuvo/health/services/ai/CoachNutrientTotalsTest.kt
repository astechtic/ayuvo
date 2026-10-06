package com.ayuvo.health.services.ai

import com.ayuvo.health.data.health.HealthCoachSnapshot
import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.MealType
import com.ayuvo.health.nutrients.NutrientProfile
import com.ayuvo.health.nutrients.NutrientsTestFiles
import com.ayuvo.health.nutrients.SupplementSnapshot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** `get_nutrient_totals` (docs/coach.md §8). iOS `CoachNutrientTotalsTests` uses the same fixture and numbers. */
class CoachNutrientTotalsTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)
    private val profile = NutrientProfile(age = 30.0, sex = "male", calorieGoal = 2200.0)

    @Before
    fun install() = NutrientsTestFiles.install()

    private fun at(day: Int, hour: Int): Instant = Instant.parse("2026-09-%02dT%02d:00:00Z".format(day, hour))

    private val foods = listOf(
        FoodEntry(name = "Paneer bowl", calories = 600, protein = 30.0, carbs = 50.0, fat = 25.0, timestamp = at(10, 13), source = FoodSource.MANUAL,
            mealType = MealType.LUNCH, fiber = 8.0, sodium = 900.0, calcium = 600.0, iron = 4.0, magnesium = 150.0, vitaminD = 1.0),
        FoodEntry(name = "Dal rice", calories = 500, protein = 20.0, carbs = 80.0, fat = 10.0, timestamp = at(10, 20), source = FoodSource.MANUAL,
            mealType = MealType.DINNER, fiber = 10.0, calcium = 100.0, iron = 5.0, magnesium = 120.0),
        FoodEntry(name = "Oats", calories = 300, protein = 10.0, carbs = 50.0, fat = 6.0, timestamp = at(11, 8), source = FoodSource.MANUAL,
            mealType = MealType.BREAKFAST, calcium = 300.0, magnesium = 100.0)
    )

    private fun tools(snapshot: HealthCoachSnapshot? = null) = CoachTools(
        weights = emptyList(), bodyFats = emptyList(), foods = foods, clock = clock, healthSnapshot = snapshot,
        nutrientProfile = profile, nutrientCustomGoals = mapOf("calcium" to 1200.0)
    )

    private fun run(tools: CoachTools, from: String = "2026-09-10", to: String = "2026-09-11"): JsonObject =
        JsonParser.parseString(tools.execute("get_nutrient_totals", mapOf("from" to from, "to" to to))).asJsonObject

    private fun nutrient(payload: JsonObject, key: String): JsonObject? =
        payload.getAsJsonArray("nutrients").map { it.asJsonObject }.firstOrNull { it["key"].asString == key }

    private fun strings(payload: JsonObject, name: String): List<String> = payload.getAsJsonArray(name).map { it.asString }

    @Test
    fun toolIsAFoodToolAdvertisedWithTheDiary() {
        assertTrue("get_nutrient_totals" in CoachTools.NUTRITION_TOOL_NAMES)
        assertTrue("get_nutrient_totals" in CoachTools(emptyList(), emptyList(), emptyList()).advertisedToolNames)
        assertTrue(CoachTools.TOOL_DESCRIPTIONS.getValue("get_nutrient_totals").contains("magnesium"))
    }

    @Test
    fun foodMicronutrientsHaveTotalsAveragesAndReferences() {
        val payload = run(tools())
        assertEquals("2026-09-10", payload["from"].asString)
        assertEquals(2, payload["days_in_range"].asInt)
        assertEquals(2, payload["logged_days"].asInt)
        assertEquals(3, payload["food_entries"].asInt)

        val calcium = nutrient(payload, "calcium")!!
        assertEquals(1000.0, calcium["food"].asDouble, 0.0)
        assertEquals(1000.0, calcium["total"].asDouble, 0.0)
        assertEquals(500.0, calcium["average_per_logged_day"].asDouble, 0.0)
        assertEquals("mg", calcium["unit"].asString)
        // The custom goal replaces the RDA, like the nutrient charts.
        assertEquals(1200.0, calcium["recommended"].asDouble, 0.0)
        assertEquals("custom_goal", calcium["recommended_kind"].asString)
        assertEquals(42, calcium["percent_of_recommended"].asInt)

        val iron = nutrient(payload, "iron")!!
        assertEquals(8.0, iron["recommended"].asDouble, 0.0)
        assertEquals("RDA", iron["recommended_kind"].asString)

        val macros = payload.getAsJsonObject("macros")
        assertEquals(1400.0, macros.getAsJsonObject("calories_kcal")["total"].asDouble, 0.0)
        assertEquals(30.0, macros.getAsJsonObject("protein_g")["average_per_logged_day"].asDouble, 0.0)

        val days = payload.getAsJsonArray("days").map { it.asJsonObject }
        assertEquals(2, days.size)
        assertEquals(700.0, days[0].getAsJsonObject("totals")["calcium"].asDouble, 0.0)
        assertNull(days[1].getAsJsonObject("totals")["iron"])
    }

    @Test
    fun nutrientsWithoutDataAreListedNotZeroed() {
        val payload = run(tools())
        assertNull(nutrient(payload, "vitamin_c"))
        assertNull(nutrient(payload, "copper"))
        val missing = strings(payload, "no_data_logged")
        assertTrue("vitamin_c" in missing)
        assertTrue("zinc" in missing)
        assertFalse("calcium" in missing)
        val untracked = strings(payload, "not_tracked_by_food_log")
        assertTrue("copper" in untracked)
        assertTrue("vitamin_b6" in untracked)
        assertTrue(strings(payload, "notes").any { it.contains("not zero") })
    }

    @Test
    fun supplementsAreSeparatedFromFood() {
        val taken = at(10, 9).toEpochMilli()
        // One taken dose of a supplement with 200 mg magnesium and 0.9 mg copper per unit (the iOS fixture).
        val snapshot = SupplementSnapshot(
            rows = listOf(
                com.ayuvo.health.nutrients.MedicationNutrientRow("m1", "magnesium", 200.0),
                com.ayuvo.health.nutrients.MedicationNutrientRow("m1", "copper", 0.9)
            ),
            doses = listOf(com.ayuvo.health.nutrients.SupplementDose("m1", "taken", taken, 1.0)),
            takenDoseMs = listOf(taken)
        )
        val payload = JsonParser.parseString(
            com.google.gson.GsonBuilder().serializeNulls().create().toJson(
                CoachNutrientReport.payload(foods, snapshot, LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-11"), ZoneOffset.UTC, profile)
            )
        ).asJsonObject
        val magnesium = nutrient(payload, "magnesium")!!
        assertEquals(370.0, magnesium["food"].asDouble, 0.0)
        assertEquals(200.0, magnesium["supplements"].asDouble, 0.0)
        assertEquals(570.0, magnesium["total"].asDouble, 0.0)
        assertEquals(285.0, magnesium["average_per_logged_day"].asDouble, 0.0)
        assertEquals("supplements_only", magnesium["upper_limit_scope"].asString)
        // 200 mg of supplements over two logged days is under the 350 mg supplement UL.
        assertFalse(magnesium["above_upper_limit"].asBoolean)

        val copper = nutrient(payload, "copper")!!
        assertFalse(copper["food_tracked"].asBoolean)
        assertTrue(copper["food"].isJsonNull)
        assertEquals(0.9, copper["supplements"].asDouble, 0.0)
        assertFalse("copper" in strings(payload, "not_tracked_by_food_log"))

        // Without the Medications source the supplements stay out.
        val foodOnly = run(tools())
        assertTrue(nutrient(foodOnly, "magnesium")!!["supplements"].isJsonNull)
    }

    @Test
    fun healthFillsOnlyNutrientsTheFoodLogDoesNotRecord() {
        val snapshot = HealthCoachSnapshot(
            lastSyncMs = null, types = emptyList(), daily = emptyMap(), samples = emptyMap(), nights = emptyList(), zoneId = "UTC",
            dietaryDaily = mapOf("dietary_selenium" to listOf(HealthDailyRollup("dietary_selenium", "2026-09-11", "UTC", sum = 40.0, count = 1)))
        )
        val payload = run(tools(snapshot))
        val selenium = nutrient(payload, "selenium")!!
        assertEquals(40.0, selenium["health_other_apps"].asDouble, 0.0)
        assertEquals(40.0, selenium["total"].asDouble, 0.0)
        assertEquals(40.0, selenium["average_per_logged_day"].asDouble, 0.0)
        assertEquals(55.0, selenium["recommended"].asDouble, 0.0)
        val days = payload.getAsJsonArray("days").map { it.asJsonObject }
        assertEquals(40.0, days[1].getAsJsonObject("totals")["selenium"].asDouble, 0.0)
    }

    @Test
    fun longRangesOmitDailyRows() {
        val payload = run(tools(), from = "2026-08-01", to = "2026-09-30")
        assertNull(payload["days"])
        assertTrue(payload["days_omitted"].isJsonPrimitive)
        assertEquals(1000.0, nutrient(payload, "calcium")!!["total"].asDouble, 0.0)
    }
}
