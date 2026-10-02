package com.ayuvo.health.ui.cycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Insights and statistics (docs/cycle-tracking.md §5): average cycle, typical range and average period; charts of
 * cycle length (with the median line), period length, highest pain per cycle, most logged symptoms and flow by period
 * day — real data only, each with a spoken summary; the rule-based notes, the fertility explainer and the disclaimer.
 */
@Composable
fun CycleInsightsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: CycleViewModel = viewModel(factory = CycleViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val cfg = vm.config
    val stats = ui.snapshot?.stats
    val trends = ui.trends
    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val completed = trends?.cycles.orEmpty().filter { it.cycleLength != null }.takeLast(12)
    val labels = completed.map { fmt.format(LocalDate.parse(it.start)) }
    val dash = "—"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.cycle_insights), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("cycle.insightsScreen"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(stringResource(R.string.cycle_avg_cycle), stats?.cycleMedian?.let { days(it) } ?: dash, Modifier.weight(1f))
                    StatTile(stringResource(R.string.cycle_typical_range), stats?.cycleRange?.let { "${it[0]}–${it[1]}" } ?: dash, Modifier.weight(1f))
                    StatTile(stringResource(R.string.cycle_avg_period), stats?.periodMedian?.let { days(it) } ?: dash, Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.cycle_stats_footer, stats?.cycleCount ?: 0),
                    fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }
            item {
                ChartCard(stringResource(R.string.cycle_chart_cycle_length)) {
                    BarChart(
                        completed.map { it.cycleLength!!.toFloat() }, labels, CycleColors.Period,
                        reference = stats?.cycleMedian?.toFloat(),
                        summary = summary(stringResource(R.string.cycle_chart_cycle_length), labels, completed.map { it.cycleLength?.toString() })
                    )
                }
            }
            item {
                val withPeriod = trends?.cycles.orEmpty().filter { it.periodLength != null }.takeLast(12)
                val l = withPeriod.map { fmt.format(LocalDate.parse(it.start)) }
                ChartCard(stringResource(R.string.cycle_chart_period_length)) {
                    BarChart(withPeriod.map { it.periodLength!!.toFloat() }, l, CycleColors.Period.copy(alpha = 0.7f), null,
                        summary(stringResource(R.string.cycle_chart_period_length), l, withPeriod.map { it.periodLength?.toString() }))
                }
            }
            item {
                val withPain = trends?.cycles.orEmpty().filter { it.painMax != null }.takeLast(12)
                val l = withPain.map { fmt.format(LocalDate.parse(it.start)) }
                ChartCard(stringResource(R.string.cycle_chart_pain)) {
                    BarChart(withPain.map { it.painMax!!.toFloat() }, l, Color(0xFFFF9500), null,
                        summary(stringResource(R.string.cycle_chart_pain), l, withPain.map { it.painMax?.toString() }), maxValue = cfg.limits.painMax.toFloat())
                }
            }
            item {
                val top = trends?.symptomFrequency.orEmpty().take(6)
                val total = trends?.windowCycles ?: 0
                ChartCard(stringResource(R.string.cycle_chart_symptoms)) {
                    if (top.isEmpty()) EmptyChart() else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            top.forEach { f ->
                                val name = CycleText.symptom(context, cfg, f.key)
                                val text = stringResource(R.string.cycle_symptom_cycles, f.cycles, total)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = "$name, $text"
                                }) {
                                    Text(name, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(AyuvoColors.fill())) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(if (total == 0) 0f else f.cycles / total.toFloat())
                                            .clip(RoundedCornerShape(6.dp)).background(CycleColors.Period))
                                    }
                                    Text(text, fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
            item {
                val pattern = trends?.flowPattern.orEmpty()
                val l = pattern.indices.map { (it + 1).toString() }
                val names = cfg.flowLevels.associate { it.rank to CycleText.flow(context, cfg, it.key) }
                ChartCard(stringResource(R.string.cycle_chart_flow)) {
                    BarChart(pattern.map { it.toFloat() }, l, CycleColors.Period, null,
                        summary(stringResource(R.string.cycle_chart_flow), l.map { stringResource(R.string.cycle_day_n, it.toInt()) },
                            pattern.map { names[Math.round(it).toInt()] ?: it.toString() }),
                        maxValue = cfg.flowLevels.maxOf { it.rank }.toFloat(),
                        valueText = { v -> names[Math.round(v)] ?: formatValue(v) })
                    Text(stringResource(R.string.cycle_chart_flow_footer), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                }
            }
            val notes = ui.snapshot?.insights.orEmpty()
            if (notes.isNotEmpty()) {
                item {
                    InsetGroup(header = stringResource(R.string.cycle_notes_header)) {
                        notes.forEach { n ->
                            row {
                                Text(CycleText.insight(context, cfg, n), modifier = Modifier.fillMaxWidth().padding(16.dp), fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
            item {
                InsetGroup(header = stringResource(R.string.cycle_about_estimates)) {
                    row {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.snapshot?.prediction?.basis?.let { b ->
                                Text(CycleText.basis(context, cfg, b), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(CycleText.basisAbout(context, cfg, b), fontSize = 14.sp)
                            }
                            if (ui.showFertility) Text(CycleText.fertilityNote(context, cfg), fontSize = 14.sp)
                            Text(CycleText.disclaimer(context, cfg), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun days(v: Double): String {
    val n = Math.round(v).toInt()
    return pluralStringResource(R.plurals.cycle_days, n, n)
}

private fun summary(title: String, labels: List<String>, values: List<String?>): String =
    title + ": " + labels.zip(values).joinToString("; ") { (l, v) -> "$l ${v ?: "—"}" }

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Text(label, fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(Locale.getDefault()), modifier = Modifier.padding(start = 16.dp, bottom = 6.dp), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        SurfaceCard { content() }
    }
}

@Composable
private fun EmptyChart() {
    Text(stringResource(R.string.cycle_chart_empty), fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(), textAlign = TextAlign.Center)
}

/** Simple vertical bars with optional dashed reference line (median); the whole chart reads as [summary]. */
@Composable
private fun BarChart(
    values: List<Float>, labels: List<String>, color: Color, reference: Float?, summary: String, maxValue: Float? = null,
    valueText: (Float) -> String = ::formatValue
) {
    if (values.isEmpty()) {
        EmptyChart(); return
    }
    val top = maxOf(maxValue ?: 0f, (values.maxOrNull() ?: 1f) * 1.15f, reference ?: 0f, 1f)
    val axisColor = AyuvoColors.separator()
    val refColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Row(Modifier.fillMaxWidth().height(150.dp), verticalAlignment = Alignment.Bottom) {
            Canvas(Modifier.fillMaxSize()) {
                val n = values.size
                val slot = size.width / n
                val barW = (slot * 0.56f).coerceAtMost(28.dp.toPx())
                drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                values.forEachIndexed { i, v ->
                    val h = size.height * (v / top)
                    drawRoundRect(
                        color, topLeft = Offset(slot * i + (slot - barW) / 2, size.height - h), size = Size(barW, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    )
                }
                reference?.let { r ->
                    val y = size.height * (1 - r / top)
                    drawLine(refColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            values.indices.forEach { i ->
                Text(
                    labels.getOrElse(i) { "" } + "\n" + valueText(values[i]),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp, lineHeight = 12.sp,
                    color = AyuvoColors.secondaryLabel(), maxLines = 2
                )
            }
        }
        reference?.let {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.cycle_chart_median_line, formatValue(it)), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        }
    }
}

private fun formatValue(v: Float): String =
    if (v == Math.round(v).toFloat()) Math.round(v).toString() else String.format(Locale.getDefault(), "%.1f", v)
