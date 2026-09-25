package com.ayuvo.health.records.ai

import com.ayuvo.health.data.KeyStore
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.services.ondevice.LocalModelId
import com.ayuvo.health.services.ondevice.LocalModelManager
import kotlinx.coroutines.flow.first

/**
 * §16 resolution of the Health Records AI mode. Local = Gemma 4 E2B installed on an eligible
 * (RAM/ABI-gated) device; cloud = the configured BYOK route with a key (an on-device primary
 * counts as local, never as cloud).
 */
class RecordsAiModeResolver(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    private val localModels: LocalModelManager
) {
    /** Mirrors FoodAnalysisService.recordsCloudProvider, so options match what a real call would use. */
    suspend fun options(textOnly: Boolean = true): RecordsAiOptions = RecordsAiOptions(
        localAvailable = localModels.isAnyChatModelExecutable(),
        cloudProvider = cloudProvider(textOnly)
    )

    private suspend fun cloudProvider(textOnly: Boolean): AIProvider? {
        val separate = textOnly && prefs.separateTextProviderEnabled.first()
        val provider = if (separate) prefs.selectedTextAIProvider.first() else prefs.selectedAIProvider.first()
        if (provider == AIProvider.LOCAL_GEMMA) return null
        if (provider.requiresApiKey && keyStore.apiKey(provider).isNullOrEmpty()) return null
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        return provider.takeIf { baseUrl.isNotEmpty() }
    }

    suspend fun preference(): RecordsAiMode? = RecordsAiMode.fromRaw(prefs.healthRecordsAiMode.first())

    suspend fun resolve(preference: RecordsAiMode?, requestedMode: String?, textOnly: Boolean = true): AiResolution =
        resolve(preference, requestedMode, options(textOnly))

    companion object {
        /**
         * Pure core. [requestedMode] is the per-record Ask decision (`local` | `cloud` | `off`) stored
         * in `processing_jobs.requested_mode`; it only applies while the preference is ask/unset.
         */
        fun resolve(preference: RecordsAiMode?, requestedMode: String?, options: RecordsAiOptions): AiResolution {
            val effective = when (preference) {
                null, RecordsAiMode.ASK -> when (requestedMode) {
                    "local" -> RecordsAiMode.LOCAL
                    "cloud" -> RecordsAiMode.CLOUD
                    "off" -> RecordsAiMode.OFF
                    else -> return AiResolution.Ask
                }
                else -> preference
            }
            return when (effective) {
                RecordsAiMode.OFF -> AiResolution.Off
                RecordsAiMode.LOCAL -> if (options.localAvailable) {
                    AiResolution.Run(RecordsAiTarget.LOCAL, AIProvider.LOCAL_GEMMA)
                } else {
                    AiResolution.Unavailable(UnavailableReason.LOCAL_NOT_SET_UP)
                }
                RecordsAiMode.CLOUD -> options.cloudProvider?.let { AiResolution.Run(RecordsAiTarget.CLOUD, it) }
                    ?: AiResolution.Unavailable(UnavailableReason.NO_CLOUD_PROVIDER)
                RecordsAiMode.ASK -> AiResolution.Ask
            }
        }
    }
}
