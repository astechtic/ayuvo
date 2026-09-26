package com.ayuvo.health.actions

import com.ayuvo.health.insights.HealthAnalyticsEngine
import com.ayuvo.health.insights.InsightsFixtures
import com.ayuvo.health.insights.InsightsTestFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The three read-only Insights actions (docs/actions.md, Domain: insights). */
class InsightsActionsTest {
    private val env = FakeActionEnvironment().apply {
        insightsSnapshot = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs(days = 190)), InsightsTestFiles.config)
        healthGranted = setOf("sleep")
    }
    private val executor = ActionExecutor(ActionsTestFiles.catalog, env)

    private fun run(id: String, params: Map<String, Any?> = emptyMap(), source: ActionSource = ActionSource.SIRI) =
        runBlocking { executor.perform(ActionRequest(id, params, source)) }

    private fun failure(id: String): ActionException {
        try {
            run(id)
        } catch (e: ActionException) {
            return e
        }
        fail("expected an ActionException from $id")
        throw IllegalStateException()
    }

    @Test
    fun recoveryReturnsTheCatalogFieldsAndScreen() {
        val r = run("insights.recovery.get")
        val spec = ActionsTestFiles.catalog.action("insights.recovery.get")!!
        assertEquals(spec.outputFields.toSet(), r.fields.keys)
        assertEquals("ok", r.fields["status"])
        assertEquals(env.insightsSnapshot!!.recovery.score!!.toLong(), r.fields["score"])
        assertEquals("screen:insights.recovery", r.screen)
    }

    @Test
    fun recoveryNeedsHealthReadAndInsightsOn() {
        env.healthGranted = emptySet()
        assertEquals(ActionErrorCode.PERMISSION_REQUIRED, failure("insights.recovery.get").code)
        env.healthGranted = setOf("sleep")
        env.insightsSnapshot = null
        assertEquals(ActionErrorCode.UNAVAILABLE, failure("insights.recovery.get").code)
    }

    @Test
    fun healthAgeAndReview() {
        val h = run("insights.healthAge.get")
        assertEquals(ActionsTestFiles.catalog.action("insights.healthAge.get")!!.outputFields.toSet(), h.fields.keys)
        assertEquals("screen:insights.health_age", h.screen)
        val today = run("insights.dailyReview.get")
        assertEquals(InsightsFixtures.today.toString(), today.fields["day"])
        assertEquals("screen:insights.review", today.screen)
        val yesterday = run("insights.dailyReview.get", mapOf("day" to "yesterday"))
        assertEquals(InsightsFixtures.today.minusDays(1).toString(), yesterday.fields["day"])
        assertEquals(ActionsTestFiles.catalog.action("insights.dailyReview.get")!!.outputFields.toSet(), yesterday.fields.keys)
    }

    @Test
    fun coachReadsThemThroughItsTools() = runBlocking {
        val tools = CoachActionTools(executor)
        assertTrue(tools.names.containsAll(listOf("get_recovery", "get_health_age", "get_daily_review")))
        val out = Json.parseToJsonElement(tools.execute("get_daily_review", mapOf("day" to "today"))).jsonObject
        assertEquals("insights.dailyReview.get", out["action"]!!.jsonPrimitive.content)
        val bad = Json.parseToJsonElement(tools.execute("get_daily_review", mapOf("day" to "last_week"))).jsonObject
        assertEquals("bad_enum", bad["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun deepLinksAndSectionOpenInsights() {
        val parsed = ActionDeepLink.parse("ayuvo://open/insights") as ActionDeepLink.Parsed.Ok
        assertEquals("open.section", parsed.id)
        val open = run("open.section", mapOf("section" to "insights"), ActionSource.DEEPLINK)
        assertEquals("section:insights", open.screen)
        assertEquals("insights", AssistantIntents.section("recovery"))
        assertEquals("insights", AssistantIntents.section("Health Age"))
    }
}
