package com.ayuvo.health.partner.noise

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Any handshake or transport failure: bad MAC, wrong key or psk, oversized or truncated message, misuse. */
class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The Noise Protocol Framework (revision 34) primitives for `25519_ChaChaPoly_SHA256`
 * (docs/partner-sync.md §5). X25519 and ChaCha20-Poly1305 come from Tink (JCA lacks them below API 33);
 * SHA-256 and HMAC from JCA. No custom cryptography: only the framework's own constructions.
 */
object NoiseCrypto {
    const val DHLEN = 32
    const val HASHLEN = 32
    const val TAGLEN = 16
    const val KEYLEN = 32
    const val MAX_MESSAGE = 65535

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(): KeyPair = keyPairFromPrivate(X25519.generatePrivateKey())

    fun keyPairFromPrivate(privateKey: ByteArray): KeyPair {
        require(privateKey.size == DHLEN) { "X25519 private key must be 32 bytes" }
        return KeyPair(privateKey.copyOf(), X25519.publicFromPrivate(privateKey))
    }

    fun dh(keyPair: KeyPair, publicKey: ByteArray): ByteArray = try {
        X25519.computeSharedSecret(keyPair.privateKey, publicKey)
    } catch (e: GeneralSecurityException) {
        throw NoiseException("invalid DH public key", e)
    }

    /** Noise nonce: 4 zero bytes then the 64-bit counter little-endian. */
    fun nonce(n: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[4 + i] = (n ushr (8 * i)).toByte()
        return out
    }

    fun encrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray = try {
        InsecureNonceChaCha20Poly1305(k).encrypt(nonce(n), plaintext, ad)
    } catch (e: GeneralSecurityException) {
        throw NoiseException("encrypt failed", e)
    }

    fun decrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray = try {
        InsecureNonceChaCha20Poly1305(k).decrypt(nonce(n), ciphertext, ad)
    } catch (e: GeneralSecurityException) {
        throw NoiseException("decrypt failed", e)
    }

    fun hash(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(64) else key, "HmacSHA256"))
        for (d in data) mac.update(d)
        return mac.doFinal()
    }

    /** Noise HKDF(chaining_key, input_key_material, num_outputs). */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        require(outputs == 2 || outputs == 3)
        val temp = hmac(chainingKey, ikm)
        val o1 = hmac(temp, byteArrayOf(0x01))
        val o2 = hmac(temp, o1, byteArrayOf(0x02))
        if (outputs == 2) return listOf(o1, o2)
        val o3 = hmac(temp, o2, byteArrayOf(0x03))
        return listOf(o1, o2, o3)
    }
}

/** Noise CipherState: key k (absent before the first MixKey) and 64-bit nonce n. */
class CipherState internal constructor() {
    private var k: ByteArray? = null

    /** Unsigned 64-bit counter; 2^64-1 is reserved and never used. */
    private var n: Long = 0

    fun initializeKey(key: ByteArray?) {
        k = key?.copyOf()
        n = 0
    }

    fun hasKey(): Boolean = k != null

    fun setNonce(nonce: Long) {
        n = nonce
    }

    private fun checkNonce() {
        if (n == -1L) throw NoiseException("nonce exhausted")
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext
        checkNonce()
        val out = NoiseCrypto.encrypt(key, n, ad, plaintext)
        n++
        return out
    }

    /** On failure n is not incremented, as the spec requires. */
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext
        checkNonce()
        val out = NoiseCrypto.decrypt(key, n, ad, ciphertext)
        n++
        return out
    }

    /** REKEY(k) = first 32 bytes of ENCRYPT(k, 2^64-1, zerolen, zeros). */
    fun rekey() {
        val key = k ?: return
        k = NoiseCrypto.encrypt(key, -1L, ByteArray(0), ByteArray(32)).copyOf(32)
    }

    // -- transport helpers ----------------------------------------------------------------------

    /** Encrypts one transport message (payload + 16-byte tag must fit in 65535 bytes). */
    fun encrypt(plaintext: ByteArray): ByteArray {
        if (plaintext.size + NoiseCrypto.TAGLEN > NoiseCrypto.MAX_MESSAGE) throw NoiseException("message too large")
        return encryptWithAd(ByteArray(0), plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size > NoiseCrypto.MAX_MESSAGE) throw NoiseException("message too large")
        if (ciphertext.size < NoiseCrypto.TAGLEN) throw NoiseException("message too short")
        return decryptWithAd(ByteArray(0), ciphertext)
    }
}

/** Noise SymmetricState. */
class SymmetricState internal constructor(protocolName: String) {
    private val cipher = CipherState()
    private var ck: ByteArray
    private var h: ByteArray

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= NoiseCrypto.HASHLEN) name.copyOf(NoiseCrypto.HASHLEN) else NoiseCrypto.hash(name)
        ck = h.copyOf()
        cipher.initializeKey(null)
    }

    val handshakeHash: ByteArray get() = h.copyOf()

    fun hasKey(): Boolean = cipher.hasKey()

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = NoiseCrypto.hkdf(ck, ikm, 2)
        ck = newCk
        cipher.initializeKey(tempK.copyOf(NoiseCrypto.KEYLEN))
    }

    fun mixHash(data: ByteArray) {
        h = NoiseCrypto.hash(h, data)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, tempH, tempK) = NoiseCrypto.hkdf(ck, ikm, 3)
        ck = newCk
        mixHash(tempH)
        cipher.initializeKey(tempK.copyOf(NoiseCrypto.KEYLEN))
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = NoiseCrypto.hkdf(ck, ByteArray(0), 2)
        val c1 = CipherState().apply { initializeKey(k1.copyOf(NoiseCrypto.KEYLEN)) }
        val c2 = CipherState().apply { initializeKey(k2.copyOf(NoiseCrypto.KEYLEN)) }
        return c1 to c2
    }
}
