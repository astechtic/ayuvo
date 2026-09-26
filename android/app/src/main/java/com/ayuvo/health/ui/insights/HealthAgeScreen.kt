package com.ayuvo.health.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
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
import com.ayuvo.health.insights.HealthAgePace
import com.ayuvo.health.insights.HealthAgeResult
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.ui.charts.HealthBucketChart
import com.ayuvo.health.ui.charts.HealthChartStyle
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Ayuvo Health Age: estimate vs actual age, pace, marker contributions, 12-week trend, data quality. */
@Composable
fun HealthAgeScreen(vm: InsightsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    val cfg = vm.config
    RefreshOnResume(vm::refresh)
    InsightsScaffold(
        title = stringResource(R.string.insights_health_age),
        tag = "insights.healthAge",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(cfg.disclaimers["health_age"], cfg.disclaimers["general"])
    ) {
        if (!insightsGate(ui, "insights.healthAge")) return@InsightsScaffold
        val snap = ui.snapshot ?: return@InsightsScaffold
        val h = snap.healthAge
        item(key = "hero") { HealthAgeHero(h, snap.pace, cfg) }
        if (h.status == "ok" || h.status == "collecting") {
            item(key = "markers") { MarkersGroup(h, cfg) }
        }
        if (h.ok) {
            item(key = "quality") {
                InsetGroup(header = stringResource(R.string.insights_data_quality), dividerInset = 16.dp) {
                    row { KeyValueRow(stringResource(R.string.insights_markers_used, h.markersAvailable, cfg.healthAge.markers.size), confidenceText(h.confidence ?: "low")) }
                }
            }
            item(key = "explain") {
                ExplainSection(
                    state = ui.explanations[vm.explanationKey("health_age")] ?: ExplainUi.Idle,
                    availability = ui.ai,
                    disclaimer = cfg.disclaimers["ai"],
                    tag = "insights.healthAge",
                    onExplain = { vm.explain("health_age") }
                )
            }
        }
        item(key = "chart") { PaceChart(snap.pace) }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.healthAge(cfg, ui.snapshot), onDismiss = { info = false })
}

@Composable
internal fun paceText(p: HealthAgePace): String? {
    if (!p.ok) return null
    val word = stringResource(
        when (p.direction) {
            "improving" -> R.string.insights_pace_improving
            "declining" -> R.string.insights_pace_declining
            else -> R.string.insights_pace_stable
        }
    )
    return stringResource(R.string.insights_pace, InsightsFormat.number(p.pace, 2)) + " · " + word
}

@Composable
internal fun differenceText(diff: Double?): String {
    val d = diff ?: return InsightsFormat.MISSING
    val r = com.ayuvo.health.insights.InsightsMath.roundTo(d, 1)
    return when {
        r < 0 -> stringResource(R.string.insights_years_younger, InsightsFormat.number(abs(r), 1))
        r > 0 -> stringResource(R.string.insights_years_older, InsightsFormat.number(r, 1))
        else -> stringResource(R.string.insights_years_same)
    }
}

@Composable
private fun HealthAgeHero(h: HealthAgeResult, pace: HealthAgePace, cfg: InsightsConfig) {
    when (h.status) {
        "no_birthday" -> StateCard(Icons.Outlined.Cake, stringResource(R.string.insights_no_birthday), stringResource(R.string.insights_no_birthday_body), "insights.healthAge.noBirthday")
        "unsupported_age" -> StateCard(Icons.Outlined.Cake, stringResource(R.string.insights_unsupported_age), "", "insights.healthAge.unsupported")
        "collecting" -> StateCard(
            Icons.Outlined.Insights,
            stringResource(R.string.insights_collecting_days, h.collecting?.have ?: 0, h.collecting?.need ?: cfg.healthAge.collectingDays),
            cfg.disclaimers["health_age"].orEmpty(),
            "insights.healthAge.collecting"
        )
        else -> SurfaceCard(modifier = Modifier.testTag("insights.healthAge.value"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(InsightsFormat.number(h.healthAge, 1), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = InsightsFormat.Insights)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.insights_actual_age, InsightsFormat.number(h.actualAge, 1)), fontSize = 15.sp, color = AyuvoColors.secondaryLabel())
                }
            }
            Text(differenceText(h.difference), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val paceLine = paceText(pace) ?: stringResource(R.string.insights_pace_collecting, pace.needed, pace.have)
            Chip(paceLine, if (pace.direction == "declining") AyuvoPalette.Warning else if (pace.ok) AyuvoPalette.Success else AyuvoColors.secondaryLabel())
        }
    }
}

@Composable
private fun MarkersGroup(h: HealthAgeResult, cfg: InsightsConfig) {
    InsetGroup(header = stringResource(R.string.insights_markers), dividerInset = 16.dp, modifier = Modifier.testTag("insights.healthAge.markers")) {
        h.markers.forEach { m ->
            val spec = cfg.healthAge.markers.firstOrNull { it.id == m.id }
            row {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(spec?.label ?: m.id, fontSize = 15.sp)
                        Text(
                            if (m.available) InsightsFormat.markerValue(m.id, m.basis, m.value, m.secondaryValue)
                            else stringResource(R.string.insights_marker_days, m.days, m.neededDays),
                            fontSize = 13.sp,
                            color = AyuvoColors.secondaryLabel()
                        )
                    }
                    if (m.available) {
                        val c = m.contributionYears ?: 0.0
                        Chip(
                            stringResource(R.string.insights_marker_years, InsightsFormat.signed(c, 2)),
                            if (c > 0) AyuvoPalette.Warning else AyuvoPalette.Success
                        )
                    } else {
                        Text(InsightsFormat.MISSING, fontSize = 15.sp, color = AyuvoColors.tertiaryLabel())
                    }
                }
            }
        }
    }
}

@Composable
private fun PaceChart(p: HealthAgePace) {
    val zone = remember { ZoneId.systemDefault() }
    val points = p.points.map { pt ->
        val start = pt.day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = pt.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        HealthChartPoint(start, end, sum = pt.healthAge, avg = pt.healthAge, min = pt.healthAge, max = pt.healthAge, count = 1)
    }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val labels = if (p.points.isEmpty()) emptyList() else listOf(0, p.points.size / 2, p.points.size - 1).distinct().map { it to p.points[it].day.format(fmt) }
    var selected by remember { mutableStateOf<Int?>(null) }
    ChartCard(stringResource(R.string.insights_last_12_weeks), points.size >= 2, "insights.healthAge.chart") {
        HealthBucketChart(
            points = points,
            style = HealthChartStyle.LINE,
            color = InsightsFormat.Insights,
            xLabels = labels,
            formatValue = { InsightsFormat.number(it, 1) },
            selected = selected,
            onSelect = { selected = it },
            summary = stringResource(R.string.insights_chart_summary, stringResource(R.string.insights_health_age), points.size)
        )
    }
}
