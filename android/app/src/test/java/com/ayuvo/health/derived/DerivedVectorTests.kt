package com.ayuvo.health.derived

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared derived-metrics vector file (docs/derived-metrics.md), plus coverage and asset parity. */
class DerivedVectorTests {
    @Test fun heartDay() = DerivedVectors.assertAll("heart_day.json")
    @Test fun hrMax() = DerivedVectors.assertAll("hr_max.json")
    @Test fun vo2maxUth() = DerivedVectors.assertAll("vo2max_uth.json")
    @Test fun rhrStrain() = DerivedVectors.assertAll("rhr_strain.json")
    @Test fun sleepNights() = DerivedVectors.assertAll("sleep_nights.json")
    @Test fun sleepNight() = DerivedVectors.assertAll("sleep_night.json")
    @Test fun sleepRegularity() = DerivedVectors.assertAll("sleep_regularity.json")
    @Test fun activityDay() = DerivedVectors.assertAll("activity_day.json")
    @Test fun stepStreak() = DerivedVectors.assertAll("step_streak.json")
    @Test fun stride() = DerivedVectors.assertAll("stride.json")
    @Test fun energyDay() = DerivedVectors.assertAll("energy_day.json")
    @Test fun gaitWeek() = DerivedVectors.assertAll("gait_week.json")
    @Test fun audioDay() = DerivedVectors.assertAll("audio_day.json")
    @Test fun bodyTrend() = DerivedVectors.assertAll("body_trend.json")
    @Test fun heightConflict() = DerivedVectors.assertAll("height_conflict.json")
    @Test fun priority() = DerivedVectors.assertAll("priority.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = DerivedTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(DerivedVectors.FILES.keys, files)
        for ((file, function) in DerivedVectors.FILES) {
            val root = MedicationJson.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-derived-vectors", root.str("format"))
            assertEquals(file, function, root.str("function"))
        }
        val runnerMethods = DerivedVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", DerivedVectors.FILES.size + 2, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        val name = "derived_config.json"
        assertArrayEquals(name, DerivedTestFiles.shared(name)!!.readBytes(), DerivedTestFiles.asset(name)!!.readBytes())
    }
}
