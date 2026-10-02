package com.ayuvo.health.ui.settings.groups

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageContext
import kotlinx.coroutines.launch

/**
 * Tracking › Camera measurements (docs/camera-vitals.md §7.1 "Settings"): keep raw signals (default on), experimental
 * estimates (SpO₂, off), research estimates (BP, off) and "Delete all camera scans" behind a confirmation.
 */
@Composable
internal fun CameraMeasurementsSettingsPage(ctx: SettingsPageContext) {
    val prefs = ctx.container.prefs
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tint = SettingsPage.CAMERA_MEASUREMENTS.tint
    val keep by prefs.vitalsKeepSignals.collectAsState(initial = true)
    val experimental by prefs.vitalsExperimentalEnabled.collectAsState(initial = false)
    val research by prefs.vitalsResearchEnabled.collectAsState(initial = false)
    var confirmDelete by remember { mutableStateOf(false) }
    val deletedText = stringResource(R.string.camvitals_delete_all_done)

    InsetGroup(footer = stringResource(R.string.camvitals_settings_footer)) {
        row {
            GroupRow(
                title = stringResource(R.string.camvitals_keep_signals),
                subtitle = stringResource(R.string.camvitals_keep_signals_sub),
                icon = Icons.Filled.Storage, iconTint = tint,
                modifier = Modifier.settingsRow("vitalsKeepSignals"),
                trailing = RowTrailing.Toggle(keep, { v -> scope.launch { prefs.setVitalsKeepSignals(v) } })
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.camvitals_experimental),
                subtitle = stringResource(R.string.camvitals_experimental_sub),
                icon = Icons.Filled.ShowChart, iconTint = tint,
                modifier = Modifier.settingsRow("vitalsExperimentalEnabled"),
                trailing = RowTrailing.Toggle(experimental, { v -> scope.launch { prefs.setVitalsExperimentalEnabled(v) } })
            )
        }
        row {
            GroupRow(
                title = stringResource(R.string.camvitals_research),
                subtitle = stringResource(R.string.camvitals_research_sub),
                icon = Icons.Filled.Science, iconTint = tint,
                modifier = Modifier.settingsRow("vitalsResearchEnabled"),
                trailing = RowTrailing.Toggle(research, { v -> scope.launch { prefs.setVitalsResearchEnabled(v) } })
            )
        }
    }
    InsetGroup {
        row {
            GroupRow(
                title = stringResource(R.string.camvitals_delete_all),
                icon = Icons.Filled.Delete,
                destructive = true,
                trailing = RowTrailing.None,
                modifier = Modifier.settingsRow("vitalsDeleteAll"),
                onClick = { confirmDelete = true }
            )
        }
    }
    if (confirmDelete) {
        GlassDialog(onDismissRequest = { confirmDelete = false }) {
            Text(stringResource(R.string.camvitals_delete_all_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.camvitals_delete_all_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { ctx.container.vitalScans.deleteAllScans() }
                        Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show()
                    }
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmDelete = false },
                destructive = true
            )
        }
    }
}
