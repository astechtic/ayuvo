package com.ayuvo.health.ui.settings.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.export.AllDataExportArchive
import com.ayuvo.health.export.AllDataExportCoordinator
import com.ayuvo.health.export.AllDataExportOutcome
import com.ayuvo.health.export.AllDataExportStep
import com.ayuvo.health.export.AllDataImportOutcome
import com.ayuvo.health.export.AllDataImportPlan
import com.ayuvo.health.export.AllDataImportUi
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.health.relativeTimeText
import com.ayuvo.health.ui.navigation.AppRoutes
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import com.ayuvo.health.ui.settings.SettingsTint
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
                icon = Icons.Filled.History, iconTint = SettingsTint.Gray,
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
                icon = Icons.Filled.Star, iconTint = SettingsTint.Orange,
                modifier = Modifier.settingsRow("editFavourites"),
                onClick = ctx.actions.openFavourites
            )
        }
        if (ui.healthHubEnabled) {
            row {
                GroupRow(
                    title = stringResource(R.string.health_settings_coach_toggle),
                    subtitle = stringResource(R.string.health_settings_coach_subtitle),
                    icon = Icons.Filled.SmartToy, iconTint = SettingsTint.Ai,
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
                icon = Icons.Filled.Restaurant, iconTint = SettingsTint.Nutrition,
                modifier = Modifier.settingsRow("importNutrition"),
                onClick = { ctx.state.showNutritionImport = true }
            )
        }
        if (ui.healthConnectEnabled && !ui.workoutHealthWriteGranted) {
            row {
                GroupRow(
                    title = stringResource(R.string.settings_workout_health_access),
                    value = stringResource(R.string.settings_grant_permission),
                    icon = Icons.Filled.LocalFireDepartment, iconTint = SettingsTint.Activity,
                    modifier = Modifier.settingsRow("workoutAccess"),
                    onClick = ctx.actions.requestWorkoutHealthAccess
                )
            }
        }
        row {
            GroupRow(
                title = stringResource(R.string.settings_manage_health_access),
                value = stringResource(R.string.settings_permissions),
                icon = Icons.Filled.Link, iconTint = SettingsTint.Gray,
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

/** Data & Privacy › Backup & Export: Google Drive backup (Android only), then Export / Import All Data. */
@Composable
internal fun BackupExportPage(ctx: SettingsPageContext) {
    val cloud = ctx.cloudBackup
    val actions = ctx.actions
    val tint = SettingsPage.BACKUP_EXPORT.tint
    // Coach chats in Drive are opt-in and off by default (docs/coach.md §12).
    val chatBackup by ctx.container.prefs.coachChatBackupEnabled.collectAsState(initial = false)
    var showChatConsent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accountLines = listOfNotNull(
        cloud.accountEmail?.let { stringResource(R.string.cloud_backup_signed_in, it) },
        cloud.lastAt?.let { stringResource(R.string.cloud_backup_last, it.take(16).replace('T', ' ')) }
    )
    InsetGroup(footer = stringResource(R.string.cloud_backup_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.cloud_backup_title),
                subtitle = accountLines.joinToString("\n").ifBlank { null },
                icon = Icons.Filled.CloudUpload, iconTint = SettingsTint.Backup,
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
            if (cloud.enabled) {
                row {
                    GroupRow(
                        title = stringResource(R.string.cloud_backup_chats_title),
                        subtitle = stringResource(R.string.cloud_backup_chats_subtitle),
                        icon = Icons.Filled.Forum, iconTint = tint,
                        modifier = Modifier.settingsRow("cloudBackupChats"),
                        trailing = RowTrailing.Toggle(
                            checked = chatBackup,
                            onChange = { on ->
                                if (on) {
                                    showChatConsent = true
                                } else {
                                    scope.launch { ctx.container.prefs.setCoachChatBackupEnabled(false) }
                                }
                            },
                            enabled = !cloud.busy
                        )
                    )
                }
            }
            row {
                GroupRow(
                    title = stringResource(R.string.cloud_backup_sign_out),
                    icon = Icons.AutoMirrored.Filled.Logout, iconTint = SettingsTint.Gray,
                    enabled = !cloud.busy,
                    modifier = Modifier.settingsRow("cloudSignOut"),
                    trailing = RowTrailing.None,
                    onClick = actions.signOutCloudBackup
                )
            }
            row {
                GroupRow(
                    title = stringResource(R.string.cloud_backup_switch_account),
                    icon = Icons.Filled.SwitchAccount, iconTint = SettingsTint.Gray,
                    enabled = !cloud.busy,
                    modifier = Modifier.settingsRow("cloudSwitchAccount"),
                    trailing = RowTrailing.None,
                    onClick = actions.switchCloudAccount
                )
            }
        }
    }
    AllDataGroup(ctx)

    if (showChatConsent) {
        GlassDialog(
            onDismissRequest = { showChatConsent = false },
            modifier = Modifier.testTag("settings.cloudBackupChats.consent")
        ) {
            Text(stringResource(R.string.cloud_backup_chats_consent_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.cloud_backup_chats_consent_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 14.sp
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.cloud_backup_chats_consent_confirm),
                onPrimary = {
                    showChatConsent = false
                    scope.launch { ctx.container.prefs.setCoachChatBackupEnabled(true) }
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showChatConsent = false }
            )
        }
    }
}

/**
 * Backup & Export › Export All Data / Import All Data. Export writes one SAF `CreateDocument` zip
 * holding every individual export ([AllDataExportCoordinator]); Import reads such a zip back
 * ([AllDataImportCoordinator]). Both run on the app scope, so reopening the page shows their progress.
 */
@Composable
private fun AllDataGroup(ctx: SettingsPageContext) {
    val coordinator = ctx.container.allDataExport
    val export by coordinator.ui.collectAsState()
    val importer = ctx.container.allDataImport
    val import by importer.ui.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AllDataExportArchive.MIME_TYPE)) { uri ->
        if (uri != null) coordinator.start(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.open(uri)
    }
    val progress = export.step?.takeIf { export.running }?.let { step ->
        stringResource(R.string.export_all_progress, stringResource(step.labelRes), export.stepNumber, export.stepCount)
    }
    val outcome = when (val o = export.outcome) {
        is AllDataExportOutcome.Done -> buildString {
            append(pluralStringResource(R.plurals.export_all_done, o.fileCount, o.fileCount))
            if (o.skippedSections.isNotEmpty()) {
                append('\n')
                append(stringResource(R.string.export_all_skipped, o.skippedSections.map { stringResource(sectionLabelRes(it)) }.joinToString(", ")))
            }
        }
        AllDataExportOutcome.NothingToExport -> stringResource(R.string.export_all_nothing)
        is AllDataExportOutcome.Failed -> o.message ?: stringResource(R.string.export_failed)
        null -> null
    }
    val importing = import is AllDataImportUi.Reading || import is AllDataImportUi.Running
    val importStatus = when (val u = import) {
        AllDataImportUi.Reading -> stringResource(R.string.import_all_reading)
        is AllDataImportUi.Running -> stringResource(R.string.import_all_progress, stringResource(importSectionTitleRes(u.section)), u.number, u.total)
        else -> null
    }
    val spinner = RowTrailing.Custom { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie) }
    InsetGroup(footer = stringResource(R.string.all_data_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.export_all_title),
                subtitle = progress ?: outcome,
                icon = Icons.Filled.Archive, iconTint = SettingsTint.Backup,
                enabled = !export.running && !importing,
                modifier = Modifier.settingsRow("exportAllData"),
                trailing = if (export.running) spinner else RowTrailing.None,
                onClick = {
                    coordinator.consumeOutcome()
                    launcher.launch(AllDataExportArchive.fileName())
                }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.import_all_title),
                subtitle = importStatus,
                icon = Icons.Filled.Unarchive, iconTint = SettingsTint.Backup,
                enabled = !export.running && !importing,
                modifier = Modifier.settingsRow("importAllData"),
                trailing = if (importing) spinner else RowTrailing.None,
                onClick = { importLauncher.launch(arrayOf(AllDataExportArchive.MIME_TYPE, "application/octet-stream", "application/x-zip-compressed")) }
            )
        }
    }
    AllDataImportDialogs(import, onConfirm = importer::confirm, onDismiss = importer::dismiss)
}

@Composable
private fun AllDataImportDialogs(ui: AllDataImportUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
    when (ui) {
        is AllDataImportUi.Invalid -> GlassDialog(onDismissRequest = onDismiss) {
            Text(stringResource(R.string.import_all_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(if (ui.reason == AllDataImportPlan.Invalid.NEWER_VERSION) R.string.import_all_newer else R.string.import_all_invalid),
                color = muted
            )
            GlassDialogActions(primaryText = stringResource(R.string.action_ok), onPrimary = onDismiss)
        }
        is AllDataImportUi.Preview -> GlassDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings.importAll.preview")) {
            Text(stringResource(R.string.import_all_preview_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.import_all_preview_meta, formatExportDate(ui.plan.manifest.created_at), platformName(ui.plan.manifest.platform)),
                color = muted, fontSize = 14.sp
            )
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (ui.plan.sections.isEmpty() || ui.plan.importCount == 0) {
                    Text(stringResource(R.string.import_all_nothing), color = muted, fontSize = 14.sp)
                }
                for (section in ui.plan.sections) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(importSectionTitleRes(section.id)), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        countsLine(section.counts)?.let { Text(it, fontSize = 13.sp, color = muted) }
                        Text(
                            stringResource(section.skip?.let(::skipReasonRes) ?: importEffectRes(section.id)),
                            fontSize = 13.sp,
                            color = if (section.imports) MaterialTheme.colorScheme.onSurface else muted
                        )
                    }
                }
            }
            GlassDialogActions(
                primaryText = stringResource(R.string.import_all_confirm),
                onPrimary = onConfirm,
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = onDismiss,
                primaryEnabled = ui.plan.importCount > 0
            )
        }
        is AllDataImportUi.Done -> GlassDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings.importAll.done")) {
            Text(stringResource(R.string.import_all_done_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (o in ui.outcomes) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(importSectionTitleRes(o.section)), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            when (o) {
                                is AllDataImportOutcome.Imported ->
                                    if (o.section == AllDataExportCoordinator.SECTION_APP_BACKUP) stringResource(R.string.import_all_result_restored)
                                    else stringResource(R.string.import_all_result_imported, o.count.toInt())
                                is AllDataImportOutcome.Skipped -> stringResource(skipReasonRes(o.reason))
                                is AllDataImportOutcome.Failed ->
                                    o.message?.let { stringResource(R.string.import_all_result_failed, it) } ?: stringResource(R.string.import_all_result_failed_unknown)
                            },
                            fontSize = 13.sp,
                            color = if (o is AllDataImportOutcome.Failed) AyuvoPalette.Destructive else muted
                        )
                    }
                }
            }
            GlassDialogActions(primaryText = stringResource(R.string.action_done), onPrimary = onDismiss)
        }
        else -> Unit
    }
}

@Composable
private fun countsLine(counts: Map<String, Long>): String? {
    val parts = counts.entries.mapNotNull { (key, value) ->
        countLabelRes(key)?.let { "${stringResource(it)} ${value}" }
    }
    return parts.joinToString(" · ").ifBlank { null }
}

private fun countLabelRes(key: String): Int? = when (key) {
    "food_entries" -> R.string.import_all_count_food_entries
    "water_entries" -> R.string.import_all_count_water_entries
    "days" -> R.string.import_all_count_days
    "samples" -> R.string.import_all_count_samples
    "series_points" -> R.string.import_all_count_series_points
    "types" -> R.string.import_all_count_types
    "medications" -> R.string.import_all_count_medications
    "schedules" -> R.string.import_all_count_schedules
    "dose_logs" -> R.string.import_all_count_dose_logs
    "records" -> R.string.import_all_count_records
    "files" -> R.string.import_all_count_files
    "settings", "settings_values" -> R.string.import_all_count_settings
    "meal_photos" -> R.string.import_all_count_meal_photos
    else -> null
}

private fun importSectionTitleRes(section: String): Int = when (section) {
    AllDataExportCoordinator.SECTION_FOOD_DIARY -> R.string.import_all_section_food_diary
    AllDataExportCoordinator.SECTION_HEALTH_DATA -> R.string.import_all_section_health_data
    AllDataExportCoordinator.SECTION_MEDICATIONS -> R.string.import_all_section_medications
    AllDataExportCoordinator.SECTION_HEALTH_RECORDS -> R.string.import_all_section_health_records
    else -> R.string.import_all_section_app_backup
}

private fun importEffectRes(section: String): Int = when (section) {
    AllDataExportCoordinator.SECTION_FOOD_DIARY -> R.string.import_all_effect_food_diary
    AllDataExportCoordinator.SECTION_HEALTH_DATA -> R.string.import_all_effect_health_data
    AllDataExportCoordinator.SECTION_MEDICATIONS -> R.string.import_all_effect_medications
    AllDataExportCoordinator.SECTION_HEALTH_RECORDS -> R.string.import_all_effect_health_records
    else -> R.string.import_all_effect_app_backup
}

private fun skipReasonRes(reason: AllDataImportPlan.SkipReason): Int = when (reason) {
    AllDataImportPlan.SkipReason.OTHER_PLATFORM -> R.string.import_all_skip_other_platform
    AllDataImportPlan.SkipReason.IN_APP_BACKUP -> R.string.import_all_skip_in_app_backup
    AllDataImportPlan.SkipReason.UNSUPPORTED -> R.string.import_all_skip_unsupported
}

@Composable
private fun platformName(platform: String): String = when (platform) {
    AllDataImportPlan.PLATFORM_IOS -> stringResource(R.string.import_all_platform_ios)
    AllDataImportPlan.PLATFORM_ANDROID -> stringResource(R.string.import_all_platform_android)
    else -> platform
}

private fun formatExportDate(raw: String): String = runCatching {
    val instant = runCatching { Instant.parse(raw) }.getOrElse { OffsetDateTime.parse(raw).toInstant() }
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))
}.getOrDefault(raw)

private val AllDataExportStep.labelRes: Int
    get() = when (this) {
        AllDataExportStep.FOOD_DIARY -> R.string.export_all_step_food_diary
        AllDataExportStep.HEALTH_DATA -> R.string.export_all_step_health_data
        AllDataExportStep.MEDICATIONS -> R.string.export_all_step_medications
        AllDataExportStep.HEALTH_RECORDS -> R.string.export_all_step_health_records
        AllDataExportStep.COACH_CHATS -> R.string.export_all_step_coach_chats
        AllDataExportStep.APP_BACKUP -> R.string.export_all_step_app_backup
        AllDataExportStep.WRITING -> R.string.export_all_step_writing
    }

private fun sectionLabelRes(section: String): Int = when (section) {
    AllDataExportCoordinator.SECTION_FOOD_DIARY -> R.string.export_all_step_food_diary
    AllDataExportCoordinator.SECTION_HEALTH_DATA -> R.string.export_all_step_health_data
    AllDataExportCoordinator.SECTION_MEDICATIONS -> R.string.export_all_step_medications
    AllDataExportCoordinator.SECTION_HEALTH_RECORDS -> R.string.export_all_step_health_records
    AllDataExportCoordinator.SECTION_COACH_CHATS -> R.string.export_all_step_coach_chats
    else -> R.string.export_all_step_app_backup
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
                icon = Icons.Filled.DeleteSweep, iconTint = SettingsTint.Warning,
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
