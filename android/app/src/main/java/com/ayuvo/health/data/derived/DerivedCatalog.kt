package com.ayuvo.health.data.derived

import android.content.Context
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.truthy
import kotlinx.serialization.json.JsonObject

/**
 * The derived-metric catalog from `assets/derived/derived_config.json` (docs/derived-metrics.md §2): what each
 * metric is called, how it is charted, which native Health type wins over it and what it depends on. Engines read
 * thresholds through [DerivedConfig]; the UI, settings and Coach read this.
 */
data class DerivedMetricInfo(
    val id: String,
    val category: String,
    val title: String,
    val unit: String,
    val decimals: Int,
    val function: String,
    val field: String,
    val value2Field: String?,
    val value3Field: String?,
    /** Health registry type that wins over this metric on Android, if any. */
    val nativeTypeId: String?,
    val requires: List<String>,
    val chartKind: String,
    val aggregation: String,
    val androidIcon: String,
    val about: String,
    val method: String,
    val citation: String
) {
    /** Clock metrics store minutes after 12:00 of the day before the wake day. */
    val isClock: Boolean get() = unit == "clock"
}

class DerivedCatalog(
    val algoVersion: Int,
    val categories: List<String>,
    val disclaimer: String,
    val metrics: List<DerivedMetricInfo>
) {
    val byId: Map<String, DerivedMetricInfo> = metrics.associateBy { it.id }

    fun metricsIn(category: String): List<DerivedMetricInfo> = metrics.filter { it.category == category }

    /** Metrics that list [id] in `requires`, directly or through another metric. */
    fun dependentsOf(id: String): List<DerivedMetricInfo> {
        val out = mutableListOf<DerivedMetricInfo>()
        var frontier = setOf(id)
        while (frontier.isNotEmpty()) {
            val next = metrics.filter { m -> m.requires.any { it in frontier } && m !in out }
            out += next
            frontier = next.map { it.id }.toSet()
        }
        return out
    }

    /** Ids computed with the given switches. */
    fun enabledIds(masterOn: Boolean, disabled: Set<String>): Set<String> =
        if (!masterOn) emptySet() else metrics.map { it.id }.filter { it !in disabled }.toSet()

    companion object {
        const val ASSET_PATH = "derived/derived_config.json"

        @Volatile
        private var cached: DerivedCatalog? = null

        /** The catalog once some caller has loaded it through [get] (icons and colours read it without a Context). */
        fun loaded(): DerivedCatalog? = cached

        fun get(context: Context): DerivedCatalog = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }).also { cached = it }
        }

        fun parse(text: String): DerivedCatalog {
            val root = MedicationJson.json.parseToJsonElement(text) as JsonObject
            val metrics = root.arr("metrics").orEmpty().map { e ->
                val o = e as JsonObject
                DerivedMetricInfo(
                    id = o.str("id")!!,
                    category = o.str("category")!!,
                    title = o.str("title")!!,
                    unit = o.str("unit").orEmpty(),
                    decimals = o.int("decimals") ?: 0,
                    function = o.str("function")!!,
                    field = o.str("field")!!,
                    value2Field = o.str("value2_field"),
                    value3Field = o.str("value3_field"),
                    nativeTypeId = o.objOrNull("native")?.str("android"),
                    requires = MedicationJson.strings(o["requires"]),
                    chartKind = o.str("chart_kind") ?: "line",
                    aggregation = o.str("aggregation") ?: "average",
                    androidIcon = o.objOrNull("icon")?.str("android").orEmpty(),
                    about = o.str("about").orEmpty(),
                    method = o.str("method").orEmpty(),
                    citation = o.str("citation").orEmpty()
                ).takeIf { o.truthy("default_enabled") || !o.containsKey("default_enabled") } ?: error("metric ${o.str("id")} must be on by default")
            }
            return DerivedCatalog(
                algoVersion = root.int("algo_version") ?: 0,
                categories = MedicationJson.strings(root["categories"]),
                disclaimer = root.str("disclaimer").orEmpty(),
                metrics = metrics
            )
        }

        /**
         * The Health registry category whose Browse domain lists a derived category (energy sits with Activity);
         * the metric catalog's `category_domains` then gives the domain.
         */
        fun healthCategoryOf(category: String): String = when (category) {
            "energy" -> "activity"
            "heart", "sleep", "activity", "mobility", "hearing", "body", "nutrition" -> category
            else -> "other"
        }

        fun categoryTitle(category: String): String = when (category) {
            "heart" -> "Heart"
            "sleep" -> "Sleep"
            "activity" -> "Activity"
            "energy" -> "Energy"
            "mobility" -> "Mobility"
            "hearing" -> "Hearing"
            "body" -> "Body"
            "nutrition" -> "Nutrition"
            else -> category.replaceFirstChar { it.uppercase() }
        }
    }
}
