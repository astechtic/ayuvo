package com.ayuvo.health.data.metrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsed `shared/metrics/metric_catalog.json` (docs/ui-structure.md §4). Pure Kotlin so the
 * reference port and its vector tests run on the JVM; the app loads the bundled asset copy
 * through [com.ayuvo.health.ui.metrics.MetricCatalog].
 */
data class CatalogDomain(
    val id: String,
    val title: String,
    val titleRes: String,
    val colourHex: String,
    val colourHexDark: String,
    val iconAndroid: String,
    val iconIos: String,
    val browseOrder: Int,
    val target: String
)

data class CatalogUnit(val canonical: String, val decimals: Int, val pref: String?)

data class CatalogMetric(
    val key: String,
    val domain: String,
    val title: String,
    val titleRes: String,
    val iconAndroid: String,
    val iconIos: String,
    val store: String,
    val field: String,
    val unit: CatalogUnit,
    val aggregation: String,
    val chartKind: String,
    val dayBucket: String,
    val ranges: List<String>,
    val goalSource: String,
    val defaultFavouriteOrder: Int?,
    val browseSection: String,
    val browseOrder: Int,
    val about: String
)

data class CatalogOverride(
    val id: String,
    val domain: String?,
    val goalSource: String?,
    val defaultFavouriteOrder: Int?,
    val browseHidden: Boolean,
    /** Optional per-metric icon; null falls back to the domain icon (docs/ui-structure.md §4). */
    val iconAndroid: String? = null,
    val iconIos: String? = null
)

data class CatalogBrowseSection(val id: String, val domain: String, val title: String, val order: Int)

data class MetricCatalogData(
    val domains: List<CatalogDomain>,
    val metrics: List<CatalogMetric>,
    val browseSections: List<CatalogBrowseSection>,
    val categoryDomains: Map<String, String>,
    val aggregationMap: Map<String, String>,
    val chartKindMap: Map<String, String>,
    val overrides: List<CatalogOverride>,
    val macroColours: Map<String, String>,
    val favouritesMax: Int,
    val favouritesPrefKey: String,
    val favouritesLegacyPrefKey: String,
    val dailyStepGoalDefault: Int,
    val dailyStepGoalMin: Int,
    val dailyStepGoalMax: Int
) {
    val domainById: Map<String, CatalogDomain> = domains.associateBy { it.id }
    val metricByKey: Map<String, CatalogMetric> = metrics.associateBy { it.key }
    val overrideById: Map<String, CatalogOverride> = overrides.associateBy { it.id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): MetricCatalogData {
            val root = json.parseToJsonElement(text) as JsonObject
            val health = root.o("health")
            val favourites = root.o("favourites")
            val stepGoal = root.o("prefs").o("dailyStepGoal")
            return MetricCatalogData(
                domains = root.arr("domains").map { d ->
                    d as JsonObject
                    CatalogDomain(
                        id = d.s("id"), title = d.s("title"), titleRes = d.s("title_res"),
                        colourHex = d.s("colour_hex"), colourHexDark = d.s("colour_hex_dark"),
                        iconAndroid = d.o("icon").s("android"), iconIos = d.o("icon").s("ios"), browseOrder = d.i("browse_order") ?: 0,
                        target = d.s("target")
                    )
                },
                metrics = root.arr("metrics").map { m ->
                    m as JsonObject
                    val unit = m.o("unit")
                    val fav = m["default_favourite"] as? JsonObject
                    CatalogMetric(
                        key = m.s("key"), domain = m.s("domain"), title = m.s("title"), titleRes = m.s("title_res"),
                        iconAndroid = m.o("icon").s("android"), iconIos = m.o("icon").s("ios"),
                        store = m.o("source").s("store"), field = m.o("source").s("field"),
                        unit = CatalogUnit(unit.s("canonical"), unit.i("decimals") ?: 0, unit.sOrNull("pref")),
                        aggregation = m.s("aggregation"), chartKind = m.s("chart_kind"), dayBucket = m.s("day_bucket"),
                        ranges = (m["ranges"] as JsonArray).map { (it as JsonPrimitive).content },
                        goalSource = m.s("goal_source"),
                        defaultFavouriteOrder = fav?.let { if (it.b("enabled")) it.i("order") else null },
                        browseSection = m.s("browse_section"), browseOrder = m.i("browse_order") ?: 0,
                        about = m.s("about")
                    )
                },
                browseSections = root.arr("browse_sections").map { b ->
                    b as JsonObject
                    CatalogBrowseSection(b.s("id"), b.s("domain"), b.s("title"), b.i("order") ?: 0)
                },
                categoryDomains = health.o("category_domains").strings(),
                aggregationMap = health.o("aggregation_map").strings(),
                chartKindMap = health.o("chart_kind_map").strings(),
                overrides = health.arr("overrides").map { o ->
                    o as JsonObject
                    val fav = o["default_favourite"] as? JsonObject
                    CatalogOverride(
                        id = o.s("id"), domain = o.sOrNull("domain"), goalSource = o.sOrNull("goal_source"),
                        defaultFavouriteOrder = fav?.let { if (it.b("enabled")) it.i("order") else null },
                        browseHidden = o.b("browse_hidden"),
                        iconAndroid = (o["icon"] as? JsonObject)?.sOrNull("android"),
                        iconIos = (o["icon"] as? JsonObject)?.sOrNull("ios")
                    )
                },
                macroColours = root.o("macro_colours").strings(),
                favouritesMax = favourites.i("max") ?: 12,
                favouritesPrefKey = favourites.s("pref_key"),
                favouritesLegacyPrefKey = favourites.s("legacy_pref_key"),
                dailyStepGoalDefault = stepGoal.i("default") ?: 10_000,
                dailyStepGoalMin = stepGoal.i("min") ?: 1_000,
                dailyStepGoalMax = stepGoal.i("max") ?: 50_000
            )
        }

        private fun JsonObject.o(k: String): JsonObject = this[k] as JsonObject
        private fun JsonObject.arr(k: String): JsonArray = this[k] as JsonArray
        private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        private fun JsonObject.s(k: String): String = prim(k)?.content ?: ""
        private fun JsonObject.sOrNull(k: String): String? = prim(k)?.takeIf { it.isString }?.content
        private fun JsonObject.i(k: String): Int? = prim(k)?.intOrNull
        private fun JsonObject.b(k: String): Boolean = prim(k)?.booleanOrNull ?: false
        private fun JsonObject.strings(): Map<String, String> =
            entries.associate { (k, v: JsonElement) -> k to (v as JsonPrimitive).content }
    }
}
