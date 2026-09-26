package com.ayuvo.health.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.ui.body.AddBodyFatDialog
import com.ayuvo.health.ui.body.AddWeightDialog
import com.ayuvo.health.ui.body.BodyLogViewModel
import com.ayuvo.health.ui.browse.tileCaption
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoLargeTopBar
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.MetricTile
import com.ayuvo.health.ui.design.SectionHeader
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.home.FastingGoalDialog
import com.ayuvo.health.ui.home.FoodLogRequest
import com.ayuvo.health.ui.home.WaterCustomAmountSheet
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.navigation.LocalLaunchFillEpoch
import com.ayuvo.health.ui.settings.SettingsPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where Summary cards and log entries hand off to (routes live in AppNavHost). */
data class SummaryDestinations(
    val openMetric: (MetricKey) -> Unit = {},
    val openBrowse: () -> Unit = {},
    val openNutrition: (FoodLogRequest?) -> Unit = {},
    val openFasting: () -> Unit = {},
    val openWorkouts: () -> Unit = {},
    val openMedications: () -> Unit = {},
    val addMedication: () -> Unit = {},
    val openRecord: (String) -> Unit = {},
    val addRecord: () -> Unit = {},
    val openSettings: () -> Unit = {},
    /** Settings › Tracking page for a widget action whose tracking is off (docs/widgets.md). */
    val openSettingsPage: (SettingsPage) -> Unit = {}
)

/** A Summary "+" entry requested from outside the screen (Quick Log widget), consumed once. */
data class SummaryLogRequest(val entry: LogEntry, val id: Long = System.nanoTime())

/**
 * Summary tab (docs/ui-structure.md §8): date, rings, Today cards, Favourites, Highlights and the
 * "Get More From Ayuvo" checklist, plus the "+" log sheet. Cards with nothing real to show are
 * not composed at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    container: AppContainer,
    destinations: SummaryDestinations,
    logRequest: SummaryLogRequest? = null,
    onLogRequestHandled: (Long) -> Unit = {}
) {
    val vm: SummaryViewModel = viewModel(factory = SummaryViewModel.Factory(container))
    val bodyVm: BodyLogViewModel = viewModel(key = "summary-body", factory = BodyLogViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val body by bodyVm.ui.collectAsState()
    val catalog = container.metricCatalog
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showLog by rememberSaveable { mutableStateOf(false) }
    var showWater by rememberSaveable { mutableStateOf(false) }
    var showFastStart by rememberSaveable { mutableStateOf(false) }
    var showWeight by rememberSaveable { mutableStateOf(false) }
    var showBodyFat by rememberSaveable { mutableStateOf(false) }
    var editFavourites by remember { mutableStateOf<List<MetricKey>?>(null) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun openEditor() {
        scope.launch { editFavourites = vm.favouriteKeys() }
    }

    /** One Summary "+" entry; the "+" sheet and the Quick Log widget both land here. */
    fun onLogEntry(entry: LogEntry, waterTracking: Boolean, fastingTracking: Boolean, fastActive: Boolean) {
        when (entry) {
            LogEntry.FOOD -> destinations.openNutrition(FoodLogRequest(method = null))
            LogEntry.WATER -> showWater = waterTracking
            LogEntry.FASTING -> if (!fastActive && fastingTracking) showFastStart = true else destinations.openFasting()
            LogEntry.WEIGHT -> showWeight = true
            LogEntry.BODY_FAT -> showBodyFat = true
            LogEntry.WORKOUT -> destinations.openWorkouts()
            LogEntry.MEDICATION -> destinations.openMedications()
            LogEntry.RECORD -> destinations.addRecord()
        }
    }

    // Quick Log widget: read tracking switches from the stores (the screen state may still be
    // loading on a cold start). Water or fasting switched off opens its Settings page instead.
    LaunchedEffect(logRequest?.id) {
        val request = logRequest ?: return@LaunchedEffect
        val water = container.prefs.waterTrackingEnabled.first()
        val fasting = container.prefs.fastingTrackingEnabled.first()
        val fastActive = runCatching { container.fastingRepository.active() != null }.getOrDefault(false)
        when {
            request.entry == LogEntry.WATER && !water -> destinations.openSettingsPage(SettingsPage.HYDRATION)
            request.entry == LogEntry.FASTING && !fasting && !fastActive -> destinations.openSettingsPage(SettingsPage.FASTING)
            else -> onLogEntry(request.entry, water, fasting, fastActive)
        }
        // Last: clearing the request changes this effect's key and would cancel it.
        onLogRequestHandled(request.id)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoLargeTopBar(
                title = stringResource(R.string.nav_summary),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showLog = true }, modifier = Modifier.testTag("summary.add")) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.summary_log_title))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 4.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap)
        ) {
            item(key = "date") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        ui.today.format(dateFormatter),
                        fontSize = 15.sp,
                        color = com.ayuvo.health.ui.design.AyuvoColors.secondaryLabel(),
                        modifier = Modifier.testTag("summary.date")
                    )
                    com.ayuvo.health.ui.design.AyuvoPrivacyPill(opensPrivacyPage = true)
                }
            }
            item(key = "rings") {
                SummaryRingsCard(
                    rings = ui.rings,
                    waterUnit = ui.waterUnit,
                    animationKey = LocalLaunchFillEpoch.current,
                    onRing = { id, state ->
                        when (id) {
                            SummaryRingId.EAT -> destinations.openMetric(MetricKey.App(AppMetricId.CALORIES))
                            SummaryRingId.MOVE ->
                                if (state == SummaryRingState.CONNECT) destinations.openBrowse()
                                else destinations.openMetric(MetricKey.Health(HealthDataType.STEPS.id))
                            SummaryRingId.DRINK -> destinations.openMetric(MetricKey.App(AppMetricId.WATER))
                        }
                    }
                )
            }

            val medications = ui.medications.takeIf { ui.showMedications }
            val fast = ui.activeFast.takeIf { ui.fastingTracking || it != null }
            val workouts = ui.workoutsToday
            if (medications != null || fast != null || workouts.isNotEmpty()) {
                item(key = "today-header") { SectionHeader(stringResource(R.string.summary_today)) }
                if (medications != null) item(key = "today-meds") { MedicationsTodayCard(medications, destinations.openMedications) }
                if (fast != null) item(key = "today-fast") { FastingTodayCard(fast, destinations.openFasting) }
                if (workouts.isNotEmpty()) item(key = "today-workouts") { WorkoutTodayCard(workouts, destinations.openWorkouts) }
            }

            item(key = "fav-header") {
                SectionHeader(
                    stringResource(R.string.summary_favourites),
                    trailing = stringResource(R.string.summary_edit),
                    onTrailing = ::openEditor,
                    modifier = Modifier.testTag("summary.favourites.edit")
                )
            }
            if (ui.favouritesLoaded && ui.favourites.isEmpty()) {
                item(key = "fav-empty") {
                    SurfaceCard(onClick = ::openEditor) {
                        EmptyState(
                            icon = Icons.Outlined.StarOutline,
                            title = stringResource(R.string.summary_add_favourites),
                            message = stringResource(R.string.summary_add_favourites_body)
                        )
                    }
                }
            } else {
                ui.favourites.chunked(2).forEachIndexed { index, pair ->
                    item(key = "fav-row-$index-${pair.joinToString { it.key.storageId }}") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap)) {
                            pair.forEach { fav ->
                                val key = fav.key
                                MetricTile(
                                    title = MetricCatalog.title(context, key),
                                    icon = MetricCatalog.icon(catalog, key),
                                    tint = MetricCatalog.color(catalog, key),
                                    value = fav.tile.number,
                                    unit = fav.tile.unit,
                                    caption = tileCaption(fav.tile),
                                    spark = fav.tile.spark,
                                    hasData = fav.tile.hasData,
                                    modifier = Modifier.weight(1f).testTag("summary.favourite.${key.storageId}"),
                                    onClick = { destinations.openMetric(key) }
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (ui.highlights.isNotEmpty()) {
                item(key = "hl-header") { SectionHeader(stringResource(R.string.summary_highlights)) }
                ui.highlights.forEachIndexed { index, highlight ->
                    item(key = "hl-$index") {
                        HighlightCard(
                            highlight = highlight,
                            weightMetric = ui.weightMetric,
                            onOpenRecord = destinations.openRecord,
                            onOpenWeight = { destinations.openMetric(MetricKey.App(AppMetricId.WEIGHT)) },
                            onOpenWorkouts = destinations.openWorkouts
                        )
                    }
                }
            }

            if (!ui.checklistDismissed && ui.checklist.isNotEmpty()) {
                item(key = "checklist") {
                    Column {
                        Spacer(Modifier.padding(top = 8.dp))
                        ChecklistCard(
                            items = ui.checklist,
                            onItem = { item ->
                                when (item) {
                                    ChecklistItem.CONNECT_HEALTH -> destinations.openBrowse()
                                    ChecklistItem.REMINDERS -> destinations.openSettings()
                                    ChecklistItem.ADD_RECORD -> destinations.addRecord()
                                    ChecklistItem.ADD_MEDICATIONS -> destinations.addMedication()
                                }
                            },
                            onDismiss = vm::dismissChecklist
                        )
                    }
                }
            }
        }
    }

    if (showLog) {
        SummaryLogSheet(
            waterTracking = ui.waterTracking,
            canStartFast = ui.fastingTracking && ui.activeFast == null,
            onEntry = { entry ->
                showLog = false
                // The sheet only lists Water while tracking is on.
                onLogEntry(entry, waterTracking = true, fastingTracking = ui.fastingTracking, fastActive = ui.activeFast != null)
            },
            onDismiss = { showLog = false }
        )
    }
    if (showWater) {
        WaterCustomAmountSheet(unit = ui.waterUnit, onDismiss = { showWater = false }, onAdd = { ml -> vm.addWater(ml); showWater = false })
    }
    if (showFastStart) {
        FastingGoalDialog(
            title = stringResource(R.string.fasting_start),
            initialMinutes = ui.fastingDefaultGoalMinutes,
            confirmLabel = stringResource(R.string.fasting_start),
            onConfirm = { showFastStart = false; vm.startFast(it) },
            onDismiss = { showFastStart = false }
        )
    }
    if (showWeight) {
        AddWeightDialog(
            useMetric = ui.weightMetric,
            initialKg = ui.latestWeightKg ?: ui.profile?.weightKg ?: 70.0,
            onUnitChange = { metric -> scope.launch { container.prefs.setWeightUnit(if (metric) "kg" else "lbs") } },
            onDismiss = { showWeight = false }
        ) { kg -> bodyVm.addWeight(kg); showWeight = false }
    }
    if (showBodyFat) {
        AddBodyFatDialog(
            initialFraction = ui.latestBodyFatFraction ?: ui.profile?.bodyFatPercentage ?: 0.20,
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
    editFavourites?.let { current ->
        FavoritesEditorSheet(
            catalog = catalog,
            current = current,
            onSave = { keys -> vm.setFavourites(keys); editFavourites = null },
            onDismiss = { editFavourites = null }
        )
    }
}
