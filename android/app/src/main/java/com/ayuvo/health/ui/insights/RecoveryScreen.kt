package com.ayuvo.health.ui.insights

import androidx.compose.ui.platform.LocalContext
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
        disclaimers = listOfNotNull(InsightsText.disclaimer(LocalContext.current, cfg, "general"), InsightsText.disclaimer(LocalContext.current, cfg, "background"))
    ) {
        val snap = ui.snapshot
        if (!insightsGate(ui, "insights.recovery")) return@InsightsScaffold
        snap ?: return@InsightsScaffold
        val r = snap.recovery
        item(key = "score") { RecoveryHero(r, cfg) }
        if (r.ok) {
            r.v2?.let { v2 -> item(key = "summary") { RecoverySummaryCard(v2) } }
            item(key = "signals") { SignalsCard(r) }
            item(key = "components") {
                // docs/camera-vitals.md §7: metrics of this day that came from a finger camera scan.
                val fromScan = snap.scanFallback.filterValues { r.day in it }.keys
                if (r.v2 != null) V2ComponentsGroup(r.v2) else ComponentsGroup(r, cfg, fromScan)
            }
            r.load?.let { load ->
                item(key = "load") {
                    InsetGroup(header = stringResource(R.string.insights_training_load), dividerInset = 16.dp) {
                        row {
                            if (r.v2 != null) {
                                val l = r.v2.m("load")
                                KeyValueRow(
                                    AnalyticsText.loadState(LocalContext.current, l.s("state")),
                                    stringResource(R.string.insights_training_load_value, InsightsFormat.number(l.n("acute")), InsightsFormat.number(l.n("chronic")))
                                )
                            } else {
                                KeyValueRow(
                                    InsightsText.trainingLoadLabel(LocalContext.current, load.category, load.label),
                                    stringResource(R.string.insights_training_load_value, InsightsFormat.number(load.load), InsightsFormat.number(load.mean28d))
                                )
                            }
                        }
                    }
                }
            }
            item(key = "explain") {
                ExplainSection(
                    state = ui.explanations[vm.explanationKey("recovery")] ?: ExplainUi.Idle,
                    availability = ui.ai,
                    disclaimer = InsightsText.disclaimer(LocalContext.current, cfg, "ai"),
                    tag = "insights.recovery",
                    onExplain = { vm.explain("recovery") }
                )
            }
        }
        item(key = "chart") { RecoveryChart(snap) }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.recovery(LocalContext.current, cfg, ui.snapshot), onDismiss = { info = false })
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
                    Text(InsightsText.bandLabel(LocalContext.current, r.label, r.labelText), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(stringResource(R.string.insights_recommendation) + ": " + InsightsText.bandRecommendation(LocalContext.current, r.label, r.recommendation), fontSize = 15.sp)
                    val pct = AnalyticsText.percent(r.confidenceScore)
                    if (pct != null) {
                        Chip(stringResource(R.string.analytics_confidence, pct), AyuvoColors.secondaryLabel())
                        AnalyticsText.percent(r.v2?.get("coverage"))?.let { Text(stringResource(R.string.analytics_coverage, it), fontSize = 13.sp, color = AyuvoColors.secondaryLabel()) }
                    } else {
                        r.confidence?.let { Chip(stringResource(R.string.insights_confidence, confidenceText(it)), AyuvoColors.secondaryLabel()) }
                    }
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
    val context = LocalContext.current
    SurfaceCard(modifier = Modifier.testTag("insights.recovery.signals"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.positives.isEmpty() && r.negatives.isEmpty()) {
            Text(stringResource(R.string.insights_signals_none), fontSize = 15.sp, color = AyuvoColors.secondaryLabel())
        }
        if (r.positives.isNotEmpty()) {
            Text(stringResource(R.string.insights_signals_positive), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
            r.positives.forEach { SignalRow(InsightsText.signal(context, InsightsConfig.active, r, it), InsightsFormat.signed(it.impact), AyuvoPalette.Success) }
        }
        if (r.negatives.isNotEmpty()) {
            Text(stringResource(R.string.insights_signals_negative), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
            r.negatives.forEach { SignalRow(InsightsText.signal(context, InsightsConfig.active, r, it), InsightsFormat.signed(it.impact), AyuvoPalette.Warning) }
        }
    }
}

/** Recovery v2: the explanation built only from the drivers, plus the data-quality notes. */
@Composable
private fun RecoverySummaryCard(v2: Map<String, Any?>) {
    val context = LocalContext.current
    SurfaceCard(modifier = Modifier.testTag("insights.recovery.summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.analytics_summary), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
        AnalyticsText.summary(context, v2.ms("drivers"))?.let { Text(it, fontSize = 15.sp) }
        val notes = v2.ms("warnings").mapNotNull { it.s("code") }.distinct().mapNotNull { AnalyticsText.warning(context, it) }
        if (notes.isNotEmpty()) {
            Text(stringResource(R.string.analytics_warnings), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
            notes.forEach { Text("• $it", fontSize = 13.sp, color = AyuvoColors.secondaryLabel()) }
        }
    }
}

/** Recovery v2 components: today vs personal baseline (median) with the robust z. */
@Composable
private fun V2ComponentsGroup(v2: Map<String, Any?>) {
    val context = LocalContext.current
    val metricOf = mapOf("sleep" to "sleep_duration")
    InsetGroup(header = stringResource(R.string.insights_components), dividerInset = 16.dp, modifier = Modifier.testTag("insights.recovery.components")) {
        v2.ms("components").forEach { c ->
            val id = c.s("id").orEmpty()
            val unit = c.s("unit").orEmpty()
            fun fmt(v: Double?): String = when {
                v == null -> InsightsFormat.MISSING
                unit == "min" -> InsightsFormat.duration(v)
                unit == "br/min" || unit == "°C" -> "${InsightsFormat.number(v, if (unit == "°C") 2 else 1)} $unit"
                unit == "%" -> "${InsightsFormat.number(v)}%"
                else -> "${InsightsFormat.number(v)} $unit"
            }
            row {
                val available = c["available"] == true
                val secondary = when {
                    !available && c.n("value") == null -> null
                    !available -> stringResource(R.string.analytics_status_history)
                    id == "sleep" -> stringResource(R.string.analytics_sleep_need) + " " + fmt(c.n("baseline"))
                    else -> stringResource(R.string.insights_baseline_value, fmt(c.n("baseline"))) +
                        (c.n("z")?.let { " · " + stringResource(R.string.analytics_sd, InsightsFormat.signed(it, 1)) } ?: "")
                }
                ValueWithBaselineRow(AnalyticsText.metricLabel(context, metricOf[id] ?: id), fmt(c.n("value")), secondary, dimmed = !available)
            }
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
private fun ComponentsGroup(r: RecoveryResult, cfg: InsightsConfig, fromScan: Set<String> = emptySet()) {
    val usedDaily = stringResource(R.string.insights_daily_value_used)
    val usedScan = stringResource(R.string.camvitals_insights_scan_footnote)
    InsetGroup(header = stringResource(R.string.insights_components), dividerInset = 16.dp, modifier = Modifier.testTag("insights.recovery.components")) {
        r.components.forEach { c ->
            val m = cfg.metric(c.id)
            row {
                val today = InsightsFormat.metricValue(m.id, m.unit, c.value)
                val secondary = when {
                    c.available -> stringResource(R.string.insights_baseline_value, InsightsFormat.metricValue(m.id, m.unit, c.baseline)) +
                        (if (c.fallback) " · $usedDaily" else "") +
                        (if (componentMetric(cfg, c.id) in fromScan) " · $usedScan" else "")
                    c.value == null -> null
                    else -> stringResource(R.string.insights_learning_nights, c.baselineN, m.minPoints)
                }
                ValueWithBaselineRow(InsightsText.metricLabel(LocalContext.current, m), today, secondary, dimmed = !c.available)
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

/** The insights series a Recovery component reads (its `metric`, else its id). */
private fun componentMetric(cfg: InsightsConfig, componentId: String): String =
    cfg.recovery.components.firstOrNull { it.id == componentId }?.metric ?: componentId
