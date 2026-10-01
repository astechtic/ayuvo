package com.ayuvo.health.services.googlehealth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `shared/health/google_health_map.json`, bundled byte-for-byte as `assets/health/google_health_map.json`
 * (GoogleHealthMapContractTest). Google Health API type → registry slug, filter, value paths and the
 * Health Connect write-back record (docs/google-health.md). Pure Kotlin, so the mapper and the sync
 * engine are tested on the JVM against the same file.
 */
@Serializable
data class GoogleHealthMap(
    @SerialName("map_version") val mapVersion: Int,
    val api: Api,
    @SerialName("scope_groups") val scopeGroups: List<ScopeGroup>,
    @SerialName("echo_guard") val echoGuard: EchoGuard,
    val types: List<TypeEntry>
) {
    @Serializable
    data class Api(
        @SerialName("base_url") val baseUrl: String,
        @SerialName("list_path") val listPath: String,
        @SerialName("auth_url") val authUrl: String,
        @SerialName("token_url") val tokenUrl: String,
        @SerialName("revoke_url") val revokeUrl: String,
        @SerialName("userinfo_url") val userinfoUrl: String,
        @SerialName("scope_prefix") val scopePrefix: String,
        @SerialName("identity_scopes") val identityScopes: List<String>,
        @SerialName("initial_backfill_days") val initialBackfillDays: Int,
        @SerialName("overlap_days") val overlapDays: Int,
        @SerialName("max_concurrent_types") val maxConcurrentTypes: Int,
        @SerialName("auto_sync_min_interval_s") val autoSyncMinIntervalS: Long,
        @SerialName("manual_sync_min_interval_s") val manualSyncMinIntervalS: Long
    )

    @Serializable
    data class ScopeGroup(
        val id: String,
        val scopes: List<String>,
        @SerialName("display_name") val displayName: String
    )

    @Serializable
    data class EchoGuard(
        @SerialName("skip_mirror_when_package_in") val skipMirrorWhenPackageIn: List<String>,
        @SerialName("skip_mirror_when_platform_and_same_os") val skipMirrorWhenPlatformAndSameOs: Boolean,
        @SerialName("duplicate_window_ms") val duplicateWindowMs: Long,
        @SerialName("duplicate_value_epsilon_ratio") val duplicateValueEpsilonRatio: Double
    )

    @Serializable
    data class ValueSpec(
        val path: String,
        val scale: Double? = null,
        val converter: String? = null
    )

    @Serializable
    data class CategorySpec(
        val path: String? = null,
        val map: Map<String, Int> = emptyMap(),
        val default: Int = 0
    )

    @Serializable
    data class HcSpec(
        val record: String,
        @SerialName("write_permission") val writePermission: String
    )

    @Serializable
    data class TypeEntry(
        @SerialName("gh_type") val ghType: String,
        val union: String,
        val optional: Boolean = false,
        @SerialName("scope_group") val scopeGroup: String,
        val time: String,
        val filter: String,
        @SerialName("page_size") val pageSize: Int,
        @SerialName("type_id") val typeId: String,
        val value: ValueSpec? = null,
        val value2: ValueSpec? = null,
        val value3: ValueSpec? = null,
        val category: CategorySpec? = null,
        val extra: Map<String, String> = emptyMap(),
        @SerialName("drop_fields") val dropFields: List<String> = emptyList(),
        val converter: String? = null,
        @SerialName("stage_map") val stageMap: Map<String, Int> = emptyMap(),
        @SerialName("meal_map") val mealMap: Map<String, Int> = emptyMap(),
        val hc: HcSpec? = null
    )

    val typesByGhType: Map<String, TypeEntry> by lazy { types.associateBy { it.ghType } }

    fun type(ghType: String): TypeEntry? = typesByGhType[ghType]

    fun group(id: String): ScopeGroup? = scopeGroups.firstOrNull { it.id == id }

    /** Full OAuth scope strings of [groupIds] plus the identity scopes (`openid email`). */
    fun scopesFor(groupIds: Collection<String>): List<String> =
        scopeGroups.filter { it.id in groupIds }.flatMap { g -> g.scopes.map { api.scopePrefix + it } } + api.identityScopes

    /** A type is readable when any scope of its group was granted (ECG/IRN share a group). */
    fun isGranted(entry: TypeEntry, grantedScopes: Set<String>): Boolean {
        val scopes = group(entry.scopeGroup)?.scopes ?: return false
        return scopes.any { (api.scopePrefix + it) in grantedScopes }
    }

    /** Distinct Health Connect WRITE_* permissions the write-back needs. */
    val writePermissions: Set<String> by lazy { types.mapNotNull { it.hc?.writePermission }.toSet() }

    companion object {
        const val ASSET_PATH = "health/google_health_map.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GoogleHealthMap = json.decodeFromString(serializer(), text)
    }
}
