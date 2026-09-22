package com.ayuvo.health.ui.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.ayuvo.health.AppContainer
import com.ayuvo.health.backup.CloudBackupUi
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.services.health.HealthSyncStatus

internal enum class HealthConnectPermissionAction {
    SYNC, ENERGY_GOALS, DAILY_SUMMARY
}

internal data class PermissionDialogState(
    val message: String,
    val healthAvailability: HealthConnectAvailability? = null
)

/**
 * Every piece of Settings UI state that used to live as locals in `SettingsScreen`: the open
 * sheet, dialog flags and the selected page. Pages read and write it
 * through [SettingsPageContext]; the host renders the sheets and dialogs it describes.
 */
@Stable
internal class SettingsScreenState(selectedPage: MutableState<SettingsPage?>) {
    var selectedPage by selectedPage
    var sheet by mutableStateOf<SettingsSheet?>(null)
    var showNutritionImport by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    var showClearFoodDialog by mutableStateOf(false)
    var showClearHealthDialog by mutableStateOf(false)
    var invalidGoalWeightMessage by mutableStateOf<String?>(null)
    var showMaxPinnedAlert by mutableStateOf(false)
    var showRebalanceBlockedAlert by mutableStateOf(false)
    var showAdaptiveLockHint by mutableStateOf(false)
    var permissionDialog by mutableStateOf<PermissionDialogState?>(null)
    var showHealthPermissionHelp by mutableStateOf(false)
    var showDefaultGramsInfo by mutableStateOf(false)
    var showHealthEnergyGoalsInfo by mutableStateOf(false)
    var showAdaptiveGoalsInfo by mutableStateOf(false)
    var showCloudEnableConfirm by mutableStateOf(false)
    var showCloudRestoreChoice by mutableStateOf(false)
    var showCloudDeleteConfirm by mutableStateOf(false)
    var cloudBackupError by mutableStateOf<String?>(null)
    var pendingHealthPermissionAction by mutableStateOf<HealthConnectPermissionAction?>(null)
    /** Summary › Favourites editor opened from Health Sync (null = closed). */
    var editFavourites by mutableStateOf<List<MetricKey>?>(null)
}

/** Callbacks the pages invoke; each wraps a launcher, an intent or a coroutine owned by the host. */
internal class SettingsActions(
    val openPage: (SettingsPage) -> Unit,
    val back: () -> Unit,
    val navigate: (String) -> Unit,
    val openBrowse: () -> Unit,
    val openMedications: () -> Unit,
    val openFavourites: () -> Unit,
    val onNotificationsToggle: (Boolean) -> Unit,
    val onDailySummaryToggle: (Boolean) -> Unit,
    val onHealthConnectToggle: (Boolean) -> Unit,
    val onHealthEnergyGoalsToggle: (Boolean) -> Unit,
    val onSavePhotosToGalleryChanged: (Boolean) -> Unit,
    val openBatteryOptimizationSettings: () -> Unit,
    val openExactAlarmSettings: () -> Unit,
    val openHealthConnectAccess: () -> Unit,
    val requestWorkoutHealthAccess: () -> Unit,
    val syncHealthNow: () -> Unit,
    val setCloudBackupEnabled: (Boolean) -> Unit,
    val backupNow: () -> Unit,
    val restoreNow: () -> Unit,
    val signOutCloudBackup: () -> Unit,
    val switchCloudAccount: () -> Unit
)

/** Everything a Settings page needs, passed down as one value. */
internal class SettingsPageContext(
    val container: AppContainer,
    val nav: NavHostController,
    val vm: SettingsViewModel,
    val ui: SettingsUiState,
    val state: SettingsScreenState,
    val actions: SettingsActions,
    val latestMeasurement: BodyMeasurement?,
    val cloudBackup: CloudBackupUi,
    val healthSync: HealthSyncStatus,
    val updateAvailable: Boolean
)
