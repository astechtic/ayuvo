package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import java.time.ZoneId

/**
 * One-time rollup rebuild when the rollup rules change (health_meta `rollup_rule_version`). Version 2: discrete
 * readings follow the per-metric source policy ([HealthSourcePolicy]) and sleep nights keep only the main episode
 * (naps are reported separately). Only the affected types are rebuilt from their stored rows; platform-aggregate types
 * keep their Health Connect totals.
 */
object HealthRollupRules {
    const val META_KEY = "rollup_rule_version"

    suspend fun upgradeIfNeeded(store: HealthDataStore, zone: ZoneId) {
        val current = store.meta(META_KEY)?.toIntOrNull() ?: 1
        if (current >= HealthSourcePolicy.ROLLUP_RULE_VERSION) return
        if (HealthSourcePolicy.policy == null) return // policy not loaded yet: try again on the next sync
        val ids = HealthSourcePolicy.policyTypeIds() + HealthDataType.SLEEP.id
        for (id in ids) {
            val type = HealthDataType.byId(id) ?: continue
            if (type.usesPlatformAggregate) continue
            val rows = store.samplesBetween(id, 0L, Long.MAX_VALUE)
            if (rows.isEmpty()) continue
            val days = rows.map { it.localDay }.toSet()
            store.replaceDailyRollups(id, days, HealthRollupMath.rebuildDaily(HealthTypeDescriptor.of(type), rows, zone.id))
        }
        store.setMeta(META_KEY, HealthSourcePolicy.ROLLUP_RULE_VERSION.toString())
    }
}
