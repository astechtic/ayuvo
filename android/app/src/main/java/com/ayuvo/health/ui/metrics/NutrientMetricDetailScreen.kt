package com.ayuvo.health.ui.metrics

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.HeadlineKind
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.NutrientMetricSeries
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.health.HealthChartRange
import java.time.ZoneId

/**
 * `nutrient:<key>` detail (docs/nutrients.md §5, docs/ui-structure.md §6): the shared metric layout
 * with food + supplement bars, the Recommended / Upper limit rules on W–Y, "Today X of Y" on D, the
 * "Average per logged day" badge, Food vs Supplements, About with the reference values and sources,
 * and the website guide.
 */
@Composable
fun NutrientMetricDetailScreen(
    container: AppContainer,
    key: MetricKey.Nutrient,
    onBack: () -> Unit,
    destinations: AppMetricDestinations = AppMetricDestinations()
) {
    val vm: NutrientMetricDetailViewModel = viewModel(key = "nutrient-metric-${key.key}", factory = NutrientMetricDetailViewModel.Factory(container, key))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val catalog = container.metricCatalog
    val zone = remember { ZoneId.systemDefault() }
    val tint = MetricCatalog.color(catalog, key)
    val unit = NutrientFields.unit(key.key)
    val name = MetricCatalog.title(context, key)
    val spec = MetricCatalog.nutrientSpec(catalog, key)

    val series = ui.series
    val window = series?.let { MetricsReference.localDateOf(it.bounds.startMs, zone)..MetricsReference.localDateOf(it.bounds.endMs, zone).minusDays(1) }
    val windowLabel = window?.let { MetricChartSupport.windowLabel(ui.range, it) } ?: ""
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val points = remember(series) { series?.let { s -> MetricChartSupport.points(s.buckets) { it } }.orEmpty() }
    var selected by remember(ui.range, ui.anchor, points) { mutableStateOf<Int?>(null) }
    val referenceLines = nutrientChartReferenceLines(ui.lines, ui.range.metricRange, tint)
    val selectedHeadline = selected?.let { i -> points.getOrNull(i) }?.takeIf { it.avg != null }?.let { p ->
        MetricHeadlineUi(
            label = stringResource(if (ui.range.plotsDailyAverage) R.string.health_detail_average else R.string.health_detail_total),
            value = NutrientFormat.amount(series!!.buckets[selected!!].value),
            unit = unit,
            rangeText = MetricChartSupport.tooltip(ui.range, p.bucketStartMs, p.bucketEndMs, zone, is24)
        )
    }

    MetricDetailScaffold(
        title = name,
        onBack = onBack,
        ranges = ui.ranges,
        range = ui.range,
        onRange = vm::setRange,
        headline = selectedHeadline ?: series?.let { headline(it, unit, windowLabel) },
        windowLabel = windowLabel,
        canGoForward = ui.canGoForward,
        onShift = vm::shiftAnchor,
        stats = series?.let { stats(it, unit, ui.range) }.orEmpty(),
        showEmpty = !ui.loading && series?.hasData != true,
        chart = {
            if (series != null) {
                HealthBucketChart(
                    points = points,
                    style = HealthChartStyle.BAR,
                    color = tint,
                    xLabels = remember(ui.range, ui.anchor, ui.weekStart, is24) { MetricChartSupport.xLabels(ui.range, ui.anchor, ui.weekStart, zone, is24) },
                    formatValue = { NutrientFormat.amount(it) },
                    selected = selected,
                    onSelect = { selected = it },
                    summary = stringResource(R.string.health_detail_chart_summary, name, points.count { !it.isEmpty }, windowLabel),
                    onBucketTap = { i ->
                        val day = MetricNavigation.drillDay(ui.range, points, i, !points[i].isEmpty, ui.ranges, zone)
                        if (day != null) vm.drillTo(day)
                        day != null
                    },
                    referenceLines = referenceLines
                )
            }
        },
        chartFooter = if (ui.range == HealthChartRange.DAY && series != null) {
            {
                NutrientDayTargetLine(series.headline.value, ui.lines, ui.anchor, unit)
            }
        } else null,
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
                    title = stringResource(R.string.metric_open_food_diary),
                    modifier = Modifier.testTag("metric.log"),
                    onClick = destinations.openFoodDiary
                )
            }
            if (NutrientFields.optionalNutrient(key.key) != null) {
                row {
                    GroupRow(
                        title = stringResource(R.string.nutrients_edit_goal),
                        value = ui.customGoal?.let { "$it $unit" } ?: stringResource(R.string.nutrients_goal_default),
                        onClick = destinations.openNutrientGoals
                    )
                }
            }
        },
        about = null,
        extraSections = {
            if (series != null) {
                item(key = "food-vs-supplements") {
                    InsetGroup(
                        modifier = Modifier.padding(top = 12.dp).testTag("nutrient.split"),
                        header = stringResource(R.string.nutrients_food_vs_supplements),
                        footer = stringResource(R.string.nutrients_split_footer, windowLabel),
                        dividerInset = 16.dp
                    ) {
                        row { GroupRow(title = stringResource(R.string.nutrients_from_food), value = NutrientFormat.withUnit(series.foodTotal, unit), trailing = RowTrailing.None) }
                        row { GroupRow(title = stringResource(R.string.nutrients_from_supplements), value = NutrientFormat.withUnit(series.supplementTotal, unit), trailing = RowTrailing.None) }
                        row {
                            val total = if (series.foodTotal == null && series.supplementTotal == null) null else (series.foodTotal ?: 0.0) + (series.supplementTotal ?: 0.0)
                            GroupRow(title = stringResource(R.string.nutrients_total), value = NutrientFormat.withUnit(total, unit), trailing = RowTrailing.None)
                        }
                    }
                }
            }
            item(key = "about") {
                NutrientAboutGroup(key.key, ui.lines, ui.customGoal, unit, Modifier.padding(top = 12.dp))
            }
            val slug = spec?.learnSlug
            if (slug != null) {
                item(key = "learn-more") { NutrientLearnMoreGroup(name, slug, Modifier.padding(top = 12.dp)) }
            }
        }
    )
}

@Composable
private fun headline(series: NutrientMetricSeries, unit: String, windowLabel: String): MetricHeadlineUi {
    val h = series.headline
    return MetricHeadlineUi(
        label = stringResource(
            when (h.kind) {
                HeadlineKind.TOTAL -> R.string.health_detail_total
                HeadlineKind.AVERAGE -> R.string.health_detail_average
                HeadlineKind.LATEST -> R.string.health_detail_latest
            }
        ),
        value = h.value?.let { NutrientFormat.amount(it) },
        unit = unit,
        rangeText = windowLabel
    )
}

/** Badges: "Average per logged day" (docs/nutrients.md §4.7), the interval total and the logged days; none on D. */
@Composable
private fun stats(series: NutrientMetricSeries, unit: String, range: HealthChartRange): List<Pair<String, String>> {
    if (range == HealthChartRange.DAY || !series.hasData) return emptyList()
    val total = if (series.foodTotal == null && series.supplementTotal == null) null else (series.foodTotal ?: 0.0) + (series.supplementTotal ?: 0.0)
    return listOf(
        stringResource(R.string.nutrients_average_per_logged_day) to NutrientFormat.withUnit(series.loggedDayAverage.average, unit),
        stringResource(R.string.nutrients_total) to NutrientFormat.withUnit(total, unit),
        stringResource(R.string.nutrients_logged_days) to series.loggedDayAverage.loggedDays.toString()
    )
}
