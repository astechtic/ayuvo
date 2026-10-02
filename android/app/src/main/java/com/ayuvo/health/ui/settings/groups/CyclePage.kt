package com.ayuvo.health.ui.settings.groups

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.cycle.CycleViewModel
import com.ayuvo.health.ui.cycle.LengthStepper
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.home.SheetTimePickerDialog
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import java.time.LocalTime

/**
 * Tracking › Cycle tracking (docs/cycle-tracking.md §5 "Settings"): show cycle tracking, typical lengths, fertility
 * estimates, Health Connect sync, reminders (period soon + days before, period end, daily + time, lock-screen
 * details), Coach access behind a consent dialog, and "Delete all cycle data" behind a confirmation.
 */
@Composable
internal fun CycleSettingsPage(ctx: SettingsPageContext) {
    val vm: CycleViewModel = viewModel(factory = CycleViewModel.Factory(ctx.container))
    val ui by vm.ui.collectAsState()
    val coach by vm.coachEnabled.collectAsState(initial = false)
    val notificationsOn by vm.notificationsEnabled.collectAsState(initial = true)
    val context = LocalContext.current
    val tint = SettingsPage.CYCLE.tint
    val cfg = vm.config
    val prefs = ui.settings.preferences
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCoach by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val deletedText = stringResource(R.string.cycle_deleted)
    val healthLauncher = rememberLauncherForActivityResult(vm.healthPermissionContract()) { granted -> vm.onHealthPermissionResult(granted) }

    InsetGroup(footer = stringResource(R.string.cycle_show_sub)) {
        row {
            GroupRow(
                stringResource(R.string.cycle_show), icon = Icons.Filled.Visibility, iconTint = tint,
                modifier = Modifier.settingsRow("cycleEnabled"),
                trailing = RowTrailing.Toggle(ui.enabled, { vm.setEnabled(it) })
            )
        }
    }
    if (ui.setupDone) {
        InsetGroup(header = stringResource(R.string.cycle_lengths_header), footer = stringResource(R.string.cycle_setup_lengths_footer)) {
            row {
                LengthStepper(stringResource(R.string.cycle_setup_cycle_length), ui.settings.cycleLength, cfg.defaults.cycleLength,
                    cfg.limits.settingCycleMin, cfg.limits.settingCycleMax, "settings.row.cycleLength") { v -> vm.saveSettings { it.copy(cycleLength = v) } }
            }
            row {
                LengthStepper(stringResource(R.string.cycle_setup_period_length), ui.settings.periodLength, cfg.defaults.periodLength,
                    cfg.limits.settingPeriodMin, cfg.limits.settingPeriodMax, "settings.row.periodLength") { v -> vm.saveSettings { it.copy(periodLength = v) } }
            }
            row {
                LengthStepper(stringResource(R.string.cycle_luteal), ui.settings.lutealLength, cfg.defaults.lutealLength,
                    cfg.limits.lutealMin, cfg.limits.lutealMax, "settings.row.lutealLength") { v -> vm.saveSettings { it.copy(lutealLength = v) } }
            }
        }
    }
    InsetGroup(footer = stringResource(R.string.cycle_show_fertility_sub)) {
        row {
            GroupRow(
                stringResource(R.string.cycle_show_fertility), icon = Icons.Filled.AutoAwesome, iconTint = tint,
                modifier = Modifier.settingsRow("cycleShowFertility"),
                trailing = RowTrailing.Toggle(ui.showFertility, { vm.setShowFertility(it) })
            )
        }
    }
    if (vm.healthAvailable()) {
        InsetGroup(footer = stringResource(R.string.cycle_health_sync_sub)) {
            row {
                GroupRow(
                    stringResource(R.string.cycle_health_sync), icon = Icons.Filled.Favorite, iconTint = tint,
                    modifier = Modifier.settingsRow("cycleHealthSync"),
                    trailing = RowTrailing.Toggle(prefs.healthSync, { on ->
                        if (on) runCatching { healthLauncher.launch(vm.healthPermissions) }
                        else vm.saveSettings { it.copy(preferences = it.preferences.copy(healthSync = false)) }
                    })
                )
            }
        }
    }
    InsetGroup(
        header = stringResource(R.string.cycle_reminders_header),
        footer = if (notificationsOn) stringResource(R.string.cycle_rem_discreet_footer) else stringResource(R.string.cycle_rem_notifications_off)
    ) {
        row {
            GroupRow(
                stringResource(R.string.cycle_rem_period_soon), icon = Icons.Filled.Notifications, iconTint = tint,
                modifier = Modifier.settingsRow("cyclePeriodSoon"),
                trailing = RowTrailing.Toggle(prefs.periodSoon, { v -> vm.saveSettings { it.copy(preferences = it.preferences.copy(periodSoon = v)) } })
            )
        }
        if (prefs.periodSoon) {
            row {
                GroupRow(
                    stringResource(R.string.cycle_rem_days_before_title),
                    value = pluralStringResource(R.plurals.cycle_days, prefs.daysBefore, prefs.daysBefore),
                    modifier = Modifier.settingsRow("cycleDaysBefore"),
                    onClick = {
                        val next = if (prefs.daysBefore >= 3) 1 else prefs.daysBefore + 1
                        vm.saveSettings { it.copy(preferences = it.preferences.copy(daysBefore = next)) }
                    }
                )
            }
        }
        row {
            GroupRow(
                stringResource(R.string.cycle_rem_period_end), subtitle = stringResource(R.string.cycle_rem_period_end_sub),
                icon = Icons.Filled.Notifications, iconTint = tint,
                modifier = Modifier.settingsRow("cyclePeriodEnd"),
                trailing = RowTrailing.Toggle(prefs.periodEnd, { v -> vm.saveSettings { it.copy(preferences = it.preferences.copy(periodEnd = v)) } })
            )
        }
        row {
            GroupRow(
                stringResource(R.string.cycle_rem_daily), icon = Icons.Filled.Notifications, iconTint = tint,
                modifier = Modifier.settingsRow("cycleDaily"),
                trailing = RowTrailing.Toggle(prefs.daily, { v -> vm.saveSettings { it.copy(preferences = it.preferences.copy(daily = v)) } })
            )
        }
        row {
            GroupRow(
                stringResource(R.string.cycle_rem_time), value = prefs.time, icon = Icons.Filled.Schedule, iconTint = tint,
                modifier = Modifier.settingsRow("cycleReminderTime"), onClick = { pickTime = true }
            )
        }
        row {
            GroupRow(
                stringResource(R.string.cycle_rem_lock_details), subtitle = stringResource(R.string.cycle_rem_lock_details_sub),
                icon = Icons.Filled.Lock, iconTint = tint,
                modifier = Modifier.settingsRow("cycleLockScreenDetails"),
                trailing = RowTrailing.Toggle(prefs.lockScreenDetails, { v -> vm.saveSettings { it.copy(preferences = it.preferences.copy(lockScreenDetails = v)) } })
            )
        }
    }
    InsetGroup(footer = stringResource(R.string.cycle_coach_sub)) {
        row {
            GroupRow(
                stringResource(R.string.cycle_coach), icon = Icons.Filled.AutoAwesome, iconTint = tint,
                modifier = Modifier.settingsRow("coachCycleEnabled"),
                trailing = RowTrailing.Toggle(coach, { on -> if (on) confirmCoach = true else vm.setCoachEnabled(false) })
            )
        }
    }
    InsetGroup {
        row {
            GroupRow(
                stringResource(R.string.cycle_delete_all), icon = Icons.Filled.Delete, destructive = true, trailing = RowTrailing.None,
                modifier = Modifier.settingsRow("cycleDeleteAll"), onClick = { confirmDelete = true }
            )
        }
    }
    if (pickTime) {
        SheetTimePickerDialog(
            initialTime = runCatching { LocalTime.parse(prefs.time) }.getOrDefault(LocalTime.of(9, 0)),
            onConfirm = { t ->
                pickTime = false
                vm.saveSettings { it.copy(preferences = it.preferences.copy(time = "%02d:%02d".format(t.hour, t.minute))) }
            },
            onDismiss = { pickTime = false }
        )
    }
    if (confirmCoach) {
        GlassDialog(onDismissRequest = { confirmCoach = false }) {
            Text(stringResource(R.string.cycle_coach_consent_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.cycle_coach_consent_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.cycle_coach_consent_allow),
                onPrimary = { confirmCoach = false; vm.setCoachEnabled(true) },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmCoach = false }
            )
        }
    }
    if (confirmDelete) {
        GlassDialog(onDismissRequest = { confirmDelete = false }) {
            Text(stringResource(R.string.cycle_delete_all_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.cycle_delete_all_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    confirmDelete = false
                    vm.deleteAll { Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show() }
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmDelete = false },
                destructive = true
            )
        }
    }
}
