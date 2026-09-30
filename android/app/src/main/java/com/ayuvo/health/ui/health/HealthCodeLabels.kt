package com.ayuvo.health.ui.health

import androidx.annotation.StringRes
import com.ayuvo.health.R

/** Display names of the Health `category_value` codes ([com.ayuvo.health.models.HealthDataType.categoryCodes], sleep stages). */
object HealthCodeLabels {
    @StringRes
    fun labelRes(code: String): Int? = when (code) {
        "asleep_unspecified" -> R.string.fu_health_code_asleep_unspecified
        "atrial_fibrillation" -> R.string.fu_health_code_atrial_fibrillation
        "awake" -> R.string.fu_health_code_awake
        "basal" -> R.string.fu_health_code_basal
        "bolus" -> R.string.fu_health_code_bolus
        "breakfast" -> R.string.fu_health_code_breakfast
        "breathing" -> R.string.fu_health_code_breathing
        "capillary_blood" -> R.string.fu_health_code_capillary_blood
        "creamy" -> R.string.fu_health_code_creamy
        "daily_mood" -> R.string.fu_health_code_daily_mood
        "decreased" -> R.string.fu_health_code_decreased
        "deep" -> R.string.fu_health_code_deep
        "dinner" -> R.string.fu_health_code_dinner
        "dry" -> R.string.fu_health_code_dry
        "egg_white" -> R.string.fu_health_code_egg_white
        "estrogen_surge" -> R.string.fu_health_code_estrogen_surge
        "heavy" -> R.string.fu_health_code_heavy
        "idle" -> R.string.fu_health_code_idle
        "implant" -> R.string.fu_health_code_implant
        "in_bed" -> R.string.fu_health_code_in_bed
        "inconclusive_high_heart_rate" -> R.string.fu_health_code_inconclusive_high_heart_rate
        "inconclusive_low_heart_rate" -> R.string.fu_health_code_inconclusive_low_heart_rate
        "inconclusive_other" -> R.string.fu_health_code_inconclusive_other
        "inconclusive_poor_reading" -> R.string.fu_health_code_inconclusive_poor_reading
        "increased" -> R.string.fu_health_code_increased
        "indeterminate" -> R.string.fu_health_code_indeterminate
        "initial_low" -> R.string.fu_health_code_initial_low
        "initial_very_low" -> R.string.fu_health_code_initial_very_low
        "injection" -> R.string.fu_health_code_injection
        "interstitial_fluid" -> R.string.fu_health_code_interstitial_fluid
        "intrauterine_device" -> R.string.fu_health_code_intrauterine_device
        "intravaginal_ring" -> R.string.fu_health_code_intravaginal_ring
        "light" -> R.string.fu_health_code_light
        "low_fitness" -> R.string.fu_health_code_low_fitness
        "lunch" -> R.string.fu_health_code_lunch
        "lying_down" -> R.string.fu_health_code_lying_down
        "meditation" -> R.string.fu_health_code_meditation
        "medium" -> R.string.fu_health_code_medium
        "mild" -> R.string.fu_health_code_mild
        "moderate" -> R.string.fu_health_code_moderate
        "moderately_severe" -> R.string.fu_health_code_moderately_severe
        "momentary_emotion" -> R.string.fu_health_code_momentary_emotion
        "momentary_limit" -> R.string.fu_health_code_momentary_limit
        "movement" -> R.string.fu_health_code_movement
        "music" -> R.string.fu_health_code_music
        "negative" -> R.string.fu_health_code_negative
        "no_change" -> R.string.fu_health_code_no_change
        "none" -> R.string.fu_health_code_none
        "none_to_minimal" -> R.string.fu_health_code_none_to_minimal
        "not_present" -> R.string.fu_health_code_not_present
        "not_set" -> R.string.fu_health_code_not_set
        "occurred" -> R.string.fu_health_code_occurred
        "oral" -> R.string.fu_health_code_oral
        "other" -> R.string.fu_health_code_other
        "out_of_bed" -> R.string.fu_health_code_out_of_bed
        "patch" -> R.string.fu_health_code_patch
        "plasma" -> R.string.fu_health_code_plasma
        "positive" -> R.string.fu_health_code_positive
        "present" -> R.string.fu_health_code_present
        "protected" -> R.string.fu_health_code_protected
        "reclining" -> R.string.fu_health_code_reclining
        "rem" -> R.string.fu_health_code_rem
        "repeat_low" -> R.string.fu_health_code_repeat_low
        "repeat_very_low" -> R.string.fu_health_code_repeat_very_low
        "serum" -> R.string.fu_health_code_serum
        "seven_day_limit" -> R.string.fu_health_code_seven_day_limit
        "severe" -> R.string.fu_health_code_severe
        "sinus_rhythm" -> R.string.fu_health_code_sinus_rhythm
        "sitting_down" -> R.string.fu_health_code_sitting_down
        "snack" -> R.string.fu_health_code_snack
        "standing_up" -> R.string.fu_health_code_standing_up
        "sticky" -> R.string.fu_health_code_sticky
        "stood" -> R.string.fu_health_code_stood
        "tears" -> R.string.fu_health_code_tears
        "unguided" -> R.string.fu_health_code_unguided
        "unknown" -> R.string.fu_health_code_unknown
        "unprotected" -> R.string.fu_health_code_unprotected
        "unrecognized" -> R.string.fu_health_code_unrecognized
        "unspecified" -> R.string.fu_health_code_unspecified
        "unusual" -> R.string.fu_health_code_unusual
        "vigorous" -> R.string.fu_health_code_vigorous
        "watery" -> R.string.fu_health_code_watery
        "whole_blood" -> R.string.fu_health_code_whole_blood
        else -> null
    }
}
