package com.ayuvo.health.ui.partner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.partner.IncomingPackageUi
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.CategoryIcon
import java.text.NumberFormat
import java.util.Locale

/**
 * The `.ayuvo.zip` confirmation sheet (docs/partner-sync.md §13 "Import"), hoisted above the NavHost so a package
 * opened from the share sheet or Files shows on any tab: "Health data from <name>" with per-category counts, then the
 * outcome (counts, "Already up to date", or why the file was refused).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PartnerImportHost(container: AppContainer, onOpenPartner: (String) -> Unit) {
    val manager = container.partnerManager
    val state by manager.state.collectAsState()
    val incoming = state.incoming ?: return
    val importing = incoming is IncomingPackageUi.Importing
    ModalBottomSheet(
        onDismissRequest = { if (!importing) manager.discardImport() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !importing }),
        shape = AyuvoShapes.Sheet,
        containerColor = AyuvoColors.sheetBackground()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().semantics { testTagsAsResourceId = true }.testTag("partner.import.sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (incoming) {
                is IncomingPackageUi.Preview -> Preview(incoming, onCancel = manager::discardImport, onImport = { manager.confirmImport() })
                IncomingPackageUi.Importing -> {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Text(stringResource(R.string.partner_importing), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                is IncomingPackageUi.Done -> Done(incoming, onClose = manager::discardImport, onOpen = { id ->
                    manager.discardImport()
                    onOpenPartner(id)
                })
                is IncomingPackageUi.Invalid -> Problem(
                    title = stringResource(R.string.partner_import_failed_title),
                    body = stringResource(PartnerFormat.packageError(incoming.code)),
                    onClose = manager::discardImport
                )
            }
        }
    }
}

private fun count(n: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

@Composable
private fun ColumnScope.Preview(p: IncomingPackageUi.Preview, onCancel: () -> Unit, onImport: () -> Unit) {
    val preview = p.preview
    Row(verticalAlignment = Alignment.CenterVertically) {
        PartnerAvatar(preview.senderName, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.partner_import_title, preview.senderName), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                pluralStringResource(R.plurals.partner_records_count, preview.summary.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count(preview.summary.total)),
                fontSize = 14.sp, color = AyuvoColors.secondaryLabel()
            )
        }
    }
    Column(Modifier.testTag("partner.import.categories"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        preview.summary.perCategory.filter { it.second > 0 }.forEach { (cat, n) ->
            val c = PartnerCategory.of(cat)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c != null) CategoryIcon(c.icon, c.color, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(c?.let { stringResource(it.titleRes) } ?: cat, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(count(n), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Text(stringResource(R.string.partner_import_note, preview.senderName), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("partner.import.cancel")) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = onImport, modifier = Modifier.weight(1f).testTag("partner.import.confirm"),
            colors = ButtonDefaults.buttonColors(containerColor = AyuvoPalette.Partner)
        ) { Text(stringResource(R.string.partner_import_action)) }
    }
}

@Composable
private fun ColumnScope.Done(d: IncomingPackageUi.Done, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val o = d.outcome
    if (!o.ok) {
        Problem(stringResource(R.string.partner_import_failed_title), stringResource(PartnerFormat.packageError(o.error)), onClose)
        return
    }
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AyuvoPalette.Success, modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally))
    Text(
        stringResource(if (o.alreadyUpToDate) R.string.partner_import_up_to_date else R.string.partner_import_done_title, d.senderName),
        fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("partner.import.result")
    )
    if (!o.alreadyUpToDate) {
        Text(
            stringResource(R.string.partner_import_counts, count(o.counts.inserted.toLong()), count(o.counts.updated.toLong()), count(o.counts.deleted.toLong())),
            fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
    if (o.counts.rejected > 0) {
        Text(
            pluralStringResource(R.plurals.partner_import_rejected, o.counts.rejected, o.counts.rejected),
            fontSize = 13.sp, color = AyuvoPalette.Warning, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
    val owner = d.ownerId
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).testTag("partner.import.close")) { Text(stringResource(R.string.partner_done)) }
        if (owner != null) {
            Button(
                onClick = { onOpen(owner) }, modifier = Modifier.weight(1f).testTag("partner.import.view"),
                colors = ButtonDefaults.buttonColors(containerColor = AyuvoPalette.Partner)
            ) { Text(stringResource(R.string.partner_view_short)) }
        }
    }
}

@Composable
private fun ColumnScope.Problem(title: String, body: String, onClose: () -> Unit) {
    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AyuvoPalette.Destructive, modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally))
    Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(body, fontSize = 15.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("partner.import.error"))
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("partner.import.close")) { Text(stringResource(R.string.partner_done)) }
}
