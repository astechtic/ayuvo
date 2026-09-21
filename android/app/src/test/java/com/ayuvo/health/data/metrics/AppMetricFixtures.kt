package com.ayuvo.health.data.metrics

import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutSession
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Small builders for app-metric tests (fixed zone, no clock). */
object AppMetricFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")

    fun at(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()
    fun ms(iso: String): Long = at(iso).toEpochMilli()

    fun food(iso: String, kcal: Int, protein: Double = 0.0, fiber: Double? = null) = FoodEntry(
        name = "f", calories = kcal, protein = protein, carbs = 0.0, fat = 0.0, timestamp = at(iso),
        source = FoodSource.MANUAL, fiber = fiber
    )

    fun water(iso: String, ml: Int) = WaterEntry(date = at(iso), milliliters = ml)
    fun weight(iso: String, kg: Double) = WeightEntry(date = at(iso), weightKg = kg)
    fun bodyFat(iso: String, fraction: Double) = BodyFatEntry(date = at(iso), bodyFatFraction = fraction)
    fun fast(start: String, end: String?) = FastingSession(startedAt = at(start), endedAt = end?.let(::at))

    fun workout(day: String, kcal: Int?, seconds: Int = 1800, version: Int? = null, completed: String = "${day}T12:00") = WorkoutSession(
        diaryDateKey = day, startedAt = at("${day}T11:00"), completedAt = at(completed),
        durationSeconds = seconds, exercises = emptyList(), caloriesBurned = kcal, healthSyncVersion = version
    )
}
