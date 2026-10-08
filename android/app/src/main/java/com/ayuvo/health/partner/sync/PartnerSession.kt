package com.ayuvo.health.partner.sync

import android.util.Log
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.logic.MergeCounts
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerMerge
import com.ayuvo.health.partner.logic.PartnerProtocol
import com.ayuvo.health.partner.logic.RecordKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Outcome of one session. [error] is a protocol error code, "incomplete", or null on success. */
data class SessionResult(
    val ok: Boolean,
    val error: String?,
    val received: MergeCounts,
    val sentRecords: Int,
    val pagesReceived: Int,
    /** Records I skipped on the network because they cannot fit one frame (docs §7.6 "Frame fit"). */
    val oversizeSkipped: Int = 0
) {
    val cursorGap: Boolean get() = error == "cursor_gap"
}

/** Who I am in HELLO. */
data class LocalPeer(val deviceId: String, val platform: String = "android", val appVersion: String, val name: String? = null)

/** Sent/received ERROR, carried out of the session. */
private class SessionError(val code: String, val send: Boolean) : Exception(code)

/**
 * One sync session with one trusted partner over an authenticated [FramedChannel] (docs/partner-sync.md §12).
 * Transport-agnostic: the LAN transport runs it over Noise KK, tests over an in-memory pair.
 *
 * Both directions run at once: I serve the peer's SYNC_REQ with CHANGES pages from `ledger_delta` (each waits for its
 * ACK, which sets `acked_rev`), and I pull with my own SYNC_REQ, merging each CHANGES page through `merge_apply` and
 * committing it with the cursor in one transaction before I ACK it. DONE goes out once my pull ended and my pages are
 * acknowledged. A `cursor_gap` refusal ends the session with ERROR cursor_gap; the caller opens a new session, whose
 * SYNC_REQ carries the committed cursor (session_script allows one SYNC_REQ per session).
 */
class PartnerSession(
    private val store: PartnerStore,
    private val renderer: OutboundRenderer,
    private val me: LocalPeer,
    /** The partner the transport authenticated (Noise static key → owner id). */
    private val peerOwnerId: String,
    private val transport: String = TRANSPORT_LAN,
    private val now: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = PROTOCOL_IDLE_MS,
    private val sessionTimeoutMs: Long = PROTOCOL_SESSION_MS,
    /** Waits for the window's in-flight ledger refresh ([LedgerRefresher.awaitInFlight]); bounded by [ledgerWaitMs]. */
    private val awaitLedger: suspend () -> Unit = {},
    private val ledgerWaitMs: Long = LEDGER_WAIT_MS
) {
    private val sendLock = Mutex()
    private val received = MergeCounts()
    private var sentRecords = 0
    private var pagesReceived = 0
    private var oversizeSkipped = 0

    private suspend fun send(ch: FramedChannel, msg: JsonObject) = sendLock.withLock { ch.send(msg) }

    private fun error(code: String) = PartnerJson.obj("t" to "ERROR", "code" to code)

    suspend fun run(channel: FramedChannel): SessionResult {
        val result = try {
            withTimeout(sessionTimeoutMs) { runInner(channel) }
            SessionResult(true, null, received, sentRecords, pagesReceived, oversizeSkipped)
        } catch (e: SessionError) {
            if (e.send) runCatching { withTimeoutOrNull(2_000) { send(channel, error(e.code)) } }
            SessionResult(false, e.code, received, sentRecords, pagesReceived, oversizeSkipped)
        } catch (e: TimeoutCancellationException) {
            SessionResult(false, INCOMPLETE, received, sentRecords, pagesReceived, oversizeSkipped)
        } catch (e: CancellationException) {
            channel.close()
            throw e
        } catch (e: ChannelClosedException) {
            SessionResult(false, INCOMPLETE, received, sentRecords, pagesReceived, oversizeSkipped)
        } catch (e: Exception) {
            Log.w(TAG, "session failed: ${e.javaClass.simpleName}")
            runCatching { withTimeoutOrNull(2_000) { send(channel, error("internal")) } }
            SessionResult(false, "internal", received, sentRecords, pagesReceived, oversizeSkipped)
        } finally {
            channel.close()
        }
        recordOutcome(result)
        return result
    }

    private suspend fun recordOutcome(r: SessionResult) = withContext(Dispatchers.IO) {
        val state = store.syncState(peerOwnerId) ?: return@withContext
        val nowMs = now()
        val next = when {
            r.ok -> state.copy(status = "up_to_date", lastError = null, lastSyncMs = nowMs, lastAttemptMs = nowMs, lastTransport = transport)
            r.error == "not_trusted" -> state.copy(status = "not_trusted", lastError = r.error, lastAttemptMs = nowMs)
            r.error == "busy" -> state.copy(lastAttemptMs = nowMs)
            else -> state.copy(status = "sync_failed", lastError = r.error, lastAttemptMs = nowMs,
                // Committed pages stay; the partner still counts as synced up to them.
                lastSyncMs = if (r.pagesReceived > 0) nowMs else state.lastSyncMs, lastTransport = if (r.pagesReceived > 0) transport else state.lastTransport)
        }
        store.updateSyncState(next)
        store.retentionPrune(nowMs)
    }

    private suspend fun runInner(ch: FramedChannel) = coroutineScope {
        val grantsOut = withContext(Dispatchers.IO) { store.grantedCategoriesOut(peerOwnerId) }
        send(ch, hello(grantsOut))
        val hello = ch.receive(idleTimeoutMs)
        checkHello(hello)
        withContext(Dispatchers.IO) {
            store.updateGrantsReceived(peerOwnerId, (hello["grants"] as JsonArray).mapNotNull { PartnerJson.str(it) }, now())
            store.syncState(peerOwnerId)?.let { store.updateSyncState(it.copy(status = "syncing", lastAttemptMs = now())) }
        }
        val cursor = withContext(Dispatchers.IO) { store.syncState(peerOwnerId)?.lastRev ?: 0L }
        send(ch, PartnerJson.obj("t" to "SYNC_REQ", "cursor" to cursor))

        val peerRequest = CompletableDeferred<Long>()
        val acks = Channel<Long>(Channel.UNLIMITED)
        val pullDone = CompletableDeferred<Unit>()
        val serveDone = CompletableDeferred<Unit>()
        val doneSent = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable>()

        val server = launch {
            try {
                serve(ch, peerRequest.await(), grantsOut, acks)
                serveDone.complete(Unit)
                pullDone.await()
                send(ch, PartnerJson.obj("t" to "DONE"))
                doneSent.complete(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure.complete(e)
            }
        }
        try {
            var peerDone = false
            var syncReqSeen = false
            while (!peerDone) {
                if (failure.isCompleted) throw failure.await()
                val msg = ch.receive(idleTimeoutMs)
                val check = PartnerProtocol.validate(msg)
                val t = PartnerJson.str(msg["t"])
                if (t == "ERROR") throw SessionError(PartnerJson.str(msg["code"]) ?: "internal", send = false)
                if (!check.ok) throw SessionError(if (check.error == "unsupported_version") "unsupported_version" else "malformed", send = true)
                when (t) {
                    "SYNC_REQ" -> {
                        if (syncReqSeen) throw SessionError("malformed", send = true)
                        syncReqSeen = true
                        peerRequest.complete(PartnerJson.long(msg["cursor"])!!)
                    }
                    "CHANGES" -> {
                        if (pullDone.isCompleted) throw SessionError("malformed", send = true)
                        val (ack, last) = merge(msg)
                        send(ch, PartnerJson.obj("t" to "ACK", "committed_rev" to ack))
                        if (last) pullDone.complete(Unit)
                    }
                    "ACK" -> {
                        if (!syncReqSeen) throw SessionError("malformed", send = true)
                        acks.send(PartnerJson.long(msg["committed_rev"])!!)
                    }
                    "DONE" -> {
                        if (!pullDone.isCompleted) throw SessionError("malformed", send = true)
                        peerDone = true
                    }
                    else -> throw SessionError("malformed", send = true)
                }
            }
            // The peer's DONE means my pages were all acknowledged; finish sending my own DONE.
            val err = withTimeout(idleTimeoutMs) {
                select<Throwable?> {
                    doneSent.onAwait { null }
                    failure.onAwait { it }
                }
            }
            if (err != null) throw err
        } finally {
            server.cancel()
        }
    }

    private fun hello(grants: List<String>): JsonObject {
        val c = PartnerCatalog.current
        val m = linkedMapOf<String, Any?>(
            "t" to "HELLO", "protocol" to c.protocolId, "v" to c.protocolVersion, "device_id" to me.deviceId,
            "platform" to me.platform, "app_version" to me.appVersion, "grants" to grants, "capabilities" to emptyList<String>()
        )
        if (me.name != null) m["name"] = me.name
        return PartnerJson.of(m) as JsonObject
    }

    private suspend fun checkHello(msg: JsonObject) {
        val t = PartnerJson.str(msg["t"])
        if (t == "ERROR") throw SessionError(PartnerJson.str(msg["code"]) ?: "internal", send = false)
        val check = PartnerProtocol.validate(msg)
        if (!check.ok) throw SessionError(if (check.error == "unsupported_version") "unsupported_version" else "malformed", send = true)
        if (t != "HELLO") throw SessionError("malformed", send = true)
        // The HELLO identity must be the partner Noise authenticated, and that partner must still be trusted.
        if (PartnerJson.str(msg["device_id"]) != peerOwnerId) throw SessionError("not_trusted", send = true)
        val partner = withContext(Dispatchers.IO) { store.partner(peerOwnerId) }
        if (partner == null || !partner.trusted) throw SessionError("not_trusted", send = true)
    }

    /** Merges one CHANGES page and commits it with the cursor in one transaction. Returns (committed_rev, last page). */
    private suspend fun merge(msg: JsonObject): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        val state = store.syncState(peerOwnerId)
        val cursor = state?.lastRev ?: 0L
        val granted = store.grantsReceived(peerOwnerId).filter { it.granted }.map { it.category }
        val keys = (msg["records"] as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val type = PartnerJson.str(o["type"]) ?: return@mapNotNull null
            val id = PartnerJson.str(o["id"]) ?: return@mapNotNull null
            RecordKey(type, id)
        }
        val stored = store.storedRevs(peerOwnerId, keys)
        val res = PartnerMerge.apply({ stored[it] }, msg, cursor, granted, now())
        if (!res.accepted) throw SessionError(res.error ?: "malformed", send = true)
        store.applyMerge(peerOwnerId, res, commitCursor = true)
        received.add(res.counts)
        pagesReceived++
        res.cursor to (PartnerJson.bool(msg["has_more"]) == false)
    }

    /** Serves the peer's SYNC_REQ: delta pages in rev order, each waiting for its ACK. */
    private suspend fun serve(ch: FramedChannel, requested: Long, grants: List<String>, acks: Channel<Long>) {
        // The window's ledger refresh runs alongside discovery (docs §10–§11), so it may still be running here. HELLO
        // and SYNC_REQ have already gone out and my pull keeps running (the peer's pages are merged and ACKed), so
        // only serving waits, and at most LEDGER_WAIT_MS (15 s): the peer, which has my HELLO and SYNC_REQ, waits at
        // most its 20 s idle timeout for my first CHANGES, and 15 s leaves room for the delta query and rendering.
        // Waiting before HELLO instead would leave the peer's HELLO receive with the same budget but stall my pull too.
        // A refresh still running after the wait is not waited for: ledger_delta serves the ledger as it stands (each
        // type commits in one transaction, so it is consistent) and the next window carries the newer changes.
        withTimeoutOrNull(ledgerWaitMs) { awaitLedger() }
        var cursor = requested
        val limit = PartnerCatalog.current.batchMax
        while (true) {
            val delta = withContext(Dispatchers.IO) { store.ledgerDelta(cursor, grants, limit) }
            val envelopes = withContext(Dispatchers.IO) { renderer.envelopes(delta) }
            val pages = renderer.split(delta, envelopes) { oversizeSkipped++ }
            for (page in pages) {
                send(ch, page.message())
                sentRecords += page.records.size
                val ack = withTimeoutOrNull(idleTimeoutMs) { acks.receive() } ?: throw ChannelClosedException("no ACK")
                withContext(Dispatchers.IO) {
                    store.syncState(peerOwnerId)?.let { if (ack > it.ackedRev) store.updateSyncState(it.copy(ackedRev = ack)) }
                }
            }
            val last = pages.last()
            if (!last.hasMore) return
            cursor = last.toRev
        }
    }

    companion object {
        /** schema.sql `last_transport`: network|package. */
        const val TRANSPORT_LAN = "network"
        const val TRANSPORT_PACKAGE = "package"
        const val INCOMPLETE = "incomplete"
        const val PROTOCOL_IDLE_MS = 20_000L
        const val PROTOCOL_SESSION_MS = 300_000L
        /** Longest wait for an in-flight ledger refresh before serving; under the peer's [PROTOCOL_IDLE_MS]. */
        const val LEDGER_WAIT_MS = 15_000L
        private const val TAG = "PartnerSession"
    }
}
