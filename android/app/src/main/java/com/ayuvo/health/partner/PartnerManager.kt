package com.ayuvo.health.partner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ayuvo.health.partner.data.GrantOut
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.data.PartnerSyncState
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.net.PairConfirmResult
import com.ayuvo.health.partner.net.PairingConnection
import com.ayuvo.health.partner.net.PartnerLink
import com.ayuvo.health.partner.net.PartnerPairing
import com.ayuvo.health.partner.pkg.ExportResult
import com.ayuvo.health.partner.pkg.PackageImportOutcome
import com.ayuvo.health.partner.pkg.PackageInspection
import com.ayuvo.health.partner.pkg.PackagePreview
import com.ayuvo.health.partner.pkg.PackageSigner
import com.ayuvo.health.partner.pkg.PartnerPackageExporter
import com.ayuvo.health.partner.pkg.PartnerPackageImporter
import com.ayuvo.health.partner.sources.PartnerSource
import com.ayuvo.health.partner.sync.LedgerRefresher
import com.ayuvo.health.partner.sync.LocalPeer
import com.ayuvo.health.partner.sync.OutboundRenderer
import com.ayuvo.health.partner.sync.PartnerSession
import com.ayuvo.health.partner.sync.PartnerSyncCoordinator
import com.ayuvo.health.partner.sync.SyncTrigger
import com.ayuvo.health.partner.sync.WindowOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/** One partner as the Partner Health screens show it. */
data class PartnerRow(
    val partner: Partner,
    val sync: PartnerSyncState?,
    val grantsOut: List<GrantOut>,
    val grantsReceived: List<ReceivedGrant>,
    val recordCount: Long
)

/** The pairing flow (Add partner › Show code / Scan code). */
sealed interface PairingUi {
    data object Idle : PairingUi
    data class Showing(val qrText: String, val expMs: Long) : PairingUi
    data object Connecting : PairingUi
    /** Both screens show [sas]; the user confirms only if they match. */
    data class CompareCode(val sas: String, val peerName: String?, val peerFingerprint: String?) : PairingUi
    data object WaitingForPartner : PairingUi
    data class Syncing(val ownerId: String, val name: String) : PairingUi
    data class Paired(val ownerId: String, val name: String, val synced: Boolean) : PairingUi
    data object Declined : PairingUi
    data class Failed(val code: String) : PairingUi
}

/** A package received from the OS, for the confirmation sheet. */
sealed interface IncomingPackageUi {
    data class Preview(val preview: PackagePreview) : IncomingPackageUi
    data class Invalid(val code: String, val senderName: String?) : IncomingPackageUi
    data object Importing : IncomingPackageUi
    data class Done(val outcome: PackageImportOutcome, val senderName: String, val ownerId: String? = null) : IncomingPackageUi
}

data class PartnerUiState(
    val loaded: Boolean = false,
    val partners: List<PartnerRow> = emptyList(),
    val windowRunning: Boolean = false,
    val pairing: PairingUi = PairingUi.Idle,
    val incoming: IncomingPackageUi? = null
)

/**
 * The state holder the Partner Health UI uses (docs/partner-sync.md): partners with their sync state and grants,
 * pairing, sync windows, `.ayuvo.zip` export and the pending import. Everything is created lazily: nothing opens the
 * partner database or creates identity keys until Partner is used.
 */
class PartnerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    storeProvider: () -> PartnerStore,
    identityProvider: () -> DeviceIdentity,
    sourcesProvider: () -> List<PartnerSource>,
    private val myName: suspend () -> String,
    private val appVersion: String
) {
    private val store by lazy(storeProvider)
    private val identity by lazy(identityProvider)
    private val sources by lazy(sourcesProvider)

    private val _state = MutableStateFlow(PartnerUiState())
    val state: StateFlow<PartnerUiState> = _state

    private val refresher by lazy { LedgerRefresher(store, sources) }
    private val renderer by lazy { OutboundRenderer(store, sources) }
    private val link by lazy {
        PartnerLink(store, { identity.noiseStatic }, renderer, awaitLedger = { refresher.awaitInFlight() }) { localPeer() }
    }
    val coordinator: PartnerSyncCoordinator by lazy {
        PartnerSyncCoordinator(context, store, { identity.deviceId }, refresher, link, scope,
            onWaitingForNetwork = { PartnerSyncWorker.scheduleOnNetwork(context) })
    }
    private val pairing by lazy { PartnerPairing(context, store, identity, scope) }
    private val exporter by lazy {
        PartnerPackageExporter(store, renderer, refresher, {
            PackageSigner(identity.deviceId, identity.x25519Public, identity.ed25519Public) { identity.sign(it) }
        })
    }
    private val importer by lazy { PartnerPackageImporter(store, { identity.deviceId }) }

    private var shownCode: PartnerPairing.ShownCode? = null
    private var pairingConnection: PairingConnection? = null
    private var pairingJob: Job? = null
    /** Ledger refresh started while the users compare the code (see [confirmPairing]); null when one is fresh. */
    private var pairingRefresh: Deferred<*>? = null

    private fun startPairingRefresh() {
        if (pairingRefresh?.isActive == true) return
        pairingRefresh = refresher.refreshInBackground(scope)
    }

    private suspend fun localPeer(): LocalPeer = LocalPeer(identity.deviceId, "android", appVersion, myName())

    /** First use: mirrors my device id into partner_meta.device_id (docs §6). */
    private suspend fun ensureMeta() = withContext(Dispatchers.IO) {
        PartnerCatalog.install(context)
        val id = identity.deviceId
        if (store.meta(META_DEVICE_ID) != id) store.setMeta(META_DEVICE_ID, id)
    }

    /** Reloads partners from the database into [state] and keeps the background worker in step. */
    suspend fun reload() {
        val rows = withContext(Dispatchers.IO) {
            // A window killed with the app leaves connecting/syncing behind: settled once per process, before the
            // first status is shown (runWindow does the same before the first window).
            coordinator.clearStaleStatus()
            store.partners(includeUnpaired = true).map { p ->
                PartnerRow(p, store.syncState(p.ownerId), store.grantsOut(p.ownerId), store.grantsReceived(p.ownerId), store.recordCount(p.ownerId))
            }
        }
        _state.update { it.copy(loaded = true, partners = rows) }
        PartnerSyncWorker.update(context, rows.any { it.partner.trusted })
    }

    fun reloadAsync(): Job = scope.launch { runCatching { reload() } }

    suspend fun hasTrustedPartners(): Boolean = withContext(Dispatchers.IO) { store.trustedOwnerIds().isNotEmpty() }

    // -- sync windows --------------------------------------------------------------------------------------

    suspend fun runWindow(trigger: SyncTrigger): WindowOutcome {
        ensureMeta()
        _state.update { it.copy(windowRunning = true) }
        try {
            return coordinator.runWindow(trigger)
        } finally {
            _state.update { it.copy(windowRunning = coordinator.running.value) }
            runCatching { reload() }
        }
    }

    /** MainActivity.onStart: a window only when a partner exists (the caller checks the database exists first). */
    fun onAppForeground(): Job = scope.launch {
        runCatching {
            if (!hasTrustedPartners()) { PartnerSyncWorker.cancel(context); return@launch }
            PartnerSyncWorker.schedule(context)
            runWindow(SyncTrigger.APP_START)
        }.onFailure { Log.w(TAG, "foreground window failed: ${it.javaClass.simpleName}") }
    }

    fun syncNow(): Job = scope.launch { runCatching { runWindow(SyncTrigger.SYNC_NOW) } }

    // -- partners and grants -------------------------------------------------------------------------------

    suspend fun setGrants(ownerId: String, granted: Map<String, Boolean>) {
        withContext(Dispatchers.IO) { store.setGrantsOut(ownerId, granted, System.currentTimeMillis()) }
        reload()
    }

    /** Unpair: trust removed, data kept. */
    suspend fun unpair(ownerId: String) {
        withContext(Dispatchers.IO) { store.unpair(ownerId, System.currentTimeMillis()) }
        reload()
    }

    /** Delete partner data: rows removed, cursor back to 0. */
    suspend fun deletePartnerData(ownerId: String) {
        withContext(Dispatchers.IO) { store.deletePartnerData(ownerId) }
        reload()
    }

    /** Remove partner: trust, grants, data and sync state. */
    suspend fun removePartner(ownerId: String) {
        withContext(Dispatchers.IO) { store.removePartner(ownerId) }
        reload()
    }

    // -- pairing -------------------------------------------------------------------------------------------

    /** The profile name, the default for how I appear to a partner. */
    suspend fun profileName(): String = myName()

    /** This device's fingerprint (hex), shown in Settings › Partner Health › This device. Creates the identity. */
    suspend fun myFingerprint(): String {
        ensureMeta()
        return withContext(Dispatchers.IO) { identity.fingerprint }
    }

    /**
     * Show code: returns the QR text (render it with [PartnerQrBitmap]); the screen then follows [state].pairing.
     * [name] is how I appear to the scanning partner (the profile name when null).
     */
    suspend fun showCode(name: String? = null): String {
        cancelPairing()
        ensureMeta()
        val shown = pairing.showCode(name?.takeIf { it.isNotBlank() } ?: myName())
        shownCode = shown
        _state.update { it.copy(pairing = PairingUi.Showing(shown.qrText, shown.expMs)) }
        pairingJob = scope.launch {
            val conn = runCatching { shown.connections.receive() }.getOrNull()
            if (conn == null) {
                if (_state.value.pairing is PairingUi.Showing) _state.update { it.copy(pairing = PairingUi.Failed("expired")) }
                return@launch
            }
            pairingConnection = conn
            startPairingRefresh()
            _state.update { it.copy(pairing = PairingUi.CompareCode(conn.sas, null, null)) }
        }
        return shown.qrText
    }

    /** Scan code: dials the QR's phone and moves to the code comparison. */
    suspend fun scan(text: String) {
        cancelPairing()
        ensureMeta()
        _state.update { it.copy(pairing = PairingUi.Connecting) }
        pairing.scan(text).fold(
            onSuccess = { conn ->
                pairingConnection = conn
                startPairingRefresh()
                _state.update { it.copy(pairing = PairingUi.CompareCode(conn.sas, conn.qr?.name, conn.qr?.fingerprint?.let(PartnerKdf::formatFingerprint))) }
            },
            onFailure = { e ->
                val code = (e as? PartnerPairing.PairingException)?.code ?: "partner_unavailable"
                _state.update { it.copy(pairing = PairingUi.Failed(code)) }
            }
        )
    }

    /**
     * The user compared the codes. [grants] are the categories I share with this partner and [name] how I appear to
     * them. With both sides accepting, the partner is stored and the first sync runs on the same connection.
     */
    fun confirmPairing(accepted: Boolean, grants: Collection<String>, name: String): Job = scope.launch {
        val conn = pairingConnection ?: return@launch
        pairingConnection = null
        _state.update { it.copy(pairing = if (accepted) PairingUi.WaitingForPartner else PairingUi.Declined) }
        when (val r = pairing.confirm(conn, accepted, grants, name)) {
            PairConfirmResult.Declined -> _state.update { it.copy(pairing = PairingUi.Declined) }
            is PairConfirmResult.Failed -> _state.update { it.copy(pairing = PairingUi.Failed(r.code)) }
            is PairConfirmResult.Paired -> {
                val p = r.partner
                shownCode?.close(); shownCode = null
                PartnerSyncWorker.schedule(context)
                _state.update { it.copy(pairing = PairingUi.Syncing(p.ownerId, p.displayName)) }
                // The partner is already waiting on this channel and drops it after 20 s of silence (docs §5), so the
                // ledger refresh started during the code comparison gets a bounded wait; a refresh still running then
                // goes on in the background (ledger_delta reads one consistent snapshot) and the next sync sends the rest.
                withTimeoutOrNull(PAIRING_REFRESH_WAIT_MS) { (pairingRefresh ?: refresher.refreshInBackground(scope))?.await() }
                pairingRefresh = null
                val result = link.exclusive(p.ownerId) {
                    PartnerSession(store, renderer, localPeer(), p.ownerId).run(r.channel)
                }
                _state.update { it.copy(pairing = PairingUi.Paired(p.ownerId, p.displayName, result?.ok == true)) }
                runCatching { reload() }
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel(); pairingJob = null
        shownCode?.close(); shownCode = null
        pairingConnection?.channel?.close(); pairingConnection = null
        _state.update { it.copy(pairing = PairingUi.Idle) }
    }

    // -- packages ------------------------------------------------------------------------------------------

    /**
     * Builds a signed package for [ownerId] (everything shared, or only changes since their last acknowledgement)
     * in cache/partner-export/ and returns a share-sheet intent for it (one recipient per package).
     */
    suspend fun exportPackage(ownerId: String, sinceAcked: Boolean): Pair<ExportResult, Intent> {
        ensureMeta()
        val dir = File(context.cacheDir, EXPORT_DIR)
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.deleteRecursively() } }
        val result = exporter.export(ownerId, sinceAcked, myName(), dir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", result.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return result to Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * A VIEW / SEND zip from the OS: copied into the cache (size-capped) and sniffed for `manifest.json` format
     * "ayuvo-partner-sync". Returns false for anything else so the caller falls through to the Records handler.
     */
    suspend fun receiveIncoming(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, IMPORT_DIR).apply { mkdirs() }
        val file = File(dir, "incoming-${UUID.randomUUID()}.zip")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_INCOMING_BYTES) throw java.io.IOException("too large")
                        out.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) { file.delete(); return@withContext false }
        PartnerCatalog.install(context)
        if (!importer.isPartnerPackage(file)) { file.delete(); return@withContext false }
        ensureMeta()
        val ui = when (val i = importer.inspect(file)) {
            is PackageInspection.Ready -> IncomingPackageUi.Preview(i.preview)
            is PackageInspection.Invalid -> { file.delete(); IncomingPackageUi.Invalid(i.code, i.senderName) }
            PackageInspection.NotPackage -> { file.delete(); return@withContext false }
        }
        Log.i(TAG, "partner package received: ${if (ui is IncomingPackageUi.Invalid) ui.code else "ready"}")
        _state.update { it.copy(incoming = ui) }
        true
    }

    /** The user confirmed the sheet: import the pending package. */
    fun confirmImport(): Job = scope.launch {
        val preview = (_state.value.incoming as? IncomingPackageUi.Preview)?.preview ?: return@launch
        _state.update { it.copy(incoming = IncomingPackageUi.Importing) }
        val outcome = runCatching { importer.import(preview) }.getOrElse { PackageImportOutcome(false, "internal", com.ayuvo.health.partner.logic.MergeCounts(), 0) }
        withContext(Dispatchers.IO) { preview.file.delete() }
        _state.update { it.copy(incoming = IncomingPackageUi.Done(outcome, preview.senderName, preview.ownerId)) }
        runCatching { reload() }
    }

    /**
     * Settings › Partner Health › Import package (SAF fallback). Unlike an OS intent there is no other handler to fall
     * through to, so a file that is not a partner package is reported as `not_package` on the sheet.
     */
    fun importFromPicker(uri: Uri): Job = scope.launch {
        val ok = runCatching { receiveIncoming(uri) }.getOrDefault(false)
        if (!ok) _state.update { it.copy(incoming = IncomingPackageUi.Invalid("not_package", null)) }
    }

    fun discardImport() {
        val pending = _state.value.incoming
        if (pending is IncomingPackageUi.Preview) scope.launch(Dispatchers.IO) { pending.preview.file.delete() }
        _state.update { it.copy(incoming = null) }
    }

    companion object {
        const val META_DEVICE_ID = "device_id"
        const val EXPORT_DIR = "partner-export"
        const val IMPORT_DIR = "partner-import"
        private const val MAX_INCOMING_BYTES = 512L shl 20
        /** How long the first session after pairing waits for the ledger refresh; well under the 20 s idle timeout. */
        const val PAIRING_REFRESH_WAIT_MS = 8_000L
        private const val TAG = "PartnerManager"

        /** MIME types MainActivity sniffs for packages before the Records handler. */
        val PACKAGE_MIME_TYPES = setOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "application/x-zip")
    }
}
