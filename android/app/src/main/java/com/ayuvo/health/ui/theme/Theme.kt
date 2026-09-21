package com.ayuvo.health.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ayuvo.health.ui.design.AyuvoMaterialShapes
import com.ayuvo.health.ui.design.AyuvoPalette

// Apple-flat neutral scheme: grouped background, plain cards, and every M3
// surfaceContainer role pinned so sheets, menus, dialogs and the navigation bar
// never pick up Material's tinted tones. The theme accent only drives primary.
private fun lightColors(themeColor: AppThemeColor) = lightColorScheme(
    primary = themeColor.start,
    onPrimary = Color.White,
    primaryContainer = themeColor.start.copy(alpha = 0.15f),
    onPrimaryContainer = themeColor.start,
    secondary = themeColor.start,
    onSecondary = Color.White,
    secondaryContainer = themeColor.start.copy(alpha = 0.15f),
    onSecondaryContainer = themeColor.start,
    tertiary = themeColor.start,
    onTertiary = Color.White,
    background = AppColors.AppBackgroundLight,
    onBackground = AppColors.OnLight,
    surface = AppColors.AppCardLight,
    onSurface = AppColors.OnLight,
    surfaceVariant = AppColors.AppCardLight,
    onSurfaceVariant = AppColors.MutedLight,
    surfaceTint = Color.Transparent,
    surfaceBright = AppColors.AppCardLight,
    surfaceDim = AppColors.AppBackgroundLight,
    surfaceContainerLowest = AppColors.AppCardLight,
    surfaceContainerLow = AppColors.AppCardLight,
    surfaceContainer = AppColors.AppCardLight,
    surfaceContainerHigh = AppColors.AppCardLight,
    surfaceContainerHighest = AyuvoPalette.FillLight,
    outline = AppColors.DividerLight,
    outlineVariant = AyuvoPalette.SeparatorLight,
    error = AyuvoPalette.Destructive
)

private fun darkColors(themeColor: AppThemeColor) = darkColorScheme(
    primary = themeColor.start,
    onPrimary = Color.White,
    primaryContainer = themeColor.start.copy(alpha = 0.22f),
    onPrimaryContainer = themeColor.start,
    secondary = themeColor.start,
    onSecondary = Color.White,
    secondaryContainer = themeColor.start.copy(alpha = 0.22f),
    onSecondaryContainer = themeColor.start,
    tertiary = themeColor.start,
    onTertiary = Color.White,
    background = AppColors.AppBackgroundDark,
    onBackground = AppColors.OnDark,
    surface = AppColors.AppCardDark,
    onSurface = AppColors.OnDark,
    surfaceVariant = AppColors.AppCardDark,
    onSurfaceVariant = AppColors.MutedDark,
    surfaceTint = Color.Transparent,
    surfaceBright = AyuvoPalette.CardElevatedDark,
    surfaceDim = AppColors.AppBackgroundDark,
    surfaceContainerLowest = AppColors.AppBackgroundDark,
    surfaceContainerLow = AppColors.AppCardDark,
    surfaceContainer = AppColors.AppCardDark,
    surfaceContainerHigh = AyuvoPalette.CardElevatedDark,
    surfaceContainerHighest = AyuvoPalette.CardElevatedDark,
    outline = AppColors.DividerDark,
    outlineVariant = AyuvoPalette.SeparatorDark,
    error = Color(0xFFFF453A)
)

@Composable
fun AyuvoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: AppThemeColor = AppThemeColor.ROSE,
    content: @Composable () -> Unit
) {
    AppColors.setThemeColor(themeColor)
    val colorScheme = if (darkTheme) darkColors(themeColor) else lightColors(themeColor)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AyuvoMaterialShapes,
        content = content
    )
}
