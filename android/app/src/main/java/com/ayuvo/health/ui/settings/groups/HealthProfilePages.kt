package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.models.AutoBalanceMacro
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WeightDisplayFormatter
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.navigation.AppRoutes
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.settings.SettingsSheet
import com.ayuvo.health.ui.settings.SettingsTint
import com.ayuvo.health.ui.settings.birthdayDisplay
import com.ayuvo.health.ui.settings.feetInchesLabel
import com.ayuvo.health.ui.settings.optionalNutrientSummary
import com.ayuvo.health.ui.theme.AppColors
import java.util.Locale

/** `settings.row.<id>` (docs/ui-structure.md §9). */
internal fun Modifier.settingsRow(id: String): Modifier = testTag("settings.row.$id")

/** Health Profile › Personal Info. */
@Composable
internal fun PersonalInfoPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val state = ctx.state
    val p = ui.profile ?: return
    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.settings_gender),
                value = stringResource(p.gender.displayNameRes),
                icon = Icons.Filled.Person, iconTint = SettingsTint.Gray,
                modifier = Modifier.settingsRow("gender"),
                onClick = { state.sheet = SettingsSheet.GENDER }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_birthday),
                value = birthdayDisplay(p),
                icon = Icons.Filled.Cake, iconTint = SettingsTint.Pink,
                modifier = Modifier.settingsRow("birthday"),
                onClick = { state.sheet = SettingsSheet.BIRTHDAY }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_height),
                value = if (ui.heightMetric) stringResource(R.string.height_cm_format, p.heightCm.toInt())
                else feetInchesLabel(p.heightCm.toInt()),
                icon = Icons.Filled.Height, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("height"),
                onClick = { state.sheet = SettingsSheet.HEIGHT }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_weight),
                value = if (ui.weightMetric) String.format(Locale.US, "%.1f kg", p.weightKg)
                else String.format(Locale.US, "%.1f lbs", p.weightKg * 2.20462),
                icon = Icons.Filled.MonitorWeight, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("weight"),
                onClick = { state.sheet = SettingsSheet.WEIGHT }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_body_fat),
                value = p.bodyFatPercentage?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.settings_not_set),
                icon = Icons.Filled.Percent, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("bodyFat"),
                onClick = { state.sheet = SettingsSheet.BODY_FAT }
            )
        }
        row {
            // Optional tape-measure circumferences — extra signal for the AI goal calc + Coach.
            GroupRow(
                title = stringResource(R.string.body_measurements_title),
                value = ctx.latestMeasurement?.waistCm?.let { waist ->
                    if (ui.heightMetric) stringResource(R.string.settings_waist_cm_format, waist)
                    else stringResource(R.string.settings_waist_in_format, waist / 2.54)
                } ?: stringResource(R.string.settings_not_set),
                icon = Icons.Filled.Straighten, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("bodyMeasurements"),
                onClick = { ctx.actions.navigate(AppRoutes.BODY_MEASUREMENTS) }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_allergen_sensitivities),
                value = p.allergenSensitivities.joinToString(", ").ifBlank { stringResource(R.string.settings_not_set) },
                icon = Icons.Filled.Warning, iconTint = SettingsTint.Warning,
                modifier = Modifier.settingsRow("allergens"),
                onClick = { ctx.actions.navigate(AppRoutes.ALLERGEN_SENSITIVITIES) }
            )
        }
    }
}

/** Health Profile › Goals & Targets: Plan, Automation and Daily Targets. */
@Composable
internal fun GoalsTargetsPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val state = ctx.state
    val vm = ctx.vm
    val p = ui.profile ?: return
    InsetGroup(header = stringResource(R.string.settings_goals_section_plan)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_weight_goal),
                value = stringResource(p.goal.displayNameRes),
                icon = Icons.Filled.Flag, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("weightGoal"),
                onClick = { state.sheet = SettingsSheet.GOAL }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_activity_level),
                value = stringResource(p.activityLevel.displayNameRes),
                icon = Icons.AutoMirrored.Filled.DirectionsRun, iconTint = SettingsTint.Activity,
                modifier = Modifier.settingsRow("activityLevel"),
                onClick = { state.sheet = SettingsSheet.ACTIVITY }
            )
        }
        if (p.goal != WeightGoal.MAINTAIN) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_weekly_change),
                    value = WeightDisplayFormatter.weeklyChange(kilograms = p.weeklyChangeKg ?: 0.5, useMetric = ui.weightMetric),
                    icon = Icons.Filled.Speed, iconTint = SettingsTint.Body,
                    modifier = Modifier.settingsRow("weeklyChange"),
                    onClick = { state.sheet = SettingsSheet.GOAL_SPEED }
                )
            }
            row {
                GroupRow(
                    title = stringResource(R.string.settings_goal_weight),
                    value = p.goalWeightKg?.let {
                        if (ui.weightMetric) String.format(Locale.US, "%.1f kg", it)
                        else String.format(Locale.US, "%.1f lbs", it * 2.20462)
                    } ?: stringResource(R.string.settings_not_set),
                    icon = Icons.AutoMirrored.Filled.TrendingUp, iconTint = SettingsTint.Body,
                    modifier = Modifier.settingsRow("goalWeight"),
                    onClick = { state.sheet = SettingsSheet.GOAL_WEIGHT }
                )
            }
        }
        // Goal Body Fat only renders when the user has a body fat % set.
        if (p.bodyFatPercentage != null) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_goal_body_fat),
                    value = p.goalBodyFatPercentage?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.settings_not_set),
                    icon = Icons.Filled.TrackChanges, iconTint = SettingsTint.Body,
                    modifier = Modifier.settingsRow("goalBodyFat"),
                    onClick = { state.sheet = SettingsSheet.GOAL_BODY_FAT }
                )
            }
        }
    }

    val infoLabel = stringResource(R.string.action_info)
    InsetGroup(header = stringResource(R.string.settings_goals_section_automation)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_adaptive_goals),
                icon = Icons.Filled.AutoMode, iconTint = SettingsTint.Green,
                modifier = Modifier.settingsRow("adaptiveGoals"),
                trailing = RowTrailing.Custom {
                    ToggleWithInfo(
                        checked = ui.adaptiveGoalsEnabled,
                        busy = ui.applyingAdaptiveGoals,
                        infoLabel = infoLabel,
                        onInfo = { state.showAdaptiveGoalsInfo = true },
                        onChange = vm::setAdaptiveGoalsEnabled
                    )
                }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_energy_goals),
                subtitle = if (!ui.healthConnectEnabled) stringResource(R.string.settings_needs_health_connect) else null,
                icon = Icons.Filled.LocalFireDepartment, iconTint = SettingsTint.Activity,
                modifier = Modifier.settingsRow("energyBurnGoals"),
                trailing = RowTrailing.Custom {
                    ToggleWithInfo(
                        checked = ui.healthEnergyGoalsEnabled,
                        busy = ui.recalculatingGoals,
                        infoLabel = infoLabel,
                        onInfo = { state.showHealthEnergyGoalsInfo = true },
                        onChange = ctx.actions.onHealthEnergyGoalsToggle
                    )
                }
            )
        }
    }

    // The lock glyph is read-only. Saving a value locks it; the picker's Reset releases it. While
    // Adaptive Goals is on, tapping a row explains that it owns the targets instead of opening.
    val lockEnabled = !ui.adaptiveGoalsEnabled
    val openGoal = { target: SettingsSheet ->
        if (ui.adaptiveGoalsEnabled) state.showAdaptiveLockHint = true else state.sheet = target
    }
    InsetGroup(header = stringResource(R.string.settings_section_daily_targets)) {
        row {
            LockableGoalGroupRow(
                label = stringResource(R.string.settings_calories),
                value = stringResource(R.string.kcal_value_format, p.effectiveCalories),
                icon = Icons.Filled.LocalFireDepartment, tint = SettingsTint.Nutrition,
                locked = p.caloriesLocked, lockEnabled = lockEnabled, id = "calories"
            ) { openGoal(SettingsSheet.CALORIES) }
        }
        row {
            LockableGoalGroupRow(
                label = stringResource(R.string.macro_protein), value = "${p.effectiveProtein}g",
                icon = Icons.Filled.DataUsage, tint = SettingsTint.Protein,
                locked = p.isMacroLocked(AutoBalanceMacro.PROTEIN), lockEnabled = lockEnabled, id = "protein"
            ) { openGoal(SettingsSheet.PROTEIN) }
        }
        row {
            LockableGoalGroupRow(
                label = stringResource(R.string.macro_carbs), value = "${p.effectiveCarbs}g",
                icon = Icons.Filled.DataUsage, tint = SettingsTint.Carbs,
                locked = p.isMacroLocked(AutoBalanceMacro.CARBS), lockEnabled = lockEnabled, id = "carbs"
            ) { openGoal(SettingsSheet.CARBS) }
        }
        row {
            LockableGoalGroupRow(
                label = stringResource(R.string.macro_fat), value = "${p.effectiveFat}g",
                icon = Icons.Filled.DataUsage, tint = SettingsTint.Fat,
                locked = p.isMacroLocked(AutoBalanceMacro.FAT), lockEnabled = lockEnabled, id = "fat"
            ) { openGoal(SettingsSheet.FAT) }
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_other_nutrient_goals),
                subtitle = optionalNutrientSummary(ui.optionalNutrientGoals),
                icon = Icons.Filled.DataUsage, iconTint = SettingsTint.Fiber,
                modifier = Modifier.settingsRow("otherNutrients"),
                onClick = { ctx.actions.navigate(AppRoutes.OPTIONAL_NUTRIENT_GOALS) }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_recalculate_goals),
                icon = Icons.Filled.Refresh, iconTint = SettingsTint.Green,
                enabled = !ui.recalculatingGoals,
                modifier = Modifier.settingsRow("recalculateGoals"),
                trailing = RowTrailing.Custom {
                    if (ui.recalculatingGoals) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (ui.goalsNeedRecalc) {
                        // Soft nudge: a goal input changed since the last recalc.
                        Text(stringResource(R.string.settings_tap_to_update), color = AppColors.Calorie, fontSize = 13.sp)
                    }
                },
                onClick = { vm.recalculateGoals() }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_calc_methods),
                icon = Icons.Filled.Calculate, iconTint = SettingsTint.Gray,
                modifier = Modifier.settingsRow("calculationMethods"),
                onClick = { ctx.actions.navigate(AppRoutes.CALCULATION_METHODS) }
            )
        }
    }
}

@Composable
private fun ToggleWithInfo(
    checked: Boolean,
    busy: Boolean,
    infoLabel: String,
    onInfo: () -> Unit,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
            Spacer(Modifier.width(6.dp))
        }
        InfoTrailing(infoLabel, onInfo)
        SettingsSwitch(checked = checked, enabled = !busy, onChange = onChange)
    }
}

/** A goal row whose trailing lock glyph is a read-only indicator (locked = pinned value). */
@Composable
private fun LockableGoalGroupRow(
    label: String,
    value: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    locked: Boolean,
    lockEnabled: Boolean,
    id: String,
    onClick: () -> Unit
) {
    GroupRow(
        title = label,
        value = value,
        icon = icon,
        iconTint = tint,
        modifier = Modifier.settingsRow(id),
        trailing = RowTrailing.Custom {
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = stringResource(if (locked) R.string.settings_macro_locked else R.string.settings_macro_unlocked),
                tint = when {
                    !lockEnabled -> AyuvoColors.tertiaryLabel()
                    locked -> AppColors.Calorie
                    else -> AyuvoColors.secondaryLabel()
                },
                modifier = Modifier.size(18.dp)
            )
        },
        onClick = onClick
    )
}

/** Health Profile › Units: display units and the week start. Stored values never change units. */
@Composable
internal fun UnitsPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val state = ctx.state
    InsetGroup(footer = stringResource(R.string.settings_units_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_units_height),
                value = stringResource(if (ui.heightMetric) R.string.settings_unit_cm else R.string.settings_unit_ftin),
                icon = Icons.Filled.Height, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("heightUnit"),
                onClick = { state.sheet = SettingsSheet.HEIGHT_UNIT }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_units_weight),
                value = stringResource(if (ui.weightMetric) R.string.settings_unit_kg else R.string.settings_unit_lbs),
                icon = Icons.Filled.MonitorWeight, iconTint = SettingsTint.Body,
                modifier = Modifier.settingsRow("weightUnit"),
                onClick = { state.sheet = SettingsSheet.WEIGHT_UNIT }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_water_unit),
                value = if (ui.waterUnit == WaterUnit.MILLILITERS) stringResource(R.string.settings_water_unit_ml)
                else stringResource(R.string.settings_water_unit_fl_oz),
                icon = Icons.Filled.WaterDrop, iconTint = SettingsTint.Hydration,
                modifier = Modifier.settingsRow("waterUnit"),
                onClick = { state.sheet = SettingsSheet.WATER_UNIT }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_units_glucose),
                value = ui.effectiveGlucoseUnit,
                icon = Icons.Filled.Bloodtype, iconTint = SettingsTint.Vitals,
                modifier = Modifier.settingsRow("glucoseUnit"),
                onClick = { state.sheet = SettingsSheet.GLUCOSE_UNIT }
            )
        }
    }
    InsetGroup(footer = stringResource(R.string.settings_units_week_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_week_starts),
                value = stringResource(if (ui.weekStartsOnMonday) R.string.settings_week_monday else R.string.settings_week_sunday),
                icon = Icons.Filled.CalendarToday, iconTint = SettingsTint.Red,
                modifier = Modifier.settingsRow("weekStart"),
                onClick = { state.sheet = SettingsSheet.WEEK_START }
            )
        }
    }
}
