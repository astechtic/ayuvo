package com.ayuvo.health.actions

import android.content.Context
import com.ayuvo.health.R
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.formatFastingDuration
import java.util.Locale

/** One-line, user-facing text for an action result or error (snackbar, confirm sheet, shortcuts). */
object ActionResultText {

    fun describe(context: Context, spec: ActionSpec, result: ActionResult, prefs: ActionPrefs): String {
        val f = result.fields
        fun num(k: String): Double? = (f[k] as? Number)?.toDouble()
        fun long(k: String): Long? = (f[k] as? Number)?.toLong()
        fun water(ml: Long?): String {
            val unit = if (prefs.volumeUnit == "floz") WaterUnit.FLUID_OUNCES else WaterUnit.MILLILITERS
            return unit.format((ml ?: 0L).toInt())
        }
        fun n(v: Double?, decimals: Int = 1): String = v?.let { formatNumber(it, decimals) } ?: "–"
        return when (spec.id) {
            "water.get" -> context.getString(R.string.action_result_water, water(long("intake_ml")), water(long("goal_ml")))
            "water.log" -> context.getString(R.string.action_result_water_logged, water(long("added_ml")), water(long("remaining_ml")))
            "nutrition.summary.get" -> context.getString(
                R.string.action_result_nutrition, long("calories") ?: 0L, n(num("protein_g"), 0), long("calorie_target")?.toString() ?: "–"
            )
            "nutrition.nutrient.get" -> context.getString(
                R.string.action_result_nutrient, f["nutrient"].toString(), n(num("value"), 0), f["unit"].toString(),
                num("target")?.let { n(it, 0) } ?: "–"
            )
            "weight.get", "weight.log", "body.fat.log", "body.measurement.log", "health.metric.latest" ->
                context.getString(R.string.action_result_value, spec.title, n(num("value")), f["unit"].toString())
            "health.metric.get" ->
                context.getString(R.string.action_result_value, spec.title, n(num("value")), f["unit"].toString())
            "weight.history" -> context.getString(R.string.action_result_weight_change, n(num("change_kg")), long("count") ?: 0L)
            "body.composition.get" -> context.getString(R.string.action_result_bmi, n(num("bmi")))
            "health.sleep.lastNight" -> context.getString(R.string.action_result_sleep, formatFastingDuration(long("asleep_s") ?: 0L))
            "fasting.status.get", "fasting.start" ->
                if (f["active"] == true) context.getString(R.string.action_result_fasting, formatFastingDuration(long("elapsed_s") ?: 0L), formatFastingDuration(long("remaining_s") ?: 0L))
                else context.getString(R.string.action_result_not_fasting)
            "fasting.stop" -> context.getString(R.string.action_result_fast_ended, formatFastingDuration(long("duration_s") ?: 0L))
            "nutrition.food.log", "nutrition.food.logSaved" -> context.getString(R.string.action_result_food_logged, f["name"].toString(), long("calories") ?: 0L)
            "workout.set.log" -> context.getString(R.string.action_result_set_logged, f["exercise"].toString(), long("set_number") ?: 0L, long("reps") ?: 0L)
            "workout.today.get", "workout.finish" -> context.getString(R.string.action_result_workout, long("sets_done") ?: 0L, n(num("volume_kg"), 0))
            "medication.dose.mark", "medications.next.get" -> context.getString(R.string.action_result_dose, f["medication"].toString(), f["status"].toString())
            "goals.update" -> context.getString(R.string.action_result_goal, f["goal"].toString(), long("value") ?: 0L)
            else -> when {
                result.items.isNotEmpty() || spec.outputKind == "list" -> context.getString(R.string.action_result_items, spec.title, result.items.size)
                else -> context.getString(R.string.action_result_done, spec.title)
            }
        }
    }

    fun error(context: Context, e: ActionException): String {
        val base = context.getString(errorRes(e.code))
        return when (e.code) {
            ActionErrorCode.CONFLICT, ActionErrorCode.NOT_FOUND -> when (e.detail) {
                "active_fast" -> context.getString(R.string.action_error_active_fast)
                "fast_active" -> context.getString(R.string.action_error_fast_running)
                "no_active_fast" -> context.getString(R.string.action_error_no_fast)
                "no_workout" -> context.getString(R.string.action_error_no_workout)
                else -> base
            }
            ActionErrorCode.AI_FAILED -> e.detail?.let { "$base $it" } ?: base
            else -> base
        }
    }

    fun errorRes(code: ActionErrorCode): Int = when (code) {
        ActionErrorCode.UNKNOWN_ACTION, ActionErrorCode.UNKNOWN_PARAM -> R.string.action_error_unknown
        ActionErrorCode.NOT_ALLOWED -> R.string.action_error_not_allowed
        ActionErrorCode.MISSING_PARAM, ActionErrorCode.REQUIRES_ONE_OF -> R.string.action_error_missing
        ActionErrorCode.BAD_TYPE, ActionErrorCode.BAD_ENUM, ActionErrorCode.BAD_VALUE, ActionErrorCode.TOO_LONG -> R.string.action_error_invalid
        ActionErrorCode.OUT_OF_RANGE -> R.string.action_error_out_of_range
        ActionErrorCode.PERMISSION_REQUIRED -> R.string.action_error_permission
        ActionErrorCode.NOT_FOUND -> R.string.action_error_not_found
        ActionErrorCode.CONFLICT -> R.string.action_error_conflict
        ActionErrorCode.UNAVAILABLE -> R.string.action_error_unavailable
        ActionErrorCode.AI_FAILED -> R.string.action_error_ai
    }

    /** Parameter lines for the confirm sheet ("Amount: 500", "Unit: ml"). Values only, no stored data. */
    fun paramLines(spec: ActionSpec, params: Map<String, Any?>): List<String> =
        spec.params.mapNotNull { p ->
            val v = params[p.name] ?: return@mapNotNull null
            val shown = if (v is Double) formatNumber(v, 2) else v.toString()
            "${p.name.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }}: $shown"
        }

    private fun formatNumber(v: Double, decimals: Int): String =
        if (decimals == 0 || v % 1.0 == 0.0) String.format(Locale.getDefault(), "%.0f", v)
        else String.format(Locale.getDefault(), "%.${decimals}f", v).trimEnd('0').trimEnd('.', ',')
}
