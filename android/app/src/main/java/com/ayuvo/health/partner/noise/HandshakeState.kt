package com.ayuvo.health.partner.noise

/** The two handshake patterns Ayuvo uses (docs/partner-sync.md §5). */
enum class NoisePattern(
    val protocolName: String,
    /** Pre-message tokens: initiator's then responder's (only "s" occurs). */
    val initiatorPre: List<String>,
    val responderPre: List<String>,
    /** Message patterns, alternating initiator -> responder, responder -> initiator. */
    val messages: List<List<String>>
) {
    /** Pairing: `<- s` / `-> e, es, s, ss` / `<- e, ee, se, psk`. */
    IK_PSK2(
        "Noise_IKpsk2_25519_ChaChaPoly_SHA256",
        emptyList(), listOf("s"),
        listOf(listOf("e", "es", "s", "ss"), listOf("e", "ee", "se", "psk"))
    ),

    /** Trusted sessions: `-> s` / `<- s` / `-> e, es, ss` / `<- e, ee, se`. */
    KK(
        "Noise_KK_25519_ChaChaPoly_SHA256",
        listOf("s"), listOf("s"),
        listOf(listOf("e", "es", "ss"), listOf("e", "ee", "se"))
    );

    val usesPsk: Boolean get() = messages.any { "psk" in it }
    val pskCount: Int get() = messages.sumOf { m -> m.count { it == "psk" } }

    companion object {
        fun byName(name: String): NoisePattern = entries.firstOrNull { it.protocolName == name }
            ?: throw IllegalArgumentException("unsupported Noise protocol $name")
    }
}

/** Both transport directions after the handshake. */
class NoiseTransport(val send: CipherState, val receive: CipherState, val handshakeHash: ByteArray)

/**
 * Noise HandshakeState (spec rev 34 §5.3 and §9 for psk). Single use: call [writeMessage] / [readMessage]
 * alternately as the pattern dictates until [isComplete], then take [transport].
 *
 * [ephemeral] is injectable only so the cacophony vectors can be replayed; production passes null and a fresh
 * X25519 key is generated per handshake.
 */
class HandshakeState(
    val pattern: NoisePattern,
    val initiator: Boolean,
    prologue: ByteArray,
    private val s: NoiseCrypto.KeyPair?,
    private var e: NoiseCrypto.KeyPair? = null,
    rs: ByteArray? = null,
    private val psks: List<ByteArray> = emptyList()
) {
    private val ss = SymmetricState(pattern.protocolName)
    private var rs: ByteArray? = rs?.copyOf()
    private var re: ByteArray? = null
    private var index = 0
    private var pskIndex = 0
    private var result: NoiseTransport? = null
    private var failed = false

    init {
        require(psks.size == pattern.pskCount) { "${pattern.protocolName} needs ${pattern.pskCount} psk(s)" }
        require(psks.all { it.size == 32 }) { "psk must be 32 bytes" }
        ss.mixHash(prologue)
        val iPre = pattern.initiatorPre
        val rPre = pattern.responderPre
        if ("s" in iPre) ss.mixHash(if (initiator) requireNotNull(s).publicKey else requireNotNull(this.rs) { "remote static required" })
        if ("s" in rPre) ss.mixHash(if (initiator) requireNotNull(this.rs) { "remote static required" } else requireNotNull(s).publicKey)
    }

    val isComplete: Boolean get() = result != null

    /** The peer's static public key (known after it was received, or given up front). */
    val remoteStatic: ByteArray? get() = rs?.copyOf()

    val handshakeHash: ByteArray get() = ss.handshakeHash

    val transport: NoiseTransport get() = result ?: throw NoiseException("handshake not complete")

    private fun myTurnToWrite(): Boolean = (index % 2 == 0) == initiator

    private fun checkUsable(write: Boolean) {
        if (failed) throw NoiseException("handshake already failed")
        if (result != null || index >= pattern.messages.size) throw NoiseException("handshake already complete")
        if (myTurnToWrite() != write) throw NoiseException(if (write) "not my turn to write" else "not my turn to read")
    }

    private fun dhToken(token: String): ByteArray {
        val local: NoiseCrypto.KeyPair
        val remote: ByteArray
        when (token) {
            "ee" -> { local = e!!; remote = re!! }
            "ss" -> { local = s!!; remote = rs!! }
            // es: initiator's e with responder's s; se: initiator's s with responder's e.
            "es" -> if (initiator) { local = e!!; remote = rs!! } else { local = s!!; remote = re!! }
            "se" -> if (initiator) { local = s!!; remote = re!! } else { local = e!!; remote = rs!! }
            else -> throw IllegalStateException(token)
        }
        return NoiseCrypto.dh(local, remote)
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        checkUsable(write = true)
        try {
            val out = java.io.ByteArrayOutputStream()
            for (token in pattern.messages[index]) {
                when (token) {
                    "e" -> {
                        val eph = e ?: NoiseCrypto.generateKeyPair().also { e = it }
                        out.write(eph.publicKey)
                        ss.mixHash(eph.publicKey)
                        if (pattern.usesPsk) ss.mixKey(eph.publicKey)
                    }
                    "s" -> out.write(ss.encryptAndHash(requireNotNull(s) { "local static required" }.publicKey))
                    "psk" -> ss.mixKeyAndHash(psks[pskIndex++])
                    else -> ss.mixKey(dhToken(token))
                }
            }
            out.write(ss.encryptAndHash(payload))
            val msg = out.toByteArray()
            if (msg.size > NoiseCrypto.MAX_MESSAGE) throw NoiseException("handshake message too large")
            advance()
            return msg
        } catch (t: Throwable) {
            failed = true
            throw if (t is NoiseException) t else NoiseException("handshake write failed", t)
        }
    }

    fun readMessage(message: ByteArray): ByteArray {
        checkUsable(write = false)
        try {
            if (message.size > NoiseCrypto.MAX_MESSAGE) throw NoiseException("handshake message too large")
            var pos = 0
            fun take(n: Int): ByteArray {
                if (pos + n > message.size) throw NoiseException("handshake message truncated")
                return message.copyOfRange(pos, pos + n).also { pos += n }
            }
            for (token in pattern.messages[index]) {
                when (token) {
                    "e" -> {
                        if (re != null) throw NoiseException("remote ephemeral already set")
                        val pub = take(NoiseCrypto.DHLEN)
                        re = pub
                        ss.mixHash(pub)
                        if (pattern.usesPsk) ss.mixKey(pub)
                    }
                    "s" -> {
                        if (rs != null) throw NoiseException("remote static already set")
                        val len = NoiseCrypto.DHLEN + if (ss.hasKey()) NoiseCrypto.TAGLEN else 0
                        rs = ss.decryptAndHash(take(len))
                    }
                    "psk" -> ss.mixKeyAndHash(psks[pskIndex++])
                    else -> ss.mixKey(dhToken(token))
                }
            }
            val payload = ss.decryptAndHash(message.copyOfRange(pos, message.size))
            advance()
            return payload
        } catch (t: Throwable) {
            failed = true
            throw if (t is NoiseException) t else NoiseException("handshake read failed", t)
        }
    }

    private fun advance() {
        index++
        if (index == pattern.messages.size) {
            val (c1, c2) = ss.split()
            // c1 carries initiator -> responder traffic, c2 the other direction.
            result = if (initiator) NoiseTransport(c1, c2, ss.handshakeHash) else NoiseTransport(c2, c1, ss.handshakeHash)
        }
    }
}
