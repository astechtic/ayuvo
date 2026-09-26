package com.ayuvo.health.insights

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared insights vector file (docs/insights.md §2), plus coverage and asset parity. */
class InsightsVectorTests {
    @Test fun baseline() = InsightsVectors.assertAll("baseline.json")
    @Test fun trend() = InsightsVectors.assertAll("trend.json")
    @Test fun overnight() = InsightsVectors.assertAll("overnight.json")
    @Test fun trainingLoad() = InsightsVectors.assertAll("training_load.json")
    @Test fun recovery() = InsightsVectors.assertAll("recovery.json")
    @Test fun healthAge() = InsightsVectors.assertAll("health_age.json")
    @Test fun healthAgePace() = InsightsVectors.assertAll("health_age_pace.json")
    @Test fun dailyReview() = InsightsVectors.assertAll("daily_review.json")
    @Test fun patterns() = InsightsVectors.assertAll("patterns.json")
    @Test fun aiSummary() = InsightsVectors.assertAll("ai_summary.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = InsightsTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(InsightsVectors.FILES.keys, files)
        for ((file, function) in InsightsVectors.FILES) {
            val root = MedicationJson.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-insights-vectors", root.str("format"))
            assertEquals(file, function, root.str("function"))
        }
        val runnerMethods = InsightsVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", InsightsVectors.FILES.size + 2, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        for (name in listOf("insights_config.json", "ai_explain.md")) {
            assertArrayEquals(name, InsightsTestFiles.shared(name)!!.readBytes(), InsightsTestFiles.asset(name)!!.readBytes())
        }
    }
}
