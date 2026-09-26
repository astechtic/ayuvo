package com.ayuvo.health.ui.design

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import com.ayuvo.health.R
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.ui.theme.AppColors

/**
 * Fixed Apple-Health-style domain colours. These never follow the user's theme
 * accent; the accent ([AppColors.Calorie]) is reserved for primary actions, the
 * selected tab and chart scrub markers (docs/ui-structure.md).
 */
object AyuvoPalette {
    val Nutrition = Color(0xFF34C759)
    val Hydration = Color(0xFF007AFF)
    val Fasting = Color(0xFF00C7BE)
    val Body = Color(0xFFAF52DE)
    val Activity = Color(0xFFFF9500)
    val Heart = Color(0xFFFF3B30)
    val Sleep = Color(0xFF5E5CE6)
    val Vitals = Color(0xFFFF2D55)
    val Respiratory = Color(0xFF5AC8FA)
    val Cycle = Color(0xFFFF2D55)
    val Mindfulness = Color(0xFF30B0C7)
    val Mobility = Color(0xFFFF9500)
    val Hearing = Color(0xFF007AFF)
    val Symptoms = Color(0xFFAF52DE)
    val Medications = Color(0xFF32ADE6)
    val Records = Color(0xFF5856D6)
    val Insights = Color(0xFFE08A00)
    val Other = Color(0xFF8E8E93)

    val Protein = Color(0xFF007AFF)
    val Carbs = Color(0xFFFF9F0A)
    val Fat = Color(0xFFBF5AF2)
    val Fiber = Color(0xFF30B0C7)

    val Destructive = Color(0xFFFF3B30)
    val Warning = Color(0xFFFF9500)
    val Success = Color(0xFF34C759)

    val GroupedBgLight = Color(0xFFF2F2F7)
    val GroupedBgDark = Color(0xFF000000)
    val CardLight = Color(0xFFFFFFFF)
    val CardDark = Color(0xFF1C1C1E)
    val CardElevatedDark = Color(0xFF2C2C2E)
    val FillLight = Color(0xFFEEEEF0)
    val FillDark = Color(0xFF2C2C2E)
    val SeparatorLight = Color(0xFFC6C6C8)
    val SeparatorDark = Color(0xFF38383A)
}

/** The Browse domains, in Browse order. */
enum class AyuvoCategory(
    val color: Color,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int
) {
    INSIGHTS(AyuvoPalette.Insights, Icons.Outlined.Insights, R.string.domain_insights),
    NUTRITION(AyuvoPalette.Nutrition, Icons.Outlined.Restaurant, R.string.domain_nutrition),
    HYDRATION(AyuvoPalette.Hydration, Icons.Outlined.WaterDrop, R.string.domain_hydration),
    FASTING(AyuvoPalette.Fasting, Icons.Outlined.Timer, R.string.domain_fasting),
    ACTIVITY(AyuvoPalette.Activity, Icons.Outlined.LocalFireDepartment, R.string.domain_activity),
    BODY(AyuvoPalette.Body, Icons.Outlined.Accessibility, R.string.domain_body),
    HEART(AyuvoPalette.Heart, Icons.Outlined.Favorite, R.string.domain_heart),
    SLEEP(AyuvoPalette.Sleep, Icons.Outlined.Bedtime, R.string.domain_sleep),
    VITALS(AyuvoPalette.Vitals, Icons.Outlined.MonitorHeart, R.string.domain_vitals),
    RESPIRATORY(AyuvoPalette.Respiratory, Icons.Outlined.Air, R.string.domain_respiratory),
    CYCLE(AyuvoPalette.Cycle, Icons.Outlined.Loop, R.string.domain_cycle),
    MINDFULNESS(AyuvoPalette.Mindfulness, Icons.Outlined.SelfImprovement, R.string.domain_mindfulness),
    MOBILITY(AyuvoPalette.Mobility, Icons.AutoMirrored.Outlined.DirectionsWalk, R.string.domain_mobility),
    HEARING(AyuvoPalette.Hearing, Icons.Outlined.Hearing, R.string.domain_hearing),
    SYMPTOMS(AyuvoPalette.Symptoms, Icons.Outlined.HealthAndSafety, R.string.domain_symptoms),
    MEDICATIONS(AyuvoPalette.Medications, Icons.Outlined.Medication, R.string.domain_medications),
    RECORDS(AyuvoPalette.Records, Icons.Outlined.Description, R.string.domain_records),
    OTHER(AyuvoPalette.Other, Icons.Outlined.MoreHoriz, R.string.domain_other)
}

fun HealthCategory.toAyuvoCategory(): AyuvoCategory = when (this) {
    HealthCategory.ACTIVITY -> AyuvoCategory.ACTIVITY
    HealthCategory.BODY -> AyuvoCategory.BODY
    HealthCategory.CYCLE_TRACKING -> AyuvoCategory.CYCLE
    HealthCategory.HEARING -> AyuvoCategory.HEARING
    HealthCategory.HEART -> AyuvoCategory.HEART
    HealthCategory.MENTAL_WELLBEING -> AyuvoCategory.MINDFULNESS
    HealthCategory.MOBILITY -> AyuvoCategory.MOBILITY
    HealthCategory.NUTRITION -> AyuvoCategory.NUTRITION
    HealthCategory.RESPIRATORY -> AyuvoCategory.RESPIRATORY
    HealthCategory.SLEEP -> AyuvoCategory.SLEEP
    HealthCategory.SYMPTOMS -> AyuvoCategory.SYMPTOMS
    HealthCategory.VITALS -> AyuvoCategory.VITALS
    HealthCategory.OTHER -> AyuvoCategory.OTHER
}

object AyuvoColors {
    fun category(category: AyuvoCategory): Color = category.color

    fun category(category: HealthCategory): Color = category(category.toAyuvoCategory())

    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    @Composable
    @ReadOnlyComposable
    fun groupedBackground(): Color = MaterialTheme.colorScheme.background

    @Composable
    @ReadOnlyComposable
    fun card(): Color = MaterialTheme.colorScheme.surface

    /** Secondary fill: text fields, chips, inner panels. */
    @Composable
    @ReadOnlyComposable
    fun fill(): Color = if (isDark()) AyuvoPalette.FillDark else AyuvoPalette.FillLight

    @Composable
    @ReadOnlyComposable
    fun separator(): Color = MaterialTheme.colorScheme.outlineVariant

    @Composable
    @ReadOnlyComposable
    fun secondaryLabel(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    @Composable
    @ReadOnlyComposable
    fun tertiaryLabel(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    /** Container colour for modal bottom sheets. */
    @Composable
    @ReadOnlyComposable
    fun sheetBackground(): Color = if (isDark()) AyuvoPalette.CardDark else AyuvoPalette.GroupedBgLight

    fun accent(): Color = AppColors.Calorie
}
