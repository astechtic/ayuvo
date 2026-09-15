package com.ayuvo.health.records.processing

import com.ayuvo.health.records.processing.RecordJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lab unit tokens (`shared/records/units.json`, bundled as `assets/records/units.json`).
 * Matching follows the reference `match_unit`: folded variants, longest folded first then by
 * folded string, a prefix of the folded text followed by end, space or `)]},;`.
 */
class UnitsCatalog(entries: List<Pair<String, List<String>>>) {

    data class Variant(val folded: String, val canonical: String)

    val variants: List<Variant> = entries
        .flatMap { (canonical, variants) -> (variants + canonical).map { Variant(RecordText.fold(it), canonical) } }
        .filter { it.folded.isNotEmpty() }
        .distinctBy { it.folded }
        .sortedWith(compareByDescending<Variant> { it.folded.length }.thenBy { it.folded })

    val canonicals: List<String> = entries.map { it.first }

    /** Alternation of folded variants (longest first) for regex use on folded text. */
    val pattern: String = variants.joinToString("|") { Regex.escape(it.folded) }

    /** The variant starting at [index] of folded [text], or null. */
    fun matchAt(text: String, index: Int): Variant? {
        for (v in variants) {
            if (!text.startsWith(v.folded, index)) continue
            val end = index + v.folded.length
            if (end == text.length || text[end] in TERMINATORS) return v
        }
        return null
    }

    /** Canonical spelling for a printed token, or null. */
    fun canonical(token: String): String? = RecordText.fold(token).let { f -> variants.firstOrNull { it.folded == f }?.canonical }

    companion object {
        const val ASSET_PATH = "records/units.json"
        private const val TERMINATORS = " )]},;"

        val DEFAULT = UnitsCatalog(
            listOf(
                "g/dL", "g/L", "mg/dL", "mg/L", "µg/dL", "mcg/dL", "ng/mL", "pg/mL", "mmol/L", "µmol/L", "mEq/L",
                "IU/L", "U/L", "mIU/L", "µIU/mL", "%", "fL", "pg", "cells/cumm", "/cumm", "cells/µL", "10^3/µL",
                "10^6/µL", "lakhs/cumm", "million/cumm", "x10^9/L", "x10^12/L", "mm/hr", "sec", "ratio", "mL/min/1.73m²"
            ).map { it to listOf(it) }
        )

        /**
         * The catalogue used by callers without an explicit one (the AI validator). [parseOrDefault]
         * installs the bundled `units.json` here when the pipeline builds its rules.
         */
        @Volatile
        var active: UnitsCatalog = DEFAULT

        fun parseOrDefault(json: String?): UnitsCatalog =
            (if (json.isNullOrBlank()) DEFAULT else runCatching { parse(json) }.getOrDefault(DEFAULT)).also { active = it }

        fun parse(json: String): UnitsCatalog {
            val root = RecordJson.parseObject(json) ?: error("units.json is not an object")
            val units = (root["units"] as? JsonArray).orEmpty().mapNotNull { e ->
                when (e) {
                    is JsonObject -> {
                        val canonical = e.string("canonical") ?: return@mapNotNull null
                        val variants = (e["variants"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                        canonical to (variants.ifEmpty { listOf(canonical) })
                    }
                    is JsonPrimitive -> e.content to listOf(e.content)
                    else -> null
                }
            }
            return UnitsCatalog(units)
        }
    }
}
