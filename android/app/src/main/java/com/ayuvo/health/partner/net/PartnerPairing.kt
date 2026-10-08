package com.ayuvo.health.partner.net

import android.content.Context
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerProtocol
import com.ayuvo.health.partner.logic.PartnerQr
import com.ayuvo.health.partner.logic.QrPayload
import com.ayuvo.health.partner.sync.ChannelClosedException
import com.ayuvo.health.partner.sync.FramedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

/** An encrypted pairing connection after the IKpsk2 handshake, waiting for both users to compare the code. */
class PairingConnection internal constructor(
    val channel: FramedChannel,
    /** The 6-digit SAS both screens show. */
    val sas: String,
    /** The peer's X25519 key (from the QR for the scanner, from the handshake for the shower). */
    val peerX25519: String,
    /** Known up front only on the scanning side. */
    val qr: QrPayload?,
    val endpoint: Endpoint?
)

/** PAIR_CONFIRM outcome. */
sealed interface PairConfirmResult {
    data class Paired(val partner: Partner, val channel: FramedChannel) : PairConfirmResult
    data object Declined : PairConfirmResult
    data class Failed(val code: String) : PairConfirmResult
}

/**
 * QR pairing (docs/partner-sync.md §4). The phone showing the code runs a single-use IKpsk2 listener for 10 minutes;
 * the scanning phone dials the QR hosts (then DNS-SD results) as initiator. Both show the SAS; after each user
 * confirms and picks grants, PAIR_CONFIRM goes both ways inside the encrypted channel, both store the other in
 * `partners`, and the caller continues into a normal session on the same channel.
 */
class PartnerPairing(
    private val context: Context,
    private val store: PartnerStore,
    private val identity: DeviceIdentity,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** A shown code: the QR text and the listener waiting for the scanner. */
    inner class ShownCode internal constructor(
        val qrText: String,
        val expMs: Long,
        private val server: ServerSocket,
        private val nsd: PartnerNsd,
        val connections: Channel<PairingConnection>
    ) {
        private var job: Job? = null

        internal fun start(psk: ByteArray) {
            job = scope.launch(Dispatchers.IO) {
                try {
                    withTimeoutOrNull((expMs - now()).coerceAtLeast(0)) {
                        while (true) {
                            val socket = runCatching { server.accept() }.getOrNull() ?: break
                            val frames = FrameSocket(socket)
                            val conn = runCatching { NoiseHandshakes.ikRespond(frames, identity.noiseStatic, psk) }.getOrNull()
                            if (conn == null) { frames.close(); continue } // not a pairing initiator
                            // A wrong psk fails only on the initiator's side, which hangs up at once: keep listening.
                            if (frames.peerClosedWithin(PSK_PROBE_MS)) { frames.close(); continue }
                            val sas = PartnerKdf.sasCode(conn.handshakeHash)
                            connections.send(PairingConnection(conn.channel(), sas, PartnerKdf.b64urlEncode(conn.remoteStatic), null, null))
                            break // the token is single-use
                        }
                    }
                } finally {
                    close()
                }
            }
        }

        /** Stops listening and advertising; the token dies with the listener. */
        fun close() {
            runCatching { server.close() }
            nsd.close()
            connections.close()
        }
    }

    private fun randomToken(): String = PartnerKdf.b64urlEncode(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** Show code: opens the pairing listener, advertises it and returns the QR text. */
    suspend fun showCode(myName: String): ShownCode = withContext(Dispatchers.IO) {
        val token = randomToken()
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0)) }
        val hosts = PartnerLan.lanAddresses(context).map { "$it:${server.localPort}" }
        val exp = now() + PartnerCatalog.current.qrTtlMs
        val qr = PartnerQr.encode(identity.deviceId, myName, identity.x25519Public, identity.ed25519Public, token, exp, hosts)
        val nsd = PartnerNsd(context, scope)
        nsd.advertise(server.localPort)
        ShownCode(qr, exp, server, nsd, Channel(1)).also { it.start(PartnerKdf.pairingPsk(token)) }
    }

    /** Scan: parses the QR and dials its hosts, then DNS-SD results, as IKpsk2 initiator. */
    suspend fun scan(text: String): Result<PairingConnection> {
        val parsed = PartnerQr.parse(text, now(), identity.deviceId)
        val qr = parsed.payload ?: return Result.failure(PairingException(parsed.error ?: "malformed"))
        val psk = PartnerKdf.pairingPsk(qr.token)
        val key = PartnerKdf.b64urlDecode(qr.x25519)!!
        suspend fun attempt(ep: Endpoint): PairingConnection? = withContext(Dispatchers.IO) {
            val socket = runCatching { PartnerLan.connect(ep) }.getOrNull() ?: return@withContext null
            val frames = FrameSocket(socket)
            val conn = runCatching { NoiseHandshakes.ikInitiate(frames, identity.noiseStatic, key, psk) }.getOrNull()
            if (conn == null) { frames.close(); null }
            else PairingConnection(conn.channel(), PartnerKdf.sasCode(conn.handshakeHash), qr.x25519, qr, ep)
        }
        for (h in qr.hosts) Endpoint.parse(h)?.let { ep -> attempt(ep)?.let { return Result.success(it) } }
        // Hosts unreachable (different subnet, hotspot quirks): try what DNS-SD finds for a while.
        val found = Channel<Endpoint>(Channel.UNLIMITED)
        val nsd = PartnerNsd(context, scope)
        nsd.browse { found.trySend(it) }
        try {
            val conn = withTimeoutOrNull(SCAN_DISCOVERY_MS) {
                for (ep in found) attempt(ep)?.let { return@withTimeoutOrNull it }
                null
            }
            return conn?.let { Result.success(it) } ?: Result.failure(PairingException("partner_unavailable"))
        } finally {
            nsd.close()
            found.close()
        }
    }

    /**
     * Sends my PAIR_CONFIRM and waits for the partner's. On mutual acceptance the partner is stored with its keys and
     * my grants for it; re-pairing with unchanged keys keeps its data and cursor (PartnerStore.upsertPartner).
     */
    suspend fun confirm(conn: PairingConnection, accepted: Boolean, grants: Collection<String>, myName: String): PairConfirmResult {
        val c = PartnerCatalog.current
        val grantList = c.categories.filter { it in grants }
        val mine = PartnerJson.obj(
            "t" to "PAIR_CONFIRM", "accepted" to accepted, "name" to PartnerQr.cleanName(myName), "grants" to grantList,
            "device_id" to identity.deviceId, "ed25519" to identity.ed25519Public, "platform" to "android"
        )
        try {
            conn.channel.send(mine)
            if (!accepted) { conn.channel.close(); return PairConfirmResult.Declined }
            val theirs = conn.channel.receive(CONFIRM_WAIT_MS)
            if (PartnerJson.str(theirs["t"]) == "ERROR") { conn.channel.close(); return PairConfirmResult.Failed(PartnerJson.str(theirs["code"]) ?: "internal") }
            val check = PartnerProtocol.validate(theirs)
            if (!check.ok || PartnerJson.str(theirs["t"]) != "PAIR_CONFIRM") { conn.channel.close(); return PairConfirmResult.Failed("malformed") }
            if (PartnerJson.bool(theirs["accepted"]) != true) { conn.channel.close(); return PairConfirmResult.Declined }
            val deviceId = PartnerJson.str(theirs["device_id"])!!
            val ed = PartnerJson.str(theirs["ed25519"])!!
            // The scanner already knows who it should be talking to from the QR.
            if (conn.qr != null && (deviceId != conn.qr.deviceId || ed != conn.qr.ed25519)) { conn.channel.close(); return PairConfirmResult.Failed("not_trusted") }
            if (deviceId == identity.deviceId) { conn.channel.close(); return PairConfirmResult.Failed("not_trusted") }
            val nowMs = now()
            val partner = Partner(
                ownerId = deviceId,
                displayName = PartnerQr.cleanName(theirs["name"]),
                fingerprint = PartnerKdf.fingerprint(conn.peerX25519, ed),
                x25519Pub = conn.peerX25519,
                ed25519Pub = ed,
                platform = PartnerJson.str(theirs["platform"]),
                pairedMs = nowMs,
                unpairedMs = null,
                lastHost = null,
                lastPort = null,
                updatedMs = nowMs
            )
            withContext(Dispatchers.IO) {
                store.upsertPartner(partner)
                store.setGrantsOut(deviceId, c.categories.associateWith { it in grantList }, nowMs)
                store.updateGrantsReceived(deviceId, (theirs["grants"] as? JsonArray)?.mapNotNull { PartnerJson.str(it) }.orEmpty(), nowMs)
            }
            return PairConfirmResult.Paired(partner, conn.channel)
        } catch (e: ChannelClosedException) {
            conn.channel.close()
            return PairConfirmResult.Failed("partner_unavailable")
        }
    }

    class PairingException(val code: String) : Exception(code)

    companion object {
        /** How long the first side to confirm waits for the other user. */
        const val CONFIRM_WAIT_MS = 5 * 60_000L
        private const val SCAN_DISCOVERY_MS = 15_000L
        private const val PSK_PROBE_MS = 1_500L
    }
}
