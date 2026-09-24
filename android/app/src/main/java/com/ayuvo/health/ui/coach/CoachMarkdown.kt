package com.ayuvo.health.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Renders an assistant reply (docs/coach.md §4). Blocks come from `CoachReference.parseBlocks`, the
 * shared parser, so Android and iOS split the same text the same way; inline emphasis, code spans
 * and links stay with [inlineMarkdown].
 *
 * Replaces the hand-rolled parser that used to live in `CoachScreen.kt` and only knew headings,
 * bullets, numbers and code fences. New here: tables, block quotes, nested lists, rules, task lists,
 * charts, and links that are actually tappable.
 */
@Composable
fun CoachMarkdown(content: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(content) {
        CoachReference.parseBlocks(content).arr("blocks").orEmpty().filterIsInstance<JsonObject>()
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            MarkdownBlock(block, color)
        }
    }
}

@Composable
private fun MarkdownBlock(block: JsonObject, color: Color) {
    val text = block.str("text").orEmpty()
    when (block.str("kind")) {
        "heading" -> Text(
            inline(text, color),
            fontSize = headingSize(block.int("level") ?: 1),
            fontWeight = FontWeight.Bold,
            color = color
        )

        "paragraph" -> Body(text, color)

        "bullet" -> ListRow(block.int("depth") ?: 0, "•", text, color)

        "numbered" -> ListRow(block.int("depth") ?: 0, (block.str("marker") ?: "") + ".", text, color)

        "task" -> {
            val checked = (block["checked"] as? JsonPrimitive)?.booleanOrNull == true
            ListRow(block.int("depth") ?: 0, if (checked) "☑" else "☐", text, color,
                    markerColor = if (checked) AppColors.Calorie else color.copy(alpha = 0.6f))
        }

        "quote" -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (((block.int("depth") ?: 1) - 1) * 12).dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(AppColors.Calorie.copy(alpha = 0.5f))
            )
            Spacer(Modifier.width(10.dp))
            Text(inline(text, color), fontSize = 15.sp, color = color.copy(alpha = 0.75f))
        }

        "code" -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.10f))
                .padding(10.dp)
        ) {
            block.str("lang")?.let {
                Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = color.copy(alpha = 0.5f))
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = color)
            }
        }

        "table" -> MarkdownTable(block, color)

        "rule" -> HorizontalDivider(color = color.copy(alpha = 0.2f))

        "chart" -> {
            val ok = (block["ok"] as? JsonPrimitive)?.booleanOrNull == true
            val spec = block["spec"] as? JsonObject
            if (ok && spec != null) {
                CoachChartBlock(spec)
            } else {
                // A spec that does not parse renders as its own text, never as a guessed chart
                // (docs/coach.md rule 1).
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.10f))
                        .padding(10.dp)
                ) {
                    Text(
                        stringResource(chartFailureRes(block.str("reason"))),
                        fontSize = 12.sp,
                        color = color.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                    }
                }
            }
        }

        else -> Body(text, color)
    }
}

/**
 * Why the chart is not drawn, in the four groups of docs/coach.md §5. Saying which one it is turns
 * "it did not work" into something the user can act on (or report).
 */
private fun chartFailureRes(reason: String?): Int = when (CoachReference.chartReasonGroup(reason)) {
    "unsupported" -> R.string.coach_chart_failed_unsupported
    "too_big" -> R.string.coach_chart_failed_too_big
    "no_readings" -> R.string.coach_chart_failed_no_readings
    else -> R.string.coach_chart_failed_malformed
}

@Composable
private fun Body(text: String, color: Color) {
    val annotated = inline(text, color)
    val uriHandler = LocalUriHandler.current
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = LocalTextStyle.current.copy(fontSize = 16.sp, color = color),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                runCatching { uriHandler.openUri(it.item) }
            }
        }
    )
}

@Composable
private fun ListRow(depth: Int, marker: String, text: String, color: Color, markerColor: Color = color) {
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            marker,
            fontSize = 15.sp,
            color = markerColor.copy(alpha = 0.8f),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Body(text, color)
    }
}

/** A table from the assistant. Scrolls sideways rather than squeezing columns on a phone. */
@Composable
private fun MarkdownTable(block: JsonObject, color: Color) {
    val headers = MedicationJson.strings(block["headers"])
    val aligns = MedicationJson.strings(block["aligns"])
    val rows = (block["rows"] as? JsonArray).orEmpty().map { MedicationJson.strings(it) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.06f))
                .border(0.6.dp, color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
        ) {
            TableRow(headers, aligns, color, isHeader = true)
            rows.forEachIndexed { index, cells ->
                HorizontalDivider(color = color.copy(alpha = 0.12f))
                TableRow(cells, aligns, color, isHeader = false, striped = index % 2 == 1)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    aligns: List<String>,
    color: Color,
    isHeader: Boolean,
    striped: Boolean = false
) {
    Row(
        Modifier.background(if (striped) color.copy(alpha = 0.04f) else Color.Transparent),
        verticalAlignment = Alignment.Top
    ) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell,
                fontSize = if (isHeader) 12.sp else 13.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHeader) color.copy(alpha = 0.7f) else color,
                textAlign = when (aligns.getOrNull(index)) {
                    "center" -> TextAlign.Center
                    "right" -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun inline(text: String, color: Color): AnnotatedString =
    inlineMarkdown(text, linkColor = AppColors.Calorie, codeBg = color.copy(alpha = 0.10f))

private fun headingSize(level: Int) = when (level) {
    1 -> 20.sp
    2 -> 17.sp
    3 -> 15.sp
    else -> 14.sp
}
