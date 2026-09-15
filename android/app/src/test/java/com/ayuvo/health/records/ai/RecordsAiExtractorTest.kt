package com.ayuvo.health.records.ai

import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.services.ai.AiError
import com.ayuvo.health.services.ai.AiErrorKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordsAiExtractorTest {
    private data class Sent(val prompt: String, val images: Int, val maxTokens: Int, val target: RecordsAiTarget)

    @Test
    fun chunksByPageWithinTheTargetLimit() = runBlocking {
        val sent = mutableListOf<Sent>()
        val extractor = RecordsAiExtractor(call = { p, i, m, t -> sent += Sent(p, i.size, m, t); RecordsAiReply("{}", AIProvider.GEMINI) })
        val pages = (0 until 5).map { AiPageText(it, "x".repeat(1_000)) }
        val reply = extractor.extract(AiExtractionRequest("lab_report", pages, listOf(AiPageImage(5, byteArrayOf(1)))), RecordsAiTarget.LOCAL)
        // 2,500 code points locally (headers included) → pages [0,1] [2,3] [4], then one image call.
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4), listOf(5)), reply.calls.map { it.pageIndexes })
        assertEquals(listOf(false, false, false, true), reply.calls.map { it.fromImage })
        assertTrue(sent.all { it.maxTokens in 256..3_000 })
        assertEquals(1, sent.last().images)
        assertTrue(sent.first().prompt.contains("=== Page 1 ===") && sent.first().prompt.contains("=== Page 2 ==="))
        assertTrue(sent.first().prompt.startsWith(RecordsAiPrompt.SYSTEM_COMPACT))
        assertTrue(sent.first().prompt.contains("Document type hint: lab_report"))
        assertTrue(sent.last().prompt.contains("=== Page 6 ===\n(image attached)"))

        sent.clear()
        extractor.extract(AiExtractionRequest("other", pages), RecordsAiTarget.CLOUD)
        assertEquals(1, sent.size)
        assertEquals(3_000, sent.single().maxTokens)
        assertTrue(sent.single().prompt.startsWith(RecordsAiPrompt.SYSTEM_FULL))
        assertTrue(sent.single().prompt.contains("Document type hint: unknown"))
    }

    @Test
    fun busyLocalRuntimeTimesOut() = runBlocking {
        val extractor = RecordsAiExtractor(
            call = { _, _, _, _ -> fail("must not call while busy"); RecordsAiReply("", AIProvider.LOCAL_GEMMA) },
            localBusy = { true }, busyWaitMs = 20, busyPollMs = 10
        )
        try {
            extractor.extract(AiExtractionRequest("other", listOf(AiPageText(0, "text"))), RecordsAiTarget.LOCAL)
            fail("expected BUSY")
        } catch (e: RecordsAiException) {
            assertEquals(RecordsAiException.Kind.BUSY, e.kind)
        }
    }

    @Test
    fun errorsMapToQueueKinds() = runBlocking {
        assertEquals(RecordsAiException.Kind.OFFLINE, RecordsAiExtractor.classify(AiError.Failure(AiErrorKind.OFFLINE)))
        assertEquals(RecordsAiException.Kind.OFFLINE, RecordsAiExtractor.classify(java.io.IOException("reset")))
        assertEquals(RecordsAiException.Kind.UNAVAILABLE, RecordsAiExtractor.classify(AiError.NoApiKey))
        assertEquals(RecordsAiException.Kind.FAILED, RecordsAiExtractor.classify(AiError.InvalidResponse))
        val extractor = RecordsAiExtractor(call = { _, _, _, _ -> throw AiError.Failure(AiErrorKind.RATE_LIMIT) })
        try {
            extractor.extract(AiExtractionRequest("other", listOf(AiPageText(0, "text"))), RecordsAiTarget.CLOUD)
            fail("expected failure")
        } catch (e: RecordsAiException) {
            assertEquals(RecordsAiException.Kind.FAILED, e.kind)
        }
    }

    @Test
    fun queryRewriteSendsOnlyTheQuery() {
        val prompt = RecordsAiPrompt.queryRewrite("  reports where hemoglobin was low  ")
        assertTrue(prompt.endsWith("Search text: reports where hemoglobin was low"))
    }
}
