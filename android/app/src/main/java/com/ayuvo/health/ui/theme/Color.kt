package com.ayuvo.health.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ayuvo.health.R

enum class AppThemeColor(
    val key: String,
    @param:StringRes val displayNameRes: Int,
    val start: Color,
    val end: Color
) {
    ROSE("rose", R.string.theme_color_rose, Color(0xFFFF375F), Color(0xFFFF6B8A)),
    RED("red", R.string.theme_color_red, Color(0xFFFF3B30), Color(0xFFFF6961)),
    ORANGE("orange", R.string.theme_color_orange, Color(0xFFFF9500), Color(0xFFFFB340)),
    GREEN("green", R.string.theme_color_green, Color(0xFF34C759), Color(0xFF62D46F)),
    MINT("mint", R.string.theme_color_mint, Color(0xFF00C7BE), Color(0xFF66D4CF)),
    TEAL("teal", R.string.theme_color_teal, Color(0xFF30B0C7), Color(0xFF64D2FF)),
    BLUE("blue", R.string.theme_color_blue, Color(0xFF0A84FF), Color(0xFF5EAEFF)),
    PURPLE("purple", R.string.theme_color_purple, Color(0xFFAF52DE), Color(0xFFBF5AF2)),
    YELLOW("yellow", R.string.theme_color_yellow, Color(0xFFFFCC00), Color(0xFFFFD60A)),
    CORAL("coral", R.string.theme_color_coral, Color(0xFFFF7F50), Color(0xFFFFA382)),
    ROSE_GOLD("roseGold", R.string.theme_color_rose_gold, Color(0xFFC9807C), Color(0xFFE8B4B0)),
    MOCHA_BROWN("mochaBrown", R.string.theme_color_mocha_brown, Color(0xFFA2845E), Color(0xFFC9A57E)),
    INDIGO("indigo", R.string.theme_color_indigo, Color(0xFF5856D6), Color(0xFF7D7AFF)),
    LAVENDER("lavender", R.string.theme_color_lavender, Color(0xFFB57EDC), Color(0xFFD0A9F5)),
    SKY_CYAN("skyCyan", R.string.theme_color_sky_cyan, Color(0xFF32ADE6), Color(0xFF70CFFF)),
    GRAPHITE("graphite", R.string.theme_color_graphite, Color(0xFF8E8E93), Color(0xFFB8B8BE)),
    BABY_PINK("babyPink", R.string.theme_color_baby_pink, Color(0xFFFF8FAB), Color(0xFFFFB3C6)),
    LIME("lime", R.string.theme_color_lime, Color(0xFFA0D911), Color(0xFFC3E956));

    companion object {
        const val DEFAULT_KEY = "rose"

        fun fromKey(key: String?): AppThemeColor =
            values().firstOrNull { it.key == key } ?: ROSE
    }
}

object AppColors {
    private var activeThemeColor: AppThemeColor = AppThemeColor.ROSE

    fun setThemeColor(themeColor: AppThemeColor) {
        activeThemeColor = themeColor
    }

    val ThemeColor: AppThemeColor
        get() = activeThemeColor

    val CalorieStart: Color
        get() = activeThemeColor.start

    val CalorieEnd: Color
        get() = activeThemeColor.end

    val Calorie: Color
        get() = CalorieStart

    // Macros use fixed domain colours (Apple Health style); the theme accent is
    // reserved for primary actions, the selected tab and chart scrub markers.
    val Protein: Color = Color(0xFF007AFF)

    val Carbs: Color = Color(0xFFFF9F0A)

    val Fat: Color = Color(0xFFBF5AF2)

    val Fiber: Color = Color(0xFF30B0C7)

    val CalorieGradient: Brush
        get() = Brush.linearGradient(listOf(CalorieStart, CalorieEnd))

    val AppBackgroundLight = Color(0xFFF2F2F7)
    val AppBackgroundDark = Color(0xFF000000)

    val AppCardLight = Color(0xFFFFFFFF)
    val AppCardDark = Color(0xFF1C1C1E)

    val OnLight = Color(0xFF1C1C1E)
    val OnDark = Color(0xFFF2F2F7)

    val MutedLight = Color(0xFF8E8E93)
    val MutedDark = Color(0xFF8E8E93)

    val DividerLight = Color(0xFFC6C6C8)
    val DividerDark = Color(0xFF38383A)
}
