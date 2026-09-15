package com.ayuvo.health

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.services.QuickActionShortcutManager
import com.ayuvo.health.services.ReviewPrompter
import com.ayuvo.health.ui.navigation.AppNavHost
import com.ayuvo.health.ui.theme.AppThemeColor
import com.ayuvo.health.ui.theme.AyuvoTheme
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class StartupPrefs(
    val startOnboarding: Boolean,
    val appearance: String,
    val themeColorKey: String
)

class MainActivity : ComponentActivity() {
    private var pendingQuickAction by mutableStateOf<QuickActionRequest?>(null)
    private var startupPrefs by mutableStateOf<StartupPrefs?>(null)
    private var contentReady by mutableStateOf(false)

    private fun handleQuickActionIntent(intent: Intent?) {
        val action = QuickActionShortcutManager.actionFrom(intent) ?: return
        pendingQuickAction = QuickActionRequest(action)
        intent?.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickActionIntent(intent)
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            // Adaptive Goals auto-runs the full goal calculation about once a week (Energy Burn,
            // when on, supplies the measured-burn anchor it consumes — separate toggle).
            val container = (application as AyuvoApp).container
            container.refreshAdaptiveGoalsIfNeeded()
            // Pull any new external weight / body-fat readings (e.g. a Withings scale)
            // from Health Connect into the app on every foreground (issue #91), then run
            // the Health Data hub mirror sync. requestHealthSync launches on the container
            // scope so a quick background/foreground cycle cannot cancel it mid-page.
            container.requestHealthSync(com.ayuvo.health.services.health.HealthSyncTrigger.APP_OPEN)
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            runCatching { (application as AyuvoApp).container.cloudBackup.autoBackupIfNeeded() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the system swaps the splash theme
        // back to Theme.Ayuvo before the first frame, preventing a white flash
        // on cold start. The splash uses a transparent foreground mark over
        // the app's light/dark splash background.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Launch the Play in-app review card once, right after the first
        // successful food log (see ReviewPrompter). Silently no-ops on devices
        // without Play services or when Play declines to show the card.
        lifecycleScope.launch {
            ReviewPrompter.requestReview.collect { wanted ->
                if (!wanted) return@collect
                ReviewPrompter.consumed()
                delay(1_500)
                runCatching {
                    val manager = ReviewManagerFactory.create(this@MainActivity)
                    val info = manager.requestReview()
                    manager.launchReview(this@MainActivity, info)
                }
            }
        }

        val container = (application as AyuvoApp).container

        // Support --reset-onboarding launch flag (parallel to iOS CLAUDE.md convention).
        if (intent?.getBooleanExtra("reset_onboarding", false) == true) {
            lifecycleScope.launch {
                container.prefs.setOnboardingCompleted(false)
            }
            intent.removeExtra("reset_onboarding")
        }

        handleQuickActionIntent(intent)

        lifecycleScope.launch {
            combine(
                container.prefs.quickAction1,
                container.prefs.quickAction2,
                container.prefs.quickAction3
            ) { first, second, third -> listOf(first, second, third) }
                .collect { QuickActionShortcutManager.update(this@MainActivity, it) }
        }

        // Hold the splash on screen until startup prefs and (when needed) the saved
        // profile have loaded from DataStore so Home doesn't briefly render its
        // 2000/150/220/70 fallback goal numbers before snapping to real targets.
        splashScreen.setKeepOnScreenCondition { startupPrefs == null || !contentReady }
        lifecycleScope.launch {
            val startOnboarding = !container.prefs.hasCompletedOnboarding.first()
            startupPrefs = StartupPrefs(
                startOnboarding = startOnboarding,
                appearance = container.prefs.appearanceMode.first(),
                themeColorKey = container.prefs.appThemeColor.first()
            )
            if (startOnboarding) {
                contentReady = true
            } else {
                container.profileRepository.profile.first { it != null }
                contentReady = true
            }
        }

        setContent {
            val startup = startupPrefs ?: return@setContent
            val appearance by container.prefs.appearanceMode.collectAsState(initial = startup.appearance)
            val themeColorKey by container.prefs.appThemeColor.collectAsState(initial = startup.themeColorKey)
            val themeColor = AppThemeColor.fromKey(themeColorKey)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (appearance) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            AyuvoTheme(darkTheme = darkTheme, themeColor = themeColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        container = container,
                        startOnboarding = startup.startOnboarding,
                        quickActionRequest = pendingQuickAction,
                        onQuickActionHandled = { requestID ->
                            if (pendingQuickAction?.id == requestID) pendingQuickAction = null
                        }
                    )
                }
            }
        }
    }
}
