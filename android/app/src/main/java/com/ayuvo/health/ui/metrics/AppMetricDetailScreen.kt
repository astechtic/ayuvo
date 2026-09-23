package com.ayuvo.health.ui.metrics

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.AppMetricSeries
import com.ayuvo.health.data.metrics.HeadlineKind
import com.ayuvo.health.data.metrics.MetricAggregation
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.AppMetricAggregator
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.ui.body.AddBodyFatDialog
import com.ayuvo.health.ui.body.AddWeightDialog
import com.ayuvo.health.ui.body.AllBodyFatHistorySheet
import com.ayuvo.health.ui.body.AllWeightHistorySheet
import com.ayuvo.health.ui.body.AllWorkoutHistorySheet
import com.ayuvo.health.ui.body.BodyLogViewModel
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.UnitToggle
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.home.WaterCustomAmountSheet
import com.ayuvo.health.ui.health.HealthChartRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Where the "Log" / "Show All Data" rows of app metrics that live in other screens go. */
data class AppMetricDestinations(
    val openFoodDiary: () -> Unit = {},
    val openFasting: () -> Unit = {},
    val openWorkouts: () -> Unit = {}
)

private val FOOD_METRICS = setOf(AppMetricId.CALORIES, AppMetricId.PROTEIN, AppMetricId.CARBS, AppMetricId.FAT, AppMetricId.FIBER)
private val WORKOUT_METRICS = setOf(AppMetricId.WORKOUTS, AppMetricId.WORKOUT_MINUTES, AppMetricId.WORKOUT_BURN)

@Composable
fun AppMetricDetailScreen(
    container: AppContainer,
    id: AppMetricId,
    onBack: () -> Unit,
    destinations: AppMetricDestinations = AppMetricDestinations()
) {
    val vm: AppMetricDetailViewModel = viewModel(key = "app-metric-${id.slug}", factory = AppMetricDetailViewModel.Factory(container, id))
    val bodyVm: BodyLogViewModel = viewModel(key = "app-metric-body", factory = BodyLogViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val body by bodyVm.ui.collectAsState()
    val context = LocalContext.current
    val catalog = container.metricCatalog
    val key = MetricKey.App(id)
    val zone = remember { ZoneId.systemDefault() }
    val tint = MetricCatalog.color(catalog, key)
    val units = ui.units

    var showLogDialog by rememberSaveable { mutableStateOf(false) }
    var showAllData by rememberSaveable { mutableStateOf(false) }
    var showWaterSheet by rememberSaveable { mutableStateOf(false) }
    var workoutPendingDelete by remember { mutableStateOf<WorkoutSession?>(null) }

    val series = ui.series
    val window = series?.let { MetricsReference.localDateOf(it.bounds.startMs, zone)..MetricsReference.localDateOf(it.bounds.endMs, zone).minusDays(1) }
    val windowLabel = window?.let { MetricChartSupport.windowLabel(ui.range, it) } ?: ""
    val fmt: (Double) -> String = { v -> MetricCatalog.formatDisplay(id, v) }
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val points = remember(series, units) { series?.let { s -> MetricChartSupport.points(s.buckets) { MetricCatalog.display(id, it, units) } }.orEmpty() }
    // Selection is cleared by a new range, anchor or data set (docs/charts.md "Selection").
    var selected by remember(ui.range, ui.anchor, points) { mutableStateOf<Int?>(null) }
    val selectedHeadline = selected?.let { i -> points.getOrNull(i) }?.takeIf { it.avg != null }?.let { p ->
        MetricHeadlineUi(
            label = stringResource(
                when {
                    AppMetricAggregator.aggregation(id) == MetricAggregation.LAST -> R.string.health_detail_latest
                    AppMetricAggregator.aggregation(id).summed && !ui.range.plotsDailyAverage -> R.string.health_detail_total
                    else -> R.string.health_detail_average
                }
            ),
            value = MetricCatalog.format(id, series!!.buckets[selected!!].value!!, units),
            unit = MetricCatalog.unitLabel(id, units),
            rangeText = MetricChartSupport.tooltip(ui.range, p.bucketStartMs, p.bucketEndMs, zone, is24)
        )
    }

    MetricDetailScaffold(
        title = MetricCatalog.title(context, key),
        onBack = onBack,
        ranges = ui.ranges,
        range = ui.range,
        onRange = vm::setRange,
        headline = selectedHeadline ?: series?.let { headlineUi(id, it, units, windowLabel, zone) },
        windowLabel = windowLabel,
        canGoForward = ui.canGoForward,
        onShift = vm::shiftAnchor,
        stats = series?.let { statsFor(id, it, units, ui.range) }.orEmpty(),
        showEmpty = !ui.loading && series?.hasData != true,
        chart = {
            if (series != null) {
                val style = if (MetricCatalog.spec(catalog, id).chartKind == "line") HealthChartStyle.LINE else HealthChartStyle.BAR
                val name = MetricCatalog.title(context, key)
                HealthBucketChart(
                    points = points,
                    style = style,
                    color = tint,
                    xLabels = remember(ui.range, ui.anchor, ui.weekStart, is24) { MetricChartSupport.xLabels(ui.range, ui.anchor, ui.weekStart, zone, is24) },
                    formatValue = fmt,
                    selected = selected,
                    onSelect = { selected = it },
                    summary = stringResource(R.string.health_detail_chart_summary, name, points.count { !it.isEmpty }, windowLabel),
                    goalValue = ui.goal?.let { MetricCatalog.display(id, it, units) },
                    onBucketTap = { i ->
                        val day = MetricNavigation.drillDay(ui.range, points, i, !points[i].isEmpty, ui.ranges, zone)
                        if (day != null) vm.drillTo(day)
                        day != null
                    }
                )
            }
        },
        options = {
            row {
                GroupRow(
                    title = stringResource(R.string.metric_add_to_favourites),
                    modifier = Modifier.testTag("metric.favourite"),
                    trailing = RowTrailing.Toggle(ui.pinned, vm::setPinned)
                )
            }
            row {
                GroupRow(
                    title = stringResource(R.string.health_detail_show_all_data),
                    modifier = Modifier.testTag("metric.allData"),
                    onClick = {
                        when (id) {
                            in FOOD_METRICS -> destinations.openFoodDiary()
                            AppMetricId.FASTING -> destinations.openFasting()
                            else -> showAllData = true
                        }
                    }
                )
            }
            if (id == AppMetricId.WEIGHT || id == AppMetricId.WATER) {
                row {
                    GroupRow(
                        title = stringResource(R.string.health_detail_unit),
                        modifier = Modifier.testTag("metric.unit"),
                        trailing = RowTrailing.Custom {
                            if (id == AppMetricId.WEIGHT) {
                                UnitToggle(stringResource(R.string.health_unit_kg), stringResource(R.string.health_unit_lb), units.weightMetric, vm::setWeightMetric, Modifier.width(UNIT_TOGGLE_WIDTH))
                            } else {
                                UnitToggle(
                                    leftLabel = WaterUnit.MILLILITERS.symbol,
                                    rightLabel = WaterUnit.FLUID_OUNCES.symbol,
                                    isLeft = units.waterUnit == WaterUnit.MILLILITERS,
                                    onSelect = { ml -> vm.setWaterUnit(if (ml) WaterUnit.MILLILITERS else WaterUnit.FLUID_OUNCES) },
                                    modifier = Modifier.width(UNIT_TOGGLE_WIDTH)
                                )
                            }
                        }
                    )
                }
            }
            row {
                GroupRow(
                    title = stringResource(logTitleRes(id)),
                    modifier = Modifier.testTag("metric.log"),
                    onClick = {
                        when (id) {
                            AppMetricId.WEIGHT, AppMetricId.BODY_FAT -> showLogDialog = true
                            AppMetricId.WATER -> showWaterSheet = true
                            in FOOD_METRICS -> destinations.openFoodDiary()
                            AppMetricId.FASTING -> destinations.openFasting()
                            else -> destinations.openWorkouts()
                        }
                    }
                )
            }
            if (id == AppMetricId.WATER) {
                row {
                    val quick = units.waterUnit.format(QUICK_WATER_ML)
                    GroupRow(title = stringResource(R.string.metric_add_water_quick, quick), onClick = { vm.addWater(QUICK_WATER_ML) }, trailing = RowTrailing.None)
                }
            }
        },
        about = stringResource(MetricCatalog.aboutRes(id)),
        extraSections = {
            if (id == AppMetricId.WATER && showAllData) {
                val rows = ui.waterInWindow()
                item(key = "water-rows") {
                    InsetGroup(modifier = Modifier.padding(top = 12.dp), header = stringResource(R.string.health_detail_show_all_data), footer = if (rows.isEmpty()) stringResource(R.string.metric_no_rows) else null, dividerInset = 16.dp) {
                        rows.take(MAX_WATER_ROWS).forEach { entry ->
                            row {
                                GroupRow(
                                    title = units.waterUnit.format(entry.milliliters),
                                    subtitle = dateTime(entry.date, zone),
                                    trailing = RowTrailing.Custom {
                                        IconButton(onClick = { vm.deleteWater(entry.id) }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.metric_delete_entry))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    if (showLogDialog && id == AppMetricId.WEIGHT) {
        val seedKg = body.entries.maxByOrNull { it.date }?.weightKg ?: body.profile?.weightKg ?: 70.0
        AddWeightDialog(
            useMetric = units.weightMetric,
            initialKg = seedKg,
            onUnitChange = vm::setWeightMetric,
            onDismiss = { showLogDialog = false }
        ) { kg -> bodyVm.addWeight(kg); showLogDialog = false }
    }
    if (showLogDialog && id == AppMetricId.BODY_FAT) {
        val seed = body.bodyFatEntries.maxByOrNull { it.date }?.bodyFatFraction ?: body.profile?.bodyFatPercentage ?: 0.20
        AddBodyFatDialog(initialFraction = seed, onDismiss = { showLogDialog = false }) { f -> bodyVm.addBodyFat(f); showLogDialog = false }
    }
    if (showWaterSheet) {
        WaterCustomAmountSheet(unit = units.waterUnit, onDismiss = { showWaterSheet = false }) { ml ->
            vm.addWater(ml); showWaterSheet = false
        }
    }
    if (showAllData) {
        when (id) {
            AppMetricId.WEIGHT -> AllWeightHistorySheet(
                entries = body.entries.sortedByDescending { it.date },
                useMetric = units.weightMetric,
                onDelete = bodyVm::deleteWeight,
                onDismiss = { showAllData = false }
            )
            AppMetricId.BODY_FAT -> AllBodyFatHistorySheet(
                entries = body.bodyFatEntries.sortedByDescending { it.date },
                onDelete = bodyVm::deleteBodyFat,
                onDismiss = { showAllData = false }
            )
            in WORKOUT_METRICS -> AllWorkoutHistorySheet(
                entries = ui.snapshot.workouts.let { all -> if (id == AppMetricId.WORKOUT_BURN) all.filter { it.caloriesBurned != null } else all },
                onRequestDelete = { workoutPendingDelete = it },
                onDismiss = { showAllData = false }
            )
            else -> Unit
        }
    }
    workoutPendingDelete?.let { session ->
        GlassDialog(onDismissRequest = { workoutPendingDelete = null }) {
            Text(stringResource(R.string.progress_workout_delete_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.progress_workout_delete_message))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = { bodyVm.deleteWorkoutBurn(session.id); workoutPendingDelete = null },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { workoutPendingDelete = null },
                destructive = true
            )
        }
    }
    if (body.goalReached) {
        GlassDialog(onDismissRequest = { bodyVm.dismissGoalReached() }) {
            Text(stringResource(R.string.progress_goal_reached_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.progress_goal_reached_message))
            GlassDialogActions(primaryText = stringResource(R.string.action_keep_going), onPrimary = { bodyVm.dismissGoalReached() })
        }
    }
}

private const val QUICK_WATER_ML = 250
private val UNIT_TOGGLE_WIDTH = 150.dp
private const val MAX_WATER_ROWS = 50

private fun logTitleRes(id: AppMetricId): Int = when (id) {
    AppMetricId.WEIGHT -> R.string.metric_log_weight
    AppMetricId.BODY_FAT -> R.string.metric_log_body_fat
    AppMetricId.WATER -> R.string.metric_add_water_custom
    in FOOD_METRICS -> R.string.metric_open_food_diary
    AppMetricId.FASTING -> R.string.metric_open_fasting
    else -> R.string.metric_open_workouts
}

private fun dateTime(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()).format(at.atZone(zone))

@Composable
private fun headlineUi(id: AppMetricId, series: AppMetricSeries, units: MetricUnits, windowLabel: String, zone: ZoneId): MetricHeadlineUi {
    val h = series.headline
    val label = stringResource(
        when (h.kind) {
            HeadlineKind.TOTAL -> R.string.health_detail_total
            HeadlineKind.AVERAGE -> R.string.health_detail_average
            HeadlineKind.LATEST -> R.string.health_detail_latest
        }
    )
    val rangeText = if (h.kind == HeadlineKind.LATEST && h.value != null) dateTime(Instant.ofEpochMilli(h.fromMs), zone) else windowLabel
    return MetricHeadlineUi(
        label = label,
        value = h.value?.let { MetricCatalog.format(id, it, units) },
        unit = MetricCatalog.unitLabel(id, units),
        rangeText = rangeText
    )
}

/** Stat badges under the interval: totals and day counts for summed metrics, range and latest for readings. */
@Composable
private fun statsFor(id: AppMetricId, series: AppMetricSeries, units: MetricUnits, range: HealthChartRange): List<Pair<String, String>> {
    val values = series.rows.mapNotNull { it.value }
    if (values.isEmpty()) return emptyList()
    val unit = MetricCatalog.unitLabel(id, units).let { if (it.isEmpty()) "" else " $it" }
    return when (AppMetricAggregator.aggregation(id)) {
        MetricAggregation.LAST -> listOf(
            stringResource(R.string.health_detail_range) to (
                MetricCatalog.format(id, values.min(), units).let { lo ->
                    val hi = MetricCatalog.format(id, values.max(), units)
                    if (lo == hi) lo else "$lo–$hi"
                } + unit
            ),
            stringResource(R.string.health_detail_records) to values.size.toString()
        )
        MetricAggregation.COUNT -> listOf(stringResource(R.string.health_detail_total) to values.size.toString())
        // The day view's total already is the headline, so only longer ranges add the day count.
        else -> listOfNotNull(
            (stringResource(R.string.health_detail_total) to MetricCatalog.format(id, values.sum(), units) + unit).takeIf { range != HealthChartRange.DAY },
            (stringResource(R.string.progress_workout_stat_days) to series.headline.daysWithData.toString()).takeIf { range != HealthChartRange.DAY }
        )
    }
}
