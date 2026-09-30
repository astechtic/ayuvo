package com.ayuvo.health.workout

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared workout vector file (docs/workouts-gps.md), plus coverage and asset parity. */
class WorkoutVectorTests {
    @Test fun gpsTrack() = WorkoutVectors.assertAll("gps_track.json")
    @Test fun hrWorkout() = WorkoutVectors.assertAll("hr_workout.json")
    @Test fun hrRecovery() = WorkoutVectors.assertAll("hr_recovery.json")
    @Test fun vo2maxGps() = WorkoutVectors.assertAll("vo2max_gps.json")
    @Test fun cooper() = WorkoutVectors.assertAll("cooper.json")
    @Test fun workoutWindows() = WorkoutVectors.assertAll("workout_windows.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = WorkoutTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(WorkoutVectors.FILES.keys, files)
        for ((file, function) in WorkoutVectors.FILES) {
            val root = MedicationJson.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-workout-vectors", root.str("format"))
            assertEquals(file, function, root.str("function"))
        }
        val runnerMethods = WorkoutVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", WorkoutVectors.FILES.size + 2, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        val name = "workout_config.json"
        assertArrayEquals(name, WorkoutTestFiles.shared(name)!!.readBytes(), WorkoutTestFiles.asset(name)!!.readBytes())
    }
}
