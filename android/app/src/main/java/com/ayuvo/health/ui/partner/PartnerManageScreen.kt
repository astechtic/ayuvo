package com.ayuvo.health.ui.partner

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import kotlinx.coroutines.launch

private enum class Confirm { UNPAIR, DELETE_DATA, REMOVE }

/**
 * Settings › Partner Health › one partner (`settings/partner/manage/{ownerId}`): what I share (six categories,
 * default off; turning one on re-grants it, docs §9), Sync Now, Export package (everything / changes since the last
 * sync) to the share sheet, and Unpair / Delete partner data / Remove partner, each behind a confirmation.
 */
@Composable
fun PartnerManageScreen(container: AppContainer, ownerId: String, onBack: () -> Unit, onOpenDashboard: () -> Unit) {
    val manager = container.partnerManager
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(ownerId) { runCatching { manager.reload() } }
    val row = state.partners.firstOrNull { it.partner.ownerId == ownerId }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val exportFailed = stringResource(R.string.partner_export_failed)

    fun export(sinceAcked: Boolean) {
        exporting = true
        scope.launch {
            runCatching { manager.exportPackage(ownerId, sinceAcked) }
                .onSuccess { (_, intent) -> runCatching { context.startActivity(intent) } }
                .onFailure { Toast.makeText(context, exportFailed, Toast.LENGTH_LONG).show() }
            exporting = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = row?.partner?.displayName ?: stringResource(R.string.partner_title), onBack = onBack) }
    ) { padding ->
        if (row == null) {
            if (state.loaded) LaunchedEffect(Unit) { onBack() }
            return@Scaffold
        }
        val p = row.partner
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH).padding(top = 4.dp).testTag("settings.partner.manage"),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            SurfaceCard(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PartnerAvatar(p.displayName, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(partnerFreshnessLine(p, row.sync), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.partner_fingerprint), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                Text(PartnerFormat.fullFingerprint(p.fingerprint), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.partner_fingerprint_hint), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
            }

            InsetGroup {
                row {
                    GroupRow(
                        stringResource(R.string.partner_view_data, p.displayName), icon = Icons.Filled.Visibility, iconTint = AyuvoPalette.Partner,
                        modifier = Modifier.testTag("settings.partner.viewData"), onClick = onOpenDashboard
                    )
                }
                if (p.trusted) row {
                    GroupRow(
                        stringResource(if (state.windowRunning) R.string.partner_syncing else R.string.partner_sync_now),
                        icon = Icons.Filled.Sync, iconTint = AyuvoPalette.Hydration, enabled = !state.windowRunning,
                        subtitle = stringResource(R.string.partner_sync_now_sub),
                        modifier = Modifier.testTag("settings.partner.syncNow"), onClick = { manager.syncNow() }
                    )
                }
            }

            if (p.trusted) {
                val grants = row.grantsOut.filter { it.granted }.map { it.category }.toSet()
                InsetGroup(
                    modifier = Modifier.testTag("settings.partner.grants"),
                    header = stringResource(R.string.partner_what_i_share),
                    footer = stringResource(R.string.partner_what_i_share_footer, p.displayName)
                ) {
                    PartnerCategory.entries.forEach { cat ->
                        row {
                            GroupRow(
                                stringResource(cat.titleRes), icon = cat.icon, iconTint = cat.color,
                                subtitle = stringResource(cat.subtitleRes),
                                modifier = Modifier.testTag("settings.partner.grant.${cat.id}"),
                                trailing = RowTrailing.Toggle(cat.id in grants, { on ->
                                    scope.launch { runCatching { manager.setGrants(ownerId, mapOf(cat.id to on)) } }
                                })
                            )
                        }
                    }
                }

                InsetGroup(header = stringResource(R.string.partner_export_header), footer = stringResource(R.string.partner_export_footer)) {
                    row {
                        GroupRow(
                            stringResource(R.string.partner_export_all), icon = Icons.Filled.IosShare, iconTint = AyuvoPalette.Records,
                            enabled = !exporting, modifier = Modifier.testTag("settings.partner.exportAll"),
                            onClick = { export(sinceAcked = false) }
                        )
                    }
                    row {
                        GroupRow(
                            stringResource(R.string.partner_export_changes), icon = Icons.Filled.IosShare, iconTint = AyuvoPalette.Records,
                            enabled = !exporting, modifier = Modifier.testTag("settings.partner.exportChanges"),
                            onClick = { export(sinceAcked = true) }
                        )
                    }
                }
            }

            InsetGroup(footer = stringResource(R.string.partner_remove_footer)) {
                if (p.trusted) row {
                    GroupRow(
                        stringResource(R.string.partner_unpair), icon = Icons.Filled.LinkOff, destructive = true,
                        modifier = Modifier.testTag("settings.partner.unpair"), onClick = { confirm = Confirm.UNPAIR }
                    )
                }
                row {
                    GroupRow(
                        stringResource(R.string.partner_delete_data), icon = Icons.Filled.Delete, destructive = true,
                        modifier = Modifier.testTag("settings.partner.deleteData"), onClick = { confirm = Confirm.DELETE_DATA }
                    )
                }
                row {
                    GroupRow(
                        stringResource(R.string.partner_remove), icon = Icons.Filled.DeleteForever, destructive = true,
                        modifier = Modifier.testTag("settings.partner.remove"), onClick = { confirm = Confirm.REMOVE }
                    )
                }
            }
            Spacer(Modifier.height(BottomNavScrollPadding))
        }

        confirm?.let { c ->
            val (title, message, action) = when (c) {
                Confirm.UNPAIR -> Triple(R.string.partner_unpair_title, R.string.partner_unpair_message, R.string.partner_unpair)
                Confirm.DELETE_DATA -> Triple(R.string.partner_delete_data_title, R.string.partner_delete_data_message, R.string.partner_delete_data)
                Confirm.REMOVE -> Triple(R.string.partner_remove_title, R.string.partner_remove_message, R.string.partner_remove)
            }
            GlassDialog(onDismissRequest = { confirm = null }) {
                Text(stringResource(title, p.displayName), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(message, p.displayName), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
                GlassDialogActions(
                    primaryText = stringResource(action),
                    onPrimary = {
                        confirm = null
                        scope.launch {
                            runCatching {
                                when (c) {
                                    Confirm.UNPAIR -> manager.unpair(ownerId)
                                    Confirm.DELETE_DATA -> manager.deletePartnerData(ownerId)
                                    // The screen pops itself once the partner is gone.
                                    Confirm.REMOVE -> manager.removePartner(ownerId)
                                }
                            }
                        }
                    },
                    dismissText = stringResource(R.string.action_cancel),
                    onDismiss = { confirm = null },
                    destructive = true
                )
            }
        }
    }
}
