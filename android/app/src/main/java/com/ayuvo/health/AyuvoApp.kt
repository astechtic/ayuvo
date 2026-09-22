package com.ayuvo.health

import android.app.Application
import android.util.Log
import com.ayuvo.health.data.BodyFatRepository
import com.ayuvo.health.data.BodyMeasurementRepository
import com.ayuvo.health.data.ChatRepository
import com.ayuvo.health.data.ExerciseRepository
import com.ayuvo.health.data.FoodRepository
import com.ayuvo.health.data.FastingRepository
import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.ProfileRepository
import com.ayuvo.health.data.WeightRepository
import com.ayuvo.health.data.WaterRepository
import com.ayuvo.health.data.WorkoutHealthSync
import com.ayuvo.health.data.WorkoutRepository
import com.ayuvo.health.backup.CloudBackupCoordinator
import com.ayuvo.health.services.FoodImageStore
import com.ayuvo.health.services.NotificationService
import com.ayuvo.health.services.WidgetSnapshotWriter
import com.ayuvo.health.services.AdaptiveGoalResult
import com.ayuvo.health.services.DailyHealthEnergyEvidence
import com.ayuvo.health.services.GoalEvidence
import com.ayuvo.health.services.GoalEvidenceBuilder
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.CurrentMealSchedule
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.data.metrics.AppMetricSeriesProvider
import com.ayuvo.health.data.metrics.FavoritePins
import com.ayuvo.health.data.metrics.MetricCatalogData
import com.ayuvo.health.data.metrics.RepositoryMetricSources
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthDatabase
import com.ayuvo.health.data.health.LocalHealthSources
import com.ayuvo.health.data.health.SqliteHealthDataStore
import com.ayuvo.health.medications.data.MedicationPhotoStore
import com.ayuvo.health.medications.data.MedicationsDatabase
import com.ayuvo.health.medications.data.MedicationsStore
import com.ayuvo.health.medications.data.SqliteMedicationsStore
import com.ayuvo.health.medications.reminders.MedicationAlarms
import com.ayuvo.health.medications.reminders.MedicationMaintenanceWorker
import com.ayuvo.health.medications.reminders.MedicationNotifications
import com.ayuvo.health.medications.reminders.MedicationReminderCoordinator
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsDatabase
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.data.SqliteRecordsStore
import com.ayuvo.health.records.ingest.RecordImporter
import com.ayuvo.health.records.ingest.RecordsImportCoordinator
import com.ayuvo.health.records.ai.RecordsAiExtractor
import com.ayuvo.health.records.ai.RecordsAiModeResolver
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.records.processing.MlKitOcrEngine
import com.ayuvo.health.records.processing.NetworkState
import com.ayuvo.health.records.processing.OcrEngine
import com.ayuvo.health.records.processing.RecordPipeline
import com.ayuvo.health.records.processing.RecordProcessingQueue
import com.ayuvo.health.records.processing.RecordRules
import com.ayuvo.health.records.processing.TextStage
import com.ayuvo.health.records.search.AiQueryRewriter
import com.ayuvo.health.ui.records.labelRes
import kotlinx.coroutines.withContext
import com.ayuvo.health.services.ai.ChatService
import com.ayuvo.health.services.ai.FoodAnalysisService
import com.ayuvo.health.services.health.HealthConnectManager
import com.ayuvo.health.services.health.HealthConnectReadSource
import com.ayuvo.health.services.health.HealthGrants
import com.ayuvo.health.services.health.HealthReadSource
import com.ayuvo.health.services.health.HealthSyncEngine
import com.ayuvo.health.services.health.HealthSyncOutcome
import com.ayuvo.health.services.health.HealthSyncTrigger
import com.ayuvo.health.services.ondevice.LocalGemmaRuntime
import com.ayuvo.health.services.ondevice.LocalModelId
import com.ayuvo.health.services.ondevice.LocalModelManager
import com.ayuvo.health.services.ondevice.LocalWhisperRuntime
import com.ayuvo.health.services.speech.SpeechService
import com.ayuvo.health.widget.WidgetRefreshScheduler
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Application-scoped singleton wiring. Manual DI (no Hilt) — repositories and
 * services are instantiated once and handed to ViewModels via [container].
 */
class AyuvoApp : Application() {

    lateinit var container: AppContainer
        private set

    // Startup work (migrations, image pruning, reminder re-arming) must never
    // take the process down: an uncaught exception here would otherwise crash
    // on every launch until the user clears app data — and their diary with it.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("AyuvoApp", "Background startup task failed", throwable)
        }
    )

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, appScope)
        container.notifications.createChannels()
        MedicationNotifications.createChannel(this)
        container.resumeRecordsProcessing()
        WidgetRefreshScheduler.onAppStarted(this)
        container.widgetSnapshotWriter.observe().launchIn(appScope)
        // Warm exercise catalog off the main thread before the first Workouts tab open.
        ExerciseRepository.warm(this)
        appScope.launch {
            container.prefs.reconcileLocalModelSelections()
            container.prefs.migrateAIModelSelections()
            container.prefs.migrateMatchingSpeechProviderIfNeeded()
            container.prefs.migrateFallbackBaseUrls()
            // Summary favourites (docs/ui-structure.md §7.9): one-time migration from healthHomeTiles.
            container.favoritePins.ensureMigrated()
        }
        // An on-device Gemma choice made before its download finished (onboarding) is applied
        // as soon as the verified model is executable — including on a cold start with it present.
        container.localModels.states
            .map { it[LocalModelId.GEMMA_4_E2B]?.executable == true }
            .distinctUntilChanged()
            .filter { it }
            .onEach { container.prefs.applyPendingLocalGemmaSelection() }
            .launchIn(appScope)
        // Prune only unreferenced JPEGs; logged foods, saved meals, and pending
        // analysis drafts remain untouched.
        appScope.launch { container.foodRepository.pruneOrphanedImages() }
        container.prefs.mealSchedule
            .onEach { CurrentMealSchedule.value = it }
            .launchIn(appScope)
        // Re-arm the daily weight-log alarm on every cold start. AlarmManager
        // drops scheduled alarms on device reboot and (sometimes) on app
        // updates — without this, a user who enabled Notifications once would
        // silently stop receiving the reminder after the next reboot.
        appScope.launch {
            // Medication reminders (docs/medications.md §10): re-plan on every cold start and keep
            // planning on every store write. Never creates the database just to look for doses.
            if (container.medicationsDatabaseExists()) {
                container.medicationReminders.start()
                MedicationMaintenanceWorker.onAppStarted(this@AyuvoApp)
            }
            if (container.prefs.fastingTrackingEnabled.first()) {
                container.notifications.ensureFastingChannel()
            }
            if (container.prefs.notificationsEnabled.first() &&
                container.notifications.canPostNotifications()
            ) {
                if (container.prefs.streakReminderEnabled.first()) {
                    container.notifications.scheduleStreakReminder(
                        container.prefs.streakReminderHour.first(),
                        container.prefs.streakReminderMinute.first()
                    )
                } else {
                    container.notifications.cancelStreakReminder()
                }
                if (container.prefs.dailySummaryEnabled.first()) {
                    container.notifications.scheduleDailySummary(
                        container.prefs.dailySummaryHour.first(),
                        container.prefs.dailySummaryMinute.first()
                    )
                } else {
                    container.notifications.cancelDailySummary()
                }
                if (container.prefs.weightReminderEnabled.first()) {
                    container.notifications.scheduleWeightReminder()
                } else {
                    container.notifications.cancelWeightReminder()
                }
                // Body-fat reminder only fires for users who've actually opted
                // into body-fat tracking and left that notification type on.
                val profile = container.profileRepository.current()
                if (container.prefs.bodyFatReminderEnabled.first() && profile?.bodyFatPercentage != null) {
                    container.notifications.scheduleBodyFatReminder()
                } else {
                    container.notifications.cancelBodyFatReminder()
                }
                if (container.prefs.waterTrackingEnabled.first() && container.prefs.waterReminderEnabled.first()) {
                    container.notifications.scheduleWaterReminder(
                        container.prefs.waterReminderHour.first(),
                        container.prefs.waterReminderMinute.first()
                    )
                } else {
                    container.notifications.cancelWaterReminder()
                }
                if (container.prefs.fastingTrackingEnabled.first() &&
                    container.prefs.fastingGoalNotificationEnabled.first()
                ) {
                    container.notifications.scheduleFastingGoal(container.fastingRepository.active())
                } else {
                    container.notifications.cancelFastingGoal()
                }
            }
        }
    }
}

/** Stable labels for the read types a Health Connect changes token was seeded for,
 *  persisted alongside the token so we can detect a newly-granted read capability. */
const val METRIC_CATALOG_ASSET = "metrics/metric_catalog.json"
private const val HEALTH_READ_TYPE_WEIGHT = "weight"
private const val HEALTH_READ_TYPE_BODY_FAT = "bodyfat"

data class GoalCalculationEvidenceContext(
    val evidence: GoalEvidence,
    val measuredTdee: Int?
)

/**
 * @param scope the application's supervisor scope (AyuvoApp.appScope) — long-running work
 *   such as the Health Data mirror sync must outlive any Activity, so it launches here.
 */
class AppContainer(app: AyuvoApp, val scope: CoroutineScope) {
    val appContext = app.applicationContext
    val localModels = LocalModelManager(app, FoodAnalysisService.defaultClient)
    val prefs = PreferencesStore(
        app,
        isLocalGemmaExecutable = { localModels.isExecutable(LocalModelId.GEMMA_4_E2B) },
        isLocalWhisperExecutable = { localModels.isExecutable(LocalModelId.WHISPER_BASE) }
    )
    val keyStore = KeyStore(app)
    val imageStore = FoodImageStore(app)
    val notifications = NotificationService(app)
    val health = HealthConnectManager(app)

    // -- Health Data hub (local mirror of Health Connect) -------------------
    // Lazily opened: users who never enable the hub never create ayuvo_health.db.
    val healthDatabase: HealthDatabase by lazy { HealthDatabase(app) }
    val healthStore: HealthDataStore by lazy { SqliteHealthDataStore(healthDatabase) }
    val healthReadSource: HealthReadSource by lazy {
        HealthConnectReadSource(client = { health.clientOrNull() }, ownPackage = app.packageName)
    }
    val localHealthSources: LocalHealthSources by lazy {
        LocalHealthSources(
            weights = { weightRepository.entries.first() },
            bodyFats = { bodyFatRepository.entries.first() },
            heightCm = { profileRepository.current()?.heightCm },
            packageName = app.packageName
        )
    }
    val healthSync: HealthSyncEngine by lazy {
        HealthSyncEngine(
            source = healthReadSource,
            store = healthStore,
            prefs = prefs,
            localSources = localHealthSources,
            log = { Log.i("AyuvoHealth", it) }
        )
    }
    val healthRepository: HealthDataRepository by lazy { HealthDataRepository(healthStore) }

    // -- Health Records (docs/health-records.md) ------------------------------
    // Lazily opened like the health mirror: nothing touches ayuvo_records.db until the Records
    // tab, an import or a share intent needs it.
    val recordFiles: RecordFileStore by lazy { RecordFileStore(app) }
    private val recordsDatabaseLazy = lazy { RecordsDatabase(app) }
    val recordsDatabase: RecordsDatabase by recordsDatabaseLazy
    val recordsStore: RecordsStore by lazy { SqliteRecordsStore(recordsDatabase, recordFiles, catalog = { analyteCatalog }) }

    // Phase 3 "Knowledge base": the bundled analyte catalogue (§20), parsed once after units.json.
    val analyteCatalog: com.ayuvo.health.records.analytes.AnalyteCatalog by lazy {
        recordRules // installs UnitsCatalog.active first
        com.ayuvo.health.records.analytes.AnalyteCatalog.parseOrEmpty(
            runCatching { app.assets.open(com.ayuvo.health.records.analytes.AnalyteCatalog.ASSET_PATH).bufferedReader().use { it.readText() } }.getOrNull()
        ).also { com.ayuvo.health.records.analytes.AnalyteCatalog.active = it }
    }
    val recordImporter: RecordImporter by lazy { RecordImporter(app, recordsStore, recordFiles) }

    // Phase 2 "Intelligence": background pipeline (docs/health-records.md §9).
    val recordsAiResolver: RecordsAiModeResolver by lazy { RecordsAiModeResolver(prefs, keyStore, localModels) }
    val recordsAi: RecordsAiExtractor by lazy { RecordsAiExtractor(foodAnalysis, localBusy = { localGemma.isBusy }) }
    val recordRules: RecordRules by lazy {
        RecordRules(
            recordTypesJson = app.assets.open(RecordRules.RECORD_TYPES_ASSET).bufferedReader().use { it.readText() },
            unitsJson = runCatching { app.assets.open(RecordRules.UNITS_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        )
    }
    private val recordsOcr: OcrEngine by lazy { MlKitOcrEngine() }
    val recordsPipeline: RecordPipeline by lazy {
        RecordPipeline(
            store = { recordsStore },
            files = recordFiles,
            textStage = { TextStage(recordsStore, recordFiles, recordsOcr) },
            rules = { recordRules },
            aiResolver = { recordsAiResolver },
            aiExtractor = { recordsAi },
            aiPreference = { RecordsAiMode.fromRaw(prefs.healthRecordsAiMode.first()) },
            isOnline = { NetworkState.isOnline(appContext) },
            typeLabel = { type -> appContext.getString(type.labelRes()) },
            providerLabel = { provider -> appContext.getString(provider.displayNameRes) }
        )
    }
    val recordsQueryRewriter: AiQueryRewriter by lazy { AiQueryRewriter({ recordsAiResolver }, { recordsAi }) }

    // Phase 4 "AI Coach" (docs/health-records.md §26–§30).
    val recordsCoachContract: com.ayuvo.health.records.coach.RecordsCoachContract by lazy {
        com.ayuvo.health.records.coach.RecordsCoachContract.parse(
            app.assets.open(com.ayuvo.health.records.coach.RecordsCoachContract.ASSET_PATH).bufferedReader().use { it.readText() }
        ).also { com.ayuvo.health.records.coach.RecordsCoachContract.active = it }
    }

    /** One-shot "open Coach with these records and this prompt" request from the Records screens (§27). */
    val coachRecordsRequests = kotlinx.coroutines.flow.MutableStateFlow<com.ayuvo.health.records.coach.CoachRecordsRequest?>(null)

    /** Whether a records database exists (Coach never creates one just to look for records). */
    fun recordsDatabaseExists(): Boolean = recordsDatabaseLazy.isInitialized() || appContext.getDatabasePath(RecordsDatabase.NAME).exists()
    val recordsQueue: RecordProcessingQueue by lazy { RecordProcessingQueue(app, { recordsStore }, { recordsPipeline }) }
    val recordsImports = RecordsImportCoordinator(scope, { recordImporter }, { recordsStore }, { recordsQueue })

    // Phase 5 "Sharing & backup" (docs/health-records.md §33–§37).
    val recordShareBuilder: com.ayuvo.health.records.share.RecordShareBuilder by lazy {
        com.ayuvo.health.records.share.RecordShareBuilder(
            store = recordsStore,
            files = recordFiles,
            catalog = { analyteCatalog },
            typeLabel = { type -> appContext.getString(type.labelRes()) }
        )
    }
    val recordsBackup: com.ayuvo.health.records.backup.RecordsBackupCoordinator by lazy {
        com.ayuvo.health.records.backup.RecordsBackupCoordinator(
            context = appContext,
            store = recordsStore,
            helper = recordsDatabase,
            files = recordFiles,
            appVersion = BuildConfig.VERSION_NAME
        )
    }
    val recordsDriveBackup: com.ayuvo.health.records.backup.DriveRecordsBackup by lazy {
        com.ayuvo.health.records.backup.DriveRecordsBackup(
            context = appContext,
            store = recordsStore,
            archives = recordsBackup,
            drive = com.ayuvo.health.backup.DriveCloudBackupClient(BuildConfig.CLOUD_BACKUP_WEB_CLIENT_ID),
            accessToken = { keyStore.cloudBackupAccessToken() }
        )
    }

    // -- Medications (docs/medications.md) ------------------------------------
    // Lazily opened like records: nothing touches ayuvo_medications.db until the Meds segment, a
    // notification action or the reminder planner needs it.
    val medicationPhotos: MedicationPhotoStore by lazy { MedicationPhotoStore(app) }
    private val medicationsDatabaseLazy = lazy { MedicationsDatabase(app) }
    val medicationsDatabase: MedicationsDatabase by medicationsDatabaseLazy
    val medicationsStore: MedicationsStore by lazy { SqliteMedicationsStore(medicationsDatabase, medicationPhotos) }

    /** Whether a medications database exists (planners never create one just to look for doses). */
    fun medicationsDatabaseExists(): Boolean = medicationsDatabaseLazy.isInitialized() || MedicationsDatabase.exists(appContext)

    /** Single next-wake alarm planner for medication reminders (docs/medications.md §10). */
    val medicationReminders: MedicationReminderCoordinator by lazy {
        MedicationReminderCoordinator(
            context = appContext,
            store = { medicationsStore },
            scope = scope,
            databaseExists = ::medicationsDatabaseExists,
            gate = {
                com.ayuvo.health.medications.reminders.ReminderGate.shouldSchedule(
                    notificationsEnabled = prefs.notificationsEnabled.first(),
                    medicationRemindersEnabled = prefs.medicationRemindersEnabled.first(),
                    canPost = notifications.canPostNotifications()
                )
            },
            snoozeMinutes = { prefs.medicationSnoozeMinutes.first() }
        )
    }

    /** Delete All Data: the medications database (+ journal files), every photo and every reminder. */
    suspend fun deleteMedicationsData() = withContext(Dispatchers.IO) {
        MedicationAlarms.cancel(appContext)
        MedicationNotifications.cancelAll(appContext)
        runCatching { MedicationMaintenanceWorker.cancel(appContext) }
        medicationReminders.reset()
        if (medicationsDatabaseLazy.isInitialized()) runCatching { medicationsDatabase.close() }
        MedicationsDatabase.deleteDatabaseFiles(appContext)
        medicationPhotos.deleteAll()
    }

    /**
     * Resumes unfinished processing (and the one-time Phase 2 backfill) when a records DB exists,
     * and clears the §34/§35 share temp folder left behind by the previous run.
     */
    fun resumeRecordsProcessing() {
        scope.launch { runCatching { withContext(Dispatchers.IO) { recordFiles.clearShareTemp() } } }
        if (!appContext.getDatabasePath(RecordsDatabase.NAME).exists()) return
        scope.launch {
            runCatching { recordsQueue.resumeOnStart() }
                .onFailure { Log.w("AyuvoRecords", "Resume failed: ${it.javaClass.simpleName}") }
        }
    }

    /** §36: the opt-in records archive upload runs after the normal Drive backup. */
    suspend fun backupRecordsToDriveIfNeeded() {
        if (!recordsDatabaseExists()) return
        runCatching { recordsDriveBackup.backupIfNeeded() }
            .onFailure { Log.w("AyuvoRecords", "Drive records backup failed: ${it.javaClass.simpleName}") }
    }

    /** Deletes records after stopping their processing. */
    suspend fun deleteRecords(ids: Collection<String>) {
        recordsQueue.cancel(ids)
        recordsStore.delete(ids)
    }

    /** Delete All Data: the records database (+ journal files), originals, thumbnails and caches. */
    suspend fun deleteRecordsData() = withContext(Dispatchers.IO) {
        runCatching { androidx.work.WorkManager.getInstance(appContext).cancelAllWorkByTag(RecordProcessingQueue.TAG_WORK) }
        if (recordsDatabaseLazy.isInitialized()) recordsStore.close()
        RecordsDatabase.deleteDatabaseFiles(appContext)
        recordFiles.deleteAll()
    }

    private val workoutHealthSync = object : WorkoutHealthSync {
        override suspend fun upsertBurn(session: WorkoutSession): Boolean {
            if (!prefs.healthConnectEnabled.first() || !health.hasActiveEnergyWrite()) return false
            val calories = session.caloriesBurned ?: return false
            val version = session.healthSyncVersion ?: return false
            return health.upsertWorkoutBurn(
                sessionId = session.id,
                diaryDateKey = session.diaryDateKey,
                caloriesBurned = calories,
                healthSyncVersion = version
            )
        }

        override suspend fun deleteBurn(sessionId: UUID, diaryDateKey: String): Boolean {
            // Keep deletion best-effort even after the user disables syncing so
            // an old Ayuvo record cannot be restored on the next connection.
            if (!health.isAvailable() || !health.hasActiveEnergyWrite()) return false
            return health.deleteWorkoutBurn(sessionId, diaryDateKey)
        }

        override suspend fun readOwnedBurns(): List<WorkoutSession>? {
            if (!prefs.healthConnectEnabled.first() || !health.hasActiveEnergyRead()) return null
            val now = Instant.now()
            return health.readOwnedWorkoutBurns(now.minus(Duration.ofDays(7_300)), now.plus(Duration.ofDays(2)))
                ?.map { burn ->
                    WorkoutSession(
                        id = burn.sessionId,
                        diaryDateKey = burn.diaryDateKey,
                        startedAt = burn.startTime,
                        completedAt = burn.endTime,
                        durationSeconds = 0,
                        exercises = emptyList(),
                        caloriesBurned = burn.caloriesBurned,
                        healthSyncVersion = burn.healthSyncVersion
                    )
                }
        }
    }

    val profileRepository = ProfileRepository(prefs)
    val foodRepository = FoodRepository(prefs, health, imageStore)
    val weightRepository = WeightRepository(prefs, profileRepository, health)
    val bodyFatRepository = BodyFatRepository(prefs, profileRepository, health)
    val bodyMeasurementRepository = BodyMeasurementRepository(prefs)
    val chatRepository = ChatRepository(prefs)
    val waterRepository = WaterRepository(prefs)
    val fastingRepository = FastingRepository(prefs)
    val workoutRepository = WorkoutRepository(prefs, workoutHealthSync)

    // -- Metrics (docs/ui-structure.md): catalog, favourites, app-metric series ---------------
    val metricCatalog: MetricCatalogData by lazy {
        MetricCatalogData.parse(app.assets.open(METRIC_CATALOG_ASSET).bufferedReader().use { it.readText() })
    }
    val favoritePins: FavoritePins by lazy { FavoritePins(prefs) { metricCatalog } }
    val appMetrics: AppMetricSeriesProvider by lazy {
        AppMetricSeriesProvider(
            sources = RepositoryMetricSources(
                food = foodRepository.entries,
                water = waterRepository.entries,
                fasting = fastingRepository.sessions,
                weight = weightRepository.entries,
                bodyFat = bodyFatRepository.entries,
                workouts = workoutRepository.completedSessions
            ),
            scope = scope
        )
    }
    val cloudBackup = CloudBackupCoordinator(app, prefs, imageStore, keyStore)

    /** Settings › Backup & Export › Export All Data (one zip of every existing export). */
    val allDataExport: com.ayuvo.health.export.AllDataExportCoordinator by lazy {
        com.ayuvo.health.export.AllDataExportCoordinator(this)
    }

    /** Settings › Backup & Export › Import All Data (reads that zip back, section by section). */
    val allDataImport: com.ayuvo.health.export.AllDataImportCoordinator by lazy {
        com.ayuvo.health.export.AllDataImportCoordinator(this)
    }

    val localGemma = LocalGemmaRuntime(app, localModels)
    val localWhisper = LocalWhisperRuntime(app, localModels)

    val foodAnalysis = FoodAnalysisService(prefs, keyStore, localGemma = localGemma)
    val chatService = ChatService(prefs, keyStore, localGemma = localGemma)
    val speechService = SpeechService(prefs, keyStore, localWhisper = localWhisper)

    val widgetSnapshotWriter = WidgetSnapshotWriter(app, prefs, foodRepository, profileRepository)
    /**
     * App-scoped flag set by [HomeViewModel] while a food analysis request is
     * in flight. The bottom nav reads this so the bar can hide during the
     * AnalyzingOverlay (matches iOS, where the analyzing sheet covers the
     * tab bar).
     */
    val analyzingFood: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private var adaptiveGoalsRefreshInFlight = false

    @Volatile
    private var healthReadSyncInFlight = false

    /**
     * Pull external weight + body-fat readings FROM Health Connect into the app (e.g. a
     * Withings scale that writes weigh-ins to Health Connect). Runs on app foreground and
     * right after the user connects/grants. Read-direction only — gated per metric on READ
     * permission, so a user who granted read but not write still gets their data imported
     * (issue #91). Incremental via a persisted changes token, with a one-time historical
     * backfill when there's no token yet; imports are deduped, so re-runs are harmless.
     */
    /**
     * The single app-open entry point for everything Health Connect: the legacy read sync
     * (weigh-ins, nutrition restore, workout burns) followed by the Health Data hub mirror.
     * Launched on [scope] so it survives Activity stop; the returned Deferred lets the hub's
     * pull-to-refresh await the outcome and always clear its spinner.
     */
    fun requestHealthSync(trigger: HealthSyncTrigger): Deferred<HealthSyncOutcome> = scope.async {
        runCatching { syncHealthConnectReads() }
            .onFailure { Log.w("AyuvoHealth", "Legacy Health Connect sync failed: ${it.javaClass.simpleName}") }
        if (!prefs.healthHubEnabled.first()) {
            return@async HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.HUB_DISABLED)
        }
        if (!health.isAvailable()) {
            return@async HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.PROBE_FAILED)
        }
        // One permission probe per run; null means the probe failed and nothing may change.
        val grants = health.capabilitiesOrNull()?.let { HealthGrants(it.hubReadTypes, it.historyRead) }
        runCatching { healthSync.sync(trigger, grants) }
            .onFailure { Log.e("AyuvoHealth", "Health Data sync failed: ${it.javaClass.simpleName}", it) }
            .getOrDefault(HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.PROBE_FAILED))
    }

    /** Removes the mirror (Delete All Data). The engine is paused so no page lands mid-delete. */
    suspend fun deleteHealthDatabase() {
        healthSync.withPaused {
            healthStore.close()
            HealthDatabase.deleteDatabaseFiles(appContext)
        }
    }

    /** "Clear synced health data": rows go, prefs and grants stay. */
    suspend fun clearHealthData() {
        healthSync.withPaused { healthStore.deleteAll() }
    }

    suspend fun syncHealthConnectReads() {
        if (healthReadSyncInFlight) return
        // The hub can be on with the legacy toggle off (hub-only users): the read paths below
        // are individually permission-gated, so let them run for either flag.
        if (!prefs.healthConnectEnabled.first() && !prefs.healthHubEnabled.first()) return
        if (!health.isAvailable()) return

        // Nutrition writes Health Connect never confirmed retry here. Deliberately ahead of
        // the read-capability guard below: a user who granted write but no reads still has a
        // queue to drain, and returning early would strand it forever.
        foodRepository.retryPendingHealthWrites()

        val caps = health.capabilities()
        val workoutBurnRead = health.hasActiveEnergyRead()
        if (!caps.weightRead && !caps.bodyFatRead && !caps.nutritionRead &&
            !workoutBurnRead && !caps.activeEnergyWrite
        ) return

        healthReadSyncInFlight = true
        try {
            // One-shot food-log restore: after a reinstall or new phone the local
            // store is empty but our own NutritionRecords survive in Health Connect.
            // Ids already in the log are skipped, so this is a no-op for intact users.
            // A null read means a page failed mid-pagination — leave the flag unset
            // so the restore retries on a later foreground instead of permanently
            // accepting a partial history.
            if (caps.nutritionRead && !prefs.healthFoodRestoreDone.first()) {
                val now = Instant.now()
                val records = health.readNutrition(now.minus(Duration.ofDays(730)), now)
                if (records != null) {
                    foodRepository.restoreFromHealthConnect(records)
                    prefs.setHealthFoodRestoreDone(true)
                }
            }

            // Reconcile app-owned calculated workout burns independently from
            // nutrition and weigh-ins. Local calculation remains available even
            // without Health permission; deferred writes/deletes retry here.
            if (workoutBurnRead || caps.activeEnergyWrite) {
                workoutRepository.synchronizeWithHealth()
            }

            if (!caps.weightRead && !caps.bodyFatRead) return

            val desiredTypes = buildSet {
                if (caps.weightRead) add(HEALTH_READ_TYPE_WEIGHT)
                if (caps.bodyFatRead) add(HEALTH_READ_TYPE_BODY_FAT)
            }
            // If a read type was granted AFTER the token was seeded, the existing token never
            // observes it. Drop the token so we re-enter the backfill branch and import that
            // metric's history + re-seed a token covering everything now granted.
            if (!prefs.healthChangesTokenTypes.first().containsAll(desiredTypes)) {
                prefs.clearHealthChangesToken()
            }

            val token = prefs.healthChangesToken.first()
            if (token == null) {
                // Seed a token covering only the types we can actually read — BEFORE the
                // backfill reads, so a record written during the (long) two-year read is
                // observed by the first incremental drain instead of being lost between them.
                val recordTypes = buildSet {
                    if (caps.weightRead) add(androidx.health.connect.client.records.WeightRecord::class)
                    if (caps.bodyFatRead) add(androidx.health.connect.client.records.BodyFatRecord::class)
                }
                val seededToken = health.getChangesToken(recordTypes)
                // First sync: backfill recent history (two years) so existing scale data shows up.
                val now = Instant.now()
                val from = now.minus(Duration.ofDays(730))
                if (caps.weightRead) {
                    weightRepository.importExternalWeights(health.readWeights(from, now))
                }
                if (caps.bodyFatRead) {
                    bodyFatRepository.importExternalBodyFats(health.readBodyFats(from, now))
                }
                seededToken?.let {
                    prefs.setHealthChangesToken(it)
                    prefs.setHealthChangesTokenTypes(desiredTypes)
                }
            } else {
                var next: String? = null
                if (caps.weightRead) {
                    val result = health.consumeWeightChanges(token)
                    if (result == null) { prefs.clearHealthChangesToken(); return }
                    weightRepository.importExternalWeights(result.first)
                    next = result.second
                }
                if (caps.bodyFatRead) {
                    val result = health.consumeBodyFatChanges(token)
                    if (result == null) { prefs.clearHealthChangesToken(); return }
                    bodyFatRepository.importExternalBodyFats(result.first)
                    next = result.second ?: next
                }
                next?.let { prefs.setHealthChangesToken(it) }
            }
        } finally {
            healthReadSyncInFlight = false
        }
    }

    /** One privacy-safe evidence snapshot shared by manual Recalculate and Adaptive Goals. Health
     *  Connect is queried once: its daily values both form the measured maintenance anchor and go
     *  into the evidence pack. App-estimated workout calories are excluded by HealthConnectManager. */
    suspend fun goalCalculationEvidence(profile: UserProfile): GoalCalculationEvidenceContext {
        val foods = foodRepository.entries.first()
        val weights = weightRepository.entries.first()
        val energy = measuredGoalEnergyIfEnabled(profile)
        return GoalCalculationEvidenceContext(
            evidence = GoalEvidenceBuilder.build(
                profile = profile,
                foods = foods,
                weights = weights,
                bodyFatEntries = bodyFatRepository.entries.first(),
                workouts = workoutRepository.completedSessions.first(),
                measurements = bodyMeasurementRepository.entries.first(),
                healthEnergyDays = energy.second
            ),
            measuredTdee = energy.first
        )
    }

    /** Compatibility helper for callers that only need the Health Connect maintenance anchor. */
    suspend fun measuredEnergyTdeeIfEnabled(profile: UserProfile): Int? =
        measuredGoalEnergyIfEnabled(profile).first

    private suspend fun measuredGoalEnergyIfEnabled(
        profile: UserProfile
    ): Pair<Int?, List<DailyHealthEnergyEvidence>> {
        if (!prefs.healthEnergyGoalsEnabled.first() || !prefs.healthConnectEnabled.first()) {
            return null to emptyList()
        }
        if (!health.isAvailable() || !health.hasEnergyRead()) return null to emptyList()
        val daily = runCatching { health.readRecentDailyEnergy(days = 14) }.getOrNull().orEmpty()
        // Reading Health Connect suspends. Respect an opt-out that happened while it was in flight
        // before constructing either the provider evidence or measured-maintenance anchor.
        if (!prefs.healthEnergyGoalsEnabled.first() || !prefs.healthConnectEnabled.first()) {
            return null to emptyList()
        }
        val evidence = daily.map {
            DailyHealthEnergyEvidence(
                date = it.date,
                externalActiveCalories = it.activeCalories,
                totalCalories = it.totalCalories
            )
        }
        if (daily.size < 3) return null to evidence
        val activeAverage = daily.map { it.activeCalories }.average().roundToInt()
        val totalAverage = daily.mapNotNull { it.totalCalories }
            // Match iOS: a measured-total anchor needs at least three complete total-energy
            // days. A lone anomalous total must not override the formula maintenance estimate.
            .takeIf { it.size >= 3 }
            ?.average()
            ?.roundToInt()
        return (totalAverage ?: (profile.bmr.roundToInt() + activeAverage)) to evidence
    }

    /**
     * Adaptive Goals: automatically re-runs the FULL AI goal calculation (the same one the
     * Recalculate button uses) about once a week, from the latest logged food + weight trend
     * (hit-and-trial) and — when Energy Burn is on — the measured Health maintenance anchor.
     * Silent and non-destructive on AI failure (keeps existing goals; marks checked so it does not
     * retry on every app open). Returned targets are validated against the same formula references
     * and plausibility bounds used by manual recalculation.
     */
    suspend fun refreshAdaptiveGoalsIfNeeded(force: Boolean = false): AdaptiveGoalResult? {
        if (adaptiveGoalsRefreshInFlight) return null
        adaptiveGoalsRefreshInFlight = true
        try {
            if (!prefs.adaptiveGoalsEnabled.first()) return null

            val today = LocalDate.now()
            if (!force && !shouldCheckAdaptiveGoals(prefs.adaptiveGoalsLastCheckDay.first(), today)) {
                return null
            }

            val profile = profileRepository.current() ?: return null
            val heightMetric = prefs.heightUnit.first() == "cm"
            val weightMetric = prefs.weightUnit.first() == "kg"
            val healthEnabledAtStart = prefs.healthConnectEnabled.first()
            val energyEnabledAtStart = prefs.healthEnergyGoalsEnabled.first()
            val result = runCatching {
                val context = goalCalculationEvidence(profile)
                if (prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                    prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart ||
                    !prefs.adaptiveGoalsEnabled.first()
                ) return null
                foodAnalysis.calculateGoals(
                    profile = profile,
                    heightMetric = heightMetric,
                    weightMetric = weightMetric,
                    measuredTdee = context.measuredTdee,
                    measurement = bodyMeasurementRepository.latestSnapshot(),
                    evidence = context.evidence
                )
            }.getOrNull()
            if (result == null) {
                // Mark provider failure checked so a bad key is not hit on every foreground.
                if (prefs.adaptiveGoalsEnabled.first() &&
                    prefs.healthConnectEnabled.first() == healthEnabledAtStart &&
                    prefs.healthEnergyGoalsEnabled.first() == energyEnabledAtStart
                ) {
                    prefs.setAdaptiveGoalsLastCheckDay(today.toString())
                }
                return null
            }

            // Never overwrite profile/target edits made while the provider request was in flight.
            val latest = profileRepository.current() ?: return null
            if (latest != profile || !prefs.adaptiveGoalsEnabled.first() ||
                prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart
            ) return null
            prefs.setAdaptiveGoalsLastCheckDay(today.toString())
            prefs.saveAdaptiveGoalPreviousTargetsIfNeeded(latest)
            val next = latest.recalculatedFromFormulas().copy(
                customCalories = result.calories,
                customProtein = result.protein,
                customCarbs = result.carbs,
                customFat = result.fat
            )
            profileRepository.save(next)
            return AdaptiveGoalResult(
                profile = next,
                changed = true,
                updatedCalories = result.calories,
                message = "Updated to ${result.calories} kcal from your latest data." + (result.reason?.let { " $it" } ?: "")
            )
        } finally {
            adaptiveGoalsRefreshInFlight = false
        }
    }

    private fun shouldCheckAdaptiveGoals(lastCheckDay: String?, today: LocalDate): Boolean {
        val lastCheck = lastCheckDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return true
        return !lastCheck.plusDays(7).isAfter(today)
    }
}
