package com.ayuvo.health.ui.partner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.partner.PartnerRow
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.navigation.AppRoutes

/** MIME types the Import package picker offers (zip packages; some providers report octet-stream). */
internal val PACKAGE_PICKER_TYPES = arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")

/**
 * Settings › Data & Privacy › Partner Health (docs/partner-sync.md §1, §4, §11, §13–§14): partners with their
 * freshness, Add partner (Show / Scan code), Import package (file fallback), this device's fingerprint and an honest
 * explainer of how sync works. Rendered inside the Settings page column.
 */
@Composable
fun PartnerHealthSettingsContent(container: AppContainer, navigate: (String) -> Unit) {
    val manager = container.partnerManager
    val state by manager.state.collectAsState()
    var fingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { manager.reload() }
        fingerprint = runCatching { manager.myFingerprint() }.getOrNull()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) manager.importFromPicker(uri)
    }

    SurfaceCard(modifier = Modifier.testTag("settings.partner.intro"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = AyuvoPalette.Partner)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.partner_intro_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.partner_intro_body), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
    }

    InsetGroup(
        modifier = Modifier.testTag("settings.partner.list"),
        header = stringResource(R.string.partner_partners_header),
        footer = if (state.partners.isEmpty()) stringResource(R.string.partner_partners_empty) else null
    ) {
        state.partners.forEach { row -> row { PartnerListRow(row) { navigate(AppRoutes.partnerManage(row.partner.ownerId)) } } }
    }

    InsetGroup(header = stringResource(R.string.partner_add_header), footer = stringResource(R.string.partner_add_footer)) {
        row {
            GroupRow(
                stringResource(R.string.partner_show_code), icon = Icons.Filled.QrCode2, iconTint = AyuvoPalette.Partner,
                subtitle = stringResource(R.string.partner_show_code_sub),
                modifier = Modifier.testTag("settings.partner.showCode"),
                onClick = { navigate(AppRoutes.partnerPair(AppRoutes.PAIR_SHOW)) }
            )
        }
        row {
            GroupRow(
                stringResource(R.string.partner_scan_code), icon = Icons.Filled.QrCodeScanner, iconTint = AyuvoPalette.Partner,
                subtitle = stringResource(R.string.partner_scan_code_sub),
                modifier = Modifier.testTag("settings.partner.scanCode"),
                onClick = { navigate(AppRoutes.partnerPair(AppRoutes.PAIR_SCAN)) }
            )
        }
    }

    InsetGroup(footer = stringResource(R.string.partner_import_footer)) {
        row {
            GroupRow(
                stringResource(R.string.partner_import), icon = Icons.Filled.FileOpen, iconTint = AyuvoPalette.Records,
                modifier = Modifier.testTag("settings.partner.import"),
                onClick = { picker.launch(PACKAGE_PICKER_TYPES) }
            )
        }
    }

    InsetGroup(header = stringResource(R.string.partner_this_device), footer = stringResource(R.string.partner_this_device_footer)) {
        row {
            GroupRow(
                stringResource(R.string.partner_fingerprint), icon = Icons.Filled.Fingerprint, iconTint = AyuvoPalette.Other,
                subtitle = fingerprint?.let { PartnerFormat.fullFingerprint(it) } ?: "",
                trailing = RowTrailing.None,
                modifier = Modifier.testTag("settings.partner.fingerprint")
            )
        }
    }

    SurfaceCard(modifier = Modifier.testTag("settings.partner.howItWorks"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.partner_how_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        listOf(R.string.partner_how_lan, R.string.partner_how_awake, R.string.partner_how_no_cloud, R.string.partner_how_signed, R.string.partner_how_read_only)
            .forEach { res ->
                Row {
                    Text(stringResource(R.string.partner_bullet), fontSize = 14.sp, color = AyuvoPalette.Partner)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(res), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
                }
            }
    }
}

@Composable
private fun PartnerListRow(row: PartnerRow, onClick: () -> Unit) {
    val revoked = row.grantsReceived.any { !it.granted && it.revokedMs != null }
    GroupRow(
        title = row.partner.displayName,
        subtitle = PartnerFormat.shortFingerprint(row.partner.fingerprint) + "\n" + partnerFreshnessLine(row.partner, row.sync),
        leading = { PartnerAvatar(row.partner.displayName, 32) },
        trailing = if (revoked) RowTrailing.Custom {
            Column(horizontalAlignment = Alignment.End) { NoLongerSharedBadge() }
        } else RowTrailing.Chevron,
        modifier = Modifier.testTag("settings.partner.row.${row.partner.ownerId}"),
        onClick = onClick
    )
}
