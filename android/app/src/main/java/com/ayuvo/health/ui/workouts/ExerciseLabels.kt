package com.ayuvo.health.ui.workouts

import com.ayuvo.health.models.WorkoutSplitGroup
import android.content.res.Resources
import androidx.annotation.StringRes
import com.ayuvo.health.R

/**
 * Display names for the exercise catalogue vocabulary (body parts, equipment, muscles). Filters,
 * saved selections and Coach keep the English value; only the text on screen is translated.
 * Unknown values (user-typed) are shown as they are.
 */
object ExerciseLabels {
    /** Stored value when no body part / equipment is picked. */
    const val UNSPECIFIED = "Unspecified"

    @StringRes
    fun labelRes(value: String): Int? = when (value) {
        UNSPECIFIED -> R.string.ui_workout_unspecified
        "Abdominals" -> R.string.fu_exercise_abdominals
        "Abductors" -> R.string.fu_exercise_abductors
        "Abs" -> R.string.fu_exercise_abs
        "Adductors" -> R.string.fu_exercise_adductors
        "Ankle Stabilizers" -> R.string.fu_exercise_ankle_stabilizers
        "Ankles" -> R.string.fu_exercise_ankles
        "Assisted" -> R.string.fu_exercise_assisted
        "Back" -> R.string.fu_exercise_back
        "Band" -> R.string.fu_exercise_band
        "Barbell" -> R.string.fu_exercise_barbell
        "Biceps" -> R.string.fu_exercise_biceps
        "Body Weight" -> R.string.fu_exercise_body_weight
        "Bosu Ball" -> R.string.fu_exercise_bosu_ball
        "Brachialis" -> R.string.fu_exercise_brachialis
        "Cable" -> R.string.fu_exercise_cable
        "Calves" -> R.string.fu_exercise_calves
        "Cardio" -> R.string.fu_exercise_cardio
        "Cardiovascular System" -> R.string.fu_exercise_cardiovascular_system
        "Chest" -> R.string.fu_exercise_chest
        "Core" -> R.string.fu_exercise_core
        "Delts" -> R.string.fu_exercise_delts
        "Dumbbell" -> R.string.fu_exercise_dumbbell
        "Elliptical Machine" -> R.string.fu_exercise_elliptical_machine
        "Ez Barbell" -> R.string.fu_exercise_ez_barbell
        "Feet" -> R.string.fu_exercise_feet
        "Forearms" -> R.string.fu_exercise_forearms
        "Glutes" -> R.string.fu_exercise_glutes
        "Grip Muscles" -> R.string.fu_exercise_grip_muscles
        "Groin" -> R.string.fu_exercise_groin
        "Hammer" -> R.string.fu_exercise_hammer
        "Hamstrings" -> R.string.fu_exercise_hamstrings
        "Hands" -> R.string.fu_exercise_hands
        "Hip Flexors" -> R.string.fu_exercise_hip_flexors
        "Inner Thighs" -> R.string.fu_exercise_inner_thighs
        "Kettlebell" -> R.string.fu_exercise_kettlebell
        "Lats" -> R.string.fu_exercise_lats
        "Levator Scapulae" -> R.string.fu_exercise_levator_scapulae
        "Leverage Machine" -> R.string.fu_exercise_leverage_machine
        "Lower Abs" -> R.string.fu_exercise_lower_abs
        "Lower Arms" -> R.string.fu_exercise_lower_arms
        "Lower Back" -> R.string.fu_exercise_lower_back
        "Lower Legs" -> R.string.fu_exercise_lower_legs
        "Medicine Ball" -> R.string.fu_exercise_medicine_ball
        "Neck" -> R.string.fu_exercise_neck
        "Obliques" -> R.string.fu_exercise_obliques
        "Olympic Barbell" -> R.string.fu_exercise_olympic_barbell
        "Pectorals" -> R.string.fu_exercise_pectorals
        "Quadriceps" -> R.string.fu_exercise_quadriceps
        "Quads" -> R.string.fu_exercise_quads
        "Rear Deltoids" -> R.string.fu_exercise_rear_deltoids
        "Resistance Band" -> R.string.fu_exercise_resistance_band
        "Rhomboids" -> R.string.fu_exercise_rhomboids
        "Roller" -> R.string.fu_exercise_roller
        "Rope" -> R.string.fu_exercise_rope
        "Rotator Cuff" -> R.string.fu_exercise_rotator_cuff
        "Serratus Anterior" -> R.string.fu_exercise_serratus_anterior
        "Shins" -> R.string.fu_exercise_shins
        "Shoulders" -> R.string.fu_exercise_shoulders
        "Skierg Machine" -> R.string.fu_exercise_skierg_machine
        "Sled Machine" -> R.string.fu_exercise_sled_machine
        "Smith Machine" -> R.string.fu_exercise_smith_machine
        "Soleus" -> R.string.fu_exercise_soleus
        "Spine" -> R.string.fu_exercise_spine
        "Stability Ball" -> R.string.fu_exercise_stability_ball
        "Stationary Bike" -> R.string.fu_exercise_stationary_bike
        "Stepmill Machine" -> R.string.fu_exercise_stepmill_machine
        "Sternocleidomastoid" -> R.string.fu_exercise_sternocleidomastoid
        "Tire" -> R.string.fu_exercise_tire
        "Trap Bar" -> R.string.fu_exercise_trap_bar
        "Traps" -> R.string.fu_exercise_traps
        "Triceps" -> R.string.fu_exercise_triceps
        "Upper Arms" -> R.string.fu_exercise_upper_arms
        "Upper Back" -> R.string.fu_exercise_upper_back
        "Upper Body Ergometer" -> R.string.fu_exercise_upper_body_ergometer
        "Upper Chest" -> R.string.fu_exercise_upper_chest
        "Upper Legs" -> R.string.fu_exercise_upper_legs
        "Waist" -> R.string.fu_exercise_waist
        "Weighted" -> R.string.fu_exercise_weighted
        "Wheel Roller" -> R.string.fu_exercise_wheel_roller
        "Wrist Extensors" -> R.string.fu_exercise_wrist_extensors
        "Wrist Flexors" -> R.string.fu_exercise_wrist_flexors
        "Wrists" -> R.string.fu_exercise_wrists
        else -> null
    }

    fun label(res: Resources, value: String): String = labelRes(value)?.let(res::getString) ?: value

    /** A split group's name: its own resource, else the (muscle) title through [label]. */
    fun groupLabel(res: Resources, group: WorkoutSplitGroup): String = group.titleRes?.let(res::getString) ?: label(res, group.title)

    /** Comma-joined names ("Unspecified" when empty), like [com.ayuvo.health.data.ExerciseItem.primaryMusclesTitle]. */
    fun join(res: Resources, values: List<String>, empty: String = res.getString(R.string.ui_workout_unspecified)): String =
        if (values.isEmpty()) empty else values.joinToString(", ") { label(res, it) }
}
