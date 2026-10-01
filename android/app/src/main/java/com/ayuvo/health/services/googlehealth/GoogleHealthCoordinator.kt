package com.ayuvo.health.services.googlehealth

import android.content.Context
import android.util.Log
import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.services.health.HealthConnectManager
import com.ayuvo.health.services.health.HealthWriteRetryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GoogleHealthTrigger { APP_OPEN, MANUAL, SETUP }

/** Everything the Google Health settings page and the Sync Now status line show. */
data class GoogleHealthUi(
    val account: GoogleHealthAccount? = null,
    val autoSync: Boolean = false,
    val writeBack: Boolean = true,
    val needsReconnect: Boolean = false,
    val sync: GoogleHealthSyncStatus = GoogleHealthSyncStatus(),
    /** Last run failed outright (network, auth) — shown on the status row, never on the platform sync. */
    val lastFailed: Boolean = false
) {
    val connected: Boolean get() = account != null
}

/**
 * App-facing Google Health entry point owned by AppContainer: OAuth ([auth]), the sync engine and the
 * Health Connect write-back, plus the device-local account prefs. A Google failure is logged and shown
 * on its own row; it never fails the platform sync that runs before it.
 */
class GoogleHealthCoordinator(
    private val context: Context,
    private val prefs: PreferencesStore,
    keyStore: KeyStore,
    private val health: HealthConnectManager,
    private val store: () -> HealthDataStore,
    private val scope: CoroutineScope,
    webClientId: String
) {
    val map: GoogleHealthMap by lazy {
        GoogleHealthMap.parse(context.assets.open(GoogleHealthMap.ASSET_PATH).bufferedReader().use { it.readText() })
    }

    val auth: GoogleHealthAuth by lazy { GoogleHealthAuth(context, map, keyStore, webClientId) }

    val engine: GoogleHealthSyncEngine by lazy {
        GoogleHealthSyncEngine(
            map = map,
            source = GoogleHealthClient(map, auth.tokenProvider { prefs.googleHealthAccount.first() }),
            store = store(),
            log = { Log.i(TAG, it) }
        )
    }

    val mirror: GoogleHealthMirrorWriter by lazy {
        GoogleHealthMirrorWriter(
            store = store(),
            insert = { health.insertGoogleHealthRecords(it) },
            grantedPermissions = { if (health.isAvailable()) health.grantedPermissionsOrNull() else null },
            scheduleRetry = { HealthWriteRetryWorker.enqueueGoogleHealth(context) },
            log = { Log.w(TAG, it) }
        )
    }

    private val lastFailed = MutableStateFlow(false)
    private val engineStatus = MutableStateFlow(GoogleHealthSyncStatus())
    @Volatile private var lastManualMs = 0L

    val ui: StateFlow<GoogleHealthUi> = combine(
        prefs.googleHealthAccount,
        prefs.googleHealthAutoSync,
        prefs.googleHealthWriteBack,
        prefs.googleHealthNeedsReconnect,
        combine(engineStatus, lastFailed) { s, f -> s to f }
    ) { account, auto, writeBack, reconnect, (status, failed) ->
        GoogleHealthUi(account, auto, writeBack, reconnect, status, failed)
    }.stateIn(scope, SharingStarted.Eagerly, GoogleHealthUi())

    @Volatile private var collecting = false

    init {
        // Mirror the engine's status without creating the engine (and the database) for users who never connect.
        scope.launch {
            if (prefs.googleHealthAccount.first() != null) {
                ensureStatusCollected()
                engine.refreshStatus()
            }
        }
    }

    @Synchronized
    private fun ensureStatusCollected() {
        if (collecting) return
        collecting = true
        scope.launch { engine.status.collect { engineStatus.value = it } }
    }

    suspend fun refreshStatus() {
        if (prefs.googleHealthAccount.first() == null) return
        ensureStatusCollected()
        engine.refreshStatus()
    }

    /**
     * Runs after the platform sync: manual and setup triggers always (30 s manual throttle), app open
     * only with Auto-sync on and at most every `auto_sync_min_interval_s`. Null when nothing ran.
     */
    suspend fun sync(trigger: GoogleHealthTrigger): GoogleHealthSyncOutcome? {
        val account = prefs.googleHealthAccount.first() ?: return null
        val now = System.currentTimeMillis()
        when (trigger) {
            GoogleHealthTrigger.APP_OPEN -> {
                if (!prefs.googleHealthAutoSync.first() || prefs.googleHealthNeedsReconnect.first()) return null
                val last = prefs.googleHealthLastAutoSyncMs()
                if (last != null && now - last < map.api.autoSyncMinIntervalS * 1000L) return null
                prefs.setGoogleHealthLastAutoSyncMs(now)
            }
            GoogleHealthTrigger.MANUAL -> {
                if (now - lastManualMs < map.api.manualSyncMinIntervalS * 1000L) return null
                lastManualMs = now
            }
            GoogleHealthTrigger.SETUP -> Unit
        }
        ensureStatusCollected()
        val writeBack = prefs.googleHealthWriteBack.first()
        val outcome = runCatching {
            engine.sync(
                GoogleHealthSyncRequest(account.grantedScopes, account.groups, writeBack),
                wait = trigger != GoogleHealthTrigger.APP_OPEN
            )
        }.onFailure { Log.w(TAG, "Google Health sync failed: ${it.javaClass.simpleName}") }.getOrNull()
        lastFailed.value = outcome == null
        when (outcome) {
            GoogleHealthSyncOutcome.NeedsReconnect -> prefs.setGoogleHealthNeedsReconnect(true)
            is GoogleHealthSyncOutcome.Synced -> {
                prefs.setGoogleHealthNeedsReconnect(false)
                flushMirror(writeBack)
            }
            else -> Unit
        }
        return outcome
    }

    suspend fun flushMirror(writeBack: Boolean? = null): GoogleHealthMirrorResult? = runCatching {
        mirror.flush(writeBack ?: prefs.googleHealthWriteBack.first())
    }.onFailure { Log.w(TAG, "Google Health write-back failed: ${it.javaClass.simpleName}") }.getOrNull()

    /** Saves the account after consent (setup step 3 or Reconnect) and fetches the email label. */
    suspend fun completeConnection(
        grant: GoogleHealthGrant,
        groups: Set<String>,
        mode: GoogleHealthClientMode,
        customClientId: String?
    ): GoogleHealthAccount {
        val existing = prefs.googleHealthAccount.first()
        val account = GoogleHealthAccount(
            email = auth.email(grant.accessToken) ?: existing?.email,
            connectedAtMs = existing?.connectedAtMs ?: System.currentTimeMillis(),
            clientMode = mode,
            grantedScopes = grant.grantedScopes,
            groups = groups,
            customClientId = customClientId?.trim()?.takeIf { mode == GoogleHealthClientMode.CUSTOM && it.isNotEmpty() }
        )
        prefs.setGoogleHealthAccount(account)
        prefs.setGoogleHealthNeedsReconnect(false)
        lastFailed.value = false
        return account
    }

    /** Groups whose scopes were all refused, for the "partially granted" line in step 3. */
    fun refusedGroups(requested: Set<String>, granted: Set<String>): List<GoogleHealthMap.ScopeGroup> =
        map.scopeGroups.filter { it.id in requested && it.scopes.none { s -> (map.api.scopePrefix + s) in granted } }

    suspend fun setAutoSync(on: Boolean) = prefs.setGoogleHealthAutoSync(on)

    suspend fun setWriteBack(on: Boolean) {
        prefs.setGoogleHealthWriteBack(on)
        if (prefs.googleHealthAccount.first() != null) scope.launch { flushMirror(on) }
    }

    /**
     * Disconnect (docs/google-health.md §2): revoke, forget tokens, drop the sync state and, when
     * [deleteRows], the origin-3 rows. Records already written to Health Connect stay there.
     */
    suspend fun disconnect(deleteRows: Boolean) {
        runCatching { auth.revokeAndClear() }
        runCatching { engine.clear(deleteRows) }.onFailure { Log.w(TAG, "Google Health clear failed: ${it.javaClass.simpleName}") }
        prefs.setGoogleHealthAccount(null)
        lastFailed.value = false
    }

    companion object {
        private const val TAG = "AyuvoGoogleHealth"
    }
}
