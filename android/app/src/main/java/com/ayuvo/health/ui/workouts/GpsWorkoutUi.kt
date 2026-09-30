package com.ayuvo.health.ui.workouts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.workout.HrWindow
import com.ayuvo.health.data.workout.WorkoutConfig
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.services.workout.OutdoorLiveState
import com.ayuvo.health.services.workout.OutdoorPhase
import com.ayuvo.health.services.workout.OutdoorWorkoutService
import com.ayuvo.health.services.workout.WorkoutFormat
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// -- Permission flow ---------------------------------------------------------------------------------

/** What the permission flow is preparing for. */
internal sealed interface WorkoutStartRequest {
    data class Gps(val sport: String, val cooper: Boolean, val resumeRecovered: Boolean = false) : WorkoutStartRequest
    data object Strength : WorkoutStartRequest
}

private enum class PermissionStep { LOCATION, LOCATION_DENIED, NOTIFICATIONS, HEALTH, DONE }

/**
 * Runtime permissions with rationale copy (docs/workouts-gps.md §4): precise location for a GPS workout (required),
 * notifications for the live controls (optional), then the Health Connect workout permissions once (optional).
 * [onReady] runs when everything required is granted; [onCancel] when the person stops.
 */
@Composable
internal fun WorkoutPermissionFlow(
    container: AppContainer,
    request: WorkoutStartRequest,
    onReady: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ayuvo_workouts", Context.MODE_PRIVATE) }
    val needsLocation = request is WorkoutStartRequest.Gps
    var step by remember {
        mutableStateOf(
            when {
                needsLocation && !OutdoorWorkoutService.hasFineLocation(context) -> PermissionStep.LOCATION
                else -> PermissionStep.NOTIFICATIONS
            }
        )
    }
    val healthKey = if (needsLocation) "hc.gps.asked" else "hc.strength.asked"
    val wanted = if (needsLocation) container.health.gpsWorkoutPermissions else container.health.strengthSessionPermissions
    var missingHealth by remember { mutableStateOf<Set<String>?>(null) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        step = if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) PermissionStep.NOTIFICATIONS else PermissionStep.LOCATION_DENIED
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        prefs.edit().putBoolean("notif.asked", true).apply()
        step = PermissionStep.HEALTH
    }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        prefs.edit().putBoolean(healthKey, true).apply()
        step = PermissionStep.DONE
    }

    LaunchedEffect(step) {
        when (step) {
            PermissionStep.NOTIFICATIONS -> {
                val granted = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted || prefs.getBoolean("notif.asked", false)) step = PermissionStep.HEALTH
            }
            PermissionStep.HEALTH -> {
                val eligible = !prefs.getBoolean(healthKey, false) &&
                    container.prefs.healthConnectEnabled.first() &&
                    runCatching { container.health.isAvailable() }.getOrDefault(false)
                val missing = if (eligible) runCatching { container.health.missingPermissions(wanted) }.getOrNull() else null
                if (missing.isNullOrEmpty()) step = PermissionStep.DONE else missingHealth = missing
            }
            PermissionStep.DONE -> onReady()
            else -> Unit
        }
    }

    when (step) {
        PermissionStep.LOCATION -> RationaleDialog(
            title = stringResource(R.string.workout_location_rationale_title),
            text = stringResource(R.string.workout_location_rationale_text),
            confirm = stringResource(R.string.workout_permission_continue),
            onConfirm = {
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onDismiss = onCancel
        )
        PermissionStep.LOCATION_DENIED -> RationaleDialog(
            title = stringResource(R.string.workout_location_rationale_title),
            text = stringResource(R.string.workout_location_denied),
            confirm = stringResource(R.string.workout_open_settings),
            onConfirm = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onCancel()
            },
            onDismiss = onCancel
        )
        PermissionStep.NOTIFICATIONS -> {
            val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("notif.asked", false)
            if (needsAsk) RationaleDialog(
                title = stringResource(R.string.workout_notifications_rationale_title),
                text = stringResource(R.string.workout_notifications_rationale_text),
                confirm = stringResource(R.string.workout_permission_continue),
                onConfirm = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onDismiss = {
                    prefs.edit().putBoolean("notif.asked", true).apply()
                    step = PermissionStep.HEALTH
                }
            )
        }
        PermissionStep.HEALTH -> missingHealth?.let { missing ->
            RationaleDialog(
                title = stringResource(R.string.workout_health_rationale_title),
                text = stringResource(R.string.workout_health_rationale_text),
                confirm = stringResource(R.string.workout_permission_continue),
                onConfirm = { runCatching { healthLauncher.launch(missing) }.onFailure { step = PermissionStep.DONE } },
                onDismiss = {
                    prefs.edit().putBoolean(healthKey, true).apply()
                    step = PermissionStep.DONE
                }
            )
        }
        PermissionStep.DONE -> Unit
    }
}

@Composable
private fun RationaleDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismissRequest = onDismiss) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 15.sp, lineHeight = 21.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassTextButton(text = stringResource(R.string.workout_permission_not_now), onClick = onDismiss, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = confirm, onClick = onConfirm)
        }
    }
}

// -- Sport picker ------------------------------------------------------------------------------------

internal fun sportIcon(sport: String): ImageVector = when (sport) {
    "run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "cycle" -> Icons.AutoMirrored.Filled.DirectionsBike
    "hike" -> Icons.Filled.Hiking
    else -> Icons.AutoMirrored.Filled.DirectionsWalk
}

/** The "Start GPS workout" sport picker (walk, run, cycle, hike from the workout config) with the Cooper option. */
@Composable
internal fun GpsSportPickerDialog(config: WorkoutConfig, onStart: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val order = listOf("walk", "run", "cycle", "hike")
    val sports = config.sports.values.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
    var selected by remember { mutableStateOf(sports.firstOrNull()?.id ?: "walk") }
    var cooper by remember { mutableStateOf(false) }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.workout_gps_pick_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.workout_gps_pick_subtitle), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f), fontSize = 14.sp, lineHeight = 19.sp)
        sports.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(selected = s.id == selected, role = Role.RadioButton) { selected = s.id }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(sportIcon(s.id), contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(s.displayTitle(LocalContext.current), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, modifier = Modifier.weight(1f))
                RadioButton(selected = s.id == selected, onClick = { selected = s.id })
            }
        }
        if (selected == "run" || selected == "walk") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.workout_gps_cooper), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.workout_gps_cooper_detail), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                Switch(checked = cooper, onCheckedChange = { cooper = it })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassTextButton(text = stringResource(R.string.workout_permission_not_now), onClick = onDismiss, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = stringResource(R.string.workout_gps_start), onClick = { onStart(selected, cooper && (selected == "run" || selected == "walk")) })
        }
    }
}

// -- Diary rows and banners --------------------------------------------------------------------------

@Composable
internal fun GpsLiveBanner(state: OutdoorLiveState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase) {
        while (state.phase == OutdoorPhase.RECORDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    GlassSurface(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen), cornerRadius = 22.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Calorie), contentAlignment = Alignment.Center) {
                Icon(sportIcon(state.sport), contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.workout_gps_in_progress, state.sportTitle), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    "${WorkoutFormat.duration(state.activeNow(now))} · ${WorkoutFormat.distanceKm(state.distanceM)}" +
                        if (state.phase == OutdoorPhase.PAUSED) " · ${stringResource(R.string.workout_live_paused)}" else "",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), fontSize = 13.sp
                )
            }
            Text(stringResource(R.string.workout_gps_open), color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun GpsSessionRow(session: WorkoutSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val gps = session.gps ?: return
    val time = remember(session.startedAt) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(session.startedAt.atZone(ZoneId.systemDefault()))
    }
    GlassSurface(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen), cornerRadius = 22.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(workoutsColors().panel), contentAlignment = Alignment.Center) {
                Icon(sportIcon(gps.sport), contentDescription = null, tint = AppColors.Calorie)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(session.exercises.firstOrNull()?.name ?: gps.sport, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    listOfNotNull(
                        time,
                        WorkoutFormat.distanceKm(gps.distanceM),
                        WorkoutFormat.seconds(gps.movingSeconds),
                        if (WorkoutFormat.usesSpeed(gps.sport)) WorkoutFormat.speedKmh(gps.avgSpeedMps) else WorkoutFormat.pace(gps.avgPaceSecondsPerKm),
                        gps.activeKcal?.let { "$it kcal" }
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), fontSize = 13.sp
                )
            }
        }
    }
}

/** Offered when a recording stopped unexpectedly (process death): resume, save as is, or discard. */
@Composable
internal fun UnfinishedWorkoutDialog(sportTitle: String, onResume: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.workout_gps_unfinished_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.workout_gps_unfinished_text, sportTitle), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 15.sp, lineHeight = 21.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassTextButton(text = stringResource(R.string.workout_gps_discard), onClick = onDiscard, color = Color(0xFFE5484D))
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = stringResource(R.string.workout_gps_save), onClick = onSave)
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = stringResource(R.string.workout_gps_resume), onClick = onResume)
        }
    }
}

// -- Live screen -------------------------------------------------------------------------------------

/** Full-screen live workout: time, distance, pace or speed, heart rate, elevation, current split and controls. */
@Composable
internal fun OutdoorLiveScreen(state: OutdoorLiveState, onMinimize: () -> Unit) {
    val context = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    fun send(action: String) = OutdoorWorkoutService.send(context, action)
    Dialog(onDismissRequest = onMinimize, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = workoutsColors()
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sportIcon(state.sport), contentDescription = null, tint = AppColors.Calorie)
                Spacer(Modifier.width(8.dp))
                Text(state.sportTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.charcoal, modifier = Modifier.weight(1f))
                IconButton(onClick = onMinimize) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.workout_live_minimize)) }
            }
            val status = when (state.phase) {
                OutdoorPhase.PAUSED -> stringResource(R.string.workout_live_paused)
                OutdoorPhase.SAVING -> stringResource(R.string.workout_live_saving)
                OutdoorPhase.RECOVERY -> stringResource(R.string.workout_live_recovery_text)
                else -> if (!state.hasFix) stringResource(R.string.workout_live_waiting_gps)
                else state.accuracyM?.let { stringResource(R.string.workout_live_gps_accuracy, it.toInt().toString()) } ?: ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GpsFixed, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.mutedText)
                Spacer(Modifier.width(6.dp))
                Text(status, color = colors.mutedText, fontSize = 13.sp)
            }
            Text(
                WorkoutFormat.duration(state.activeNow(now)),
                fontSize = 64.sp, fontWeight = FontWeight.Black, color = colors.charcoal, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveTile(stringResource(R.string.workout_live_distance), WorkoutFormat.distanceKm(state.distanceM), Modifier.weight(1f))
                if (WorkoutFormat.usesSpeed(state.sport)) {
                    LiveTile(stringResource(R.string.workout_live_speed), WorkoutFormat.speedKmh(state.currentSpeedMps ?: state.avgSpeedMps), Modifier.weight(1f))
                } else {
                    LiveTile(stringResource(R.string.workout_live_pace), WorkoutFormat.paceFromSpeed(state.currentSpeedMps), Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveTile(stringResource(R.string.workout_live_heart_rate), WorkoutFormat.bpm(state.heartRate), Modifier.weight(1f))
                LiveTile(stringResource(R.string.workout_live_elevation), "+" + WorkoutFormat.metres(state.elevationGainM), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveTile(
                    stringResource(R.string.workout_live_split) + " ${state.splitKm}",
                    "${WorkoutFormat.metres(state.splitDistanceM)} · ${WorkoutFormat.seconds(state.splitSeconds)}",
                    Modifier.weight(1f)
                )
                LiveTile(
                    stringResource(R.string.workout_live_avg_pace),
                    if (WorkoutFormat.usesSpeed(state.sport)) WorkoutFormat.speedKmh(state.avgSpeedMps) else WorkoutFormat.pace(state.avgPaceSPerKm),
                    Modifier.weight(1f)
                )
            }
            if (state.laps > 0) Text("${stringResource(R.string.workout_live_laps)}: ${state.laps}", color = colors.mutedText)
            if (state.cooper) Text(stringResource(R.string.workout_gps_cooper), color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            when (state.phase) {
                OutdoorPhase.RECORDING, OutdoorPhase.PAUSED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (state.phase == OutdoorPhase.RECORDING) {
                        ControlButton(Icons.Filled.Pause, stringResource(R.string.workout_action_pause)) { send(OutdoorWorkoutService.ACTION_PAUSE) }
                        ControlButton(Icons.Filled.Flag, stringResource(R.string.workout_action_lap)) { send(OutdoorWorkoutService.ACTION_LAP) }
                    } else {
                        ControlButton(Icons.Filled.PlayArrow, stringResource(R.string.workout_action_resume)) { send(OutdoorWorkoutService.ACTION_RESUME) }
                    }
                    ControlButton(Icons.Filled.Stop, stringResource(R.string.workout_action_end), tint = Color(0xFFE5484D)) { confirmEnd = true }
                }
                OutdoorPhase.RECOVERY -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val left = ((state.recoveryEndsMs ?: now) - now).coerceAtLeast(0)
                    Text(stringResource(R.string.workout_live_recovery_title) + " · " + WorkoutFormat.duration(left), fontWeight = FontWeight.SemiBold, color = colors.charcoal)
                    Spacer(Modifier.height(8.dp))
                    GlassTextButton(text = stringResource(R.string.workout_action_skip), onClick = { send(OutdoorWorkoutService.ACTION_SKIP_RECOVERY) })
                }
                OutdoorPhase.SAVING -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppColors.Calorie) }
                OutdoorPhase.DONE -> Unit
            }
            if (state.phase == OutdoorPhase.PAUSED) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GlassTextButton(text = stringResource(R.string.workout_gps_discard), onClick = { confirmDiscard = true }, color = Color(0xFFE5484D))
                }
            }
        }
    }
    if (confirmEnd) {
        GlassDialog(onDismissRequest = { confirmEnd = false }) {
            Text(stringResource(R.string.workout_live_end_confirm_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.workout_live_end_confirm_text), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 15.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassTextButton(text = stringResource(R.string.workout_permission_not_now), onClick = { confirmEnd = false }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.width(8.dp))
                GlassTextButton(text = stringResource(R.string.workout_action_end), onClick = {
                    confirmEnd = false
                    send(OutdoorWorkoutService.ACTION_END)
                })
            }
        }
    }
    if (confirmDiscard) {
        GlassDialog(onDismissRequest = { confirmDiscard = false }) {
            Text(stringResource(R.string.workout_live_discard_confirm), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassTextButton(text = stringResource(R.string.workout_permission_not_now), onClick = { confirmDiscard = false }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.width(8.dp))
                GlassTextButton(text = stringResource(R.string.workout_gps_discard), color = Color(0xFFE5484D), onClick = {
                    confirmDiscard = false
                    send(OutdoorWorkoutService.ACTION_DISCARD)
                    onMinimize()
                })
            }
        }
    }
}

@Composable
private fun LiveTile(label: String, value: String, modifier: Modifier = Modifier) {
    GlassSurface(modifier = modifier, cornerRadius = 20.dp, padding = 12.dp) {
        Column {
            Text(label, color = workoutsColors().mutedText, fontSize = 12.sp)
            Text(value, color = workoutsColors().charcoal, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, tint: Color = AppColors.Calorie, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(tint).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = workoutsColors().charcoal)
    }
}

// -- Strength sessions -------------------------------------------------------------------------------

/** Start session / running timer + Finish / the saved interval (docs/workouts-gps.md §1). */
@Composable
internal fun StrengthSessionBar(
    state: WorkoutDiaryUiState,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = state.activeSession
    val zone = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    GlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = AppColors.Calorie)
            Spacer(Modifier.width(10.dp))
            when {
                active != null -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(active) {
                        while (true) {
                            now = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.workout_session_running, WorkoutFormat.duration(now - active.startedAt.toEpochMilli())),
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.workout_session_cancel),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), fontSize = 12.sp,
                            modifier = Modifier.clickable(role = Role.Button, onClick = onCancel)
                        )
                    }
                    GlassTextButton(text = stringResource(R.string.workout_session_finish), onClick = onFinish, enabled = !state.isCalculatingBurn)
                }
                state.sessionInterval != null -> {
                    val (s, e) = state.sessionInterval
                    Text(
                        stringResource(
                            R.string.ui_gps_session_duration,
                            stringResource(R.string.workout_session_times, fmt.format(s.atZone(zone)), fmt.format(e.atZone(zone))),
                            java.time.Duration.between(s, e).toMinutes().toInt()
                        ),
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    Text(stringResource(R.string.workout_strength_live_title), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    GlassTextButton(text = stringResource(R.string.workout_session_start), onClick = onStart)
                }
            }
        }
    }
}

/**
 * Confirms (or edits) a heart-rate window before saving the burn with a real interval. Start and end move in
 * one- and five-minute steps; "No times" keeps the legacy daily snapshot.
 */
@Composable
internal fun WorkoutWindowDialog(
    windows: List<HrWindow>,
    onConfirm: (Long, Long, Boolean) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    fun hm(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    val initial = windows.indices.maxByOrNull { windows[it].minutes } ?: 0
    var selected by remember { mutableStateOf(initial) }
    var start by remember { mutableLongStateOf(windows[initial].startMs) }
    var end by remember { mutableLongStateOf(windows[initial].endMs) }
    var edited by remember { mutableStateOf(false) }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.workout_window_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.workout_window_text), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 14.sp, lineHeight = 19.sp)
        windows.forEachIndexed { i, w ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = i == selected, role = Role.RadioButton) {
                    selected = i; start = w.startMs; end = w.endMs; edited = false
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = i == selected, onClick = { selected = i; start = w.startMs; end = w.endMs; edited = false })
                Text(
                    stringResource(R.string.workout_window_option, hm(w.startMs), hm(w.endMs), w.avgHr.toInt().toString()),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp
                )
            }
        }
        HorizontalDivider(color = workoutsColors().hairline)
        TimeStepper(stringResource(R.string.workout_window_start), hm(start)) { deltaMin ->
            val next = start + deltaMin * 60_000L
            if (next < end - 60_000L) { start = next; edited = true }
        }
        TimeStepper(stringResource(R.string.workout_window_end), hm(end)) { deltaMin ->
            val next = end + deltaMin * 60_000L
            if (next > start + 60_000L && next <= System.currentTimeMillis() + 60_000L) { end = next; edited = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassTextButton(text = stringResource(R.string.workout_window_skip), onClick = onSkip, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = stringResource(R.string.workout_window_use), onClick = { onConfirm(start, end, edited) })
        }
    }
}

@Composable
private fun TimeStepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(56.dp))
        IconButton(onClick = { onStep(-5) }) { Text("−5", color = AppColors.Calorie, fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = { onStep(-1) }) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.ui_minus_one_min), tint = AppColors.Calorie) }
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        IconButton(onClick = { onStep(1) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ui_plus_one_min), tint = AppColors.Calorie) }
        IconButton(onClick = { onStep(5) }) { Text("+5", color = AppColors.Calorie, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
internal fun rememberLiveWorkout() = OutdoorWorkoutService.live.collectAsState()

@Composable
internal fun rememberLaunch(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { block() } }
}
