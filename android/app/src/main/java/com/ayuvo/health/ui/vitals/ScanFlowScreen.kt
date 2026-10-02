package com.ayuvo.health.ui.vitals

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.vitals.camera.CameraFrameSource
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.session.ScanSession
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlin.math.roundToInt

/**
 * The scan flow of docs/camera-vitals.md §7.1: intro (instructions + context) → camera permission → live → analysing
 * → results with Save / Discard. [sessionId] links a compare pair and [onFinished] receives the saved scan id (null
 * when discarded or cancelled) so Wave 3 can chain the face scan after the finger scan.
 */
@Composable
fun ScanFlowScreen(
    container: AppContainer,
    mode: VitalsMode,
    sessionId: String? = null,
    onFinished: (String?) -> Unit,
    onBack: () -> Unit,
    /** Compare finger leg saved: start the face scan with this session id. */
    onNextFaceScan: (String) -> Unit = {},
    /** Compare face leg saved and linked: open the compare screen of this session. */
    onOpenCompare: (String) -> Unit = {}
) {
    val vm: ScanFlowViewModel = viewModel(key = "vitals-scan-${mode.id}-$sessionId", factory = ScanFlowViewModel.Factory(container, mode, sessionId))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Portrait while the flow is open; the screen stays on while a scan runs.
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    val scanning = ui.step == ScanStep.LIVE || ui.step == ScanStep.ANALYSING
    // The live screen is dark: light status-bar icons while it shows.
    val live = ui.step == ScanStep.LIVE
    DisposableEffect(activity, live) {
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val previous = controller?.isAppearanceLightStatusBars
        if (live) controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    DisposableEffect(activity, scanning) {
        if (scanning) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.requestCamera() else vm.showPermission()
    }

    fun begin() {
        when {
            ui.replay -> vm.startReplay()
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> vm.requestCamera()
            else -> permission.launch(Manifest.permission.CAMERA)
        }
    }

    BackHandler(enabled = ui.step == ScanStep.LIVE) { vm.cancel() }

    when (ui.step) {
        ScanStep.INTRO -> IntroStep(vm, ui, onBack = { onFinished(null); onBack() }, onStart = ::begin)
        ScanStep.PERMISSION -> PermissionStep(
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            onRetry = { vm.backToIntro(); begin() },
            onBack = { vm.backToIntro() }
        )
        ScanStep.LIVE -> if (ui.useCamera) CameraLiveStep(vm, ui) else LiveStep(vm, ui, null)
        ScanStep.ANALYSING -> AnalysingStep()
        ScanStep.RESULTS -> ResultStep(
            container, vm, ui,
            onSave = { vm.save() },
            onDiscard = { vm.discard(); onFinished(null); onBack() },
            onDone = { onFinished(ui.savedId) },
            onNextFaceScan = { vm.compareSessionId?.let(onNextFaceScan) },
            onOpenCompare = { vm.compareSessionId?.let(onOpenCompare) }
        )
    }
}

@Composable
private fun IntroStep(vm: ScanFlowViewModel, ui: ScanFlowUi, onBack: () -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val finger = vm.mode == VitalsMode.FINGER
    val steps = if (finger) listOf(R.string.camvitals_finger_step_1, R.string.camvitals_finger_step_2, R.string.camvitals_finger_step_3)
    else listOf(R.string.camvitals_face_step_1, R.string.camvitals_face_step_2, R.string.camvitals_face_step_3, R.string.camvitals_face_step_4, R.string.camvitals_face_step_5)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(
                title = stringResource(if (finger) R.string.camvitals_finger_scan else R.string.camvitals_face_scan),
                onBack = onBack,
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH)
                .padding(bottom = 24.dp)
                .testTag("vitals.intro"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(vitalsTint().copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (finger) Icons.Filled.Fingerprint else Icons.Filled.Face, contentDescription = null, tint = vitalsTint(), modifier = Modifier.size(48.dp))
                }
            }
            InsetGroup {
                steps.forEachIndexed { i, res ->
                    row {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                            Text("${i + 1}", fontWeight = FontWeight.Bold, color = vitalsTint(), modifier = Modifier.width(24.dp))
                            Text(stringResource(res), fontSize = 15.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
            Column {
                Text(
                    stringResource(R.string.camvitals_context).uppercase(),
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                    fontSize = 12.sp,
                    color = AyuvoColors.secondaryLabel()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    listOf(
                        VitalScanRecord.CONTEXT_RESTING to R.string.camvitals_context_resting,
                        VitalScanRecord.CONTEXT_AFTER_ACTIVITY to R.string.camvitals_context_after_activity,
                        VitalScanRecord.CONTEXT_OTHER to R.string.camvitals_context_other
                    ).forEach { (id, res) ->
                        FilterChip(
                            selected = ui.context == id,
                            onClick = { vm.setContext(id) },
                            label = { Text(stringResource(res)) },
                            modifier = Modifier.testTag("vitals.context.$id")
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = AyuvoColors.secondaryLabel(), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.camvitals_privacy_note), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
            }
            if (ui.compare) {
                Text(
                    if (finger) stringResource(R.string.camvitals_compare_intro_finger, vm.compareGapMinutes)
                    else stringResource(R.string.camvitals_compare_intro_face),
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("vitals.compare.intro"),
                    fontSize = 14.sp,
                    color = vitalsTint()
                )
            }
            if (ui.replay) {
                Text(
                    stringResource(R.string.camvitals_replay_note),
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("vitals.replay"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("vitals.start"),
                colors = ButtonDefaults.buttonColors(containerColor = vitalsTint())
            ) { Text(stringResource(R.string.camvitals_start), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                VitalsText.disclaimer(context, vm.cfg),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AyuvoColors.secondaryLabel()
            )
        }
    }
}

@Composable
private fun PermissionStep(onOpenSettings: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(24.dp)
            .testTag("vitals.permission"),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.camvitals_permission_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.camvitals_permission_body), fontSize = 15.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center)
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().testTag("vitals.permission.settings")) {
            Text(stringResource(R.string.camvitals_open_settings))
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.camvitals_try_again)) }
    }
}

/** Live camera step: owns the CameraX source for as long as it is composed. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraLiveStep(vm: ScanFlowViewModel, ui: ScanFlowUi) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val source = CameraFrameSource(
            context = context,
            mode = vm.mode,
            owner = owner,
            preview = if (vm.mode == VitalsMode.FACE) previewView.surfaceProvider else null,
            roiNames = vm.cfg.face.rois,
            scope = vm.sourceScope,
            onCapabilities = { caps, json -> vm.onDeviceProfile(json, caps.position) },
            onError = { vm.onCameraError() }
        )
        vm.start(source)
        onDispose { vm.stopFrames() }
    }
    LiveStep(vm, ui, if (vm.mode == VitalsMode.FACE) previewView else null)
}

@Composable
private fun LiveStep(vm: ScanFlowViewModel, ui: ScanFlowUi, preview: PreviewView?) {
    val context = LocalContext.current
    val face = vm.mode == VitalsMode.FACE
    val dark = Color(0xFF0B0B0D)
    Box(Modifier.fillMaxSize().background(dark).testTag("vitals.live")) {
        if (face) {
            if (preview != null) AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            OvalGuide(Modifier.fillMaxSize(), ok = ui.guidance == ScanSession.GUIDANCE_OK)
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.testTag("vitals.cancel")) {
                    Text(stringResource(R.string.camvitals_cancel), color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                SignalChip(ui.signalShare)
            }
            if (!face) Spacer(Modifier.weight(0.6f)) else Spacer(Modifier.weight(1f))
            ClockRing(ui, vm.cfg.scan.maxS, compact = face)
            Spacer(Modifier.height(16.dp))
            val statusText = when (ui.phase) {
                ScanSession.Phase.WAITING -> if (ui.guidance == ScanSession.GUIDANCE_WAITING || ui.guidance == ScanSession.GUIDANCE_OK) stringResource(R.string.camvitals_getting_ready) else null
                ScanSession.Phase.PAUSED -> stringResource(R.string.camvitals_paused)
                else -> if (ui.extending) stringResource(R.string.camvitals_extending) else null
            }
            statusText?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
            val guidanceKey = if (ui.guidance == ScanSession.GUIDANCE_WAITING) (if (face) "face_none" else "no_finger") else ui.guidance
            Text(
                VitalsText.guidance(context, vm.cfg, guidanceKey),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("vitals.guidance")
            )
            Spacer(Modifier.height(20.dp))
            LiveWaveform(
                ui.waveform,
                vitalsTint(),
                Modifier.fillMaxWidth().height(if (face) 70.dp else 120.dp).testTag("vitals.liveWaveform")
            )
            if (!face) Spacer(Modifier.weight(1f))
            if (ui.cameraError) {
                Text(stringResource(R.string.camvitals_camera_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            if (ui.replay) {
                Text(stringResource(R.string.camvitals_replay_note), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun SignalChip(share: Double) {
    val pct = (share * 100).roundToInt()
    val color = when {
        pct >= 90 -> Color(0xFF34C759)
        pct >= 60 -> Color(0xFFFF9500)
        else -> Color(0xFFFF3B30)
    }
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("vitals.signal"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.camvitals_signal_chip, pct), color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ClockRing(ui: ScanFlowUi, maxS: Double, compact: Boolean = false) {
    val goal = if (ui.extending) maxS else ui.targetS
    val progress = (ui.elapsedS / goal).toFloat().coerceIn(0f, 1f)
    val track = Color.White.copy(alpha = 0.15f)
    val tint = vitalsTint()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (compact) 96.dp else 150.dp).testTag("vitals.clock")) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (if (compact) 7 else 10).dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
            drawArc(tint, -90f, 360f * progress, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(stringResource(R.string.camvitals_seconds, ui.elapsedS.toInt()), color = Color.White, fontSize = if (compact) 22.sp else 30.sp, fontWeight = FontWeight.Bold)
    }
}

/** Dimmed surround with an oval cut-out; the face goes inside the oval. */
@Composable
private fun OvalGuide(modifier: Modifier, ok: Boolean) {
    val stroke = if (ok) Color(0xFF34C759) else Color.White
    Canvas(modifier.testTag("vitals.oval")) {
        val w = size.width * 0.62f
        val h = w * 1.3f
        val topLeft = Offset((size.width - w) / 2f, size.height * 0.36f - h / 2f)
        val oval = Path().apply { addOval(androidx.compose.ui.geometry.Rect(topLeft, Size(w, h))) }
        clipPath(oval, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }
        drawOval(stroke, topLeft, Size(w, h), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun AnalysingStep() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("vitals.analysing"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = vitalsTint())
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.camvitals_analysing), fontSize = 16.sp)
    }
}

@Composable
private fun ResultStep(
    container: AppContainer,
    vm: ScanFlowViewModel,
    ui: ScanFlowUi,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDone: () -> Unit,
    onNextFaceScan: () -> Unit,
    onOpenCompare: () -> Unit
) {
    val result = ui.result ?: return
    val saved = ui.savedId != null
    var showReference by remember { mutableStateOf(false) }
    BackHandler { if (saved) onDone() else onDiscard() }
    val fingerLeg = ui.compare && vm.mode == VitalsMode.FINGER
    val faceLinked = ui.compare && vm.mode == VitalsMode.FACE && !ui.compareUnlinked
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.camvitals_results), onBack = null, windowInsets = WindowInsets.statusBars) },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!saved) {
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f).height(50.dp).testTag("vitals.discard"), enabled = !ui.saving) {
                        Text(stringResource(R.string.camvitals_discard))
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f).height(50.dp).testTag("vitals.save"),
                        enabled = !ui.saving,
                        colors = ButtonDefaults.buttonColors(containerColor = vitalsTint())
                    ) { Text(stringResource(R.string.camvitals_save), fontWeight = FontWeight.SemiBold) }
                } else {
                    OutlinedButton(
                        onClick = { showReference = true },
                        modifier = Modifier.weight(1f).height(50.dp).testTag("vitals.addReference"),
                        enabled = ui.savedRecord != null
                    ) { Text(stringResource(R.string.camvitals_add_reference), maxLines = 1) }
                    val (label, action, tag) = when {
                        fingerLeg -> Triple(R.string.camvitals_next_face_scan, onNextFaceScan, "vitals.nextFace")
                        faceLinked -> Triple(R.string.camvitals_open_compare, onOpenCompare, "vitals.openCompare")
                        else -> Triple(R.string.camvitals_done, onDone, "vitals.done")
                    }
                    Button(
                        onClick = action,
                        modifier = Modifier.weight(1f).height(50.dp).testTag(tag),
                        colors = ButtonDefaults.buttonColors(containerColor = vitalsTint())
                    ) { Text(stringResource(label), fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH, vertical = 8.dp)
                .testTag("vitals.results")
        ) {
            if (ui.compare && vm.mode == VitalsMode.FACE && ui.compareUnlinked) {
                Text(
                    stringResource(R.string.camvitals_compare_unlinked, vm.compareGapMinutes),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(14.dp)
                        .testTag("vitals.compare.unlinked"),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
            }
            if (saved) {
                Text(
                    if (fingerLeg) stringResource(R.string.camvitals_saved_next_face, vm.compareGapMinutes)
                    else stringResource(R.string.camvitals_saved),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("vitals.saved"),
                    fontSize = 13.sp,
                    color = AyuvoColors.secondaryLabel()
                )
                Spacer(Modifier.height(8.dp))
            }
            VitalResultContent(result, vm.cfg)
            Spacer(Modifier.height(16.dp))
        }
    }
    val record = ui.savedRecord
    if (showReference && record != null) {
        ReferenceReadingSheet(
            container = container,
            record = record,
            experimentalEnabled = ui.experimentalEnabled,
            researchEnabled = ui.researchEnabled,
            onDismiss = { showReference = false },
            onSaved = { showReference = false; vm.refreshSaved() }
        )
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
