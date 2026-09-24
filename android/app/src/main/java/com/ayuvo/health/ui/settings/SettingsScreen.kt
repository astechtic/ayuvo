package com.ayuvo.health.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.backup.DriveCloudBackupClient
import com.ayuvo.health.medications.reminders.MedicationAlarms
import com.ayuvo.health.services.health.HealthAvailabilityMessageKind
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.services.health.HealthSyncTrigger
import com.ayuvo.health.services.health.healthAvailabilityMessageKind
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoLargeTopBar
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.settings.groups.SettingsPageContent
import com.ayuvo.health.ui.settings.groups.SettingsRoot
import com.ayuvo.health.ui.summary.FavoritesEditorSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings tab (docs/ui-structure.md §2, plan §6): a root with the profile header and one inset
 * group per [SettingsGroup], and one pushed page per [SettingsPage]. The host owns every launcher,
 * dialog and sheet; pages get state and callbacks through [SettingsPageContext].
 *
 * [openPageRequest] opens a page from elsewhere (Browse's Health Sync footer → Health Sync).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    nav: NavHostController,
    vm: SettingsViewModel,
    popToRootTick: Int = 0,
    openPageRequest: SettingsPageRequest? = null,
    onOpenPageHandled: (Long) -> Unit = {},
    onOpenBrowse: () -> Unit = {},
    onOpenMedications: () -> Unit = {},
    updateAvailable: Boolean = false
) {
    val ui by vm.ui.collectAsState()
    val profile = ui.profile
    val latestMeasurement by container.bodyMeasurementRepository.latest.collectAsState(initial = null)
    val healthSync by container.healthSync.status.collectAsState()

    val selectedPageState = rememberSaveable(saver = SelectedSettingsPageSaver) {
        androidx.compose.runtime.mutableStateOf<SettingsPage?>(null)
    }
    val state = remember { SettingsScreenState(selectedPageState) }

    if (state.showNutritionImport) {
        HealthNutritionImportDialog(container) { state.showNutritionImport = false }
    }

    // Only a new re-tap pops to the root; returning to the tab keeps the open page.
    var handledPopTick by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(popToRootTick) }
    LaunchedEffect(popToRootTick) {
        if (popToRootTick != handledPopTick) {
            handledPopTick = popToRootTick
            state.selectedPage = null
        }
    }
    LaunchedEffect(openPageRequest?.id) {
        val request = openPageRequest ?: return@LaunchedEffect
        state.selectedPage = request.page
        onOpenPageHandled(request.id)
    }
    val settingsHomeScrollState = rememberScrollState()
    val settingsDetailScrollState = remember(state.selectedPage) { ScrollState(initial = 0) }
    val activityContext = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    val cloudBackupSignInFailed = stringResource(R.string.cloud_backup_sign_in_failed)
    val cloudBackup by container.cloudBackup.ui.collectAsState()
    LaunchedEffect(state.selectedPage) {
        when (state.selectedPage) {
            SettingsPage.BACKUP_EXPORT -> container.cloudBackup.refresh()
            SettingsPage.HEALTH_SYNC -> container.healthSync.refreshStatus()
            else -> Unit
        }
    }
    val finishDriveSignIn: () -> Unit = {
        settingsScope.launch {
            container.cloudBackup.refresh()
            if (container.cloudBackup.ui.value.existingCloudBackup) {
                state.showCloudRestoreChoice = true
            } else {
                container.cloudBackup.enableAfterAuth(restoreIfPresent = false)
                    .onFailure { state.cloudBackupError = it.message }
            }
        }
    }
    val driveAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            state.cloudBackupError = cloudBackupSignInFailed
            return@rememberLauncherForActivityResult
        }
        settingsScope.launch {
            val activity = activityContext as? Activity
            if (activity == null) {
                state.cloudBackupError = cloudBackupSignInFailed
                return@launch
            }
            if (container.cloudBackup.finishAuthorization(activity, result.data)) {
                finishDriveSignIn()
            } else {
                state.cloudBackupError = cloudBackupSignInFailed
            }
        }
    }
    val continueDriveAuth: (android.accounts.Account) -> Unit = { account ->
        val activity = activityContext as? Activity
        if (activity == null) {
            state.cloudBackupError = cloudBackupSignInFailed
        } else {
            settingsScope.launch {
                runCatching { container.cloudBackup.authorize(activity, account) }
                    .onSuccess { outcome ->
                        when (outcome) {
                            is DriveCloudBackupClient.AuthOutcome.Token -> finishDriveSignIn()
                            is DriveCloudBackupClient.AuthOutcome.Resolution -> {
                                driveAuthLauncher.launch(
                                    IntentSenderRequest.Builder(outcome.intentSender).build()
                                )
                            }
                        }
                    }
                    .onFailure { state.cloudBackupError = it.message }
            }
        }
    }
    val driveAccountPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            state.cloudBackupError = cloudBackupSignInFailed
            return@rememberLauncherForActivityResult
        }
        val account = container.cloudBackup.accountFromPickerResult(result.data)
        if (account == null) {
            state.cloudBackupError = cloudBackupSignInFailed
        } else {
            continueDriveAuth(account)
        }
    }
    val startDriveSignIn: () -> Unit = {
        runCatching {
            driveAccountPickerLauncher.launch(container.cloudBackup.accountPickerIntent())
        }.onFailure { state.cloudBackupError = it.message ?: cloudBackupSignInFailed }
    }

    // Notifications: API 33+ requires runtime POST_NOTIFICATIONS. We only flip the
    // pref to true if the user actually grants. Denial leaves the toggle off so
    // the UI never lies about whether notifications can fire.
    val notifDeniedMsg = stringResource(R.string.settings_notifications_denied)
    val healthDeniedMsg = stringResource(R.string.settings_health_denied)
    val healthUnavailableMsg = stringResource(R.string.settings_health_unavailable)
    val healthProfileUnsupportedMsg = stringResource(R.string.settings_health_profile_unsupported)
    val healthSystemUnavailableMsg = stringResource(R.string.settings_health_system_unavailable)

    fun healthAvailabilityMessage(availability: HealthConnectAvailability): String =
        when (healthAvailabilityMessageKind(availability)) {
            HealthAvailabilityMessageKind.PROFILE_UNSUPPORTED -> healthProfileUnsupportedMsg
            HealthAvailabilityMessageKind.PROVIDER_UPDATE_REQUIRED -> healthUnavailableMsg
            HealthAvailabilityMessageKind.SYSTEM_UNAVAILABLE -> healthSystemUnavailableMsg
            null -> healthDeniedMsg
        }

    fun showHealthAvailabilityDialog() {
        val availability = container.health.availability()
        state.permissionDialog = PermissionDialogState(
            message = healthAvailabilityMessage(availability),
            healthAvailability = availability
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setNotificationsEnabled(true)
        else state.permissionDialog = PermissionDialogState(notifDeniedMsg)
    }

    val photoSaveDeniedMsg = stringResource(R.string.photo_save_permission_denied)
    val gallerySavePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setSaveMealPhotosToGallery(true)
        else state.permissionDialog = PermissionDialogState(photoSaveDeniedMsg)
    }

    fun onSavePhotosToGalleryChanged(enabled: Boolean) {
        if (!enabled) {
            vm.setSaveMealPhotosToGallery(false)
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(activityContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            gallerySavePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.setSaveMealPhotosToGallery(true)
        }
    }

    // Health Connect honors partial grants: any granted permission connects the app, and
    // each direction is gated on its own permission downstream (issue #91). The SYNC toggle
    // accepts any grant; ENERGY_GOALS still needs the energy reads, which its VM re-checks.
    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = container.health.permissionRequestContract()
    ) { granted ->
        val action = state.pendingHealthPermissionAction ?: HealthConnectPermissionAction.SYNC
        state.pendingHealthPermissionAction = null
        if (granted.any { it in container.health.permissions }) {
            when (action) {
                HealthConnectPermissionAction.SYNC -> vm.setHealthConnectEnabled(true)
                HealthConnectPermissionAction.ENERGY_GOALS -> vm.setHealthEnergyGoalsEnabled(true)
                HealthConnectPermissionAction.DAILY_SUMMARY -> vm.setDailySummaryEnabled(true)
            }
        } else {
            state.showHealthPermissionHelp = true
        }
    }

    fun openHealthConnectAccess() {
        if (!container.health.openManageAccess(activityContext)) showHealthAvailabilityDialog()
    }

    fun openHealthConnectStore() {
        if (!container.health.openPlayStore()) {
            state.permissionDialog = PermissionDialogState(healthUnavailableMsg)
        }
    }

    fun onNotificationsToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setNotificationsEnabled(false)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            vm.setNotificationsEnabled(true)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                activityContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.setNotificationsEnabled(true)
            else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun onDailySummaryToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setDailySummaryEnabled(false)
            return
        }
        // The existing notification remains available without Health Connect.
        // When Health is connected, request background read only for this opt-in
        // so its scheduled alarm can replace the static text with measured burn.
        if (!ui.healthConnectEnabled || !container.health.isAvailable()) {
            vm.setDailySummaryEnabled(true)
            return
        }
        state.pendingHealthPermissionAction = HealthConnectPermissionAction.DAILY_SUMMARY
        healthConnectLauncher.launch(container.health.dailySummaryPermissions)
    }

    fun onHealthConnectToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setHealthConnectEnabled(false)
            return
        }
        if (!container.health.isAvailable()) {
            showHealthAvailabilityDialog()
            return
        }
        // Don't pre-check granted state — Health Connect's contract handles the
        // already-granted case by returning the full set immediately.
        state.pendingHealthPermissionAction = HealthConnectPermissionAction.SYNC
        healthConnectLauncher.launch(
            if (ui.dailySummaryEnabled) container.health.dailySummaryPermissions
            else container.health.permissions
        )
    }

    fun onHealthEnergyGoalsToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setHealthEnergyGoalsEnabled(false)
            return
        }
        if (!container.health.isAvailable()) {
            showHealthAvailabilityDialog()
            return
        }
        state.pendingHealthPermissionAction = HealthConnectPermissionAction.ENERGY_GOALS
        healthConnectLauncher.launch(container.health.permissions)
    }

    fun openBatteryOptimizationSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${activityContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        for (intent in intents) {
            if (runCatching { activityContext.startActivity(intent) }.isSuccess) return
        }
    }

    // Medication reminders: the system "Alarms & reminders" toggle (Android 12+), with the app
    // details page as a fallback. The exact-alarm state is re-read when the screen resumes.
    fun openExactAlarmSettings() {
        val intents = listOfNotNull(
            MedicationAlarms.requestExactIntent(activityContext),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${activityContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        for (intent in intents) {
            if (runCatching { activityContext.startActivity(intent) }.isSuccess) return
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshExactAlarmState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.selectedPage != null) {
        state.selectedPage = null
    }

    val actions = SettingsActions(
        openPage = { state.selectedPage = it },
        back = { state.selectedPage = null },
        navigate = { route -> nav.navigate(route) },
        openBrowse = onOpenBrowse,
        openMedications = onOpenMedications,
        openFavourites = {
            settingsScope.launch { state.editFavourites = container.favoritePins.current() }
        },
        onNotificationsToggle = ::onNotificationsToggle,
        onDailySummaryToggle = ::onDailySummaryToggle,
        onHealthConnectToggle = ::onHealthConnectToggle,
        onHealthEnergyGoalsToggle = ::onHealthEnergyGoalsToggle,
        onSavePhotosToGalleryChanged = ::onSavePhotosToGalleryChanged,
        openBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
        openExactAlarmSettings = ::openExactAlarmSettings,
        openHealthConnectAccess = ::openHealthConnectAccess,
        requestWorkoutHealthAccess = {
            state.pendingHealthPermissionAction = HealthConnectPermissionAction.SYNC
            healthConnectLauncher.launch(container.health.permissions)
        },
        syncHealthNow = {
            settingsScope.launch {
                runCatching { container.requestHealthSync(HealthSyncTrigger.MANUAL_REFRESH).await() }
                container.healthSync.refreshStatus()
            }
        },
        setCloudBackupEnabled = { on ->
            if (on) state.showCloudEnableConfirm = true
            else settingsScope.launch { container.cloudBackup.disable(activityContext as? Activity) }
        },
        backupNow = {
            settingsScope.launch {
                container.cloudBackup.backupNow().onFailure { state.cloudBackupError = it.message }
            }
        },
        restoreNow = {
            settingsScope.launch {
                container.cloudBackup.restoreNow().onFailure { state.cloudBackupError = it.message }
            }
        },
        signOutCloudBackup = {
            settingsScope.launch { container.cloudBackup.signOut(activityContext as? Activity) }
        },
        switchCloudAccount = {
            settingsScope.launch {
                container.cloudBackup.signOut(activityContext as? Activity)
                startDriveSignIn()
            }
        }
    )
    val ctx = SettingsPageContext(
        container = container,
        nav = nav,
        vm = vm,
        ui = ui,
        state = state,
        actions = actions,
        latestMeasurement = latestMeasurement,
        cloudBackup = cloudBackup,
        healthSync = healthSync,
        updateAvailable = updateAvailable
    )

    val page = state.selectedPage
    val rootScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = if (page == null) Modifier.nestedScroll(rootScrollBehavior.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (page == null) {
                AyuvoLargeTopBar(title = stringResource(R.string.nav_settings), scrollBehavior = rootScrollBehavior)
            } else {
                AyuvoTopBar(title = stringResource(page.titleRes), onBack = { state.selectedPage = null })
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(if (page == null) settingsHomeScrollState else settingsDetailScrollState)
                .padding(horizontal = AyuvoSpacing.ScreenH)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            if (page == null) SettingsRoot(ctx) else SettingsPageContent(ctx, page)
            Spacer(Modifier.height(BottomNavScrollPadding))
        }
    }

    state.editFavourites?.let { current ->
        FavoritesEditorSheet(
            catalog = container.metricCatalog,
            current = current,
            onSave = { keys ->
                settingsScope.launch { container.favoritePins.set(keys) }
                state.editFavourites = null
            },
            onDismiss = { state.editFavourites = null }
        )
    }

    state.sheet?.let { s ->
        SettingsSheets(
            sheet = s,
            ui = ui,
            vm = vm,
            onDismiss = { state.sheet = null },
            onInvalidGoalWeight = { state.invalidGoalWeightMessage = it },
            onRebalanceBlocked = { state.showRebalanceBlockedAlert = true },
            selectedProfileId = state.selectedModelProfileId,
            // The actions sheet hands off to another sheet about the same model, so the selection
            // survives the swap.
            onProfileAction = { next -> state.sheet = next }
        )
    }

    if (state.showClearHealthDialog) {
        GlassDialog(onDismissRequest = { state.showClearHealthDialog = false }) {
            Text(stringResource(R.string.health_settings_clear_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.health_settings_clear_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_clear),
                onPrimary = {
                    vm.clearSyncedHealthData()
                    state.showClearHealthDialog = false
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { state.showClearHealthDialog = false },
                destructive = true
            )
        }
    }

    if (state.showClearFoodDialog) {
        GlassDialog(onDismissRequest = { state.showClearFoodDialog = false }) {
            Text(stringResource(R.string.settings_clear_food_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_clear_food_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_clear),
                onPrimary = {
                    vm.clearFoodLog()
                    state.showClearFoodDialog = false
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { state.showClearFoodDialog = false },
                destructive = true
            )
        }
    }

    if (state.showCloudEnableConfirm) {
        GlassDialog(onDismissRequest = { state.showCloudEnableConfirm = false }) {
            Text(stringResource(R.string.cloud_backup_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.cloud_backup_enable_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.cloud_backup_turn_on),
                onPrimary = {
                    state.showCloudEnableConfirm = false
                    startDriveSignIn()
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { state.showCloudEnableConfirm = false }
            )
        }
    }

    if (state.showCloudRestoreChoice) {
        GlassDialog(onDismissRequest = { state.showCloudRestoreChoice = false }) {
            Text(stringResource(R.string.cloud_backup_restore_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.cloud_backup_restore_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.cloud_backup_restore),
                onPrimary = {
                    state.showCloudRestoreChoice = false
                    settingsScope.launch {
                        container.cloudBackup.enableAfterAuth(restoreIfPresent = true)
                            .onFailure { state.cloudBackupError = it.message }
                    }
                },
                dismissText = stringResource(R.string.cloud_backup_keep),
                onDismiss = {
                    state.showCloudRestoreChoice = false
                    settingsScope.launch {
                        container.cloudBackup.enableAfterAuth(restoreIfPresent = false)
                            .onFailure { state.cloudBackupError = it.message }
                    }
                }
            )
        }
    }

    if (state.showCloudDeleteConfirm) {
        GlassDialog(onDismissRequest = { state.showCloudDeleteConfirm = false }) {
            Text(stringResource(R.string.cloud_backup_delete), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.cloud_backup_delete_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    state.showCloudDeleteConfirm = false
                    settingsScope.launch {
                        container.cloudBackup.deleteCloudBackup()
                            .onFailure { state.cloudBackupError = it.message }
                    }
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { state.showCloudDeleteConfirm = false },
                destructive = true
            )
        }
    }

    state.cloudBackupError?.let { message ->
        GlassDialog(onDismissRequest = { state.cloudBackupError = null }) {
            Text(stringResource(R.string.cloud_backup_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.cloudBackupError = null },
                dismissText = null,
                onDismiss = { state.cloudBackupError = null }
            )
        }
    }

    if (state.showDeleteDialog) {
        val context = LocalContext.current
        GlassDialog(onDismissRequest = { state.showDeleteDialog = false }) {
            Text(stringResource(R.string.settings_delete_all_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_delete_all_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    vm.deleteAllData {
                        state.showDeleteDialog = false
                        (context as? android.app.Activity)?.recreate()
                    }
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { state.showDeleteDialog = false },
                destructive = true
            )
        }
    }

    if (state.showMaxPinnedAlert) {
        GlassDialog(onDismissRequest = { state.showMaxPinnedAlert = false }) {
            Text(stringResource(R.string.settings_max_pinned_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_max_pinned_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.showMaxPinnedAlert = false }
            )
        }
    }

    if (state.showRebalanceBlockedAlert) {
        GlassDialog(onDismissRequest = { state.showRebalanceBlockedAlert = false }) {
            Text(stringResource(R.string.settings_rebalance_blocked_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_rebalance_blocked_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.showRebalanceBlockedAlert = false }
            )
        }
    }

    if (state.showAdaptiveLockHint) {
        GlassDialog(onDismissRequest = { state.showAdaptiveLockHint = false }) {
            Text(stringResource(R.string.settings_adaptive_locks_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_adaptive_locks_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.showAdaptiveLockHint = false }
            )
        }
    }

    if (state.showDefaultGramsInfo) {
        GlassDialog(onDismissRequest = { state.showDefaultGramsInfo = false }) {
            Text(stringResource(R.string.settings_default_to_grams), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_default_to_grams_info),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.showDefaultGramsInfo = false }
            )
        }
    }

    if (state.showHealthEnergyGoalsInfo) {
        GlassDialog(onDismissRequest = { state.showHealthEnergyGoalsInfo = false }) {
            Text(stringResource(R.string.settings_energy_goals), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_energy_goals_info),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.showHealthEnergyGoalsInfo = false }
            )
        }
    }

    if (state.showAdaptiveGoalsInfo) {
        GlassDialog(onDismissRequest = { state.showAdaptiveGoalsInfo = false }) {
            Text(stringResource(R.string.settings_adaptive_goals), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_adaptive_goals_info),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.showAdaptiveGoalsInfo = false }
            )
        }
    }

    val energyAlertTitle = ui.healthEnergyGoalAlertTitle
    val energyAlertMessage = ui.healthEnergyGoalAlertMessage
    if (energyAlertTitle != null && energyAlertMessage != null) {
        GlassDialog(onDismissRequest = { vm.dismissHealthEnergyGoalAlert() }) {
            Text(energyAlertTitle, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                energyAlertMessage,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { vm.dismissHealthEnergyGoalAlert() }
            )
        }
    }

    val adaptiveAlertTitle = ui.adaptiveGoalAlertTitle
    val adaptiveAlertMessage = ui.adaptiveGoalAlertMessage
    if (adaptiveAlertTitle != null && adaptiveAlertMessage != null) {
        GlassDialog(onDismissRequest = { vm.dismissAdaptiveGoalAlert() }) {
            Text(adaptiveAlertTitle, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                adaptiveAlertMessage,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { vm.dismissAdaptiveGoalAlert() }
            )
        }
    }

    state.invalidGoalWeightMessage?.let { msg ->
        GlassDialog(onDismissRequest = { state.invalidGoalWeightMessage = null }) {
            Text(stringResource(R.string.settings_invalid_goal_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { state.invalidGoalWeightMessage = null }
            )
        }
    }

    state.permissionDialog?.let { dialog ->
        val getHealthConnectLabel = stringResource(R.string.settings_get_health_connect)
        val manageHealthLabel = stringResource(R.string.settings_manage_health_access)
        val (primaryText, onPrimary) = when (dialog.healthAvailability) {
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> getHealthConnectLabel to {
                state.permissionDialog = null
                openHealthConnectStore()
            }
            HealthConnectAvailability.UNAVAILABLE -> manageHealthLabel to {
                state.permissionDialog = null
                openHealthConnectAccess()
            }
            else -> stringResource(R.string.action_ok) to { state.permissionDialog = null }
        }
        val dismissForUnavailable = dialog.healthAvailability == HealthConnectAvailability.UNAVAILABLE
        GlassDialog(onDismissRequest = { state.permissionDialog = null }) {
            Text(stringResource(R.string.settings_permission_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(dialog.message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = primaryText,
                onPrimary = onPrimary,
                dismissText = if (dismissForUnavailable) getHealthConnectLabel else null,
                onDismiss = if (dismissForUnavailable) {
                    {
                        state.permissionDialog = null
                        openHealthConnectStore()
                    }
                } else {
                    null
                }
            )
        }
    }

    if (state.showHealthPermissionHelp) {
        GlassDialog(onDismissRequest = { state.showHealthPermissionHelp = false }) {
            Text(stringResource(R.string.settings_permission_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(healthDeniedMsg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.settings_manage_health_access),
                onPrimary = {
                    state.showHealthPermissionHelp = false
                    openHealthConnectAccess()
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { state.showHealthPermissionHelp = false }
            )
        }
    }
}
