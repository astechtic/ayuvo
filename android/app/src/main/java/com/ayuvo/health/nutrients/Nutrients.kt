package com.ayuvo.health.nutrients

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.pow

/** Profile facts the reference lines read: age in years, `male` / `female` / null, calorie goal. */
data class NutrientProfile(val age: Double? = null, val sex: String? = null, val calorieGoal: Double? = null)

/** `reference_lines` output (docs/nutrients.md §4.2). */
data class ReferenceLines(
    val key: String,
    val band: String,
    val sex: String?,
    val style: String?,
    val unit: String?,
    val recommended: Double?,
    val upperLimit: Double?,
    val limit: Double?,
    val recommendedLabel: String,
    val limitLabel: String,
    val upperLimitScope: String?,
    val referenceRecommended: Double?,
    val referenceLimit: Double?,
    val recommendedKind: String?,
    val sourceIds: List<String>,
    val error: String?
) {
    val recommendedIsGoal: Boolean get() = recommendedLabel == Nutrients.LABEL_GOAL
    val limitIsGoal: Boolean get() = limitLabel == Nutrients.LABEL_GOAL
}

/** `convert_amount` output: the amount in the nutrient's canonical unit, or an error code. */
data class ConvertedAmount(val ok: Boolean, val amount: Double?, val unit: String?, val error: String?)

/** One `medication_nutrients` row: what ONE dose unit contains, in the canonical unit. */
data class MedicationNutrientRow(val medicationId: String, val nutrientKey: String, val amountPerUnit: Double)

/** The dose-log fields `supplement_entries` reads. */
data class SupplementDose(val medicationId: String, val status: String, val takenAtMs: Long?, val doseQuantity: Double?)

/** One supplement contribution: [value] of [nutrientKey] at [tMs]. */
data class SupplementEntry(val tMs: Long, val nutrientKey: String, val value: Double, val medicationId: String)

/** A food entry as `day_totals` sees it: its instant and nutrient values (null = not recorded). */
data class FoodNutrients(val tMs: Long, val nutrients: Map<String, Double?>)

/** Food, supplement and combined amount of one nutrient; null parts had no data. */
data class NutrientAmount(val food: Double?, val supplements: Double?, val total: Double?) {
    companion object {
        val NONE = NutrientAmount(null, null, null)
    }
}

/** One value of one nutrient (food or supplement) for [Nutrients.loggedDayAverage]. */
data class NutrientValueEntry(val tMs: Long, val value: Double?)

data class LoggedDayAverage(val average: Double?, val loggedDays: Int)

/**
 * Line-by-line port of `scripts/nutrients_reference.py` (docs/nutrients.md): reference lines,
 * default goals, unit conversion and supplement contributions. The reference wins over prose;
 * `NutrientsVectorTests` runs every shared vector file through these functions.
 */
object Nutrients {
    const val AMOUNT_DECIMALS = 6
    const val LIMIT_DECIMALS = 1
    const val LABEL_RECOMMENDED = "Recommended"
    const val LABEL_LIMIT = "Limit"
    const val LABEL_GOAL = "Your goal"
    val SEXES = listOf("male", "female")

    /** mcg per unit. */
    val MASS_FACTORS: Map<String, Double> = linkedMapOf("g" to 1000000.0, "mg" to 1000.0, "mcg" to 1.0)

    private val ref: NutrientReference get() = NutrientReference.current

    // -- numbers and days -----------------------------------------------------------------------

    /** floor(x * 10^d + 0.5) / 10^d, never -0. */
    fun roundTo(x: Double?, decimals: Int): Double? {
        if (x == null) return null
        val scale = 10.0.pow(decimals)
        val v = floor(x * scale + 0.5) / scale
        return if (v == 0.0) 0.0 else v
    }

    fun roundHalfUpInt(x: Double): Int = floor(x + 0.5).toInt()

    private fun isNumber(v: Double?): Boolean = v != null && v.isFinite()

    fun localDayOf(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    fun localMidnightMs(day: String, zone: ZoneId): Long = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()

    // -- profile -> band, reference lines, default goals ----------------------------------------

    /** No age or under 19 → the default band (31-50); fractional ages are floored. */
    fun bandForAge(age: Double?): String {
        if (!isNumber(age)) return ref.defaultBand
        val a = floor(age!!).toInt()
        if (a < 19) return ref.defaultBand
        for (b in ref.ageBands) {
            if (a >= b.min && (b.max == null || a <= b.max)) return b.id
        }
        return ref.defaultBand
    }

    private fun sexOf(profile: NutrientProfile?): String? = profile?.sex?.takeIf { it in SEXES }

    /** Unknown sex: the higher (recommended) or the lower (upper limit) of the two sexes. */
    private fun bySex(table: NutrientSexTable, band: String, sex: String?, pickHigh: Boolean): Double {
        if (sex != null) return table.of(sex, band)
        val m = table.of("male", band)
        val f = table.of("female", band)
        return if (pickHigh) (if (m >= f) m else f) else (if (m <= f) m else f)
    }

    private fun limitValue(limit: NutrientLimit?, calorieGoal: Double?): Double? {
        if (limit == null) return null
        return when (limit.kind) {
            "fixed" -> limit.value
            "pct_energy" -> {
                if (!isNumber(calorieGoal) || calorieGoal!! <= 0) return null
                roundTo(calorieGoal * limit.pct!! / 100.0 / limit.kcalPerUnit!!, LIMIT_DECIMALS)
            }
            else -> error("bad limit kind ${limit.kind}")
        }
    }

    /**
     * Chart lines for one nutrient. A [customGoal] > 0 replaces the Recommended line (target style
     * and sports supplements) or the Limit line (limit and info styles), labelled "Your goal".
     */
    fun referenceLines(key: String, profile: NutrientProfile?, customGoal: Double? = null): ReferenceLines {
        val p = profile ?: NutrientProfile()
        val band = bandForAge(p.age)
        val sex = sexOf(p)
        val goal = if (isNumber(customGoal) && customGoal!! > 0) customGoal else null
        val base = ReferenceLines(
            key = key, band = band, sex = sex, style = null, unit = null, recommended = null, upperLimit = null, limit = null,
            recommendedLabel = LABEL_RECOMMENDED, limitLabel = LABEL_LIMIT, upperLimitScope = null,
            referenceRecommended = null, referenceLimit = null, recommendedKind = null, sourceIds = emptyList(), error = null
        )
        ref.sportsByKey[key]?.let { s ->
            val r = base.copy(style = "target", unit = s.unit)
            return if (goal != null) r.copy(recommended = goal, recommendedLabel = LABEL_GOAL) else r
        }
        val n = ref.byKey[key] ?: return base.copy(error = "unknown_nutrient")
        val rec = n.recommended?.let { bySex(it.table, band, sex, true) }
        val ul = n.upperLimit?.let { bySex(it.table, band, sex, false) }
        val lim = limitValue(n.limit, p.calorieGoal)
        val r = base.copy(
            style = n.style, unit = n.unit, recommended = rec, upperLimit = ul, limit = lim,
            upperLimitScope = n.upperLimit?.scope, referenceRecommended = rec, referenceLimit = lim,
            recommendedKind = n.recommended?.kind, sourceIds = n.sourceIds.toList()
        )
        if (goal == null) return r
        return if (n.style == "target") r.copy(recommended = goal, recommendedLabel = LABEL_GOAL) else r.copy(limit = goal, limitLabel = LABEL_GOAL)
    }

    /** Recommended for target style, Limit for limit style, null for info style, sports and unknown keys. */
    fun defaultGoal(key: String, profile: NutrientProfile?): Double? {
        val n = ref.byKey[key] ?: return null
        if (n.style == "info") return null
        val r = referenceLines(key, profile)
        return if (n.style == "target") r.recommended else r.limit
    }

    /** Half-up integer, never below 1 when the exact value is > 0; null stays null. */
    fun defaultGoalInt(value: Double?): Int? {
        if (value == null) return null
        val i = roundHalfUpInt(value)
        return if (value > 0 && i < 1) 1 else i
    }

    // -- unit conversion ------------------------------------------------------------------------

    /** `g` | `mg` | `mcg` | `iu` | null; trimmed, lower-cased, ug / µg / μg → mcg. */
    fun normalizeUnit(unit: String?): String? {
        if (unit == null) return null
        var u = unit.trim().lowercase()
        u = ref.unitAliases[u] ?: u
        return if (u in MASS_FACTORS || u == "iu") u else null
    }

    private fun fail(code: String) = ConvertedAmount(false, null, null, code)

    /** Amount in the canonical unit (docs/nutrients.md §4.3); rounded to 6 decimals. */
    fun convertAmount(value: Double?, unit: String?, key: String, form: String? = null): ConvertedAmount {
        val unitC = ref.unitOf(key) ?: return fail("unknown_nutrient")
        if (!isNumber(value) || value!! <= 0) return fail("invalid_amount")
        val u = normalizeUnit(unit) ?: return fail("unsupported_unit")
        val n = ref.byKey[key]
        var amount: Double
        if (u == "iu") {
            val iu = n?.iu ?: return fail("iu_not_supported")
            val forms = iu.forms
            amount = if (forms != null) {
                if (form == null || form == "") return fail("form_required")
                val factor = forms[form] ?: return fail("unknown_form")
                value * factor
            } else {
                value * iu.mcgPerIu!!
            }
            amount = amount * MASS_FACTORS.getValue(iu.unit) / MASS_FACTORS.getValue(unitC)
        } else {
            amount = value * MASS_FACTORS.getValue(u) / MASS_FACTORS.getValue(unitC)
            val mf = n?.massForms.orEmpty()
            if (form != null && form in mf) amount /= mf.getValue(form)
        }
        return ConvertedAmount(true, roundTo(amount, AMOUNT_DECIMALS), unitC, null)
    }

    /** Units the editor may offer for [key]: the mass units, plus `iu` where the reference converts it. */
    fun inputUnits(key: String): List<String> =
        if (ref.byKey[key]?.iu != null) listOf("g", "mg", "mcg", "iu") else listOf("g", "mg", "mcg")

    /** IU forms of [key] (vitamin A / E) in reference order; empty when IU needs no form. */
    fun iuForms(key: String): List<String> = ref.byKey[key]?.iu?.forms?.keys?.toList().orEmpty()

    // -- supplement contributions and day totals ------------------------------------------------

    /**
     * Every TAKEN dose with a taken instant contributes dose_quantity (null → 1) × amount_per_unit
     * per nutrient; sorted by (t, key, medication), stable.
     */
    fun supplementEntries(rows: List<MedicationNutrientRow>, doses: List<SupplementDose>): List<SupplementEntry> {
        val perMed = LinkedHashMap<String, MutableList<MedicationNutrientRow>>()
        for (row in rows) perMed.getOrPut(row.medicationId) { mutableListOf() } += row
        val out = mutableListOf<SupplementEntry>()
        for (log in doses) {
            val at = log.takenAtMs
            if (log.status != "taken" || at == null) continue
            val q = log.doseQuantity ?: 1.0
            for (row in perMed[log.medicationId].orEmpty()) {
                out += SupplementEntry(at, row.nutrientKey, roundTo(q * row.amountPerUnit, AMOUNT_DECIMALS)!!, log.medicationId)
            }
        }
        return out.sortedWith(compareBy<SupplementEntry> { it.tMs }.thenBy { it.nutrientKey }.thenBy { it.medicationId })
    }

    /**
     * `{key: {food, supplements, total}}` for the local [day]: keys named by a food entry of the day
     * (even with a null value) or by a supplement entry of the day, sorted. A part without values
     * is null; total is null only when both parts are.
     */
    fun dayTotals(food: List<FoodNutrients>, supplements: List<SupplementEntry>, day: String, zone: ZoneId): Map<String, NutrientAmount> {
        val acc = HashMap<String, Array<Double?>>()
        for (e in food) {
            if (localDayOf(e.tMs, zone) != day) continue
            for ((k, v) in e.nutrients) {
                val a = acc.getOrPut(k) { arrayOfNulls(2) }
                if (isNumber(v)) a[0] = a[0]?.plus(v!!) ?: v
            }
        }
        for (s in supplements) {
            if (localDayOf(s.tMs, zone) != day) continue
            val a = acc.getOrPut(s.nutrientKey) { arrayOfNulls(2) }
            if (isNumber(s.value)) a[1] = a[1]?.plus(s.value) ?: s.value
        }
        val out = LinkedHashMap<String, NutrientAmount>()
        for (k in acc.keys.sorted()) {
            val (f, p) = acc.getValue(k)
            val total = if (f == null && p == null) null else (f ?: 0.0) + (p ?: 0.0)
            out[k] = NutrientAmount(roundTo(f, AMOUNT_DECIMALS), roundTo(p, AMOUNT_DECIMALS), roundTo(total, AMOUNT_DECIMALS))
        }
        return out
    }

    /**
     * Average per logged day over [startMs, endMs): interval total ÷ logged days. [loggedDays] are
     * the caller's days with any food entry or taken dose; days of valued entries in the interval
     * are added. Null average when no entry in the interval has a value.
     */
    fun loggedDayAverage(entries: List<NutrientValueEntry>, loggedDays: Collection<String>, startMs: Long, endMs: Long, zone: ZoneId): LoggedDayAverage {
        val days = HashSet<String>()
        for (d in loggedDays) {
            val m = localMidnightMs(d, zone)
            if (m in startMs until endMs) days += d
        }
        var total = 0.0
        var seen = false
        for (e in entries) {
            if (e.tMs < startMs || e.tMs >= endMs || !isNumber(e.value)) continue
            total += e.value!!
            seen = true
            days += localDayOf(e.tMs, zone)
        }
        val n = days.size
        if (!seen || n == 0) return LoggedDayAverage(null, n)
        return LoggedDayAverage(roundTo(total / n, AMOUNT_DECIMALS), n)
    }
}
