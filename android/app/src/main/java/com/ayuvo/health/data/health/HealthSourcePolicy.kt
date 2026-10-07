package com.ayuvo.health.data.health

import com.ayuvo.health.data.analytics.engine.AnalyticsConfig
import com.ayuvo.health.data.analytics.engine.AnalyticsMath
import com.ayuvo.health.data.analytics.engine.JMap
import kotlin.math.abs

/**
 * Per-metric source handling for discrete daily rollups (`shared/health/source_policy.json`, docs/health-data.md §1.3).
 * The row choice is the same as `analytics_reference.source_select`:
 *  - cross-origin de-duplication: a Google Health row (origin 3, source `google_health:<pkg>`) is dropped when a row of
 *    another origin from `<pkg>` lies within `dedup_window_s` with a value within `dedup_value_tolerance`;
 *  - `single_best_source_per_day`: one source per day — wearable device types first, then the most samples, then the
 *    smaller source id — instead of averaging devices together.
 * Without a loaded policy (JVM tests that do not set one) rows pass through unchanged.
 */
object HealthSourcePolicy {
    /** Bumped when rollup rules change so every rollup rebuilds once (health_meta `rollup_rule_version`). */
    const val ROLLUP_RULE_VERSION = 2

    @Volatile
    var policy: JMap? = null
        get() = field ?: AnalyticsConfig.active?.policy

    @Suppress("UNCHECKED_CAST")
    fun policyTypeIds(): Set<String> = ((policy?.get("metrics") as? Map<String, Any?>) ?: emptyMap()).keys

    private fun strategy(p: JMap, typeId: String): String =
        ((p["metrics"] as? Map<*, *>)?.get(typeId) as? String) ?: (p["default_strategy"] as String)

    /** The rows the rollup should summarize for one discrete type and day. */
    fun selectRows(typeId: String, rows: List<HealthSampleRow>): List<HealthSampleRow> {
        val p = policy ?: return rows
        val withValue = rows.filter { !it.deleted && it.value != null }
            .sortedWith(compareBy<HealthSampleRow> { it.startMs }.then { a, b -> AnalyticsMath.CODE_POINT_ORDER.compare(a.id, b.id) })
        if (withValue.isEmpty()) return rows
        val win = (p["dedup_window_s"] as Number).toDouble() * 1000
        val tol = (p["dedup_value_tolerance"] as List<*>).map { (it as Number).toDouble() }
        val kept = ArrayList<HealthSampleRow>()
        for (r in withValue) {
            if (r.origin == ORIGIN_GOOGLE_HEALTH) {
                val pkg = r.sourceId.removePrefix(GOOGLE_HEALTH_SOURCE_PREFIX)
                val dup = withValue.any { o ->
                    o.origin != ORIGIN_GOOGLE_HEALTH && o.sourceId == pkg && abs(o.startMs - r.startMs) <= win &&
                        abs(o.value!! - r.value!!) <= maxOf(tol[0], tol[1] * abs(o.value))
                }
                if (dup) continue
            }
            kept += r
        }
        if (strategy(p, typeId) != SINGLE_BEST) return kept
        val wearable = (p["wearable_device_types"] as List<*>).map { (it as Number).toInt() }.toSet()
        val by = LinkedHashMap<String, MutableList<HealthSampleRow>>()
        for (r in kept) by.getOrPut(r.sourceId) { ArrayList() } += r
        val best = by.keys.sortedWith(
            compareBy<String> { s -> if (by.getValue(s).any { it.deviceType in wearable }) 0 else 1 }
                .thenByDescending { s -> by.getValue(s).sumOf { maxOf(1, it.count) } }
                .then(AnalyticsMath.CODE_POINT_ORDER)
        ).firstOrNull() ?: return kept
        return by.getValue(best)
    }

    const val SINGLE_BEST = "single_best_source_per_day"
    const val ORIGIN_GOOGLE_HEALTH = 3
    const val GOOGLE_HEALTH_SOURCE_PREFIX = "google_health:"
}
