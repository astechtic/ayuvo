package com.ayuvo.health.coach

import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gallery's text (docs/coach.md §9). `shared/coach/prompt_gallery.json` is the source of truth:
 * every id must have a title and a prompt in `values/strings.xml`, saying exactly what the catalog's
 * English says. The twin of `PromptGalleryTextTests.swift`.
 */
class PromptGalleryTextTest {
    private val strings: Map<String, String> by lazy {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.exists() }
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private fun unescape(raw: String): String = raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private val prompts: List<JsonObject>
        get() = (CoachTestFiles.gallery["prompts"] as JsonArray).filterIsInstance<JsonObject>()

    @Test
    fun everyPromptHasItsTextVerbatim() {
        for (entry in prompts) {
            val id = entry.str("id")!!
            assertEquals(
                "coach_prompt_${id}_title",
                entry.str("title_en"),
                strings["coach_prompt_${id}_title"]
            )
            assertEquals(
                "coach_prompt_${id}_text",
                entry.str("prompt_en"),
                strings["coach_prompt_${id}_text"]
            )
        }
        assertTrue(prompts.isNotEmpty())
    }

    @Test
    fun everyCategoryHasATitle() {
        for (category in (CoachTestFiles.gallery["categories"] as JsonArray).mapNotNull { it.str() }) {
            assertNotNull(category, strings["coach_prompt_category_$category"])
        }
    }

    /** The chip ids the shared selection can return must all be real gallery entries. */
    @Test
    fun everyChipIdIsInTheCatalog() {
        val ids = prompts.mapNotNull { it.str("id") }.toSet()
        val chipIds = com.ayuvo.health.coach.logic.CoachReference.CHIP_GOALS.values.flatten() +
            com.ayuvo.health.coach.logic.CoachReference.CHIP_DEFAULT +
            listOf("sleep_week", "training_review")
        for (id in chipIds) assertTrue("$id is not a gallery entry", id in ids)
    }
}

private fun kotlinx.serialization.json.JsonElement.str(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
