package com.ayuvo.health.data.health

import com.ayuvo.health.analytics.AnalyticsTestFiles
import com.ayuvo.health.data.analytics.engine.AnalyticsMath
import com.ayuvo.health.data.analytics.engine.JMap
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.models.HealthDataType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The rollup's source policy picks the same rows as `analytics_reference.source_select` (shared vectors). */
class HealthSourcePolicyTest {
    @Before fun setUp() { HealthSourcePolicy.policy = AnalyticsTestFiles.config.policy }
    @After fun tearDown() { HealthSourcePolicy.policy = null }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun rollupAverageMatchesTheSourceSelectVectors() {
        val root = AnalyticsMath.plain(
            MedicationJson.json.parseToJsonElement(AnalyticsTestFiles.shared("analytics/test-vectors/source_select.json")!!.readText())
        ) as JMap
        for (case in root["cases"] as List<JMap>) {
            val input = case["input"] as JMap
            val expected = case["expected"] as JMap
            val metric = input["metric"] as String
            val rows = (input["rows"] as List<JMap>).map { r ->
                HealthSampleRow(
                    id = r["id"] as String, typeId = metric, startMs = (r["t_ms"] as Double).toLong(), endMs = (r["t_ms"] as Double).toLong(),
                    localDay = "2026-03-15", value = r["value"] as Double?, unit = "x", sourceId = r["source"] as String,
                    deviceType = (r["device_type"] as Double?)?.toInt(), origin = (r["origin"] as Double).toInt(),
                    count = (r["count"] as Double?)?.toInt() ?: 1, updatedMs = 1
                )
            }
            val chosen = HealthSourcePolicy.selectRows(metric, rows).filter { it.value != null }
            val name = case["name"] as String
            if (expected["value"] == null) {
                assertEquals(name, 0, chosen.size)
                continue
            }
            var ws = 0.0
            var w = 0
            for (r in chosen) {
                ws += r.value!! * maxOf(1, r.count)
                w += maxOf(1, r.count)
            }
            assertEquals(name, expected["value"] as Double, AnalyticsMath.roundTo(ws / w, 6), 1e-9)
            if (expected["source"] != null) assertEquals(name, expected["source"], chosen.map { it.sourceId }.distinct().single())
        }
    }

    @Test
    fun withoutAPolicyRowsPassThrough() {
        HealthSourcePolicy.policy = null
        val r = HealthSampleRow(id = "a", typeId = "resting_heart_rate", startMs = 0, endMs = 0, localDay = "2026-03-15", value = 50.0,
            unit = "bpm", sourceId = "x", updatedMs = 1)
        if (com.ayuvo.health.data.analytics.engine.AnalyticsConfig.active == null) assertEquals(listOf(r), HealthSourcePolicy.selectRows("resting_heart_rate", listOf(r)))
    }

    @Test
    fun ownSumCountsOnlyWorkoutBurnRecords() {
        fun row(id: String, client: String?, v: Double) = HealthSampleRow(
            id = id, typeId = HealthDataType.ACTIVE_ENERGY.id, startMs = 0, endMs = 1, localDay = "2026-03-15", value = v,
            unit = "kcal", sourceId = "com.ayuvo.health", clientRecordId = client, updatedMs = 1
        )
        val own = HealthRollupMath.ownWorkoutBurnByDay(
            listOf(row("a", "ayuvo_workout_burn|2026-03-15|x", 250.0), row("b", "ayuvo_gh_123", 400.0), row("c", null, 30.0))
        )
        assertEquals(250.0, own["2026-03-15"]!!, 0.0)
        assertNull(own["2026-03-14"])
    }
}
