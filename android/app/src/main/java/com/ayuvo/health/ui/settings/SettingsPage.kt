package com.ayuvo.health.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ayuvo.health.R
import com.ayuvo.health.ui.about.AboutSettingsCategory

/** Settings root sections, in root order (plan §6). Groups without a title render as a bare group. */
enum class SettingsGroup(@param:StringRes val titleRes: Int?, val tag: String) {
    HEALTH_PROFILE(R.string.settings_group_health_profile, "healthProfile"),
    TRACKING(R.string.settings_group_tracking, "tracking"),
    NOTIFICATIONS(null, "notifications"),
    DATA_PRIVACY(R.string.settings_group_data_privacy, "dataPrivacy"),
    AI_SPEECH(R.string.settings_group_ai_speech, "aiSpeech"),
    APPEARANCE(null, "appearance"),
    ABOUT(R.string.settings_group_about, "about");

    val pages: List<SettingsPage> get() = SettingsPage.entries.filter { it.group == this }
}

/**
 * One pushed Settings screen. [rawValue] matches iOS `SettingsPane` and backs the
 * `settings.category.<rawValue>` test tags. The selected page is saved by [name]
 * ([SelectedSettingsPageSaver]), so renaming an entry only resets the page, never crashes.
 */
enum class SettingsPage(
    val rawValue: String,
    @param:StringRes val titleRes: Int,
    val group: SettingsGroup,
    val icon: ImageVector,
    val tint: Color,
    val aboutCategory: AboutSettingsCategory? = null
) {
    PERSONAL_INFO("personalInfo", R.string.settings_section_personal, SettingsGroup.HEALTH_PROFILE, Icons.Filled.Person, SettingsTint.PersonalInfo),
    GOALS_TARGETS("goalsNutrition", R.string.settings_page_goals, SettingsGroup.HEALTH_PROFILE, Icons.Filled.TrackChanges, SettingsTint.Goals),
    UNITS("units", R.string.settings_page_units, SettingsGroup.HEALTH_PROFILE, Icons.Filled.Straighten, SettingsTint.Units),
    NUTRITION("nutritionTracking", R.string.settings_page_nutrition, SettingsGroup.TRACKING, Icons.Filled.Restaurant, SettingsTint.Nutrition),
    HYDRATION("hydration", R.string.settings_page_hydration, SettingsGroup.TRACKING, Icons.Filled.WaterDrop, SettingsTint.Hydration),
    FASTING("fasting", R.string.settings_page_fasting, SettingsGroup.TRACKING, Icons.Filled.Timer, SettingsTint.Fasting),
    ACTIVITY("activity", R.string.settings_page_activity, SettingsGroup.TRACKING, Icons.AutoMirrored.Filled.DirectionsWalk, SettingsTint.Activity),
    MEDICATIONS("medications", R.string.settings_page_medications, SettingsGroup.TRACKING, Icons.Filled.Medication, SettingsTint.Medications),
    NOTIFICATIONS("notifications", R.string.settings_notifications, SettingsGroup.NOTIFICATIONS, Icons.Filled.Notifications, SettingsTint.Notifications),
    HEALTH_SYNC("healthData", R.string.settings_page_health_sync, SettingsGroup.DATA_PRIVACY, Icons.Filled.Favorite, SettingsTint.HealthSync),
    HEALTH_RECORDS("healthRecords", R.string.settings_section_health_records, SettingsGroup.DATA_PRIVACY, Icons.Filled.Description, SettingsTint.Records),
    BACKUP_EXPORT("dataManagement", R.string.settings_page_backup_export, SettingsGroup.DATA_PRIVACY, Icons.Filled.Storage, SettingsTint.Backup),
    DELETE_DATA("deleteData", R.string.settings_delete_all_data, SettingsGroup.DATA_PRIVACY, Icons.Filled.Delete, SettingsTint.Destructive),
    AI_PROVIDERS("aiProviders", R.string.settings_page_ai_providers, SettingsGroup.AI_SPEECH, Icons.Filled.AutoAwesome, SettingsTint.Ai),
    SPEECH_TO_TEXT("speechToText", R.string.settings_section_speech, SettingsGroup.AI_SPEECH, Icons.Filled.Mic, SettingsTint.Speech),
    CUSTOM_INSTRUCTIONS("customInstructions", R.string.settings_page_custom_instructions, SettingsGroup.AI_SPEECH, Icons.Filled.FormatQuote, SettingsTint.Instructions),
    APPEARANCE("appearance", R.string.settings_appearance, SettingsGroup.APPEARANCE, Icons.Filled.Palette, SettingsTint.Appearance),
    APP_UPDATES("appUpdates", R.string.about_category_app_updates, SettingsGroup.ABOUT, AboutSettingsCategory.APP_UPDATES.icon, SettingsTint.About, AboutSettingsCategory.APP_UPDATES),
    HELP_SUPPORT("helpSupport", R.string.about_category_help_support, SettingsGroup.ABOUT, AboutSettingsCategory.HELP_SUPPORT.icon, SettingsTint.Help, AboutSettingsCategory.HELP_SUPPORT),
    LEGAL("legal", R.string.about_category_legal, SettingsGroup.ABOUT, AboutSettingsCategory.LEGAL.icon, SettingsTint.Legal, AboutSettingsCategory.LEGAL);

    /** Destructive root row (red title), like iOS. */
    val destructive: Boolean get() = this == DELETE_DATA

    val testTag: String get() = "settings.category.$rawValue"

    companion object {
        /** Restores a saved page name; unknown or blank names (renamed entries, old builds) mean the root. */
        fun fromName(name: String?): SettingsPage? =
            if (name.isNullOrBlank()) null else entries.firstOrNull { it.name == name }
    }
}

/** Saves the selected page by enum name ("" = root) so process death restores the same page. */
internal val SelectedSettingsPageSaver: Saver<MutableState<SettingsPage?>, String> = Saver(
    save = { it.value?.name ?: "" },
    restore = { mutableStateOf(SettingsPage.fromName(it)) }
)

/** One-shot request to open a Settings page from elsewhere (Browse › Health Sync footer). */
data class SettingsPageRequest(val page: SettingsPage, val id: Long = System.nanoTime())
