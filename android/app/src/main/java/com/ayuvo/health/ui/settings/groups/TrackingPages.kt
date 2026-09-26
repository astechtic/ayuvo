package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import com.ayuvo.health.ui.home.SheetTimePickerDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.navigation.AppRoutes
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.settings.SettingsSheet
import java.text.NumberFormat

/** Tracking › Nutrition: meal times, logging defaults, Quick Actions and the + Menu. */
@Composable
internal fun NutritionTrackingPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val state = ctx.state
    val tint = SettingsPage.NUTRITION.tint
    val infoLabel = stringResource(R.string.action_info)
    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.settings_meal_times),
                value = stringResource(R.string.settings_meal_times_customize),
                icon = Icons.Filled.Schedule, iconTint = tint,
                modifier = Modifier.settingsRow("mealTimes"),
                onClick = { state.sheet = SettingsSheet.MEAL_TIMES }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_default_to_grams),
                icon = Icons.Filled.LocalDining, iconTint = tint,
                modifier = Modifier.settingsRow("defaultToGrams"),
                trailing = RowTrailing.Custom {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InfoTrailing(infoLabel) { state.showDefaultGramsInfo = true }
                        SettingsSwitch(checked = ui.preferGramsByDefault, onChange = ctx.vm::setPreferGramsByDefault)
                    }
                }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_save_photos_to_gallery),
                subtitle = stringResource(R.string.settings_save_photos_to_gallery_subtitle),
                icon = Icons.Filled.Download, iconTint = tint,
                modifier = Modifier.settingsRow("saveToPhotos"),
                trailing = RowTrailing.Toggle(ui.saveMealPhotosToGallery, ctx.actions.onSavePhotosToGalleryChanged)
            )
        }
    }
    InsetGroup(footer = stringResource(R.string.settings_add_menu_summary_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_quick_actions),
                value = stringResource(R.string.settings_meal_times_customize),
                icon = Icons.Filled.Bolt, iconTint = tint,
                modifier = Modifier.settingsRow("quickActions"),
                onClick = { ctx.actions.navigate(AppRoutes.QUICK_ACTIONS) }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_add_menu_title),
                value = stringResource(R.string.settings_meal_times_customize),
                icon = Icons.Filled.Add, iconTint = tint,
                modifier = Modifier.settingsRow("addMenu"),
                onClick = { ctx.actions.navigate(AppRoutes.ADD_MENU) }
            )
        }
    }
}

/** Tracking › Hydration: water tracking (also the Drink ring) and the daily goal. */
@Composable
internal fun HydrationPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val tint = SettingsPage.HYDRATION.tint
    InsetGroup(footer = stringResource(R.string.settings_hydration_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_water_tracking),
                icon = Icons.Filled.WaterDrop, iconTint = tint,
                modifier = Modifier.settingsRow("waterTracking"),
                trailing = RowTrailing.Toggle(ui.waterTrackingEnabled, ctx.vm::setWaterTrackingEnabled)
            )
        }
        if (ui.waterTrackingEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_water_goal),
                    value = ui.waterUnit.format(ui.waterDailyGoalMl),
                    icon = Icons.Filled.TrackChanges, iconTint = tint,
                    modifier = Modifier.settingsRow("waterGoal"),
                    onClick = { ctx.state.sheet = SettingsSheet.WATER_GOAL }
                )
            }
        }
    }
}

/** Tracking › Fasting: fasting tracking and the default goal. */
@Composable
internal fun FastingTrackingPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val tint = SettingsPage.FASTING.tint
    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.settings_fasting_tracking),
                icon = Icons.Filled.Timer, iconTint = tint,
                modifier = Modifier.settingsRow("fastingTracking"),
                trailing = RowTrailing.Toggle(ui.fastingTrackingEnabled, ctx.vm::setFastingTrackingEnabled)
            )
        }
        if (ui.fastingTrackingEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_fasting_goal),
                    value = stringResource(R.string.settings_fasting_goal_value, ui.fastingDefaultGoalMinutes / 60),
                    icon = Icons.Filled.TrackChanges, iconTint = tint,
                    modifier = Modifier.settingsRow("fastingGoal"),
                    onClick = { ctx.state.sheet = SettingsSheet.FASTING_GOAL }
                )
            }
        }
    }
}

/** Tracking › Activity: the daily step goal (Summary Move ring) and workout logging preferences. */
@Composable
internal fun ActivityPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val state = ctx.state
    val tint = SettingsPage.ACTIVITY.tint
    InsetGroup(footer = stringResource(R.string.settings_daily_step_goal_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_daily_step_goal),
                value = stringResource(R.string.settings_steps_value, NumberFormat.getIntegerInstance().format(ui.dailyStepGoal)),
                icon = Icons.AutoMirrored.Filled.DirectionsWalk, iconTint = tint,
                modifier = Modifier.settingsRow("dailyStepGoal"),
                onClick = { state.sheet = SettingsSheet.STEP_GOAL }
            )
        }
    }
    InsetGroup(
        header = stringResource(R.string.settings_workout_logging),
        footer = stringResource(R.string.settings_rpe_guide)
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_training_split),
                value = ui.workoutSplit.title,
                icon = Icons.Filled.FitnessCenter, iconTint = tint,
                modifier = Modifier.settingsRow("trainingSplit"),
                onClick = { state.sheet = SettingsSheet.WORKOUT_SPLIT }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_rpe_scale),
                value = ui.workoutRpeScale.title,
                icon = Icons.Filled.Speed, iconTint = tint,
                modifier = Modifier.settingsRow("rpeScale"),
                onClick = { state.sheet = SettingsSheet.WORKOUT_RPE }
            )
        }
    }
    InsetGroup(footer = stringResource(R.string.settings_walk_run_quick_log_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_walk_run_quick_log),
                icon = Icons.AutoMirrored.Filled.DirectionsWalk, iconTint = tint,
                modifier = Modifier.settingsRow("walkRunQuickLog"),
                trailing = RowTrailing.Toggle(ui.walkRunQuickLogEnabled, ctx.vm::setWalkRunQuickLogEnabled)
            )
        }
    }
}

/** Tracking › Medications: shortcut into Browse › Medications and its reminder settings. */
@Composable
internal fun MedicationsSettingsPage(ctx: SettingsPageContext) {
    val tint = SettingsPage.MEDICATIONS.tint
    InsetGroup(footer = stringResource(R.string.settings_medications_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_open_medications),
                icon = Icons.Filled.Medication, iconTint = tint,
                modifier = Modifier.settingsRow("openMedications"),
                onClick = ctx.actions.openMedications
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_dose_reminders),
                icon = Icons.Filled.NotificationsActive, iconTint = tint,
                modifier = Modifier.settingsRow("medicationReminders"),
                onClick = { ctx.actions.openPage(SettingsPage.NOTIFICATIONS) }
            )
        }
    }
}

/**
 * Tracking › Insights (docs/insights.md): show or hide Insights, the opt-in morning Recovery
 * notification and the Daily Review time (the existing daily-summary hour and minute). Only
 * switches are stored; scores are always recomputed.
 */
@Composable
internal fun InsightsSettingsPage(ctx: SettingsPageContext) {
    val prefs = ctx.container.prefs
    val scope = rememberCoroutineScope()
    val tint = SettingsPage.INSIGHTS.tint
    val enabled by prefs.insightsEnabled.collectAsState(initial = true)
    val morning by prefs.insightsMorningNotification.collectAsState(initial = false)
    val hour by prefs.dailySummaryHour.collectAsState(initial = 21)
    val minute by prefs.dailySummaryMinute.collectAsState(initial = 0)
    var pickTime by remember { mutableStateOf(false) }
    val context = LocalContext.current
    InsetGroup(footer = stringResource(R.string.settings_insights_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_insights_enabled),
                subtitle = stringResource(R.string.settings_insights_enabled_sub),
                icon = Icons.Filled.Insights, iconTint = tint,
                modifier = Modifier.settingsRow("insightsEnabled"),
                trailing = RowTrailing.Toggle(enabled, { v -> scope.launch { prefs.setInsightsEnabled(v) } })
            )
        }
        if (enabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_insights_morning),
                    subtitle = stringResource(R.string.settings_insights_morning_sub),
                    icon = Icons.Filled.NotificationsActive, iconTint = tint,
                    modifier = Modifier.settingsRow("insightsMorning"),
                    trailing = RowTrailing.Toggle(morning, ctx.actions.onInsightsMorningToggle)
                )
            }
            row {
                GroupRow(
                    title = stringResource(R.string.settings_insights_review_time),
                    value = DateFormat.getTimeFormat(context).format(
                        java.util.Date.from(LocalTime.of(hour, minute).atDate(java.time.LocalDate.now()).atZone(java.time.ZoneId.systemDefault()).toInstant())
                    ),
                    icon = Icons.Filled.Schedule, iconTint = tint,
                    modifier = Modifier.settingsRow("insightsReviewTime"),
                    onClick = { pickTime = true }
                )
            }
        }
    }
    if (pickTime) {
        SheetTimePickerDialog(
            initialTime = LocalTime.of(hour, minute),
            onConfirm = { time ->
                pickTime = false
                scope.launch {
                    prefs.setDailySummaryHour(time.hour)
                    prefs.setDailySummaryMinute(time.minute)
                    val notifications = ctx.container.notifications
                    if (prefs.notificationsEnabled.first() && prefs.dailySummaryEnabled.first() && notifications.canPostNotifications()) {
                        notifications.scheduleDailySummary(time.hour, time.minute)
                    }
                }
            },
            onDismiss = { pickTime = false }
        )
    }
}
