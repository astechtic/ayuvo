package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.processing.RecordJson.double
import com.ayuvo.health.records.processing.RecordJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** `shared/records/record_types.json` (docs/health-records.md §10), bundled as `assets/records/record_types.json`. */
data class RecordTypesConfig(
    val threshold: Double,
    val margin: Double,
    val types: List<TypeRules>
) {
    enum class Scope { HEAD, BODY }

    data class Rule(val scope: Scope, val pattern: Regex, val weight: Double)

    data class TypeRules(val type: RecordType, val category: RecordCategory, val rules: List<Rule>)

    fun rulesFor(type: RecordType): TypeRules? = types.firstOrNull { it.type == type }

    companion object {
        const val ASSET_PATH = "records/record_types.json"

        /**
         * record_types.json patterns use `\\b \\d \\s \\w` with Python 3 str (Unicode) semantics.
         * Desktop JVMs need UNICODE_CHARACTER_CLASS for that; Android's ICU engine is Unicode-aware
         * already and may reject the flag, hence the fallback.
         */
        fun compile(pattern: String): Regex =
            runCatching { java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).toRegex() }
                .getOrElse { Regex(pattern) }

        fun parse(json: String): RecordTypesConfig {
            val root = RecordJson.parseObject(json) ?: error("record_types.json is not a JSON object")
            val types = (root["types"] as? JsonArray).orEmpty().map { element ->
                val o = element as JsonObject
                val id = o.string("id") ?: error("type without id")
                TypeRules(
                    type = RecordType.entries.firstOrNull { it.raw == id } ?: error("unknown record type $id"),
                    category = RecordCategory.entries.firstOrNull { it.raw == o.string("category") } ?: RecordCategory.OTHER,
                    rules = (o["rules"] as? JsonArray).orEmpty().map { r ->
                        val rule = r as JsonObject
                        Rule(
                            scope = if (rule.string("scope") == "head") Scope.HEAD else Scope.BODY,
                            pattern = compile(rule.string("pattern") ?: error("rule without pattern")),
                            weight = rule.double("weight") ?: 0.0
                        )
                    }
                )
            }
            return RecordTypesConfig(root.double("threshold") ?: 4.0, root.double("margin") ?: 2.0, types)
        }
    }
}
