package com.ayuvo.health.ui.settings.groups

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.health.relativeTimeText
import com.ayuvo.health.ui.navigation.AppRoutes
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.theme.AppColors

/**
 * Data & Privacy › Health Sync: the Health Connect connection, sync status, Browse and Favourites
 * shortcuts, Coach access, then the Android-only Health Connect access rows.
 */
@Composable
internal fun HealthSyncPage(ctx: SettingsPageContext) {
    val ui = ctx.ui
    val sync = ctx.healthSync
    val tint = SettingsPage.HEALTH_SYNC.tint
    InsetGroup(footer = stringResource(R.string.settings_health_sync_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_health_connect),
                subtitle = if (ui.healthConnectEnabled && ui.healthReadOnly) stringResource(R.string.health_settings_connected_read_only) else null,
                icon = Icons.Filled.Favorite, iconTint = tint,
                modifier = Modifier.settingsRow("healthConnect"),
                trailing = RowTrailing.Toggle(ui.healthConnectEnabled, ctx.actions.onHealthConnectToggle)
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_browse_health_data),
                icon = Icons.Filled.GridView, iconTint = tint,
                modifier = Modifier.settingsRow("browseHealthData"),
                onClick = ctx.actions.openBrowse
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_last_synced),
                value = when {
                    sync.running -> stringResource(R.string.settings_syncing)
                    sync.lastSyncMs == null -> stringResource(R.string.settings_never)
                    else -> relativeTimeText(sync.lastSyncMs)
                },
                icon = Icons.Filled.History, iconTint = tint,
                modifier = Modifier.settingsRow("lastSynced"),
                trailing = RowTrailing.None
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_sync_now),
                icon = Icons.Filled.Sync, iconTint = tint,
                enabled = ui.healthHubEnabled && !sync.running,
                modifier = Modifier.testTag("settings.health.syncNow"),
                trailing = if (sync.running) {
                    RowTrailing.Custom { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie) }
                } else {
                    RowTrailing.None
                },
                onClick = ctx.actions.syncHealthNow
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_edit_favourites),
                icon = Icons.Filled.Star, iconTint = tint,
                modifier = Modifier.settingsRow("editFavourites"),
                onClick = ctx.actions.openFavourites
            )
        }
        if (ui.healthHubEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.health_settings_coach_toggle),
                    subtitle = stringResource(R.string.health_settings_coach_subtitle),
                    icon = Icons.Filled.SmartToy, iconTint = tint,
                    modifier = Modifier.testTag("settings.health.coachToggle"),
                    trailing = RowTrailing.Toggle(ui.coachHealthDataEnabled, ctx.vm::setCoachHealthDataEnabled)
                )
            }
        }
    }
    // Android only: Health Connect nutrition import, workout write grant and Manage access.
    InsetGroup(header = stringResource(R.string.settings_health_connect_access_header)) {
        row {
            GroupRow(
                title = stringResource(R.string.health_import_title),
                value = stringResource(R.string.health_import_action),
                icon = Icons.Filled.Restaurant, iconTint = tint,
                modifier = Modifier.settingsRow("importNutrition"),
                onClick = { ctx.state.showNutritionImport = true }
            )
        }
        if (ui.healthConnectEnabled && !ui.workoutHealthWriteGranted) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_workout_health_access),
                    value = stringResource(R.string.settings_grant_permission),
                    icon = Icons.Filled.LocalFireDepartment, iconTint = tint,
                    modifier = Modifier.settingsRow("workoutAccess"),
                    onClick = ctx.actions.requestWorkoutHealthAccess
                )
            }
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_manage_health_access),
                value = stringResource(R.string.settings_permissions),
                icon = Icons.Filled.Link, iconTint = tint,
                modifier = Modifier.settingsRow("manageAccess"),
                onClick = ctx.actions.openHealthConnectAccess
            )
        }
    }
}

/**
 * Data & Privacy › Health Records (docs/health-records.md §16, §26, §35-§37): AI processing,
 * Coach access, the privacy explainer and the (single) storage and backup rows.
 */
@Composable
internal fun HealthRecordsPage(ctx: SettingsPageContext) {
    val tint = SettingsPage.HEALTH_RECORDS.tint
    HealthRecordsAiSection(container = ctx.container, onOpenAiProviders = { ctx.actions.openPage(SettingsPage.AI_PROVIDERS) })
    HealthRecordsCoachSection(container = ctx.container)
    HealthRecordsPrivacySection()
    InsetGroup(header = stringResource(R.string.settings_records_storage_backup_header)) {
        row {
            GroupRow(
                title = stringResource(R.string.records_settings_storage),
                subtitle = stringResource(R.string.records_settings_storage_subtitle),
                icon = Icons.Filled.Storage, iconTint = tint,
                modifier = Modifier.settingsRow("recordsStorage"),
                onClick = { ctx.actions.navigate(AppRoutes.HEALTH_RECORDS_STORAGE) }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.records_settings_backup),
                subtitle = stringResource(R.string.records_settings_backup_subtitle),
                icon = Icons.Filled.CloudUpload, iconTint = tint,
                modifier = Modifier.settingsRow("recordsBackup"),
                onClick = { ctx.actions.navigate(AppRoutes.HEALTH_RECORDS_BACKUP) }
            )
        }
    }
}

/** Data & Privacy › Backup & Export: Google Drive backup (Android) and diary / health data files. */
@Composable
internal fun BackupExportPage(ctx: SettingsPageContext) {
    val cloud = ctx.cloudBackup
    val actions = ctx.actions
    val tint = SettingsPage.BACKUP_EXPORT.tint
    val accountLines = listOfNotNull(
        cloud.accountEmail?.let { stringResource(R.string.cloud_backup_signed_in, it) },
        cloud.lastAt?.let { stringResource(R.string.cloud_backup_last, it.take(16).replace('T', ' ')) }
    )
    InsetGroup(footer = stringResource(R.string.cloud_backup_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.cloud_backup_title),
                subtitle = accountLines.joinToString("\n").ifBlank { null },
                icon = Icons.Filled.CloudUpload, iconTint = AyuvoPalette.Hydration,
                modifier = Modifier.settingsRow("cloudBackup"),
                trailing = if (cloud.busy) {
                    RowTrailing.Custom { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie) }
                } else {
                    RowTrailing.Toggle(cloud.enabled, actions.setCloudBackupEnabled)
                }
            )
        }
    }
    if (cloud.enabled || cloud.accountEmail != null) {
        InsetGroup {
            if (cloud.enabled) {
                row {
                    GroupRow(
                        title = stringResource(R.string.cloud_backup_now),
                        icon = Icons.Filled.CloudUpload, iconTint = tint,
                        enabled = !cloud.busy,
                        modifier = Modifier.settingsRow("backupNow"),
                        trailing = RowTrailing.None,
                        onClick = actions.backupNow
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.cloud_backup_restore_now),
                        icon = Icons.Filled.CloudDownload, iconTint = tint,
                        enabled = !cloud.busy,
                        modifier = Modifier.settingsRow("restoreNow"),
                        trailing = RowTrailing.None,
                        onClick = actions.restoreNow
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.cloud_backup_delete),
                        icon = Icons.Filled.CloudOff,
                        destructive = true,
                        enabled = !cloud.busy,
                        modifier = Modifier.settingsRow("deleteCloudBackup"),
                        trailing = RowTrailing.None,
                        onClick = { ctx.state.showCloudDeleteConfirm = true }
                    )
                }
            }
            row {
                GroupRow(
                    title = stringResource(R.string.cloud_backup_sign_out),
                    icon = Icons.AutoMirrored.Filled.Logout, iconTint = tint,
                    enabled = !cloud.busy,
                    modifier = Modifier.settingsRow("cloudSignOut"),
                    trailing = RowTrailing.None,
                    onClick = actions.signOutCloudBackup
                )
            }
            row {
                GroupRow(
                    title = stringResource(R.string.cloud_backup_switch_account),
                    icon = Icons.Filled.SwitchAccount, iconTint = tint,
                    enabled = !cloud.busy,
                    modifier = Modifier.settingsRow("cloudSwitchAccount"),
                    trailing = RowTrailing.None,
                    onClick = actions.switchCloudAccount
                )
            }
        }
    }
    InsetGroup(
        header = stringResource(R.string.settings_export_import_header),
        footer = stringResource(R.string.settings_export_import_footer)
    ) {
        row {
            GroupRow(
                title = stringResource(R.string.export_diary_title),
                icon = Icons.Filled.IosShare, iconTint = AyuvoPalette.Nutrition,
                modifier = Modifier.settingsRow("exportDiary"),
                trailing = RowTrailing.None,
                onClick = { ctx.state.showExportSheet = true }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.import_diary_title),
                icon = Icons.Filled.Download, iconTint = AyuvoPalette.Nutrition,
                modifier = Modifier.settingsRow("importDiary"),
                trailing = RowTrailing.None,
                onClick = actions.importDiary
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.health_settings_export),
                icon = Icons.Filled.MonitorHeart, iconTint = AyuvoPalette.Vitals,
                modifier = Modifier.testTag("settings.health.export"),
                trailing = RowTrailing.None,
                onClick = { ctx.state.showExportHealthSheet = true }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.health_settings_import),
                icon = Icons.Filled.MonitorHeart, iconTint = AyuvoPalette.Vitals,
                modifier = Modifier.testTag("settings.health.import"),
                trailing = RowTrailing.None,
                onClick = actions.importHealthData
            )
        }
    }
}

/** Data & Privacy › Delete All Data: the three destructive actions (existing confirmations kept). */
@Composable
internal fun DeleteDataPage(ctx: SettingsPageContext) {
    val state = ctx.state
    InsetGroup(footer = stringResource(R.string.settings_clear_health_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.health_settings_clear),
                icon = Icons.Filled.DeleteSweep,
                destructive = true,
                modifier = Modifier.testTag("settings.health.clearData"),
                trailing = RowTrailing.None,
                onClick = { state.showClearHealthDialog = true }
            )
        }
    }
    InsetGroup(footer = stringResource(R.string.settings_delete_all_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.settings_clear_food_log),
                icon = Icons.Filled.DeleteSweep, iconTint = AyuvoPalette.Warning,
                modifier = Modifier.settingsRow("clearFoodLog"),
                trailing = RowTrailing.None,
                onClick = { state.showClearFoodDialog = true }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_delete_all_data),
                icon = Icons.Filled.DeleteForever,
                destructive = true,
                modifier = Modifier.settingsRow("deleteAllData"),
                trailing = RowTrailing.None,
                onClick = { state.showDeleteDialog = true }
            )
        }
    }
}
