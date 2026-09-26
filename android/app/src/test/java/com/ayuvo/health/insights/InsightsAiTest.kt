package com.ayuvo.health.insights

import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.services.ai.AIRoleResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI step: payload privacy, validator rules and the no-fallback routing (docs/insights.md §5). */
class InsightsAiTest {
    private val cfg = InsightsTestFiles.config
    private val snap = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs(days = 190)), cfg)
    private val payload = InsightsAi.payload(snap.summaryJson())

    private fun keys(e: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> out += k; keys(v, out) }
            is JsonArray -> e.forEach { keys(it, out) }
            else -> Unit
        }
        return out
    }

    private fun strings(e: JsonElement, out: MutableList<String> = ArrayList()): List<String> {
        when (e) {
            is JsonObject -> e.values.forEach { strings(it, out) }
            is JsonArray -> e.forEach { strings(it, out) }
            is JsonPrimitive -> if (e.isString) out += e.content
        }
        return out
    }

    @Test
    fun payloadHoldsDerivedValuesOnly() {
        val k = keys(payload)
        for (forbidden in listOf("day", "as_of", "start_ms", "end_ms", "t_ms", "components", "points", "value", "baseline", "name", "birthday")) {
            assertFalse("payload must not carry $forbidden", forbidden in k)
        }
        val text = strings(payload)
        assertTrue("no dates in payload strings", text.none { Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) })
        // Epoch-millisecond timestamps never appear, not even as numbers.
        assertFalse(InsightsJson.canonical(payload).contains(Regex("\\d{12,}")))
        assertTrue(payload.containsKey("recovery"))
        assertTrue(payload.containsKey("daily_review"))
    }

    @Test
    fun promptUsesThePayloadOnceAndTheRightVariant() {
        val prompts = InsightsTestFiles.prompts
        val cloud = InsightsAi.buildPrompt("recovery", payload, "cloud", cfg, prompts)
        val local = InsightsAi.buildPrompt("recovery", payload, "local", cfg, prompts)
        assertEquals(prompts.cloud, cloud.system)
        assertEquals(prompts.local, local.system)
        assertFalse(cloud.user.contains("{payload}"))
        assertTrue(cloud.user.contains(cfg.ai.tasks.getValue("recovery")))
    }

    @Test
    fun validatorRejectsUnknownNumbersBlockedTermsAndBadShapes() {
        val score = snap.recovery.score!!
        assertTrue(InsightsAi.validate("{\"headline\":\"Recovery is $score today.\",\"bullets\":[\"Sleep was close to usual.\"]}", payload, cfg).ok)
        assertEquals(listOf("unknown_number"), InsightsAi.validate("{\"headline\":\"Recovery is 999.\",\"bullets\":[\"x\"]}", payload, cfg).errors)
        assertEquals(listOf("blocked_term"), InsightsAi.validate("{\"headline\":\"You may have a cold.\",\"bullets\":[\"Rest.\"]}", payload, cfg).errors)
        assertEquals(listOf("bad_shape"), InsightsAi.validate("{\"headline\":\"Hi\",\"bullets\":\"no\"}", payload, cfg).errors)
        assertEquals(listOf("parse_error"), InsightsAi.validate("no json here", payload, cfg).errors)
    }

    private fun route(provider: AIProvider, blocked: String? = null) = AIRoleResolver.Route(
        role = "text", profileId = null, provider = provider, model = "m", baseUrl = "https://x", apiKey = "k",
        vertexProjectId = null, vertexLocation = null, requestTimeoutSeconds = null, maxResponseTokens = 1024,
        tokenLimitKey = "max_tokens", contextTokens = null, blocked = blocked
    )

    private fun explainer(r: AIRoleResolver.Route?, answer: String, calls: MutableList<Pair<AIProvider, Int>>) = InsightsExplainer(
        route = { r },
        call = { rt, _, tokens -> calls += rt.provider to tokens; answer },
        config = { cfg },
        prompts = { InsightsTestFiles.prompts },
        providerName = { it.name }
    )

    @Test
    fun onDeviceRunsLocallyWithTheCompactPromptAndNeverFallsBack() = runBlocking {
        val calls = mutableListOf<Pair<AIProvider, Int>>()
        val ok = "{\"headline\":\"Recovery is ${snap.recovery.score}.\",\"bullets\":[\"Compared with your usual.\"]}"
        val r = explainer(route(AIProvider.LOCAL_GEMMA), ok, calls).explain("recovery", snap.summaryJson())
        assertTrue(r is InsightsExplainer.Result.Explained)
        assertEquals(cfg.ai.statusLocal, (r as InsightsExplainer.Result.Explained).statusLine)
        assertEquals(listOf(AIProvider.LOCAL_GEMMA to cfg.ai.maxOutputTokensLocal), calls)

        // A failing on-device call is reported, not retried on a cloud provider.
        calls.clear()
        val failing = InsightsExplainer(
            route = { route(AIProvider.LOCAL_GEMMA) },
            call = { rt, _, _ -> calls += rt.provider to 0; error("model busy") },
            config = { cfg }, prompts = { InsightsTestFiles.prompts }, providerName = { it.name }
        )
        assertTrue(failing.explain("recovery", snap.summaryJson()) is InsightsExplainer.Result.Failed)
        assertEquals(1, calls.size)
    }

    @Test
    fun cloudStatusNamesTheProviderAndBadAnswersKeepTheDeterministicText() = runBlocking {
        val calls = mutableListOf<Pair<AIProvider, Int>>()
        val ok = "{\"headline\":\"Recovery is ${snap.recovery.score}.\",\"bullets\":[\"Compared with your usual.\"]}"
        val r = explainer(route(AIProvider.GEMINI), ok, calls).explain("recovery", snap.summaryJson()) as InsightsExplainer.Result.Explained
        assertEquals(cfg.ai.statusCloud.replace("{provider}", AIProvider.GEMINI.name), r.statusLine)
        assertEquals(cfg.ai.maxOutputTokensCloud, calls.single().second)
        val bad = explainer(route(AIProvider.GEMINI), "{\"headline\":\"Recovery 12345.\",\"bullets\":[\"x\"]}", calls).explain("recovery", snap.summaryJson())
        assertTrue(bad is InsightsExplainer.Result.Rejected)
    }

    @Test
    fun withoutAConfiguredModelNothingIsSent() = runBlocking {
        val calls = mutableListOf<Pair<AIProvider, Int>>()
        val none = explainer(null, "{}", calls)
        assertEquals(InsightsExplainer.Availability.NotConfigured, none.availability())
        assertEquals(InsightsExplainer.Result.NotConfigured, none.explain("recovery", snap.summaryJson()))
        val blocked = explainer(route(AIProvider.GEMINI, blocked = "no_key"), "{}", calls)
        assertEquals(InsightsExplainer.Availability.NotConfigured, blocked.availability())
        assertEquals(InsightsExplainer.Result.NotConfigured, blocked.explain("health_age", snap.summaryJson()))
        assertTrue(calls.isEmpty())
    }
}
