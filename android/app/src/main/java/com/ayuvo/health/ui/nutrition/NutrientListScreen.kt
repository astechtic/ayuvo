package com.ayuvo.health.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.nutrients.NutrientTotals
import com.ayuvo.health.nutrients.SupplementSnapshot
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.metrics.nutrientDisplayName
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import java.time.LocalDate
import java.time.ZoneId

/** Macros open their app metric; every other nutrient its `nutrient:<key>` chart (docs/nutrients.md §5). */
private fun metricKeyFor(key: String): MetricKey = when (key) {
    NutrientFields.PROTEIN -> MetricKey.App(AppMetricId.PROTEIN)
    NutrientFields.CARBS -> MetricKey.App(AppMetricId.CARBS)
    NutrientFields.FAT -> MetricKey.App(AppMetricId.FAT)
    else -> MetricKey.Nutrient(key)
}

private data class NutrientListRow(val key: String, val total: Double?, val goal: Int?, val unit: String)

/**
 * Browse › Nutrition › All Nutrients: today's food + supplement intake per nutrient against its
 * goal. Nutrients nobody recorded today are listed under "Not Logged Today" without a number.
 * Every row opens its chart.
 */
@Composable
fun NutrientListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit
) {
    val entries by container.foodRepository.entries.collectAsState(initial = emptyList())
    val supplements by container.supplementIntake.snapshots.collectAsState(initial = SupplementSnapshot.EMPTY)
    val profile by container.profileRepository.profile.collectAsState(initial = null)
    val goals by container.prefs.optionalNutrientGoals.collectAsState(initial = OptionalNutrientGoals.Default)
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }
    val rows = remember(entries, supplements, profile, goals) {
        val totals = NutrientTotals(entries, supplements, zone)
        val keys = listOf(NutrientFields.PROTEIN, NutrientFields.CARBS, NutrientFields.FAT) + NutrientFields.REFERENCE_KEYS
        keys.map { k ->
            val goal = NutrientFields.homeTopNutrient(k)?.goal(profile, goals)
            NutrientListRow(k, totals.total(k, today).total, goal, NutrientFields.unit(k))
        }
    }
    val logged = rows.filter { it.total != null }
    val notLogged = rows.filter { it.total == null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.nutrition_all_nutrients), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item(key = "today") {
                InsetGroup(
                    header = stringResource(R.string.nutrition_today_header),
                    footer = if (logged.isEmpty()) stringResource(R.string.nutrition_nothing_logged) else null,
                    dividerInset = AyuvoSpacing.RowH
                ) {
                    logged.forEach { r -> row { NutrientRow(r.key, valueText(r), onOpenMetric) } }
                }
            }
            if (notLogged.isNotEmpty()) {
                item(key = "not-logged") {
                    InsetGroup(header = stringResource(R.string.nutrition_not_logged_header), dividerInset = AyuvoSpacing.RowH) {
                        notLogged.forEach { r ->
                            row {
                                val goalText = r.goal?.takeIf { it > 0 }?.let { stringResource(R.string.metric_goal_label, "$it ${r.unit}") }
                                NutrientRow(r.key, goalText, onOpenMetric)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NutrientRow(key: String, value: String?, onOpenMetric: (MetricKey) -> Unit) {
    val metric = metricKeyFor(key)
    GroupRow(
        title = nutrientDisplayName(key),
        value = value,
        modifier = Modifier.testTag("nutrients.row.$key"),
        trailing = RowTrailing.Chevron,
        onClick = { onOpenMetric(metric) }
    )
}

private fun valueText(r: NutrientListRow): String {
    val amount = NutrientFormat.withUnit(r.total, r.unit)
    return if (r.goal != null && r.goal > 0) "$amount / ${r.goal} ${r.unit}" else amount
}
