package com.ayuvo.health.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.charts.StatBadgeRow
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.HeadlineStat
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.InsetGroupScope
import com.ayuvo.health.ui.design.RangeSegmentedControl
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.health.HealthChartRange
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors

/** Headline above the chart: label ("AVERAGE"), value or null ("—"), unit and the interval text. */
data class MetricHeadlineUi(val label: String, val value: String?, val unit: String, val rangeText: String)

/**
 * Shared metric detail layout (docs/ui-structure.md §6): title bar · range picker · headline ·
 * ‹ interval › · chart card with stat badges · Options · extra sections · About.
 */
@Composable
fun MetricDetailScaffold(
    title: String,
    onBack: () -> Unit,
    ranges: List<HealthChartRange>,
    range: HealthChartRange,
    onRange: (HealthChartRange) -> Unit,
    headline: MetricHeadlineUi?,
    windowLabel: String,
    canGoForward: Boolean,
    onShift: (Int) -> Unit,
    stats: List<Pair<String, String>>,
    showEmpty: Boolean,
    chart: @Composable ColumnScope.() -> Unit,
    options: InsetGroupScope.() -> Unit,
    about: String?,
    modifier: Modifier = Modifier,
    chartFooter: (@Composable ColumnScope.() -> Unit)? = null,
    extraSections: LazyListScope.() -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = title, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 4.dp, bottom = BottomNavScrollPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap)
        ) {
            if (ranges.size > 1) {
                item(key = "range") {
                    RangeSegmentedControl(
                        options = ranges,
                        selected = range,
                        label = { stringResource(it.labelRes) },
                        onSelect = onRange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item(key = "headline") {
                val h = headline
                if (h != null) {
                    HeadlineStat(
                        label = h.label,
                        value = h.value ?: "—",
                        unit = if (h.value == null) "" else h.unit,
                        rangeText = h.rangeText,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).testTag("metric.headline")
                    )
                }
            }
            item(key = "chart") {
                SurfaceCard(modifier = Modifier.testTag("metric.chart"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onShift(-1) }, modifier = Modifier.size(36.dp).testTag("metric.prev")) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.health_detail_previous), tint = AppColors.Calorie)
                        }
                        Text(
                            windowLabel,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        IconButton(onClick = { onShift(1) }, enabled = canGoForward, modifier = Modifier.size(36.dp).testTag("metric.next")) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = stringResource(R.string.health_detail_next),
                                tint = if (canGoForward) AppColors.Calorie else AyuvoColors.tertiaryLabel()
                            )
                        }
                    }
                    if (stats.isNotEmpty()) StatBadgeRow(stats)
                    if (showEmpty) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.ShowChart,
                                title = stringResource(R.string.health_detail_no_data_range),
                                message = ""
                            )
                        }
                    } else {
                        chart()
                    }
                    chartFooter?.invoke(this)
                }
            }
            item(key = "options") {
                InsetGroup(
                    modifier = Modifier.padding(top = 12.dp),
                    header = stringResource(R.string.health_detail_options),
                    dividerInset = 16.dp,
                    content = options
                )
            }
            extraSections()
            if (!about.isNullOrBlank()) {
                item(key = "about") {
                    Box(Modifier.padding(top = 12.dp).testTag("metric.about")) {
                        InsetGroup(header = stringResource(R.string.metric_about), dividerInset = 16.dp) {
                            row {
                                Text(
                                    about,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
