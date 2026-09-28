package com.ayuvo.health.nutrients

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared nutrients vector file (docs/nutrients.md §4), plus coverage and asset parity. */
class NutrientsVectorTests {
    @Test fun referenceLines() = NutrientsVectors.assertAll("reference_lines.json")
    @Test fun defaultGoal() = NutrientsVectors.assertAll("default_goal.json")
    @Test fun iuConversion() = NutrientsVectors.assertAll("iu_conversion.json")
    @Test fun supplementEntries() = NutrientsVectors.assertAll("supplement_entries.json")
    @Test fun dayTotals() = NutrientsVectors.assertAll("day_totals.json")
    @Test fun loggedDayAverage() = NutrientsVectors.assertAll("logged_day_average.json")
    @Test fun labelOutput() = NutrientsVectors.assertAll("label_output.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = NutrientsTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(NutrientsVectors.FILES.keys, files)
        for ((file, function) in NutrientsVectors.FILES) {
            val root = MedicationJson.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-nutrients-vectors", root.str("format"))
            assertEquals(file, function, root.str("function"))
        }
        val runnerMethods = NutrientsVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage, parity and prompts", NutrientsVectors.FILES.size + 3, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        for (name in listOf("nutrient_reference.json", "ai_supplement_label.md")) {
            assertArrayEquals(name, NutrientsTestFiles.shared(name)!!.readBytes(), NutrientsTestFiles.asset(name)!!.readBytes())
        }
    }

    /** `load_prompts`: the four fenced blocks, verbatim, placeholders filled by plain replacement. */
    @Test
    fun promptsParseAndFill() {
        val p = NutrientLabel.parsePrompts(NutrientsTestFiles.shared("ai_supplement_label.md")!!.readText())
        assertEquals(true, p.cloud.startsWith("You read dietary supplement labels"))
        assertEquals(true, p.local.startsWith("Read a supplement label."))
        assertEquals(false, p.cloud.endsWith("\n"))
        val filled = p.fill(p.userText, "  Vitamin D3 ", "60,000 IU", "capsule")
        assertEquals(true, filled.contains("Name: Vitamin D3\n"))
        assertEquals(true, filled.contains("Strength: 60,000 IU"))
        assertEquals(true, filled.contains("dose unit is capsule."))
        assertEquals(false, filled.contains("{"))
        val prompt = SupplementLabelAi.prompt(p, local = true, photo = true, name = "D3", strength = "", doseUnit = "capsule")
        assertEquals(true, prompt.startsWith(p.local))
        assertEquals(600, SupplementLabelAi.maxTokens(true))
        assertEquals(100, SupplementLabelAi.maxTokens(true, contextTokens = 400))
        // Prompt v2: both key lists are SUPPLEMENT_KEYS (every reference nutrient, then sports).
        val keys = NutrientsTestFiles.reference.supplementKeys.joinToString(", ")
        assertEquals(true, p.local.contains("Keys: $keys\n"))
        assertEquals(true, p.cloud.contains("\n$keys\n"))
        assertEquals(800, SupplementLabelAi.maxTokens(false))
    }
}
