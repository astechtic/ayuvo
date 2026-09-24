package com.ayuvo.health.services.ai

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.ceil
import kotlin.math.max

/**
 * Kotlin port of `scripts/ai_reference.py` (docs/ai-models.md). Every function is pure and is driven
 * by `shared/ai/test-vectors JSON files` in `AiVectorTests`. The Python reference wins over this file
 * and over the prose; keep the two in step in the same change.
 */
object AIReference {

    // -- Constants (docs/ai-models.md 3-8) --------------------------------------------------------

    val ROLES = listOf("image", "text", "image_fallback", "text_fallback")

    /** The primary role each fallback backs up. */
    val FALLBACK_OF = mapOf("image_fallback" to "image", "text_fallback" to "text")

    /**
     * Profile ids are `aip_` + four digits: ASCII, sortable, and identical on both platforms, so the
     * per-profile credential account name is portable in a way the provider token is not.
     */
    const val PROFILE_ID_PREFIX = "aip_"
    const val PROFILE_ID_DIGITS = 4
    private const val PROFILE_ID_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
    const val MAX_PROFILE_ID_LENGTH = 64

    /** Separates the profile id from the provider token inside `conversations.provider_override`. */
    const val OVERRIDE_PREFIX = "profile:"
    const val OVERRIDE_SEPARATOR = '|'

    /** Joins the three parts of the primary-projection fingerprint. NUL can appear in no token. */
    const val FINGERPRINT_SEPARATOR = "\u0000"
    const val MIGRATION_VERSION = 1

    /** Chosen when nothing else is usable, matching today's `executableAIProviderOrDefault`. */
    const val DEFAULT_PROVIDER = "Google Gemini"
    val CREDENTIAL_SOURCES = listOf("none", "profile", "provider", "missing")
    val CATALOG_STATES = listOf("installed", "available", "needs_token", "ineligible")

    /** Providers whose "endpoint" is the device itself: no base URL, no key, no network. */
    val LOCAL_FORMATS = setOf("on_device", "litert_local")
    const val GIB = 1024L * 1024L * 1024L

    /** This build's row filter in `shared/ai/providers.json`. */
    const val PLATFORM = "android"

    // -- Small helpers ----------------------------------------------------------------------------

    private fun trim(value: String?): String = value?.trim() ?: ""

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.child(key: String): JsonObject = objOrNull(key) ?: JsonObject(emptyMap())

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun element(value: JsonElement?): JsonElement = value ?: JsonNull

    /**
     * Whitespace only. Deliberately NOT the platform's `normalizeModelId`: its legacy-id upgrades run
     * BEFORE this reference is called, so folding them in would let a migration depend on which
     * upgrade version the device happened to be on.
     */
    fun normalizeModelId(model: String?): String = trim(model)

    private fun fold(text: String): String =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    /**
     * The provider table the resolver reasons over.
     *
     * Callers may pass their own map. Otherwise the registry is used, narrowed to `env.platform`
     * when one is given: Apple Intelligence is iOS-only, and a build that does not have a provider
     * must not be able to migrate a profile onto it or resolve a route to it.
     */
    fun providerDefaults(env: JsonObject): Map<String, JsonObject> {
        val supplied = env.objOrNull("provider_defaults")
        if (supplied != null && supplied.isNotEmpty()) {
            return supplied.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
        }
        return defaultProviderTable(env.str("platform"))
    }

    private fun defaultProviderTable(platform: String?): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for (row in AICatalogs.providers.objects("providers")) {
            val id = row.str("id") ?: continue
            if (platform != null && platform !in strings(row["platforms"])) continue
            out[id] = MedicationJson.obj(
                "api_format" to element(row["api_format"]),
                "base_url" to element(row["base_url"]),
                "requires_key" to element(row["requires_key"]),
                "supports_vision" to element(row["supports_vision"]),
                "configurable_timeout" to element(row["configurable_timeout"]),
                "models" to element(row["models"]),
            )
        }
        return out
    }

    private fun isLocal(spec: JsonObject): Boolean = spec.str("api_format") in LOCAL_FORMATS

    private fun defaultModelFor(provider: String, table: Map<String, JsonObject>): String =
        strings(table[provider]?.get("models")).firstOrNull() ?: ""

    /** Which key carries the output cap in an OpenAI-compatible body. */
    fun tokenLimitKey(provider: String?, model: String?): String {
        val limits = AICatalogs.providers.child("token_limit")
        val completion = limits.str("completion") ?: "max_completion_tokens"
        val name = provider ?: ""
        if (name in strings(limits["always_completion"])) return completion
        if (name in strings(limits["conditional"])) {
            val tail = normalizeModelId(model).lowercase().substringAfterLast('/')
            for (prefix in strings(limits["completion_prefixes"])) {
                if (tail.startsWith(prefix)) return completion
            }
        }
        return limits.str("default") ?: "max_tokens"
    }

    // -- 4 Profiles -------------------------------------------------------------------------------

    /** The identity of a configuration. Two slots with this key are one profile. */
    fun profileKey(provider: String?, model: String?, baseUrl: String?): List<String> =
        listOf(trim(provider), normalizeModelId(model), trim(baseUrl))

    private fun profileKey(profile: JsonObject): List<String> =
        profileKey(profile.str("provider"), profile.str("model_id"), profile.str("base_url"))

    private fun nextProfileNumber(profiles: List<JsonObject>): Int {
        var highest = 0
        for (profile in profiles) {
            val pid = profile.str("id") ?: continue
            if (!pid.startsWith(PROFILE_ID_PREFIX)) continue
            val tail = pid.substring(PROFILE_ID_PREFIX.length)
            val value = tail.toIntOrNull()
            if (tail.isNotEmpty() && tail.all { it.isDigit() } && value != null) {
                highest = max(highest, value)
            }
        }
        return highest + 1
    }

    private fun formatProfileId(number: Int): String =
        PROFILE_ID_PREFIX + number.toString().padStart(PROFILE_ID_DIGITS, '0')

    private fun nickname(provider: String, model: String, taken: MutableSet<String>): String {
        val base = autoNickname(provider, model)
        if (fold(base) !in taken) {
            taken.add(fold(base))
            return base
        }
        var n = 2
        while (true) {
            val candidate = "$base ($n)"
            if (fold(candidate) !in taken) {
                taken.add(fold(candidate))
                return candidate
            }
            n += 1
        }
    }

    private fun newProfile(
        provider: String,
        model: String,
        baseUrl: String,
        nowMs: Long,
        profiles: MutableList<JsonObject>,
        taken: MutableSet<String>,
    ): JsonObject {
        val profile = MedicationJson.obj(
            "id" to formatProfileId(nextProfileNumber(profiles)),
            "nickname" to nickname(provider, model, taken),
            "provider" to provider,
            "model_id" to model,
            "base_url" to baseUrl.ifEmpty { null },
            "vertex" to null,
            // Every migrated or adopted profile inherits the provider's key. That single line is the
            // whole backward-compatibility story: nobody has to re-enter a key.
            "credential_ref" to "provider:$provider",
            "created_ms" to nowMs,
            "updated_ms" to nowMs,
        )
        profiles.add(profile)
        return profile
    }

    private fun findOrCreate(
        provider: String,
        model: String,
        baseUrl: String,
        nowMs: Long,
        profiles: MutableList<JsonObject>,
        index: MutableMap<String, JsonObject>,
        taken: MutableSet<String>,
    ): Pair<JsonObject, Boolean> {
        val key = profileKey(provider, model, baseUrl)
        val joined = key.joinToString(FINGERPRINT_SEPARATOR)
        index[joined]?.let { return it to false }
        val profile = newProfile(key[0], key[1], key[2], nowMs, profiles, taken)
        index[joined] = profile
        return profile to true
    }

    private fun indexProfiles(
        profiles: List<JsonObject>,
    ): Pair<MutableMap<String, JsonObject>, MutableSet<String>> {
        val index = LinkedHashMap<String, JsonObject>()
        val taken = LinkedHashSet<String>()
        for (profile in profiles) {
            val joined = profileKey(profile).joinToString(FINGERPRINT_SEPARATOR)
            if (joined !in index) index[joined] = profile
            taken.add(fold(profile.str("nickname").orEmpty()))
        }
        return index to taken
    }

    private fun roleRow(roles: JsonObject, name: String): JsonObject {
        val row = roles.child(name)
        return MedicationJson.obj(
            "profile_id" to element(row["profile_id"]),
            "enabled" to (row.bool("enabled") ?: (name == "image")),
        )
    }

    /**
     * One-shot conversion of the four legacy slots into profiles + role pointers. Deterministic and
     * idempotent, which is what lets it run on every launch behind a version guard.
     */
    fun migrateProfiles(payload: JsonObject): JsonObject {
        val version = payload.double("migration_version")?.toInt() ?: 0
        val profiles = payload.objects("existing_profiles").toMutableList()
        val rolesIn = payload.child("roles")
        if (version >= MIGRATION_VERSION) {
            val out = ROLES.associateWith { roleRow(rolesIn, it) }
            return MedicationJson.obj(
                "migration_version" to version,
                "profiles" to profiles.toList(),
                "roles" to out,
            )
        }

        val nowMs = payload.double("now_ms")?.toLong() ?: 0L
        val slots = payload.child("slots")
        val table = providerDefaults(payload.child("env"))
        val (index, taken) = indexProfiles(profiles)
        val out = LinkedHashMap<String, JsonObject>()
        for (name in ROLES) {
            val slot = slots.child(name)
            val provider = trim(slot.str("provider"))
            val model = normalizeModelId(slot.str("model"))
            val enabled = slot.bool("enabled") ?: (name == "image")
            val spec = table[provider]
            if (spec == null || (model.isEmpty() && !isLocal(spec))) {
                // A slot naming a provider this build does not have, or carrying no model, describes
                // nothing runnable. Dropping it is safer than inventing a model it never had.
                out[name] = MedicationJson.obj("profile_id" to null, "enabled" to false)
                continue
            }
            val (profile, _) = findOrCreate(
                provider, model, trim(slot.str("base_url")), nowMs, profiles, index, taken,
            )
            out[name] = MedicationJson.obj("profile_id" to profile.str("id"), "enabled" to enabled)
        }

        if (out["image"]?.str("profile_id") == null) {
            // Resolution must never come up empty, so the primary always ends with something real.
            val (profile, _) = findOrCreate(
                DEFAULT_PROVIDER, defaultModelFor(DEFAULT_PROVIDER, table), "", nowMs,
                profiles, index, taken,
            )
            out["image"] = MedicationJson.obj("profile_id" to profile.str("id"), "enabled" to true)
        }
        return MedicationJson.obj(
            "migration_version" to MIGRATION_VERSION,
            "profiles" to profiles.toList(),
            "roles" to out,
        )
    }

    private fun autoNickname(provider: String, model: String): String =
        if (model.isEmpty()) provider else "$provider · $model"

    /**
     * Add a saved configuration without wiring it to any role.
     *
     * Settings' "Add model" writes through this, so a standalone profile gets its id and its unique
     * nickname from the same place a migrated one does. An identical configuration is returned
     * rather than duplicated -- two rows naming one endpoint would be a list nobody can reason about.
     */
    fun addProfile(payload: JsonObject): JsonObject {
        val profiles = payload.objects("profiles").toMutableList()
        val table = providerDefaults(payload.child("env"))
        val provider = trim(payload.str("provider"))
        val model = normalizeModelId(payload.str("model"))
        val baseUrl = trim(payload.str("base_url"))
        if (table[provider] == null) {
            return MedicationJson.obj(
                "profiles" to profiles.toList(), "profile_id" to null, "action" to "ignored",
            )
        }
        val (index, taken) = indexProfiles(profiles)
        val key = profileKey(provider, model, baseUrl).joinToString(FINGERPRINT_SEPARATOR)
        index[key]?.let {
            return MedicationJson.obj(
                "profiles" to profiles.toList(), "profile_id" to it.str("id"), "action" to "reused",
            )
        }
        var profile = newProfile(
            provider, model, baseUrl, payload.double("now_ms")?.toLong() ?: 0L, profiles, taken,
        )
        val nickname = trim(payload.str("nickname"))
        if (nickname.isNotEmpty()) {
            val fields = LinkedHashMap<String, Any?>()
            for ((k, v) in profile) fields[k] = v
            fields["nickname"] = nickname
            profile = MedicationJson.obj(*fields.map { it.key to it.value }.toTypedArray())
            profiles[profiles.size - 1] = profile
        }
        return MedicationJson.obj(
            "profiles" to profiles.toList(), "profile_id" to profile.str("id"),
            "action" to "created",
        )
    }

    /**
     * Point a role at the configuration {provider, model, base_url}.
     *
     * Settings' per-role editors write through this. It is copy-on-write: editing the primary must
     * not silently change a fallback that happens to share its profile, which is exactly what the
     * four separate flat slots used to guarantee. So a profile another role also points at is never
     * edited in place -- a new one is created instead.
     *
     * Editing in place keeps the profile id, so a conversation pinned to it follows the change
     * rather than losing its pin. Changing the provider resets `credential_ref` to that provider's
     * key and reports `cleared_profile_key`, because a key issued for one provider is meaningless at
     * another.
     */
    fun assignRole(payload: JsonObject): JsonObject {
        val profiles = payload.objects("profiles").toMutableList()
        val rolesOut = LinkedHashMap<String, JsonObject>()
        for (name in ROLES) rolesOut[name] = roleRow(payload.child("roles"), name)
        val roleName = payload.str("role").orEmpty()

        fun result(profileId: String?, action: String, cleared: String? = null): JsonObject =
            MedicationJson.obj(
                "profiles" to profiles.toList(),
                "roles" to rolesOut,
                "profile_id" to profileId,
                "action" to action,
                "cleared_profile_key" to cleared,
            )

        if (roleName !in ROLES) return result(null, "ignored")

        val table = providerDefaults(payload.child("env"))
        val provider = trim(payload.str("provider"))
        val model = normalizeModelId(payload.str("model"))
        val baseUrl = trim(payload.str("base_url"))
        if (table[provider] == null) {
            return result(rolesOut[roleName]?.str("profile_id"), "ignored")
        }

        val nowMs = payload.double("now_ms")?.toLong() ?: 0L
        val (index, taken) = indexProfiles(profiles)
        val byId = profiles.withIndex().mapNotNull { (i, p) -> p.str("id")?.let { it to i } }.toMap()
        val key = profileKey(provider, model, baseUrl).joinToString(FINGERPRINT_SEPARATOR)

        index[key]?.let { existing ->
            rolesOut[roleName] = MedicationJson.obj(
                "profile_id" to existing.str("id"),
                "enabled" to (rolesOut[roleName]?.bool("enabled") ?: true),
            )
            return result(existing.str("id"), "reused")
        }

        val pointer = rolesOut[roleName]?.str("profile_id")
        val currentIndex = pointer?.let { byId[it] }
        val shared = pointer != null &&
            ROLES.any { it != roleName && rolesOut[it]?.str("profile_id") == pointer }

        if (currentIndex != null && !shared) {
            val current = profiles[currentIndex]
            val oldProvider = current.str("provider").orEmpty()
            val oldModel = current.str("model_id").orEmpty()
            val oldNickname = current.str("nickname").orEmpty()
            val wasAuto = oldNickname == autoNickname(oldProvider, oldModel)
            val providerChanged = oldProvider != provider
            val cleared = providerChanged &&
                current.str("credential_ref").orEmpty().startsWith("profile:")
            val next = LinkedHashMap<String, Any?>()
            for ((k, v) in current) next[k] = v
            next["provider"] = provider
            next["model_id"] = model
            next["base_url"] = baseUrl.ifEmpty { null }
            next["updated_ms"] = nowMs
            if (providerChanged) {
                next["credential_ref"] = "provider:$provider"
                next["vertex"] = null
            }
            if (wasAuto) {
                // The name was ours, so it follows the configuration. A typed name does not.
                taken.remove(fold(oldNickname))
                next["nickname"] = nickname(provider, model, taken)
            }
            profiles[currentIndex] =
                MedicationJson.obj(*next.map { it.key to it.value }.toTypedArray())
            val id = current.str("id")
            rolesOut[roleName] = MedicationJson.obj(
                "profile_id" to id,
                "enabled" to (rolesOut[roleName]?.bool("enabled") ?: true),
            )
            return result(id, "edited", if (cleared) id else null)
        }

        val profile = newProfile(provider, model, baseUrl, nowMs, profiles, taken)
        index[key] = profile
        rolesOut[roleName] = MedicationJson.obj(
            "profile_id" to profile.str("id"),
            "enabled" to (rolesOut[roleName]?.bool("enabled") ?: true),
        )
        return result(profile.str("id"), "created")
    }

    /** What the mirror last wrote for the primary role. */
    fun fingerprint(provider: String?, model: String?, baseUrl: String?): String =
        profileKey(provider, model, baseUrl).joinToString(FINGERPRINT_SEPARATOR)

    /**
     * Profiles -> the flat keys, the one direction writes flow in. Lossy on purpose: nickname, id,
     * `vertex{}` and `credential_ref` have no legacy home, and nothing that reads them wants them.
     */
    fun projectLegacy(profiles: List<JsonObject>, rolesIn: JsonObject): JsonObject {
        val byId = profiles.mapNotNull { p -> p.str("id")?.let { it to p } }.toMap()
        val slots = LinkedHashMap<String, JsonObject>()
        for (name in ROLES) {
            val row = rolesIn.child(name)
            val profile = row.str("profile_id")?.let { byId[it] }
            if (profile == null) {
                slots[name] = MedicationJson.obj(
                    "provider" to null, "model" to "", "base_url" to null, "enabled" to false,
                )
                continue
            }
            slots[name] = MedicationJson.obj(
                "provider" to profile.str("provider"),
                "model" to profile.str("model_id"),
                "base_url" to trim(profile.str("base_url")).ifEmpty { null },
                "enabled" to (row.bool("enabled") ?: (name == "image")),
            )
        }
        val primary = slots["image"] ?: JsonObject(emptyMap())
        return MedicationJson.obj(
            "slots" to slots,
            "fingerprint" to fingerprint(
                primary.str("provider").orEmpty(), primary.str("model"),
                primary.str("base_url").orEmpty(),
            ),
        )
    }

    /** The one inbound channel: a flat primary the mirror did not write, i.e. onboarding's choice. */
    fun adoptLegacyPrimary(payload: JsonObject): JsonObject {
        val observed = payload.child("observed")
        val provider = trim(observed.str("provider"))
        val model = normalizeModelId(observed.str("model"))
        val baseUrl = trim(observed.str("base_url"))
        val profiles = payload.objects("profiles").toMutableList()
        val out = LinkedHashMap<String, JsonObject>()
        for (name in ROLES) out[name] = roleRow(payload.child("roles"), name)
        val table = providerDefaults(payload.child("env"))
        val stored = element(payload["fingerprint"])
        val observedPrint = fingerprint(provider, model, baseUrl)

        if (table[provider] == null) {
            return MedicationJson.obj(
                "reason" to "ignored", "changed" to false, "profiles" to profiles.toList(),
                "roles" to out, "fingerprint" to stored,
            )
        }
        if (payload.str("fingerprint") == observedPrint) {
            return MedicationJson.obj(
                "reason" to "match", "changed" to false, "profiles" to profiles.toList(),
                "roles" to out, "fingerprint" to stored,
            )
        }
        val (index, taken) = indexProfiles(profiles)
        val (profile, created) = findOrCreate(
            provider, model, baseUrl, payload.double("now_ms")?.toLong() ?: 0L,
            profiles, index, taken,
        )
        val changed = created || out["image"]?.str("profile_id") != profile.str("id")
        out["image"] = MedicationJson.obj("profile_id" to profile.str("id"), "enabled" to true)
        return MedicationJson.obj(
            "reason" to if (created) "created" else "adopted",
            "changed" to changed,
            "profiles" to profiles.toList(),
            "roles" to out,
            "fingerprint" to observedPrint,
        )
    }

    // -- 4 Credentials ----------------------------------------------------------------------------

    /**
     * Which store holds the key for this profile.
     *
     * The profile -> provider fall-through is load-bearing: `KeyStore.openOrRecover` deliberately
     * wipes every key after an `AEADBadTagException`, and without it a profile whose own key vanished
     * would be permanently dead rather than merely back on the provider key.
     */
    fun resolveCredential(
        credentialRef: String?,
        requiresKey: Boolean,
        hasProfileKey: Boolean,
        hasProviderKey: Boolean,
    ): String {
        val ref = credentialRef ?: ""
        if (!requiresKey || ref == "none" || ref.isEmpty()) return "none"
        if (ref.startsWith("profile:")) {
            if (hasProfileKey) return "profile"
            return if (hasProviderKey) "provider" else "missing"
        }
        if (ref.startsWith("provider:")) return if (hasProviderKey) "provider" else "missing"
        return "missing"
    }

    /** What deleting a profile may remove from the two credential stores. */
    fun keysToDelete(payload: JsonObject): JsonObject {
        val pid = payload.child("profile").str("id").orEmpty()
        return MedicationJson.obj(
            "profile_keys" to if (pid.isEmpty()) emptyList<String>() else listOf("aiprofilekey_$pid"),
            // Never, under any condition. The provider key has owners outside the profile layer:
            // onboarding, the legacy per-provider screen, and `KeyStore.speechApiKey`, which falls
            // back to the matching AI provider's key.
            "provider_keys" to emptyList<String>(),
        )
    }

    // -- 5 Role resolution -------------------------------------------------------------------------

    private fun localModel(env: JsonObject, modelId: String): JsonObject =
        env.child("local_models").child(modelId)

    private fun credentialFor(profile: JsonObject, spec: JsonObject, env: JsonObject): JsonObject {
        val pid = profile.str("id").orEmpty()
        val provider = profile.str("provider").orEmpty()
        val source = resolveCredential(
            profile.str("credential_ref"),
            spec.bool("requires_key") ?: false,
            pid in strings(env["profiles_with_keys"]),
            provider in strings(env["providers_with_keys"]),
        )
        return when (source) {
            "profile" -> MedicationJson.obj("source" to source, "ref" to "aiprofilekey_$pid")
            "provider" -> MedicationJson.obj("source" to source, "ref" to "apikey_$provider")
            else -> MedicationJson.obj("source" to source, "ref" to null)
        }
    }

    private fun effectiveBaseUrl(profile: JsonObject, spec: JsonObject): String {
        val own = trim(profile.str("base_url"))
        return own.ifEmpty { spec.str("base_url").orEmpty() }
    }

    /** Why this profile cannot answer, or null when it can. */
    private fun usable(
        profile: JsonObject,
        env: JsonObject,
        table: Map<String, JsonObject>,
        requiresVision: Boolean,
    ): String? {
        val provider = profile.str("provider").orEmpty()
        val spec = table[provider] ?: return "unknown_provider"
        if (requiresVision && spec.bool("supports_vision") != true) return "no_vision"
        when (spec.str("api_format")) {
            "litert_local" -> {
                val local = localModel(env, profile.str("model_id").orEmpty())
                if (local.bool("installed") != true) return "model_not_installed"
                if (requiresVision && local.bool("vision") != true) return "no_vision"
            }
            "on_device" -> if (env.bool("on_device_available") != true) return "model_not_installed"
        }
        if (credentialFor(profile, spec, env).str("source") == "missing") return "no_key"
        if (!isLocal(spec) && effectiveBaseUrl(profile, spec).isEmpty()) return "no_base_url"
        return null
    }

    private fun route(
        profile: JsonObject,
        env: JsonObject,
        table: Map<String, JsonObject>,
        roleName: String,
    ): LinkedHashMap<String, Any?> {
        val provider = profile.str("provider").orEmpty()
        val spec = table[provider] ?: JsonObject(emptyMap())
        val model = normalizeModelId(profile.str("model_id"))
        val local = isLocal(spec)
        val timeout: JsonElement =
            if (spec.bool("configurable_timeout") == true) element(env["request_timeout_seconds"])
            else JsonNull
        var context: JsonElement = JsonNull
        if (spec.str("api_format") == "litert_local") {
            context = element(localModel(env, model)["context_tokens"])
        }
        return linkedMapOf(
            "role" to roleName,
            "profile_id" to element(profile["id"]),
            "provider" to provider,
            "model" to model,
            "base_url" to if (local) "" else effectiveBaseUrl(profile, spec),
            "api_format" to element(spec["api_format"]),
            "credential" to credentialFor(profile, spec, env),
            "vertex" to element(profile["vertex"]),
            "request_timeout_seconds" to timeout,
            "max_response_tokens" to element(env["max_response_tokens"]),
            "token_limit_key" to tokenLimitKey(provider, model),
            "context_tokens" to context,
        )
    }

    private fun synthesizedDefault(
        env: JsonObject,
        table: Map<String, JsonObject>,
        roleName: String,
    ): LinkedHashMap<String, Any?> {
        val profile = MedicationJson.obj(
            "id" to null,
            "nickname" to DEFAULT_PROVIDER,
            "provider" to DEFAULT_PROVIDER,
            "model_id" to defaultModelFor(DEFAULT_PROVIDER, table),
            "base_url" to null,
            "vertex" to null,
            "credential_ref" to "provider:$DEFAULT_PROVIDER",
            "created_ms" to 0L,
            "updated_ms" to 0L,
        )
        return route(profile, env, table, roleName)
    }

    /** Image requests always use the vision role; text requests use the dedicated one when on. */
    fun roleFor(rolesIn: JsonObject, requiresVision: Boolean): String {
        if (requiresVision) return "image"
        return if (rolesIn.child("text").bool("enabled") == true) "text" else "image"
    }

    /**
     * The single answer to "which provider, which model, which key".
     *
     * It NEVER substitutes a different profile for the one the user chose. A chosen profile that
     * cannot answer comes back with `blocked` set and the caller refuses -- silently answering
     * elsewhere would be wrong for an ordinary cloud profile and unacceptable for an on-device one,
     * where the substitute would put health data on someone else's server without a word.
     *
     * A route always comes back so the caller has something to name in that refusal. Only a device
     * with nothing configured at all gets the unblocked default, which is what the app shows today
     * before the first key is entered.
     */
    fun resolveRole(payload: JsonObject): JsonObject {
        val env = payload.child("env")
        val table = providerDefaults(env)
        val profiles = payload.objects("profiles")
        val rolesIn = payload.child("roles")
        val requiresVision = env.bool("requires_vision") ?: false
        val roleName = payload.str("role") ?: roleFor(rolesIn, requiresVision)
        val byId = profiles.mapNotNull { p -> p.str("id")?.let { it to p } }.toMap()

        val pointer = rolesIn.child(roleName).str("profile_id")
        val profile = pointer?.let { byId[it] }
        if (profile == null) {
            val fallback = synthesizedDefault(env, table, roleName)
            fallback["fell_back"] = true
            // A dangling pointer means a profile was deleted without repointing its role, which
            // rule 1 forbids; an absent one on an empty device means nothing is set up yet. Only
            // the second is allowed to answer.
            fallback["blocked"] =
                if (profiles.isEmpty() && pointer == null) null else "no_profile"
            return MedicationJson.obj(*fallback.entries.map { it.key to it.value }.toTypedArray())
        }
        val found = route(profile, env, table, roleName)
        found["fell_back"] = false
        found["blocked"] = usable(profile, env, table, requiresVision)
        return MedicationJson.obj(*found.entries.map { it.key to it.value }.toTypedArray())
    }

    private fun sameEndpoint(a: Map<String, Any?>, b: JsonObject): Boolean =
        (a["provider"] as? String).orEmpty() == b.str("provider").orEmpty() &&
            (a["model"] as? String).orEmpty() == b.str("model").orEmpty() &&
            (a["base_url"] as? String).orEmpty() == b.str("base_url").orEmpty()

    /**
     * The one retry, or nothing. A fallback pointing at the same endpoint as the primary is not a
     * fallback; it is a guaranteed second failure. Compare the endpoint triple, never the profile id:
     * two profiles can describe one endpoint, and one profile can never be its own fallback.
     */
    fun resolveFallback(payload: JsonObject): JsonObject {
        val env = payload.child("env")
        val table = providerDefaults(env)
        val profiles = payload.objects("profiles")
        val rolesIn = payload.child("roles")
        val primary = payload.child("primary")
        val requiresVision = env.bool("requires_vision") ?: false
        // Unchanged from today: an image request falls back on the image pair, anything else on the
        // text pair, whether or not a separate text provider is configured.
        val roleName = payload.str("role")
            ?: if (requiresVision) "image_fallback" else "text_fallback"
        if (roleName !in FALLBACK_OF) {
            return MedicationJson.obj("route" to null, "reason" to "not_a_fallback_role")
        }
        val row = rolesIn.child(roleName)
        if (row.bool("enabled") != true) {
            return MedicationJson.obj("route" to null, "reason" to "disabled")
        }
        val byId = profiles.mapNotNull { p -> p.str("id")?.let { it to p } }.toMap()
        val profile = row.str("profile_id")?.let { byId[it] }
            ?: return MedicationJson.obj("route" to null, "reason" to "no_profile")
        val reason = usable(profile, env, table, requiresVision)
        if (reason != null) return MedicationJson.obj("route" to null, "reason" to reason)
        val found = route(profile, env, table, roleName)
        if (sameEndpoint(found, primary)) {
            return MedicationJson.obj("route" to null, "reason" to "same_endpoint")
        }
        return MedicationJson.obj(
            "route" to MedicationJson.obj(*found.entries.map { it.key to it.value }.toTypedArray()),
            "reason" to null,
        )
    }

    // -- 8 Per-conversation override ---------------------------------------------------------------

    private fun validProfileId(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_PROFILE_ID_LENGTH &&
            value.all { it in PROFILE_ID_CHARS }

    /**
     * Read `conversations.provider_override`, old form or new.
     *
     * Old rows hold a bare provider token, written by the Health-Records "Use on-device Coach" flow.
     * New rows hold `profile:<id>|<provider token>`. The provider is carried AFTER the id on purpose:
     * this column travels in the chat archive, so a conversation imported onto another device names a
     * profile that does not exist there and must degrade to the provider rather than quietly answer
     * on the wrong model.
     */
    fun parseOverride(raw: String?): JsonObject {
        val value = trim(raw)
        if (value.isEmpty()) {
            return MedicationJson.obj("profile_id" to null, "provider" to null)
        }
        if (value.startsWith(OVERRIDE_PREFIX)) {
            val body = value.substring(OVERRIDE_PREFIX.length)
            val parts = body.split(OVERRIDE_SEPARATOR, limit = 2)
            val pid = trim(parts[0])
            val provider = if (parts.size > 1) trim(parts[1]) else ""
            if (validProfileId(pid)) {
                return MedicationJson.obj(
                    "profile_id" to pid, "provider" to provider.ifEmpty { null },
                )
            }
            // Not a profile id after all; the whole string is somebody's provider token.
            return MedicationJson.obj("profile_id" to null, "provider" to value)
        }
        return MedicationJson.obj("profile_id" to null, "provider" to value)
    }

    class OverrideError(message: String) : IllegalArgumentException(message)

    /** Write the column. A null `profileId` gives the bare token the records flow has always written. */
    fun encodeOverride(profileId: String?, provider: String?): String? {
        val pid = trim(profileId)
        val token = trim(provider)
        if (token.contains(OVERRIDE_SEPARATOR) || pid.contains(OVERRIDE_SEPARATOR)) {
            throw OverrideError("a provider token or profile id may not contain '$OVERRIDE_SEPARATOR'")
        }
        if (pid.isEmpty()) return token.ifEmpty { null }
        if (!validProfileId(pid)) throw OverrideError("bad profile id $pid")
        return OVERRIDE_PREFIX + pid + OVERRIDE_SEPARATOR + token
    }

    // -- 6 Google Vertex AI ------------------------------------------------------------------------

    private fun vertexHost(location: String): String {
        val hosts = AICatalogs.vertex.objects("hosts")
        for (rule in hosts) {
            when (rule.str("match")) {
                "global" -> if (location == "global") return rule.str("host").orEmpty()
                "multi_region" -> if (location in strings(rule["values"])) {
                    return rule.str("host").orEmpty().replace("{location}", location)
                }
            }
        }
        for (rule in hosts) {
            if (rule.str("match") == "region") {
                return rule.str("host").orEmpty().replace("{location}", location)
            }
        }
        return ""
    }

    private fun vertexRoute(modelId: String): JsonObject? {
        val lowered = modelId.lowercase()
        val routes = AICatalogs.vertex.objects("routes")
        for (route in routes) {
            for (prefix in strings(route["prefixes"])) {
                if (lowered.startsWith(prefix)) return route
            }
        }
        return routes.firstOrNull { strings(it["prefixes"]).isEmpty() }
    }

    /**
     * Host + path + transport for one Vertex call. One provider, three transports: Gemini and Claude
     * are different APIs that happen to share a host, and everything else goes through the
     * OpenAI-compatible surface.
     */
    fun vertexEndpoint(project: String?, location: String?, modelId: String?): JsonObject {
        val projectId = trim(project)
        var where = trim(location)
        if (where.isEmpty()) {
            where = AICatalogs.vertex.str("default_location") ?: "global"
        }
        val model = trim(modelId)
        if (projectId.isEmpty()) {
            return MedicationJson.obj("ok" to false, "reason" to "no_project")
        }
        if (model.isEmpty()) return MedicationJson.obj("ok" to false, "reason" to "no_model")
        val route = vertexRoute(model)
            ?: return MedicationJson.obj("ok" to false, "reason" to "no_route")
        val host = vertexHost(where)
        if (host.isEmpty()) return MedicationJson.obj("ok" to false, "reason" to "no_host")
        val prefix = (AICatalogs.vertex.str("path_prefix") ?: "")
            .replace("{project}", projectId)
            .replace("{location}", where)
        val path = route.str("path").orEmpty().replace("{model}", model)
        return MedicationJson.obj(
            "ok" to true,
            "url" to host + prefix + path,
            "publisher" to element(route["publisher"]),
            "transport" to element(route["transport"]),
            "body_extras" to element(route["body_extras"]),
            "drops_model_from_body" to (route.bool("drops_model_from_body") ?: false),
            "location" to where,
        )
    }

    // -- 7 Local model catalogue -------------------------------------------------------------------

    /**
     * `max(6 GiB, artifact * 2 + 2 GiB rounded up to an even GiB)`. Reproduces the shipped
     * Gemma 4 E2B gate of 8 GiB exactly, so adding models does not quietly re-gate the one already
     * installed on people's phones.
     */
    fun memoryGate(sizeBytes: Long): Long {
        val needed = sizeBytes.toDouble() / GIB.toDouble() * 2 + 2
        val even = ceil(needed / 2.0).toLong() * 2
        return max(6L, even) * GIB
    }

    /** Total RAM in GiB, rounded UP -- an "8 GB" phone reports a little less than 8 GiB. */
    fun memoryClassGb(physicalMemoryBytes: Long): Long =
        ceil(physicalMemoryBytes.toDouble() / GIB.toDouble()).toLong()

    fun requiredHeadroom(sizeBytes: Long, policy: JsonObject): Long {
        val minimum = policy.double("minimumHeadroomBytes")?.toLong() ?: 268_435_456L
        val percent = policy.double("headroomPercent")?.toLong() ?: 10L
        return max(minimum, sizeBytes * percent / 100L)
    }

    /** What Settings shows for every downloadable model, in the order it shows them. */
    fun localCatalogState(payload: JsonObject): JsonObject {
        val catalog = payload.objOrNull("catalog") ?: AICatalogs.models
        val device = payload.child("device")
        val installed = strings(payload["installed"]).toSet()
        val hasToken = payload.bool("has_hf_token") ?: false
        val platform = payload.str("platform") ?: "android"
        val abis = strings(device["abis"]).toSet()
        val supported = strings(catalog["supportedAbis"]).toSet()
        val memory = memoryClassGb(device.double("physical_memory_bytes")?.toLong() ?: 0L)
        val free = device.double("free_bytes")?.toLong()
        val policy = catalog.child("downloadPolicy")

        val rows = mutableListOf<JsonObject>()
        for (model in catalog.objects("models")) {
            if (platform !in strings(model["platforms"])) continue
            val artifact = model.child("artifact")
            val size = artifact.double("sizeBytes")?.toLong() ?: 0L
            val gate = model.child("memoryPolicy").double("minimumPhysicalMemoryBytes")?.toLong() ?: 0L
            val gated = artifact.child("access").bool("gated") ?: false
            val headroom = requiredHeadroom(size, policy)
            var state = "available"
            var reason: String? = null
            when {
                model.str("id") in installed -> state = "installed"
                device.bool("low_ram") == true -> {
                    state = "ineligible"; reason = "low_ram_device"
                }
                supported.isNotEmpty() && abis.isNotEmpty() && abis.none { it in supported } -> {
                    state = "ineligible"; reason = "unsupported_abi"
                }
                memory * GIB < gate -> {
                    state = "ineligible"; reason = "insufficient_memory"
                }
                gated && !hasToken -> {
                    state = "needs_token"; reason = "needs_token"
                }
                free != null && free < size + headroom -> {
                    state = "ineligible"; reason = "insufficient_space"
                }
            }
            rows.add(
                MedicationJson.obj(
                    "id" to model.str("id"),
                    "display_name" to model.str("displayName"),
                    "size_bytes" to size,
                    "vision" to ("image" in strings(model["capabilities"])),
                    "context_tokens" to element(model["contextTokens"]),
                    "gated" to gated,
                    "minimum_memory_bytes" to gate,
                    "required_headroom_bytes" to headroom,
                    "state" to state,
                    "reason" to reason,
                ),
            )
        }
        // sortedBy is stable, so catalogue order survives inside each group.
        val ordered = rows.sortedBy { CATALOG_STATES.indexOf(it.str("state")) }
        return MedicationJson.obj("models" to ordered)
    }

    // -- Vector dispatch ---------------------------------------------------------------------------

    fun runCase(function: String, input: JsonObject): JsonElement = when (function) {
        "migrate_profiles" -> migrateProfiles(input)
        "adopt_legacy_primary" -> adoptLegacyPrimary(input)
        "add_profile" -> addProfile(input)
        "assign_role" -> assignRole(input)
        "project_legacy" -> projectLegacy(input.objects("profiles"), input.child("roles"))
        "resolve_role" -> resolveRole(input)
        "resolve_fallback" -> resolveFallback(input)
        "resolve_credential" -> MedicationJson.obj(
            "source" to resolveCredential(
                input.str("credential_ref"),
                input.bool("requires_key") ?: true,
                input.bool("has_profile_key") ?: false,
                input.bool("has_provider_key") ?: false,
            ),
        )
        "keys_to_delete" -> keysToDelete(input)
        "vertex_endpoint" ->
            vertexEndpoint(input.str("project"), input.str("location"), input.str("model"))
        "override" ->
            if (input.str("op") == "parse") {
                parseOverride(input.str("raw"))
            } else {
                try {
                    MedicationJson.obj(
                        "raw" to encodeOverride(input.str("profile_id"), input.str("provider")),
                    )
                } catch (e: OverrideError) {
                    MedicationJson.obj("raw" to null, "error" to e.message)
                }
            }
        "token_limit_key" ->
            MedicationJson.obj("key" to tokenLimitKey(input.str("provider"), input.str("model")))
        "local_catalog_state" -> localCatalogState(input)
        else -> error("unknown function $function")
    }
}
