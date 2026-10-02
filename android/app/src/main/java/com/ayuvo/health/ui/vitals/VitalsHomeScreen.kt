package com.ayuvo.health.ui.vitals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.vitals.camera.VitalsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Camera measurements home (docs/camera-vitals.md §7.1 "Vitals home"): start buttons, the latest scan per mode, the
 * HR trend per mode, baselines (HR and RMSSD medians over 7/14/30 days and all time), the history by day and the
 * disclaimer. Validation, calibration and compare arrive with Wave 3.
 */
@Composable
fun VitalsHomeScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onStartScan: (VitalsMode) -> Unit,
    onOpenScan: (String) -> Unit,
    onStartCompare: () -> Unit = {},
    onOpenValidation: () -> Unit = {},
    onOpenCalibration: () -> Unit = {}
) {
    val vm: VitalsHomeViewModel = viewModel(factory = VitalsHomeViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val cfg = vm.cfg
    val zone = remember { ZoneId.systemDefault() }
    val timeFmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()) }
    val dateTimeFmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()) }
    val dayFmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()) }
    val selected = remember { mutableStateMapOf<VitalsMode, Int?>() }
    val bpm = stringResource(R.string.camvitals_unit_bpm)
    val dash = stringResource(R.string.camvitals_dash)

    fun hrText(hr: Double?): String = hr?.let { String.format(Locale.getDefault(), "%.0f %s", it, bpm) } ?: dash

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.camvitals_title), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("vitals.home"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item(key = "start") {
                InsetGroup(header = stringResource(R.string.camvitals_measure)) {
                    row {
                        GroupRow(
                            title = stringResource(R.string.camvitals_finger_scan),
                            subtitle = stringResource(R.string.camvitals_finger_sub),
                            icon = Icons.Filled.Fingerprint, iconTint = vitalsTint(),
                            modifier = Modifier.testTag("vitals.home.finger"),
                            onClick = { onStartScan(VitalsMode.FINGER) }
                        )
                    }
                    row {
                        GroupRow(
                            title = stringResource(R.string.camvitals_face_scan),
                            subtitle = stringResource(R.string.camvitals_face_sub),
                            icon = Icons.Filled.Face, iconTint = vitalsTint(),
                            modifier = Modifier.testTag("vitals.home.face"),
                            onClick = { onStartScan(VitalsMode.FACE) }
                        )
                    }
                    row {
                        GroupRow(
                            title = stringResource(R.string.camvitals_compare),
                            subtitle = stringResource(R.string.camvitals_compare_sub),
                            icon = Icons.Filled.CompareArrows, iconTint = vitalsTint(),
                            modifier = Modifier.testTag("vitals.home.compare"),
                            onClick = onStartCompare
                        )
                    }
                }
            }
            if (!ui.loading && ui.history.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.MonitorHeart,
                        title = stringResource(R.string.camvitals_no_scans_title),
                        message = stringResource(R.string.camvitals_no_scans_body)
                    )
                }
            }
            if (ui.latest.isNotEmpty()) {
                item(key = "latest") {
                    InsetGroup(header = stringResource(R.string.camvitals_latest), modifier = Modifier.testTag("vitals.home.latest")) {
                        VitalsMode.entries.forEach { mode ->
                            val r = ui.latest[mode] ?: return@forEach
                            row {
                                GroupRow(
                                    title = VitalsText.modeName(context, mode),
                                    subtitle = stringResource(
                                        R.string.camvitals_row_subtitle,
                                        dateTimeFmt.format(Instant.ofEpochMilli(r.startMs).atZone(zone)),
                                        VitalsText.grade(context, cfg, r.grade)
                                    ),
                                    value = hrText(r.hr),
                                    icon = modeIcon(mode), iconTint = vitalsTint(),
                                    onClick = { onOpenScan(r.id) }
                                )
                            }
                        }
                    }
                }
            }
            ui.trend.forEach { (mode, points) ->
                item(key = "trend-${mode.id}") {
                    Column {
                        Text(
                            (stringResource(R.string.camvitals_hr_trend) + " · " + VitalsText.modeName(context, mode)).uppercase(),
                            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                            fontSize = 12.sp,
                            color = AyuvoColors.secondaryLabel()
                        )
                        SurfaceCard(modifier = Modifier.testTag("vitals.home.trend.${mode.id}")) {
                            val labels = remember(points) {
                                val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                                listOf(0, points.size / 2, points.size - 1).distinct().map { i ->
                                    i to fmt.format(Instant.ofEpochMilli(points[i].bucketStartMs).atZone(zone))
                                }
                            }
                            HealthBucketChart(
                                points = points,
                                style = HealthChartStyle.LINE,
                                color = vitalsTint(),
                                xLabels = labels,
                                formatValue = { v -> String.format(Locale.getDefault(), "%.0f", v) },
                                selected = selected[mode],
                                onSelect = { selected[mode] = it },
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.camvitals_hr_trend_footer),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            fontSize = 12.sp,
                            color = AyuvoColors.secondaryLabel()
                        )
                    }
                }
            }
            if (ui.baselines.isNotEmpty()) {
                item(key = "baselines") {
                    Column {
                        Text(
                            stringResource(R.string.camvitals_baselines).uppercase(),
                            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                            fontSize = 12.sp,
                            color = AyuvoColors.secondaryLabel()
                        )
                        SurfaceCard(modifier = Modifier.testTag("vitals.home.baselines")) {
                            BaselineTable(ui.baselines)
                        }
                        Text(
                            stringResource(R.string.camvitals_baselines_footer),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            fontSize = 12.sp,
                            color = AyuvoColors.secondaryLabel()
                        )
                    }
                }
            }
            if (ui.history.isNotEmpty()) {
                item(key = "history-title") {
                    Text(
                        stringResource(R.string.camvitals_history),
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                ui.history.forEach { (day, rows) ->
                    item(key = "day-$day") {
                        InsetGroup(header = dayLabel(day, dayFmt), modifier = Modifier.testTag("vitals.home.day.$day")) {
                            rows.forEach { r ->
                                row {
                                    GroupRow(
                                        title = timeFmt.format(Instant.ofEpochMilli(r.startMs).atZone(zone)),
                                        subtitle = stringResource(
                                            R.string.camvitals_row_subtitle,
                                            VitalsText.modeName(context, r.mode),
                                            VitalsText.grade(context, cfg, r.grade)
                                        ),
                                        value = hrText(r.hr),
                                        icon = modeIcon(r.mode), iconTint = vitalsTint(),
                                        modifier = Modifier.testTag("vitals.history.row"),
                                        onClick = { onOpenScan(r.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "tools") {
                InsetGroup(header = stringResource(R.string.camvitals_tools), modifier = Modifier.testTag("vitals.home.tools")) {
                    row {
                        GroupRow(
                            title = stringResource(R.string.camvitals_validation_title),
                            subtitle = stringResource(R.string.camvitals_validation_sub),
                            icon = Icons.Filled.Insights, iconTint = vitalsTint(),
                            modifier = Modifier.testTag("vitals.home.validation"),
                            onClick = onOpenValidation
                        )
                    }
                    // §7.1: Calibration appears only when the experimental or research toggle is on.
                    if (ui.calibrationVisible) {
                        row {
                            GroupRow(
                                title = stringResource(R.string.camvitals_calibration_title),
                                subtitle = stringResource(R.string.camvitals_calibration_sub),
                                icon = Icons.Filled.Tune, iconTint = vitalsTint(),
                                modifier = Modifier.testTag("vitals.home.calibration"),
                                onClick = onOpenCalibration
                            )
                        }
                    }
                }
            }
            item(key = "disclaimer") {
                Text(
                    VitalsText.disclaimer(context, cfg),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Start,
                    color = AyuvoColors.secondaryLabel()
                )
            }
        }
    }
}

internal fun modeIcon(mode: VitalsMode) = if (mode == VitalsMode.FACE) Icons.Filled.Face else Icons.Filled.Fingerprint

private fun dayLabel(day: LocalDate, fmt: DateTimeFormatter): String = fmt.format(day)

@Composable
private fun BaselineTable(baselines: List<VitalBaselineUi>) {
    val context = LocalContext.current
    val windows = listOf(
        "7d" to R.string.camvitals_baseline_7d, "14d" to R.string.camvitals_baseline_14d,
        "30d" to R.string.camvitals_baseline_30d, "all" to R.string.camvitals_baseline_all
    )
    val dash = stringResource(R.string.camvitals_dash)
    val hrLabel = stringResource(R.string.camvitals_baseline_hr) + " (" + stringResource(R.string.camvitals_unit_bpm) + ")"
    val rmssdLabel = stringResource(R.string.camvitals_baseline_rmssd) + " (" + stringResource(R.string.camvitals_unit_ms) + ")"
    fun cell(v: Double?): String = v?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: dash
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Spacer(Modifier.weight(1.6f))
            windows.forEach { (_, res) ->
                Text(stringResource(res), fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
        baselines.forEach { b ->
            Text(VitalsText.modeName(context, b.mode), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            listOf(
                hrLabel to b.hr,
                rmssdLabel to b.rmssd
            ).forEach { (label, values) ->
                Row {
                    Text(label, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
                    windows.forEach { (key, _) ->
                        Text(cell(values[key]), fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}
