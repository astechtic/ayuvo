package com.ayuvo.health.partner

import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.noise.HandshakeState
import com.ayuvo.health.partner.noise.NoiseCrypto
import com.ayuvo.health.partner.noise.NoiseException
import com.ayuvo.health.partner.noise.NoisePattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays the published cacophony vectors (shared/partner/test-vectors/noise_*.json) byte for byte: fixed
 * static and ephemeral keys, prologue and psks from the vector, every handshake and transport ciphertext and the
 * final handshake hash (docs/partner-sync.md §3, §5).
 */
class NoiseVectorTests {

    private fun hex(o: JsonObject, k: String): ByteArray? = PartnerJson.str(o[k])?.let(PartnerKdf::unhex)

    private fun replay(file: String): Int {
        val root = PartnerJson.parse(PartnerTestFiles.shared("test-vectors/$file")!!.readText()) as JsonObject
        var replayed = 0
        for (v in (root["vectors"] as JsonArray).map { it as JsonObject }) {
            val pattern = NoisePattern.byName(PartnerJson.str(v["protocol_name"])!!)
            val psks = { k: String -> (v[k] as? JsonArray).orEmpty().map { PartnerKdf.unhex(PartnerJson.str(it)!!) } }
            val init = HandshakeState(
                pattern, initiator = true, prologue = hex(v, "init_prologue") ?: ByteArray(0),
                s = hex(v, "init_static")?.let(NoiseCrypto::keyPairFromPrivate),
                e = hex(v, "init_ephemeral")?.let(NoiseCrypto::keyPairFromPrivate),
                rs = hex(v, "init_remote_static"), psks = psks("init_psks")
            )
            val resp = HandshakeState(
                pattern, initiator = false, prologue = hex(v, "resp_prologue") ?: ByteArray(0),
                s = hex(v, "resp_static")?.let(NoiseCrypto::keyPairFromPrivate),
                e = hex(v, "resp_ephemeral")?.let(NoiseCrypto::keyPairFromPrivate),
                rs = hex(v, "resp_remote_static"), psks = psks("resp_psks")
            )
            val messages = (v["messages"] as JsonArray).map { it as JsonObject }
            for ((i, m) in messages.withIndex()) {
                val payload = hex(m, "payload")!!
                val expected = hex(m, "ciphertext")!!
                val fromInit = i % 2 == 0
                val (writer, reader) = if (fromInit) init to resp else resp to init
                if (i < pattern.messages.size) {
                    val ct = writer.writeMessage(payload)
                    assertArrayEquals("$file message $i ciphertext", expected, ct)
                    assertArrayEquals("$file message $i payload", payload, reader.readMessage(ct))
                    if (i == pattern.messages.size - 1) {
                        assertTrue(init.isComplete && resp.isComplete)
                        assertArrayEquals("$file handshake hash (init)", hex(v, "handshake_hash"), init.handshakeHash)
                        assertArrayEquals("$file handshake hash (resp)", hex(v, "handshake_hash"), resp.handshakeHash)
                        assertArrayEquals(init.remoteStatic, NoiseCrypto.keyPairFromPrivate(hex(v, "resp_static")!!).publicKey)
                        assertArrayEquals(resp.remoteStatic, NoiseCrypto.keyPairFromPrivate(hex(v, "init_static")!!).publicKey)
                    }
                } else {
                    val ct = writer.transport.send.encrypt(payload)
                    assertArrayEquals("$file transport message $i", expected, ct)
                    assertArrayEquals(payload, reader.transport.receive.decrypt(ct))
                }
                replayed++
            }
        }
        return replayed
    }

    @Test
    fun ikPsk2CacophonyReplay() {
        assertEquals(6, replay("noise_ikpsk2.json"))
    }

    @Test
    fun kkCacophonyReplay() {
        assertEquals(6, replay("noise_kk.json"))
    }

    @Test
    fun wrongPskFailsPairingHandshake() {
        val a = NoiseCrypto.generateKeyPair()
        val b = NoiseCrypto.generateKeyPair()
        val prologue = "ayuvo-partner-sync/1".toByteArray()
        val init = HandshakeState(NoisePattern.IK_PSK2, true, prologue, a, rs = b.publicKey, psks = listOf(ByteArray(32) { 1 }))
        val resp = HandshakeState(NoisePattern.IK_PSK2, false, prologue, b, psks = listOf(ByteArray(32) { 2 }))
        resp.readMessage(init.writeMessage())
        val m2 = resp.writeMessage()
        try {
            init.readMessage(m2)
            fail("a wrong psk must fail the handshake")
        } catch (_: NoiseException) {
        }
    }

    @Test
    fun kkRejectsUnknownInitiatorStatic() {
        val a = NoiseCrypto.generateKeyPair()
        val b = NoiseCrypto.generateKeyPair()
        val stranger = NoiseCrypto.generateKeyPair()
        val prologue = "ayuvo-partner-sync/1".toByteArray()
        // The responder believes it talks to `stranger`; the initiator is really `a`.
        val init = HandshakeState(NoisePattern.KK, true, prologue, a, rs = b.publicKey)
        val resp = HandshakeState(NoisePattern.KK, false, prologue, b, rs = stranger.publicKey)
        try {
            resp.readMessage(init.writeMessage("hi".toByteArray()))
            fail("KK with the wrong static key must not decrypt")
        } catch (_: NoiseException) {
        }
    }

    @Test
    fun transportRoundTripAndTamper() {
        val a = NoiseCrypto.generateKeyPair()
        val b = NoiseCrypto.generateKeyPair()
        val init = HandshakeState(NoisePattern.KK, true, ByteArray(0), a, rs = b.publicKey)
        val resp = HandshakeState(NoisePattern.KK, false, ByteArray(0), b, rs = a.publicKey)
        resp.readMessage(init.writeMessage())
        init.readMessage(resp.writeMessage())
        val big = ByteArray(65535 - 16) { it.toByte() }
        assertArrayEquals(big, resp.transport.receive.decrypt(init.transport.send.encrypt(big)))
        try {
            init.transport.send.encrypt(ByteArray(65535 - 15))
            fail("oversized transport message")
        } catch (_: NoiseException) {
        }
        val ct = resp.transport.send.encrypt("ok".toByteArray())
        ct[0] = (ct[0].toInt() xor 1).toByte()
        try {
            init.transport.receive.decrypt(ct)
            fail("tampered message must not decrypt")
        } catch (_: NoiseException) {
        }
        // A failed decrypt does not consume the nonce: the genuine next message still decrypts.
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertArrayEquals("ok".toByteArray(), init.transport.receive.decrypt(ct))
        assertFalse(init.handshakeHash.isEmpty())
    }
}
