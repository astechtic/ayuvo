package com.ayuvo.health.nutrients

import com.ayuvo.health.data.metrics.MetricRange

enum class NutrientRuleKind { RECOMMENDED, GOAL, UPPER_LIMIT, LIMIT }

/** One dashed rule on a nutrient chart. */
data class NutrientRule(val value: Double, val kind: NutrientRuleKind) {
    /** Upper limits and limits use the warning colour; Recommended and Your goal the domain colour. */
    val isWarning: Boolean get() = kind == NutrientRuleKind.UPPER_LIMIT || kind == NutrientRuleKind.LIMIT
}

/**
 * Which reference rules a nutrient chart draws (docs/nutrients.md §5): W, M, 6M and Y only (daily
 * totals or means of daily totals), never D. Target style: Recommended (or Your goal) plus Upper
 * limit; limit style: Limit (or Your goal); info style: only a custom goal.
 */
object NutrientChartRules {
    fun rules(lines: ReferenceLines?, range: MetricRange): List<NutrientRule> {
        if (lines == null || lines.error != null || range == MetricRange.D) return emptyList()
        val out = mutableListOf<NutrientRule>()
        lines.recommended?.let { out += NutrientRule(it, if (lines.recommendedIsGoal) NutrientRuleKind.GOAL else NutrientRuleKind.RECOMMENDED) }
        lines.upperLimit?.let { out += NutrientRule(it, NutrientRuleKind.UPPER_LIMIT) }
        lines.limit?.let { out += NutrientRule(it, if (lines.limitIsGoal) NutrientRuleKind.GOAL else NutrientRuleKind.LIMIT) }
        return out
    }

    /** The "of Y" of the Day header: the recommended amount or goal, else the limit; null without either. */
    fun dayTarget(lines: ReferenceLines?): Double? = lines?.takeIf { it.error == null }?.let { it.recommended ?: it.limit }
}
