package com.ayuvo.health.widget

import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.MealType
import com.ayuvo.health.widget.history.HistoryHeatmap
import com.ayuvo.health.widget.history.HistorySeries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** `shared/widgets/history_heatmap.json` is the contract of the Workout / Food history widgets. */
class HistoryHeatmapContractTest {
    private val root: JsonObject by lazy {
        val file = listOf("../../shared/widgets/history_heatmap.json", "../shared/widgets/history_heatmap.json", "shared/widgets/history_heatmap.json")
            .map(::File).firstOrNull { it.exists() }
        assertNotNull("shared/widgets/history_heatmap.json not found", file)
        Json.parseToJsonElement(file!!.readText()).jsonObject
    }

    private val vectors get() = root.getValue("vectors").jsonObject

    @Test
    fun envelopeAndThresholds() {
        assertEquals("ayuvo-history-heatmap", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        assertEquals(HistoryHeatmap.DAYS, root.getValue("days").jsonPrimitive.int)
        assertEquals(HistoryHeatmap.LEVELS, root.getValue("levels").jsonPrimitive.int)
        assertEquals(
            root.getValue("workout").jsonObject.getValue("min_seconds_per_level").jsonArray.map { it.jsonPrimitive.long },
            HistoryHeatmap.WORKOUT_MIN_SECONDS.toList()
        )
        assertEquals(
            root.getValue("food").jsonObject.getValue("min_meals_per_level").jsonArray.map { it.jsonPrimitive.int },
            HistoryHeatmap.FOOD_MIN_MEALS.toList()
        )
    }

    @Test
    fun workoutLevels() {
        vectors.getValue("workout_level").jsonArray.map { it.jsonObject }.forEach { v ->
            val workouts = v.getValue("workouts").jsonPrimitive.int
            val seconds = v.getValue("seconds").jsonPrimitive.long
            assertEquals("$v", v.getValue("level").jsonPrimitive.int, HistoryHeatmap.workoutLevel(workouts, seconds))
        }
    }

    @Test
    fun foodLevels() {
        vectors.getValue("food_level").jsonArray.map { it.jsonObject }.forEach { v ->
            val meals = v.getValue("meals").jsonArray.map { m -> MealType.entries.first { it.name.lowercase() == m.jsonPrimitive.content } }
            assertEquals("$v", v.getValue("level").jsonPrimitive.int, HistoryHeatmap.foodLevel(meals))
        }
    }

    @Test
    fun grid() {
        vectors.getValue("grid").jsonArray.map { it.jsonObject }.forEach { v ->
            val grid = HistoryHeatmap.grid(
                today = LocalDate.parse(v.getValue("today").jsonPrimitive.content),
                weekStart = WeekStart.fromRaw(v.getValue("week_start").jsonPrimitive.content),
                columns = v.getValue("columns").jsonPrimitive.int
            )
            assertEquals("$v", LocalDate.parse(v.getValue("first_day").jsonPrimitive.content), grid.firstDay)
            assertEquals("$v", v.getValue("today_row").jsonPrimitive.int, grid.todayRow)
            assertEquals("$v", v.getValue("hidden_after_today").jsonPrimitive.int, grid.hiddenAfterToday)
        }
    }

    @Test
    fun encodeAndReadBack() {
        val today = LocalDate.of(2026, 10, 7)
        val encoded = HistoryHeatmap.encode(mapOf(today to 4, today.minusDays(1) to 2, today.minusDays(400) to 3), today)
        assertEquals(HistoryHeatmap.DAYS, encoded.length)
        val series = HistorySeries(encoded, 2)
        assertEquals(4, HistoryHeatmap.levelAt(series, today, today))
        assertEquals(2, HistoryHeatmap.levelAt(series, today, today.minusDays(1)))
        assertEquals(0, HistoryHeatmap.levelAt(series, today, today.minusDays(400)))
        // After midnight, before a refresh: the new day is not covered and reads empty.
        assertEquals(0, HistoryHeatmap.levelAt(series, today, today.plusDays(1)))
    }

    @Test
    fun buildCountsDistinctMealsPerDiaryDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val today = LocalDate.of(2026, 10, 7)
        fun entry(day: LocalDate, hour: Int, meal: MealType) = FoodEntry(
            name = "x", calories = 100, protein = 0.0, carbs = 0.0, fat = 0.0,
            timestamp = day.atTime(hour, 0).atZone(zone).toInstant(), source = FoodSource.MANUAL, mealType = meal
        )
        val snapshot = HistoryHeatmap.build(
            workouts = emptyList(),
            food = listOf(
                entry(today, 8, MealType.BREAKFAST), entry(today, 13, MealType.LUNCH), entry(today, 15, MealType.LUNCH),
                entry(today.minusDays(1), 20, MealType.SNACK)
            ),
            today = today, zone = zone, weekStart = WeekStart.MONDAY, nowMs = 0
        )
        assertEquals(2, HistoryHeatmap.levelAt(snapshot.food, today, today))
        assertEquals(1, HistoryHeatmap.levelAt(snapshot.food, today, today.minusDays(1)))
        assertEquals(2, snapshot.food.activeDays)
        assertEquals(0, snapshot.workout.activeDays)
    }
}
