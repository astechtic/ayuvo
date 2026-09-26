package com.ayuvo.health.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.ui.metrics.AppMetricDestinations
import com.ayuvo.health.ui.metrics.MetricDetailScreen
import com.ayuvo.health.services.update.AndroidUpdateChecker
import com.ayuvo.health.services.update.AndroidUpdateState
import com.ayuvo.health.ui.coach.CoachScreen
import com.ayuvo.health.ui.home.FoodLogRequest
import com.ayuvo.health.ui.browse.ActivityScreen
import com.ayuvo.health.ui.browse.BodyScreen
import com.ayuvo.health.ui.browse.BrowseScreen
import com.ayuvo.health.ui.browse.HealthCategoryScreen
import com.ayuvo.health.ui.fasting.FastingScreen
import com.ayuvo.health.ui.nutrition.NutrientListScreen
import com.ayuvo.health.ui.nutrition.NutritionScreen
import com.ayuvo.health.ui.summary.SummaryDestinations
import com.ayuvo.health.ui.summary.SummaryLogRequest
import com.ayuvo.health.models.WidgetRequest
import com.ayuvo.health.models.WidgetTarget
import com.ayuvo.health.widget.WidgetMetric
import com.ayuvo.health.ui.summary.SummaryScreen
import com.ayuvo.health.ui.medications.MedicationsScreen
import com.ayuvo.health.ui.workouts.WorkoutsScreen
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.ayuvo.health.ui.onboarding.OnboardingScreen
import com.ayuvo.health.ui.body.BodyMeasurementsScreen
import com.ayuvo.health.ui.settings.AllergenSensitivitiesScreen
import com.ayuvo.health.ui.settings.CalculationMethodsScreen
import com.ayuvo.health.ui.settings.OptionalNutrientGoalsScreen
import com.ayuvo.health.ui.settings.SettingsPage
import com.ayuvo.health.ui.settings.SettingsPageRequest
import com.ayuvo.health.ui.settings.SettingsScreen
import com.ayuvo.health.ui.settings.SettingsViewModel
import com.ayuvo.health.models.WorkoutTabMode
import com.ayuvo.health.records.RecordsRequest
import com.ayuvo.health.ui.records.RecordDetailScreen
import com.ayuvo.health.ui.records.RecordsScreen
import com.ayuvo.health.ui.records.SplitReviewScreen
import com.ayuvo.health.ui.records.TrendScreen
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.medications.model.MedicationRequest
import com.ayuvo.health.ui.medications.ImportFromRecordScreen
import com.ayuvo.health.ui.medications.MedicationDetailScreen
import com.ayuvo.health.ui.medications.MedicationEditorScreen
import com.ayuvo.health.ui.medications.MedicationHistoryScreen
import com.ayuvo.health.ui.settings.AddMenuSettingsScreen
import com.ayuvo.health.ui.about.LicensesScreen
import com.ayuvo.health.ui.settings.QuickActionsScreen
import com.ayuvo.health.ui.actions.ActionHost
import com.ayuvo.health.ui.actions.ActionNavigation
import com.ayuvo.health.ui.actions.PendingAction
import com.ayuvo.health.ui.summary.LogEntry
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

/**
 * Increments each time the app is opened: 1 on cold launch, then +1 on every
 * return from the background (ON_START after a real ON_STOP). Read by the Home
 * gauge + macro bars to replay their fill-from-zero reveal. It lives above the
 * NavHost, so tab switches (which recompose Home) never change it.
 */
val LocalLaunchFillEpoch = compositionLocalOf { 1 }

private const val WORKOUT_UI_PREFS = "ayuvo_workouts"
private const val WORKOUT_MODE_V2_DEFAULT_KEY = "mode.diary_default.v2"

/** Tab roots other than the start destination, checked when resolving a shared route's tab. */
private val NON_START_TABS = listOf(AppRoutes.BROWSE, AppRoutes.RECORDS, AppRoutes.COACH, AppRoutes.SETTINGS)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppNavHost(
    container: AppContainer,
    startOnboarding: Boolean,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    recordsRequest: RecordsRequest? = null,
    onRecordsRequestHandled: (Long) -> Unit = {},
    medicationRequest: MedicationRequest? = null,
    onMedicationRequestHandled: (Long) -> Unit = {},
    widgetRequest: WidgetRequest? = null,
    onWidgetRequestHandled: (Long) -> Unit = {},
    pendingAction: PendingAction? = null,
    onActionHandled: (Long) -> Unit = {}
) {
    val nav = rememberNavController()
    // Warm the app-scoped Settings state while Summary is visible. By the time the user changes
    // tabs, its local profile/preferences are already ready and the page opens like every other
    // tab instead of constructing empty cards on first entry.
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(container)
    )
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Hide the bar while a food analysis is in flight so the AnalyzingOverlay
    // is the only thing on screen — matches iOS, where the analyzing sheet
    // covers the tab bar.
    val context = LocalContext.current
    val analyzing by container.analyzingFood.collectAsState()
    val workoutUiPrefs = remember(context) {
        context.getSharedPreferences(WORKOUT_UI_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var workoutModeV2Initialized by remember(context) {
        mutableStateOf(workoutUiPrefs.getBoolean(WORKOUT_MODE_V2_DEFAULT_KEY, false))
    }

    /** Tab roots present in the back stack, nearest first (only one non-start tab can be there). */
    fun tabAncestors(): List<String> =
        (NON_START_TABS + AppRoutes.SUMMARY).filter { route -> runCatching { nav.getBackStackEntry(route) }.isSuccess }

    fun routeNow(): String? = nav.currentBackStackEntry?.destination?.route
    fun tabNow(): String? = AppRoutes.selectedBottomTab(routeNow(), tabAncestors())

    // Shared destinations (metric, workouts, medications) keep the tab that opened them selected.
    val selectedTabRoute = remember(backStack) { tabNow() }
    val showTabs = selectedTabRoute != null && !analyzing
    // Bumped when the Settings tab is re-tapped so SettingsScreen can pop back to the hub
    // (matches iOS TabView + NavigationStack reselect → root behavior).
    var settingsPopToRootTick by remember { mutableIntStateOf(0) }
    val currentVersion = remember(context) { AndroidUpdateChecker.currentVersion(context) }
    var updateAvailable by remember { mutableStateOf(false) }
    // One-shot requests consumed by the destination that owns the flow.
    var foodLogRequest by remember { mutableStateOf<FoodLogRequest?>(null) }
    var recordsAddRequest by remember { mutableStateOf<Long?>(null) }
    var settingsPageRequest by remember { mutableStateOf<SettingsPageRequest?>(null) }
    var summaryLogRequest by remember { mutableStateOf<SummaryLogRequest?>(null) }

    // Settings is intentionally warmed before onboarding finishes. Reload the values that
    // onboarding can change outside Settings so the first visit never shows the pre-onboarding
    // provider/model/API-key snapshot (issue #170). Re-running this on later visits also keeps
    // the app-scoped ViewModel honest if another flow updates the stored AI configuration.
    LaunchedEffect(currentRoute) {
        if (currentRoute == AppRoutes.SETTINGS) {
            settingsViewModel.refreshAiConfiguration()
        }
    }

    LaunchedEffect(container.workoutRepository, workoutModeV2Initialized) {
        if (!workoutModeV2Initialized) {
            container.workoutRepository.setMode(WorkoutTabMode.LOG)
            workoutUiPrefs.edit().putBoolean(WORKOUT_MODE_V2_DEFAULT_KEY, true).apply()
            workoutModeV2Initialized = true
        }
    }

    LaunchedEffect(currentVersion) {
        val state = AndroidUpdateChecker.check(context, currentVersion)
        updateAvailable = state is AndroidUpdateState.Available
        // A newer version is out — fire a one-shot notification (de-duped per version, gated by the
        // "App Updates" toggle) so the user finds out even without opening the About section.
        if (state is AndroidUpdateState.Available &&
            container.prefs.appUpdateNotificationsEnabled.first() &&
            container.notifications.canPostNotifications() &&
            container.prefs.lastNotifiedUpdateVersion.first() != state.latest
        ) {
            container.notifications.showUpdateAvailable()
            container.prefs.setLastNotifiedUpdateVersion(state.latest)
        }
    }

    // App-open epoch for the Summary ring fill. Bumped only on ON_START
    // that follows an ON_STOP (a genuine background -> foreground return), so
    // transient pauses (notification shade, permission dialog) don't retrigger it.
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchFillEpoch by remember { mutableIntStateOf(1) }
    var hasStopped by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> hasStopped = true
                Lifecycle.Event.ON_START -> if (hasStopped) { launchFillEpoch++; hasStopped = false }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Bottom-bar navigation (also used by in-app "go to tab" links). Re-tapping the selected tab
     * pops back to its root; other tabs save/restore their state under the graph's start
     * destination. Reads the controller directly so it never acts on a stale route.
     */
    fun navigateToTab(target: String) {
        val selected = tabNow()
        if (target == selected) {
            if (target == AppRoutes.SETTINGS) settingsPopToRootTick++
            if (!nav.popBackStack(target, inclusive = false) && routeNow() != target) {
                // The tab root is not in the stack (a Browse page pushed from Summary): open it fresh.
                nav.navigate(target) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                    launchSingleTop = true
                }
            }
            return
        }
        val start = nav.graph.findStartDestination()
        if (target == start.route) {
            // Tapping the start destination needs popBackStack: navigate(start) { popUpTo(start) }
            // is a no-op when it is already at the bottom, so the bar would keep the old tab.
            if (!nav.popBackStack(start.id, inclusive = false, saveState = true)) {
                nav.navigate(target) { launchSingleTop = true }
            }
        } else {
            nav.navigate(target) {
                popUpTo(start.id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    /** Switches to Browse and shows [route] directly above its root (Nutrition, Fasting, Medications…). */
    fun openBrowsePlace(route: String) {
        if (tabNow() != AppRoutes.BROWSE) navigateToTab(AppRoutes.BROWSE)
        nav.popBackStack(AppRoutes.BROWSE, inclusive = false)
        nav.navigate(route) { launchSingleTop = true }
    }

    /** Browse › Nutrition, optionally opening a food log method (or the + menu) there. */
    fun openNutrition(request: FoodLogRequest? = null) {
        if (request != null) foodLogRequest = request
        if (routeNow() != AppRoutes.BROWSE_NUTRITION) openBrowsePlace(AppRoutes.BROWSE_NUTRITION)
    }

    /**
     * Browse › Medications (reminder taps, Summary "Log a dose"), plus the medication's detail when
     * [medicationId] is given.
     */
    fun openMedications(medicationId: String? = null) {
        openBrowsePlace(AppRoutes.MEDICATIONS)
        medicationId?.let { nav.navigate(AppRoutes.medicationDetail(it)) }
    }

    /** Records tab with its "Add record" sheet open. */
    fun openAddRecord() {
        recordsAddRequest = System.nanoTime()
        if (tabNow() != AppRoutes.RECORDS) navigateToTab(AppRoutes.RECORDS)
        nav.popBackStack(AppRoutes.RECORDS, inclusive = false)
    }

    /** Summary tab root (pops anything pushed on top of it). */
    fun openSummaryRoot() {
        if (tabNow() != AppRoutes.SUMMARY) navigateToTab(AppRoutes.SUMMARY)
        nav.popBackStack(AppRoutes.SUMMARY, inclusive = false)
    }

    /** Settings tab on one page (Browse footer, widget actions whose tracking is off). */
    fun openSettingsPage(page: SettingsPage) {
        settingsPageRequest = SettingsPageRequest(page)
        navigateToTab(AppRoutes.SETTINGS)
    }

    /** §27 entry points: select records, prefill the prompt and open the Coach tab. */
    fun askCoach(recordIds: List<String>, prompt: String) {
        container.coachRecordsRequests.value = com.ayuvo.health.records.coach.CoachRecordsRequest(recordIds, prompt)
        navigateToTab(AppRoutes.COACH)
    }

    /** Workouts log ↔ Exercise Library: go back when the other mode is directly below, else replace. */
    fun switchWorkoutMode(to: String, from: String) {
        if (nav.previousBackStackEntry?.destination?.route == to) {
            nav.popBackStack()
        } else {
            nav.navigate(to) { popUpTo(from) { inclusive = true } }
        }
    }

    val metricDestinations = AppMetricDestinations(
        openFoodDiary = { openNutrition() },
        openFasting = { openBrowsePlace(AppRoutes.BROWSE_FASTING) },
        openWorkouts = { nav.navigate(AppRoutes.WORKOUTS_LOG) }
    )

    // Food quick actions (widget taps, app shortcuts, notification actions) land on
    // Browse › Nutrition, whose diary consumes the request (docs/ui-structure.md §10).
    LaunchedEffect(quickActionRequest?.id, currentRoute) {
        if (quickActionRequest != null && currentRoute != null && currentRoute != AppRoutes.ONBOARDING &&
            currentRoute != AppRoutes.BROWSE_NUTRITION
        ) {
            openNutrition()
        }
    }

    // Widget taps (docs/widgets.md): food actions land on Nutrition like the + food menu, other
    // Quick Log actions run the Summary "+" entry, metric tiles open their detail from Summary.
    LaunchedEffect(widgetRequest?.id, currentRoute) {
        val request = widgetRequest ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == AppRoutes.ONBOARDING) return@LaunchedEffect
        onWidgetRequestHandled(request.id)
        when (val target = request.target) {
            is WidgetTarget.Log -> {
                val action = target.action
                val entry = action.logEntry
                if (action.isFood || entry == null) {
                    openNutrition(FoodLogRequest(method = action.foodMethod))
                } else {
                    openSummaryRoot()
                    summaryLogRequest = SummaryLogRequest(entry)
                }
            }
            is WidgetTarget.Metric -> when (WidgetMetric.fromKey(target.key)?.tap) {
                WidgetMetric.Tap.FASTING -> openBrowsePlace(AppRoutes.BROWSE_FASTING)
                WidgetMetric.Tap.MEDICATIONS -> openMedications()
                else -> {
                    openSummaryRoot()
                    MetricKey.parse(target.key)?.let { nav.navigate(AppRoutes.metric(it)) }
                }
            }
            WidgetTarget.Summary -> openSummaryRoot()
        }
    }

    // Share / "Open in" landed records: switch to the Records tab once the app is past onboarding.
    // The import itself already started in MainActivity; the Records screen shows its notice.
    LaunchedEffect(recordsRequest?.id, currentRoute) {
        val request = recordsRequest ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == AppRoutes.ONBOARDING) return@LaunchedEffect
        if (tabNow() != AppRoutes.RECORDS || AppRoutes.isRecordsChildRoute(currentRoute)) {
            navigateToTab(AppRoutes.RECORDS)
            nav.popBackStack(AppRoutes.RECORDS, inclusive = false)
        }
        onRecordsRequestHandled(request.id)
    }

    // A medication reminder tap lands on Browse › Medications (docs/medications.md §16) and, when
    // the intent names a medicine, pushes its detail on top.
    LaunchedEffect(medicationRequest?.id, currentRoute) {
        val request = medicationRequest ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == AppRoutes.ONBOARDING) return@LaunchedEffect
        openMedications(request.medicationId)
        onMedicationRequestHandled(request.id)
    }

    /** A catalog `screen` from an action result (docs/actions.md) → the existing destinations. */
    fun openActionScreen(screen: String) {
        val (kind, value) = screen.substringBefore(':') to screen.substringAfter(':')
        fun section(id: String) {
            when (id) {
                "summary" -> openSummaryRoot()
                "browse", "health" -> navigateToTab(AppRoutes.BROWSE)
                "nutrition" -> openNutrition()
                "water" -> openActionScreen("metric:app:water")
                "fasting" -> openBrowsePlace(AppRoutes.BROWSE_FASTING)
                "body" -> openBrowsePlace(AppRoutes.BROWSE_BODY)
                "activity" -> openBrowsePlace(AppRoutes.BROWSE_ACTIVITY)
                "workouts", "workout_log" -> openBrowsePlace(AppRoutes.WORKOUTS_LOG)
                "medications" -> openMedications()
                "records" -> { navigateToTab(AppRoutes.RECORDS); nav.popBackStack(AppRoutes.RECORDS, inclusive = false) }
                "coach" -> navigateToTab(AppRoutes.COACH)
                "settings" -> navigateToTab(AppRoutes.SETTINGS)
            }
        }
        when (kind) {
            "metric" -> MetricKey.parse(value)?.let { key ->
                openSummaryRoot()
                nav.navigate(AppRoutes.metric(key))
            }
            "screen", "section" -> section(value)
            "tab" -> section(value)
            "record" -> if (value.isNotEmpty()) {
                section("records")
                nav.navigate(AppRoutes.recordDetail(value))
            }
        }
    }

    /** A write that arrived without its value opens the matching in-app logger instead. */
    fun openActionLogger(actionId: String): Boolean {
        when (actionId) {
            "water.log" -> { openSummaryRoot(); summaryLogRequest = SummaryLogRequest(LogEntry.WATER) }
            "weight.log" -> { openSummaryRoot(); summaryLogRequest = SummaryLogRequest(LogEntry.WEIGHT) }
            "body.fat.log" -> { openSummaryRoot(); summaryLogRequest = SummaryLogRequest(LogEntry.BODY_FAT) }
            "body.measurement.log" -> openBrowsePlace(AppRoutes.BROWSE_MEASUREMENTS)
            "nutrition.food.log", "nutrition.food.logSaved" -> openNutrition(FoodLogRequest(method = null))
            "workout.set.log" -> openBrowsePlace(AppRoutes.WORKOUTS_LOG)
            "medication.dose.mark" -> openMedications()
            "goals.update" -> openSettingsPage(SettingsPage.GOALS_TARGETS)
            else -> return false
        }
        return true
    }

    val snackbarHost = remember { SnackbarHostState() }
    ActionHost(
        container = container,
        pending = pendingAction,
        ready = currentRoute != null && currentRoute != AppRoutes.ONBOARDING,
        onHandled = onActionHandled,
        snackbar = snackbarHost,
        navigation = ActionNavigation(
            openScreen = ::openActionScreen,
            openLogger = ::openActionLogger,
            openHealthSync = { openSettingsPage(SettingsPage.HEALTH_SYNC) }
        )
    )

    CompositionLocalProvider(LocalLaunchFillEpoch provides launchFillEpoch) {
    Scaffold(
        // Resource ids for uiautomator walkthroughs (docs/ui-structure.md §9).
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        // The docked tab bar owns the navigation-bar inset; content is padded by the
        // bar only, so screens keep handling the status bar themselves (TabInset).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (showTabs) {
                AppBottomNavBar(
                    currentRoute = selectedTabRoute,
                    showAboutBadge = updateAvailable,
                    onTap = ::navigateToTab
                )
            }
        }
    ) { inner ->
        val barPadding = PaddingValues(bottom = inner.calculateBottomPadding())
        Box(
            Modifier
                .fillMaxSize()
                .padding(barPadding)
                .consumeWindowInsets(barPadding)
        ) {
            NavHost(
                navController = nav,
                startDestination = if (startOnboarding) AppRoutes.ONBOARDING else AppRoutes.SUMMARY
            ) {
                composable(AppRoutes.ONBOARDING) {
                    OnboardingScreen(container = container, onComplete = {
                        settingsViewModel.refreshAiConfiguration()
                        // Summary becomes the start destination, so tab switches pop up to it.
                        nav.graph.setStartDestination(AppRoutes.SUMMARY)
                        nav.navigate(AppRoutes.SUMMARY) {
                            popUpTo(AppRoutes.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    })
                }
                composable(AppRoutes.SUMMARY) {
                    TabInset {
                        SummaryScreen(
                            container = container,
                            destinations = SummaryDestinations(
                                openMetric = { key -> nav.navigate(AppRoutes.metric(key)) },
                                openBrowse = { navigateToTab(AppRoutes.BROWSE) },
                                openNutrition = { request -> openNutrition(request) },
                                openFasting = { openBrowsePlace(AppRoutes.BROWSE_FASTING) },
                                openWorkouts = { nav.navigate(AppRoutes.WORKOUTS_LOG) },
                                openMedications = { nav.navigate(AppRoutes.MEDICATIONS) },
                                addMedication = { nav.navigate(AppRoutes.medicationAdd()) },
                                openRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) },
                                addRecord = { openAddRecord() },
                                openSettings = { navigateToTab(AppRoutes.SETTINGS) },
                                openSettingsPage = { page -> openSettingsPage(page) }
                            ),
                            logRequest = summaryLogRequest,
                            onLogRequestHandled = { id -> if (summaryLogRequest?.id == id) summaryLogRequest = null }
                        )
                    }
                }
                composable(AppRoutes.BROWSE) {
                    TabInset {
                        BrowseScreen(
                            container = container,
                            onOpenTarget = { target ->
                                when {
                                    target == "screen:nutrition" -> nav.navigate(AppRoutes.BROWSE_NUTRITION)
                                    target == "screen:fasting" -> nav.navigate(AppRoutes.BROWSE_FASTING)
                                    target == "screen:body" -> nav.navigate(AppRoutes.BROWSE_BODY)
                                    target == "screen:activity" -> nav.navigate(AppRoutes.BROWSE_ACTIVITY)
                                    target == "screen:medications" -> nav.navigate(AppRoutes.MEDICATIONS)
                                    target == "tab:records" -> navigateToTab(AppRoutes.RECORDS)
                                    target.startsWith("metric:") ->
                                        MetricKey.parse(target.removePrefix("metric:"))?.let { nav.navigate(AppRoutes.metric(it)) }
                                    target.startsWith("category:") -> nav.navigate(AppRoutes.browseCategory(target.removePrefix("category:")))
                                }
                            },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) },
                            onOpenHealthSync = {
                                // Browse footer → Settings › Data & Privacy › Health Sync.
                                openSettingsPage(SettingsPage.HEALTH_SYNC)
                            },
                            onOpenFeature = { feature ->
                                // Browse search "Features" (ids shared with iOS); logWeight / logBodyFat open in Browse.
                                when (feature.id) {
                                    "workouts", "workoutLog" -> nav.navigate(AppRoutes.WORKOUTS_LOG)
                                    "exerciseLibrary" -> nav.navigate(AppRoutes.WORKOUTS_LIBRARY)
                                    "nutrition" -> nav.navigate(AppRoutes.BROWSE_NUTRITION)
                                    "logFood" -> openNutrition(FoodLogRequest(method = null))
                                    "water" -> nav.navigate(AppRoutes.metric(MetricKey.App(com.ayuvo.health.data.metrics.AppMetricId.WATER)))
                                    "fasting" -> nav.navigate(AppRoutes.BROWSE_FASTING)
                                    "bodyMeasurements" -> nav.navigate(AppRoutes.BROWSE_MEASUREMENTS)
                                    "medications" -> nav.navigate(AppRoutes.MEDICATIONS)
                                    "addMedication" -> nav.navigate(AppRoutes.medicationAdd())
                                    "records" -> navigateToTab(AppRoutes.RECORDS)
                                    "addRecord" -> openAddRecord()
                                    "coach" -> navigateToTab(AppRoutes.COACH)
                                    "settings" -> navigateToTab(AppRoutes.SETTINGS)
                                }
                            }
                        )
                    }
                }
                composable(AppRoutes.BROWSE_NUTRITION) {
                    TabInset {
                        NutritionScreen(
                            container = container,
                            onBack = { nav.popBackStack() },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) },
                            onOpenNutrients = { nav.navigate(AppRoutes.BROWSE_NUTRIENTS) },
                            quickActionRequest = quickActionRequest,
                            onQuickActionHandled = onQuickActionHandled,
                            logRequest = foodLogRequest,
                            onLogRequestHandled = { id -> if (foodLogRequest?.id == id) foodLogRequest = null }
                        )
                    }
                }
                composable(AppRoutes.BROWSE_NUTRIENTS) {
                    TabInset {
                        NutrientListScreen(
                            container = container,
                            onBack = { nav.popBackStack() },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) }
                        )
                    }
                }
                composable(AppRoutes.BROWSE_FASTING) {
                    TabInset {
                        FastingScreen(
                            container = container,
                            onBack = { nav.popBackStack() },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) }
                        )
                    }
                }
                composable(AppRoutes.BROWSE_BODY) {
                    TabInset {
                        BodyScreen(
                            container = container,
                            onBack = { nav.popBackStack() },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) },
                            onOpenMeasurements = { nav.navigate(AppRoutes.BROWSE_MEASUREMENTS) }
                        )
                    }
                }
                composable(AppRoutes.BROWSE_MEASUREMENTS) {
                    TabInset { BodyMeasurementsScreen(container = container, onBack = { nav.popBackStack() }) }
                }
                composable(AppRoutes.BROWSE_ACTIVITY) {
                    TabInset {
                        ActivityScreen(
                            container = container,
                            onBack = { nav.popBackStack() },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) },
                            onOpenWorkouts = { nav.navigate(AppRoutes.WORKOUTS_LOG) },
                            onOpenLibrary = { nav.navigate(AppRoutes.WORKOUTS_LIBRARY) }
                        )
                    }
                }
                composable(
                    AppRoutes.BROWSE_CATEGORY,
                    arguments = listOf(navArgument(AppRoutes.CATEGORY_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val categoryId = entry.arguments?.getString(AppRoutes.CATEGORY_ID_ARG) ?: return@composable
                    TabInset {
                        HealthCategoryScreen(
                            container = container,
                            categoryId = categoryId,
                            onBack = { nav.popBackStack() },
                            onOpenMetric = { key -> nav.navigate(AppRoutes.metric(key)) }
                        )
                    }
                }
                composable(AppRoutes.WORKOUTS_LOG) {
                    TabInset {
                        WorkoutsScreen(
                            container = container,
                            forcedMode = WorkoutTabMode.LOG,
                            onOpenLibrary = { switchWorkoutMode(AppRoutes.WORKOUTS_LIBRARY, AppRoutes.WORKOUTS_LOG) },
                            onOpenLog = {},
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(AppRoutes.WORKOUTS_LIBRARY) {
                    TabInset {
                        WorkoutsScreen(
                            container = container,
                            forcedMode = WorkoutTabMode.LIBRARY,
                            onOpenLibrary = {},
                            onOpenLog = { switchWorkoutMode(AppRoutes.WORKOUTS_LOG, AppRoutes.WORKOUTS_LIBRARY) },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(
                    AppRoutes.HEALTH_TYPE,
                    arguments = listOf(navArgument(AppRoutes.HEALTH_TYPE_ARG) { type = NavType.StringType })
                ) { entry ->
                    val typeKey = entry.arguments?.getString(AppRoutes.HEALTH_TYPE_ARG) ?: return@composable
                    TabInset {
                        MetricDetailScreen(
                            container = container,
                            key = MetricKey.Health(typeKey),
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(
                    AppRoutes.METRIC,
                    arguments = listOf(navArgument(AppRoutes.METRIC_KEY_ARG) { type = NavType.StringType })
                ) { entry ->
                    val key = entry.arguments?.getString(AppRoutes.METRIC_KEY_ARG)?.let(MetricKey::parse) ?: return@composable
                    TabInset {
                        MetricDetailScreen(
                            container = container,
                            key = key,
                            onBack = { nav.popBackStack() },
                            destinations = metricDestinations
                        )
                    }
                }
                composable(AppRoutes.MEDICATIONS) {
                    TabInset {
                        MedicationsScreen(
                            container = container,
                            onOpenMedication = { id -> nav.navigate(AppRoutes.medicationDetail(id)) },
                            onAddMedication = { nav.navigate(AppRoutes.medicationAdd()) },
                            onOpenHistory = { nav.navigate(AppRoutes.medicationHistory()) },
                            onImportFromRecord = { recordId -> nav.navigate(AppRoutes.medicationImport(recordId)) },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(
                    AppRoutes.MEDICATION_ADD,
                    arguments = listOf(navArgument(AppRoutes.RECORD_ID_ARG) { type = NavType.StringType; nullable = true; defaultValue = null })
                ) { entry ->
                    val recordId = entry.arguments?.getString(AppRoutes.RECORD_ID_ARG)
                    TabInset {
                        MedicationEditorScreen(
                            container = container,
                            medicationId = null,
                            recordId = recordId,
                            onBack = { nav.popBackStack() },
                            onSaved = { nav.popBackStack() }
                        )
                    }
                }
                composable(
                    AppRoutes.MEDICATION_EDIT,
                    arguments = listOf(navArgument(AppRoutes.MEDICATION_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val medicationId = entry.arguments?.getString(AppRoutes.MEDICATION_ID_ARG) ?: return@composable
                    TabInset {
                        MedicationEditorScreen(
                            container = container,
                            medicationId = medicationId,
                            recordId = null,
                            onBack = { nav.popBackStack() },
                            onSaved = { nav.popBackStack() }
                        )
                    }
                }
                composable(
                    AppRoutes.MEDICATION_DETAIL,
                    arguments = listOf(navArgument(AppRoutes.MEDICATION_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val medicationId = entry.arguments?.getString(AppRoutes.MEDICATION_ID_ARG) ?: return@composable
                    TabInset {
                        MedicationDetailScreen(
                            container = container,
                            medicationId = medicationId,
                            onBack = { nav.popBackStack() },
                            onEdit = { id -> nav.navigate(AppRoutes.medicationEdit(id)) },
                            onOpenHistory = { id -> nav.navigate(AppRoutes.medicationHistory(id)) },
                            onOpenRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) }
                        )
                    }
                }
                composable(
                    AppRoutes.MEDICATION_HISTORY,
                    arguments = listOf(navArgument(AppRoutes.MEDICATION_ID_ARG) { type = NavType.StringType; nullable = true; defaultValue = null })
                ) { entry ->
                    val medicationId = entry.arguments?.getString(AppRoutes.MEDICATION_ID_ARG)
                    TabInset {
                        MedicationHistoryScreen(container = container, medicationId = medicationId, onBack = { nav.popBackStack() })
                    }
                }
                composable(
                    AppRoutes.MEDICATION_IMPORT,
                    arguments = listOf(navArgument(AppRoutes.RECORD_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val recordId = entry.arguments?.getString(AppRoutes.RECORD_ID_ARG) ?: return@composable
                    TabInset {
                        ImportFromRecordScreen(
                            container = container,
                            recordId = recordId,
                            onBack = { nav.popBackStack() },
                            onDone = {
                                // Created from a record: land on Medications so the new rows are visible.
                                nav.popBackStack()
                                if (!nav.popBackStack(AppRoutes.MEDICATIONS, inclusive = false)) nav.navigate(AppRoutes.MEDICATIONS)
                            },
                            onAddManually = { id ->
                                nav.navigate(AppRoutes.medicationAdd(id)) { popUpTo(AppRoutes.medicationImport(id)) { inclusive = true } }
                            }
                        )
                    }
                }
                composable(AppRoutes.RECORDS) {
                    TabInset {
                        RecordsScreen(
                            container = container,
                            onOpenRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) },
                            onOpenValue = { recordId, observationId -> nav.navigate(AppRoutes.recordDetailAt(recordId, observationId)) },
                            onAskCoach = { ids -> askCoach(ids, "") },
                            onShare = { ids -> nav.navigate(AppRoutes.recordShare(ids)) },
                            openAddRequest = recordsAddRequest,
                            onOpenAddHandled = { id -> if (recordsAddRequest == id) recordsAddRequest = null }
                        )
                    }
                }
                composable(
                    AppRoutes.RECORD_DETAIL_FOCUS,
                    arguments = listOf(
                        navArgument(AppRoutes.RECORD_ID_ARG) { type = NavType.StringType },
                        navArgument(AppRoutes.RECORD_FOCUS_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }
                    )
                ) { entry ->
                    val recordId = entry.arguments?.getString(AppRoutes.RECORD_ID_ARG) ?: return@composable
                    val focusObservation = entry.arguments?.getString(AppRoutes.RECORD_FOCUS_ARG)
                    TabInset {
                        RecordDetailScreen(
                            container = container,
                            recordId = recordId,
                            onBack = { nav.popBackStack() },
                            onOpenRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) },
                            onOpenSplit = { id -> nav.navigate(AppRoutes.recordSplit(id)) },
                            onOpenTrend = { analyteId -> nav.navigate(AppRoutes.recordTrend(analyteId)) },
                            focusObservationId = focusObservation,
                            onAskCoach = ::askCoach,
                            onShare = { ids -> nav.navigate(AppRoutes.recordShare(ids)) },
                            onAddToMedications = { id -> nav.navigate(AppRoutes.medicationImport(id)) }
                        )
                    }
                }
                composable(
                    AppRoutes.RECORD_TREND,
                    arguments = listOf(navArgument(AppRoutes.ANALYTE_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val analyteId = entry.arguments?.getString(AppRoutes.ANALYTE_ID_ARG) ?: return@composable
                    TabInset {
                        TrendScreen(
                            container = container,
                            analyteId = analyteId,
                            onBack = { nav.popBackStack() },
                            onOpenPoint = { recordId, observationId -> nav.navigate(AppRoutes.recordDetailAt(recordId, observationId)) },
                            onAskCoach = ::askCoach
                        )
                    }
                }
                composable(
                    AppRoutes.RECORD_SPLIT,
                    arguments = listOf(navArgument(AppRoutes.RECORD_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val recordId = entry.arguments?.getString(AppRoutes.RECORD_ID_ARG) ?: return@composable
                    TabInset {
                        SplitReviewScreen(
                            container = container,
                            recordId = recordId,
                            onBack = { nav.popBackStack() },
                            onOpenRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) }
                        )
                    }
                }
                composable(
                    AppRoutes.RECORD_SHARE,
                    arguments = listOf(navArgument(AppRoutes.RECORD_IDS_ARG) { type = NavType.StringType })
                ) { entry ->
                    val ids = AppRoutes.parseRecordIds(entry.arguments?.getString(AppRoutes.RECORD_IDS_ARG))
                    if (ids.isEmpty()) return@composable
                    TabInset {
                        com.ayuvo.health.ui.records.ShareRecordScreen(
                            container = container,
                            recordIds = ids,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(AppRoutes.HEALTH_RECORDS_STORAGE) {
                    TabInset {
                        com.ayuvo.health.ui.records.RecordsStorageScreen(container = container, onBack = { nav.popBackStack() })
                    }
                }
                composable(AppRoutes.HEALTH_RECORDS_BACKUP) {
                    TabInset {
                        com.ayuvo.health.ui.records.RecordsBackupScreen(container = container, onBack = { nav.popBackStack() })
                    }
                }
                composable(AppRoutes.COACH) {
                    TabInset { CoachScreen(container = container, onOpenRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) }) }
                }
                composable(AppRoutes.SETTINGS) {
                    TabInset {
                        SettingsScreen(
                            container = container,
                            nav = nav,
                            vm = settingsViewModel,
                            popToRootTick = settingsPopToRootTick,
                            openPageRequest = settingsPageRequest,
                            onOpenPageHandled = { id -> if (settingsPageRequest?.id == id) settingsPageRequest = null },
                            onOpenBrowse = { navigateToTab(AppRoutes.BROWSE) },
                            onOpenMedications = { nav.navigate(AppRoutes.MEDICATIONS) },
                            updateAvailable = updateAvailable
                        )
                    }
                }
                composable(AppRoutes.OPTIONAL_NUTRIENT_GOALS) {
                    OptionalNutrientGoalsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(AppRoutes.CALCULATION_METHODS) {
                    CalculationMethodsScreen(onBack = { nav.popBackStack() })
                }
                composable(AppRoutes.LICENSES) {
                    LicensesScreen(onBack = { nav.popBackStack() })
                }
                composable(AppRoutes.QUICK_ACTIONS) {
                    QuickActionsScreen(vm = settingsViewModel, onBack = { nav.popBackStack() })
                }
                composable(AppRoutes.ADD_MENU) {
                    AddMenuSettingsScreen(vm = settingsViewModel, onBack = { nav.popBackStack() })
                }
                composable(AppRoutes.BODY_MEASUREMENTS) {
                    BodyMeasurementsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(AppRoutes.ALLERGEN_SENSITIVITIES) {
                    val settingsUi by settingsViewModel.ui.collectAsState()
                    AllergenSensitivitiesScreen(
                        current = settingsUi.profile?.allergenSensitivities.orEmpty(),
                        onSave = { values -> settingsViewModel.updateProfile { it.copy(allergenSensitivities = values) } },
                        onBack = { nav.popBackStack() },
                        foodAnalysis = container.foodAnalysis
                    )
                }
            }
        }
    }
    }
}

/**
 * Reserves the status-bar space above a tab's content. The top-level Scaffold
 * renders the NavHost full-screen (it discards its inset padding), so each tab
 * would otherwise draw under the status bar. This used to be handled by the ad
 * banner strip that sat above the content; with ads removed, this keeps the
 * exact same clearance. The content Box consumes the status-bar inset so a tab's
 * own Scaffold/TopAppBar doesn't pad for it a second time.
 */
@Composable
private fun TabInset(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}

internal fun NavHostController.current(): String? = currentBackStackEntry?.destination?.route
