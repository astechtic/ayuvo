package com.ayuvo.health.services.ai

import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.strings
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.models.AIProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileMigrationVectorsTest { @Test fun allCases() = AiVectors.assertAll("profile_migration.json") }
class AdoptLegacyPrimaryVectorsTest { @Test fun allCases() = AiVectors.assertAll("adopt_legacy_primary.json") }
class LegacyProjectionVectorsTest { @Test fun allCases() = AiVectors.assertAll("legacy_projection.json") }
class RoleAssignmentVectorsTest { @Test fun allCases() = AiVectors.assertAll("role_assignment.json") }
class ProfileAddVectorsTest { @Test fun allCases() = AiVectors.assertAll("profile_add.json") }
class RoleResolutionVectorsTest { @Test fun allCases() = AiVectors.assertAll("role_resolution.json") }
class FallbackResolutionVectorsTest { @Test fun allCases() = AiVectors.assertAll("fallback_resolution.json") }
class CredentialLookupVectorsTest { @Test fun allCases() = AiVectors.assertAll("credential_lookup.json") }
class ProfileDeleteVectorsTest { @Test fun allCases() = AiVectors.assertAll("profile_delete.json") }
class VertexEndpointVectorsTest { @Test fun allCases() = AiVectors.assertAll("vertex_endpoint.json") }
class ConversationOverrideVectorsTest { @Test fun allCases() = AiVectors.assertAll("conversation_override.json") }
class TokenLimitVectorsTest { @Test fun allCases() = AiVectors.assertAll("token_limit.json") }
class LocalCatalogVectorsTest { @Test fun allCases() = AiVectors.assertAll("local_catalog.json") }

val AI_VECTOR_FILES_WITH_RUNNERS = listOf(
    "profile_migration.json", "adopt_legacy_primary.json", "legacy_projection.json",
    "role_assignment.json", "profile_add.json",
    "role_resolution.json", "fallback_resolution.json", "credential_lookup.json",
    "profile_delete.json", "vertex_endpoint.json", "conversation_override.json",
    "token_limit.json", "local_catalog.json",
)

class AiVectorCoverageTest {
    @Test
    fun everyVectorFileIsRun() {
        val dir = AiTestFiles.shared("ai/test-vectors")
        assertNotNull("shared/ai/test-vectors missing", dir)
        val files = dir!!.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty()
            .map { it.name }.sorted()
        assertEquals(AI_VECTOR_FILES_WITH_RUNNERS.sorted(), files)
    }
}

/** The bundled catalogues must be byte-identical to the shared ones. */
class AICatalogParityTest {
    @Test
    fun providersAreBundledVerbatim() {
        assertEquals(
            AiTestFiles.shared("ai/providers.json")!!.readText(),
            AiTestFiles.asset("ai/providers.json").readText(),
        )
    }

    @Test
    fun vertexRoutingIsBundledVerbatim() {
        assertEquals(
            AiTestFiles.shared("ai/vertex.json")!!.readText(),
            AiTestFiles.asset("ai/vertex.json").readText(),
        )
    }

    @Test
    fun modelCatalogIsBundledVerbatim() {
        assertEquals(
            AiTestFiles.repo("local-models/catalog.v2.json")!!.readText(),
            AiTestFiles.asset("ai/models_catalog.json").readText(),
        )
    }
}

/**
 * `shared/ai/providers.json` is the file iOS is checked against too. If it drifts from the Kotlin
 * enum, the two apps quietly disagree about what a saved profile means.
 */
/**
 * The persisted provider token, derived from the enum's own `@SerialName` rather than re-typed, so
 * this test cannot pass against a copy that has drifted from the serializer.
 */
private fun AIProvider.serialName(): String =
    MedicationJson.json.encodeToString(AIProvider.serializer(), this).trim('"')

class AIProviderRegistryParityTest {
    private fun rows(): List<JsonObject> {
        AiTestFiles.installCatalogs()
        return (AICatalogs.providers["providers"] as JsonArray).filterIsInstance<JsonObject>()
    }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    @Test
    fun everyAndroidProviderIsInTheRegistry() {
        val tokens = rows().mapNotNull { it.str("id") }.toSet()
        for (provider in AIProvider.entries) {
            assertTrue("${provider.name} is missing from shared/ai/providers.json",
                       provider.serialName() in tokens)
        }
    }

    @Test
    fun theRegistryMatchesTheEnum() {
        for (row in rows()) {
            val token = row.str("id")!!
            if ("android" !in strings(row["platforms"])) continue
            val provider = AIProvider.entries.first { it.serialName() == token }
            assertEquals("$token base URL", provider.baseUrl, row.str("base_url"))
            assertEquals("$token requiresApiKey", provider.requiresApiKey, row.bool("requires_key"))
            assertEquals("$token vision", provider.supportsVision, row.bool("supports_vision"))
            assertEquals("$token custom endpoint", provider.requiresCustomEndpoint,
                         row.bool("requires_custom_endpoint"))
            assertEquals("$token requires custom model", provider.requiresCustomModelName,
                         row.bool("requires_custom_model_name"))
            assertEquals("$token supports custom model", provider.supportsCustomModelName,
                         row.bool("supports_custom_model_name"))
            assertEquals("$token timeout", provider.usesConfigurableRequestTimeout,
                         row.bool("configurable_timeout"))
            // The on-device row's lineup is the set of INSTALLED models, so it is resolved at
            // runtime and deliberately not compared here.
            if (row["models_from"] == null) {
                assertEquals("$token model lineup", provider.models, strings(row["models"]))
                assertEquals("$token text lineup", provider.textModels, strings(row["text_models"]))
            }
        }
    }

    /**
     * The override column packs the token after a `|`; a token containing one would make an
     * imported conversation unparseable on the device that reads it.
     */
    @Test
    fun noProviderTokenCanBreakTheOverrideEncoding() {
        AiTestFiles.installCatalogs()
        for (provider in AIProvider.entries) {
            val token = provider.serialName()
            assertFalse(token.contains(AIReference.OVERRIDE_SEPARATOR))
            val raw = AIReference.encodeOverride("aip_0001", token)!!
            assertEquals(token, AIReference.parseOverride(raw).str("provider"))
            assertEquals("aip_0001", AIReference.parseOverride(raw).str("profile_id"))
        }
    }

    /** Today's `tokenLimitParameter` and the reference must not drift apart. */
    @Test
    fun theTokenLimitRuleMatchesTheClient() {
        AiTestFiles.installCatalogs()
        val models = listOf("gpt-5.4-mini", "gpt-4o-mini", "o3-mini", "vendor/o1-preview",
                            "llama-3.3-70b", "claude-sonnet-5")
        for (provider in AIProvider.entries) {
            for (model in models) {
                assertEquals(
                    "${provider.name}/$model",
                    OpenAICompatibleClient.tokenLimitParameter(provider, model),
                    AIReference.tokenLimitKey(provider.serialName(), model),
                )
            }
        }
    }

    /** The gate the shipped Gemma artifact already passes must not move when models are added. */
    @Test
    fun theMemoryGateDoesNotMoveForTheModelAlreadyInstalled() {
        AiTestFiles.installCatalogs()
        val models = (AICatalogs.models["models"] as JsonArray).filterIsInstance<JsonObject>()
        val gemma = models.first { it.str("id") == "gemma-4-e2b-it-litertlm" }
        val size = gemma.objOrNull("artifact")!!.double("sizeBytes")!!.toLong()
        assertEquals(
            8L * AIReference.GIB,
            gemma.objOrNull("memoryPolicy")!!.double("minimumPhysicalMemoryBytes")!!.toLong(),
        )
        assertEquals(8L * AIReference.GIB, AIReference.memoryGate(size))
    }
}
