package com.ayuvo.health.partner.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** The peer closed the channel, a frame was invalid, or nothing arrived within the idle timeout. */
class ChannelClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One protocol message (§12, a JSON object) per frame, already authenticated and encrypted by the transport.
 * [send] may be called concurrently with [receive]; implementations serialize writes.
 */
interface FramedChannel {
    suspend fun send(message: JsonObject)

    /** The next message; throws [ChannelClosedException] on close, a bad frame, or after [timeoutMs] of silence. */
    suspend fun receive(timeoutMs: Long): JsonObject

    fun close()
}

/** An in-process pair of channels (tests, and the pairing hand-off). */
class InMemoryFramedChannel private constructor(
    private val inbox: Channel<JsonObject>,
    private val outbox: Channel<JsonObject>
) : FramedChannel {
    /** Test hook: fails every send after this many messages (simulates a dropped connection). */
    @Volatile var failAfterSends: Int = -1
    private var sends = 0

    override suspend fun send(message: JsonObject) {
        if (failAfterSends in 0..sends) { close(); throw ChannelClosedException("injected drop") }
        sends++
        // Round-trip through text so tests see exactly what a socket would carry.
        val copy = com.ayuvo.health.partner.logic.PartnerJson.parse(message.toString()) as JsonObject
        try {
            outbox.send(copy)
        } catch (e: Exception) {
            throw ChannelClosedException("closed", e)
        }
    }

    override suspend fun receive(timeoutMs: Long): JsonObject {
        val r = withTimeoutOrNull(timeoutMs) { inbox.receiveCatching() } ?: throw ChannelClosedException("idle timeout")
        return r.getOrNull() ?: throw ChannelClosedException("closed")
    }

    override fun close() {
        inbox.close()
        outbox.close()
    }

    companion object {
        fun pair(): Pair<InMemoryFramedChannel, InMemoryFramedChannel> {
            val a = Channel<JsonObject>(Channel.UNLIMITED)
            val b = Channel<JsonObject>(Channel.UNLIMITED)
            return InMemoryFramedChannel(a, b) to InMemoryFramedChannel(b, a)
        }
    }
}
