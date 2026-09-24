package com.ayuvo.health.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.SpeechProvider

/**
 * Encrypted per-provider API key storage — the Android equivalent of iOS Keychain.
 * Backed by EncryptedSharedPreferences (AES-256).
 */
class KeyStore(context: Context) {
    private val prefs: SharedPreferences = openOrRecover(context)

    fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun load(key: String): String? = prefs.getString(key, null)

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    // AI providers
    fun apiKey(provider: AIProvider): String? = load(AI_PREFIX + provider.name)
    fun setApiKey(provider: AIProvider, key: String?) {
        val storageKey = AI_PREFIX + provider.name
        if (key.isNullOrEmpty()) delete(storageKey) else save(storageKey, key)
    }

    /**
     * Per-profile keys (docs/ai-models.md 4). A profile may own a key; when it does not, or when
     * this store wiped itself after an AEADBadTagException, lookup falls back to the provider key.
     * Deleting a profile removes only its own entry -- never `apikey_<provider>`, which onboarding,
     * the per-provider screen and [speechApiKey] all still depend on.
     */
    fun profileApiKey(profileId: String): String? = load(PROFILE_PREFIX + profileId)

    fun setProfileApiKey(profileId: String, key: String?) {
        val storageKey = PROFILE_PREFIX + profileId
        if (key.isNullOrEmpty()) delete(storageKey) else save(storageKey, key)
    }

    fun profileIdsWithOwnKey(ids: Collection<String>): List<String> =
        ids.filter { !profileApiKey(it).isNullOrEmpty() }

    fun providersWithKeys(): List<AIProvider> =
        AIProvider.entries.filter { !apiKey(it).isNullOrEmpty() }

    // Speech providers
    fun speechApiKey(provider: SpeechProvider): String? {
        val dedicated = load(STT_PREFIX + provider.name)
        if (!dedicated.isNullOrEmpty()) return dedicated
        return provider.matchingAIProvider?.let(::apiKey)
    }
    fun setSpeechApiKey(provider: SpeechProvider, key: String?) {
        val storageKey = STT_PREFIX + provider.name
        if (key.isNullOrEmpty()) delete(storageKey) else save(storageKey, key)
    }

    /**
     * The optional Hugging Face token, sent only for catalogue entries marked gated
     * (docs/ai-models.md 7). MedGemma is `gated: auto` on the Hub; without a token its row is
     * listed and blocked rather than started and failed a third of the way through a 3 GB download.
     */
    fun huggingFaceToken(): String? = load(HUGGING_FACE_TOKEN)

    fun setHuggingFaceToken(token: String?) {
        if (token.isNullOrBlank()) delete(HUGGING_FACE_TOKEN) else save(HUGGING_FACE_TOKEN, token.trim())
    }

    fun cloudBackupAccessToken(): String? = load(CLOUD_BACKUP_ACCESS_TOKEN)

    fun setCloudBackupAccessToken(token: String?) {
        if (token.isNullOrBlank()) delete(CLOUD_BACKUP_ACCESS_TOKEN) else save(CLOUD_BACKUP_ACCESS_TOKEN, token)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "AyuvoKeyStore"
        private const val FILE_NAME = "ayuvo_keychain"
        private const val AI_PREFIX = "apikey_"
        private const val STT_PREFIX = "speechApiKey_"
        private const val PROFILE_PREFIX = "aiprofilekey_"
        private const val CLOUD_BACKUP_ACCESS_TOKEN = "cloud_backup_access_token_v1"
        private const val HUGGING_FACE_TOKEN = "huggingFaceToken"

        /**
         * Open EncryptedSharedPreferences. On Android 14/15 (and occasionally
         * older), the AndroidKeystore master-key alias survives `pm uninstall`
         * but the encrypted prefs file does not — so a reinstall (debug build,
         * Play Store update from a deleted install, etc.) hits an
         * `AEADBadTagException` on the first read because the surviving alias
         * can't decrypt a freshly-generated keyset header.
         *
         * Recovery path: catch the failure, wipe both the prefs file AND the
         * AndroidKeystore alias, then rebuild. The user only loses cached API
         * keys, which they'd re-enter on first run anyway. Without this, the
         * app crashes on Application.onCreate before showing any UI.
         */
        private fun openOrRecover(context: Context): SharedPreferences {
            return try {
                build(context)
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences open failed; recovering", e)
                runCatching { context.deleteSharedPreferences(FILE_NAME) }
                runCatching {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
                build(context)
            }
        }

        private fun build(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
