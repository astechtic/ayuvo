package com.ayuvo.health.partner.sync

import android.content.Context
import android.util.Log
import com.ayuvo.health.BuildConfig
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.net.DialOutcome
import com.ayuvo.health.partner.net.Endpoint
import com.ayuvo.health.partner.net.PartnerLan
import com.ayuvo.health.partner.net.PartnerLink
import com.ayuvo.health.partner.net.PartnerNsd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/** Why a sync window was opened (for logs). */
enum class SyncTrigger { APP_START, WORKER, NETWORK, SYNC_NOW, PAIRED }

/** What one window achieved. */
data class WindowOutcome(val ran: Boolean, val synced: Set<String>, val unavailable: Set<String>, val waitingForNetwork: Boolean)

/**
 * Sync windows (docs/partner-sync.md §11): ≤ 60 s of advertise + listen + browse, a KK session with every trusted
 * partner found, then everything is torn down. No foreground service, no polling loop, no socket held open between
 * windows. One window at a time; a request while one runs joins it.
 *
 * Both sides connect rule (docs §11): when both browse, the device with the smaller device_id dials at once and the
 * other answers. The larger one dials only if no session from that partner arrived within [TIEBREAK_MS] (15 s), which
 * covers a partner whose browser is blocked; a concurrent second session is refused with `busy` and the first one
 * completes.
 */
class PartnerSyncCoordinator(
    private val context: Context,
    private val store: PartnerStore,
    private val myDeviceId: () -> String,
    private val refresher: LedgerRefresher,
    private val link: PartnerLink,
    private val scope: CoroutineScope,
    private val onWaitingForNetwork: () -> Unit = {},
    private val windowMs: Long = WINDOW_MS
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private suspend fun setStatus(ownerIds: Collection<String>, status: String, error: String? = null, onlyIf: (String) -> Boolean = { true }) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (id in ownerIds) {
                val s = store.syncState(id) ?: continue
                if (!onlyIf(s.status)) continue
                store.updateSyncState(s.copy(status = status, lastError = error ?: s.lastError, lastAttemptMs = now))
            }
        }

    private val staleCleared = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Once per process, before the first window (and before PartnerManager shows any status): a window that was
     * killed with the app leaves `connecting` / `syncing` behind, which would otherwise show until the next window.
     * Those become `partner_unavailable`; last_sync_ms and last_error are kept. Blocking (database); call off main.
     */
    fun clearStaleStatus() {
        if (!staleCleared.compareAndSet(false, true)) return
        runCatching { settleStaleStatus(store, link::isActive) }.onFailure { Log.w(TAG, "stale status reset failed: ${it.javaClass.simpleName}") }
    }

    /** Opens a window unless one is running (then waits for it). */
    suspend fun runWindow(trigger: SyncTrigger): WindowOutcome {
        if (!mutex.tryLock()) {
            mutex.withLock { }
            return WindowOutcome(false, emptySet(), emptySet(), false)
        }
        try {
            // Under the window mutex, so it never rewrites the status of a window running in this process.
            withContext(Dispatchers.IO) { clearStaleStatus() }
            _running.value = true
            return window(trigger)
        } finally {
            _running.value = false
            mutex.unlock()
        }
    }

    private suspend fun window(trigger: SyncTrigger): WindowOutcome = coroutineScope {
        val partners = withContext(Dispatchers.IO) { store.partners(includeUnpaired = false) }
        if (partners.isEmpty()) return@coroutineScope WindowOutcome(false, emptySet(), emptySet(), false)
        val ids = partners.map { it.ownerId }
        if (!PartnerLan.available(context)) {
            setStatus(ids, "waiting_for_network")
            onWaitingForNetwork()
            return@coroutineScope WindowOutcome(true, emptySet(), ids.toSet(), true)
        }
        Log.i(TAG, "window ($trigger) for ${partners.size} partner(s)")
        setStatus(ids, "connecting")

        val me = myDeviceId()
        val synced: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val failedHere: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val lnpDenied = java.util.concurrent.atomic.AtomicBoolean(false)
        val allDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = System.currentTimeMillis()
        // The larger device_id waits this long for the partner to dial before dialling itself (short test windows scale).
        val lateAfterMs = minOf(TIEBREAK_MS, windowMs / 2)
        val windowScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + Dispatchers.IO)
        val sessions = mutableListOf<Job>()
        val sessionsLock = Any()
        fun track(j: Job) = synchronized(sessionsLock) { sessions += j }

        // docs §10–§11: the ledger refresh runs alongside discovery instead of before it (a full refresh can take
        // tens of seconds on a cold start and used to eat the window). Skipped when one finished < 2 min ago.
        // Sessions wait for it, bounded, before serving (PartnerSession.LEDGER_WAIT_MS); otherwise they serve the
        // ledger as it stands and the next window carries the rest.
        val refresh = refresher.refreshInBackground(windowScope)
        if (refresh == null) Log.i(TAG, "ledger refresh skipped (fresh)")

        fun record(owner: String, r: SessionResult) {
            if (BuildConfig.DEBUG) Log.i(TAG, "session ${owner.take(8)}: ok=${r.ok} error=${r.error} sent=${r.sentRecords} pages=${r.pagesReceived}")
            if (r.ok) synced += owner else failedHere += owner
            if (synced.containsAll(ids)) allDone.complete(Unit)
        }

        // Listen.
        val server = withContext(Dispatchers.IO) {
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), if (BuildConfig.DEBUG) debugListenPort else 0)) } }.getOrNull()
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "listening on port ${server?.localPort}")
        val acceptJob = server?.let { ss ->
            windowScope.launch {
                while (isActive && !ss.isClosed) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: break
                    track(windowScope.launch { link.accept(socket)?.let { (owner, r) -> record(owner, r) } })
                }
            }
        }

        // Advertise + browse.
        val nsd = PartnerNsd(context, windowScope)
        val endpoints = Channel<Endpoint>(Channel.UNLIMITED)
        val myHosts = PartnerLan.lanAddresses(context).toSet()
        if (server != null) nsd.advertise(server.localPort)
        nsd.browse { ep -> if (!(ep.host in myHosts && ep.port == server?.localPort)) endpoints.trySend(ep) }

        fun pending(): List<Partner> = partners.filter { it.ownerId !in synced && !link.isActive(it.ownerId) }
        fun mayDial(p: Partner, lateStage: Boolean) = lateStage || me < p.ownerId

        suspend fun tryEndpoint(ep: Endpoint, lateStage: Boolean, only: Partner? = null) {
            val candidates = (only?.let { listOf(it) } ?: pending()).filter { it.ownerId !in synced && mayDial(it, lateStage) }
            for (p in candidates) {
                when (val o = link.dial(ep, p)) {
                    is DialOutcome.Synced -> { record(o.ownerId, o.result); return }
                    is DialOutcome.Unreachable -> { if (o.localNetworkDenied) lnpDenied.set(true); return }
                    DialOutcome.Busy -> return
                    DialOutcome.NotThisPartner -> continue
                }
            }
        }

        val seen = mutableListOf<Endpoint>()
        val dialer = windowScope.launch {
            // Discovered endpoints, as they resolve.
            launch {
                for (ep in endpoints) {
                    synchronized(seen) { seen += ep }
                    val late = System.currentTimeMillis() - started > lateAfterMs
                    track(windowScope.launch { tryEndpoint(ep, late) })
                }
            }
            // Fallback: the last address that completed a session, after giving discovery a head start; then, once
            // the tie-break wait is over (no session from that partner arrived), everything again as the larger id.
            val head = minOf(FALLBACK_DELAY_MS, lateAfterMs)
            delay(head)
            for (p in pending()) {
                val host = p.lastHost ?: continue
                val port = p.lastPort ?: continue
                if (mayDial(p, false)) tryEndpoint(Endpoint(host, port), lateStage = false, only = p)
            }
            delay((lateAfterMs - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            for (p in pending()) {
                val eps = synchronized(seen) { seen.toList() } + listOfNotNull(p.lastHost?.let { h -> p.lastPort?.let { Endpoint(h, it) } })
                for (ep in eps.distinct()) {
                    if (p.ownerId in synced) break
                    tryEndpoint(ep, lateStage = true, only = p)
                }
            }
        }

        // Wait until everyone is synced or the window ends.
        withTimeoutOrNull(windowMs) { allDone.await() }
        // Tear down discovery and the listener; sessions already running finish under their own 5-minute cap.
        nsd.close()
        endpoints.close()
        dialer.cancelAndJoin()
        runCatching { server?.close() }
        acceptJob?.cancelAndJoin()
        val running = synchronized(sessionsLock) { sessions.toList() }
        withTimeoutOrNull(PartnerSession.PROTOCOL_SESSION_MS) { running.joinAll() }
        // Let the refresh finish (a worker-run window keeps the process alive for it); the next window then skips it.
        refresh?.join()
        windowScope.coroutineContext[Job]?.cancel()

        val unavailable = ids.filter { it !in synced && it !in failedHere }.toSet()
        setStatus(unavailable, if (lnpDenied.get()) "local_network_denied" else "partner_unavailable") { it == "connecting" || it == "syncing" }
        withContext(Dispatchers.IO) { store.retentionPrune(System.currentTimeMillis()) }
        WindowOutcome(true, synced.toSet(), unavailable, false)
    }

    /** Opens a window on [scope] without waiting (MainActivity.onStart, Sync Now). */
    fun launchWindow(trigger: SyncTrigger): Job = scope.launch { runCatching { runWindow(trigger) } }

    companion object {
        const val WINDOW_MS = 60_000L
        private const val FALLBACK_DELAY_MS = 5_000L
        /** docs §11: the larger device_id dials only after this long without a session from that partner. */
        const val TIEBREAK_MS = 15_000L
        private const val TAG = "PartnerSync"

        /** Rewrites `connecting` / `syncing` (left by a killed window) to `partner_unavailable`; returns how many. */
        fun settleStaleStatus(store: PartnerStore, sessionActive: (String) -> Boolean = { false }): Int {
            var n = 0
            for (id in store.partners(includeUnpaired = true).map { it.ownerId }) {
                if (sessionActive(id)) continue // a live session in this process (e.g. the first sync after pairing)
                val s = store.syncState(id) ?: continue
                if (s.status != "connecting" && s.status != "syncing") continue
                store.updateSyncState(s.copy(status = "partner_unavailable"))
                n++
            }
            return n
        }

        /** Debug builds only (src/debug DebugPartnerSeedActivity): a fixed window listener port for adb-forward tests; 0 = ephemeral. */
        @Volatile var debugListenPort: Int = 0
    }
}
