package com.ayuvo.health.partner.net

import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.noise.HandshakeState
import com.ayuvo.health.partner.noise.NoiseCrypto
import com.ayuvo.health.partner.noise.NoiseException
import com.ayuvo.health.partner.noise.NoisePattern
import com.ayuvo.health.partner.noise.NoiseTransport
import com.ayuvo.health.partner.sync.ChannelClosedException
import com.ayuvo.health.partner.sync.FramedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/** docs/partner-sync.md §5 framing: 2-byte big-endian length + one Noise message (≤ 65535 bytes). Blocking; call on IO. */
class FrameSocket(val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    fun write(frame: ByteArray) {
        if (frame.size > NoiseCrypto.MAX_MESSAGE) throw IOException("frame too large")
        synchronized(output) {
            output.writeShort(frame.size)
            output.write(frame)
            output.flush()
        }
    }

    /** Reads one frame, waiting at most [timeoutMs]. */
    fun read(timeoutMs: Long): ByteArray {
        socket.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val n = input.readUnsignedShort()
        val b = ByteArray(n)
        input.readFully(b)
        return b
    }

    /**
     * True when the peer closes the connection within [timeoutMs] without sending anything. Nothing is consumed:
     * a byte that does arrive is pushed back. Used after a pairing handshake, whose psk failure only the initiator
     * can see (it then hangs up at once).
     */
    fun peerClosedWithin(timeoutMs: Long): Boolean {
        socket.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        input.mark(1)
        return try {
            val b = input.read()
            if (b < 0) true else { input.reset(); false }
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    fun close() {
        runCatching { socket.close() }
    }
}

/** A completed handshake on a socket. */
class NoiseConnection(val frames: FrameSocket, val transport: NoiseTransport, val remoteStatic: ByteArray) {
    val handshakeHash: ByteArray get() = transport.handshakeHash

    fun channel(): FramedChannel = NoiseFramedChannel(frames, transport)
}

/** One UTF-8 JSON protocol message per Noise transport message (docs §5, §12). */
class NoiseFramedChannel(private val frames: FrameSocket, private val transport: NoiseTransport) : FramedChannel {
    private val sendLock = Mutex()
    private val receiveLock = Mutex()

    override suspend fun send(message: JsonObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        sendLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    frames.write(transport.send.encrypt(bytes))
                } catch (e: IOException) {
                    throw ChannelClosedException("send failed", e)
                } catch (e: NoiseException) {
                    throw ChannelClosedException("encrypt failed", e)
                }
            }
        }
    }

    override suspend fun receive(timeoutMs: Long): JsonObject = receiveLock.withLock {
        withContext(Dispatchers.IO) {
            val frame = try {
                frames.read(timeoutMs)
            } catch (e: SocketTimeoutException) {
                throw ChannelClosedException("idle timeout", e)
            } catch (e: IOException) {
                throw ChannelClosedException("closed", e)
            }
            val plain = try {
                transport.receive.decrypt(frame)
            } catch (e: NoiseException) {
                throw ChannelClosedException("bad frame", e)
            }
            val text = PartnerJson.decodeUtf8(plain) ?: throw ChannelClosedException("not UTF-8")
            runCatching { PartnerJson.parse(text) }.getOrNull() as? JsonObject ?: throw ChannelClosedException("not a JSON object")
        }
    }

    override fun close() = frames.close()
}

/** The two handshakes of docs §4–§5 over [FrameSocket]s. Blocking; call on Dispatchers.IO. */
object NoiseHandshakes {
    const val HANDSHAKE_TIMEOUT_MS = 20_000L

    private fun prologue(): ByteArray = PartnerCatalog.current.noisePrologue.toByteArray(Charsets.UTF_8)

    /** Trusted session, initiator: I know the partner's static key. */
    fun kkInitiate(frames: FrameSocket, local: NoiseCrypto.KeyPair, remoteStatic: ByteArray): NoiseConnection {
        val hs = HandshakeState(NoisePattern.KK, true, prologue(), local, rs = remoteStatic)
        frames.write(hs.writeMessage())
        hs.readMessage(frames.read(HANDSHAKE_TIMEOUT_MS))
        return NoiseConnection(frames, hs.transport, remoteStatic)
    }

    /**
     * Trusted session, responder: the initiator's static key must be one of [trusted] (paired, not unpaired). The
     * first message only authenticates under the right key, so each candidate is tried; none matching means the
     * initiator is unknown and null is returned before anything else is read or sent.
     */
    fun kkRespond(frames: FrameSocket, local: NoiseCrypto.KeyPair, trusted: List<Pair<String, ByteArray>>): Pair<String, NoiseConnection>? {
        val first = frames.read(HANDSHAKE_TIMEOUT_MS)
        for ((owner, key) in trusted) {
            val hs = HandshakeState(NoisePattern.KK, false, prologue(), local, rs = key)
            try {
                hs.readMessage(first)
            } catch (_: NoiseException) {
                continue
            }
            frames.write(hs.writeMessage())
            return owner to NoiseConnection(frames, hs.transport, key)
        }
        return null
    }

    /** Pairing, initiator (the scanner): responder key from the QR, psk from the QR token. */
    fun ikInitiate(frames: FrameSocket, local: NoiseCrypto.KeyPair, remoteStatic: ByteArray, psk: ByteArray): NoiseConnection {
        val hs = HandshakeState(NoisePattern.IK_PSK2, true, prologue(), local, rs = remoteStatic, psks = listOf(psk))
        frames.write(hs.writeMessage())
        hs.readMessage(frames.read(HANDSHAKE_TIMEOUT_MS))
        return NoiseConnection(frames, hs.transport, remoteStatic)
    }

    /** Pairing, responder (the phone showing the QR): learns the initiator's static key; a wrong psk throws. */
    fun ikRespond(frames: FrameSocket, local: NoiseCrypto.KeyPair, psk: ByteArray, firstTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS): NoiseConnection {
        val hs = HandshakeState(NoisePattern.IK_PSK2, false, prologue(), local, psks = listOf(psk))
        hs.readMessage(frames.read(firstTimeoutMs))
        frames.write(hs.writeMessage())
        return NoiseConnection(frames, hs.transport, hs.remoteStatic ?: throw NoiseException("no remote static"))
    }
}
