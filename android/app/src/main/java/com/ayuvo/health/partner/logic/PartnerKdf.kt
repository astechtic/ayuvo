package com.ayuvo.health.partner.logic

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** docs/partner-sync.md §3–§4: encodings and key derivations (reference `b64url_*`, `hkdf_sha256`, ...). */
object PartnerKdf {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** base64url without padding. */
    fun b64urlEncode(raw: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    /**
     * Strict alphabet `[A-Za-z0-9_-]`, no padding, length % 4 != 1; returns null otherwise. Like the reference
     * (Python's non-validating decoder) unused trailing bits are ignored.
     */
    fun b64urlDecode(text: String?): ByteArray? {
        if (text.isNullOrEmpty() || text.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is legal (RFC 2104) but SecretKeySpec rejects it; a block of zeros is equivalent.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(64) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** RFC 5869. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** 32-byte Noise psk from the QR token (base64url). */
    fun pairingPsk(tokenB64: String): ByteArray {
        val token = b64urlDecode(tokenB64) ?: throw IllegalArgumentException("token is not base64url")
        val c = PartnerCatalog.current
        return hkdfSha256(token, c.pskSalt.toByteArray(Charsets.UTF_8), c.pskInfo.toByteArray(Charsets.UTF_8), 32)
    }

    /** The 6-digit short authentication string both screens show after the pairing handshake. */
    fun sasCode(handshakeHash: ByteArray): String {
        val d = sha256(PartnerCatalog.current.sasLabel.toByteArray(Charsets.UTF_8), handshakeHash)
        val n = ((d[0].toLong() and 0xFF) shl 24 or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)) % 1_000_000L
        return n.toString().padStart(6, '0')
    }

    /** Hex of the first 16 bytes of SHA-256(x25519_pub || ed25519_pub). */
    fun fingerprint(x25519B64: String, ed25519B64: String): String {
        val x = b64urlDecode(x25519B64) ?: throw IllegalArgumentException("x25519 key is not base64url")
        val e = b64urlDecode(ed25519B64) ?: throw IllegalArgumentException("ed25519 key is not base64url")
        return hex(sha256(x, e).copyOf(PartnerCatalog.current.fingerprintBytes))
    }

    /** Display form: groups of 4 upper-case hex digits. */
    fun formatFingerprint(fpHex: String): String = fpHex.uppercase(java.util.Locale.ROOT).chunked(4).joinToString(" ")

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
