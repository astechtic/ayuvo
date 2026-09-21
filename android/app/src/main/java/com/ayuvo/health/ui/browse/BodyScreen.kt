package com.ayuvo.health.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
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
 * Browse › Body (docs/ui-structure.md §2): app Weight, Body Fat and Body Measurements, then the
 * Health Connect Body rows except the ones the catalog hides (weight and body fat are already
 * part of the app metrics; search still finds them).
 */
@Composable
fun BodyScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit,
    onOpenMeasurements: () -> Unit
) {
    val hubVm: HealthHubViewModel = viewModel(factory = HealthHubViewModel.Factory(container))
    val browseVm: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory(container))
    val hub by hubVm.ui.collectAsState()
    val app by browseVm.ui.collectAsState()
    val catalog = container.metricCatalog
    val launch = rememberHealthPermissionLauncher(container, hubVm)
    val hidden = catalog.overrides.filter { it.browseHidden }.map { it.id }.toSet()
    val healthHeader = stringResource(R.string.browse_from_health_connect)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.domain_body), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item(key = "app") {
                InsetGroup(header = stringResource(R.string.browse_logged_in_ayuvo), dividerInset = 60.dp) {
                    row { AppMetricRow(AppMetricId.WEIGHT, app, catalog) { onOpenMetric(MetricKey.App(AppMetricId.WEIGHT)) } }
                    row { AppMetricRow(AppMetricId.BODY_FAT, app, catalog) { onOpenMetric(MetricKey.App(AppMetricId.BODY_FAT)) } }
                    row {
                        val latest = app.measurements.maxByOrNull { it.date }
                        MetricRow(
                            title = stringResource(R.string.browse_body_measurements),
                            value = null,
                            unit = null,
                            caption = if (latest == null) stringResource(R.string.browse_no_data)
                            else pluralStringResource(R.plurals.browse_measurement_count, app.measurements.size, app.measurements.size) +
                                " · " + relativeTimeText(latest.date.toEpochMilli()),
                            icon = Icons.Filled.Straighten,
                            tint = AyuvoPalette.Body,
                            modifier = Modifier.testTag("browse.link.bodyMeasurements"),
                            onClick = onOpenMeasurements
                        )
                    }
                }
            }
            val body = hub.categories.firstOrNull { it.category == HealthCategory.BODY }
            if (body != null) {
                healthCategoryGroups(
                    ui = body,
                    hub = hub,
                    container = container,
                    onOpenMetric = onOpenMetric,
                    onAllow = { launch(hubVm.categoryPermissions(HealthCategory.BODY)) },
                    withDataHeader = healthHeader,
                    exclude = hidden
                )
            }
        }
    }
}
