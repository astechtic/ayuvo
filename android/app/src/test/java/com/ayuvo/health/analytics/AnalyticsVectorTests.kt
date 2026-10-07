package com.ayuvo.health.analytics

import com.ayuvo.health.data.analytics.engine.AnalyticsConfig
import com.ayuvo.health.data.analytics.engine.AnalyticsEngine
import com.ayuvo.health.data.analytics.engine.AnalyticsMath
import com.ayuvo.health.data.analytics.engine.JMap
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Locates the shared analytics contract from the Gradle unit-test working directory (android/app). */
object AnalyticsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/$relative", "../shared/$relative", "shared/$relative").map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/analytics/$relative", "app/src/main/assets/analytics/$relative").map(::File).firstOrNull { it.exists() }

    val config: AnalyticsConfig by lazy {
        AnalyticsConfig.parse(shared("analytics/analytics_config.json")!!.readText(), shared("health/source_policy.json")!!.readText())
    }

    val tolerance: Double by lazy { (config.root["tolerance"] as Number).toDouble() }
}

/**
 * Runs `shared/analytics/test-vectors/<file>` through the Kotlin engine. Numbers must agree with the reference within
 * `tolerance` (absolute, or relative to the larger magnitude); everything else must be identical.
 */
object AnalyticsVectors {
    val FILES: Map<String, String> = AnalyticsEngine.FUNCTIONS.associateBy { "$it.json" }

    fun diff(expected: JsonElement, actual: JsonElement, path: String, tol: Double): String? {
        when (expected) {
            is JsonNull -> return if (actual is JsonNull) null else "$path: expected null, got $actual"
            is JsonObject -> {
                if (actual !is JsonObject) return "$path: expected object, got $actual"
                if (expected.keys != actual.keys) return "$path: keys ${expected.keys} vs ${actual.keys}"
                for (k in expected.keys) diff(expected[k]!!, actual[k]!!, "$path.$k", tol)?.let { return it }
                return null
            }
            is JsonArray -> {
                if (actual !is JsonArray) return "$path: expected array, got $actual"
                if (expected.size != actual.size) return "$path: size ${expected.size} vs ${actual.size}"
                for (i in expected.indices) diff(expected[i], actual[i], "$path[$i]", tol)?.let { return it }
                return null
            }
            is JsonPrimitive -> {
                if (actual !is JsonPrimitive || actual is JsonNull) return "$path: expected $expected, got $actual"
                if (expected.isString || actual.isString) {
                    return if (expected.isString == actual.isString && expected.content == actual.content) null else "$path: expected $expected, got $actual"
                }
                val eb = expected.booleanOrNull
                val ab = actual.booleanOrNull
                if (eb != null || ab != null) return if (eb == ab) null else "$path: expected $expected, got $actual"
                val en = expected.doubleOrNull
                val an = actual.doubleOrNull
                if (en == null || an == null) return "$path: expected $expected, got $actual"
                val d = abs(en - an)
                return if (d <= tol || d <= tol * max(abs(en), abs(an))) null else "$path: expected $en, got $an"
            }
        }
    }

    fun assertAll(file: String) {
        val f = AnalyticsTestFiles.shared("analytics/test-vectors/$file") ?: error("shared/analytics/test-vectors/$file missing")
        val root = MedicationJson.json.parseToJsonElement(f.readText()) as JsonObject
        val function = (root["function"] as JsonPrimitive).content
        val cases = root["cases"] as JsonArray
        val failures = ArrayList<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = (case["name"] as JsonPrimitive).content
            val actual = try {
                @Suppress("UNCHECKED_CAST")
                AnalyticsMath.toJson(AnalyticsEngine.run(function, AnalyticsMath.plain(case["input"]) as JMap, AnalyticsTestFiles.config))
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(4).joinToString("\n")}"
                continue
            }
            val d = diff(case["expected"]!!, actual, "$", AnalyticsTestFiles.tolerance)
            if (d == null) passed++ else failures += "$name: $d"
        }
        println("VECTORS analytics/$file: $passed/${cases.size}")
        assertTrue("$file: $passed/${cases.size} passed\n" + failures.joinToString("\n"), failures.isEmpty() && cases.size > 0)
    }
}

class AnalyticsVectorTests {
    @Test fun sourceSelect() = AnalyticsVectors.assertAll("source_select.json")
    @Test fun unitConvert() = AnalyticsVectors.assertAll("unit_convert.json")
    @Test fun inputHash() = AnalyticsVectors.assertAll("input_hash.json")
    @Test fun robustStats() = AnalyticsVectors.assertAll("robust_stats.json")
    @Test fun baseline() = AnalyticsVectors.assertAll("baseline.json")
    @Test fun baselines() = AnalyticsVectors.assertAll("baselines.json")
    @Test fun trend() = AnalyticsVectors.assertAll("trend.json")
    @Test fun hrvRr() = AnalyticsVectors.assertAll("hrv_rr.json")
    @Test fun hrvRrDay() = AnalyticsVectors.assertAll("hrv_rr_day.json")
    @Test fun hrvStatus() = AnalyticsVectors.assertAll("hrv_status.json")
    @Test fun sleepNeed() = AnalyticsVectors.assertAll("sleep_need.json")
    @Test fun sleepStatus() = AnalyticsVectors.assertAll("sleep_status.json")
    @Test fun load() = AnalyticsVectors.assertAll("load.json")
    @Test fun hrr() = AnalyticsVectors.assertAll("hrr.json")
    @Test fun recovery() = AnalyticsVectors.assertAll("recovery.json")
    @Test fun anomaly() = AnalyticsVectors.assertAll("anomaly.json")
    @Test fun correlation() = AnalyticsVectors.assertAll("correlation.json")
    @Test fun response() = AnalyticsVectors.assertAll("response.json")
    @Test fun energy() = AnalyticsVectors.assertAll("energy.json")
    @Test fun vo2maxTrend() = AnalyticsVectors.assertAll("vo2max_trend.json")
    @Test fun metIntensity() = AnalyticsVectors.assertAll("met_intensity.json")
    @Test fun forecast() = AnalyticsVectors.assertAll("forecast.json")
    @Test fun evidence() = AnalyticsVectors.assertAll("evidence.json")

    /** A new vector file fails here until it has a runner above; each file declares the function it tests. */
    @Test
    fun everyVectorFileHasARunner() {
        val dir = AnalyticsTestFiles.shared("analytics/test-vectors")!!
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name }.toSet()
        assertEquals(AnalyticsVectors.FILES.keys, files)
        for ((file, function) in AnalyticsVectors.FILES) {
            val root = MedicationJson.json.parseToJsonElement(dir.resolve(file).readText()) as JsonObject
            assertEquals(file, "ayuvo-analytics-vectors", (root["format"] as JsonPrimitive).content)
            assertEquals(file, function, (root["function"] as JsonPrimitive).content)
        }
        val runners = AnalyticsVectorTests::class.java.declaredMethods.count { it.getAnnotation(Test::class.java) != null }
        assertEquals("one @Test per vector file plus coverage and parity", AnalyticsVectors.FILES.size + 2, runners)
    }

    @Test
    fun assetsAreByteCopiesOfTheSharedContract() {
        assertArrayEquals(
            AnalyticsTestFiles.shared("analytics/analytics_config.json")!!.readBytes(),
            AnalyticsTestFiles.asset("analytics_config.json")!!.readBytes()
        )
        assertArrayEquals(
            AnalyticsTestFiles.shared("health/source_policy.json")!!.readBytes(),
            AnalyticsTestFiles.asset("source_policy.json")!!.readBytes()
        )
    }
}
