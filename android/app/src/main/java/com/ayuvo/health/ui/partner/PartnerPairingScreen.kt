package com.ayuvo.health.ui.partner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.partner.PairingUi
import com.ayuvo.health.partner.PartnerQrBitmap
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.home.BarcodeScannerSheet
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add partner (docs/partner-sync.md §4): Show code (QR + name + expiry, waiting for the partner) or Scan code
 * (camera, QR only), then both phones compare the 6-digit code, the user picks what to share (all off by default),
 * and the first sync runs on the same connection.
 */
@Composable
fun PartnerPairingScreen(container: AppContainer, showMode: Boolean, onClose: () -> Unit, onOpenPartner: (String) -> Unit) {
    val manager = container.partnerManager
    val state by manager.state.collectAsState()
    val pairing = state.pairing
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    // After "Codes match", the user chooses categories before PAIR_CONFIRM goes out.
    var choosing by rememberSaveable { mutableStateOf(false) }
    val grants = remember { mutableStateOf(setOf<String>()) }
    var scanning by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manager.cancelPairing()
        if (name == null) name = runCatching { manager.profileName() }.getOrDefault("")
    }
    DisposableEffect(Unit) { onDispose { if (manager.state.value.pairing !is PairingUi.Syncing) manager.cancelPairing() } }
    // Show code: (re)generate the QR once the name settles.
    if (showMode) {
        LaunchedEffect(name) {
            val n = name ?: return@LaunchedEffect
            if (started) delay(700)
            started = true
            runCatching { manager.showCode(n) }
        }
    }
    LaunchedEffect(pairing) { if (pairing !is PairingUi.CompareCode) choosing = false }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) scanning = true }
    fun openScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    fun close() {
        manager.cancelPairing()
        onClose()
    }
    fun retry() {
        manager.cancelPairing()
        if (showMode) scope.launch { runCatching { manager.showCode(name) } } else openScanner()
    }
    BackHandler { close() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(if (showMode) R.string.partner_show_code else R.string.partner_scan_code), onBack = ::close) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH).padding(top = 4.dp, bottom = 32.dp).testTag("partner.pairing"),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                pairing is PairingUi.CompareCode && choosing -> ChooseShare(grants.value, { grants.value = it }) {
                    manager.confirmPairing(true, grants.value, name.orEmpty())
                }
                pairing is PairingUi.CompareCode -> CompareCode(pairing, onMatch = { choosing = true }, onMismatch = {
                    manager.confirmPairing(false, emptyList(), name.orEmpty())
                })
                pairing is PairingUi.Connecting -> Progress(stringResource(R.string.partner_connecting))
                pairing is PairingUi.WaitingForPartner -> Progress(stringResource(R.string.partner_waiting_confirm))
                pairing is PairingUi.Syncing -> Progress(stringResource(R.string.partner_first_sync, pairing.name))
                pairing is PairingUi.Paired -> Outcome(
                    icon = Icons.Filled.CheckCircle, tint = AyuvoPalette.Success,
                    title = stringResource(R.string.partner_paired_title, pairing.name),
                    body = stringResource(if (pairing.synced) R.string.partner_paired_synced else R.string.partner_paired_not_synced),
                    primary = stringResource(R.string.partner_view_data, pairing.name), onPrimary = { onOpenPartner(pairing.ownerId) },
                    secondary = stringResource(R.string.partner_done), onSecondary = ::close
                )
                pairing is PairingUi.Declined -> Outcome(
                    icon = Icons.Filled.ErrorOutline, tint = AyuvoPalette.Warning,
                    title = stringResource(R.string.partner_declined_title), body = stringResource(R.string.partner_declined_body),
                    primary = stringResource(R.string.partner_try_again), onPrimary = ::retry,
                    secondary = stringResource(R.string.partner_done), onSecondary = ::close
                )
                pairing is PairingUi.Failed -> Outcome(
                    icon = Icons.Filled.ErrorOutline, tint = AyuvoPalette.Destructive,
                    title = stringResource(R.string.partner_pair_failed_title), body = stringResource(PartnerFormat.pairingError(pairing.code)),
                    primary = stringResource(R.string.partner_try_again), onPrimary = ::retry,
                    secondary = stringResource(R.string.partner_done), onSecondary = ::close
                )
                showMode -> ShowCode(pairing as? PairingUi.Showing, name, { name = it })
                else -> ScanIntro(name, { name = it }, ::openScanner)
            }
        }
    }

    if (scanning) {
        BarcodeScannerSheet(
            onBarcode = { text ->
                scanning = false
                scope.launch { manager.scan(text) }
            },
            onDismiss = { scanning = false },
            formats = Barcode.FORMAT_QR_CODE,
            hint = stringResource(R.string.partner_scan_hint),
            note = stringResource(R.string.partner_scan_note)
        )
    }
}

@Composable
private fun NameField(name: String?, onName: (String) -> Unit) {
    OutlinedTextField(
        value = name.orEmpty(),
        onValueChange = { onName(it.take(40)) },
        label = { Text(stringResource(R.string.partner_your_name)) },
        supportingText = { Text(stringResource(R.string.partner_your_name_sub)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("partner.pairing.name")
    )
}

@Composable
private fun ShowCode(showing: PairingUi.Showing?, name: String?, onName: (String) -> Unit) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showing?.qrText) {
        val text = showing?.qrText
        bitmap = if (text == null) null else withContext(Dispatchers.Default) { PartnerQrBitmap.bitmap(text, 720).asImageBitmap() }
    }
    LaunchedEffect(showing?.expMs) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    NameField(name, onName)
    SurfaceCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).testTag("partner.pairing.qr"),
            contentAlignment = Alignment.Center
        ) {
            val b = bitmap
            if (b != null) Image(b, contentDescription = stringResource(R.string.partner_qr_cd), modifier = Modifier.fillMaxSize().padding(8.dp))
            else CircularProgressIndicator()
        }
        if (showing != null) {
            val left = ((showing.expMs - now) / 1000).coerceAtLeast(0)
            Text(
                stringResource(R.string.partner_code_expires, left / 60, String.format(java.util.Locale.getDefault(), "%02d", left % 60)),
                fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.partner_waiting_scan), fontSize = 15.sp)
        }
    }
    Text(stringResource(R.string.partner_show_code_help), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
}

@Composable
private fun ScanIntro(name: String?, onName: (String) -> Unit, onScan: () -> Unit) {
    NameField(name, onName)
    SurfaceCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = AyuvoPalette.Partner, modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.partner_scan_intro), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = onScan, modifier = Modifier.fillMaxWidth().testTag("partner.pairing.scan"),
            colors = ButtonDefaults.buttonColors(containerColor = AyuvoPalette.Partner)
        ) { Text(stringResource(R.string.partner_scan_code)) }
    }
}

@Composable
private fun CompareCode(c: PairingUi.CompareCode, onMatch: () -> Unit, onMismatch: () -> Unit) {
    SurfaceCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.partner_compare_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            c.sas.chunked(3).joinToString(" "),
            fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp,
            textAlign = TextAlign.Center, color = AyuvoPalette.Partner, modifier = Modifier.fillMaxWidth().testTag("partner.pairing.sas")
        )
        Text(stringResource(R.string.partner_compare_body), fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (c.peerName != null) {
            Text(
                stringResource(R.string.partner_compare_peer, c.peerName, c.peerFingerprint?.let { PartnerFormat.shortFingerprint(it.replace(" ", "")) }.orEmpty()),
                fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
        Button(onClick = onMatch, modifier = Modifier.fillMaxWidth().testTag("partner.pairing.match"),
            colors = ButtonDefaults.buttonColors(containerColor = AyuvoPalette.Success)) { Text(stringResource(R.string.partner_codes_match)) }
        OutlinedButton(onClick = onMismatch, modifier = Modifier.fillMaxWidth().testTag("partner.pairing.mismatch")) {
            Text(stringResource(R.string.partner_codes_differ), color = AyuvoPalette.Destructive)
        }
    }
}

@Composable
private fun ChooseShare(selected: Set<String>, onChange: (Set<String>) -> Unit, onContinue: () -> Unit) {
    Text(stringResource(R.string.partner_choose_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
    InsetGroup(footer = stringResource(R.string.partner_choose_footer), modifier = Modifier.testTag("partner.pairing.grants")) {
        PartnerCategory.entries.forEach { cat ->
            row {
                GroupRow(
                    stringResource(cat.titleRes), icon = cat.icon, iconTint = cat.color, subtitle = stringResource(cat.subtitleRes),
                    modifier = Modifier.testTag("partner.pairing.grant.${cat.id}"),
                    trailing = RowTrailing.Toggle(cat.id in selected, { on -> onChange(if (on) selected + cat.id else selected - cat.id) })
                )
            }
        }
    }
    Button(
        onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("partner.pairing.continue"),
        colors = ButtonDefaults.buttonColors(containerColor = AyuvoPalette.Partner)
    ) { Text(stringResource(R.string.partner_continue)) }
}

@Composable
private fun Progress(text: String) {
    SurfaceCard(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        Text(text, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.partner_keep_open), fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Outcome(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit
) {
    SurfaceCard(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 520.dp).testTag("partner.pairing.outcome")) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(body, fontSize = 15.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AyuvoPalette.Partner)) { Text(primary) }
        OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondary) }
    }
}
