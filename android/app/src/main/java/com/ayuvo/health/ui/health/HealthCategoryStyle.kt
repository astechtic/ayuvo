package com.ayuvo.health.ui.health

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ayuvo.health.R
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.ui.design.AyuvoColors

/** Icons, tints and display names per category / type — Compose-side only, so the registry stays pure. */
object HealthCategoryStyle {

    fun icon(category: HealthCategory): ImageVector = when (category) {
        HealthCategory.ACTIVITY -> Icons.Outlined.LocalFireDepartment
        HealthCategory.BODY -> Icons.Outlined.Accessibility
        HealthCategory.CYCLE_TRACKING -> Icons.Outlined.WaterDrop
        HealthCategory.HEARING -> Icons.Outlined.Hearing
        HealthCategory.HEART -> Icons.Outlined.Favorite
        HealthCategory.MENTAL_WELLBEING -> Icons.Outlined.SelfImprovement
        HealthCategory.MOBILITY -> Icons.AutoMirrored.Outlined.DirectionsWalk
        HealthCategory.NUTRITION -> Icons.Outlined.Restaurant
        HealthCategory.RESPIRATORY -> Icons.Outlined.Air
        HealthCategory.SLEEP -> Icons.Outlined.Bedtime
        HealthCategory.SYMPTOMS -> Icons.Outlined.HealthAndSafety
        HealthCategory.VITALS -> Icons.Outlined.MonitorHeart
        HealthCategory.OTHER -> Icons.Outlined.Thermostat
    }

    /** Apple-Health-style category tints (fixed, independent of the app theme colour). */
    fun tint(category: HealthCategory): Color = AyuvoColors.category(category)

    fun categoryName(context: Context, category: HealthCategory): String = context.getString(
        when (category) {
            HealthCategory.ACTIVITY -> R.string.health_category_activity
            HealthCategory.BODY -> R.string.health_category_body
            HealthCategory.CYCLE_TRACKING -> R.string.health_category_cycle_tracking
            HealthCategory.HEARING -> R.string.health_category_hearing
            HealthCategory.HEART -> R.string.health_category_heart
            HealthCategory.MENTAL_WELLBEING -> R.string.health_category_mental_wellbeing
            HealthCategory.MOBILITY -> R.string.health_category_mobility
            HealthCategory.NUTRITION -> R.string.health_category_nutrition
            HealthCategory.RESPIRATORY -> R.string.health_category_respiratory
            HealthCategory.SLEEP -> R.string.health_category_sleep
            HealthCategory.SYMPTOMS -> R.string.health_category_symptoms
            HealthCategory.VITALS -> R.string.health_category_vitals
            HealthCategory.OTHER -> R.string.health_category_other
        }
    )

    private val typeNames: Map<String, Int> = mapOf(
        "steps" to R.string.health_type_steps,
        "distance" to R.string.health_type_distance,
        "wheelchair_pushes" to R.string.health_type_wheelchair_pushes,
        "floors_climbed" to R.string.health_type_floors_climbed,
        "elevation_gained" to R.string.health_type_elevation_gained,
        "active_energy" to R.string.health_type_active_energy,
        "total_energy" to R.string.health_type_total_energy,
        "workout" to R.string.health_type_workout,
        "planned_workout" to R.string.health_type_planned_workout,
        "speed" to R.string.health_type_speed,
        "power" to R.string.health_type_power,
        "cycling_cadence" to R.string.health_type_cycling_cadence,
        "step_cadence" to R.string.health_type_step_cadence,
        "weight" to R.string.health_type_weight,
        "height" to R.string.health_type_height,
        "body_fat" to R.string.health_type_body_fat,
        "lean_body_mass" to R.string.health_type_lean_body_mass,
        "bone_mass" to R.string.health_type_bone_mass,
        "body_water_mass" to R.string.health_type_body_water_mass,
        "basal_metabolic_rate" to R.string.health_type_basal_metabolic_rate,
        "heart_rate" to R.string.health_type_heart_rate,
        "resting_heart_rate" to R.string.health_type_resting_heart_rate,
        "hrv_rmssd" to R.string.health_type_hrv_rmssd,
        "hrv_sdnn" to R.string.health_type_hrv_sdnn,
        "blood_pressure" to R.string.health_type_blood_pressure,
        "vo2_max" to R.string.health_type_vo2_max,
        "sleep" to R.string.health_type_sleep,
        "blood_glucose" to R.string.health_type_blood_glucose,
        "body_temperature" to R.string.health_type_body_temperature,
        "skin_temperature" to R.string.health_type_skin_temperature,
        "respiratory_rate" to R.string.health_type_respiratory_rate,
        "blood_oxygen" to R.string.health_type_blood_oxygen,
        "hydration" to R.string.health_type_hydration,
        "nutrition" to R.string.health_type_nutrition,
        "menstrual_flow" to R.string.health_type_menstrual_flow,
        "menstruation_period" to R.string.health_type_menstruation_period,
        "intermenstrual_bleeding" to R.string.health_type_intermenstrual_bleeding,
        "ovulation_test" to R.string.health_type_ovulation_test,
        "cervical_mucus" to R.string.health_type_cervical_mucus,
        "sexual_activity" to R.string.health_type_sexual_activity,
        "basal_body_temperature" to R.string.health_type_basal_body_temperature,
        "mindfulness_session" to R.string.health_type_mindfulness_session,
        "resting_energy" to R.string.health_type_resting_energy,
        "exercise_minutes" to R.string.health_type_exercise_minutes,
        "stand_minutes" to R.string.health_type_stand_minutes,
        "bmi" to R.string.health_type_bmi,
        "walking_heart_rate_average" to R.string.health_type_walking_heart_rate_average
    )

    /** Display name for a registry slug or raw id; imported/iOS-only ids fall back to a humanised slug. */
    fun typeName(context: Context, typeId: String, displayNameHint: String? = null): String {
        typeNames[typeId]?.let { return context.getString(it) }
        displayNameHint?.takeIf { it.isNotBlank() }?.let { return it }
        return HealthDataType.byId(typeId)?.displayFallback() ?: HealthDataType.humanise(typeId.substringAfterLast('.'))
    }

    fun tintFor(typeId: String): Color =
        HealthDataType.byId(typeId)?.let { tint(it.category) } ?: tint(HealthCategory.OTHER)
}
