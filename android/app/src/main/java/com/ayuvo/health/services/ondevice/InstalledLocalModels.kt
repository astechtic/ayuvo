package com.ayuvo.health.services.ondevice

/**
 * The on-device chat models that are installed right now (docs/ai-models.md §7).
 *
 * `AIProvider.LOCAL_GEMMA.models` used to be a one-element constant. With a catalogue it is the set
 * of installed artifacts, which only the runtime knows, so the app publishes it here and the enum
 * reads it. Empty means "nothing installed yet"; the enum then reports the legacy single id so a
 * build that has not started the manager still behaves as it did.
 */
object InstalledLocalModels {
    /** The id every build knew before the catalogue existed. */
    const val LEGACY_GEMMA_MODEL_ID = "gemma-4-E2B-it"

    @Volatile
    var ids: List<String> = emptyList()

    fun orLegacy(): List<String> = ids.ifEmpty { listOf(LEGACY_GEMMA_MODEL_ID) }
}
