package com.ayuvo.health.services.health

import com.ayuvo.health.models.HealthAndroidTier
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthFeatureFlag

/**
 * Registry-driven permission sets for the Health Data hub — pure so JVM tests can
 * exercise them with a fake feature-availability lambda. Separate from the legacy
 * WRITE_* set in [HealthConnectManager.permissions].
 */
object HealthHubPermissions {
    /** Tracked in `healthHubPromptedVersion`; unrelated to [HealthConnectManager.CURRENT_TYPES_VERSION]. */
    const val HUB_PERMISSIONS_VERSION = 1

    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    fun typesFor(tier: HealthAndroidTier?, featureAvailable: (HealthFeatureFlag) -> Boolean): List<HealthDataType> =
        HealthDataType.sdkTypes.filter { type ->
            (tier == null || type.androidTier == tier) && featureOk(type, featureAvailable)
        }

    fun readPermissions(tier: HealthAndroidTier?, featureAvailable: (HealthFeatureFlag) -> Boolean): Set<String> =
        typesFor(tier, featureAvailable).mapNotNull { it.hcPermission }.toSet()

    fun categoryReadPermissions(category: HealthCategory, featureAvailable: (HealthFeatureFlag) -> Boolean): Set<String> =
        HealthDataType.sdkTypes
            .filter { it.category == category && featureOk(it, featureAvailable) }
            .mapNotNull { it.hcPermission }
            .toSet()

    fun requestPermissions(
        tier: HealthAndroidTier?,
        includeHistory: Boolean,
        featureAvailable: (HealthFeatureFlag) -> Boolean
    ): Set<String> = readPermissions(tier, featureAvailable) + if (includeHistory && featureAvailable(HealthFeatureFlag.HISTORY)) setOf(HISTORY) else emptySet()

    /** Registry types whose read permission is granted and whose platform feature is available. */
    fun grantedTypes(granted: Set<String>, featureAvailable: (HealthFeatureFlag) -> Boolean): Set<HealthDataType> =
        HealthDataType.sdkTypes
            .filter { it.hcPermission in granted && featureOk(it, featureAvailable) }
            .toSet()

    fun featureOk(type: HealthDataType, featureAvailable: (HealthFeatureFlag) -> Boolean): Boolean =
        type.hcFeatureFlag?.let(featureAvailable) ?: true

    /**
     * Quota detection by class name + message so JVM tests can use plain exceptions:
     * a bare `RemoteException` also backs off (the binder itself is refusing us).
     */
    fun isQuotaError(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val name = cur.javaClass.simpleName
            val message = cur.message.orEmpty().lowercase()
            if (name == "RemoteException") return true
            if ("quota" in message || "rate limit" in message || "rate-limit" in message || "rate_limit" in message || "too many requests" in message) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /** Reads before the readable window (no history permission) surface as argument/security errors. */
    fun isBoundaryError(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val message = cur.message.orEmpty().lowercase()
            if ("30 days" in message || "history" in message || "before" in message && "grant" in message) return true
            if (cur is SecurityException && "read" in message) return true
            cur = cur.cause
            depth++
        }
        return false
    }
}
