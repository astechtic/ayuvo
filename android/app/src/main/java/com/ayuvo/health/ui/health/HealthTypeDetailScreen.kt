package com.ayuvo.health.ui.health

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.charts.IosStyleSegmentedControl
import com.ayuvo.health.ui.charts.PeriodBandChart
import com.ayuvo.health.ui.charts.SleepStackedBars
import com.ayuvo.health.ui.charts.SleepStageStrip
import com.ayuvo.health.ui.charts.StatBadgeRow
import com.ayuvo.health.ui.charts.spreadLabels
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.UnitToggle
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One data type: D/W/M/6M/Y chart with anchor navigation, highlights, Show All Data (keyset
 * pages of 100), Data Sources & Access, and Options (units, Show on Home, Export CSV).
 */
@Composable
fun HealthTypeDetailScreen(container: AppContainer, typeKey: String, onBack: () -> Unit) {
    val vm: HealthTypeDetailViewModel = viewModel(key = "health-detail-$typeKey", factory = HealthTypeDetailViewModel.Factory(container, typeKey))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = HealthCategoryStyle.typeName(context, typeKey, ui.displayNameHint)
    val tint = HealthCategoryStyle.tintFor(typeKey)
    var selectedRecord by remember { mutableStateOf<HealthRecordUi?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val dateFmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()) }
    val exportTitle = stringResource(R.string.health_detail_export_csv)
    val manageUnavailable = stringResource(R.string.health_hub_unavailable_title)

    fun openManageAccess() {
        if (!vm.openManageAccess(context)) Toast.makeText(context, manageUnavailable, Toast.LENGTH_SHORT).show()
    }

    fun shareCsv() {
        scope.launch {
            runCatching {
                val csv = vm.buildCsv()
                val dir = File(context.cacheDir, "capture").apply { mkdirs() }
                val file = File(dir, "Ayuvo-Health-$typeKey-${LocalDate.now()}.csv")
                file.writeText(csv)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, exportTitle))
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.health_back_hub), color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Text(name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(R.plurals.health_records_count, ui.count.toInt(), ui.count.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                IosStyleSegmentedControl(
                    options = HealthChartRange.entries,
                    selected = ui.range,
                    label = { stringResource(it.labelRes) },
                    onSelect = vm::setRange
                )
            }
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.shiftAnchor(-1) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.health_detail_previous), tint = AppColors.Calorie)
                            }
                            Text(
                                rangeLabel(ui.range, ui.window, dateFmt),
                                modifier = Modifier.weight(1f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            IconButton(onClick = { vm.shiftAnchor(1) }, enabled = ui.canGoForward, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.health_detail_next), tint = if (ui.canGoForward) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            }
                        }
                        if (ui.highlights.isNotEmpty()) {
                            StatBadgeRow(ui.highlights.map { (res, value) -> stringResource(res) to value })
                        }
                        if (!ui.loading && !ui.hasChartData) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.health_detail_no_data_range), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                        } else {
                            DetailChart(ui, tint, name, zone)
                        }
                        ui.historyLimitedBeforeMs?.let { floor ->
                            Text(
                                stringResource(R.string.health_hub_subtitle_limited, formatDate(floor)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
            // Show All Data — 5 newest records up front, 5 more per "Load more" (keyset paged).
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
                    Column {
                        Text(stringResource(R.string.health_detail_show_all_data), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
                        ui.allData.forEachIndexed { index, record ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            HealthRecordRow(record = record, onClick = { selectedRecord = record })
                        }
                        if (ui.allData.isEmpty() && !ui.loading) {
                            Text(stringResource(R.string.health_hub_no_data_title), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        if (!ui.allDataEnd) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                                GlassTextButton(text = stringResource(R.string.health_load_more), onClick = vm::loadMore, enabled = !ui.loadingMore)
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            // Data Sources & Access — grouped by origin package.
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
                    Column {
                        Text(stringResource(R.string.health_detail_sources), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
                        Text(
                            stringResource(R.string.health_detail_sources_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        ui.sources.forEach { source ->
                            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (source.packageName.isNotBlank() && source.packageName != source.label) {
                                        Text(source.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(pluralStringResource(R.plurals.health_records_count, source.count, source.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { openManageAccess() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_manage_health_access), color = AppColors.Calorie, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            // Options — unit, Show on Home, Export CSV.
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
                    Column {
                        Text(stringResource(R.string.health_detail_options), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
                        UnitOptionRow(ui, vm)
                        if (ui.type != null) {
                            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.health_detail_show_on_home), modifier = Modifier.weight(1f), fontSize = 15.sp)
                                Switch(checked = ui.showOnHome, onCheckedChange = vm::setShowOnHome)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { shareCsv() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.health_detail_export_csv), color = AppColors.Calorie, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item { HealthReadOnlyFooter() }
        }
    }

    selectedRecord?.let { record ->
        val row = record.row
        GlassDialog(onDismissRequest = { selectedRecord = null }) {
            Text(name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            RecordField(stringResource(R.string.health_record_value), record.valueText)
            RecordField(stringResource(R.string.health_record_time), record.timeRangeText)
            RecordField(
                stringResource(R.string.health_record_source),
                record.sourceLabel + if (row.sourceId.isNotBlank() && row.sourceId != context.packageName && row.sourceId != record.sourceLabel) " (${row.sourceId})" else ""
            )
            row.device?.let { RecordField(stringResource(R.string.health_record_device), it) }
            RecordField(stringResource(R.string.health_record_method), recordingMethodLabel(row.recordingMethod))
            if (row.origin == HealthSampleRow.ORIGIN_IMPORT) {
                Text(stringResource(R.string.health_record_origin_import), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            RecordField(stringResource(R.string.health_record_id), row.id)
            GlassDialogActions(primaryText = stringResource(R.string.action_done), onPrimary = { selectedRecord = null })
        }
    }
}

@Composable
private fun DetailChart(ui: HealthDetailUiState, tint: Color, name: String, zone: ZoneId) {
    val window = ui.window
    val days = remember(window) { generateSequence(window.start) { it.plusDays(1) }.takeWhile { !it.isAfter(window.endInclusive) }.toList() }
    val monthDay = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val month = remember { DateTimeFormatter.ofPattern("MMM", Locale.getDefault()) }
    val xLabels = when (ui.range) {
        HealthChartRange.DAY -> listOf("0", "6", "12", "18", "24")
        HealthChartRange.WEEK -> days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
        HealthChartRange.MONTH -> spreadLabels(days.map { monthDay.format(it) }, 5)
        HealthChartRange.SIX_MONTHS -> spreadLabels(ui.points.map { monthDay.format(Instant.ofEpochMilli(it.bucketStartMs).atZone(zone)) }, 6)
        HealthChartRange.YEAR -> spreadLabels(ui.points.map { month.format(Instant.ofEpochMilli(it.bucketStartMs).atZone(zone)) }, 6)
    }
    val fmt: (Double) -> String = { v -> HealthValueFormatter.format(ui.typeId, v, ui.unitPrefs, unitOverride = ui.descriptor.unit.takeIf { ui.type == null }).number }
    val tooltip: (Int) -> String = { i ->
        val p = ui.points[i]
        val at = Instant.ofEpochMilli(p.bucketStartMs).atZone(zone)
        when (ui.range) {
            HealthChartRange.DAY -> String.format(Locale.getDefault(), "%02d:00", at.hour)
            HealthChartRange.YEAR -> month.format(at)
            else -> monthDay.format(at)
        }
    }
    val summary = stringResource(R.string.health_detail_chart_summary, name, ui.points.count { !it.isEmpty }, ui.highlights.joinToString(", ") { it.second })
    when (ui.chartKind) {
        HealthChartKind.SLEEP -> {
            if (ui.range == HealthChartRange.DAY) {
                val night = ui.sleepNights.firstOrNull()
                if (night != null) {
                    // Stage segments are resolved by the view model from the night's source rows.
                    SleepStageStrip(night, ui.sleepStages)
                    Text(
                        "${stringResource(R.string.health_sleep_bedtime)} ${DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(night.startMs).atZone(zone))} · ${stringResource(R.string.health_sleep_wake)} ${DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(night.endMs).atZone(zone))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                val nightDays = if (ui.range == HealthChartRange.WEEK || ui.range == HealthChartRange.MONTH) days else days.filterIndexed { i, _ -> i % (days.size / 30).coerceAtLeast(1) == 0 }
                SleepStackedBars(nightDays, ui.sleepNights.associateBy { it.nightOf }, spreadLabels(nightDays.map { monthDay.format(it) }, 5))
            }
        }
        HealthChartKind.PERIOD_BAND -> PeriodBandChart(days, ui.periodDays, tint, spreadLabels(days.map { monthDay.format(it) }, 5))
        HealthChartKind.BAR -> HealthBucketChart(ui.points, HealthChartStyle.BAR, tint, xLabels, fmt, tooltip, summary = summary)
        HealthChartKind.LINE -> HealthBucketChart(ui.points, HealthChartStyle.LINE, tint, xLabels, fmt, tooltip, summary = summary)
        HealthChartKind.RANGE -> HealthBucketChart(ui.points, HealthChartStyle.RANGE, tint, xLabels, fmt, tooltip, summary = summary)
        HealthChartKind.BLOOD_PRESSURE -> HealthBucketChart(ui.points, HealthChartStyle.RANGE, tint, xLabels, fmt, tooltip, secondaryColor = Color(0xFF0A84FF), summary = summary)
        HealthChartKind.NONE -> Unit
    }
}

/** One pre-formatted record: value + time on the left, source label (ellipsized) and chevron on the right. */
@Composable
private fun HealthRecordRow(record: HealthRecordUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.valueText, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                record.timeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            record.sourceLabel,
            modifier = Modifier.widthIn(max = 120.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun UnitOptionRow(ui: HealthDetailUiState, vm: HealthTypeDetailViewModel) {
    val unit = ui.descriptor.unit
    val type = ui.type
    val isBodyLength = type?.category == com.ayuvo.health.models.HealthCategory.BODY && unit == "m"
    when {
        unit == "kg" -> UnitRow(stringResource(R.string.health_unit_kg), stringResource(R.string.health_unit_lb), ui.unitPrefs.metricMass) { vm.setMassUnit(it) }
        isBodyLength -> UnitRow(stringResource(R.string.health_unit_cm), stringResource(R.string.health_unit_in), ui.unitPrefs.metricLength) { vm.setLengthUnit(it) }
        unit == "mmol/L" -> UnitRow(stringResource(R.string.health_unit_mmol_l), stringResource(R.string.health_unit_mg_dl), !ui.unitPrefs.glucoseMgDl) { vm.setGlucoseUnit(!it) }
        else -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.health_detail_unit), modifier = Modifier.weight(1f), fontSize = 15.sp)
            Text(HealthValueFormatter.format(ui.typeId, 1.0, ui.unitPrefs, unitOverride = unit.takeIf { type == null }).unit.ifEmpty { unit }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun UnitRow(metricLabel: String, imperialLabel: String, metricSelected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.health_detail_unit), modifier = Modifier.weight(1f), fontSize = 15.sp)
        UnitToggle(leftLabel = metricLabel, rightLabel = imperialLabel, isLeft = metricSelected, onSelect = onChange)
    }
}

@Composable
private fun RecordField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun recordingMethodLabel(method: Int?): String = when (method) {
    1 -> stringResource(R.string.health_method_active)
    2 -> stringResource(R.string.health_method_auto)
    3 -> stringResource(R.string.health_method_manual)
    else -> stringResource(R.string.health_method_unknown)
}

private fun rangeLabel(range: HealthChartRange, window: ClosedRange<LocalDate>, fmt: DateTimeFormatter): String = when (range) {
    HealthChartRange.DAY -> fmt.format(window.endInclusive)
    HealthChartRange.YEAR -> "${DateTimeFormatter.ofPattern("MMM yyyy").format(window.start)} – ${DateTimeFormatter.ofPattern("MMM yyyy").format(window.endInclusive)}"
    else -> "${fmt.format(window.start)} – ${fmt.format(window.endInclusive)}"
}
