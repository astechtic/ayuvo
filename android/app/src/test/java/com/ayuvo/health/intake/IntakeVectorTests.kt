package com.ayuvo.health.intake

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared intake vector file (docs/intake-metrics.md), plus coverage and asset parity. */
class IntakeVectorTests {
    @Test fun nutritionDay() = IntakeVectors.assertAll("nutrition_day.json")
    @Test fun driGoals() = IntakeVectors.assertAll("dri_goals.json")
    @Test fun nutrientCoverage() = IntakeVectors.assertAll("nutrient_coverage.json")
    @Test fun supplementDaily() = IntakeVectors.assertAll("supplement_daily.json")
    @Test fun medsAdherence() = IntakeVectors.assertAll("meds_adherence.json")
    @Test fun strengthWeek() = IntakeVectors.assertAll("strength_week.json")
    @Test fun energyBalance() = IntakeVectors.assertAll("energy_balance.json")
    @Test fun pairedDifference() = IntakeVectors.assertAll("paired_difference.json")
    @Test fun labNutrientLinks() = IntakeVectors.assertAll("lab_nutrient_links.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = IntakeTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(IntakeVectors.FILES.keys, files)
        for ((file, function) in IntakeVectors.FILES) {
            val root = MedicationJson.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-intake-vectors", root.str("format"))
            assertEquals(file, function, root.str("function"))
        }
        val runnerMethods = IntakeVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", IntakeVectors.FILES.size + 2, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        val name = "intake_config.json"
        assertArrayEquals(name, IntakeTestFiles.shared(name)!!.readBytes(), IntakeTestFiles.asset(name)!!.readBytes())
    }
}
