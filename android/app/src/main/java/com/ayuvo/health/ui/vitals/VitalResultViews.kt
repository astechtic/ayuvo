package com.ayuvo.health.ui.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.vitals.engine.VitalsConfig
import java.util.Locale
import kotlin.math.abs

/** Accent colour of a scan mode. */
internal fun vitalsTint(): Color = AyuvoPalette.Heart

internal fun gradeColor(grade: String): Color = when (grade) {
    "excellent", "good" -> AyuvoPalette.Success
    "fair" -> AyuvoPalette.Warning
    else -> AyuvoPalette.Destructive
}

private fun classificationColor(id: String): Color = when (id) {
    "measured" -> AyuvoPalette.Success
    "calculated" -> AyuvoPalette.Hydration
    "estimated" -> AyuvoPalette.Activity
    "experimental" -> AyuvoPalette.Body
    else -> AyuvoPalette.Other
}

/**
 * The results layout of PPG.md §19 / docs/camera-vitals.md §7.1: signal quality (grade, score, expandable components),
 * the metric rows, the processed waveform with beat markers and the disclaimer. Shared by the scan flow and the detail.
 */
@Composable
internal fun VitalResultContent(result: VitalResultUi, cfg: VitalsConfig, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        QualityCard(result, cfg)
        result.rejectReason?.let { reason ->
            SurfaceCard(modifier = Modifier.testTag("vitals.rejected")) {
                Text(stringResource(R.string.camvitals_rejected_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(VitalsText.reason(context, cfg, reason), color = AyuvoColors.secondaryLabel(), fontSize = 14.sp)
            }
        }
        InsetGroup(
            header = stringResource(R.string.camvitals_metrics),
            footer = stringResource(R.string.camvitals_indicator_footer),
            modifier = Modifier.testTag("vitals.metrics")
        ) {
            result.metrics.forEach { m -> row { MetricResultRow(m, cfg) } }
        }
        if (result.waveform.isNotEmpty()) {
            Column {
                Text(
                    stringResource(R.string.camvitals_waveform).uppercase(),
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                    fontSize = 12.sp,
                    color = AyuvoColors.secondaryLabel()
                )
                SurfaceCard(modifier = Modifier.testTag("vitals.waveform")) {
                    WaveformChart(result.waveform, result.mask, result.beatsMs, result.fs, vitalsTint())
                }
                Text(
                    stringResource(R.string.camvitals_waveform_footer),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    fontSize = 12.sp,
                    color = AyuvoColors.secondaryLabel()
                )
            }
        }
        Text(
            VitalsText.disclaimer(context, cfg),
            modifier = Modifier.padding(horizontal = 16.dp).testTag("vitals.disclaimer"),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = AyuvoColors.secondaryLabel()
        )
    }
}

@Composable
private fun QualityCard(result: VitalResultUi, cfg: VitalsConfig) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val color = gradeColor(result.grade)
    SurfaceCard(modifier = Modifier.testTag("vitals.quality")) {
        Text(stringResource(R.string.camvitals_signal_quality), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
        Row(verticalAlignment = Alignment.Bottom) {
            Text(VitalsText.grade(context, cfg, result.grade), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.camvitals_score, String.format(Locale.getDefault(), "%.0f", result.qualityScore)),
                fontSize = 15.sp,
                color = AyuvoColors.secondaryLabel(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (result.qualityScore / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = AyuvoColors.fill()
        )
        if (result.components.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("vitals.quality.toggle")) {
                Text(stringResource(if (expanded) R.string.camvitals_hide_details else R.string.camvitals_show_details))
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.components.forEach { (id, v) ->
                        Column {
                            Row {
                                Text(VitalsText.component(context, id), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(String.format(Locale.getDefault(), "%.0f%%", v * 100.0), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
                            }
                            LinearProgressIndicator(
                                progress = { v.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = AyuvoPalette.Hydration,
                                trackColor = AyuvoColors.fill()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricResultRow(m: VitalMetricUi, cfg: VitalsConfig) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("vitals.metric.${m.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(VitalsText.metricTitle(context, cfg, m.id), fontSize = 16.sp, modifier = Modifier.weight(1f))
            val value = VitalsText.value(m)
            if (value != null) {
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Text(VitalsText.unit(context, m.unit), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
            } else {
                Text(stringResource(R.string.camvitals_unavailable), fontSize = 15.sp, color = AyuvoColors.secondaryLabel())
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClassificationBadge(m.classification, VitalsText.classification(context, cfg, m.classification))
            val detail = when {
                !m.valid -> m.reason?.let { VitalsText.reason(context, cfg, it) }
                m.band != null -> listOfNotNull(VitalsText.band(context, cfg, m.band), VitalsText.confidence(context, m.confidenceLabel)).joinToString(" · ")
                else -> VitalsText.confidence(context, m.confidenceLabel)
            }
            if (detail != null) Text(detail, fontSize = 13.sp, lineHeight = 17.sp, color = AyuvoColors.secondaryLabel())
        }
    }
}

@Composable
internal fun ClassificationBadge(id: String, label: String) {
    val color = classificationColor(id)
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

/** Processed waveform at [fs] Hz, scrollable, with masked stretches shaded and beats as dots. */
@Composable
internal fun WaveformChart(wave: FloatArray, mask: BooleanArray, beatsMs: FloatArray, fs: Double, color: Color) {
    val seconds = (wave.size / fs).coerceAtLeast(1.0)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width((seconds * 56).dp).height(140.dp)) {
            val n = wave.size
            if (n < 2) return@Canvas
            var amp = 1e-9f
            for (v in wave) if (abs(v) > amp) amp = abs(v)
            val mid = size.height / 2f
            val scale = (size.height / 2f - 8f) / amp
            val dx = size.width / (n - 1)
            // One-second grid.
            var s = 0
            while (s <= seconds) {
                val x = (s * fs).toFloat() * dx
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                s += 1
            }
            var i = 0
            while (i < mask.size && i < n) {
                if (mask[i]) {
                    var j = i
                    while (j < mask.size && j < n && mask[j]) j++
                    drawRect(shade, Offset(i * dx, 0f), Size((j - i) * dx, size.height))
                    i = j
                } else i++
            }
            val path = Path()
            for (k in 0 until n) {
                val p = Offset(k * dx, mid - wave[k] * scale)
                if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (b in beatsMs) {
                val idx = (b / 1000.0 * fs).toFloat()
                val k = idx.toInt().coerceIn(0, n - 1)
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(idx * dx, mid - wave[k] * scale))
            }
        }
    }
}

/** Live waveform (last ~8 s), fitted to the width. */
@Composable
internal fun LiveWaveform(wave: FloatArray, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = wave.size
        if (n < 2) return@Canvas
        var amp = 1e-9f
        for (v in wave) if (abs(v) > amp) amp = abs(v)
        val mid = size.height / 2f
        val scale = (size.height / 2f - 4f) / amp
        val dx = size.width / (n - 1)
        val path = Path()
        for (k in 0 until n) {
            val p = Offset(k * dx, mid - wave[k] * scale)
            if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
