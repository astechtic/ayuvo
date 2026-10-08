package com.ayuvo.health.partner.identity

import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.noise.NoiseCrypto
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.Locale
import java.util.UUID

/** Where the identity secrets live; [KeyStore] (EncryptedSharedPreferences on the Android Keystore) in the app. */
interface PartnerSecretStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
}

class KeyStorePartnerSecrets(private val keyStore: KeyStore) : PartnerSecretStore {
    override fun load(key: String): String? = keyStore.load(key)
    override fun save(key: String, value: String) = keyStore.save(key, value)
}

/**
 * This installation's partner identity (docs/partner-sync.md §3), created lazily on first use of Partner:
 * a lowercase UUID `device_id`, an X25519 static key pair (Noise) and an Ed25519 signing key pair (packages).
 * The keys never leave the encrypted store and are never exported: a restored or new phone is a new partner
 * device. If the encrypted store had to reset itself (KeyStore.openOrRecover), a fresh identity is created and
 * partners must pair again.
 */
class DeviceIdentity(private val secrets: PartnerSecretStore) {

    private class Material(val deviceId: String, val x25519: NoiseCrypto.KeyPair, val ed25519Seed: ByteArray, val ed25519Public: ByteArray)

    @Volatile private var material: Material? = null

    private fun material(): Material {
        material?.let { return it }
        synchronized(this) {
            material?.let { return it }
            return (loadExisting() ?: create()).also { material = it }
        }
    }

    private fun loadExisting(): Material? {
        val id = secrets.load(KEY_DEVICE_ID) ?: return null
        val x = PartnerKdf.b64urlDecode(secrets.load(KEY_X25519_PRIVATE)) ?: return null
        val seed = PartnerKdf.b64urlDecode(secrets.load(KEY_ED25519_SEED)) ?: return null
        if (x.size != 32 || seed.size != 32) return null
        return runCatching {
            val ed = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
            Material(id, NoiseCrypto.keyPairFromPrivate(x), seed, ed.publicKey)
        }.getOrNull()
    }

    private fun create(): Material {
        val id = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val x = NoiseCrypto.generateKeyPair()
        val ed = Ed25519Sign.KeyPair.newKeyPair()
        secrets.save(KEY_X25519_PRIVATE, PartnerKdf.b64urlEncode(x.privateKey))
        secrets.save(KEY_ED25519_SEED, PartnerKdf.b64urlEncode(ed.privateKey))
        // The id goes last: an interrupted creation leaves no id, so the next use starts over cleanly.
        secrets.save(KEY_DEVICE_ID, id)
        return Material(id, x, ed.privateKey, ed.publicKey)
    }

    val deviceId: String get() = material().deviceId

    /** X25519 static key pair for Noise. */
    val noiseStatic: NoiseCrypto.KeyPair get() = material().x25519.let { NoiseCrypto.KeyPair(it.privateKey.copyOf(), it.publicKey.copyOf()) }

    val x25519Public: String get() = PartnerKdf.b64urlEncode(material().x25519.publicKey)
    val ed25519Public: String get() = PartnerKdf.b64urlEncode(material().ed25519Public)

    /** Hex fingerprint (32 chars) of my keys. */
    val fingerprint: String get() = PartnerKdf.fingerprint(x25519Public, ed25519Public)

    /** Ed25519 signature over [message] with my signing key. */
    fun sign(message: ByteArray): ByteArray = Ed25519Sign(material().ed25519Seed).sign(message)

    companion object {
        const val KEY_DEVICE_ID = "partner_device_id_v1"
        const val KEY_X25519_PRIVATE = "partner_x25519_private_v1"
        const val KEY_ED25519_SEED = "partner_ed25519_seed_v1"

        /** Verifies an Ed25519 signature with a base64url public key; false on any malformed input. */
        fun verify(ed25519PublicB64: String, message: ByteArray, signature: ByteArray): Boolean {
            val key = PartnerKdf.b64urlDecode(ed25519PublicB64) ?: return false
            if (key.size != 32 || signature.size != 64) return false
            return try {
                Ed25519Verify(key).verify(signature, message)
                true
            } catch (_: GeneralSecurityException) {
                false
            }
        }
    }
}
