package com.ayuvo.health.ui.metrics

import com.ayuvo.health.l10n.ContractStrings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ayuvo.health.data.derived.DerivedMetricInfo
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.health.HealthCategoryStyle
import com.ayuvo.health.ui.health.HealthChartRange
import java.time.ZoneId

/**
 * `derived:<id>` detail (docs/derived-metrics.md, docs/ui-structure.md §6): the shared metric layout with the day values
 * bucketed by `aggregation`, a source line (Ayuvo's estimate or the Health Connect value that won), Options with Add to
 * Favourites and "Calculate this metric", About, and "How it's calculated" (method, reference, confidence, disclaimer).
 * Every number here is an estimate, never a diagnosis.
 */
@Composable
fun DerivedMetricDetailScreen(
    container: AppContainer,
    key: MetricKey.Derived,
    onBack: () -> Unit
) {
    val vm: DerivedMetricDetailViewModel = viewModel(key = "derived-metric-${key.id}", factory = DerivedMetricDetailViewModel.Factory(container, key))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val catalog = container.metricCatalog
    val zone = remember { ZoneId.systemDefault() }
    val info = ui.info
    val name = info?.displayTitle(context) ?: key.id
    val tint = MetricCatalog.color(catalog, key)
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val unit = info?.let(DerivedMetricSupport::unitLabel).orEmpty()
    val labels = remember(ui.labels) { ui.labels?.mapIndexed { i, t -> ContractStrings.text(context, "derived.labels.${key.id}.$i", t) } }
    val format: (Double) -> String = { v -> if (info == null) v.toString() else DerivedMetricSupport.format(info, v, is24, labels) }

    val bounds = ui.bounds
    val window = bounds?.let { MetricsReference.localDateOf(it.startMs, zone)..MetricsReference.localDateOf(it.endMs, zone).minusDays(1) }
    val windowLabel = window?.let { MetricChartSupport.windowLabel(ui.range, it) } ?: ""
    val points = remember(ui.buckets) { MetricChartSupport.points(ui.buckets) { it } }
    var selected by remember(ui.range, ui.anchor, points) { mutableStateOf<Int?>(null) }
    val selectedHeadline = selected?.let { i -> ui.buckets.getOrNull(i) }?.takeIf { it.value != null }?.let { b ->
        MetricHeadlineUi(
            label = stringResource(headlineLabel(ui.headline?.kind ?: DerivedHeadline.Kind.AVERAGE)),
            value = format(b.value!!),
            unit = unit,
            rangeText = MetricChartSupport.tooltip(ui.range, b.startMs, b.endMs, zone, is24)
        )
    }
    val headline = ui.headline?.let { h ->
        MetricHeadlineUi(
            label = stringResource(headlineLabel(h.kind)),
            value = h.value?.let(format),
            unit = unit,
            rangeText = windowLabel
        )
    }
    val sourceLine = sourceLine(info, ui.points)

    MetricDetailScaffold(
        title = name,
        onBack = onBack,
        ranges = ui.ranges,
        range = ui.range,
        onRange = vm::setRange,
        headline = selectedHeadline ?: headline,
        headlineNote = sourceLine,
        windowLabel = windowLabel,
        canGoForward = ui.canGoForward,
        onShift = vm::shiftAnchor,
        stats = emptyList(),
        showEmpty = !ui.loading && ui.points.isEmpty(),
        chart = {
            HealthBucketChart(
                points = points,
                style = if (info?.chartKind == "bar") HealthChartStyle.BAR else HealthChartStyle.LINE,
                color = tint,
                xLabels = remember(ui.range, ui.anchor, ui.weekStart, is24) {
                    if (ui.range == HealthChartRange.DAY) emptyList() else MetricChartSupport.xLabels(ui.range, ui.anchor, ui.weekStart, zone, is24)
                },
                formatValue = format,
                selected = selected,
                onSelect = { selected = it },
                summary = stringResource(R.string.health_detail_chart_summary, name, points.count { !it.isEmpty }, windowLabel),
                onBucketTap = { i ->
                    val day = MetricNavigation.drillDay(ui.range, points, i, !points[i].isEmpty, ui.ranges, zone)
                    if (day != null) vm.drillTo(day)
                    day != null
                }
            )
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
                val subtitle = when {
                    !ui.masterOn -> stringResource(R.string.derived_master_off)
                    !ui.metricOn && ui.dependents.isNotEmpty() ->
                        stringResource(R.string.settings_derived_also_affects, container.derivedCatalog.dependentsOf(key.id).joinToString(", ") { it.displayTitle(context) })
                    !ui.metricOn -> stringResource(R.string.derived_metric_toggle_off_footer)
                    else -> null
                }
                GroupRow(
                    title = stringResource(R.string.derived_metric_toggle),
                    subtitle = subtitle,
                    modifier = Modifier.testTag("derived.toggle"),
                    trailing = RowTrailing.Toggle(ui.calculated, vm::setCalculated, enabled = ui.masterOn)
                )
            }
        },
        about = null,
        extraSections = {
            if (info != null && info.about.isNotBlank()) {
                item(key = "about") {
                    InsetGroup(
                        modifier = Modifier.padding(top = 12.dp).testTag("metric.about"),
                        header = stringResource(R.string.metric_about),
                        dividerInset = 16.dp
                    ) {
                        row { BodyText(info.displayAbout(context)) }
                    }
                }
            }
            if (info != null) {
                item(key = "how") {
                    val confidence = DerivedMetricSupport.confidence(ui.points)
                    val mix = DerivedMetricSupport.sourceMix(ui.points)
                    val methodLabel = stringResource(R.string.derived_method)
                    val citationLabel = stringResource(R.string.derived_citation)
                    val confidenceTitle = stringResource(R.string.derived_confidence)
                    val confidenceText = when {
                        confidence != null -> stringResource(confidenceLabel(confidence))
                        mix == DerivedSourceMix.NATIVE -> stringResource(R.string.derived_confidence_native)
                        else -> null
                    }
                    InsetGroup(
                        modifier = Modifier.padding(top = 12.dp).testTag("derived.how"),
                        header = stringResource(R.string.derived_how_calculated),
                        footer = container.derivedCatalog.displayDisclaimer(context).takeIf { it.isNotBlank() },
                        dividerInset = 16.dp
                    ) {
                        if (info.method.isNotBlank()) row { LabelledText(methodLabel, info.displayMethod(context)) }
                        if (info.citation.isNotBlank()) row { LabelledText(citationLabel, info.citation) }
                        if (confidenceText != null) {
                            row {
                                GroupRow(
                                    title = confidenceTitle,
                                    value = confidenceText,
                                    trailing = RowTrailing.None,
                                    modifier = Modifier.testTag("derived.confidence")
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun headlineLabel(kind: DerivedHeadline.Kind): Int = when (kind) {
    DerivedHeadline.Kind.TOTAL -> R.string.health_detail_total
    DerivedHeadline.Kind.AVERAGE -> R.string.health_detail_average
    DerivedHeadline.Kind.HIGHEST -> R.string.derived_headline_highest
    DerivedHeadline.Kind.LATEST -> R.string.health_detail_latest
}

private fun confidenceLabel(c: DerivedConfidence): Int = when (c) {
    DerivedConfidence.HIGH -> R.string.derived_confidence_high
    DerivedConfidence.MEDIUM -> R.string.derived_confidence_medium
    DerivedConfidence.LOW -> R.string.derived_confidence_low
}

/** "Estimated by Ayuvo …", "From Health Connect (Resting Heart Rate)" or both, for the shown interval. */
@Composable
private fun sourceLine(info: DerivedMetricInfo?, points: List<com.ayuvo.health.data.health.DerivedPoint>): String {
    val context = LocalContext.current
    val nativeName = info?.nativeTypeId?.let { HealthCategoryStyle.typeName(context, it) }
    return when (DerivedMetricSupport.sourceMix(points)) {
        DerivedSourceMix.NATIVE ->
            if (nativeName != null) stringResource(R.string.derived_source_native, nativeName) else stringResource(R.string.derived_source_native_generic)
        DerivedSourceMix.MIXED -> stringResource(R.string.derived_source_mixed)
        DerivedSourceMix.DERIVED -> stringResource(R.string.derived_source_estimated)
        DerivedSourceMix.NONE ->
            if (info?.nativeTypeId != null) stringResource(R.string.settings_derived_native_wins) else stringResource(R.string.derived_estimated_badge)
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun LabelledText(label: String, text: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
        Text(text, fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
