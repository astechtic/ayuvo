package com.ayuvo.health.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.workout.GpsWorkoutAnalysis
import com.ayuvo.health.data.workout.RecordedTrack
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.services.workout.GpsWorkoutFinalizer
import com.ayuvo.health.services.workout.OutdoorPhase
import com.ayuvo.health.services.workout.OutdoorWorkoutService
import com.ayuvo.health.services.workout.WorkoutFormat
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Heart-rate zone colours (below light → near max); null zone draws the route in the accent colour. */
private val ZONE_COLORS = listOf(Color(0xFF8E9BAE), Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444))

/**
 * Summary of a recorded GPS workout: an osmdroid map of the route (coloured by heart-rate zone when heart rate
 * exists, OpenStreetMap attribution always visible), per-km splits, laps, elevation, heart rate, recovery,
 * energy and cardio fitness. Opens from the live screen after End and from the diary's workout rows.
 */
@Composable
internal fun GpsWorkoutSummaryScreen(container: AppContainer, sessionId: UUID, onClose: () -> Unit) {
    val sessions by container.workoutRepository.gpsSessions.collectAsState(initial = null)
    val session = sessions?.firstOrNull { it.id == sessionId }
    val live by OutdoorWorkoutService.live.collectAsState()
    var track by remember { mutableStateOf<RecordedTrack?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val finalizer = remember { GpsWorkoutFinalizer(container) }

    LaunchedEffect(sessionId) {
        track = withContext(Dispatchers.IO) { container.gpsTrackStore.load(sessionId.toString()) }
        loaded = true
    }
    // Heart rate that synced after the workout (a watch) or a recovery measurement the recorder did not finish.
    LaunchedEffect(session?.id, live?.phase) {
        val s = session ?: return@LaunchedEffect
        val recording = live?.savedSessionId == s.id.toString() && live?.phase == OutdoorPhase.RECOVERY
        val due = System.currentTimeMillis() >= s.completedAt.toEpochMilli() + GpsWorkoutFinalizer.RECOVERY_WINDOW_MS
        if (!recording && due && GpsWorkoutFinalizer.needsHeartRateRefresh(s)) {
            refreshing = true
            withContext(Dispatchers.IO) { runCatching { finalizer.refreshHeartRate(s.id) } }
            refreshing = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = workoutsColors()
        Column(Modifier.fillMaxSize().background(colors.background)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session?.exercises?.firstOrNull()?.name ?: stringResource(R.string.workout_gps_pick_title),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.charcoal,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = null) }
            }
            if (session == null || session.gps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sessions == null) CircularProgressIndicator(color = AppColors.Calorie)
                    else Text(stringResource(R.string.workout_summary_no_route), color = colors.mutedText)
                }
                return@Column
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RouteMap(container, session, track, loaded)
                SummaryContent(session)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (refreshing) CircularProgressIndicator(color = AppColors.Calorie, strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                    else GlassTextButton(text = stringResource(R.string.workout_summary_refresh_hr), onClick = {
                        scope.launch {
                            refreshing = true
                            withContext(Dispatchers.IO) { runCatching { finalizer.refreshHeartRate(session.id) } }
                            refreshing = false
                        }
                    })
                    GlassTextButton(text = stringResource(R.string.workout_summary_delete), onClick = { confirmDelete = true }, color = Color(0xFFE5484D))
                }
                Text(stringResource(R.string.workout_summary_disclaimer), color = colors.mutedText, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDelete) {
        GlassDialog(onDismissRequest = { confirmDelete = false }) {
            Text(stringResource(R.string.workout_summary_delete_confirm), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassTextButton(text = stringResource(R.string.workout_permission_not_now), onClick = { confirmDelete = false }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.width(8.dp))
                GlassTextButton(text = stringResource(R.string.workout_summary_delete), color = Color(0xFFE5484D), onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.workoutRepository.deleteSession(sessionId)
                        onClose()
                    }
                })
            }
        }
    }
}

@Composable
private fun RouteMap(container: AppContainer, session: WorkoutSession, track: RecordedTrack?, loaded: Boolean) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    if (!loaded) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(shape).background(workoutsColors().panel))
        return
    }
    val cfg = container.workoutConfig
    val runs = remember(track, session.heartRate) {
        val t = track ?: return@remember emptyList<Pair<Int?, List<GeoPoint>>>()
        val end = t.endMs ?: session.completedAt.toEpochMilli()
        val kept = GpsWorkoutAnalysis.keptPoints(t.trackInput(end), cfg)
        val hr = t.hrSamples()
        val hrMax = session.heartRate?.hrMax
        // Consecutive fixes in the same heart-rate zone share one polyline (segments overlap by one point).
        val out = ArrayList<Pair<Int?, MutableList<GeoPoint>>>()
        for (p in kept) {
            val zone = if (hrMax != null) GpsWorkoutAnalysis.zoneAt(p.tMs, hr, hrMax, session.heartRate?.restingHr, cfg) else null
            val geo = GeoPoint(p.lat, p.lon)
            val last = out.lastOrNull()
            if (last != null && last.first == zone) last.second += geo
            else {
                val start = last?.second?.lastOrNull()
                out += zone to (if (start != null) mutableListOf(start, geo) else mutableListOf(geo))
            }
        }
        out.map { it.first to it.second.toList() }
    }
    Column {
        if (runs.isEmpty() || runs.sumOf { it.second.size } < 2) {
            Box(Modifier.fillMaxWidth().height(120.dp).clip(shape).background(workoutsColors().panel), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workout_summary_no_route), color = workoutsColors().mutedText)
            }
            return@Column
        }
        val accent = AppColors.Calorie.toArgb()
        val mapView = remember {
            // osmdroid: identify the app to the OpenStreetMap tile servers and cache tiles in app storage.
            Configuration.getInstance().apply {
                userAgentValue = context.packageName
                val base = File(context.cacheDir, "osmdroid")
                osmdroidBasePath = base
                osmdroidTileCache = File(base, "tiles")
            }
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                overlays.add(CopyrightOverlay(context))
            }
        }
        DisposableEffect(mapView) {
            mapView.onResume()
            onDispose {
                mapView.onPause()
                mapView.onDetach()
            }
        }
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().height(280.dp).clip(shape),
            update = { map ->
                map.overlays.removeAll { it is Polyline }
                val all = ArrayList<GeoPoint>()
                for ((zone, pts) in runs) {
                    if (pts.size < 2) continue
                    all += pts
                    map.overlays.add(Polyline(map).apply {
                        setPoints(pts)
                        outlinePaint.color = zone?.let { ZONE_COLORS[it.coerceIn(0, 4)].toArgb() } ?: accent
                        outlinePaint.strokeWidth = 12f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    })
                }
                if (all.size >= 2) {
                    val box = BoundingBox.fromGeoPointsSafe(all).increaseByScale(1.25f)
                    map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false, 48) }
                    if (map.width > 0) map.zoomToBoundingBox(box, false, 48)
                }
                map.invalidate()
            }
        )
        Text(
            stringResource(R.string.workout_summary_osm_attribution),
            fontSize = 11.sp, color = workoutsColors().mutedText,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )
    }
}

@Composable
private fun SummaryContent(session: WorkoutSession) {
    val gps = session.gps ?: return
    val hr = session.heartRate
    val colors = workoutsColors()
    val speedSport = WorkoutFormat.usesSpeed(gps.sport)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(stringResource(R.string.workout_live_distance), WorkoutFormat.distanceKm(gps.distanceM), Modifier.weight(1f))
        Stat(stringResource(R.string.workout_summary_moving), WorkoutFormat.seconds(gps.movingSeconds), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(
            if (speedSport) stringResource(R.string.workout_live_speed) else stringResource(R.string.workout_live_avg_pace),
            if (speedSport) WorkoutFormat.speedKmh(gps.avgSpeedMps) else WorkoutFormat.pace(gps.avgPaceSecondsPerKm),
            Modifier.weight(1f)
        )
        Stat(stringResource(R.string.workout_summary_elapsed), WorkoutFormat.seconds(gps.elapsedSeconds), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(
            stringResource(R.string.workout_summary_elevation),
            stringResource(R.string.workout_summary_gain_loss, WorkoutFormat.metres(gps.elevationGainM), WorkoutFormat.metres(gps.elevationLossM)),
            Modifier.weight(1f)
        )
        Stat(
            stringResource(R.string.workout_summary_energy),
            gps.activeKcal?.let { "$it kcal" } ?: "--",
            Modifier.weight(1f),
            footnote = when (gps.kcalMethod) {
                "keytel" -> stringResource(R.string.workout_summary_energy_keytel)
                "met" -> stringResource(R.string.workout_summary_energy_met)
                else -> null
            }
        )
    }
    if (hr != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(stringResource(R.string.workout_summary_avg_hr), WorkoutFormat.bpm(hr.avgHr), Modifier.weight(1f))
            Stat(stringResource(R.string.workout_summary_max_hr), WorkoutFormat.bpm(hr.maxHr), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(stringResource(R.string.workout_summary_trimp), hr.trimp?.let { String.format(java.util.Locale.getDefault(), "%.0f", it) } ?: "--", Modifier.weight(1f))
            Stat(
                stringResource(R.string.workout_summary_hrr1), hr.hrr1?.let { "${it.roundToInt()} bpm" } ?: "--", Modifier.weight(1f),
                footnote = if (hr.hrr1FlagLow) stringResource(R.string.workout_summary_hrr1_low) else hr.hrr1Confidence
            )
        }
        if (hr.zoneSeconds.size == 5 && hr.zoneSeconds.sum() > 0) ZoneBars(hr.zoneSeconds)
    }
    val vo2 = gps.vo2max
    val cooper = gps.cooperVo2max
    if (gps.sport == "walk" || gps.sport == "run" || cooper != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(
                stringResource(R.string.workout_summary_vo2max), vo2?.let { "$it mL/kg/min" } ?: "--", Modifier.weight(1f),
                footnote = if (vo2 == null) stringResource(R.string.workout_summary_no_vo2max) else null
            )
            if (gps.cooperTest) Stat(stringResource(R.string.workout_summary_cooper), cooper?.let { "$it mL/kg/min" } ?: "--", Modifier.weight(1f))
        }
    }
    if (gps.splits.isNotEmpty()) {
        Section(stringResource(R.string.workout_summary_splits)) {
            gps.splits.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(stringResource(R.string.ui_gps_split_km, s.km), color = colors.mutedText, modifier = Modifier.width(64.dp))
                    Text(WorkoutFormat.pace(s.seconds), color = colors.charcoal, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (gps.laps.isNotEmpty()) {
        Section(stringResource(R.string.workout_summary_laps)) {
            gps.laps.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${l.index}", color = colors.mutedText, modifier = Modifier.width(32.dp))
                    Text(WorkoutFormat.distanceKm(l.distanceM), color = colors.charcoal, modifier = Modifier.weight(1f))
                    Text(WorkoutFormat.duration(l.endMs - l.startMs), color = colors.charcoal, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ZoneBars(seconds: List<Double>) {
    val names = stringResource(R.string.workout_summary_zone_names).split('|')
    val total = seconds.sum().coerceAtLeast(1.0)
    Section(stringResource(R.string.workout_summary_zones)) {
        seconds.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(names.getOrElse(i) { "Z$i" }, color = workoutsColors().mutedText, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(workoutsColors().panel)) {
                    Box(Modifier.fillMaxWidth((s / total).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(ZONE_COLORS[i]))
                }
                Text(WorkoutFormat.seconds(s), fontSize = 13.sp, color = workoutsColors().charcoal, modifier = Modifier.padding(start = 8.dp).width(56.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 14.dp) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = workoutsColors().charcoal)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = workoutsColors().hairline)
            content()
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, footnote: String? = null) {
    GlassSurface(modifier = modifier, cornerRadius = 20.dp, padding = 12.dp) {
        Column {
            Text(label, color = workoutsColors().mutedText, fontSize = 12.sp)
            Text(value, color = workoutsColors().charcoal, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            footnote?.let { Text(it, color = workoutsColors().mutedText, fontSize = 11.sp, lineHeight = 14.sp) }
        }
    }
}
