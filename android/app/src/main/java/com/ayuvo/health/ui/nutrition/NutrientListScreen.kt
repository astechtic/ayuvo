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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.HomeTopNutrient
import com.ayuvo.health.models.MacroValueFormatter
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import java.time.LocalDate
import java.time.ZoneId

/** Nutrients that have their own metric detail (docs/ui-structure.md §4). */
private fun HomeTopNutrient.metricId(): AppMetricId? = when (this) {
    HomeTopNutrient.PROTEIN -> AppMetricId.PROTEIN
    HomeTopNutrient.CARBS -> AppMetricId.CARBS
    HomeTopNutrient.FAT -> AppMetricId.FAT
    HomeTopNutrient.FIBER -> AppMetricId.FIBER
    else -> null
}

/**
 * Browse › Nutrition › All Nutrients: today's intake per nutrient against its goal. Nutrients
 * nobody logged today and without a goal are listed under "Not Logged Today" without a number.
 */
@Composable
fun NutrientListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit
) {
    val entries by container.foodRepository.entries.collectAsState(initial = emptyList())
    val profile by container.profileRepository.profile.collectAsState(initial = null)
    val goals by container.prefs.optionalNutrientGoals.collectAsState(initial = OptionalNutrientGoals.Default)
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }
    val todayEntries = remember(entries) { entries.filter { it.timestamp.atZone(zone).toLocalDate() == today } }
    val rows = remember(todayEntries, profile, goals) {
        HomeTopNutrient.entries.map { n -> Triple(n, n.current(todayEntries), n.goal(profile, goals)) }
    }
    val logged = rows.filter { (_, current, _) -> current > 0.0 }
    val notLogged = rows.filter { (_, current, _) -> current <= 0.0 }

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
                    logged.forEach { (nutrient, current, goal) ->
                        row { NutrientRow(nutrient, valueText(nutrient, current, goal), onOpenMetric) }
                    }
                }
            }
            if (notLogged.isNotEmpty()) {
                item(key = "not-logged") {
                    InsetGroup(header = stringResource(R.string.nutrition_not_logged_header), dividerInset = AyuvoSpacing.RowH) {
                        notLogged.forEach { (nutrient, _, goal) ->
                            row {
                                val goalText = if (goal > 0) stringResource(R.string.metric_goal_label, "$goal ${nutrient.unit}") else null
                                NutrientRow(nutrient, goalText, onOpenMetric)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NutrientRow(nutrient: HomeTopNutrient, value: String?, onOpenMetric: (MetricKey) -> Unit) {
    val metric = nutrient.metricId()
    GroupRow(
        title = stringResource(nutrient.displayNameRes),
        value = value,
        trailing = if (metric != null) RowTrailing.Chevron else RowTrailing.None,
        onClick = metric?.let { id -> { onOpenMetric(MetricKey.App(id)) } }
    )
}

private fun valueText(nutrient: HomeTopNutrient, current: Double, goal: Int): String {
    val amount = "${MacroValueFormatter.string(current)} ${nutrient.unit}"
    return if (goal > 0) "$amount / $goal ${nutrient.unit}" else amount
}
