package com.ayuvo.health.nutrients

import java.text.NumberFormat
import java.util.Locale

/** Nutrient amounts for display: canonical unit, sensible decimals, "—" for missing values. */
object NutrientFormat {
    const val MISSING = "—"

    /** ≥ 100 → whole numbers, ≥ 10 → 1 decimal, else up to 2 decimals (trailing zeros dropped). */
    fun amount(value: Double?, locale: Locale = Locale.getDefault()): String {
        if (value == null || !value.isFinite()) return MISSING
        val abs = kotlin.math.abs(value)
        val decimals = when {
            abs >= 100 -> 0
            abs >= 10 -> 1
            else -> 2
        }
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = decimals
            isGroupingUsed = true
        }
        return nf.format(value)
    }

    /** "12.5 mcg"; "—" when missing. */
    fun withUnit(value: Double?, unit: String, locale: Locale = Locale.getDefault()): String =
        if (value == null || !value.isFinite()) MISSING else "${amount(value, locale)} $unit"
}
