package com.ayuvo.health.vitals

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every shared vitals vector file (docs/camera-vitals.md), plus coverage and asset parity. */
class VitalsVectorTests {
    @Test fun analyzeFace() = VitalsVectors.assertAll("analyze_face.json")
    @Test fun analyzeFinger() = VitalsVectors.assertAll("analyze_finger.json")
    @Test fun bandpass() = VitalsVectors.assertAll("bandpass.json")
    @Test fun baselines() = VitalsVectors.assertAll("baselines.json")
    @Test fun bpResearch() = VitalsVectors.assertAll("bp_research.json")
    @Test fun compare() = VitalsVectors.assertAll("compare.json")
    @Test fun effectiveConfig() = VitalsVectors.assertAll("effective_config.json")
    @Test fun fingerDetect() = VitalsVectors.assertAll("finger_detect.json")
    @Test fun hrvFreq() = VitalsVectors.assertAll("hrv_freq.json")
    @Test fun insightsFallback() = VitalsVectors.assertAll("insights_fallback.json")
    @Test fun jacobi() = VitalsVectors.assertAll("jacobi.json")
    @Test fun pulses() = VitalsVectors.assertAll("pulses.json")
    @Test fun respRate() = VitalsVectors.assertAll("resp_rate.json")
    @Test fun scanControl() = VitalsVectors.assertAll("scan_control.json")
    @Test fun scanIndicator() = VitalsVectors.assertAll("scan_indicator.json")
    @Test fun spectrum() = VitalsVectors.assertAll("spectrum.json")
    @Test fun spo2Estimate() = VitalsVectors.assertAll("spo2_estimate.json")
    @Test fun synth() = VitalsVectors.assertAll("synth.json")
    @Test fun validationStats() = VitalsVectors.assertAll("validation_stats.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = VitalsTestFiles.shared("test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(VitalsVectors.FILES.keys, files)
        for ((file, function) in VitalsVectors.FILES) {
            val root = VitalsVectors.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-vitals-vectors", (root["format"] as JsonPrimitive).content)
            assertEquals(file, function, (root["function"] as JsonPrimitive).content)
        }
        val runnerMethods = VitalsVectorTests::class.java.declaredMethods.count { m -> m.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", VitalsVectors.FILES.size + 2, runnerMethods)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        val name = "vitals_config.json"
        assertArrayEquals(name, VitalsTestFiles.shared(name)!!.readBytes(), VitalsTestFiles.asset(name)!!.readBytes())
    }
}
