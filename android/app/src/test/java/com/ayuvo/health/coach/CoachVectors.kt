package com.ayuvo.health.coach

import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates shared Coach contract files from the Gradle unit-test working directory (android/app). */
object CoachTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/$relative", "../shared/$relative", "shared/$relative")
            .map(::File).firstOrNull { it.exists() }

    val gallery: JsonObject by lazy {
        MedicationJson.json.parseToJsonElement(shared("coach/prompt_gallery.json")!!.readText()) as JsonObject
    }

    val chartSpec: JsonObject by lazy {
        MedicationJson.json.parseToJsonElement(shared("coach/chart_spec.json")!!.readText()) as JsonObject
    }
}

/** Runs `shared/coach/test-vectors/<file>` through [CoachReference] (dispatch mirrors `run_case`). */
object CoachVectors {
    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = CoachTestFiles.shared("coach/test-vectors/$file")
            ?: run { fail("shared/coach/test-vectors/$file missing"); error("") }
        val root = MedicationJson.json.parseToJsonElement(f.readText()) as JsonObject
        assertTrue("$file: bad format", root.str("format") == "ayuvo-coach-vectors")
        val function = root.str("function") ?: error("$file has no function")
        val cases = root["cases"] as JsonArray
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = case.str("name") ?: "?"
            val expected = case["expected"]!!
            val actual = try {
                runCase(function, case["input"] as JsonObject)
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
        println("VECTORS coach/${o.file}: ${o.passed}/${o.total}")
        assertTrue(
            "coach/${o.file} ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"),
            o.failures.isEmpty() && o.total > 0
        )
    }

    private fun strings(e: JsonElement?): List<String> = MedicationJson.strings(e)

    private fun objects(e: JsonElement?): List<JsonObject> =
        (e as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    fun runCase(function: String, input: JsonObject): JsonElement = when (function) {
        "parse_blocks" -> CoachReference.parseBlocks(input.str("markdown") ?: "")
        "parse_chart_spec" -> CoachReference.parseChartSpec(input.str("raw") ?: "")
        "repair_chart_json" ->
            MedicationJson.obj("text" to CoachReference.repairChartJson(input.str("raw") ?: ""))
        "attachment_excerpt" -> CoachReference.attachmentExcerpt(
            strings(input["pages"]),
            (input["max_pages"] as? JsonPrimitive)?.content?.toIntOrNull() ?: CoachReference.MAX_ATTACHMENT_PAGES,
            (input["max_chars"] as? JsonPrimitive)?.content?.toIntOrNull() ?: CoachReference.MAX_ATTACHMENT_CHARS
        )
        "conversation_title" -> CoachReference.conversationTitle(input.str("text") ?: "")
        "resolve_data_sources" -> CoachReference.resolveDataSources(
            input.objOrNull("available"), input.objOrNull("consents"), input.objOrNull("switches"),
            (input["workouts_available"] as? JsonPrimitive)?.booleanOrNull ?: false
        )
        "gallery_for" -> CoachReference.galleryFor(
            input.objOrNull("sources"), input.objOrNull("catalog") ?: CoachTestFiles.gallery
        )
        "chips_for" -> CoachReference.chipsFor(
            input.str("goal"),
            (input["has_workouts"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (input["has_sleep"] as? JsonPrimitive)?.booleanOrNull ?: false,
            input.objOrNull("sources"), input.objOrNull("catalog") ?: CoachTestFiles.gallery
        )
        "chat_archive" ->
            if (input.containsKey("archive")) {
                CoachReference.mergeChatArchive(input.objOrNull("snapshot"), input.objOrNull("archive"))
            } else {
                CoachReference.chatArchive(input.objOrNull("snapshot"))
            }
        "export" -> when (val op = input.str("op")) {
            "markdown" -> CoachReference.conversationMarkdown(
                input.objOrNull("conversation"), objects(input["messages"]), objects(input["attachments"]),
                input.str("local_day").orEmpty(), input.str("provider")
            )
            "json" -> CoachReference.conversationJson(
                input.objOrNull("conversation"), objects(input["messages"]), objects(input["attachments"]),
                input.str("local_day").orEmpty()
            )
            "regenerate" -> CoachReference.regeneratePlan(
                objects(input["messages"]),
                (input["seq"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            )
            "slug" -> MedicationJson.obj("slug" to CoachReference.exportSlug(input.str("title")))
            else -> error("unknown export op $op")
        }
        else -> error("unknown function $function")
    }
}
