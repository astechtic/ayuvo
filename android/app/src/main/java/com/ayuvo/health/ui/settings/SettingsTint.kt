package com.ayuvo.health.ui.settings

import androidx.compose.ui.graphics.Color
import com.ayuvo.health.ui.design.AyuvoPalette

/**
 * Fixed iOS-Settings-style icon square colours for every Settings row (white glyph on a filled
 * rounded square, see `CategoryIcon`). Rows that belong to a health domain use the domain colour
 * from [AyuvoPalette]; everything else uses an Apple system colour with a fixed meaning. Never the
 * theme accent, so the icons read the same whatever colour the user picks. Same mapping as iOS.
 */
internal object SettingsTint {
    // Apple system colours.
    val Gray = AyuvoPalette.Other // #8E8E93
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)
    val Pink = Color(0xFFFF2D55)
    val Purple = Color(0xFFAF52DE)
    val Indigo = Color(0xFF5E5CE6)

    // Health domains.
    val Nutrition = AyuvoPalette.Nutrition
    val Hydration = AyuvoPalette.Hydration
    val Fasting = AyuvoPalette.Fasting
    val Body = AyuvoPalette.Body
    val Activity = AyuvoPalette.Activity
    val Vitals = AyuvoPalette.Vitals
    val Medications = AyuvoPalette.Medications
    val Records = AyuvoPalette.Records
    val Protein = AyuvoPalette.Protein
    val Carbs = AyuvoPalette.Carbs
    val Fat = AyuvoPalette.Fat
    val Fiber = AyuvoPalette.Fiber

    // Roles.
    val PersonalInfo = Gray
    val Goals = Nutrition
    val Units = Orange
    val Notifications = Red
    val HealthSync = Vitals
    val Backup = Blue
    val Destructive = AyuvoPalette.Destructive
    val Warning = AyuvoPalette.Warning
    val Ai = Purple
    val Speech = Orange
    val Instructions = Indigo
    val Appearance = Blue
    val About = Gray
    val Help = Green
    val Legal = Gray
}
