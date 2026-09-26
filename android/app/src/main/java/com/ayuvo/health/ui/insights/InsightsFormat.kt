package com.ayuvo.health.ui.insights

import androidx.compose.ui.graphics.Color
import com.ayuvo.health.insights.InsightsMath
import com.ayuvo.health.ui.design.AyuvoPalette
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Display formatting for Insights values. A missing value is always "—", never 0
 * (docs/insights.md §7). Engine texts (contributors, review items) are shown as the engines wrote them.
 */
object InsightsFormat {
    const val MISSING = "—"

    val Insights = AyuvoPalette.Insights

    fun number(v: Double?, decimals: Int = 0, locale: Locale = Locale.getDefault()): String {
        if (v == null || !v.isFinite()) return MISSING
        val f = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        val r = InsightsMath.roundTo(v, decimals)
        // A real minus sign, like the engine texts (docs/insights.md §2.2).
        return if (r < 0) InsightsMath.MINUS + f.format(-r) else f.format(r)
    }

    /** Signed with a real minus sign: "+1.2", "−0.6", "0". */
    fun signed(v: Double?, decimals: Int = 1, locale: Locale = Locale.getDefault()): String {
        if (v == null || !v.isFinite()) return MISSING
        val r = InsightsMath.roundTo(v, decimals)
        if (r == 0.0) return number(0.0, decimals, locale)
        return (if (r > 0) "+" else InsightsMath.MINUS) + number(abs(r), decimals, locale)
    }

    fun duration(minutes: Double?): String = minutes?.let { InsightsMath.fmtDuration(it) } ?: MISSING

    /** A baseline metric's value in its config unit (sleep as a duration, counts without decimals). */
    fun metricValue(metricId: String, unit: String, v: Double?, locale: Locale = Locale.getDefault()): String {
        if (v == null) return MISSING
        return when (metricId) {
            "sleep", "workout" -> duration(v)
            "steps" -> "${number(v, 0, locale)} $unit"
            "active_energy" -> "${number(v, 0, locale)} $unit"
            "resting_heart_rate" -> "${number(v, 0, locale)} $unit"
            "hrv" -> "${number(v, 0, locale)} $unit"
            "blood_oxygen", "body_fat" -> "${number(v, 1, locale)}$unit"
            else -> "${number(v, 1, locale)} $unit"
        }
    }

    /** Recovery label → colour (Apple-flat traffic light). */
    fun recoveryColor(label: String?): Color = when (label) {
        "good" -> AyuvoPalette.Success
        "moderate" -> AyuvoPalette.Warning
        "low" -> AyuvoPalette.Destructive
        else -> AyuvoPalette.Other
    }

    /** 0–100 score → colour on the same bands as Recovery. */
    fun scoreColor(score: Int?): Color = when {
        score == null -> AyuvoPalette.Other
        score >= 67 -> AyuvoPalette.Success
        score >= 34 -> AyuvoPalette.Warning
        else -> AyuvoPalette.Destructive
    }

    /** Health Age marker value with its unit. */
    fun markerValue(markerId: String, basis: String?, value: Double?, secondary: Double?, locale: Locale = Locale.getDefault()): String {
        if (value == null) return MISSING
        return when (markerId) {
            "vo2_max" -> "${number(value, 1, locale)} mL/kg/min"
            "resting_heart_rate" -> "${number(value, 0, locale)} bpm"
            "hrv" -> "${number(value, 0, locale)} ms"
            "steps" -> "${number(value, 0, locale)} steps/day"
            "sleep" -> duration(value * 60.0) + (secondary?.let { " · ±${number(it, 0, locale)} min" } ?: "")
            "workouts" -> "${number(value, 0, locale)} min/week"
            "body_composition" -> if (basis == "bmi") "BMI ${number(value, 1, locale)}" else "${number(value, 1, locale)}% body fat"
            else -> number(value, 1, locale)
        }
    }
}
