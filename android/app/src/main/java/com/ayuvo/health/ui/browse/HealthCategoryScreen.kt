package com.ayuvo.health.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.health.HealthCategoryStyle
import com.ayuvo.health.ui.health.HealthCategoryUi
import com.ayuvo.health.ui.health.HealthHubUiState
import com.ayuvo.health.ui.health.HealthHubViewModel
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding

/** Browse › a Health Connect category (docs/ui-structure.md §2): types with data, then a "No Data" group. */
@Composable
fun HealthCategoryScreen(
    container: AppContainer,
    categoryId: String,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit
) {
    val vm: HealthHubViewModel = viewModel(factory = HealthHubViewModel.Factory(container))
    val hub by vm.ui.collectAsState()
    val context = LocalContext.current
    val category = HealthCategory.byId(categoryId) ?: HealthCategory.OTHER
    val launch = rememberHealthPermissionLauncher(container, vm)
    val title = container.metricCatalog.categoryDomains[category.id]
        ?.let { stringResource(domainTitleRes(it)) }
        ?: HealthCategoryStyle.categoryName(context, category)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = title, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            if (hub.loading) return@LazyColumn
            val ui = hub.categories.firstOrNull { it.category == category }
            if (ui == null || (ui.rows.isEmpty() && ui.missingPermissions.isEmpty())) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.MonitorHeart,
                        title = stringResource(R.string.browse_no_data),
                        message = stringResource(if (hub.hubEnabled) R.string.health_hub_no_data_body else R.string.browse_connect_to_see)
                    )
                }
                return@LazyColumn
            }
            healthCategoryGroups(
                ui = ui,
                hub = hub,
                container = container,
                onOpenMetric = onOpenMetric,
                onAllow = { launch(vm.categoryPermissions(category)) },
                withDataHeader = null
            )
        }
    }
}

/**
 * The rows of one category as two inset groups: types with data (plus the "Allow access" row), then
 * the empty ones dimmed. [exclude] drops ids listed elsewhere on the page (Body's app metrics).
 */
internal fun LazyListScope.healthCategoryGroups(
    ui: HealthCategoryUi,
    hub: HealthHubUiState,
    container: AppContainer,
    onOpenMetric: (MetricKey) -> Unit,
    onAllow: () -> Unit,
    withDataHeader: String?,
    exclude: Set<String> = emptySet()
) {
    val rows = ui.rows.filter { it.typeId !in exclude }
    val withData = rows.filter { it.count > 0 }
    val empty = rows.filter { it.count == 0L }
    // No rows with data and nothing to grant: skip the group so no header floats alone.
    if (withData.isNotEmpty() || ui.missingPermissions.isNotEmpty()) item(key = "cat-data-${ui.category.id}") {
        val context = LocalContext.current
        InsetGroup(header = withDataHeader, dividerInset = 60.dp) {
            withData.forEach { r ->
                row { HealthMetricRow(r, hub.unitPrefs, container.metricCatalog, onClick = { onOpenMetric(MetricKey.Health(r.typeId)) }) }
            }
            if (ui.missingPermissions.isNotEmpty()) {
                row { AllowAccessRow(HealthCategoryStyle.categoryName(context, ui.category), onAllow) }
            }
        }
    }
    if (empty.isNotEmpty()) {
        item(key = "cat-empty-${ui.category.id}") {
            InsetGroup(header = stringResource(R.string.browse_no_data), dividerInset = 60.dp) {
                empty.forEach { r ->
                    row { HealthMetricRow(r, hub.unitPrefs, container.metricCatalog, onClick = { onOpenMetric(MetricKey.Health(r.typeId)) }, dimmed = true) }
                }
            }
        }
    }
}
