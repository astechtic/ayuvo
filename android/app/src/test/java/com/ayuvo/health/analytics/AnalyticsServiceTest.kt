package com.ayuvo.health.analytics

import com.ayuvo.health.actions.ActionExecutor
import com.ayuvo.health.actions.ActionRequest
import com.ayuvo.health.actions.ActionSource
import com.ayuvo.health.actions.ActionsTestFiles
import com.ayuvo.health.actions.CoachActionTools
import com.ayuvo.health.actions.FakeActionEnvironment
import com.ayuvo.health.data.analytics.AnalyticsService
import com.ayuvo.health.data.analytics.MlModelRow
import com.ayuvo.health.data.analytics.engine.AnalyticsEngine
import com.ayuvo.health.data.analytics.engine.AnalyticsForecast
import com.ayuvo.health.data.analytics.engine.AnalyticsMath
import com.ayuvo.health.data.analytics.engine.JMap
import com.ayuvo.health.insights.AnalyticsBridge
import com.ayuvo.health.insights.AnalyticsExtras
import com.ayuvo.health.insights.HealthAnalyticsEngine
import com.ayuvo.health.insights.InsightsFixtures
import com.ayuvo.health.insights.InsightsTestFiles
import com.ayuvo.health.insights.WorkoutInput
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.services.ai.CoachAnalytics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** AnalyticsBridge input building, Recovery v2 mapping, AnalyticsService hashing / stored-model prediction, evidence. */
class AnalyticsServiceTest {
    private val acfg = AnalyticsTestFiles.config
    private val icfg = InsightsTestFiles.config
    private val today = InsightsFixtures.today

    private fun bundle(extras: AnalyticsExtras = AnalyticsExtras(), scan: Map<String, Set<LocalDate>> = emptyMap()) =
        InsightsFixtures.bundle(
            InsightsFixtures.inputs(days = 90, workouts = (0 until 60).map { k ->
                val s = today.minusDays(k.toLong()).atTime(7, 0).atZone(InsightsFixtures.zone).toInstant().toEpochMilli()
                WorkoutInput(s, s + 45 * 60_000L, if (k % 2 == 0) 6.0 else null)
            })
        ).copy(extras = extras, scanFallback = scan)

    @Test
    fun inputsUseDayKeysAndNeverInventValues() {
        val b = bundle(scan = mapOf("resting_heart_rate" to setOf(today)), extras = AnalyticsExtras(derivedDays = mapOf("vo2_max" to setOf(today.minusDays(3)))))
        val inp = AnalyticsBridge.inputs(b)
        @Suppress("UNCHECKED_CAST")
        val series = inp["series"] as Map<String, Map<String, Any?>>
        assertEquals(b.inputs.series["hrv"]!!.size, series["hrv"]!!.size)
        assertTrue(series["hrv"]!!.keys.all { it.length == 10 })
        assertNull(series["wrist_temperature"]) // no skin-temperature data -> absent, never zeros
        @Suppress("UNCHECKED_CAST")
        val ctx = inp["contexts"] as Map<String, Map<String, Any?>>
        assertEquals("camera", ctx["resting_heart_rate"]!![today.toString()])
        assertEquals("derived", ctx["vo2_max"]!![today.minusDays(3).toString()])
        assertEquals(listOf("resting_heart_rate"), inp["scan_fallback"])
        // Without night details the nights carry only asleep minutes.
        @Suppress("UNCHECKED_CAST")
        val night = (inp["nights"] as Map<String, Map<String, Any?>>)[today.toString()]!!
        assertEquals(setOf("asleep_min"), night.keys)
        @Suppress("UNCHECKED_CAST")
        val effort = (inp["workouts"] as List<Map<String, Any?>>).map { it["effort"] }
        assertTrue(effort.contains(null) && effort.contains(6.0))
    }

    @Test
    fun recoveryV2IsShownAndMappedToTheV1Shape() {
        val snap = HealthAnalyticsEngine.snapshot(bundle(), icfg, acfg)
        val r = snap.recovery
        assertNotNull(r.v2)
        assertEquals("ayuvo.recovery", r.v2!!["algorithm_id"])
        assertEquals("ok", r.status)
        assertEquals((r.v2!!["score"] as Number).toInt(), r.score)
        assertTrue(r.confidence in setOf("high", "medium", "low"))
        assertNotNull(r.confidenceScore)
        // Same engine, same inputs: the mapped score equals the reference function's.
        val direct = AnalyticsBridge.recoveryV2(AnalyticsBridge.inputs(bundle()), today, acfg)
        assertEquals(direct["score"], r.v2!!["score"])
        assertNotNull(snap.analytics)
        assertNotNull(snap.analytics!!.load)
        // Without the analytics contract Insights falls back to Recovery v1.
        assertNull(HealthAnalyticsEngine.snapshot(bundle(), icfg, null).recovery.v2)
    }

    @Test
    fun recoveryStatusMapping() {
        val collecting = AnalyticsBridge.toRecoveryResult(mapOf("status" to "INSUFFICIENT_HISTORY", "collecting" to mapOf("have" to 3, "need" to 7)), today)
        assertEquals("collecting", collecting.status)
        assertEquals(3, collecting.collecting!!.have)
        assertEquals("no_heart_data", AnalyticsBridge.toRecoveryResult(mapOf("status" to "NO_DATA", "reason" to "no_heart_data"), today).status)
        val low = AnalyticsBridge.toRecoveryResult(mapOf("status" to "LOW_CONFIDENCE", "score" to 51, "confidence_band" to "low", "confidence" to 0.42), today)
        assertEquals("ok", low.status)
        assertEquals("low", low.confidence)
    }

    @Test
    fun inputHashChangesOnlyWithTheDaysSlice() {
        val inp = AnalyticsBridge.inputs(bundle())
        val h1 = AnalyticsService.hashOf(AnalyticsService.slice(inp, today), 2, 1)
        assertEquals(h1, AnalyticsService.hashOf(AnalyticsService.slice(AnalyticsBridge.inputs(bundle()), today), 2, 1))
        // A value 200 days back is outside today's 120-day slice.
        @Suppress("UNCHECKED_CAST")
        fun withHrv(day: LocalDate, v: Double): JMap = LinkedHashMap(inp).apply {
            val series = LinkedHashMap(inp["series"] as Map<String, Any?>)
            series["hrv"] = LinkedHashMap(series["hrv"] as Map<String, Any?>).apply { put(day.toString(), v) }
            put("series", series)
        }
        assertEquals(h1, AnalyticsService.hashOf(AnalyticsService.slice(withHrv(today.minusDays(200), 70.0), today), 2, 1))
        assertNotEquals(h1, AnalyticsService.hashOf(AnalyticsService.slice(withHrv(today.minusDays(3), 70.0), today), 2, 1))
        // A new algorithm or config version always recomputes.
        assertNotEquals(h1, AnalyticsService.hashOf(AnalyticsService.slice(inp, today), 3, 1))
        assertNotEquals(h1, AnalyticsService.hashOf(AnalyticsService.slice(inp, today), 2, 2))
    }

    @Test
    fun storedModelPredictsLikeTheEngine() {
        // The deployed vector case: refit model, then predict from the stored coefficients / normalization.
        val doc = MedicationJson.json.parseToJsonElement(AnalyticsTestFiles.shared("analytics/test-vectors/forecast.json")!!.readText()).jsonObject
        val case = doc["cases"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "predictable_deployed" }
        @Suppress("UNCHECKED_CAST")
        val input = AnalyticsMath.plain(case["input"]) as JMap
        @Suppress("UNCHECKED_CAST")
        val inputs = AnalyticsEngine.decodeInputs(input["inputs"] as JMap)
        val asOf = input["as_of"] as String
        val feats = AnalyticsForecast.forecastFeatures(inputs, input["from"] as String, asOf, acfg)
        @Suppress("UNCHECKED_CAST")
        val target = com.ayuvo.health.data.analytics.engine.AnalyticsCore.seriesOf((inputs["series"] as Map<String, Any?>)["hrv"])
        val res = AnalyticsForecast.forecast("hrv", feats, target, asOf, acfg)
        assertEquals(true, res["deployed"])
        val model = MlModelRow(
            modelId = "ayuvo.forecast.hrv", modelVersion = 1, algorithmVersion = 1, target = "hrv", featureSchemaVersion = 1,
            trainStart = null, trainEnd = null, valStart = null, valEnd = null, testStart = null, testEnd = null, lambda = null,
            coefficientsJson = AnalyticsService.json(mapOf("intercept" to res["intercept"], "coefficients" to res["coefficients"])),
            normalizationJson = AnalyticsService.json(res["normalization"]), metricsJson = "{}", baselineMetricsJson = "{}",
            deployed = true, createdMs = 0
        )
        val pred = AnalyticsService.predict(model, feats[asOf]!!)!!
        // Coefficients are stored at 6 decimals, so agreement is to rounding, not bit-exact.
        assertEquals((res["prediction"] as Number).toDouble(), pred, 0.05)
    }

    @Test
    fun evidenceActionAndCoachToolReturnTheEvidenceObject() = runBlocking {
        val snap = HealthAnalyticsEngine.snapshot(bundle(), icfg, acfg)
        val env = FakeActionEnvironment().apply {
            insightsSnapshot = snap
            healthGranted = setOf("sleep")
        }
        val executor = ActionExecutor(ActionsTestFiles.catalog, env)
        val r = executor.perform(ActionRequest("insights.evidence.get", emptyMap(), ActionSource.SHORTCUTS))
        assertEquals(ActionsTestFiles.catalog.action("insights.evidence.get")!!.outputFields.toSet(), r.fields.keys)
        @Suppress("UNCHECKED_CAST")
        val items = r.fields["items"] as List<Map<String, Any?>>
        assertTrue(items.any { it["metric"] == "recovery_indicator" && it["algorithm"] == "ayuvo.recovery@2" })
        val tools = CoachActionTools(executor)
        assertTrue("get_health_evidence" in tools.names)
        val out = Json.parseToJsonElement(tools.execute("get_health_evidence", emptyMap())).jsonObject
        assertEquals("insights.evidence.get", out["action"]!!.jsonPrimitive.content)
        // The recovery action keeps the catalog fields; v2 adds the percentage to the confidence text.
        val rec = executor.perform(ActionRequest("insights.recovery.get", emptyMap(), ActionSource.SIRI))
        assertEquals(ActionsTestFiles.catalog.action("insights.recovery.get")!!.outputFields.toSet(), rec.fields.keys)
        assertTrue((rec.fields["confidence"] as String).endsWith("%)"))
    }

    @Test
    fun coachDigestStatesComputedValuesAndRules() {
        val snap = HealthAnalyticsEngine.snapshot(bundle(), icfg, acfg)
        val lines = CoachAnalytics.promptLines(snap)
        assertTrue(lines.any { it.startsWith("- Recovery Indicator v2: ${snap.recovery.score}/100") })
        assertTrue(CoachAnalytics.RULES.any { "Never calculate" in it })
        assertFalse(CoachAnalytics.RULES.joinToString(" ").contains("caused your"))
        assertTrue(CoachAnalytics.promptLines(null).isEmpty())
    }
}
