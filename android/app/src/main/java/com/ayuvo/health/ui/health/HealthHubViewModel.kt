package com.ayuvo.health.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSyncState
import com.ayuvo.health.data.health.HealthTypeMeta
import com.ayuvo.health.models.HealthAndroidTier
import com.ayuvo.health.models.HealthCategory
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthFeatureFlag
import com.ayuvo.health.services.health.HealthCapabilities
import com.ayuvo.health.services.health.HealthConnectAvailability
import com.ayuvo.health.services.health.HealthHubPermissions
import com.ayuvo.health.services.health.HealthSyncOutcome
import com.ayuvo.health.services.health.HealthSyncPhase
import com.ayuvo.health.services.health.HealthSyncStatus
import com.ayuvo.health.services.health.HealthSyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** One data-type row in a category card. */
data class HealthTypeRowUi(
    val typeId: String,
    val type: HealthDataType?,
    val displayNameHint: String?,
    val latest: HealthSampleRow?,
    val count: Long,
    val granted: Boolean,
    val featureGated: Boolean,
    val sinceMs: Long?,
    /** SUM / duration types: the latest day's total (today when present) instead of the last chunk. */
    val dayValue: Double? = null,
    val dayKey: String? = null,
    val dayIsToday: Boolean = false
)

data class HealthCategoryUi(
    val category: HealthCategory,
    val rows: List<HealthTypeRowUi>,
    /** Read permissions still missing for this category (drives the "Allow … access" row). */
    val missingPermissions: Set<String>,
    val hiddenEmptyCount: Int
)

data class HealthHubUiState(
    val loading: Boolean = true,
    val availability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
    val hubEnabled: Boolean = false,
    val consentChecked: Boolean = true,
    val categories: List<HealthCategoryUi> = emptyList(),
    val syncStatus: HealthSyncStatus = HealthSyncStatus(),
    val refreshing: Boolean = false,
    val storageBytes: Long = 0L,
    val showAllTypes: Boolean = false,
    val permissionsReset: Boolean = false,
    val hasAnyData: Boolean = false,
    val grantedTypeIds: Set<String> = emptySet(),
    val unitPrefs: HealthUnitPrefs = HealthUnitPrefs(),
    val historyGranted: Boolean = false,
    val historySupported: Boolean = false
)

@OptIn(FlowPreview::class)
class HealthHubViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HealthHubUiState())
    val ui: StateFlow<HealthHubUiState> = _ui.asStateFlow()

    private val showAllTypes = MutableStateFlow(false)
    private var visibleLoop: Job? = null
    private var lastGrantedPermissions: Set<String> = emptySet()
    /** One Health Connect permission probe per visible session; store revisions reuse it. */
    private var cachedCaps: HealthCapabilities? = null
    @Volatile
    private var capsStale = true

    init {
        combine(
            container.prefs.healthHubEnabled,
            container.healthRepository.revision.debounce(REVISION_DEBOUNCE_MS),
            showAllTypes,
            unitPrefsFlow()
        ) { hub, _, showAll, units -> Triple(hub, showAll, units) }
            .onEach { (hub, showAll, units) -> reload(hub, showAll, units) }
            .launchIn(viewModelScope)

        container.healthSync.status
            .onEach { status ->
                _ui.value = _ui.value.copy(
                    syncStatus = status,
                    permissionsReset = status.phase == HealthSyncPhase.PERMISSIONS_RESET
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch { container.healthSync.refreshStatus() }
    }

    private fun unitPrefsFlow() = combine(
        container.prefs.weightUnit,
        container.prefs.heightUnit,
        container.prefs.healthGlucoseUnit,
        container.prefs.useMetric
    ) { weight, height, glucose, metric ->
        HealthUnitPrefs(
            metricMass = weight == "kg",
            metricLength = height == "cm",
            glucoseMgDl = when (glucose) {
                "mg/dL" -> true
                "mmol/L" -> false
                else -> HealthValueFormatter.defaultGlucoseMgDl(Locale.getDefault())
            },
            fahrenheit = !metric
        )
    }

    private suspend fun reload(hubEnabled: Boolean, showAll: Boolean, units: HealthUnitPrefs) {
        val snapshot = withContext(Dispatchers.Default) { buildSnapshot(hubEnabled, showAll) }
        _ui.value = _ui.value.copy(
            loading = false,
            availability = snapshot.availability,
            hubEnabled = hubEnabled,
            categories = snapshot.categories,
            storageBytes = snapshot.storageBytes,
            showAllTypes = showAll,
            hasAnyData = snapshot.hasAnyData,
            grantedTypeIds = snapshot.grantedTypeIds,
            unitPrefs = units,
            historyGranted = snapshot.historyGranted,
            historySupported = container.health.featureAvailable(HealthFeatureFlag.HISTORY)
        )
    }

    private class Snapshot(
        val availability: HealthConnectAvailability,
        val categories: List<HealthCategoryUi>,
        val storageBytes: Long,
        val hasAnyData: Boolean,
        val grantedTypeIds: Set<String>,
        val historyGranted: Boolean
    )

    private suspend fun buildSnapshot(hubEnabled: Boolean, showAll: Boolean): Snapshot {
        val availability = container.health.availability()
        val caps = when {
            availability != HealthConnectAvailability.AVAILABLE -> null
            !capsStale && cachedCaps != null -> cachedCaps
            else -> container.health.capabilitiesOrNull()?.also { cachedCaps = it; capsStale = false }
        }
        val grantedTypes = caps?.hubReadTypes ?: emptySet()
        val grantedPermissions = grantedTypes.mapNotNull { it.hcPermission }.toSet()
        if (caps != null) lastGrantedPermissions = grantedPermissions
        val repo = container.healthRepository
        val summaries = if (hubEnabled) repo.summaries() else emptyMap()
        val meta: Map<String, HealthTypeMeta> = if (hubEnabled) repo.typeMeta() else emptyMap()
        val states: Map<String, HealthSyncState> = if (hubEnabled) repo.syncStates() else emptyMap()
        val latestById = HashMap<String, HealthSampleRow?>()
        val today = LocalDate.now()
        val weekStart = today.minusDays(6)
        val dayTotals = HashMap<String, Pair<String, Double>>()
        for (typeId in summaries.keys) {
            latestById[typeId] = repo.latest(typeId)
            val type = HealthDataType.byId(typeId) ?: continue
            if (!(type.isSumType || type.isDurationLike) || summaries.getValue(typeId).count == 0L) continue
            // Cumulative types read as a day total, not as the last 10-minute chunk.
            val rollups = repo.daily(typeId, weekStart, today)
            val pick = { r: HealthDailyRollup -> if (type.isDurationLike) r.durationS ?: r.sum else r.sum }
            val chosen = rollups.firstOrNull { it.day == today.toString() && (pick(it) ?: 0.0) > 0.0 }
                ?: rollups.lastOrNull { (pick(it) ?: 0.0) > 0.0 }
            chosen?.let { r -> pick(r)?.let { dayTotals[typeId] = r.day to it } }
        }

        val categories = HealthCategory.entries.mapNotNull { category ->
            val registryTypes = HealthDataType.forCategory(category)
            val rows = mutableListOf<HealthTypeRowUi>()
            var hiddenEmpty = 0
            for (type in registryTypes) {
                if (type.isVirtualDietary || type == HealthDataType.NUTRITION_RECORD && summaries[type.id] == null && !showAll) continue
                val summary = summaries[type.id]
                val featureOk = HealthHubPermissions.featureOk(type, container.health::featureAvailable)
                val granted = type in grantedTypes
                val show = (summary != null && summary.count > 0) || (type.sdkAvailable && (granted || showAll))
                if (!show) {
                    if (type.sdkAvailable && featureOk) hiddenEmpty++
                    continue
                }
                val dayTotal = dayTotals[type.id]
                rows += HealthTypeRowUi(
                    typeId = type.id,
                    type = type,
                    displayNameHint = null,
                    latest = latestById[type.id],
                    count = summary?.count ?: 0L,
                    granted = granted,
                    featureGated = type.sdkAvailable && !featureOk,
                    sinceMs = states[type.id]?.let { it.backfillFloorMs ?: it.oldestBackfilledMs } ?: summary?.firstMs,
                    dayValue = dayTotal?.second,
                    dayKey = dayTotal?.first,
                    dayIsToday = dayTotal?.first == today.toString()
                )
            }
            // Unknown/imported ids registered at runtime.
            for (m in meta.values) {
                if (HealthDataType.byId(m.typeId) != null) continue
                val cat = HealthCategory.byId(m.category) ?: HealthCategory.OTHER
                if (cat != category) continue
                val summary = summaries[m.typeId] ?: continue
                rows += HealthTypeRowUi(m.typeId, null, m.displayName ?: m.nativeId, latestById[m.typeId], summary.count, granted = false, featureGated = false, sinceMs = summary.firstMs)
            }
            val missing = if (hubEnabled && availability == HealthConnectAvailability.AVAILABLE && caps != null) {
                HealthHubPermissions.categoryReadPermissions(category, container.health::featureAvailable) - grantedPermissions
            } else emptySet()
            val iosOnlyCategory = category == HealthCategory.HEARING || category == HealthCategory.MOBILITY || category == HealthCategory.SYMPTOMS
            if (rows.isEmpty() && (iosOnlyCategory || missing.isEmpty() && !showAll)) return@mapNotNull null
            if (rows.isEmpty() && !hubEnabled) return@mapNotNull null
            HealthCategoryUi(category, rows.sortedWith(compareByDescending<HealthTypeRowUi> { it.count > 0 }.thenBy { it.type?.ordinal ?: Int.MAX_VALUE }), missing, hiddenEmpty)
        }
        return Snapshot(
            availability = availability,
            categories = categories,
            storageBytes = if (hubEnabled) repo.storageBytes() else 0L,
            hasAnyData = summaries.values.any { it.count > 0 },
            grantedTypeIds = grantedTypes.map { it.id }.toSet(),
            historyGranted = caps?.historyRead == true
        )
    }

    // -- Actions ---------------------------------------------------------------

    fun refresh() {
        if (_ui.value.refreshing) return
        _ui.value = _ui.value.copy(refreshing = true)
        capsStale = true
        viewModelScope.launch {
            runCatching { container.requestHealthSync(HealthSyncTrigger.MANUAL_REFRESH).await() }
            _ui.value = _ui.value.copy(refreshing = false)
            reloadNow()
        }
    }

    /** While the hub is visible keep syncing back-to-back until the engine reports no more work. */
    fun setVisible(visible: Boolean) {
        visibleLoop?.cancel()
        visibleLoop = null
        if (!visible) return
        capsStale = true
        visibleLoop = viewModelScope.launch {
            var passes = 0
            while (isActive && passes < 40) {
                if (!container.prefs.healthHubEnabled.first()) break
                val outcome = runCatching { container.requestHealthSync(HealthSyncTrigger.HUB_VISIBLE).await() }.getOrNull()
                passes++
                if (outcome !is HealthSyncOutcome.Synced || !outcome.moreWork) break
            }
            reloadNow()
        }
    }

    fun setConsentChecked(checked: Boolean) {
        _ui.value = _ui.value.copy(consentChecked = checked)
    }

    fun toggleShowAllTypes() {
        showAllTypes.value = !showAllTypes.value
    }

    /** What the connect CTA launches: the CORE sheet plus History when the platform supports it. */
    fun connectPermissions(): Set<String> =
        container.health.hubRequestPermissions(HealthAndroidTier.CORE, includeHistory = true)

    fun allPermissions(): Set<String> = container.health.hubRequestPermissions(null, includeHistory = true)

    fun categoryPermissions(category: HealthCategory): Set<String> = container.health.categoryReadPermissions(category)

    fun historyPermissions(): Set<String> = setOf(container.health.historyPermission)

    /**
     * Every grant path lands here. Any hub read granted turns the hub (and the legacy toggle) on;
     * Coach access follows the visible consent toggle — never a silent flip.
     */
    fun onPermissionResult(granted: Set<String>) {
        capsStale = true
        viewModelScope.launch {
            val hubGranted = granted.any { it in container.health.allHubReadPermissions }
            if (hubGranted) {
                val wasEnabled = container.prefs.healthHubEnabled.first()
                container.prefs.setHealthHubEnabled(true)
                container.prefs.setHealthConnectEnabled(true)
                container.prefs.setHealthHubPromptedVersion(HealthHubPermissions.HUB_PERMISSIONS_VERSION)
                if (!wasEnabled) {
                    val consent = _ui.value.consentChecked
                    container.prefs.setCoachHealthDataEnabled(consent)
                    container.prefs.setCoachHealthDataConsentedAt(if (consent) Instant.now().toString() else null)
                }
            }
            if (hubGranted || container.prefs.healthHubEnabled.first()) {
                _ui.value = _ui.value.copy(refreshing = true)
                runCatching { container.requestHealthSync(HealthSyncTrigger.PERMISSIONS_CHANGED).await() }
                _ui.value = _ui.value.copy(refreshing = false)
            }
            reloadNow()
        }
    }

    fun clearSyncedData() {
        viewModelScope.launch {
            container.clearHealthData()
            reloadNow()
        }
    }

    fun setHomeTiles(types: List<HealthDataType>) {
        viewModelScope.launch { container.favoritePins.setHealth(types.map { it.id }) }
    }

    private suspend fun reloadNow() {
        val hub = container.prefs.healthHubEnabled.first()
        reload(hub, showAllTypes.value, _ui.value.unitPrefs)
    }

    override fun onCleared() {
        visibleLoop?.cancel()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HealthHubViewModel(container) as T
    }

    private companion object {
        const val REVISION_DEBOUNCE_MS = 500L
    }
}
