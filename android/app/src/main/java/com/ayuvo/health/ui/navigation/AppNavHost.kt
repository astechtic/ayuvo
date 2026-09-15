package com.ayuvo.health.ui.navigation

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.ayuvo.health.ui.progress.HealthTabDestination
import com.ayuvo.health.ui.health.HealthTypeDetailScreen
import com.ayuvo.health.services.update.AndroidUpdateChecker
import com.ayuvo.health.services.update.AndroidUpdateState
import com.ayuvo.health.ui.coach.CoachScreen
import com.ayuvo.health.ui.home.HomeScreen
import com.ayuvo.health.ui.onboarding.OnboardingScreen
import com.ayuvo.health.ui.progress.BodyMeasurementsScreen
import com.ayuvo.health.ui.progress.ProgressScreen
import com.ayuvo.health.ui.settings.AllergenSensitivitiesScreen
import com.ayuvo.health.ui.settings.CalculationMethodsScreen
import com.ayuvo.health.ui.settings.OptionalNutrientGoalsScreen
import com.ayuvo.health.ui.settings.SettingsScreen
import com.ayuvo.health.ui.settings.SettingsViewModel
import com.ayuvo.health.models.WorkoutTabMode
import com.ayuvo.health.records.RecordsRequest
import com.ayuvo.health.ui.records.RecordDetailScreen
import com.ayuvo.health.ui.records.RecordsScreen
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.ui.settings.AddMenuSettingsScreen
import com.ayuvo.health.ui.about.LicensesScreen
import com.ayuvo.health.ui.settings.QuickActionsScreen

/**
 * Increments each time the app is opened: 1 on cold launch, then +1 on every
 * return from the background (ON_START after a real ON_STOP). Read by the Home
 * gauge + macro bars to replay their fill-from-zero reveal. It lives above the
 * NavHost, so tab switches (which recompose Home) never change it.
 */
val LocalLaunchFillEpoch = compositionLocalOf { 1 }

private const val WORKOUT_UI_PREFS = "ayuvo_workouts"
private const val WORKOUT_MODE_V2_DEFAULT_KEY = "mode.diary_default.v2"

@Composable
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
fun AppNavHost(
    container: AppContainer,
    startOnboarding: Boolean,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    recordsRequest: RecordsRequest? = null,
    onRecordsRequestHandled: (Long) -> Unit = {}
) {
    val nav = rememberNavController()
    // Warm the app-scoped Settings state while Home is visible. By the time the user changes
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
    // Match iOS's versioned AppStorage key: reset the former library-first
    // default once (see the LaunchedEffect below), then keep every user switch persistent.
    // Nested settings/* screens keep the tab bar visible and highlight Settings,
    // so re-tapping the Settings icon can return to the hub (iOS parity).
    val selectedTabRoute = AppRoutes.selectedBottomTab(currentRoute)
    val showTabs = selectedTabRoute != null && !analyzing
    // Bumped when the Settings tab is re-tapped so SettingsScreen can pop back to the hub
    // (matches iOS TabView + NavigationStack reselect → root behavior).
    var settingsPopToRootTick by remember { mutableIntStateOf(0) }
    val currentVersion = remember(context) { AndroidUpdateChecker.currentVersion(context) }
    var updateAvailable by remember { mutableStateOf(false) }
    // One-shot request to land on a Health tab segment (Home "See All" / Settings "All Health Data" /
    // Home "Workouts").
    var healthTabRequest by remember { mutableStateOf<HealthTabDestination?>(null) }

    // Settings is intentionally warmed before onboarding finishes. Reload the values that
    // onboarding can change outside Settings so the first visit never shows the pre-onboarding
    // provider/model/API-key snapshot (issue #170). Re-running this on later visits also keeps
    // the app-scoped ViewModel honest if another flow updates the stored AI configuration.
    LaunchedEffect(currentRoute) {
        if (currentRoute == AppRoutes.SETTINGS) {
            settingsViewModel.refreshAiConfiguration()
        }
    }

    LaunchedEffect(quickActionRequest?.id, currentRoute) {
        if (quickActionRequest != null &&
            currentRoute != AppRoutes.HOME &&
            currentRoute != AppRoutes.ONBOARDING
        ) {
            nav.navigate(AppRoutes.HOME) {
                popUpTo(AppRoutes.HOME) { inclusive = false }
                launchSingleTop = true
            }
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

    // App-open epoch for the Home fill-from-zero reveal. Bumped only on ON_START
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

    // The bar memoises its tap lambda against `onTap` (a function reference compares equal across
    // recompositions), so the navigator must read the *latest* route state rather than the values
    // captured when it was first created.
    val latestRoute = rememberUpdatedState(currentRoute)
    val latestSelectedTab = rememberUpdatedState(selectedTabRoute)

    /**
     * Bottom-bar navigation shared by the bar itself, Home's "See All" and Settings' "All Health
     * Data": re-tapping the selected tab pops any pushed detail (Settings children, Health type
     * details); other tabs save/restore their state.
     */
    fun navigateToTab(target: String) {
        val route = latestRoute.value
        val selectedTab = latestSelectedTab.value
        if (target == AppRoutes.SETTINGS) {
            val onSettingsRoot = route == AppRoutes.SETTINGS
            val onSettingsChild = route?.startsWith("settings/") == true
            if (onSettingsRoot || onSettingsChild) {
                settingsPopToRootTick++
                if (onSettingsChild) {
                    nav.popBackStack(AppRoutes.SETTINGS, inclusive = false)
                }
                return
            }
        }
        // A Health Data type detail is pushed over whichever tab opened it; re-tapping Health
        // from the detail pops to the tab, like Settings' nested screens.
        if (target == AppRoutes.HEALTH && AppRoutes.isHealthDetailRoute(route)) {
            if (!nav.popBackStack(AppRoutes.HEALTH, inclusive = false)) {
                // The detail was pushed from Home: open the tab on its Health Data segment.
                healthTabRequest = HealthTabDestination.HEALTH_DATA
                nav.navigate(AppRoutes.HEALTH) {
                    popUpTo(AppRoutes.HOME) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            return
        }
        // Re-tapping Records from a pushed record screen pops back to the Records home.
        if (target == AppRoutes.RECORDS && AppRoutes.isRecordsChildRoute(route)) {
            if (!nav.popBackStack(AppRoutes.RECORDS, inclusive = false)) {
                nav.navigate(AppRoutes.RECORDS) {
                    popUpTo(AppRoutes.HOME) { saveState = true }
                    launchSingleTop = true
                }
            }
            return
        }
        if (target == selectedTab) return
        // Tapping HOME (the start destination) needs popBackStack
        // — `navigate(HOME) { popUpTo(HOME); launchSingleTop = true }`
        // is a no-op because NavController sees HOME at the top of
        // the stack and skips re-emitting currentBackStackEntry, so
        // the bar stays selected on the previous tab.
        if (target == AppRoutes.HOME) {
            // saveState keeps the popped tab's UI state (e.g. the Health tab's segment) for restoreState.
            nav.popBackStack(AppRoutes.HOME, inclusive = false, saveState = true)
        } else {
            nav.navigate(target) {
                popUpTo(AppRoutes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Share / "Open in" landed records: switch to the Records tab once the app is past onboarding.
    // The import itself already started in MainActivity; the Records screen shows its notice.
    LaunchedEffect(recordsRequest?.id, currentRoute) {
        val request = recordsRequest ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == AppRoutes.ONBOARDING) return@LaunchedEffect
        if (AppRoutes.selectedBottomTab(currentRoute) != AppRoutes.RECORDS || AppRoutes.isRecordsChildRoute(currentRoute)) {
            navigateToTab(AppRoutes.RECORDS)
        }
        onRecordsRequestHandled(request.id)
    }

    CompositionLocalProvider(LocalLaunchFillEpoch provides launchFillEpoch) {
    Scaffold(
        bottomBar = {
            if (showTabs) {
                AppBottomNavBar(
                    currentRoute = selectedTabRoute,
                    showAboutBadge = updateAvailable,
                    onTap = ::navigateToTab
                )
            }
        }
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = if (startOnboarding) AppRoutes.ONBOARDING else AppRoutes.HOME
            ) {
                composable(AppRoutes.ONBOARDING) {
                    OnboardingScreen(container = container, onComplete = {
                        settingsViewModel.refreshAiConfiguration()
                        nav.navigate(AppRoutes.HOME) {
                            popUpTo(AppRoutes.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    })
                }
                composable(AppRoutes.HOME) {
                    TabInset {
                        HomeScreen(
                            container = container,
                            quickActionRequest = quickActionRequest,
                            onQuickActionHandled = onQuickActionHandled,
                            onOpenHealth = {
                                healthTabRequest = HealthTabDestination.HEALTH_DATA
                                navigateToTab(AppRoutes.HEALTH)
                            },
                            onOpenHealthType = { key -> nav.navigate(AppRoutes.healthType(key)) },
                            onOpenWorkouts = {
                                healthTabRequest = HealthTabDestination.WORKOUTS
                                navigateToTab(AppRoutes.HEALTH)
                            }
                        )
                    }
                }
                composable(
                    AppRoutes.HEALTH_TYPE,
                    arguments = listOf(navArgument(AppRoutes.HEALTH_TYPE_ARG) { type = NavType.StringType })
                ) { entry ->
                    val typeKey = entry.arguments?.getString(AppRoutes.HEALTH_TYPE_ARG) ?: return@composable
                    HealthTypeDetailScreen(
                        container = container,
                        typeKey = typeKey,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(AppRoutes.HEALTH) {
                    TabInset {
                        ProgressScreen(
                            container = container,
                            requestedDestination = healthTabRequest,
                            onRequestConsumed = { healthTabRequest = null },
                            onOpenType = { key -> nav.navigate(AppRoutes.healthType(key)) }
                        )
                    }
                }
                composable(AppRoutes.RECORDS) {
                    TabInset {
                        RecordsScreen(
                            container = container,
                            onOpenRecord = { id -> nav.navigate(AppRoutes.recordDetail(id)) }
                        )
                    }
                }
                composable(
                    AppRoutes.RECORD_DETAIL,
                    arguments = listOf(navArgument(AppRoutes.RECORD_ID_ARG) { type = NavType.StringType })
                ) { entry ->
                    val recordId = entry.arguments?.getString(AppRoutes.RECORD_ID_ARG) ?: return@composable
                    TabInset {
                        RecordDetailScreen(
                            container = container,
                            recordId = recordId,
                            onBack = { nav.popBackStack() },
                            onOpenRecord = { id ->
                                nav.navigate(AppRoutes.recordDetail(id)) {
                                    popUpTo(AppRoutes.RECORDS) { inclusive = false }
                                }
                            }
                        )
                    }
                }
                composable(AppRoutes.COACH) { TabInset { CoachScreen(container = container) } }
                composable(AppRoutes.SETTINGS) {
                    TabInset {
                        SettingsScreen(
                            container = container,
                            nav = nav,
                            vm = settingsViewModel,
                            popToRootTick = settingsPopToRootTick,
                            onOpenHealthData = {
                                healthTabRequest = HealthTabDestination.HEALTH_DATA
                                navigateToTab(AppRoutes.HEALTH)
                            }
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
