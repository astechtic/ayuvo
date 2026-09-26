package com.ayuvo.health.ui.insights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.insights.MetricInsight
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.InsetGroup

/** Per-metric personal baseline, normal range, today, % change and 28-day trend. */
@Composable
fun InsightsTrendsScreen(vm: InsightsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    val cfg = vm.config
    RefreshOnResume(vm::refresh)
    InsightsScaffold(
        title = stringResource(R.string.insights_trends),
        tag = "insights.trends",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(cfg.disclaimers["general"])
    ) {
        if (!insightsGate(ui, "insights.trends", needsHealth = false)) return@InsightsScaffold
        val snap = ui.snapshot ?: return@InsightsScaffold
        if (snap.baselines.isEmpty()) {
            item(key = "empty") { StateCard(Icons.Outlined.Insights, stringResource(R.string.insights_trends_empty), "", "insights.trends.empty") }
            return@InsightsScaffold
        }
        item(key = "list") {
            InsetGroup(dividerInset = 16.dp, modifier = Modifier.testTag("insights.trends.list")) {
                snap.baselines.forEach { m -> row { TrendRow(m) } }
            }
        }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.baselines(cfg, ui.snapshot), onDismiss = { info = false })
}

@Composable
private fun TrendRow(m: MetricInsight) {
    val b = m.baseline
    val metric = m.metric
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp).testTag("insights.trends.${metric.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(metric.label, fontSize = 16.sp)
            if (!b.ok) {
                Text(stringResource(R.string.insights_trends_learning, b.n, b.needed), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
            } else {
                Text(
                    stringResource(
                        R.string.insights_trends_usual,
                        InsightsFormat.metricValue(metric.id, metric.unit, b.mean),
                        InsightsFormat.metricValue(metric.id, metric.unit, b.low),
                        InsightsFormat.metricValue(metric.id, metric.unit, b.high)
                    ),
                    fontSize = 13.sp,
                    color = AyuvoColors.secondaryLabel()
                )
                trendLine(m)?.let { Text(it, fontSize = 13.sp, color = AyuvoColors.secondaryLabel()) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(if (metric.recent == "mean7") R.string.insights_trends_last7 else R.string.insights_trends_today, InsightsFormat.metricValue(metric.id, metric.unit, b.recent)), fontSize = 15.sp)
            if (b.ok && b.pct != null) {
                val color = when {
                    m.trend.direction == "declining" -> AyuvoPalette.Warning
                    m.trend.direction == "improving" -> AyuvoPalette.Success
                    else -> AyuvoColors.secondaryLabel()
                }
                Text(InsightsFormat.signed(b.pct, 1) + "%", fontSize = 13.sp, color = color)
            }
        }
    }
}

@Composable
private fun trendLine(m: MetricInsight): String? {
    val t = m.trend
    if (t.status != "ok") return null
    val arrow = when (t.direction) {
        "improving" -> "↗ " + stringResource(R.string.insights_trend_improving)
        "declining" -> "↘ " + stringResource(R.string.insights_trend_declining)
        "changing" -> "↕ " + stringResource(R.string.insights_trend_changing)
        else -> "→ " + stringResource(R.string.insights_trend_stable)
    }
    return arrow + " · " + stringResource(R.string.insights_trend_per_week, InsightsFormat.signed(t.slopePctPerWeek, 1))
}
