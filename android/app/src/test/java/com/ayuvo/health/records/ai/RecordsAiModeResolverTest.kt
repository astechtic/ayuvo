package com.ayuvo.health.records.ai

import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.records.model.RecordsAiMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** §16 resolution matrix. */
class RecordsAiModeResolverTest {
    private val both = RecordsAiOptions(localAvailable = true, cloudProvider = AIProvider.GEMINI)
    private val none = RecordsAiOptions(localAvailable = false, cloudProvider = null)

    private fun r(pref: RecordsAiMode?, requested: String?, options: RecordsAiOptions) =
        RecordsAiModeResolver.resolve(pref, requested, options)

    @Test
    fun localRunsOnlyWhenGemmaIsInstalled() {
        assertEquals(AiResolution.Run(RecordsAiTarget.LOCAL, AIProvider.LOCAL_GEMMA), r(RecordsAiMode.LOCAL, null, both))
        assertEquals(AiResolution.Unavailable(UnavailableReason.LOCAL_NOT_SET_UP), r(RecordsAiMode.LOCAL, null, none))
        // A cloud key never turns local mode into a cloud call.
        assertEquals(
            AiResolution.Unavailable(UnavailableReason.LOCAL_NOT_SET_UP),
            r(RecordsAiMode.LOCAL, null, RecordsAiOptions(false, AIProvider.OPENAI))
        )
    }

    @Test
    fun cloudNeedsAConfiguredProvider() {
        assertEquals(AiResolution.Run(RecordsAiTarget.CLOUD, AIProvider.GEMINI), r(RecordsAiMode.CLOUD, null, both))
        assertEquals(AiResolution.Unavailable(UnavailableReason.NO_CLOUD_PROVIDER), r(RecordsAiMode.CLOUD, null, none))
        assertEquals(
            AiResolution.Unavailable(UnavailableReason.NO_CLOUD_PROVIDER),
            r(RecordsAiMode.CLOUD, null, RecordsAiOptions(true, null))
        )
    }

    @Test
    fun askWaitsUntilTheRecordHasADecision() {
        assertEquals(AiResolution.Ask, r(RecordsAiMode.ASK, null, both))
        assertEquals(AiResolution.Run(RecordsAiTarget.LOCAL, AIProvider.LOCAL_GEMMA), r(RecordsAiMode.ASK, "local", both))
        assertEquals(AiResolution.Run(RecordsAiTarget.CLOUD, AIProvider.GEMINI), r(RecordsAiMode.ASK, "cloud", both))
        assertEquals(AiResolution.Off, r(RecordsAiMode.ASK, "off", both))
        assertEquals(AiResolution.Unavailable(UnavailableReason.NO_CLOUD_PROVIDER), r(RecordsAiMode.ASK, "cloud", none))
    }

    @Test
    fun unsetBehavesLikeAskAndOffSkips() {
        assertEquals(AiResolution.Ask, r(null, null, both))
        assertEquals(AiResolution.Run(RecordsAiTarget.CLOUD, AIProvider.GEMINI), r(null, "cloud", both))
        assertEquals(AiResolution.Off, r(RecordsAiMode.OFF, null, both))
        // A stale per-record decision does not override an explicit mode.
        assertEquals(AiResolution.Off, r(RecordsAiMode.OFF, "cloud", both))
        assertEquals(AiResolution.Run(RecordsAiTarget.LOCAL, AIProvider.LOCAL_GEMMA), r(RecordsAiMode.LOCAL, "cloud", both))
    }
}
