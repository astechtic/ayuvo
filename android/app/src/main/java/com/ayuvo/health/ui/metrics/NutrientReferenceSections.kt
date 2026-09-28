package com.ayuvo.health.ui.metrics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ayuvo.health.AppLinks
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.nutrients.NutrientChartRules
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.nutrients.NutrientReference
import com.ayuvo.health.nutrients.NutrientRuleKind
import com.ayuvo.health.nutrients.ReferenceLines
import com.ayuvo.health.ui.charts.ChartReferenceLine
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import java.time.LocalDate

/**
 * The reference-line pieces every nutrient chart shares (docs/nutrients.md §5 and §5a): the
 * `nutrient:<key>` detail and the health nutrition types (`dietary_*`) draw the same dashed rules,
 * the same "Today X of Y" Day line, the same About and the same Learn more link.
 */
object NutrientReferenceSections {
    fun openUrl(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    /**
     * Display name of a reference key: the app's nutrient name for tracked and sports keys, else
     * the reference `name` (copper, niacin, … have no app string).
     */
    fun name(context: Context, key: String): String = NutrientFields.displayName(context, key)
}

/** [NutrientFields.displayName] in composition. */
@Composable
fun nutrientDisplayName(key: String): String = NutrientFields.displayName(LocalContext.current, key)

/**
 * Chart rules for [lines] on [range] (none on D): Recommended / Your goal in [tint], Upper limit
 * and Limit in the warning colour, each labelled with its value ([format]).
 */
@Composable
fun nutrientChartReferenceLines(
    lines: ReferenceLines?,
    range: MetricRange,
    tint: Color,
    format: (Double) -> String = { NutrientFormat.amount(it) }
): List<ChartReferenceLine> {
    val labels = mapOf(
        NutrientRuleKind.RECOMMENDED to stringResource(R.string.nutrients_line_recommended),
        NutrientRuleKind.GOAL to stringResource(R.string.nutrients_line_goal),
        NutrientRuleKind.UPPER_LIMIT to stringResource(R.string.nutrients_line_upper_limit),
        NutrientRuleKind.LIMIT to stringResource(R.string.nutrients_line_limit)
    )
    val warning = AyuvoPalette.Warning
    return NutrientChartRules.rules(lines, range).map { r ->
        ChartReferenceLine(r.value, "${labels.getValue(r.kind)} ${format(r.value)}", if (r.isWarning) warning else tint)
    }
}

/** "Today 12 of 15 mcg" under a Day chart (the reference rules are hidden on D). */
@Composable
fun NutrientDayTargetLine(
    total: Double?,
    lines: ReferenceLines?,
    anchor: LocalDate,
    unit: String,
    format: (Double?) -> String = { NutrientFormat.amount(it) }
) {
    val target = NutrientChartRules.dayTarget(lines)
    val day = if (anchor == LocalDate.now()) stringResource(R.string.nutrients_today) else stringResource(R.string.nutrients_this_day)
    val text = if (target != null) {
        stringResource(R.string.nutrients_day_of_target, day, format(total), format(target), unit)
    } else {
        val value = format(total)
        stringResource(R.string.nutrients_day_total, day, if (value == NutrientFormat.MISSING) value else "$value $unit")
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().testTag("nutrient.dayTarget"),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * About for a nutrient chart: the reference summary, the values for the user's age band and sex,
 * the upper-limit scope note, the limit basis, the user's goal, the notes, the source titles and
 * the "not personal medical advice" line.
 */
@Composable
fun NutrientAboutGroup(
    key: String,
    lines: ReferenceLines?,
    customGoal: Int?,
    unit: String,
    modifier: Modifier = Modifier,
    format: (Double?) -> String = { NutrientFormat.amount(it) }
) {
    val ref = NutrientReference.active
    val spec = ref?.byKey?.get(key)
    val withUnit: (Double?) -> String = { v -> format(v).let { if (it == NutrientFormat.MISSING) it else "$it $unit" } }
    val sexText = when (lines?.sex) {
        "male" -> stringResource(R.string.nutrients_sex_male)
        "female" -> stringResource(R.string.nutrients_sex_female)
        else -> stringResource(R.string.nutrients_sex_unknown)
    }
    val forYou = lines?.let { stringResource(R.string.nutrients_for_you, it.band, sexText) }
    InsetGroup(modifier = modifier.testTag("metric.about"), header = stringResource(R.string.metric_about), dividerInset = 16.dp) {
        spec?.summary?.takeIf { it.isNotBlank() }?.let { summary -> row { NutrientAboutText(summary) } }
        lines?.referenceRecommended?.let { v ->
            row {
                GroupRow(
                    title = stringResource(if (lines.recommendedKind == "AI") R.string.nutrients_adequate_intake else R.string.nutrients_recommended_rda),
                    subtitle = forYou,
                    value = withUnit(v),
                    trailing = RowTrailing.None
                )
            }
        }
        lines?.upperLimit?.let { v ->
            row {
                GroupRow(
                    title = stringResource(R.string.nutrients_upper_limit_title),
                    subtitle = spec?.upperLimit?.note?.takeIf { lines.upperLimitScope != "all_sources" } ?: forYou,
                    value = withUnit(v),
                    trailing = RowTrailing.None
                )
            }
        }
        lines?.referenceLimit?.let { v ->
            row {
                GroupRow(
                    title = stringResource(R.string.nutrients_limit_title),
                    subtitle = spec?.limit?.basis,
                    value = withUnit(v),
                    trailing = RowTrailing.None
                )
            }
        }
        if (lines != null && lines.referenceLimit == null && spec?.limit?.kind == "pct_energy") {
            row { NutrientAboutText(stringResource(R.string.nutrients_limit_needs_calories)) }
        }
        customGoal?.let { g ->
            row { GroupRow(title = stringResource(R.string.nutrients_line_goal), value = "$g $unit", trailing = RowTrailing.None) }
        }
        spec?.notes?.takeIf { it.isNotEmpty() }?.let { notes -> row { NutrientAboutText(notes.joinToString("\n")) } }
        val sources = spec?.sourceIds?.mapNotNull { ref.sources[it] }.orEmpty()
        if (sources.isNotEmpty()) {
            row {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.nutrients_sources), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
                    sources.forEach { s ->
                        Text("${s.title} (${s.publisher})", fontSize = 13.sp, lineHeight = 17.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        row { NutrientAboutText(stringResource(R.string.nutrients_reference_disclaimer, ref?.populationNote.orEmpty())) }
    }
}

/** "Learn more about ‹name›" → the website guide `https://ayuvo-health.web.app/nutrients/<slug>`. */
@Composable
fun NutrientLearnMoreGroup(name: String, slug: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    InsetGroup(modifier = modifier) {
        row {
            GroupRow(
                title = stringResource(R.string.nutrients_learn_more, name),
                modifier = Modifier.testTag("nutrient.learnMore"),
                trailing = RowTrailing.Custom {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = AyuvoColors.tertiaryLabel())
                },
                onClick = { NutrientReferenceSections.openUrl(context, AppLinks.nutrientUrl(slug)) }
            )
        }
    }
}

@Composable
private fun NutrientAboutText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}
