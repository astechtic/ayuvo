package com.ayuvo.health

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
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
import com.ayuvo.health.medications.model.MedicationIntents
import com.ayuvo.health.medications.model.MedicationRequest
import com.ayuvo.health.models.LogEntryIntents
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.models.WidgetRequest
import com.ayuvo.health.records.RecordsRequest
import com.ayuvo.health.records.ingest.ImportItem
import com.ayuvo.health.records.ingest.ImportSpec
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.RecordSource
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
    private var pendingRecordsRequest by mutableStateOf<RecordsRequest?>(null)
    private var pendingMedicationRequest by mutableStateOf<MedicationRequest?>(null)
    private var pendingWidgetRequest by mutableStateOf<WidgetRequest?>(null)
    private var startupPrefs by mutableStateOf<StartupPrefs?>(null)
    private var contentReady by mutableStateOf(false)

    private fun handleQuickActionIntent(intent: Intent?) {
        val action = QuickActionShortcutManager.actionFrom(intent) ?: return
        pendingQuickAction = QuickActionRequest(action)
        intent?.action = null
    }

    /** A medication reminder tap: open the Meds segment (and the medicine when the intent names one). */
    private fun handleMedicationIntent(intent: Intent?) {
        val request = MedicationIntents.requestFrom(intent) ?: return
        pendingMedicationRequest = request
        intent?.action = null
    }

    /** A Today / My Metrics / Quick Log widget tap (docs/widgets.md). */
    private fun handleWidgetIntent(intent: Intent?) {
        val request = LogEntryIntents.requestFrom(intent) ?: return
        pendingWidgetRequest = request
        intent?.action = null
    }

    /**
     * Share sheet (SEND / SEND_MULTIPLE) and "Open in" (VIEW): the streams are copied right away
     * on the app scope while the URI grant is still valid, then the Records tab is requested.
     */
    private fun handleIncomingRecordsIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val isView = action == Intent.ACTION_VIEW
        val items: List<ImportItem> = when (action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                when {
                    stream != null -> listOf(ImportItem.FromUri(stream))
                    !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank() ->
                        listOf(ImportItem.FromText(intent.getStringExtra(Intent.EXTRA_TEXT)!!))
                    else -> emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
                    .ifEmpty {
                        val clip = intent.clipData
                        if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    }
                streams.map { ImportItem.FromUri(it) }
            }
            Intent.ACTION_VIEW -> listOfNotNull(intent.data?.let { ImportItem.FromUri(it) })
            else -> emptyList()
        }
        if (items.isEmpty()) return
        val textOnly = items.all { it is ImportItem.FromText }
        val spec = ImportSpec(
            source = if (isView) RecordSource.OPEN_IN else RecordSource.SHARE_IN,
            method = if (isView) ImportMethod.OPEN_IN else ImportMethod.SHARE_SHEET,
            userTitle = if (textOnly) intent.getStringExtra(Intent.EXTRA_SUBJECT) else null,
            sourceApp = referrer?.host
        )
        (application as AyuvoApp).container.recordsImports.import(items, spec)
        pendingRecordsRequest = RecordsRequest()
        intent.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickActionIntent(intent)
        handleMedicationIntent(intent)
        handleWidgetIntent(intent)
        handleIncomingRecordsIntent(intent)
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
        val container = (application as AyuvoApp).container
        container.scope.launch {
            runCatching { container.cloudBackup.autoBackupIfNeeded() }
            // docs/health-records.md §36: the opt-in records archive follows the normal backup.
            container.backupRecordsToDriveIfNeeded()
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
        handleMedicationIntent(intent)
        if (savedInstanceState == null) handleWidgetIntent(intent)
        // A recreated activity (rotation, process restore) still carries the original share intent.
        if (savedInstanceState == null) handleIncomingRecordsIntent(intent)

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
                        },
                        recordsRequest = pendingRecordsRequest,
                        onRecordsRequestHandled = { requestID ->
                            if (pendingRecordsRequest?.id == requestID) pendingRecordsRequest = null
                        },
                        medicationRequest = pendingMedicationRequest,
                        onMedicationRequestHandled = { requestID ->
                            if (pendingMedicationRequest?.id == requestID) pendingMedicationRequest = null
                        },
                        widgetRequest = pendingWidgetRequest,
                        onWidgetRequestHandled = { requestID ->
                            if (pendingWidgetRequest?.id == requestID) pendingWidgetRequest = null
                        }
                    )
                }
            }
        }
    }
}
