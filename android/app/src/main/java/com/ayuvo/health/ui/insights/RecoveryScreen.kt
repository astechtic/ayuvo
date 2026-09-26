package com.ayuvo.health.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.insights.InsightsSnapshot
import com.ayuvo.health.insights.RecoveryResult
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Daily Recovery (docs/insights.md): score, signals, components, training load, 30-day chart. */
@Composable
fun RecoveryScreen(vm: InsightsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    val cfg = vm.config
    RefreshOnResume(vm::refresh)
    InsightsScaffold(
        title = stringResource(R.string.insights_recovery),
        tag = "insights.recovery",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(cfg.disclaimers["general"], cfg.disclaimers["background"])
    ) {
        val snap = ui.snapshot
        if (!insightsGate(ui, "insights.recovery")) return@InsightsScaffold
        snap ?: return@InsightsScaffold
        val r = snap.recovery
        item(key = "score") { RecoveryHero(r, cfg) }
        if (r.ok) {
            item(key = "signals") { SignalsCard(r) }
            item(key = "components") { ComponentsGroup(r, cfg) }
            r.load?.let { load ->
                item(key = "load") {
                    InsetGroup(header = stringResource(R.string.insights_training_load), dividerInset = 16.dp) {
                        row {
                            KeyValueRow(
                                load.label,
                                stringResource(R.string.insights_training_load_value, InsightsFormat.number(load.load), InsightsFormat.number(load.mean28d))
                            )
                        }
                    }
                }
            }
            item(key = "explain") {
                ExplainSection(
                    state = ui.explanations[vm.explanationKey("recovery")] ?: ExplainUi.Idle,
                    availability = ui.ai,
                    disclaimer = cfg.disclaimers["ai"],
                    tag = "insights.recovery",
                    onExplain = { vm.explain("recovery") }
                )
            }
        }
        item(key = "chart") { RecoveryChart(snap) }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.recovery(cfg, ui.snapshot), onDismiss = { info = false })
}

/** Shared "off / health not connected / loading" states; false when the screen has nothing else to show. */
internal fun androidx.compose.foundation.lazy.LazyListScope.insightsGate(ui: InsightsUiState, tag: String, needsHealth: Boolean = true): Boolean {
    val snap = ui.snapshot
    when {
        !ui.enabled -> item(key = "off") {
            StateCard(Icons.Outlined.Insights, stringResource(R.string.insights_off), stringResource(R.string.insights_off_body), "$tag.off")
        }
        snap == null -> item(key = "loading") {
            StateCard(Icons.Outlined.Insights, stringResource(R.string.insights_loading), "", "$tag.loading")
        }
        needsHealth && !snap.healthEnabled -> item(key = "health-off") {
            StateCard(Icons.Outlined.MonitorHeart, stringResource(R.string.insights_health_off), stringResource(R.string.insights_health_off_body), "$tag.healthOff")
        }
        else -> return true
    }
    return false
}

@Composable
private fun RecoveryHero(r: RecoveryResult, cfg: InsightsConfig) {
    when (r.status) {
        "collecting" -> StateCard(
            Icons.Outlined.Insights,
            stringResource(R.string.insights_learning_baseline, r.collecting?.have ?: 0, r.collecting?.need ?: cfg.metric("sleep").minPoints),
            stringResource(R.string.insights_learning_baseline_body),
            "insights.recovery.collecting"
        )
        "no_sleep" -> StateCard(Icons.Outlined.Bedtime, stringResource(R.string.insights_no_sleep), stringResource(R.string.insights_no_sleep_body), "insights.recovery.noSleep")
        "no_heart_data" -> StateCard(Icons.Outlined.MonitorHeart, stringResource(R.string.insights_no_heart), stringResource(R.string.insights_no_heart_body), "insights.recovery.noHeart")
        else -> SurfaceCard(modifier = Modifier.testTag("insights.recovery.score")) {
            val color = InsightsFormat.recoveryColor(r.label)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(r.score, color, "/ 100")
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.labelText.orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(stringResource(R.string.insights_recommendation) + ": " + r.recommendation.orEmpty(), fontSize = 15.sp)
                    r.confidence?.let { Chip(stringResource(R.string.insights_confidence, confidenceText(it)), AyuvoColors.secondaryLabel()) }
                }
            }
        }
    }
}

@Composable
internal fun confidenceText(c: String): String = stringResource(
    when (c) {
        "high" -> R.string.insights_confidence_high
        "medium" -> R.string.insights_confidence_medium
        else -> R.string.insights_confidence_low
    }
)

@Composable
private fun SignalsCard(r: RecoveryResult) {
    SurfaceCard(modifier = Modifier.testTag("insights.recovery.signals"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.positives.isEmpty() && r.negatives.isEmpty()) {
            Text(stringResource(R.string.insights_signals_none), fontSize = 15.sp, color = AyuvoColors.secondaryLabel())
        }
        if (r.positives.isNotEmpty()) {
            Text(stringResource(R.string.insights_signals_positive), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
            r.positives.forEach { SignalRow(it.text, InsightsFormat.signed(it.impact), AyuvoPalette.Success) }
        }
        if (r.negatives.isNotEmpty()) {
            Text(stringResource(R.string.insights_signals_negative), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
            r.negatives.forEach { SignalRow(it.text, InsightsFormat.signed(it.impact), AyuvoPalette.Warning) }
        }
    }
}

@Composable
private fun SignalRow(text: String, chip: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Chip(chip, color)
    }
}

@Composable
private fun ComponentsGroup(r: RecoveryResult, cfg: InsightsConfig) {
    val usedDaily = stringResource(R.string.insights_daily_value_used)
    InsetGroup(header = stringResource(R.string.insights_components), dividerInset = 16.dp, modifier = Modifier.testTag("insights.recovery.components")) {
        r.components.forEach { c ->
            val m = cfg.metric(c.id)
            row {
                val today = InsightsFormat.metricValue(m.id, m.unit, c.value)
                val secondary = when {
                    c.available -> stringResource(R.string.insights_baseline_value, InsightsFormat.metricValue(m.id, m.unit, c.baseline)) +
                        (if (c.fallback) " · $usedDaily" else "")
                    c.value == null -> null
                    else -> stringResource(R.string.insights_learning_nights, c.baselineN, m.minPoints)
                }
                ValueWithBaselineRow(m.label, today, secondary, dimmed = !c.available)
            }
        }
    }
}

@Composable
private fun RecoveryChart(snap: InsightsSnapshot) {
    val zone = remember { ZoneId.systemDefault() }
    val history = snap.recoveryHistory
    val points = history.map { r ->
        val start = r.day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = r.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val s = r.score?.toDouble()
        if (s == null) HealthChartPoint(start, end) else HealthChartPoint(start, end, sum = s, avg = s, min = s, max = s, count = 1)
    }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val labels = listOf(0, 7, 14, 21, 29).filter { it < history.size }.map { it to history[it].day.format(fmt) }
    var selected by remember { mutableStateOf<Int?>(null) }
    ChartCard(stringResource(R.string.insights_last_30_days), points.any { it.count > 0 }, "insights.recovery.chart") {
        HealthBucketChart(
            points = points,
            style = HealthChartStyle.BAR,
            color = InsightsFormat.Insights,
            xLabels = labels,
            formatValue = { InsightsFormat.number(it) },
            selected = selected,
            onSelect = { selected = it },
            summary = stringResource(R.string.insights_chart_summary, stringResource(R.string.insights_recovery), points.count { it.count > 0 })
        )
    }
}
