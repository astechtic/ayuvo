package com.ayuvo.health.records.analytes

import com.ayuvo.health.records.model.AnalyteMethod
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordJson.string
import com.ayuvo.health.records.processing.RecordText
import com.ayuvo.health.records.processing.UnitsCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.floor

/** One analyte unit: canonical = value × [factor] + [offset] (§20). */
data class AnalyteUnit(val unit: String, val factor: Double, val offset: Double)

/** One `shared/records/analytes.json` entry (§20). */
data class Analyte(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val panels: List<String>,
    val category: String?,
    val canonicalUnit: String?,
    val units: List<AnalyteUnit>,
    val loinc: String? = null,
    val metricRegistryId: String? = null,
    val kind: String = "numeric",
    val decimals: Int? = null
)

/** Reference `map_analyte` result. */
data class AnalyteMapping(
    val analyteId: String?,
    val method: AnalyteMethod?,
    val key: String? = null,
    val normalizedName: String = "",
    val candidates: List<String> = emptyList()
)

/** Reference `convert_unit` result; status converted | unmapped | no_value | unit_missing | unit_unknown. */
data class CanonicalValue(val unit: String?, val value: Double?, val canonicalUnit: String?, val status: String)

/**
 * Analyte catalogue (`assets/records/analytes.json`, a byte copy of `shared/records/analytes.json`),
 * ported from `scripts/records_reference.py` §20: test-name keys, alias mapping with shared-alias
 * disambiguation, panel detection and unit conversion. Pure; never fuzzy.
 */
class AnalyteCatalog(val analytes: List<Analyte>, val version: String? = null, private val unitsOverride: UnitsCatalog? = null) {

    private val units: UnitsCatalog get() = unitsOverride ?: UnitsCatalog.active

    val byId: Map<String, Analyte> = analytes.associateBy { it.id }

    /** Alias key (analyte words joined) → ids listing it, in file order. */
    private val index: Map<String, List<String>> = LinkedHashMap<String, MutableList<String>>().also { idx ->
        for (a in analytes) for (alias in a.aliases) {
            val ids = idx.getOrPut(analyteWords(alias).joinToString(" ")) { mutableListOf() }
            if (a.id !in ids) ids += a.id
        }
    }

    fun analyte(id: String?): Analyte? = id?.let { byId[it] }

    fun displayName(id: String?): String? = analyte(id)?.displayName

    fun decimals(id: String?): Int = analyte(id)?.decimals ?: 2

    /** Reference `canonical_unit_spelling`. */
    fun canonicalUnitSpelling(unit: String?): String? {
        if (unit == null) return null
        val p = RecordText.pfold(unit).replace(WS, " ").trim(' ')
        if (p.isEmpty()) return null
        val m = units.matchAt(RecordText.fold(p), 0)
        if (m != null && m.folded.length == p.length) return m.canonical
        return p
    }

    private fun analyteUnits(e: Analyte): Set<String> {
        val us = e.units.map { it.unit }.toMutableSet()
        if (e.canonicalUnit == null && e.kind == "numeric") us += "ratio"
        return us
    }

    private fun disambiguate(ids: List<String>, unit: String?, qualitative: Boolean?, panels: Set<String>): String? {
        var c = ids
        val steps = mutableListOf<(String) -> Boolean>()
        if (unit != null) steps += { i -> unit in analyteUnits(byId.getValue(i)) }
        if (qualitative != null) steps += { i -> byId.getValue(i).kind == (if (qualitative) "qualitative" else "numeric") }
        steps += { i -> byId.getValue(i).panels.any { it in panels } }
        if (panels.none { it in URINE_PANELS }) steps += { i -> byId.getValue(i).category != "urine" }
        for (keep in steps) {
            val k = c.filter(keep)
            if (k.size == 1) return k[0]
            if (k.size >= 2) c = k
        }
        return null
    }

    /** Reference `map_analyte`. */
    fun map(
        name: String,
        panels: Collection<String>? = null,
        userAliases: Map<String, String>? = null,
        unit: String? = null,
        qualitative: Boolean? = null
    ): AnalyteMapping {
        val keys = testNameKeys(name)
        val normalized = normalizeTestName(name)
        val ua = userAliases.orEmpty()
        for (k in keys) {
            val aid = ua[k]
            if (aid != null && aid in byId) return AnalyteMapping(aid, AnalyteMethod.USER_ALIAS, k, normalized)
        }
        var u = canonicalUnitSpelling(unit)
        if (u == null && '%' in name) u = "%"
        val pset = panels.orEmpty().toSet()
        for (k in keys) {
            val ids = index[k] ?: continue
            if (ids.isEmpty()) continue
            val aid = if (ids.size == 1) ids[0] else disambiguate(ids, u, qualitative, pset)
            return if (aid == null) AnalyteMapping(null, null, k, normalized, ids.toList())
            else AnalyteMapping(aid, AnalyteMethod.CATALOG, k, normalized)
        }
        return AnalyteMapping(null, null, null, normalized)
    }

    /** Reference `detect_panels`: heading phrases in [texts] or ≥ 3 distinct mapped analytes; PANEL_IDS order. */
    fun detectPanels(texts: List<String>?, testNames: List<String>?): List<String> {
        val found = HashSet<String>()
        for (t in texts.orEmpty()) {
            val s = " " + analyteWords(t).joinToString(" ") + " "
            for ((panel, phrases) in PANEL_KEYWORDS) if (phrases.any { s.contains(" $it ") }) found += panel
        }
        val counts = LinkedHashMap<String, Int>()
        val seen = HashSet<String>()
        for (n in testNames.orEmpty()) {
            val m = map(n)
            if (m.method == AnalyteMethod.CATALOG && seen.add(m.analyteId!!)) {
                for (p in byId.getValue(m.analyteId).panels) counts[p] = (counts[p] ?: 0) + 1
            }
        }
        for ((p, c) in counts) if (c >= PANEL_MIN_ANALYTES) found += p
        return PANEL_IDS.filter { it in found }
    }

    /** Reference `convert_unit`. */
    fun convert(analyteId: String?, value: Double?, unit: String?): CanonicalValue {
        val u = canonicalUnitSpelling(unit)
        val e = analyteId?.let { byId[it] } ?: return CanonicalValue(u, null, null, "unmapped")
        if (value == null) return CanonicalValue(u, null, null, "no_value")
        var factor: Double? = null
        var offset: Double? = null
        if (e.canonicalUnit == null && e.kind == "numeric") {
            if (u == null || u == "ratio") { factor = 1.0; offset = 0.0 }
        } else if (u == null) {
            return CanonicalValue(null, null, null, "unit_missing")
        } else {
            e.units.firstOrNull { it.unit == u }?.let { factor = it.factor; offset = it.offset }
        }
        val f = factor ?: return CanonicalValue(u, null, null, "unit_unknown")
        return CanonicalValue(u, round4(value * f + (offset ?: 0.0)), e.canonicalUnit, "converted")
    }

    /** Reference `_convert_between`: both units listed, round4; else null. */
    fun convertBetween(analyteId: String?, value: Double?, fromUnit: String?, toUnit: String?): Double? {
        val e = analyte(analyteId) ?: return null
        if (value == null || fromUnit == null || toUnit == null) return null
        val f = e.units.associateBy { it.unit }
        val a = f[fromUnit] ?: return null
        val b = f[toUnit] ?: return null
        return round4((value * a.factor + a.offset - b.offset) / b.factor)
    }

    /** Converts a canonical value back to a listed [unit] (trend unit toggle); null when not listed. */
    fun fromCanonical(analyteId: String?, canonical: Double?, unit: String?): Double? {
        val e = analyte(analyteId) ?: return null
        val entry = e.units.firstOrNull { it.unit == unit } ?: return null
        if (canonical == null || entry.factor == 0.0) return null
        return (canonical - entry.offset) / entry.factor
    }

    /** Case-insensitive search over display names and aliases (analyte picker). */
    fun search(query: String, limit: Int = 50): List<Analyte> {
        val q = analyteWords(query).joinToString(" ")
        if (q.isEmpty()) return analytes.sortedBy { it.displayName.lowercase() }.take(limit)
        val scored = analytes.mapNotNull { a ->
            val names = (listOf(a.displayName) + a.aliases).map { analyteWords(it).joinToString(" ") }
            val rank = when {
                names.any { it == q } -> 0
                names.any { it.startsWith(q) } -> 1
                names.any { n -> n.split(' ').any { it.startsWith(q) } } -> 2
                names.any { it.contains(q) } -> 3
                else -> return@mapNotNull null
            }
            rank to a
        }
        return scored.sortedWith(compareBy<Pair<Int, Analyte>>({ it.first }, { it.second.displayName.lowercase() })).map { it.second }.take(limit)
    }

    // -- §23 query aliases ------------------------------------------------------------------------

    private var queryAliasCache: Pair<Map<List<String>, List<String>>, Int>? = null

    /** Reference `_q_alias_table` with the parser's [reserved] words. */
    fun queryAliases(reserved: Set<String>): Pair<Map<List<String>, List<String>>, Int> {
        queryAliasCache?.let { return it }
        val table = LinkedHashMap<List<String>, MutableList<String>>()
        val res = reserved + METHOD_WORDS
        for (e in analytes) for (a in e.aliases) {
            for (ws in listOf(RecordText.words(RecordText.fold(a)), analyteWords(a))) {
                if (ws.joinToString(" ").length <= 1 || ws.all { it in res }) continue
                val ids = table.getOrPut(ws) { mutableListOf() }
                if (e.id !in ids) ids += e.id
            }
        }
        val out = table to (table.keys.maxOfOrNull { it.size } ?: 0)
        queryAliasCache = out
        return out
    }

    /** Reference `_q_resolve`. */
    fun queryResolve(ids: List<String>, unit: String?): String? {
        var c = ids
        val filters = listOf<(String) -> Boolean>(
            { i -> unit != null && unit in analyteUnits(byId.getValue(i)) },
            { i -> byId.getValue(i).category != "urine" },
            { i -> byId.getValue(i).kind == "numeric" }
        )
        for (keep in filters) {
            if (c.size > 1) {
                val k = c.filter(keep)
                if (k.isNotEmpty()) c = k
            }
        }
        return if (c.size == 1) c[0] else null
    }

    companion object {
        const val ASSET_PATH = "records/analytes.json"

        val EMPTY = AnalyteCatalog(emptyList())

        private val WS = Regex("[ \t\n]+")

        val PANEL_IDS = listOf(
            "cbc", "iron_studies", "vitamins", "diabetes", "lipid", "lft", "kft", "electrolytes", "thyroid",
            "cardiac", "coagulation", "hormones", "urine_routine", "urine_albumin", "serology", "pancreas"
        )
        val URINE_PANELS = setOf("urine_routine", "urine_albumin")

        val METHOD_WORDS: Set<String> = setOf(
            "serum", "plasma", "blood", "whole", "venous", "capillary", "edta", "fluoride", "heparin",
            "calculated", "calc", "derived", "measured", "method", "automated", "auto", "analyzer", "analyser",
            "hplc", "ifcc", "ngsp", "dcct", "clia", "eclia", "cmia", "cia", "elisa", "elfa", "ria", "ise",
            "turbidimetric", "turbidimetry", "immunoturbidimetric", "immunoturbidimetry", "nephelometric", "nephelometry",
            "photometric", "photometry", "colorimetric", "colorimetry", "spectrophotometric", "spectrophotometry",
            "enzymatic", "kinetic", "jaffe", "jaffes", "westergren", "wintrobe", "ckd", "epi", "mdrd",
            "level", "levels", "conc", "concentration"
        )
        private val METHOD_LEADING = setOf("s", "sr", "se", "ser")

        val PANEL_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "cbc" to listOf("complete blood count", "cbc", "hemogram", "haemogram", "full blood count", "fbc", "blood count"),
            "iron_studies" to listOf("iron studies", "iron profile", "iron panel", "anemia profile", "anaemia profile"),
            "vitamins" to listOf("vitamin profile", "vitamin panel", "vitamin b12", "vitamin d"),
            "diabetes" to listOf("diabetes profile", "diabetic profile", "diabetes panel", "glucose tolerance", "blood sugar", "hba1c", "glycated", "glycosylated"),
            "lipid" to listOf("lipid", "lipids", "lipid profile", "lipid panel"),
            "lft" to listOf("liver function", "lft", "hepatic function", "liver panel", "liver profile"),
            "kft" to listOf("kidney function", "renal function", "kft", "rft", "renal profile", "kidney profile", "renal panel"),
            "electrolytes" to listOf("electrolyte", "electrolytes"),
            "thyroid" to listOf("thyroid", "tft"),
            "cardiac" to listOf("cardiac", "troponin"),
            "coagulation" to listOf("coagulation", "prothrombin", "pt inr"),
            "hormones" to listOf("hormone", "hormones", "hormonal", "fertility", "pcos"),
            "urine_routine" to listOf("urine", "urinalysis", "cue"),
            "urine_albumin" to listOf("microalbumin", "albumin creatinine ratio", "acr", "urine albumin"),
            "serology" to listOf("serology", "dengue", "widal", "hiv", "hbsag", "viral markers"),
            "pancreas" to listOf("amylase", "lipase", "pancreatic")
        )
        const val PANEL_MIN_ANALYTES = 3

        /** Half-up to 4 decimals. */
        fun round4(x: Double): Double = floor(x * 10000 + 0.5 + 1e-9) / 10000.0

        /** Reference `analyte_words`: §8.1 words, runs of ≥ 2 one-letter ASCII words joined. */
        fun analyteWords(s: String?): List<String> {
            val out = mutableListOf<String>()
            val run = mutableListOf<String>()
            fun flush() {
                if (run.size >= 2) out += run.joinToString("") else out.addAll(run)
                run.clear()
            }
            for (w in RecordText.words(RecordText.fold(s ?: ""))) {
                if (w.length == 1 && w[0] in 'a'..'z') {
                    run += w
                    continue
                }
                flush()
                out += w
            }
            flush()
            return out
        }

        fun stripMethodWords(ws: List<String>): List<String> {
            val out = ws.filterIndexed { i, w -> !(w in METHOD_WORDS || (i == 0 && w in METHOD_LEADING)) }
            return out.ifEmpty { ws.toList() }
        }

        /** Reference `_strip_brackets`. */
        fun stripBrackets(f: String): String {
            val out = StringBuilder()
            var close: Char? = null
            var opener: Char? = null
            var depth = 0
            for (ch in f) {
                if (close == null) {
                    if (ch == '(' || ch == '[') {
                        opener = ch
                        close = if (ch == '(') ')' else ']'
                        depth = 1
                        out.append(' ')
                    } else {
                        out.append(ch)
                    }
                } else if (ch == opener) {
                    depth++
                } else if (ch == close) {
                    depth--
                    if (depth == 0) close = null
                }
            }
            return out.toString()
        }

        /** Reference `test_name_keys`. */
        fun testNameKeys(name: String?): List<String> {
            val f = RecordText.fold(name ?: "")
            val k1 = analyteWords(f)
            val k2 = analyteWords(stripBrackets(f))
            val keys = mutableListOf<String>()
            // Brackets go before method words: "Sr. Creatinine (Jaffe)" keeps "sr creatinine".
            for (ws in listOf(k1, k2, stripMethodWords(k1), stripMethodWords(k2))) {
                val k = ws.joinToString(" ")
                if (k.isNotEmpty() && k !in keys) keys += k
            }
            return keys
        }

        /** Reference `normalize_test_name` (the `analyte_user_aliases` key). */
        fun normalizeTestName(name: String?): String = stripMethodWords(analyteWords(name)).joinToString(" ")

        /** Kept for callers of the Phase 3 draft API. */
        fun normalizeName(name: String?): String = normalizeTestName(name)

        fun parseOrEmpty(json: String?): AnalyteCatalog =
            if (json.isNullOrBlank()) EMPTY else runCatching { parse(json) }.getOrDefault(EMPTY)

        fun parse(json: String, units: UnitsCatalog? = null): AnalyteCatalog {
            val root = RecordJson.json.parseToJsonElement(json)
            val (array, version) = when (root) {
                is JsonArray -> root to null
                is JsonObject -> (root["analytes"] as? JsonArray ?: JsonArray(emptyList())) to (root["version"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                else -> JsonArray(emptyList()) to null
            }
            val list = array.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.string("id") ?: return@mapNotNull null
                Analyte(
                    id = id,
                    displayName = o.string("display_name") ?: id,
                    aliases = (o["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                    panels = (o["panels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                    category = o.string("category"),
                    canonicalUnit = o.string("canonical_unit"),
                    units = (o["units"] as? JsonArray).orEmpty().mapNotNull { u ->
                        val uo = u as? JsonObject ?: return@mapNotNull null
                        AnalyteUnit(
                            uo.string("unit") ?: return@mapNotNull null,
                            (uo["factor"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
                            (uo["offset"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        )
                    },
                    loinc = o.string("loinc"),
                    metricRegistryId = o.string("metric_registry_id"),
                    kind = o.string("kind") ?: "numeric",
                    decimals = (o["decimals"] as? JsonPrimitive)?.intOrNull
                )
            }
            return AnalyteCatalog(list, version, units)
        }

        @Volatile
        var active: AnalyteCatalog = EMPTY
    }
}
