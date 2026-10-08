package com.ayuvo.health.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.MainActivity
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.identity.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ayuvo.health.partner.IncomingPackageUi
import com.ayuvo.health.partner.PairingUi
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerQr
import com.ayuvo.health.partner.net.Endpoint
import com.ayuvo.health.partner.net.FrameSocket
import com.ayuvo.health.partner.net.NoiseHandshakes
import com.ayuvo.health.partner.net.PartnerLan
import com.ayuvo.health.partner.noise.NoiseCrypto
import com.ayuvo.health.partner.sync.PartnerSyncCoordinator
import com.ayuvo.health.partner.sync.SyncTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * ADB-only Partner Health hook, compiled into debug APKs only (src/debug). It lets the shared fixture
 * `shared/partner/fixtures/partner-sample.ayuvo.zip` be imported on an emulator:
 *
 * - `--ez seed_fixture true`: stores the fixture sender (partner-sample.json) as a paired partner and makes this
 *   install's partner device id the fixture recipient, then ends the process so the identity reloads with that id.
 * - `--es import <path>`: hands a readable file to PartnerManager.receiveIncoming, exactly like a VIEW intent, and
 *   opens the app so the confirmation sheet shows.
 *
 * ```
 * adb push shared/partner/fixtures/partner-sample.ayuvo.zip /data/local/tmp/
 * adb shell am start -n com.ayuvo.health/.debug.DebugPartnerSeedActivity --ez seed_fixture true
 * adb shell am start -n com.ayuvo.health/.debug.DebugPartnerSeedActivity --es import /data/local/tmp/partner-sample.ayuvo.zip
 * ```
 *
 * Cross-platform test hooks (emulator ↔ iOS simulator, no NSD across the NAT; every step runs the real pairing /
 * Noise / session code, only the address is supplied):
 * - `--ez show_code true`: Add partner › Show code; logs `QR <text>` (its hosts carry the listener port).
 * - `--es scan <qr text> [--es scan_host host:port]`: Scan code; `scan_host` is dialled before the QR hosts.
 * - `--ez auto_confirm true` (with show_code / scan): logs `SAS <6 digits>` and confirms with all categories granted.
 * - `--ei listen_port P`: the sync-window listener binds port P (for `adb forward`).
 * - `--es dial host:port`: stores host:port as every trusted partner's last address (the window's direct-dial fallback).
 * - `--ez sync_now true`: Sync Now; logs the window outcome.
 * - `--es kk_probe host:port`: a fresh, unknown X25519 identity attempts Noise KK against the most recently paired
 *   partner's key there; logs whether the responder rejected it.
 * - `--es import <path> --ez confirm_import true`: also taps Import and logs the preview and outcome.
 * - `--es delete_data <owner id>`: Delete partner data (rows removed, cursor back to 0).
 * - `--es export_for <owner id>`: writes a full package to cache/partner-export and logs its path.
 * - `--ez dump true`: logs partners, grants and sync state.
 */
class DebugPartnerSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AyuvoApp).container
        lifecycleScope.launch {
            if (intent.getBooleanExtra("seed_fixture", false)) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        container.deviceIdentity.deviceId // creates the keys if needed
                        container.keyStore.save(DeviceIdentity.KEY_DEVICE_ID, FIXTURE_RECIPIENT)
                        val now = System.currentTimeMillis()
                        container.partnerStore.upsertPartner(
                            Partner(
                                ownerId = FIXTURE_SENDER, displayName = FIXTURE_NAME, fingerprint = FIXTURE_FINGERPRINT,
                                x25519Pub = FIXTURE_X25519, ed25519Pub = FIXTURE_ED25519, platform = "android",
                                pairedMs = now, updatedMs = now
                            )
                        )
                    }
                }.onSuccess { Log.i(TAG, "fixture partner seeded; restarting") }
                    .onFailure { Log.e(TAG, "fixture seed failed", it) }
                // KeyStore.save uses apply(): give the write time to land before the process ends.
                delay(1_500)
                finish()
                // The identity is cached in memory with the old id: restart the process so it reloads.
                Process.killProcess(Process.myPid())
                return@launch
            }
            runCatching { crossPlatformHooks(container) }.onFailure { Log.e(TAG, "hook failed", it) }
            // Pairing and sync continue in the app scope: keep the app in the foreground so the cached-app freezer
            // does not suspend the process once this activity finishes.
            if (listOf("show_code", "scan", "sync_now").any { intent.hasExtra(it) }) {
                startActivity(Intent(this@DebugPartnerSeedActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            intent.getStringExtra("import")?.let { path ->
                val ok = runCatching { container.partnerManager.receiveIncoming(Uri.fromFile(File(path))) }
                    .onFailure { Log.e(TAG, "import failed", it) }.getOrDefault(false)
                Log.i(TAG, "import $path -> partner package: $ok")
                // `--ez confirm_import true`: taps Import on the sheet and logs the preview and the outcome.
                val manager = container.partnerManager
                when (val incoming = manager.state.value.incoming) {
                    is IncomingPackageUi.Preview -> {
                        Log.i(TAG, "import preview ${incoming.preview}")
                        if (intent.getBooleanExtra("confirm_import", false)) container.scope.launch {
                            manager.confirmImport().join()
                            Log.i(TAG, "import outcome ${manager.state.value.incoming}")
                        }
                    }
                    else -> Log.i(TAG, "import state $incoming")
                }
                startActivity(Intent(this@DebugPartnerSeedActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
    }

    private suspend fun crossPlatformHooks(container: com.ayuvo.health.AppContainer) {
        val manager = container.partnerManager
        val store = container.partnerStore
        PartnerCatalog.install(applicationContext)
        if (intent.hasExtra("listen_port")) {
            PartnerSyncCoordinator.debugListenPort = intent.getIntExtra("listen_port", 0)
            Log.i(TAG, "listen_port ${PartnerSyncCoordinator.debugListenPort}")
        }
        intent.getStringExtra("dial")?.let { hp ->
            val ep = Endpoint.parse(hp) ?: return@let
            withContext(Dispatchers.IO) {
                for (p in store.partners(includeUnpaired = false)) store.setLastAddress(p.ownerId, ep.host, ep.port, System.currentTimeMillis())
            }
            Log.i(TAG, "dial $ep")
        }
        val autoConfirm = intent.getBooleanExtra("auto_confirm", false)
        if (intent.getBooleanExtra("show_code", false)) {
            val qr = manager.showCode()
            Log.i(TAG, "QR $qr")
            if (autoConfirm) autoConfirm(container)
        }
        intent.getStringExtra("scan")?.let { text ->
            if (autoConfirm) autoConfirm(container)
            val host = intent.getStringExtra("scan_host")
            val scanned = if (host == null) text else {
                val q = PartnerQr.parse(text, System.currentTimeMillis(), null).payload
                if (q == null) text else PartnerQr.encode(q.deviceId, q.name, q.x25519, q.ed25519, q.token, q.expMs, listOf(host) + q.hosts)
            }
            container.scope.launch { manager.scan(scanned) }
        }
        intent.getStringExtra("kk_probe")?.let { hp ->
            val ep = Endpoint.parse(hp) ?: return@let
            val target = withContext(Dispatchers.IO) { store.partners(includeUnpaired = false).maxByOrNull { it.pairedMs } } ?: return@let
            val outcome = withContext(Dispatchers.IO) {
                val stranger = NoiseCrypto.generateKeyPair()
                val frames = runCatching { FrameSocket(PartnerLan.connect(ep)) }.getOrElse { return@withContext "unreachable: ${it.javaClass.simpleName}" }
                try {
                    val conn = NoiseHandshakes.kkInitiate(frames, stranger, PartnerKdf.b64urlDecode(target.x25519Pub)!!)
                    val msg = runCatching { conn.channel().receive(5_000) }.getOrNull()
                    "ACCEPTED (unexpected), first message: $msg"
                } catch (e: Exception) {
                    "rejected (not_trusted): ${e.javaClass.simpleName}"
                } finally {
                    frames.close()
                }
            }
            Log.i(TAG, "kk_probe $ep as stranger vs ${target.ownerId}: $outcome")
        }
        intent.getStringExtra("delete_data")?.let { owner ->
            manager.deletePartnerData(owner)
            Log.i(TAG, "deleted partner data of $owner")
        }
        intent.getStringExtra("export_for")?.let { owner ->
            val (result, _) = manager.exportPackage(owner, sinceAcked = false)
            Log.i(TAG, "exported ${result.file.absolutePath} (${result.file.length()} bytes)")
        }
        if (intent.getBooleanExtra("sync_now", false)) {
            container.scope.launch {
                val outcome = manager.runWindow(SyncTrigger.SYNC_NOW)
                Log.i(TAG, "sync_now outcome $outcome")
                dump(container)
            }
        }
        if (intent.getBooleanExtra("dump", false)) dump(container)
        if (intent.getBooleanExtra("ledger_profile", false)) withContext(Dispatchers.IO) { ledgerProfile(container) }
    }

    /**
     * `--ez ledger_profile true`: profiles a full ledger refresh of this install's real sources into a throwaway
     * partner database (the real one is untouched): per type the source read, the content hashing and the ledger
     * write, then the whole LedgerRefresher run (full, then a window run). Logs `ledger_profile …` lines.
     */
    private suspend fun ledgerProfile(container: com.ayuvo.health.AppContainer) {
        // `--ei profile_delay_s N`: start N seconds after launch (separates app-start contention from the refresh itself).
        delay(intent.getIntExtra("profile_delay_s", 0) * 1_000L)
        val name = "partner_ledger_profile.db"
        com.ayuvo.health.partner.data.PartnerDatabase.deleteDatabaseFiles(applicationContext, name)
        val sources = container.partnerSources()
        val catalog = PartnerCatalog.current
        val db = com.ayuvo.health.partner.data.PartnerDatabase(applicationContext, name)
        try {
            val store = com.ayuvo.health.partner.data.SqlitePartnerStore(db)
            val refresher = com.ayuvo.health.partner.sync.LedgerRefresher(store, sources)
            val full = refresher.refresh()
            Log.i(TAG, "ledger_profile full refresh: ${full.rows} rows in ${full.elapsedMs} ms ${full.typeMs}")
            val window = refresher.refresh()
            Log.i(TAG, "ledger_profile window refresh: ${window.rows} rows in ${window.elapsedMs} ms ${window.typeMs}")
        } finally {
            db.close()
            com.ayuvo.health.partner.data.PartnerDatabase.deleteDatabaseFiles(applicationContext, name)
        }
        // Warm breakdown: source read vs content hashing per type.
        for (s in sources.sortedBy { it.type }) {
            val spec = catalog.types[s.type] ?: continue
            val dayFrom = if (spec.scope == "intraday") java.time.LocalDate.now().minusDays(catalog.intradayDays.toLong()).toString() else null
            val recs = ArrayList<com.ayuvo.health.partner.sources.SourceRecord>()
            val t0 = System.nanoTime()
            runCatching { s.forEach(dayFrom) { recs += it } }
            val t1 = System.nanoTime()
            recs.forEach { it.contentHash }
            val t2 = System.nanoTime()
            recs.forEach { com.ayuvo.health.partner.sources.SourceRecord.referenceHash(it) }
            val t3 = System.nanoTime()
            Log.i(TAG, "ledger_profile ${s.type}: ${recs.size} rows, read ${(t1 - t0) / 1_000_000} ms, hash ${(t2 - t1) / 1_000_000} ms (reference hash ${(t3 - t2) / 1_000_000} ms)")
        }
        // Warm full refreshes (fresh throwaway database each time).
        repeat(3) { i ->
            com.ayuvo.health.partner.data.PartnerDatabase.deleteDatabaseFiles(applicationContext, name)
            val wdb = com.ayuvo.health.partner.data.PartnerDatabase(applicationContext, name)
            try {
                val r = com.ayuvo.health.partner.sync.LedgerRefresher(com.ayuvo.health.partner.data.SqlitePartnerStore(wdb), sources).refresh()
                Log.i(TAG, "ledger_profile warm full refresh #${i + 1}: ${r.rows} rows in ${r.elapsedMs} ms, writing ${r.writeMs.values.sum()} ms")
            } finally {
                wdb.close()
                com.ayuvo.health.partner.data.PartnerDatabase.deleteDatabaseFiles(applicationContext, name)
            }
        }
    }

    private fun autoConfirm(container: com.ayuvo.health.AppContainer) {
        val manager = container.partnerManager
        container.scope.launch {
            val compare = withTimeoutOrNull(10 * 60_000L) {
                manager.state.map { it.pairing }.first { it is PairingUi.CompareCode || it is PairingUi.Failed }
            }
            Log.i(TAG, "pairing state $compare")
            if (compare !is PairingUi.CompareCode) return@launch
            Log.i(TAG, "SAS ${compare.sas} peer=${compare.peerName} fingerprint=${compare.peerFingerprint}")
            manager.confirmPairing(true, PartnerCatalog.current.categories, manager.profileName())
            val end = withTimeoutOrNull(6 * 60_000L) {
                manager.state.map { it.pairing }.first { it is PairingUi.Paired || it is PairingUi.Failed || it is PairingUi.Declined }
            }
            Log.i(TAG, "pairing finished $end")
            dump(container)
        }
    }

    private suspend fun dump(container: com.ayuvo.health.AppContainer) = withContext(Dispatchers.IO) {
        val store = container.partnerStore
        Log.i(TAG, "me ${container.deviceIdentity.deviceId} fingerprint ${PartnerKdf.formatFingerprint(container.deviceIdentity.fingerprint)}")
        for (p in store.partners(includeUnpaired = true)) {
            Log.i(TAG, "partner ${p.ownerId} '${p.displayName}' ${p.platform} fp=${PartnerKdf.formatFingerprint(p.fingerprint)} last=${p.lastHost}:${p.lastPort} " +
                "out=${store.grantsOut(p.ownerId).filter { it.granted }.map { it.category }} sync=${store.syncState(p.ownerId)} records=${store.recordCount(p.ownerId)}")
        }
    }

    private companion object {
        const val TAG = "AyuvoPartnerDebug"
        // shared/partner/fixtures/partner-sample.json
        const val FIXTURE_RECIPIENT = "7e6d5c4b-3a29-4180-8f7e-6d5c4b3a2918"
        const val FIXTURE_SENDER = "0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f"
        const val FIXTURE_NAME = "Ananya"
        const val FIXTURE_FINGERPRINT = "5cbe8b6c1a846a49e8e550ecf82c3931"
        const val FIXTURE_X25519 = "YiiWYQ7mJTehVbgzdY3cpXACQOeE3oby2RoPSS7LbFA"
        const val FIXTURE_ED25519 = "BwzLCWnVbK85oHgDHwR5J23GwE6XurQhgoUrbV1J_QQ"
    }
}
