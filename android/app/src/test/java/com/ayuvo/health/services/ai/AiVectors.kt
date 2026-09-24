package com.ayuvo.health.services.ai

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates shared AI contract files from the Gradle unit-test working directory (android/app). */
object AiTestFiles {
    fun repo(relative: String): File? =
        listOf("../../$relative", "../$relative", relative).map(::File).firstOrNull { it.exists() }

    fun shared(relative: String): File? = repo("shared/$relative")

    fun asset(relative: String): File =
        listOf("src/main/assets/$relative", "app/src/main/assets/$relative")
            .map(::File).first { it.exists() }

    /**
     * App startup loads these from `assets/ai/`; the tests inject the shared files so a vector run
     * can never pass against a stale bundled copy (the parity test covers the copies themselves).
     */
    fun installCatalogs() {
        AICatalogs.providers =
            AICatalogs.parse(shared("ai/providers.json")!!.readText()) ?: JsonObject(emptyMap())
        AICatalogs.vertex =
            AICatalogs.parse(shared("ai/vertex.json")!!.readText()) ?: JsonObject(emptyMap())
        AICatalogs.models =
            AICatalogs.parse(repo("local-models/catalog.v2.json")!!.readText())
                ?: JsonObject(emptyMap())
    }
}

/** Runs `shared/ai/test-vectors/<file>` through [AIReference] (dispatch mirrors `run_case`). */
object AiVectors {
    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        AiTestFiles.installCatalogs()
        val f = AiTestFiles.shared("ai/test-vectors/$file")
            ?: run { fail("shared/ai/test-vectors/$file missing"); error("") }
        val root = MedicationJson.json.parseToJsonElement(f.readText()) as JsonObject
        assertTrue("$file: bad format", root.str("format") == "ayuvo-ai-vectors")
        val function = root.str("function") ?: error("$file has no function")
        val cases = root["cases"] as JsonArray
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = case.str("name") ?: "?"
            val expected = case["expected"]!!
            val actual = try {
                AIReference.runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }
            val diff = RecordsVectors.diff(expected, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS ai/${o.file}: ${o.passed}/${o.total}")
        assertTrue(
            "ai/${o.file} ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"),
            o.failures.isEmpty() && o.total > 0,
        )
    }
}
