package com.ayuvo.health.services.ai

import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.services.ondevice.InstalledLocalModels
import com.ayuvo.health.services.ondevice.LocalModelCatalog
import com.ayuvo.health.services.ondevice.LocalModelId
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Turns the saved profiles into "which provider, which model, which key" (docs/ai-models.md 5).
 *
 * The decision itself lives in [AIReference.resolveRole] / [AIReference.resolveFallback], which iOS
 * runs line for line. This class only gathers the facts the pure function may not look up for
 * itself: which providers hold a key, which local models are installed.
 */
class AIRoleResolver(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    /** Whether a given on-device chat model is installed and executable right now. */
    private val isModelInstalled: (LocalModelId) -> Boolean = { false },
) {

    /** One resolved request target. */
    data class Route(
        val role: String,
        val profileId: String?,
        val provider: AIProvider,
        val model: String,
        val baseUrl: String,
        val apiKey: String?,
        val vertexProjectId: String?,
        val vertexLocation: String?,
        val requestTimeoutSeconds: Int?,
        val maxResponseTokens: Int,
        val tokenLimitKey: String,
        val contextTokens: Int?,
        /**
         * Why this route must not be sent, or null. A chosen profile is never swapped for another
         * one, so this is how the caller learns it cannot answer (docs/ai-models.md rule 1).
         */
        val blocked: String?,
    ) {
        val isUsable: Boolean get() = blocked == null
    }

    /**
     * The installed on-device models, in the shape [AIReference] expects.
     *
     * Keyed by both the id a profile carries today (`AIProvider.LOCAL_GEMMA.models`) and the
     * catalogue id, so a profile written before or after the multi-model catalogue lands resolves
     * the same way.
     */
    private fun localModelsJson(): JsonObject {
        val rows = LinkedHashMap<String, JsonObject>()
        for (descriptor in LocalModelCatalog.chatModels) {
            val installed = isModelInstalled(descriptor.id)
            val entry = MedicationJson.obj(
                "installed" to installed,
                // Per model: Qwen3 has no vision tower, so it can serve text and never images.
                "vision" to descriptor.supportsVision,
                "context_tokens" to descriptor.contextTokens,
            )
            rows[descriptor.catalogId] = entry
            // A profile written before the catalogue carries the bare artifact id.
            if (descriptor.id == LocalModelId.GEMMA_4_E2B) {
                rows[InstalledLocalModels.LEGACY_GEMMA_MODEL_ID] = entry
            }
        }
        return MedicationJson.obj(*rows.map { it.key to it.value }.toTypedArray())
    }

    private suspend fun env(requiresVision: Boolean, profiles: JsonArray): JsonObject {
        val ids = profiles.filterIsInstance<JsonObject>().mapNotNull { it.str("id") }
        return MedicationJson.obj(
            "platform" to AIReference.PLATFORM,
            "requires_vision" to requiresVision,
            "providers_with_keys" to keyStore.providersWithKeys().map { it.token },
            "profiles_with_keys" to keyStore.profileIdsWithOwnKey(ids),
            "local_models" to localModelsJson(),
            // Android has no Apple Intelligence; the row is iOS-only in shared/ai/providers.json.
            "on_device_available" to false,
            "max_response_tokens" to prefs.maxResponseTokens.first(),
            "request_timeout_seconds" to prefs.aiRequestTimeoutSeconds.first(),
        )
    }

    private fun route(rj: JsonObject?, profiles: JsonArray, fallbackRole: String): Route? {
        if (rj == null) return null
        val provider = AIProvider.fromToken(rj.str("provider")) ?: return null
        val profileId = rj.str("profile_id")
        val profile = profiles.filterIsInstance<JsonObject>().firstOrNull { it.str("id") == profileId }
        return Route(
            role = rj.str("role") ?: fallbackRole,
            profileId = profileId,
            provider = provider,
            model = rj.str("model").orEmpty(),
            baseUrl = rj.str("base_url").orEmpty(),
            apiKey = apiKeyFor(profile, provider),
            vertexProjectId = rj.objOrNull("vertex")?.str("project_id"),
            vertexLocation = rj.objOrNull("vertex")?.str("location"),
            requestTimeoutSeconds = rj.double("request_timeout_seconds")?.toInt(),
            maxResponseTokens = rj.double("max_response_tokens")?.toInt() ?: 1024,
            tokenLimitKey = rj.str("token_limit_key") ?: "max_tokens",
            contextTokens = rj.double("context_tokens")?.toInt(),
            blocked = rj.str("blocked"),
        )
    }

    /** The lookup rule of docs/ai-models.md 4, including the profile -> provider fall-through. */
    fun apiKeyFor(profile: JsonObject?, provider: AIProvider): String? {
        val profileId = profile?.str("id")
        val source = AIReference.resolveCredential(
            profile?.str("credential_ref") ?: "provider:${provider.token}",
            provider.requiresApiKey,
            profileId != null && !keyStore.profileApiKey(profileId).isNullOrEmpty(),
            !keyStore.apiKey(provider).isNullOrEmpty(),
        )
        return when (source) {
            "profile" -> profileId?.let(keyStore::profileApiKey)
            "provider" -> keyStore.apiKey(provider)
            else -> null
        }
    }

    /**
     * The primary route for a request. Always returns something: a blocked route still names the
     * profile the user chose, which is what a refusal has to say.
     *
     * `pinnedProfileId` is a conversation's own model choice (docs/ai-models.md 8). It is resolved
     * through the same guards as a role, so a pinned model that cannot answer refuses rather than
     * being swapped for a different one.
     */
    suspend fun resolve(
        requiresVision: Boolean,
        role: String? = null,
        pinnedProfileId: String? = null,
    ): Route? {
        val profiles = prefs.aiProfilesSnapshot()
        val requestedRole = role ?: if (requiresVision) "image" else "text"
        var roles = prefs.aiRolesSnapshot()
        if (pinnedProfileId != null) {
            val pointers = LinkedHashMap<String, JsonObject>()
            for (name in AIReference.ROLES) {
                pointers[name] = if (name == requestedRole) {
                    MedicationJson.obj("profile_id" to pinnedProfileId, "enabled" to true)
                } else {
                    (roles[name] as? JsonObject) ?: JsonObject(emptyMap())
                }
            }
            roles = MedicationJson.obj(*pointers.map { it.key to it.value }.toTypedArray())
        }
        val resolved = AIReference.resolveRole(
            MedicationJson.obj(
                "profiles" to profiles,
                "roles" to roles,
                "env" to env(requiresVision, profiles),
                "role" to requestedRole,
            ),
        )
        return route(resolved, profiles, requestedRole)
    }

    companion object {
        /** What to tell the user when a pinned model cannot answer. Names it, never substitutes. */
        fun refusal(route: Route, reason: String): String {
            val name = route.model.ifEmpty { route.provider.token }
            return when (reason) {
                "no_key" ->
                    "$name has no API key. Add one in Settings, or pick another model for this chat."
                "model_not_installed" ->
                    "$name is not installed on this device. Download it in Settings, or pick another model for this chat."
                "no_vision" ->
                    "$name cannot read images. Pick another model for this chat, or send the message without the photo."
                "no_base_url" ->
                    "$name has no server URL. Add one in Settings, or pick another model for this chat."
                else -> "$name is not available. Pick another model for this chat."
            }
        }
    }

    /** The one retry, or null. Never a second attempt at the endpoint that just failed. */
    suspend fun fallback(primary: Route, requiresVision: Boolean): Route? {
        val profiles = prefs.aiProfilesSnapshot()
        val resolved = AIReference.resolveFallback(
            MedicationJson.obj(
                "profiles" to profiles,
                "roles" to prefs.aiRolesSnapshot(),
                "env" to env(requiresVision, profiles),
                "primary" to MedicationJson.obj(
                    "provider" to primary.provider.token,
                    "model" to primary.model,
                    "base_url" to primary.baseUrl,
                ),
            ),
        )
        return route(
            resolved.objOrNull("route"),
            profiles,
            if (requiresVision) "image_fallback" else "text_fallback",
        )
    }
}
