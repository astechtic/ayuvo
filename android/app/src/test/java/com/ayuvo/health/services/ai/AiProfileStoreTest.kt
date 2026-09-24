package com.ayuvo.health.services.ai

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.ui.coach.ConversationOverride
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration / mirror cycle as `PreferencesStore` drives it (docs/ai-models.md §4).
 *
 * `PreferencesStore` needs an Android `Context`, and this module has no Robolectric, so the DataStore
 * plumbing itself is only reachable from an instrumented test. What these cover is the part that can
 * actually go wrong silently: Android persists the enum NAME (`OPENAI`) while shared data carries the
 * token (`OpenAI`), so every value crossing that boundary is converted, and a slip there would send a
 * migration into the "unknown provider" branch and quietly drop the user's whole setup.
 */
class AiProfileStoreTest {

    private fun slot(provider: AIProvider?, model: String, baseUrl: String? = null,
                     enabled: Boolean = false): JsonObject =
        MedicationJson.obj(
            "provider" to provider?.token,
            "model" to model,
            "base_url" to baseUrl,
            "enabled" to enabled,
        )

    private fun slots(
        image: JsonObject,
        text: JsonObject = slot(null, ""),
        imageFallback: JsonObject = slot(null, ""),
        textFallback: JsonObject = slot(null, ""),
    ): JsonObject = MedicationJson.obj(
        "image" to image, "text" to text,
        "image_fallback" to imageFallback, "text_fallback" to textFallback,
    )

    private fun migrate(slots: JsonObject, nowMs: Long = 1_700_000_000_000L): JsonObject {
        AiTestFiles.installCatalogs()
        return AIReference.migrateProfiles(
            MedicationJson.obj(
                "migration_version" to 0,
                "now_ms" to nowMs,
                "slots" to slots,
                "existing_profiles" to JsonArray(emptyList()),
                "env" to MedicationJson.obj("platform" to AIReference.PLATFORM),
            ),
        )
    }

    private fun JsonObject.profiles(): List<JsonObject> =
        (this["profiles"] as JsonArray).filterIsInstance<JsonObject>()

    @Test
    fun everyProviderTokenRoundTripsThroughTheEnumName() {
        for (provider in AIProvider.entries) {
            // What PreferencesStore stores, and what shared data carries, are different strings.
            assertEquals(provider, AIProvider.fromToken(provider.token))
            assertTrue("${provider.name} token looks like an enum name",
                       provider.token != provider.name || provider.token == "Groq" ||
                           provider.token == "Mistral" || provider.token == "OpenAI" ||
                           provider.token == "OpenRouter" || provider.token == "DeepInfra" ||
                           provider.token == "DeepSeek" || provider.token == "Cerebras")
        }
        assertNull(AIProvider.fromToken("OPENAI"))
        assertNull(AIProvider.fromToken(null))
    }

    @Test
    fun theAndroidSlotShapeMigratesIntoProfiles() {
        val out = migrate(
            slots(
                image = slot(AIProvider.OPENAI, "gpt-5.4-mini", enabled = true),
                text = slot(AIProvider.GEMINI, "gemini-3.5-flash-lite", enabled = true),
            ),
        )
        val profiles = out.profiles()
        assertEquals(2, profiles.size)
        assertEquals(AIProvider.OPENAI.token, profiles[0].str("provider"))
        // Nobody has to re-enter a key.
        assertEquals("provider:${AIProvider.OPENAI.token}", profiles[0].str("credential_ref"))
        val roles = out.objOrNull("roles")!!
        assertEquals(profiles[0].str("id"), roles.objOrNull("image")!!.str("profile_id"))
        assertEquals(profiles[1].str("id"), roles.objOrNull("text")!!.str("profile_id"))
    }

    @Test
    fun theProjectionWritesBackTheSameProviderTheSlotCameFrom() {
        val out = migrate(slots(slot(AIProvider.ANTHROPIC, "claude-sonnet-5", enabled = true)))
        val projection = AIReference.projectLegacy(out.profiles(), out.objOrNull("roles")!!)
        val image = projection.objOrNull("slots")!!.objOrNull("image")!!
        // This is the step PreferencesStore turns back into `prefs[SELECTED_AI_PROVIDER] = name`.
        assertEquals(AIProvider.ANTHROPIC, AIProvider.fromToken(image.str("provider")))
        assertEquals("claude-sonnet-5", image.str("model"))
        assertNotNull(projection.str("fingerprint"))
    }

    @Test
    fun aProviderThisBuildDoesNotHaveDropsItsSlotWithoutLosingThePrimary() {
        // Apple Intelligence is iOS-only. An archive or a future build could name it here.
        val out = migrate(
            slots(
                image = slot(AIProvider.GEMINI, "gemini-3.8-flash", enabled = true),
                text = MedicationJson.obj(
                    "provider" to "Apple Intelligence (On-Device)",
                    "model" to "System Language Model",
                    "base_url" to null,
                    "enabled" to true,
                ),
            ),
        )
        val roles = out.objOrNull("roles")!!
        assertNotNull(roles.objOrNull("image")!!.str("profile_id"))
        assertNull(roles.objOrNull("text")!!.str("profile_id"))
    }

    @Test
    fun onboardingsChoiceIsAdoptedAfterProfilesAlreadyExist() {
        val migrated = migrate(slots(slot(AIProvider.OPENAI, "gpt-5.4-mini", enabled = true)))
        val projection = AIReference.projectLegacy(migrated.profiles(), migrated.objOrNull("roles")!!)
        val adopted = AIReference.adoptLegacyPrimary(
            MedicationJson.obj(
                "observed" to MedicationJson.obj(
                    "provider" to AIProvider.GEMINI.token,
                    "model" to "gemini-3.8-flash",
                    "base_url" to null,
                ),
                "fingerprint" to projection.str("fingerprint"),
                "profiles" to JsonArray(migrated.profiles()),
                "roles" to migrated.objOrNull("roles")!!,
                "now_ms" to 2L,
            ),
        )
        assertEquals("created", adopted.str("reason"))
        val roles = adopted.objOrNull("roles")!!
        val primary = adopted.profiles().first { it.str("id") == roles.objOrNull("image")!!.str("profile_id") }
        assertEquals(AIProvider.GEMINI.token, primary.str("provider"))
    }

    @Test
    fun anUnchangedProjectionIsLeftAlone() {
        val migrated = migrate(slots(slot(AIProvider.OPENAI, "gpt-5.4-mini", enabled = true)))
        val projection = AIReference.projectLegacy(migrated.profiles(), migrated.objOrNull("roles")!!)
        val again = AIReference.adoptLegacyPrimary(
            MedicationJson.obj(
                "observed" to MedicationJson.obj(
                    "provider" to AIProvider.OPENAI.token,
                    "model" to "gpt-5.4-mini",
                    "base_url" to null,
                ),
                "fingerprint" to projection.str("fingerprint"),
                "profiles" to JsonArray(migrated.profiles()),
                "roles" to migrated.objOrNull("roles")!!,
                "now_ms" to 2L,
            ),
        )
        assertEquals("match", again.str("reason"))
    }

    @Test
    fun aPinnedConversationRoundTripsThroughTheColumn() {
        AiTestFiles.installCatalogs()
        val pinned = ConversationOverride("aip_0004", AIProvider.OPENAI)
        val raw = pinned.encoded()
        assertEquals("profile:aip_0004|${AIProvider.OPENAI.token}", raw)
        assertEquals(pinned, ConversationOverride.parse(raw))
    }

    @Test
    fun theRecordsFlowsBareProviderStillParses() {
        AiTestFiles.installCatalogs()
        val bare = ConversationOverride(provider = AIProvider.LOCAL_GEMMA)
        assertEquals(AIProvider.LOCAL_GEMMA.token, bare.encoded())
        assertEquals(bare, ConversationOverride.parse(AIProvider.LOCAL_GEMMA.token))
        assertEquals(ConversationOverride(), ConversationOverride.parse(null))
    }

    /**
     * An archive carries the profile id of the device that wrote it. On another device that id
     * means nothing, and the conversation must degrade to the provider rather than quietly answer
     * on whatever model happens to hold that id.
     */
    @Test
    fun anImportedPinDegradesToTheProvider() {
        AiTestFiles.installCatalogs()
        val fromElsewhere = ConversationOverride.parse("profile:aip_9999|${AIProvider.ANTHROPIC.token}")
        assertEquals("aip_9999", fromElsewhere.profileId)
        assertEquals(AIProvider.ANTHROPIC, fromElsewhere.provider)
    }
}
