package com.ayuvo.health.records.ai

import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.services.ai.AiError
import com.ayuvo.health.services.ai.AiErrorKind
import com.ayuvo.health.services.ai.FoodAnalysisService
import kotlinx.coroutines.CancellationException
import com.ayuvo.health.records.processing.AiChunker
import kotlinx.coroutines.delay

/**
 * §9.3 AI stage transport: chunks page text by page (≤ 12,000 characters per cloud call, ≤ 2,500
 * per local call), sends pages without usable text as images (cloud vision, or local Gemma one page
 * per call), caps output at 3,000 tokens per call, and returns the raw responses for the validator.
 */
class RecordsAiExtractor(
    private val call: suspend (prompt: String, images: List<ByteArray>, maxOutputTokens: Int, target: RecordsAiTarget) -> RecordsAiReply,
    private val localBusy: () -> Boolean = { false },
    private val busyWaitMs: Long = BUSY_WAIT_MS,
    private val busyPollMs: Long = BUSY_POLL_MS
) {
    constructor(foodAnalysis: FoodAnalysisService, localBusy: () -> Boolean = { false }) :
        this(call = foodAnalysis::callRecordsAi, localBusy = localBusy)

    /**
     * [AiExtractionRequest.pages] indexes are positions in the record's (or segment's) page list;
     * the prompt numbers them 1-based (`=== Page N ===`, shared `ai_chunks`).
     */
    suspend fun extract(request: AiExtractionRequest, target: RecordsAiTarget): AiExtractionReply {
        val local = target == RecordsAiTarget.LOCAL
        val size = maxOf(request.pages.maxOfOrNull { it.index } ?: -1, request.imagePages.maxOfOrNull { it.index } ?: -1) + 1
        val byIndex = request.pages.associate { it.index to it.text }
        val padded = (0 until size).map { byIndex[it].orEmpty() }
        val calls = mutableListOf<AiCallResult>()
        var provider: AIProvider? = null
        for (chunk in AiChunker.chunks(padded, if (local) "local" else "cloud")) {
            val prompt = RecordsAiPrompt.render(local, request.recordType, chunk.text)
            val reply = invoke(prompt, emptyList(), target, maxOutputTokens = outputTokens(local, prompt))
            provider = reply.provider
            calls += AiCallResult(chunk.pages.map { it - 1 }, fromImage = false, text = reply.text)
        }
        for (image in request.imagePages) {
            val prompt = RecordsAiPrompt.renderImage(local, request.recordType, image.index + 1)
            val reply = invoke(prompt, listOf(image.jpeg), target, maxOutputTokens = outputTokens(local, prompt))
            provider = reply.provider
            calls += AiCallResult(listOf(image.index), fromImage = true, text = reply.text)
        }
        return AiExtractionReply(calls, provider ?: if (local) AIProvider.LOCAL_GEMMA else AIProvider.GEMINI)
    }

    /** Plain call for the query rewriter (text only). */
    suspend fun rewriteQuery(query: String, target: RecordsAiTarget): RecordsAiReply =
        invoke(RecordsAiPrompt.queryRewrite(query), emptyList(), target, maxOutputTokens = QUERY_MAX_OUTPUT_TOKENS)

    private suspend fun invoke(prompt: String, images: List<ByteArray>, target: RecordsAiTarget, maxOutputTokens: Int = MAX_OUTPUT_TOKENS): RecordsAiReply {
        if (target == RecordsAiTarget.LOCAL) {
            // Coach (or another stage) may hold Gemma; wait a bounded time instead of queueing blindly.
            var waited = 0L
            while (localBusy()) {
                if (waited >= busyWaitMs) throw RecordsAiException(RecordsAiException.Kind.BUSY)
                delay(busyPollMs)
                waited += busyPollMs
            }
        }
        return try {
            call(prompt, images, maxOutputTokens, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RecordsAiException) {
            throw e
        } catch (e: Throwable) {
            throw RecordsAiException(classify(e), e)
        }
    }

    companion object {
        const val CLOUD_CHUNK_CHARS = 12_000
        const val LOCAL_CHUNK_CHARS = 2_500
        const val MAX_OUTPUT_TOKENS = 3_000
        const val QUERY_MAX_OUTPUT_TOKENS = 600
        const val BUSY_WAIT_MS = 120_000L
        const val BUSY_POLL_MS = 2_000L

        /**
         * Cloud: 3,000. Local Gemma: min(3,000, 4,096 − prompt tokens − 64), prompt tokens estimated at
         * one token per 3 characters (no tokenizer is exposed by the runtime).
         */
        fun outputTokens(local: Boolean, prompt: String): Int =
            if (!local) MAX_OUTPUT_TOKENS else minOf(MAX_OUTPUT_TOKENS, 4_096 - (prompt.length + 2) / 3 - 64).coerceAtLeast(256)

        fun classify(error: Throwable): RecordsAiException.Kind {
            val kind = (error as? AiError)?.kind
            return when {
                kind == AiErrorKind.OFFLINE || kind == AiErrorKind.CONNECTION || kind == AiErrorKind.TIMEOUT -> RecordsAiException.Kind.OFFLINE
                kind == AiErrorKind.NO_KEY || kind == AiErrorKind.LOCAL_UNAVAILABLE || kind == AiErrorKind.KEY_REJECTED -> RecordsAiException.Kind.UNAVAILABLE
                error is java.io.IOException -> RecordsAiException.Kind.OFFLINE
                error is IllegalStateException && error.message?.contains("not installed") == true -> RecordsAiException.Kind.UNAVAILABLE
                else -> RecordsAiException.Kind.FAILED
            }
        }

    }
}
