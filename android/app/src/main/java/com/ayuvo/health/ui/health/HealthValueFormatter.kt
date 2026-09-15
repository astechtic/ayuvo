package com.ayuvo.health.ui.health

import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.models.HealthDataType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Which display units the user prefers (kg/lb, cm/in, mmol/L vs mg/dL, °C/°F). */
data class HealthUnitPrefs(
    val metricMass: Boolean = true,
    val metricLength: Boolean = true,
    val glucoseMgDl: Boolean = false,
    val fahrenheit: Boolean = false
)

/** A formatted number and its unit symbol, kept apart so layouts never concatenate copy. */
data class FormattedHealthValue(val number: String, val unit: String) {
    val text: String get() = if (unit.isEmpty()) number else "$number $unit"
}

/**
 * Pure value formatting for the hub, Home tiles and Coach summaries. Canonical storage units
 * (see HealthDataType.canonicalUnits) → display units per [HealthUnitPrefs]. No Android imports
 * so it is unit-tested on the JVM; unit *symbols* are literals, not translated copy.
 */
object HealthValueFormatter {
    const val LB_PER_KG = 2.20462
    const val IN_PER_M = 39.3701
    const val MGDL_PER_MMOL = 18.0182

    fun format(typeId: String, value: Double?, prefs: HealthUnitPrefs = HealthUnitPrefs(), locale: Locale = Locale.US, unitOverride: String? = null): FormattedHealthValue {
        if (value == null || value.isNaN() || value.isInfinite()) return FormattedHealthValue("—", "")
        val type = HealthDataType.byId(typeId)
        val unit = unitOverride ?: type?.unit ?: "none"
        return when (unit) {
            "count" -> when (typeId) {
                HealthDataType.BMI.id, HealthDataType.WORKOUT_EFFORT_SCORE.id, HealthDataType.ESTIMATED_WORKOUT_EFFORT_SCORE.id ->
                    FormattedHealthValue(decimal(value, 1, locale), "")
                else -> FormattedHealthValue(integer(value, locale), "")
            }
            "kcal" -> FormattedHealthValue(integer(value, locale), "kcal")
            "kcal/d" -> FormattedHealthValue(integer(value, locale), "kcal/day")
            "m" -> formatLength(typeId, value, prefs, locale)
            "kg" -> if (prefs.metricMass) FormattedHealthValue(decimal(value, 1, locale), "kg")
                    else FormattedHealthValue(decimal(value * LB_PER_KG, 1, locale), "lb")
            "%" -> FormattedHealthValue(decimal(value, if (typeId == HealthDataType.BLOOD_OXYGEN.id) 0 else 1, locale), "%")
            "count/min" -> FormattedHealthValue(integer(value, locale), if (type?.category?.id == "heart") "bpm" else "/min")
            "count/hr" -> FormattedHealthValue(decimal(value, 1, locale), "/h")
            "ms" -> FormattedHealthValue(integer(value, locale), "ms")
            "mmHg" -> FormattedHealthValue(integer(value, locale), "mmHg")
            "mmol/L" -> if (prefs.glucoseMgDl) FormattedHealthValue(integer(value * MGDL_PER_MMOL, locale), "mg/dL")
                        else FormattedHealthValue(decimal(value, 1, locale), "mmol/L")
            "degC" -> if (prefs.fahrenheit) FormattedHealthValue(decimal(value * 9.0 / 5.0 + 32.0, 1, locale), "°F")
                      else FormattedHealthValue(decimal(value, 1, locale), "°C")
            "s" -> FormattedHealthValue(duration(value), "")
            "mL" -> if (abs(value) >= 1000) FormattedHealthValue(decimal(value / 1000.0, 2, locale), "L")
                    else FormattedHealthValue(integer(value, locale), "mL")
            "L" -> FormattedHealthValue(decimal(value, 2, locale), "L")
            "L/min" -> FormattedHealthValue(integer(value, locale), "L/min")
            "g" -> FormattedHealthValue(decimal(value, 1, locale), "g")
            "mg" -> FormattedHealthValue(integer(value, locale), "mg")
            "mcg" -> FormattedHealthValue(integer(value, locale), "µg")
            "m/s" -> FormattedHealthValue(decimal(value, 1, locale), "m/s")
            "W" -> FormattedHealthValue(integer(value, locale), "W")
            "dBASPL" -> FormattedHealthValue(integer(value, locale), "dB")
            "mL/min·kg" -> FormattedHealthValue(decimal(value, 1, locale), "mL/kg·min")
            "kcal/hr·kg" -> FormattedHealthValue(decimal(value, 1, locale), "kcal/kg·h")
            "IU" -> FormattedHealthValue(decimal(value, 1, locale), "IU")
            "mcS" -> FormattedHealthValue(decimal(value, 2, locale), "µS")
            "days" -> FormattedHealthValue(integer(value, locale), if (value.roundToInt() == 1) "day" else "days")
            else -> FormattedHealthValue(decimal(value, 1, locale), "")
        }
    }

    /** "121/79 mmHg" for blood pressure. */
    fun formatBloodPressure(systolic: Double?, diastolic: Double?, locale: Locale = Locale.US): FormattedHealthValue {
        if (systolic == null) return FormattedHealthValue("—", "")
        val d = diastolic?.let { integer(it, locale) } ?: "—"
        return FormattedHealthValue("${integer(systolic, locale)}/$d", "mmHg")
    }

    /** Category label for a `category_value` code, falling back to the code itself. */
    fun categoryLabel(typeId: String, code: Int?): String {
        if (code == null) return "—"
        if (typeId == HealthDataType.SLEEP.id) return HealthSleepCodes.label(code).replace('_', ' ')
        val type = HealthDataType.byId(typeId)
        return type?.categoryCodes?.get(code)?.replace('_', ' ') ?: code.toString()
    }

    /** "7h 32m", "45 min", "30 s". */
    fun duration(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }

    fun formatLength(typeId: String, meters: Double, prefs: HealthUnitPrefs, locale: Locale): FormattedHealthValue {
        val type = HealthDataType.byId(typeId)
        val isDistance = typeId.startsWith("distance") || typeId == HealthDataType.SIX_MINUTE_WALK_DISTANCE.id || typeId == HealthDataType.ELEVATION_GAINED.id
        val isBodyLength = type?.category?.id == "body" || typeId == HealthDataType.HEIGHT.id
        return when {
            isBodyLength && prefs.metricLength -> FormattedHealthValue(integer(meters * 100.0, locale), "cm")
            isBodyLength -> {
                val inches = meters * IN_PER_M
                if (typeId == HealthDataType.HEIGHT.id) {
                    val ft = (inches / 12).toInt()
                    val rem = (inches - ft * 12).roundToInt()
                    FormattedHealthValue("$ft ft $rem", "in")
                } else FormattedHealthValue(decimal(inches, 1, locale), "in")
            }
            isDistance && prefs.metricLength -> if (abs(meters) >= 1000) FormattedHealthValue(decimal(meters / 1000.0, 2, locale), "km") else FormattedHealthValue(integer(meters, locale), "m")
            isDistance -> {
                val miles = meters / 1609.344
                if (abs(miles) >= 0.1) FormattedHealthValue(decimal(miles, 2, locale), "mi") else FormattedHealthValue(integer(meters * 3.28084, locale), "ft")
            }
            prefs.metricLength -> FormattedHealthValue(decimal(meters, 2, locale), "m")
            else -> FormattedHealthValue(decimal(meters * 3.28084, 1, locale), "ft")
        }
    }

    fun integer(value: Double, locale: Locale = Locale.US): String = String.format(locale, "%,d", value.roundToLong())

    fun decimal(value: Double, digits: Int, locale: Locale = Locale.US): String {
        if (digits <= 0) return integer(value, locale)
        val text = String.format(locale, "%,.${digits}f", value)
        // Trim "80.0" → "80" but keep "80.5".
        val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val idx = text.indexOf(sep)
        if (idx < 0) return text
        val trimmed = text.trimEnd('0')
        return if (trimmed.endsWith(sep)) trimmed.dropLast(1) else trimmed
    }

    /** Human-readable byte count for the "Storage used" line. */
    fun bytes(bytes: Long, locale: Locale = Locale.US): String = when {
        bytes >= 1_048_576L -> String.format(locale, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(locale, "%d KB", bytes / 1024)
        else -> String.format(locale, "%d B", bytes)
    }

    /** "%" of glucose-style convention choice by locale: US uses mg/dL by default. */
    fun defaultGlucoseMgDl(locale: Locale): Boolean = locale.country.uppercase(Locale.US) in setOf("US", "IN", "DE", "AT", "FR", "IT", "ES", "PL", "IL", "JP", "TR", "BR", "MX", "AR", "EG")
}
