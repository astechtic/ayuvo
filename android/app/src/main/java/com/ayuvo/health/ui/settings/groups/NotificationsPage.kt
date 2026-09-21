package com.ayuvo.health.ui.settings.groups

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.ui.components.OptionPickerSheet
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext

/**
 * Notifications: the master switch, then every reminder type, medication reminder options
 * (docs/medications.md §10) and the Android-only exact timing and battery optimisation rows.
 */
@Composable
internal fun NotificationsPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val vm = ctx.vm
    val tint = SettingsPage.NOTIFICATIONS.tint
    var showSnoozeSheet by remember { mutableStateOf(false) }
    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.settings_notifications),
                icon = Icons.Filled.Notifications, iconTint = tint,
                modifier = Modifier.settingsRow("notifications"),
                trailing = RowTrailing.Toggle(ui.notificationsEnabled, ctx.actions.onNotificationsToggle)
            )
        }
    }
    if (!ui.notificationsEnabled) return

    val noneSelected = !ui.streakReminderEnabled &&
        !ui.dailySummaryEnabled &&
        !ui.weightReminderEnabled &&
        !ui.bodyFatReminderEnabled &&
        (!ui.waterTrackingEnabled || !ui.waterReminderEnabled) &&
        (!ui.fastingTrackingEnabled || !ui.fastingGoalNotificationEnabled) &&
        !ui.medicationRemindersEnabled &&
        !ui.goalReachedNotificationsEnabled &&
        !ui.appUpdateNotificationsEnabled
    InsetGroup(
        header = stringResource(R.string.settings_notification_types),
        footer = if (noneSelected) stringResource(R.string.settings_notif_none_selected) else null
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_food_reminders),
                icon = Icons.Filled.LocalDining, iconTint = tint,
                modifier = Modifier.settingsRow("streakReminder"),
                trailing = RowTrailing.Toggle(ui.streakReminderEnabled, vm::setStreakReminderEnabled)
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_daily_summary),
                icon = Icons.Filled.GraphicEq, iconTint = tint,
                modifier = Modifier.settingsRow("dailySummary"),
                trailing = RowTrailing.Toggle(ui.dailySummaryEnabled, ctx.actions.onDailySummaryToggle)
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_weight_reminder),
                icon = Icons.Filled.MonitorWeight, iconTint = tint,
                modifier = Modifier.settingsRow("weightReminder"),
                trailing = RowTrailing.Toggle(ui.weightReminderEnabled, vm::setWeightReminderEnabled)
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_body_fat_reminder),
                icon = Icons.Filled.Percent, iconTint = tint,
                modifier = Modifier.settingsRow("bodyFatReminder"),
                trailing = RowTrailing.Toggle(ui.bodyFatReminderEnabled, vm::setBodyFatReminderEnabled)
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_goal_alerts),
                icon = Icons.Filled.TrackChanges, iconTint = tint,
                modifier = Modifier.settingsRow("goalAlerts"),
                trailing = RowTrailing.Toggle(ui.goalReachedNotificationsEnabled, vm::setGoalReachedNotificationsEnabled)
            )
        }
        if (ui.waterTrackingEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_notif_water_reminder),
                    icon = Icons.Filled.WaterDrop, iconTint = tint,
                    modifier = Modifier.settingsRow("waterReminder"),
                    trailing = RowTrailing.Toggle(ui.waterReminderEnabled, vm::setWaterReminderEnabled)
                )
            }
        }
        if (ui.fastingTrackingEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_notif_fasting_goal),
                    icon = Icons.Filled.Timer, iconTint = tint,
                    modifier = Modifier.settingsRow("fastingGoalNotification"),
                    trailing = RowTrailing.Toggle(ui.fastingGoalNotificationEnabled, vm::setFastingGoalNotificationEnabled)
                )
            }
        }
    }

    val exactFooter = if (ui.medicationRemindersEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ui.medicationExactAlarms) {
        stringResource(R.string.settings_medications_exact_rationale)
    } else {
        null
    }
    InsetGroup(footer = exactFooter) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_medication_reminders),
                icon = Icons.Filled.Medication, iconTint = tint,
                modifier = Modifier.settingsRow("doseReminders"),
                trailing = RowTrailing.Toggle(ui.medicationRemindersEnabled, vm::setMedicationRemindersEnabled)
            )
        }
        if (ui.medicationRemindersEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_medications_snooze),
                    value = pluralStringResource(R.plurals.settings_medications_snooze_minutes, ui.medicationSnoozeMinutes, ui.medicationSnoozeMinutes),
                    icon = Icons.Filled.Snooze, iconTint = tint,
                    modifier = Modifier.settingsRow("snooze"),
                    onClick = { showSnoozeSheet = true }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                row {
                    GroupRow(
                        title = stringResource(R.string.settings_medications_exact_timing),
                        value = stringResource(
                            if (ui.medicationExactAlarms) R.string.settings_medications_exact_timing_on
                            else R.string.settings_medications_exact_timing_off
                        ),
                        icon = Icons.Filled.Alarm, iconTint = tint,
                        modifier = Modifier.settingsRow("exactTiming"),
                        onClick = ctx.actions.openExactAlarmSettings
                    )
                }
            }
        }
    }

    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.settings_notif_app_updates),
                icon = Icons.Filled.SystemUpdate, iconTint = tint,
                modifier = Modifier.settingsRow("appUpdates"),
                trailing = RowTrailing.Toggle(ui.appUpdateNotificationsEnabled, vm::setAppUpdateNotificationsEnabled)
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_battery_opt),
                value = stringResource(R.string.settings_battery_opt_value),
                icon = Icons.Filled.BatteryAlert, iconTint = tint,
                modifier = Modifier.settingsRow("batteryOptimisation"),
                onClick = ctx.actions.openBatteryOptimizationSettings
            )
        }
    }

    if (showSnoozeSheet) {
        OptionPickerSheet(
            title = stringResource(R.string.settings_medications_snooze),
            items = MedicationConstants.SNOOZE_MINUTES,
            label = { pluralStringResource(R.plurals.settings_medications_snooze_minutes, it, it) },
            selected = { it == ui.medicationSnoozeMinutes },
            onSelect = { vm.setMedicationSnoozeMinutes(it); showSnoozeSheet = false },
            onDismiss = { showSnoozeSheet = false }
        )
    }
}
