package com.ayuvo.health.services.ai

import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

/**
 * The three shared catalogues, bundled verbatim (`assets/ai/`). `AICatalogParityTest` byte-compares
 * them with `shared/ai/` and `local-models/`, so what the resolver reasons over can never drift from
 * what the other platform reasons over.
 */
object AICatalogs {
    /** Set once at startup from `assets/ai/providers.json`; the tests inject the shared file. */
    @Volatile
    var providers: JsonObject = JsonObject(emptyMap())

    /** Set once at startup from `assets/ai/vertex.json`. */
    @Volatile
    var vertex: JsonObject = JsonObject(emptyMap())

    /** Set once at startup from `assets/ai/models_catalog.json` (`local-models/catalog.v2.json`). */
    @Volatile
    var models: JsonObject = JsonObject(emptyMap())

    const val PROVIDERS_ASSET = "ai/providers.json"
    const val VERTEX_ASSET = "ai/vertex.json"
    const val MODELS_ASSET = "ai/models_catalog.json"

    fun parse(text: String): JsonObject? =
        runCatching { MedicationJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
