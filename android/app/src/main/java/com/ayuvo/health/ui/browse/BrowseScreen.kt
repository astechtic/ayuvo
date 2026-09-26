package com.ayuvo.health.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.CatalogDomain
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoLargeTopBar
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.body.AddBodyFatDialog
import com.ayuvo.health.ui.body.AddWeightDialog
import com.ayuvo.health.ui.body.BodyLogViewModel
import kotlinx.coroutines.launch
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.MetricRow
import com.ayuvo.health.ui.health.HealthCategoryStyle
import com.ayuvo.health.ui.health.HealthConnectCard
import com.ayuvo.health.ui.health.HealthHubUiState
import com.ayuvo.health.ui.health.HealthHubViewModel
import com.ayuvo.health.ui.health.HealthNoticeCard
import com.ayuvo.health.ui.health.HealthUnavailableCard
import com.ayuvo.health.ui.health.HealthValueFormatter
import com.ayuvo.health.ui.health.formatDate
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors

/** Categories Android cannot read: listed only when imported data exists (docs/ui-structure.md §3). */
private val DATA_ONLY_DOMAINS = setOf("hearing", "symptoms", "mobility")

/**
 * Browse tab (docs/ui-structure.md §2): search over app metrics and Health Connect types, the
 * Health Connect connect card, one row per domain (dimmed when empty) and a sync status footer.
 * [onOpenTarget] receives the catalog target (`screen:nutrition`, `category:heart`, `tab:records`…).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    container: AppContainer,
    onOpenTarget: (String) -> Unit,
    onOpenMetric: (MetricKey) -> Unit,
    onOpenHealthSync: () -> Unit,
    /** A "Features" search result; `logWeight` / `logBodyFat` are handled here with the log dialogs. */
    onOpenFeature: (BrowseFeature) -> Unit = {}
) {
    val hubVm: HealthHubViewModel = viewModel(factory = HealthHubViewModel.Factory(container))
    val browseVm: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory(container))
    val hub by hubVm.ui.collectAsState()
    val app by browseVm.ui.collectAsState()
    val catalog = container.metricCatalog
    HubVisibilityEffect(hubVm)
    val launch = rememberHealthPermissionLauncher(container, hubVm)
    val manageAccess = rememberManageAccess(container)

    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bodyVm: BodyLogViewModel = viewModel(key = "browse-body", factory = BodyLogViewModel.Factory(container))
    val body by bodyVm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var showWeight by rememberSaveable { mutableStateOf(false) }
    var showBodyFat by rememberSaveable { mutableStateOf(false) }
    val openFeature: (BrowseFeature) -> Unit = { feature ->
        when (feature.id) {
            "logWeight" -> showWeight = true
            "logBodyFat" -> showBodyFat = true
            else -> onOpenFeature(feature)
        }
    }

    val domains = remember(catalog) { catalog.domains.sortedBy { it.browseOrder } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoLargeTopBar(
                title = stringResource(R.string.nav_browse),
                scrollBehavior = scrollBehavior,
                actions = {
                    if (hub.hubEnabled) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.health_hub_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (hub.showAllTypes) R.string.health_hub_hide_empty_types else R.string.health_hub_show_all_types)) },
                                    onClick = { menuOpen = false; hubVm.toggleShowAllTypes() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.health_hub_storage_used, HealthValueFormatter.bytes(hub.storageBytes))) },
                                    onClick = { menuOpen = false },
                                    enabled = false
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.health_hub_menu_manage_access)) }, onClick = { menuOpen = false; manageAccess() })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.health_settings_clear), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; showClearConfirm = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = hub.refreshing,
            onRefresh = { if (hub.hubEnabled) hubVm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 4.dp, bottom = BottomNavScrollPadding),
                verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
            ) {
                item(key = "search") { BrowseSearchField(query = query, onQuery = { query = it }) }

                if (query.isNotBlank()) {
                    item(key = "results") {
                        BrowseSearchResults(
                            query = query.trim(),
                            container = container,
                            hub = hub,
                            domains = domains,
                            onOpenTarget = onOpenTarget,
                            onOpenMetric = onOpenMetric,
                            onOpenFeature = openFeature
                        )
                    }
                    return@LazyColumn
                }

                if (!hub.loading && hub.availability == HealthConnectAvailability.AVAILABLE && !hub.hubEnabled) {
                    item(key = "connect") {
                        HealthConnectCard(
                            consentChecked = hub.consentChecked,
                            onConsentChange = hubVm::setConsentChecked,
                            onConnect = { launch(hubVm.connectPermissions()) },
                            onManageAccess = manageAccess
                        )
                    }
                }
                if (hub.hubEnabled && hub.permissionsReset) {
                    item(key = "reset") {
                        HealthNoticeCard(
                            message = stringResource(R.string.health_hub_subtitle_permissions_reset),
                            actionText = stringResource(R.string.health_hub_regrant),
                            onAction = { launch(hubVm.allPermissions()) }
                        )
                    }
                }
                hub.syncStatus.historyLimitedBeforeMs?.let { floor ->
                    if (hub.hubEnabled && !hub.historyGranted && hub.historySupported) {
                        item(key = "history") {
                            HealthNoticeCard(
                                message = stringResource(R.string.health_hub_subtitle_limited, formatDate(floor)),
                                actionText = stringResource(R.string.health_hub_grant_history),
                                onAction = { launch(hubVm.historyPermissions()) }
                            )
                        }
                    }
                }

                item(key = "domains") {
                    val visible = domains.filter { d -> d.id !in DATA_ONLY_DOMAINS || domainHasData(d, app, hub) }
                    InsetGroup {
                        visible.forEach { domain ->
                            row {
                                val tint = MetricCatalog.color(domain)
                                val dimmed = app.loaded && !hub.loading && !domainHasData(domain, app, hub)
                                GroupRow(
                                    title = stringResource(domainTitleRes(domain.id)),
                                    modifier = Modifier.testTag("browse.row.${domain.id}"),
                                    enabled = true,
                                    leading = {
                                        CategoryIcon(domainIcon(domain), if (dimmed) tint.copy(alpha = 0.45f) else tint)
                                    },
                                    subtitle = if (dimmed) stringResource(R.string.browse_no_data) else null,
                                    onClick = { onOpenTarget(domain.target) }
                                )
                            }
                        }
                    }
                }

                if (!hub.loading && hub.availability != HealthConnectAvailability.AVAILABLE) {
                    item(key = "unavailable") { HealthUnavailableCard(hub.availability) { container.health.openPlayStore() } }
                } else if (!hub.loading) {
                    item(key = "sync") {
                        InsetGroup(footer = stringResource(R.string.health_hub_read_only_footer)) {
                            row {
                                GroupRow(
                                    title = stringResource(R.string.browse_health_sync),
                                    subtitle = healthSyncStatusText(hub),
                                    icon = Icons.Filled.Sync,
                                    iconTint = AyuvoColors.category(HealthCategory.HEART),
                                    modifier = Modifier.testTag("browse.sync"),
                                    onClick = onOpenHealthSync
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        GlassDialog(onDismissRequest = { showClearConfirm = false }) {
            Text(stringResource(R.string.health_settings_clear_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.health_settings_clear_message), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_clear),
                onPrimary = { hubVm.clearSyncedData(); showClearConfirm = false },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showClearConfirm = false },
                destructive = true
            )
        }
    }
    if (showWeight) {
        AddWeightDialog(
            useMetric = app.units.weightMetric,
            initialKg = body.entries.maxByOrNull { it.date }?.weightKg ?: body.profile?.weightKg ?: 70.0,
            onUnitChange = { metric -> scope.launch { container.prefs.setWeightUnit(if (metric) "kg" else "lbs") } },
            onDismiss = { showWeight = false }
        ) { kg -> bodyVm.addWeight(kg); showWeight = false }
    }
    if (showBodyFat) {
        AddBodyFatDialog(
            initialFraction = body.bodyFatEntries.maxByOrNull { it.date }?.bodyFatFraction ?: body.profile?.bodyFatPercentage ?: 0.20,
            onDismiss = { showBodyFat = false }
        ) { fraction -> bodyVm.addBodyFat(fraction); showBodyFat = false }
    }
    if (body.goalReached) {
        GlassDialog(onDismissRequest = bodyVm::dismissGoalReached) {
            Text(stringResource(R.string.progress_goal_reached_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.progress_goal_reached_message), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(primaryText = stringResource(R.string.action_keep_going), onPrimary = bodyVm::dismissGoalReached)
        }
    }
}

/** Whether a Browse domain has anything to show (dimmed otherwise). */
internal fun domainHasData(domain: CatalogDomain, app: BrowseAppState, hub: HealthHubUiState): Boolean {
    fun category(id: String) = hub.categories.firstOrNull { it.category.id == id }?.rows?.any { it.count > 0 } == true
    val s = app.snapshot
    val target = domain.target
    return when {
        target == "screen:nutrition" -> s.food.isNotEmpty() || category(HealthCategory.NUTRITION.id)
        target == "metric:app:water" -> s.water.isNotEmpty()
        target == "screen:fasting" -> s.fasting.isNotEmpty()
        target == "screen:body" -> s.weight.isNotEmpty() || s.bodyFat.isNotEmpty() || app.measurements.isNotEmpty() || category(HealthCategory.BODY.id)
        target == "screen:activity" -> s.workouts.isNotEmpty() || category(HealthCategory.ACTIVITY.id)
        target == "screen:medications" -> app.medicationsExist
        target == "tab:records" -> app.recordsCount > 0
        // Insights are recomputed from everything else, so the domain is always open (its screens say what is missing).
        target == "screen:insights" -> true
        target.startsWith("category:") -> category(target.removePrefix("category:"))
        else -> false
    }
}

@Composable
private fun BrowseSearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().testTag("browse.search"),
        placeholder = { Text(stringResource(R.string.browse_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear)) }
            }
        },
        singleLine = true,
        shape = AyuvoShapes.Field,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AyuvoColors.fill(),
            unfocusedContainerColor = AyuvoColors.fill(),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppColors.Calorie
        )
    )
}

/**
 * Search results: app features and actions ([BrowseFeatures]), then domains, then the trend graphs
 * (app metrics and Health Connect types) whose name contains [query].
 */
@Composable
private fun BrowseSearchResults(
    query: String,
    container: AppContainer,
    hub: HealthHubUiState,
    domains: List<CatalogDomain>,
    onOpenTarget: (String) -> Unit,
    onOpenMetric: (MetricKey) -> Unit,
    onOpenFeature: (BrowseFeature) -> Unit
) {
    val context = LocalContext.current
    val catalog = container.metricCatalog
    val res = LocalResources.current
    val features = remember(query, res) { BrowseFeatures.search(query) { res.getString(it.titleRes) } }
    val matchedDomains = domains.filter { res.getString(domainTitleRes(it.id)).contains(query, ignoreCase = true) }
    val appMatches = AppMetricId.entries.filter { res.getString(MetricCatalog.titleRes(it)).contains(query, ignoreCase = true) }
    val withData = hub.categories.flatMap { it.rows }.filter { it.count > 0 }.map { it.typeId }.toSet()
    val healthMatches = HealthDataType.entries
        .filter { !it.reserved && !it.isVirtualDietary && (it.sdkAvailable || it.id in withData) }
        .filter { HealthCategoryStyle.typeName(context, it.id).contains(query, ignoreCase = true) }
        .sortedByDescending { it.id in withData }
    if (features.isEmpty() && matchedDomains.isEmpty() && appMatches.isEmpty() && healthMatches.isEmpty()) {
        Text(
            stringResource(R.string.browse_search_empty, query),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            color = AyuvoColors.secondaryLabel()
        )
        return
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)) {
        if (features.isNotEmpty()) {
            InsetGroup(header = stringResource(R.string.browse_search_features), dividerInset = 60.dp) {
                features.forEach { feature ->
                    row {
                        GroupRow(
                            title = stringResource(feature.titleRes),
                            subtitle = stringResource(feature.subtitleRes),
                            leading = { CategoryIcon(featureIcon(feature.id), featureColor(feature.domain)) },
                            modifier = Modifier.testTag("browse.feature.${feature.id}"),
                            onClick = { onOpenFeature(feature) }
                        )
                    }
                }
            }
        }
        if (matchedDomains.isNotEmpty()) {
            InsetGroup(header = stringResource(R.string.browse_search_categories)) {
                matchedDomains.forEach { d ->
                    row {
                        GroupRow(
                            title = stringResource(domainTitleRes(d.id)),
                            leading = { CategoryIcon(domainIcon(d), MetricCatalog.color(d)) },
                            onClick = { onOpenTarget(d.target) }
                        )
                    }
                }
            }
        }
        if (appMatches.isNotEmpty()) {
            InsetGroup(header = stringResource(R.string.browse_search_trends), dividerInset = 60.dp) {
                appMatches.forEach { id ->
                    row {
                        val key = MetricKey.App(id)
                        MetricRow(
                            title = stringResource(MetricCatalog.titleRes(id)),
                            value = null,
                            unit = null,
                            caption = stringResource(domainTitleRes(MetricCatalog.spec(catalog, id).domain)),
                            icon = MetricCatalog.icon(catalog, key),
                            tint = MetricCatalog.color(catalog, key),
                            modifier = Modifier.testTag("browse.metric.${key.storageId}"),
                            onClick = { onOpenMetric(key) }
                        )
                    }
                }
            }
        }
        if (healthMatches.isNotEmpty()) {
            InsetGroup(header = stringResource(R.string.browse_search_trends_health_connect), dividerInset = 60.dp) {
                healthMatches.take(SEARCH_LIMIT).forEach { type ->
                    row {
                        val key = MetricKey.Health(type.id)
                        MetricRow(
                            title = HealthCategoryStyle.typeName(context, type.id),
                            value = null,
                            unit = null,
                            caption = HealthCategoryStyle.categoryName(context, type.category) +
                                if (type.id in withData) "" else " · " + stringResource(R.string.browse_no_data),
                            icon = MetricCatalog.icon(catalog, key),
                            tint = MetricCatalog.color(catalog, key),
                            dimmed = type.id !in withData,
                            modifier = Modifier.testTag("browse.metric.${type.id}"),
                            onClick = { onOpenMetric(key) }
                        )
                    }
                }
            }
        }
    }
}

private const val SEARCH_LIMIT = 40

private fun featureIcon(id: String): ImageVector = when (id) {
    "workouts" -> Icons.Filled.FitnessCenter
    "workoutLog" -> Icons.Filled.AddCircle
    "exerciseLibrary" -> Icons.AutoMirrored.Filled.MenuBook
    "nutrition" -> Icons.Filled.Restaurant
    "logFood" -> Icons.Filled.AddCircle
    "water" -> Icons.Filled.WaterDrop
    "fasting" -> Icons.Filled.Timer
    "bodyMeasurements" -> Icons.Filled.Straighten
    "logWeight" -> Icons.Filled.MonitorWeight
    "logBodyFat" -> Icons.Filled.Percent
    "medications" -> Icons.Filled.Medication
    "addMedication" -> Icons.Filled.AddCircle
    "records" -> Icons.Filled.Description
    "addRecord" -> Icons.AutoMirrored.Filled.NoteAdd
    "insights", "recovery", "healthAge", "dailyReview" -> Icons.Filled.Insights
    "coach" -> Icons.AutoMirrored.Filled.Chat
    "settings" -> Icons.Filled.Settings
    else -> Icons.Filled.Search
}

private fun featureColor(domain: BrowseFeatureDomain): Color = when (domain) {
    BrowseFeatureDomain.ACTIVITY -> AyuvoPalette.Activity
    BrowseFeatureDomain.NUTRITION -> AyuvoPalette.Nutrition
    BrowseFeatureDomain.HYDRATION -> AyuvoPalette.Hydration
    BrowseFeatureDomain.FASTING -> AyuvoPalette.Fasting
    BrowseFeatureDomain.BODY -> AyuvoPalette.Body
    BrowseFeatureDomain.MEDICATIONS -> AyuvoPalette.Medications
    BrowseFeatureDomain.RECORDS -> AyuvoPalette.Records
    BrowseFeatureDomain.INSIGHTS -> com.ayuvo.health.ui.insights.InsightsFormat.Insights
    BrowseFeatureDomain.COACH -> AyuvoPalette.Mindfulness
    BrowseFeatureDomain.SETTINGS -> AyuvoPalette.Other
}
