package com.ayuvo.health.ui.workouts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.theme.AppColors

/**
 * Workouts theme bridge — the exercise library is ported from Delts
 * (the Delts workout app), whose screens read a small resolved palette
 * (`LocalDeltsColors.current`). This file re-implements that exact field surface
 * on top of Ayuvo's theme (AppColors + the user-selectable accent), so the ported
 * screens render with Ayuvo's default look while keeping their code unchanged.
 */
data class WorkoutsColors(
    val background: Color,
    val charcoal: Color,
    val card: Color,
    val panel: Color,
    val hairline: Color,
    val accent: Color,
    val secondaryAccent: Color,
    val onAccent: Color,
    val mutedText: Color,
    val isDark: Boolean,
    /** Fixed Activity domain colour for workout decoration (not the theme accent). */
    val domain: Color = AyuvoPalette.Activity
)

@Composable
fun workoutsColors(): WorkoutsColors {
    // Same dark-detection trick as AppBottomNavBar: the resolved background
    // luminance tracks the user's appearance override, not just the system.
    val bg = MaterialTheme.colorScheme.background
    val isDark = (bg.red + bg.green + bg.blue) / 3f < 0.5f

    return WorkoutsColors(
        background = if (isDark) AppColors.AppBackgroundDark else AppColors.AppBackgroundLight,
        charcoal = if (isDark) AppColors.OnDark else AppColors.OnLight,
        card = if (isDark) AppColors.AppCardDark else AppColors.AppCardLight,
        panel = if (isDark) AyuvoPalette.FillDark else AyuvoPalette.FillLight,
        hairline = if (isDark) AyuvoPalette.SeparatorDark else AyuvoPalette.SeparatorLight,
        accent = AppColors.Calorie,
        secondaryAccent = AppColors.Calorie,
        onAccent = Color.White,
        mutedText = if (isDark) AppColors.MutedDark else AppColors.MutedLight,
        isDark = isDark
    )
}
