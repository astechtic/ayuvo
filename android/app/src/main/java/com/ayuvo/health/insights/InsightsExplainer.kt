package com.ayuvo.health.insights

import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.services.ai.AIRoleResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * The on-tap "Explain with AI" step (docs/insights.md §5). The text role is resolved like the other
 * AI features and the request runs on exactly that route: no silent fallback, and never from the
 * device to a cloud provider. The numbers on screen always come from the engines; an answer that
 * fails [InsightsAi.validate] is dropped and the deterministic text stays.
 */
class InsightsExplainer(
    private val route: suspend () -> AIRoleResolver.Route?,
    private val call: suspend (AIRoleResolver.Route, String, Int) -> String,
    private val config: () -> InsightsConfig,
    private val prompts: () -> InsightsAi.Prompts,
    private val providerName: (AIProvider) -> String
) {
    sealed interface Availability {
        /** No usable text model: the button shows "Set up AI in Settings". */
        data object NotConfigured : Availability
        data class Ready(val onDevice: Boolean, val providerName: String) : Availability
    }

    sealed interface Result {
        data class Explained(val output: InsightsAi.Output, val statusLine: String) : Result
        /** The model answered but failed validation; the deterministic text is shown instead. */
        data class Rejected(val errors: List<String>) : Result
        data class Failed(val message: String?) : Result
        data object NotConfigured : Result
    }

    suspend fun availability(): Availability {
        val r = runCatching { route() }.getOrNull()
        if (r == null || !r.isUsable) return Availability.NotConfigured
        return Availability.Ready(r.provider == AIProvider.LOCAL_GEMMA, providerName(r.provider))
    }

    /** [kind] is `recovery`, `health_age` or `daily_review`; [summary] is [InsightsSnapshot.summaryJson]. */
    suspend fun explain(kind: String, summary: JsonObject): Result {
        val r = runCatching { route() }.getOrNull()
        if (r == null || !r.isUsable) return Result.NotConfigured
        val cfg = config()
        val local = r.provider == AIProvider.LOCAL_GEMMA
        val payload = InsightsAi.payload(summary)
        val prompt = InsightsAi.buildPrompt(kind, payload, if (local) "local" else "cloud", cfg, prompts())
        val tokens = if (local) cfg.ai.maxOutputTokensLocal else cfg.ai.maxOutputTokensCloud
        val text = try {
            call(r, prompt.system + "\n\n" + prompt.user, tokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.Failed(e.message)
        }
        val v = InsightsAi.validate(text, payload, cfg)
        val output = v.output ?: return Result.Rejected(v.errors)
        val status = if (local) cfg.ai.statusLocal else cfg.ai.statusCloud.replace("{provider}", providerName(r.provider))
        return Result.Explained(output, status)
    }
}
