package com.ayuvo.health.derived

import com.ayuvo.health.data.derived.DerivedIntakeInputs
import com.ayuvo.health.data.derived.SleepRow
import com.ayuvo.health.intake.IntakeTestFiles
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Per-day `nutrition_day` / `energy_balance` inputs from the food diary (docs/intake-metrics.md §1). */
class DerivedIntakeInputsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 9, 20)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant()

    private fun food(name: String, logged: java.time.Instant, eaten: java.time.Instant? = null, meal: MealType = MealType.LUNCH,
                     kcal: Int = 500, protein: Double = 20.0, iron: Double? = null, caffeine: Double? = null) =
        FoodEntry(name = name, calories = kcal, protein = protein, carbs = 60.0, fat = 15.0, timestamp = logged,
            source = FoodSource.MANUAL, mealType = meal, iron = iron, caffeine = caffeine, eatenAt = eaten)

    @Test
    fun eatenAtDecidesTheDayAndTheEatingWindow() {
        // Dinner eaten at 23:00 on the 20th but logged after midnight belongs to the 20th.
        val entries = listOf(
            food("Poha", at(day, 8), meal = MealType.BREAKFAST),
            food("Dinner", at(day.plusDays(1), 0, 30), eaten = at(day, 23), meal = MealType.DINNER)
        )
        val inputs = DerivedIntakeInputs(entries, zone, mapOf(day.minusDays(3) to 70.0), IntakeTestFiles.config)
        val out = inputs.nutritionDay(day, bedtimeMs = at(day.plusDays(1), 0, 15).toEpochMilli())!!
        assertEquals(2, out["items"])
        assertEquals(900.0, out["eating_window_min"])
        assertEquals(75.0, out["last_meal_to_bed_min"])
        assertEquals(0.57, out["protein_g_per_kg"])
        assertNull(inputs.nutritionDay(day.plusDays(1), null))
        assertEquals(mapOf(day.toString() to 1000.0), inputs.intakeByDay)
    }

    @Test
    fun teaOrCoffeeByCaffeineOrWholeWordName() {
        assertTrue(DerivedIntakeInputs.isTeaOrCoffee(food("Masala chai", at(day, 9))))
        assertTrue(DerivedIntakeInputs.isTeaOrCoffee(food("Iced Coffee", at(day, 9))))
        assertTrue(DerivedIntakeInputs.isTeaOrCoffee(food("Cola", at(day, 9), caffeine = 30.0)))
        assertFalse(DerivedIntakeInputs.isTeaOrCoffee(food("Steak", at(day, 9))))
    }

    @Test
    fun addToFillsNutritionDayAndEnergyBalanceFromEnergyDay() {
        val entries = listOf(
            food("Spinach dal", at(day, 13), iron = 4.0),
            food("Green tea", at(day, 13, 30), kcal = 2, protein = 0.0)
        )
        val inputs = DerivedIntakeInputs(entries, zone, emptyMap(), IntakeTestFiles.config)
        val out = HashMap<String, Map<String, Any?>>()
        out["energy_day"] = mapOf("tdee" to 2100.0)
        inputs.addTo(out, day, listOf(SleepRow(at(day, 22, 45).toEpochMilli(), at(day.plusDays(1), 6).toEpochMilli(), 1)))
        assertEquals(1, out[DerivedIntakeInputs.NUTRITION_DAY]!!["iron_absorption_risk"])
        assertEquals(555.0, out[DerivedIntakeInputs.NUTRITION_DAY]!!["last_meal_to_bed_min"])
        assertEquals(-1598.0, out[DerivedIntakeInputs.ENERGY_BALANCE]!!["balance_kcal"])
        assertEquals("insufficient", out[DerivedIntakeInputs.ENERGY_BALANCE]!!["status"])
    }
}
