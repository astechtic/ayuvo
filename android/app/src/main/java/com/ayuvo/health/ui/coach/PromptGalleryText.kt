package com.ayuvo.health.ui.coach

import androidx.annotation.StringRes
import com.ayuvo.health.R

/**
 * The gallery's text, keyed by catalog id (docs/coach.md §9).
 *
 * The ids and the English source live in `shared/coach/prompt_gallery.json`; this table only
 * points each one at its string resource, so a card, a chip and a translation can never drift
 * apart. `PromptGalleryTextTest` compares the resources with the catalog.
 */
object PromptGalleryText {
    @StringRes
    fun title(id: String): Int? = when (id) {
        "diet_chart_from_health" -> R.string.coach_prompt_diet_chart_from_health_title
        "macro_balance" -> R.string.coach_prompt_macro_balance_title
        "dinner_tonight" -> R.string.coach_prompt_dinner_tonight_title
        "protein_sources" -> R.string.coach_prompt_protein_sources_title
        "week_review" -> R.string.coach_prompt_week_review_title
        "improve_sleep_cycle" -> R.string.coach_prompt_improve_sleep_cycle_title
        "sleep_week" -> R.string.coach_prompt_sleep_week_title
        "sleep_consistency" -> R.string.coach_prompt_sleep_consistency_title
        "sleep_and_resting_hr" -> R.string.coach_prompt_sleep_and_resting_hr_title
        "training_review" -> R.string.coach_prompt_training_review_title
        "moving_enough" -> R.string.coach_prompt_moving_enough_title
        "lift_progress" -> R.string.coach_prompt_lift_progress_title
        "recovery_check" -> R.string.coach_prompt_recovery_check_title
        "explain_latest_report" -> R.string.coach_prompt_explain_latest_report_title
        "find_abnormal" -> R.string.coach_prompt_find_abnormal_title
        "compare_reports" -> R.string.coach_prompt_compare_reports_title
        "analyte_trend" -> R.string.coach_prompt_analyte_trend_title
        "questions_for_doctor" -> R.string.coach_prompt_questions_for_doctor_title
        "adherence_check" -> R.string.coach_prompt_adherence_check_title
        "medication_schedule" -> R.string.coach_prompt_medication_schedule_title
        "missed_dose_pattern" -> R.string.coach_prompt_missed_dose_pattern_title
        "reach_my_goal" -> R.string.coach_prompt_reach_my_goal_title
        "month_over_month" -> R.string.coach_prompt_month_over_month_title
        "one_thing_to_change" -> R.string.coach_prompt_one_thing_to_change_title
        "whole_picture" -> R.string.coach_prompt_whole_picture_title
        else -> null
    }

    @StringRes
    fun prompt(id: String): Int? = when (id) {
        "diet_chart_from_health" -> R.string.coach_prompt_diet_chart_from_health_text
        "macro_balance" -> R.string.coach_prompt_macro_balance_text
        "dinner_tonight" -> R.string.coach_prompt_dinner_tonight_text
        "protein_sources" -> R.string.coach_prompt_protein_sources_text
        "week_review" -> R.string.coach_prompt_week_review_text
        "improve_sleep_cycle" -> R.string.coach_prompt_improve_sleep_cycle_text
        "sleep_week" -> R.string.coach_prompt_sleep_week_text
        "sleep_consistency" -> R.string.coach_prompt_sleep_consistency_text
        "sleep_and_resting_hr" -> R.string.coach_prompt_sleep_and_resting_hr_text
        "training_review" -> R.string.coach_prompt_training_review_text
        "moving_enough" -> R.string.coach_prompt_moving_enough_text
        "lift_progress" -> R.string.coach_prompt_lift_progress_text
        "recovery_check" -> R.string.coach_prompt_recovery_check_text
        "explain_latest_report" -> R.string.coach_prompt_explain_latest_report_text
        "find_abnormal" -> R.string.coach_prompt_find_abnormal_text
        "compare_reports" -> R.string.coach_prompt_compare_reports_text
        "analyte_trend" -> R.string.coach_prompt_analyte_trend_text
        "questions_for_doctor" -> R.string.coach_prompt_questions_for_doctor_text
        "adherence_check" -> R.string.coach_prompt_adherence_check_text
        "medication_schedule" -> R.string.coach_prompt_medication_schedule_text
        "missed_dose_pattern" -> R.string.coach_prompt_missed_dose_pattern_text
        "reach_my_goal" -> R.string.coach_prompt_reach_my_goal_text
        "month_over_month" -> R.string.coach_prompt_month_over_month_text
        "one_thing_to_change" -> R.string.coach_prompt_one_thing_to_change_text
        "whole_picture" -> R.string.coach_prompt_whole_picture_text
        else -> null
    }

    @StringRes
    fun category(name: String): Int? = when (name) {
        "nutrition" -> R.string.coach_prompt_category_nutrition
        "sleep" -> R.string.coach_prompt_category_sleep
        "training" -> R.string.coach_prompt_category_training
        "labs" -> R.string.coach_prompt_category_labs
        "medications" -> R.string.coach_prompt_category_medications
        "planning" -> R.string.coach_prompt_category_planning
        else -> null
    }
}
