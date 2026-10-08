package com.ayuvo.health.partner

import com.ayuvo.health.data.health.SleepNight
import com.ayuvo.health.models.CompletedExercise
import com.ayuvo.health.models.CompletedSet
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.GpsLap
import com.ayuvo.health.models.GpsSplit
import com.ayuvo.health.models.GpsWorkoutSummary
import com.ayuvo.health.models.MealType
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutHeartRateStats
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerEnvelopes
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.sources.PartnerSourceMaps
import com.ayuvo.health.partner.sources.SourceRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The blob-backed renderers (docs/partner-sync.md §7.3–§7.4): every record validates as an envelope, and no
 * forbidden key (photos, image paths, notes, GPS tracks, routes…) can appear even when the source model has them.
 */
class PartnerSourceMapsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Before
    fun setUp() { PartnerTestFiles.install() }

    private fun keys(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap(::keys)
        is JsonArray -> e.flatMap(::keys).toSet()
        else -> emptySet()
    }

    private fun assertClean(r: SourceRecord) {
        val env = r.envelope(1, TEST_NOW)
        val check = PartnerEnvelopes.validate(env, TEST_NOW)
        assertTrue("${r.type}: $check $env", check.ok)
        assertEquals(r.category, check.category)
        val forbidden = PartnerCatalog.current.forbiddenKeys
        assertTrue("${r.type} leaks ${keys(env).intersect(forbidden)}", keys(env).intersect(forbidden).isEmpty())
        val spec = PartnerCatalog.current.types.getValue(r.type)
        val allowed = (spec.required + spec.optional).toSet()
        assertTrue("${r.type} has unlisted fields ${r.data.keys - allowed}", (r.data.keys - allowed).isEmpty())
    }

    @Test
    fun foodEntryHasNoPhotoOrNote() {
        val e = FoodEntry(
            id = UUID.fromString("f0e1d2c3-0000-4000-8000-000000000001"), name = "Oats with milk", calories = 320,
            protein = 12.5, carbs = 54.0, fat = 6.0, timestamp = Instant.ofEpochMilli(TEST_NOW), imageFilename = "secret.jpg",
            additionalImageFilenames = listOf("b.jpg"), emoji = "🥣", source = FoodSource.entries.first(), mealType = MealType.BREAKFAST,
            sodium = 120.0, customNote = "private note", servingSizeGrams = 250.0
        )
        val r = PartnerSourceMaps.food(e, zone)
        assertClean(r)
        assertEquals("2026-10-07", r.day)
        assertEquals("breakfast", PartnerJson.str(r.data["meal"]))
        assertEquals("250 g", PartnerJson.str(r.data["serving"]))
        assertFalse(r.data.toString().contains("secret.jpg") || r.data.toString().contains("private note"))
    }

    @Test
    fun gpsWorkoutHasNoTrackRouteSplitsOrLaps() {
        val start = Instant.ofEpochMilli(TEST_NOW - 3_600_000)
        val s = WorkoutSession(
            diaryDateKey = "2026-10-07", startedAt = start, completedAt = Instant.ofEpochMilli(TEST_NOW), durationSeconds = 3600,
            exercises = emptyList(), caloriesBurned = 410, kind = WorkoutSession.KIND_GPS, realInterval = true,
            heartRate = WorkoutHeartRateStats(avgHr = 148.0, maxHr = 171.0, trimp = 92.5),
            gps = GpsWorkoutSummary(
                sport = "running", distanceM = 10_020.0, avgPaceSecondsPerKm = 359.3,
                splits = listOf(GpsSplit(1, 350.0)), laps = listOf(GpsLap(1, 0, 1, 1000.0))
            )
        )
        val r = PartnerSourceMaps.workout(s, zone)
        assertClean(r)
        assertEquals("running", PartnerJson.str(r.data["activity"]))
        assertEquals(92.5, PartnerJson.double(r.data["load"])!!, 0.0)
        assertFalse(r.data.containsKey("splits") || r.data.containsKey("laps"))
    }

    @Test
    fun strengthWorkoutSummarisesSetsAndVolume() {
        val sets = listOf(
            CompletedSet(setNumber = 1, weight = "60", weightUnit = WorkoutWeightUnit.KG, reps = "8", rpe = "8", completed = true),
            CompletedSet(setNumber = 2, weight = "100", weightUnit = WorkoutWeightUnit.LBS, reps = "5", rpe = "", completed = true),
            CompletedSet(setNumber = 3, weight = "60", weightUnit = WorkoutWeightUnit.KG, reps = "8", rpe = "", completed = false)
        )
        val s = WorkoutSession(
            diaryDateKey = "2026-10-07", startedAt = Instant.ofEpochMilli(TEST_NOW), completedAt = Instant.ofEpochMilli(TEST_NOW),
            durationSeconds = 1800, exercises = listOf(CompletedExercise(itemId = "1", name = "Bench press", targetMuscles = emptyList(), equipment = "barbell", sets = sets))
        )
        val r = PartnerSourceMaps.workout(s, zone)
        assertClean(r)
        assertEquals(2L, PartnerJson.long(r.data["sets"]))
        assertEquals(60 * 8 + 100 * 0.45359237 * 5, PartnerJson.double(r.data["volume_kg"])!!, 0.01)
        assertEquals("Bench press", PartnerJson.str(r.data["title"]))
    }

    @Test
    fun healthConnectWorkoutAndOthersValidate() {
        assertClean(PartnerSourceMaps.healthWorkout("hc-1", 56, "Morning run", TEST_NOW - 1_800_000, TEST_NOW, "2026-10-07", "Fitbit"))
        assertEquals("running", PartnerJson.str(PartnerSourceMaps.healthWorkout("x", 56, null, 0, 1, "2026-10-07", null).data["activity"]))
        assertClean(PartnerSourceMaps.weight(WeightEntry(date = Instant.ofEpochMilli(TEST_NOW), weightKg = 71.234), zone))
        val water = PartnerSourceMaps.waterDays(
            listOf(WaterEntry(date = Instant.ofEpochMilli(TEST_NOW), milliliters = 500), WaterEntry(date = Instant.ofEpochMilli(TEST_NOW - 1000), milliliters = 250)),
            zone, "2026-10-07", 2000
        )
        assertEquals(1, water.size)
        assertEquals(750L, PartnerJson.long(water[0].data["total_ml"]))
        water.forEach(::assertClean)
        val night = SleepNight("2026-10-07", TEST_NOW - 30_000_000, TEST_NOW - 3_000_000, 27_000.0, 25_000.0, 14_000.0, 5_000.0, 6_000.0, 2_000.0, "watch")
        val n = PartnerSourceMaps.sleepNight(night, PartnerSourceMaps.NightVitals(avgHr = 55.24))
        assertClean(n)
        assertEquals(417L, PartnerJson.long(n.data["asleep_min"]))
        assertFalse(n.data.containsKey("avg_hrv"))
    }

    @Test
    fun contentHashIsStableAndSensitive() {
        val a = Recs.food("f1", "Oats", 320)
        assertEquals(a.contentHash, Recs.food("f1", "Oats", 320).contentHash)
        assertTrue(a.contentHash != Recs.food("f1", "Oats", 321).contentHash)
        assertTrue(a.contentHash != a.copy(day = "2026-10-06").contentHash)
    }
}
