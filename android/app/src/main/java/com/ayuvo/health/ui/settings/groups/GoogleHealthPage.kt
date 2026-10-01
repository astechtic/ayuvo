package com.ayuvo.health.ui.settings.groups

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncLock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.services.googlehealth.GoogleHealthMap
import com.ayuvo.health.services.googlehealth.GoogleHealthUi
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassTextButton
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
import kotlinx.coroutines.launch

/** Per data group status on the connected page (docs/google-health.md §5). */
internal enum class GoogleHealthGroupStatus { OFF, NOT_GRANTED, ERROR, WAITING, OK }

/**
 * Worst status among the group's types: a group the user left unticked is OFF; a refused scope
 * is NOT_GRANTED; any type error (other than `unsupported`) is ERROR; no sync yet is WAITING.
 */
internal fun googleHealthGroupStatus(map: GoogleHealthMap, ui: GoogleHealthUi, groupId: String): GoogleHealthGroupStatus {
    val account = ui.account ?: return GoogleHealthGroupStatus.OFF
    if (groupId !in account.groups) return GoogleHealthGroupStatus.OFF
    val statuses = map.types.filter { it.scopeGroup == groupId }.map { ui.sync.typeStatus[it.ghType] }
    return when {
        statuses.any { it == "error:scope" } -> GoogleHealthGroupStatus.NOT_GRANTED
        statuses.any { it != null && it.startsWith("error:") } -> GoogleHealthGroupStatus.ERROR
        statuses.all { it == null || it == "unsupported" } -> GoogleHealthGroupStatus.WAITING
        else -> GoogleHealthGroupStatus.OK
    }
}

@StringRes
internal fun googleHealthGroupLabel(groupId: String): Int = when (groupId) {
    "body_vitals" -> R.string.google_health_group_body_vitals
    "activity" -> R.string.google_health_group_activity
    "sleep" -> R.string.google_health_group_sleep
    "nutrition" -> R.string.google_health_group_nutrition
    else -> R.string.google_health_group_heart_rhythm
}

internal fun googleHealthGroupIcon(groupId: String): ImageVector = when (groupId) {
    "body_vitals" -> Icons.Filled.Favorite
    "activity" -> Icons.AutoMirrored.Filled.DirectionsRun
    "sleep" -> Icons.Filled.Bedtime
    "nutrition" -> Icons.Filled.Restaurant
    else -> Icons.Filled.MonitorHeart
}

@StringRes
private fun GoogleHealthGroupStatus.labelRes(): Int = when (this) {
    GoogleHealthGroupStatus.OFF -> R.string.google_health_status_off
    GoogleHealthGroupStatus.NOT_GRANTED -> R.string.google_health_status_not_granted
    GoogleHealthGroupStatus.ERROR -> R.string.google_health_status_error
    GoogleHealthGroupStatus.WAITING -> R.string.google_health_status_waiting
    GoogleHealthGroupStatus.OK -> R.string.google_health_status_ok
}

/** One-line status for the row under Health Sync › Sync now. */
@Composable
internal fun googleHealthStatusText(ui: GoogleHealthUi): String = when {
    ui.needsReconnect -> stringResource(R.string.google_health_status_reconnect)
    ui.sync.running -> stringResource(R.string.settings_syncing)
    ui.lastFailed -> stringResource(R.string.google_health_status_error)
    ui.sync.lastSyncMs == null -> stringResource(R.string.google_health_status_waiting)
    else -> relativeTimeText(ui.sync.lastSyncMs)
}

/** Data & Privacy › Google Health: Connect when disconnected; account, groups and controls when connected. */
@Composable
internal fun GoogleHealthPage(ctx: SettingsPageContext) {
    val coordinator = ctx.container.googleHealth
    val ui by coordinator.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val tint = SettingsPage.GOOGLE_HEALTH.tint
    var showDisconnect by remember { mutableStateOf(false) }
    val account = ui.account

    if (account == null) {
        InsetGroup(footer = stringResource(R.string.google_health_disconnected_footer)) {
            row {
                GroupRow(
                    title = stringResource(R.string.google_health_connect),
                    subtitle = stringResource(R.string.google_health_connect_subtitle),
                    icon = Icons.Filled.Link, iconTint = tint,
                    modifier = Modifier.settingsRow("googleHealth.connect"),
                    onClick = { ctx.actions.navigate(AppRoutes.googleHealthSetup()) }
                )
            }
        }
        return
    }

    InsetGroup(footer = stringResource(R.string.google_health_connected_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.google_health_account),
                value = account.email ?: stringResource(R.string.google_health_title),
                icon = Icons.Filled.AccountCircle, iconTint = tint,
                modifier = Modifier.settingsRow("googleHealth.account"),
                trailing = RowTrailing.None
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.google_health_last_synced),
                value = googleHealthStatusText(ui),
                icon = Icons.Filled.History, iconTint = SettingsTint.Gray,
                modifier = Modifier.settingsRow("googleHealth.lastSynced"),
                trailing = RowTrailing.None
            )
        }
        if (ui.needsReconnect) {
            row {
                GroupRow(
                    title = stringResource(R.string.google_health_reconnect),
                    subtitle = stringResource(R.string.google_health_reconnect_subtitle),
                    icon = Icons.Filled.SyncLock, iconTint = AyuvoPalette.Warning,
                    modifier = Modifier.settingsRow("googleHealth.reconnect"),
                    onClick = { ctx.actions.navigate(AppRoutes.googleHealthSetup(start = 3)) }
                )
            }
        }
        row {
            GroupRow(
                title = stringResource(R.string.google_health_sync_now),
                icon = Icons.Filled.Sync, iconTint = tint,
                enabled = !ui.sync.running,
                modifier = Modifier.settingsRow("googleHealth.syncNow"),
                trailing = if (ui.sync.running) {
                    RowTrailing.Custom { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie) }
                } else {
                    RowTrailing.None
                },
                onClick = { scope.launch { coordinator.sync(com.ayuvo.health.services.googlehealth.GoogleHealthTrigger.MANUAL) } }
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.google_health_auto_sync),
                subtitle = stringResource(R.string.google_health_auto_sync_subtitle),
                icon = Icons.Filled.Update, iconTint = tint,
                modifier = Modifier.settingsRow("googleHealth.autoSync"),
                trailing = RowTrailing.Toggle(ui.autoSync, { on -> scope.launch { coordinator.setAutoSync(on) } })
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.google_health_write_back),
                subtitle = stringResource(R.string.google_health_write_back_subtitle),
                icon = Icons.Filled.Upload, iconTint = SettingsTint.HealthSync,
                modifier = Modifier.settingsRow("googleHealth.writeBack"),
                trailing = RowTrailing.Toggle(ui.writeBack, { on -> scope.launch { coordinator.setWriteBack(on) } })
            )
        }
    }

    InsetGroup(header = stringResource(R.string.google_health_groups_header)) {
        coordinator.map.scopeGroups.forEach { group ->
            row {
                val status = googleHealthGroupStatus(coordinator.map, ui, group.id)
                GroupRow(
                    title = stringResource(googleHealthGroupLabel(group.id)),
                    value = stringResource(status.labelRes()),
                    icon = googleHealthGroupIcon(group.id),
                    iconTint = if (status == GoogleHealthGroupStatus.OFF) SettingsTint.Gray else tint,
                    modifier = Modifier.settingsRow("googleHealth.group.${group.id}"),
                    trailing = RowTrailing.None
                )
            }
        }
        row {
            GroupRow(
                title = stringResource(R.string.google_health_manage),
                icon = Icons.Filled.Tune, iconTint = SettingsTint.Gray,
                modifier = Modifier.settingsRow("googleHealth.manage"),
                onClick = { ctx.actions.navigate(AppRoutes.googleHealthSetup(start = 2)) }
            )
        }
    }

    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.google_health_disconnect),
                icon = Icons.AutoMirrored.Filled.Logout,
                destructive = true,
                modifier = Modifier.settingsRow("googleHealth.disconnect"),
                trailing = RowTrailing.None,
                onClick = { showDisconnect = true }
            )
        }
    }

    if (showDisconnect) {
        GlassDialog(
            onDismissRequest = { showDisconnect = false },
            modifier = Modifier.testTag("settings.googleHealth.disconnectDialog")
        ) {
            Text(stringResource(R.string.google_health_disconnect_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.google_health_disconnect_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 14.sp
            )
            GlassTextButton(
                text = stringResource(R.string.google_health_disconnect_keep),
                onClick = {
                    showDisconnect = false
                    scope.launch { coordinator.disconnect(deleteRows = false) }
                },
                modifier = Modifier.testTag("settings.googleHealth.disconnectKeep")
            )
            GlassTextButton(
                text = stringResource(R.string.google_health_disconnect_delete),
                color = AyuvoPalette.Destructive,
                onClick = {
                    showDisconnect = false
                    scope.launch { coordinator.disconnect(deleteRows = true) }
                },
                modifier = Modifier.testTag("settings.googleHealth.disconnectDelete")
            )
            GlassTextButton(
                text = stringResource(R.string.action_cancel),
                color = MaterialTheme.colorScheme.onSurface,
                onClick = { showDisconnect = false }
            )
        }
    }
}
