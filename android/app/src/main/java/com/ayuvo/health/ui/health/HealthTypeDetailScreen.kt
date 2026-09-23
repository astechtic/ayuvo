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
import com.ayuvo.health.ui.charts.ChartClock
import com.ayuvo.health.ui.charts.SleepDayHeader
import com.ayuvo.health.ui.charts.SleepHypnogram
import com.ayuvo.health.ui.charts.SleepRangeBars
import com.ayuvo.health.ui.charts.SleepStageList
import com.ayuvo.health.ui.charts.chartValueOf
import com.ayuvo.health.models.HealthAggregation
import com.ayuvo.health.ui.metrics.MetricNavigation
import com.ayuvo.health.ui.charts.StatBadgeRow
import com.ayuvo.health.ui.charts.spreadLabels
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.UnitToggle
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.metrics.MetricChartSupport
import com.ayuvo.health.ui.metrics.MetricDetailScaffold
import com.ayuvo.health.ui.metrics.MetricHeadlineUi
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One health data type on the shared metric detail layout (docs/ui-structure.md §6): D/W/M/6M/Y
 * chart with anchor navigation, highlights, Options (favourite, unit, Export CSV), Show All Data
 * (keyset pages) and Data Sources & Access.
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

    val windowLabel = MetricChartSupport.windowLabel(ui.range, ui.window)
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    // Selection is cleared by a new range, anchor or data set (docs/charts.md "Selection").
    var selected by remember(ui.range, ui.anchor, ui.points, ui.sleepRange) { mutableStateOf<Int?>(null) }
    val selectedHeadline = selected?.let { selectedHeadline(ui, it, zone, is24) }
    MetricDetailScaffold(
        title = name,
        onBack = onBack,
        ranges = HealthChartRange.entries,
        range = ui.range,
        onRange = vm::setRange,
        headline = selectedHeadline ?: ui.headline?.let { (res, value) -> MetricHeadlineUi(stringResource(res), value, "", ui.headlineRange ?: windowLabel) },
        // Sleep D uses Apple's TIME IN BED / TIME ASLEEP header, unless a stage is selected.
        headlineContent = ui.sleepWindow?.takeIf { ui.range == HealthChartRange.DAY && selectedHeadline == null }?.let { w ->
            // The card's ‹ date › row already names the night, so the header stays date-free.
            { SleepDayHeader(w, "") }
        },
        windowLabel = windowLabel,
        canGoForward = ui.canGoForward,
        onShift = vm::shiftAnchor,
        stats = ui.highlights.map { (res, value) -> stringResource(res) to value },
        showEmpty = !ui.loading && !ui.hasChartData,
        chart = {
            DetailChart(ui, tint, name, zone, is24, selected, { selected = it }) { day -> vm.drillTo(day) }
        },
        chartFooter = ui.historyLimitedBeforeMs?.let { floor ->
            {
                Text(
                    stringResource(R.string.health_hub_subtitle_limited, formatDate(floor)),
                    style = MaterialTheme.typography.bodySmall,
                    color = AyuvoColors.secondaryLabel()
                )
            }
        },
        options = {
            if (ui.type != null) {
                row {
                    GroupRow(
                        title = stringResource(R.string.metric_add_to_favourites),
                        modifier = Modifier.testTag("metric.favourite"),
                        trailing = RowTrailing.Toggle(ui.showOnHome, vm::setShowOnHome)
                    )
                }
            }
            row { Box(Modifier.testTag("metric.unit")) { UnitOptionRow(ui, vm) } }
            row {
                GroupRow(
                    title = stringResource(R.string.health_detail_export_csv),
                    onClick = { shareCsv() }
                )
            }
        },
        about = null,
        extraSections = {
            // Show All Data — 5 newest records up front, 5 more per "Load more" (keyset paged).
            item(key = "all-data") {
                Column(Modifier.padding(top = 12.dp).testTag("metric.allData")) {
                    InsetGroup(
                        header = stringResource(R.string.health_detail_show_all_data),
                        footer = if (ui.allData.isEmpty() && !ui.loading) stringResource(R.string.health_hub_no_data_title) else null,
                        dividerInset = 16.dp
                    ) {
                        ui.allData.forEach { record -> row { HealthRecordRow(record = record, onClick = { selectedRecord = record }) } }
                        if (!ui.allDataEnd) {
                            row {
                                GroupRow(
                                    title = stringResource(R.string.health_load_more),
                                    enabled = !ui.loadingMore,
                                    trailing = RowTrailing.None,
                                    onClick = vm::loadMore
                                )
                            }
                        }
                    }
                }
            }
            // Data Sources & Access — grouped by origin package.
            item(key = "sources") {
                Column(Modifier.padding(top = 12.dp).testTag("metric.sources")) {
                    InsetGroup(
                        header = stringResource(R.string.health_detail_sources),
                        footer = stringResource(R.string.health_detail_sources_body),
                        dividerInset = 16.dp
                    ) {
                        ui.sources.forEach { source ->
                            row {
                                GroupRow(
                                    title = source.label,
                                    subtitle = source.packageName.takeIf { it.isNotBlank() && it != source.label },
                                    value = pluralStringResource(R.plurals.health_records_count, source.count, source.count),
                                    trailing = RowTrailing.None
                                )
                            }
                        }
                        row {
                            GroupRow(
                                title = stringResource(R.string.settings_manage_health_access),
                                onClick = { openManageAccess() }
                            )
                        }
                    }
                }
            }
            item(key = "footer") { HealthReadOnlyFooter() }
        }
    )

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

/** Headline for the selected bucket: its value and date (Apple Health behaviour). */
@Composable
private fun selectedHeadline(ui: HealthDetailUiState, index: Int, zone: ZoneId, is24: Boolean): MetricHeadlineUi? {
    if (ui.chartKind == HealthChartKind.SLEEP) {
        val b = ui.sleepRange?.buckets?.getOrNull(index) ?: return null
        if (b.count == 0 || b.bedOffsetMin == null || b.wakeOffsetMin == null) return null
        val daily = ui.range == HealthChartRange.WEEK || ui.range == HealthChartRange.MONTH
        val date = MetricChartSupport.tooltip(ui.range, b.startMs, b.endMs, zone, is24)
        val span = stringResource(R.string.sleep_time_span, ChartClock.offset(b.bedOffsetMin, is24), ChartClock.offset(b.wakeOffsetMin, is24))
        return MetricHeadlineUi(
            stringResource(if (daily) R.string.sleep_time_asleep else R.string.sleep_avg_time_asleep),
            b.asleepS?.takeIf { it > 0 }?.let { HealthValueFormatter.duration(it) },
            "",
            stringResource(R.string.chart_selected_on, date, span)
        )
    }
    val p = ui.points.getOrNull(index) ?: return null
    val style = chartStyle(ui) ?: return null
    val fmt: (Double) -> String = { v -> HealthValueFormatter.format(ui.typeId, v, ui.unitPrefs, unitOverride = ui.descriptor.unit.takeIf { ui.type == null }).text }
    val summed = ui.descriptor.aggregation == HealthAggregation.SUM || ui.descriptor.isDurationLike
    val value = when (style) {
        HealthChartStyle.RANGE -> if (p.min != null && p.max != null) {
            if (ui.chartKind == HealthChartKind.BLOOD_PRESSURE && p.v2Min != null && p.v2Max != null) {
                "${p.min.toInt()}–${p.max.toInt()} / ${p.v2Min.toInt()}–${p.v2Max.toInt()}"
            } else "${HealthValueFormatter.format(ui.typeId, p.min, ui.unitPrefs).number}–${fmt(p.max)}"
        } else null
        else -> chartValueOf(style, barValueSelector(ui))(p)?.let(fmt)
    }
    val label = when {
        style == HealthChartStyle.RANGE -> R.string.health_detail_range
        summed && !ui.range.plotsDailyAverage -> R.string.health_detail_total
        ui.descriptor.aggregation == HealthAggregation.COUNT || ui.descriptor.kind == com.ayuvo.health.models.HealthKind.CATEGORY -> R.string.health_detail_records
        else -> R.string.health_detail_average
    }
    val shown = if (label == R.string.health_detail_records) "${p.count}" else value
    return MetricHeadlineUi(stringResource(label), shown, "", MetricChartSupport.tooltip(ui.range, p.bucketStartMs, p.bucketEndMs, zone, is24))
}

private fun chartStyle(ui: HealthDetailUiState): HealthChartStyle? = when (ui.chartKind) {
    HealthChartKind.BAR -> HealthChartStyle.BAR
    HealthChartKind.LINE -> HealthChartStyle.LINE
    HealthChartKind.RANGE, HealthChartKind.BLOOD_PRESSURE -> HealthChartStyle.RANGE
    else -> null
}

/** 6M/Y bars of summed types plot the daily average. */
private fun barValueSelector(ui: HealthDetailUiState): ((com.ayuvo.health.data.health.HealthChartPoint) -> Double?)? =
    if (ui.chartKind == HealthChartKind.BAR && ui.range.plotsDailyAverage && (ui.descriptor.aggregation == HealthAggregation.SUM || ui.descriptor.isDurationLike)) { p -> p.avg } else null

@Composable
private fun DetailChart(
    ui: HealthDetailUiState,
    tint: Color,
    name: String,
    zone: ZoneId,
    is24: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDrill: (LocalDate) -> Unit
) {
    val window = ui.window
    val days = remember(window) { generateSequence(window.start) { it.plusDays(1) }.takeWhile { !it.isAfter(window.endInclusive) }.toList() }
    val monthDay = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val xLabels = remember(ui.range, ui.anchor, ui.weekStart, is24) { MetricChartSupport.xLabels(ui.range, ui.anchor, ui.weekStart, zone, is24) }
    val fmt: (Double) -> String = { v -> HealthValueFormatter.format(ui.typeId, v, ui.unitPrefs, unitOverride = ui.descriptor.unit.takeIf { ui.type == null }).number }
    val summary = stringResource(R.string.health_detail_chart_summary, name, ui.points.count { !it.isEmpty }, ui.highlights.joinToString(", ") { it.second })
    val ranges = HealthChartRange.entries
    val drill: (Int) -> Boolean = { i ->
        val p = ui.points.getOrNull(i)
        val day = p?.let { MetricNavigation.drillDay(ui.range, it.bucketStartMs, !it.isEmpty, ranges, zone) }
        if (day != null) onDrill(day)
        day != null
    }
    when (ui.chartKind) {
        HealthChartKind.SLEEP -> {
            val w = ui.sleepWindow
            val series = ui.sleepRange
            if (ui.range == HealthChartRange.DAY && w != null) {
                SleepHypnogram(w, ui.sleepRows, zone, is24)
                SleepStageList(w, Modifier.padding(top = 8.dp))
            } else if (series != null) {
                SleepRangeBars(
                    series = series,
                    nightRows = ui.sleepNightRows,
                    xLabels = xLabels,
                    zone = zone,
                    is24 = is24,
                    selected = selected,
                    onSelect = onSelect,
                    onBucketTap = { i ->
                        val b = series.buckets[i]
                        val day = MetricNavigation.drillDay(ui.range, b.startMs, b.count > 0, ranges, zone)
                        if (day != null) onDrill(day)
                        day != null
                    }
                )
            }
        }
        HealthChartKind.PERIOD_BAND -> PeriodBandChart(days, ui.periodDays, tint, spreadLabels(days.map { monthDay.format(it) }, 5))
        HealthChartKind.BAR -> HealthBucketChart(
            ui.points, HealthChartStyle.BAR, tint, xLabels, fmt, selected, onSelect, summary = summary,
            valueSelector = barValueSelector(ui), onBucketTap = drill
        )
        HealthChartKind.LINE -> HealthBucketChart(ui.points, HealthChartStyle.LINE, tint, xLabels, fmt, selected, onSelect, summary = summary, onBucketTap = drill)
        HealthChartKind.RANGE -> HealthBucketChart(ui.points, HealthChartStyle.RANGE, tint, xLabels, fmt, selected, onSelect, summary = summary, onBucketTap = drill)
        HealthChartKind.BLOOD_PRESSURE -> HealthBucketChart(ui.points, HealthChartStyle.RANGE, tint, xLabels, fmt, selected, onSelect, secondaryColor = Color(0xFF0A84FF), summary = summary, onBucketTap = drill)
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
