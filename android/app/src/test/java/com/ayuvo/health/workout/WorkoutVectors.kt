package com.ayuvo.health.workout

import com.ayuvo.health.data.workout.CardioFitness
import com.ayuvo.health.data.workout.CooperInput
import com.ayuvo.health.data.workout.GpsPoint
import com.ayuvo.health.data.workout.GpsTrack
import com.ayuvo.health.data.workout.GpsTrackInput
import com.ayuvo.health.data.workout.HeartRateWorkout
import com.ayuvo.health.data.workout.HrMinuteSeries
import com.ayuvo.health.data.workout.HrRecoveryInput
import com.ayuvo.health.data.workout.HrSample
import com.ayuvo.health.data.workout.HrWorkoutInput
import com.ayuvo.health.data.workout.SteadySegment
import com.ayuvo.health.data.workout.TimeSpan
import com.ayuvo.health.data.workout.Vo2maxGpsInput
import com.ayuvo.health.data.workout.WorkoutConfig
import com.ayuvo.health.data.workout.WorkoutWindowsInput
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates the shared workout contract from the Gradle unit-test working directory (android/app). */
object WorkoutTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/workout/$relative", "../shared/workout/$relative", "shared/workout/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/workout/$relative", "app/src/main/assets/workout/$relative").map(::File).firstOrNull { it.exists() }

    val config: WorkoutConfig by lazy { WorkoutConfig.parse(shared("workout_config.json")!!.readText()) }
}

/** Runs `shared/workout/test-vectors/<file>` through the Kotlin engines (dispatch mirrors `workout_reference.FUNCTIONS`). */
object WorkoutVectors {
    val FILES = linkedMapOf(
        "gps_track.json" to "gps_track",
        "hr_workout.json" to "hr_workout",
        "hr_recovery.json" to "hr_recovery",
        "vo2max_gps.json" to "vo2max_gps",
        "cooper.json" to "cooper",
        "workout_windows.json" to "workout_windows"
    )

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = WorkoutTestFiles.shared("test-vectors/$file") ?: run { fail("shared/workout/test-vectors/$file missing"); error("") }
        val root = MedicationJson.json.parseToJsonElement(f.readText()) as JsonObject
        val function = root.str("function") ?: error("$file has no function")
        val cases = root["cases"] as JsonArray
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = case.str("name") ?: "?"
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val diff = RecordsVectors.diff(case["expected"]!!, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff\n    actual=$actual"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS workout/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- Decoding --------------------------------------------------------------------------------

    private fun num(e: JsonElement?): Double? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null }?.doubleOrNull

    private fun lng(e: JsonElement?): Long = (e as JsonPrimitive).let { it.longOrNull ?: it.doubleOrNull!!.toLong() }

    private fun arrays(e: JsonElement?): List<JsonArray> = (e as? JsonArray).orEmpty().map { it as JsonArray }

    private fun samples(e: JsonElement?): List<HrSample> = arrays(e).map { HrSample(lng(it[0]), num(it[1])!!) }

    fun runCase(function: String, i: JsonObject): JsonElement {
        val cfg = WorkoutTestFiles.config
        return when (function) {
            "gps_track" -> GpsTrack.gpsTrack(
                GpsTrackInput(
                    sport = i.str("sport")!!,
                    points = arrays(i["points"]).map { GpsPoint(lng(it[0]), num(it[1])!!, num(it[2])!!, num(it[3]), num(it[4]), num(it[5])) },
                    pauses = arrays(i["pauses"]).map { TimeSpan(lng(it[0]), lng(it[1])) },
                    startMs = i.long("start_ms")!!, endMs = i.long("end_ms")!!
                ), cfg
            ).toJson()
            "hr_workout" -> HeartRateWorkout.hrWorkout(
                HrWorkoutInput(
                    samples = samples(i["samples"]), startMs = i.long("start_ms")!!, endMs = i.long("end_ms")!!,
                    hrMax = i.double("hr_max")!!, rhr = i.double("rhr"), sex = i.str("sex"), age = i.double("age"), weightKg = i.double("weight_kg")
                ), cfg
            ).toJson()
            "hr_recovery" -> HeartRateWorkout.hrRecovery(HrRecoveryInput(samples(i["samples"]), i.long("end_ms")!!), cfg).toJson()
            "vo2max_gps" -> CardioFitness.vo2maxGps(
                Vo2maxGpsInput(
                    segments = (i["segments"] as? JsonArray).orEmpty().map {
                        val s = it as JsonObject
                        SteadySegment(s.double("speed_mps")!!, s.double("grade")!!, s.double("hr")!!, s.double("duration_s")!!)
                    },
                    rhr = i.double("rhr"), hrMax = i.double("hr_max"), sport = i.str("sport")
                ), cfg
            ).toJson()
            "cooper" -> CardioFitness.cooper(CooperInput(i.double("distance_m")), cfg).toJson()
            "workout_windows" -> {
                val hr = i.objOrNull("hr")!!
                HeartRateWorkout.workoutWindows(
                    WorkoutWindowsInput(
                        HrMinuteSeries(hr.long("start_ms")!!, (hr["values"] as JsonArray).map(::num)), i.double("rhr"), i.double("hr_max")!!
                    ), cfg
                ).toJson()
            }
            else -> error("unknown function $function")
        }
    }
}
