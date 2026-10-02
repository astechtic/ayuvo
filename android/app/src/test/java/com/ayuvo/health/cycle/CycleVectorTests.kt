package com.ayuvo.health.cycle

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared cycle vector file (docs/cycle-tracking.md), plus coverage and asset parity. */
class CycleVectorTests {
    @Test fun applyPeriodDay() = CycleVectors.assertAll("apply_period_day.json")
    @Test fun cycles() = CycleVectors.assertAll("cycles.json")
    @Test fun dayStatus() = CycleVectors.assertAll("day_status.json")
    @Test fun days() = CycleVectors.assertAll("days.json")
    @Test fun normalize() = CycleVectors.assertAll("normalize.json")
    @Test fun settings() = CycleVectors.assertAll("settings.json")
    @Test fun snapshot() = CycleVectors.assertAll("snapshot.json")
    @Test fun trends() = CycleVectors.assertAll("trends.json")
    @Test fun validatePeriod() = CycleVectors.assertAll("validate_period.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = CycleTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(CycleVectors.FILES.keys, files)
        for ((file, function) in CycleVectors.FILES) {
            val root = CycleVectors.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-cycle-vectors", (root["format"] as JsonPrimitive).content)
            assertEquals(file, function, (root["function"] as JsonPrimitive).content)
        }
        val runnerMethods = CycleVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", CycleVectors.FILES.size + 2, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        for (name in listOf("cycle_config.json", "coach.json")) {
            assertArrayEquals(name, CycleTestFiles.shared(name)!!.readBytes(), CycleTestFiles.asset(name)!!.readBytes())
        }
    }
}
