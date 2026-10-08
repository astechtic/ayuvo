package com.ayuvo.health.partner.net

import android.util.Log
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.noise.NoiseCrypto
import com.ayuvo.health.partner.sync.FramedChannel
import com.ayuvo.health.partner.sync.LocalPeer
import com.ayuvo.health.partner.sync.OutboundRenderer
import com.ayuvo.health.partner.sync.PartnerSession
import com.ayuvo.health.partner.sync.SessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** What happened when dialling one candidate partner at one endpoint. */
sealed interface DialOutcome {
    data class Synced(val ownerId: String, val result: SessionResult) : DialOutcome
    /** The endpoint answered but is not this partner (or no longer trusts me). */
    data object NotThisPartner : DialOutcome
    data class Unreachable(val localNetworkDenied: Boolean) : DialOutcome
    data object Busy : DialOutcome
}

/**
 * Trusted LAN sessions (docs/partner-sync.md §5, §11–§12): Noise KK over [FrameSocket], then a [PartnerSession].
 * At most one session per partner at a time; a second one is refused with ERROR busy after the handshake.
 */
class PartnerLink(
    private val store: PartnerStore,
    private val staticKey: () -> NoiseCrypto.KeyPair,
    private val renderer: OutboundRenderer,
    /** The window's in-flight ledger refresh; sessions wait for it (bounded) before serving. */
    private val awaitLedger: suspend () -> Unit = {},
    private val localPeer: suspend () -> LocalPeer
) {
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isActive(ownerId: String): Boolean = ownerId in active

    /** Runs [block] as the only session with [ownerId]; null when one is already running. */
    suspend fun <T> exclusive(ownerId: String, block: suspend () -> T): T? {
        if (!active.add(ownerId)) return null
        try {
            return block()
        } finally {
            active.remove(ownerId)
        }
    }

    private suspend fun session(ownerId: String, channel: FramedChannel): SessionResult =
        PartnerSession(store, renderer, localPeer(), ownerId, awaitLedger = awaitLedger).run(channel)

    private suspend fun refuseBusy(channel: FramedChannel) {
        runCatching { withTimeoutOrNull(2_000) { channel.send(PartnerJson.obj("t" to "ERROR", "code" to "busy")) } }
        channel.close()
    }

    /**
     * Dials [endpoint] as [partner]'s initiator. A `cursor_gap` ends that session; one new session follows at once
     * and starts from the committed cursor. A completed session records the endpoint as the partner's last address.
     */
    suspend fun dial(endpoint: Endpoint, partner: Partner): DialOutcome {
        val key = PartnerKdf.b64urlDecode(partner.x25519Pub) ?: return DialOutcome.NotThisPartner
        var attempt = 0
        while (true) {
            val conn = try {
                withContext(Dispatchers.IO) {
                    val frames = FrameSocket(PartnerLan.connect(endpoint))
                    try {
                        NoiseHandshakes.kkInitiate(frames, staticKey(), key)
                    } catch (e: Exception) {
                        frames.close()
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.ConnectException) {
                return DialOutcome.Unreachable(PartnerLan.isLocalNetworkDenied(e))
            } catch (e: java.net.SocketTimeoutException) {
                return DialOutcome.Unreachable(false)
            } catch (e: java.net.NoRouteToHostException) {
                return DialOutcome.Unreachable(false)
            } catch (e: java.net.SocketException) {
                if (PartnerLan.isLocalNetworkDenied(e)) return DialOutcome.Unreachable(true)
                return DialOutcome.NotThisPartner
            } catch (e: Exception) {
                return DialOutcome.NotThisPartner
            }
            val channel = conn.channel()
            val result = exclusive(partner.ownerId) { session(partner.ownerId, channel) }
                ?: run { channel.close(); return DialOutcome.Busy }
            if (result.error == "busy") return DialOutcome.Busy
            if (result.ok || result.pagesReceived > 0) {
                withContext(Dispatchers.IO) { store.setLastAddress(partner.ownerId, endpoint.host, endpoint.port, System.currentTimeMillis()) }
            }
            if (result.cursorGap && attempt++ == 0) continue
            return DialOutcome.Synced(partner.ownerId, result)
        }
    }

    /**
     * Answers one inbound connection: the initiator must be a trusted partner (unknown or unpaired static keys are
     * dropped before anything else is read or sent). Returns the owner id and session result, or null.
     */
    suspend fun accept(socket: Socket): Pair<String, SessionResult>? {
        val frames = FrameSocket(socket)
        val pair = try {
            withContext(Dispatchers.IO) {
                socket.tcpNoDelay = true
                val trusted = store.partners(includeUnpaired = false).mapNotNull { p -> PartnerKdf.b64urlDecode(p.x25519Pub)?.let { p.ownerId to it } }
                NoiseHandshakes.kkRespond(frames, staticKey(), trusted)
            }
        } catch (e: CancellationException) {
            frames.close()
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "inbound handshake failed: ${e.javaClass.simpleName}")
            null
        }
        if (pair == null) {
            frames.close()
            return null
        }
        val (owner, conn) = pair
        val channel = conn.channel()
        val result = exclusive(owner) { session(owner, channel) } ?: run { refuseBusy(channel); return null }
        return owner to result
    }

    companion object {
        private const val TAG = "PartnerLink"
    }
}
