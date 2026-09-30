package com.ayuvo.health.models

import java.text.Normalizer
import com.ayuvo.health.R
import com.ayuvo.health.l10n.AppText

enum class AllergenAssessment {
    DECLARED_ALLERGEN_MATCH,
    MAY_CONTAIN,
    POSSIBLE_ALLERGEN,
    UNABLE_TO_ASSESS
}

data class AllergenAnalysis(
    val assessment: AllergenAssessment,
    val matchedSensitivities: List<String> = emptyList(),
    val evidence: List<String> = emptyList()
) {
    val summary: String
        get() = buildString {
            append(assessment.displayName)
            if (matchedSensitivities.isNotEmpty()) {
                append(": ")
                append(matchedSensitivities.joinToString(", "))
            }
        }

    companion object {
        fun evaluate(entry: FoodEntry, sensitivities: List<String>): AllergenAnalysis {
            val configured = sensitivities.map(::normalize).filter(String::isNotEmpty)
            if (configured.isEmpty()) return AllergenAnalysis(AllergenAssessment.UNABLE_TO_ASSESS)

            val sources = listOf(
                Triple(AllergenAssessment.DECLARED_ALLERGEN_MATCH, "Declared product allergens", entry.productMetadata?.allergens.orEmpty()),
                Triple(AllergenAssessment.MAY_CONTAIN, "Trace warning", entry.productMetadata?.traces.orEmpty()),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Ingredient label", listOfNotNull(entry.productMetadata?.ingredientsText)),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Ingredient name", entry.ingredients.map { it.name }),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Food name", listOf(entry.name))
            )

            for ((assessment, source, values) in sources) {
                val matches = configured.mapIndexedNotNull { index, sensitivity ->
                    if (values.any { matches(sensitivity, it) }) sensitivities[index].trim() else null
                }.distinct()
                if (matches.isNotEmpty()) {
                    return AllergenAnalysis(assessment, matches, listOf(source))
                }
            }
            return AllergenAnalysis(AllergenAssessment.UNABLE_TO_ASSESS)
        }

        private fun normalize(value: String): String {
            val folded = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "")
                .lowercase()
            return folded
                .replace("^[a-z]{2}:".toRegex(), "")
                .replace("[^a-z0-9]+".toRegex(), " ")
                .trim()
        }

        private fun matches(sensitivity: String, candidate: String): Boolean {
            val candidateTokens = normalize(candidate).split(" ").filter(String::isNotEmpty)
            val sensitivityTokens = normalize(sensitivity).split(" ").filter(String::isNotEmpty)
            if (sensitivityTokens.isEmpty()) return false
            val aliases = mapOf(
                "milk" to setOf("milk", "dairy", "casein", "whey", "lactose"),
                "dairy" to setOf("milk", "dairy", "casein", "whey", "lactose"),
                "soy" to setOf("soy", "soya", "soybean"),
                "soya" to setOf("soy", "soya", "soybean"),
                "peanut" to setOf("peanut", "groundnut"),
                "peanuts" to setOf("peanut", "groundnut"),
                "tree nut" to setOf("almond", "cashew", "hazelnut", "macadamia", "pecan", "pistachio", "walnut"),
                "nuts" to setOf("almond", "cashew", "hazelnut", "macadamia", "pecan", "pistachio", "walnut"),
                "wheat" to setOf("wheat", "gluten"),
                "gluten" to setOf("wheat", "gluten", "rye", "barley", "oat")
            )
            val key = sensitivityTokens.joinToString(" ")
            val terms = aliases[key] ?: sensitivityTokens.toSet()
            return terms.any { term ->
                val termTokens = term.split(" ")
                if (termTokens.size == 1) {
                    candidateTokens.any { tokenMatches(termTokens[0], it) }
                } else {
                    candidateTokens.windowed(termTokens.size).any { window ->
                        window.zip(termTokens).all { (candidate, expected) -> tokenMatches(expected, candidate) }
                    }
                }
            } || sensitivityTokens.all { expected ->
                candidateTokens.any { tokenMatches(expected, it) }
            }
        }

        private fun tokenMatches(term: String, candidate: String): Boolean =
            term == candidate ||
                term + "s" == candidate ||
                (candidate.endsWith("s") && candidate.dropLast(1) == term)
    }
}

val AllergenAssessment.labelRes: Int
    get() = when (this) {
        AllergenAssessment.DECLARED_ALLERGEN_MATCH -> R.string.core_allergen_declared_match
        AllergenAssessment.MAY_CONTAIN -> R.string.core_allergen_may_contain
        AllergenAssessment.POSSIBLE_ALLERGEN -> R.string.core_allergen_possible
        AllergenAssessment.UNABLE_TO_ASSESS -> R.string.core_allergen_unable
    }

/** Shown as is by the food sheets ([AllergenAnalysis.summary]). */
val AllergenAssessment.displayName: String
    get() = AppText.get(labelRes)

fun FoodEntry.allergenAnalysis(sensitivities: List<String>): AllergenAnalysis =
    AllergenAnalysis.evaluate(this, sensitivities)
