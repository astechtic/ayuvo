package com.ayuvo.health.nutrients

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One age band of the reference (`19-30`, `31-50`, `51-70`, `71+`); [max] null = no upper bound. */
data class NutrientAgeBand(val id: String, val min: Int, val max: Int?)

/** `{male: {band: v}, female: {band: v}}` of the reference. */
data class NutrientSexTable(val male: Map<String, Double>, val female: Map<String, Double>) {
    fun of(sex: String, band: String): Double = (if (sex == "male") male else female).getValue(band)
}

/** `recommended`: RDA or AI by sex and band. */
data class NutrientRecommended(val kind: String, val table: NutrientSexTable)

/** `upper_limit`: the UL by sex and band, with its [scope] (`all_sources`, `supplements_only`, …). */
data class NutrientUpperLimit(val scope: String, val note: String?, val table: NutrientSexTable)

/** `limit`: `fixed` ([value]) or `pct_energy` ([pct] of the calorie goal ÷ [kcalPerUnit]). */
data class NutrientLimit(val kind: String, val value: Double?, val pct: Double?, val kcalPerUnit: Double?, val basis: String?)

/** `iu`: vitamin D has [mcgPerIu]; vitamin A / E need a form from [forms] (insertion order kept). */
data class NutrientIu(val unit: String, val mcgPerIu: Double?, val forms: Map<String, Double>?)

data class NutrientSpec(
    val key: String,
    val name: String,
    val slug: String,
    val unit: String,
    val style: String,
    val category: String,
    val summary: String,
    val notes: List<String>,
    val recommended: NutrientRecommended?,
    val upperLimit: NutrientUpperLimit?,
    val limit: NutrientLimit?,
    val iu: NutrientIu?,
    val massForms: Map<String, Double>?,
    val sourceIds: List<String>,
    /**
     * True for the 23 nutrients the FOOD LOG records (docs/nutrients.md §3). It never limits
     * supplements: every reference entry can be a supplement nutrient, an AI label key and a
     * `nutrient:<key>` chart; an untracked one's food part is always null.
     */
    val appTracked: Boolean = true,
    /** The health registry id of the same nutrient (`dietary_vitamin_d`); null when there is none. */
    val healthType: String? = null
)

data class NutrientSource(
    val id: String,
    val title: String,
    val publisher: String,
    val url: String,
    val checked: String
)

data class SportsSupplementSpec(val key: String, val unit: String)

/**
 * Parsed `shared/nutrients/nutrient_reference.json` (docs/nutrients.md). Pure Kotlin so the
 * reference port runs on the JVM; the app installs the bundled asset copy as [active] at start.
 */
class NutrientReference(
    val version: Int,
    val checked: String,
    val defaultBand: String,
    val ageBands: List<NutrientAgeBand>,
    val nutrients: List<NutrientSpec>,
    val sports: List<SportsSupplementSpec>,
    val unitAliases: Map<String, String>,
    val amountPerUnitMax: Map<String, Double>,
    val sources: Map<String, NutrientSource>,
    val populationNote: String
) {
    /** Every reference entry (tracked or not): reference lines, default goals and About read these. */
    val byKey: Map<String, NutrientSpec> = nutrients.associateBy { it.key }
    val sportsByKey: Map<String, SportsSupplementSpec> = sports.associateBy { it.key }

    /** The `app_tracked` entries in reference order (food log, goals, Nutrition Details' always-shown rows). */
    val trackedNutrients: List<NutrientSpec> = nutrients.filter { it.appTracked }

    /** Reference entries by their health registry id (`dietary_copper` → copper). */
    val byHealthType: Map<String, NutrientSpec> = nutrients.filter { it.healthType != null }.associateBy { it.healthType!! }

    /** `app_tracked` reference keys in reference order, then the sports supplement keys (the food log's keys). */
    val foodKeys: List<String> = trackedNutrients.map { it.key } + sports.map { it.key }

    /**
     * `SUPPLEMENT_KEYS`: every reference key (all styles, `app_tracked` or not) in reference order,
     * then the sports keys. Supplement pickers, AI label keys, archive validation and
     * `nutrient_metrics` use this list (docs/nutrients.md §3).
     */
    val supplementKeys: List<String> = nutrients.map { it.key } + sports.map { it.key }

    /** Canonical unit of a supplement nutrient (any reference entry or sports supplement); null for unknown keys. */
    fun unitOf(key: String): String? = byKey[key]?.unit ?: sportsByKey[key]?.unit

    /** `food_tracked(key)`: `app_tracked` for reference entries, true for sports, false for unknown keys. */
    fun foodTracked(key: String): Boolean = byKey[key]?.appTracked ?: (key in sportsByKey)

    companion object {
        const val ASSET_PATH = "nutrients/nutrient_reference.json"
        const val PROMPT_ASSET_PATH = "nutrients/ai_supplement_label.md"

        /** Installed once at app start (AppContainer) and by the unit tests. */
        @Volatile
        var active: NutrientReference? = null

        /** [active], or an error that names the missing install (never silently empty). */
        val current: NutrientReference
            get() = active ?: error("NutrientReference.active is not installed")

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): NutrientReference {
            val root = json.parseToJsonElement(text) as JsonObject
            return NutrientReference(
                version = root.i("version") ?: 1,
                checked = root.s("checked") ?: "",
                defaultBand = root.s("default_band")!!,
                ageBands = root.arr("age_bands").map { b ->
                    b as JsonObject
                    NutrientAgeBand(b.s("id")!!, b.i("min")!!, b.i("max"))
                },
                nutrients = root.arr("nutrients").map { n -> spec(n as JsonObject) },
                sports = root.arr("sports_supplements").map { s ->
                    s as JsonObject
                    SportsSupplementSpec(s.s("key")!!, s.s("unit")!!)
                },
                unitAliases = (root["unit_aliases"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as JsonPrimitive).content },
                amountPerUnitMax = (root["amount_per_unit_max"] as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).doubleOrNull!! },
                sources = (root["sources"] as JsonObject).mapValues { (id, v) ->
                    v as JsonObject
                    NutrientSource(id, v.s("title") ?: "", v.s("publisher") ?: "", v.s("url") ?: "", v.s("checked") ?: "")
                },
                populationNote = root.s("population_note") ?: ""
            )
        }

        private fun spec(n: JsonObject): NutrientSpec = NutrientSpec(
            key = n.s("key")!!,
            name = n.s("name") ?: "",
            slug = n.s("slug") ?: "",
            unit = n.s("unit")!!,
            style = n.s("style")!!,
            category = n.s("category") ?: "",
            summary = n.s("summary") ?: "",
            notes = (n["notes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            recommended = (n["recommended"] as? JsonObject)?.let { NutrientRecommended(it.s("kind") ?: "", sexTable(it)) },
            upperLimit = (n["upper_limit"] as? JsonObject)?.let { NutrientUpperLimit(it.s("scope") ?: "all_sources", it.s("note"), sexTable(it)) },
            limit = (n["limit"] as? JsonObject)?.let {
                NutrientLimit(it.s("kind")!!, it.d("value"), it.d("pct"), it.d("kcal_per_unit"), it.s("basis"))
            },
            iu = (n["iu"] as? JsonObject)?.let { iu ->
                NutrientIu(
                    unit = iu.s("unit")!!,
                    mcgPerIu = iu.d("mcg_per_iu"),
                    forms = (iu["forms"] as? JsonObject)?.let(::doubles)
                )
            },
            massForms = (n["mass_forms"] as? JsonObject)?.let(::doubles),
            sourceIds = (n["source_ids"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content },
            appTracked = n.b("app_tracked") ?: true,
            healthType = n.s("health_type")
        )

        private fun sexTable(o: JsonObject) = NutrientSexTable(doubles(o["male"] as JsonObject), doubles(o["female"] as JsonObject))

        private fun doubles(o: JsonObject): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            for ((k, v) in o) (v as? JsonPrimitive)?.doubleOrNull?.let { out[k] = it }
            return out
        }

        private fun JsonObject.arr(k: String): JsonArray = this[k] as JsonArray
        private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        private fun JsonObject.s(k: String): String? = prim(k)?.takeIf { it.isString }?.content
        private fun JsonObject.i(k: String): Int? = prim(k)?.takeIf { !it.isString }?.intOrNull
        private fun JsonObject.d(k: String): Double? = prim(k)?.takeIf { !it.isString }?.doubleOrNull
        private fun JsonObject.b(k: String): Boolean? = prim(k)?.takeIf { !it.isString }?.booleanOrNull
    }
}
