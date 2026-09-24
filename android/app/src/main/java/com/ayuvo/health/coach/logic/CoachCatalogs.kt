package com.ayuvo.health.coach.logic

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject

/**
 * The two shared catalogs, bundled verbatim (`assets/coach/`). `CoachCatalogParityTest`
 * byte-compares them with `shared/coach/`, so the prompt the model sees can never drift from the
 * grammar the parser enforces.
 */
object CoachCatalogs {
    /** Set once at startup from `assets/coach/chart_spec.json`; the tests inject the shared file. */
    @Volatile
    var chartSpec: JsonObject = JsonObject(emptyMap())

    /** Set once at startup from `assets/coach/prompt_gallery.json`. */
    @Volatile
    var promptGallery: JsonObject = JsonObject(emptyMap())

    const val CHART_SPEC_ASSET = "coach/chart_spec.json"
    const val PROMPT_GALLERY_ASSET = "coach/prompt_gallery.json"

    fun parse(text: String): JsonObject? =
        runCatching { MedicationJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** The `## Charts` section appended to the system prompt (docs/coach.md §5). */
    fun chartsPromptSection(): String = chartSpec.objOrNull("prompt")?.str("charts_section").orEmpty()

    fun chartGuardrails(): String = chartSpec.objOrNull("prompt")?.str("guardrails").orEmpty()
}
