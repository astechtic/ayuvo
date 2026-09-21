package com.ayuvo.health.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.MetricRow
import com.ayuvo.health.ui.health.HealthHubViewModel
import com.ayuvo.health.ui.health.relativeTimeText
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding

/**
 * Browse › Activity (docs/ui-structure.md §2): the Workouts log, the Exercise Library and the app
 * workout metrics, then the Health Connect Activity rows.
 */
@Composable
fun ActivityScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit,
    onOpenWorkouts: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val hubVm: HealthHubViewModel = viewModel(factory = HealthHubViewModel.Factory(container))
    val browseVm: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory(container))
    val hub by hubVm.ui.collectAsState()
    val app by browseVm.ui.collectAsState()
    val catalog = container.metricCatalog
    val launch = rememberHealthPermissionLauncher(container, hubVm)
    val healthHeader = stringResource(R.string.browse_from_health_connect)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.domain_activity), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item(key = "app") {
                InsetGroup(header = stringResource(R.string.browse_logged_in_ayuvo), dividerInset = 60.dp) {
                    row {
                        val sessions = app.snapshot.workouts
                        val latest = sessions.maxByOrNull { it.completedAt }
                        MetricRow(
                            title = stringResource(R.string.nav_workouts),
                            value = null,
                            unit = null,
                            caption = if (latest == null) stringResource(R.string.browse_workouts_caption_empty)
                            else pluralStringResource(R.plurals.browse_workout_count, sessions.size, sessions.size) +
                                " · " + relativeTimeText(latest.completedAt.toEpochMilli()),
                            icon = Icons.Filled.FitnessCenter,
                            tint = AyuvoPalette.Activity,
                            modifier = Modifier.testTag("browse.link.workouts"),
                            onClick = onOpenWorkouts
                        )
                    }
                    row {
                        MetricRow(
                            title = stringResource(R.string.browse_exercise_library),
                            value = null,
                            unit = null,
                            caption = stringResource(R.string.browse_exercise_library_caption),
                            icon = Icons.Filled.SportsGymnastics,
                            tint = AyuvoPalette.Activity,
                            modifier = Modifier.testTag("browse.link.exerciseLibrary"),
                            onClick = onOpenLibrary
                        )
                    }
                    row { AppMetricRow(AppMetricId.WORKOUT_BURN, app, catalog) { onOpenMetric(MetricKey.App(AppMetricId.WORKOUT_BURN)) } }
                }
            }
            val activity = hub.categories.firstOrNull { it.category == HealthCategory.ACTIVITY }
            if (activity != null) {
                healthCategoryGroups(
                    ui = activity,
                    hub = hub,
                    container = container,
                    onOpenMetric = onOpenMetric,
                    onAllow = { launch(hubVm.categoryPermissions(HealthCategory.ACTIVITY)) },
                    withDataHeader = healthHeader
                )
            }
        }
    }
}
