package com.ayuvo.health.data.analytics.engine

import android.content.Context
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.int
import com.ayuvo.health.medications.logic.MedicationJson

/**
 * `assets/analytics/analytics_config.json` and `assets/analytics/source_policy.json`, byte copies of
 * `shared/analytics/analytics_config.json` and `shared/health/source_policy.json` (docs/health-analytics.md).
 * Held as plain JSON maps so the engine reads them exactly like the reference reads `cfg[...]`.
 */
class AnalyticsConfig(val root: JMap, val policy: JMap) {
    val configVersion: Int = root.int("config_version")

    companion object {
        const val ASSET_PATH = "analytics/analytics_config.json"
        const val POLICY_ASSET_PATH = "analytics/source_policy.json"

        @Volatile
        var active: AnalyticsConfig? = null

        @Suppress("UNCHECKED_CAST")
        fun parse(configText: String, policyText: String): AnalyticsConfig = AnalyticsConfig(
            AnalyticsMath.plain(MedicationJson.json.parseToJsonElement(configText)) as JMap,
            AnalyticsMath.plain(MedicationJson.json.parseToJsonElement(policyText)) as JMap
        )

        fun load(context: Context): AnalyticsConfig {
            active?.let { return it }
            val cfg = parse(
                context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() },
                context.assets.open(POLICY_ASSET_PATH).bufferedReader().use { it.readText() }
            )
            active = cfg
            return cfg
        }
    }
}
