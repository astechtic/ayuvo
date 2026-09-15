package com.ayuvo.health.services.ai

import com.ayuvo.health.data.health.HealthCoachSnapshot
import com.ayuvo.health.data.health.HealthCoachTypeSummary
import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.SleepNight
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Mirrors CoachWorkoutToolsTest for the four health tools: gating, exact contract, caps, payloads. */
class CoachHealthToolsTest {
    private val now = Instant.parse("2026-09-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = 86_400_000L

    private fun row(id: String, type: String, endMs: Long, value: Double?, value2: Double? = null, categoryValue: Int? = null, source: String = "com.example.watch") = HealthSampleRow(
        id = id, typeId = type, startMs = endMs - 60_000, endMs = endMs, startOffsetS = 0, endOffsetS = 0,
        localDay = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate().toString(),
        value = value, value2 = value2, unit = if (type == "blood_pressure") "mmHg" else "count", categoryValue = categoryValue,
        sourceId = source, device = "Acme Band", extraJson = "{\"activity_type\":56,\"has_route\":false}", updatedMs = endMs
    )

    private fun snapshot(): HealthCoachSnapshot {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val stepsDays = (0 until 10).map { i ->
            val d = today.minusDays(i.toLong())
            HealthDailyRollup("steps", d.toString(), "UTC", sum = 1000.0 * (i + 1), avg = 500.0, min = 100.0, max = 900.0, count = 2, lastValue = 900.0, fromPlatformAggregate = true)
        }
        val energyDays = listOf(HealthDailyRollup("active_energy", today.toString(), "UTC", sum = 420.0, count = 3, ownSum = 120.0, fromPlatformAggregate = true))
        val nights = (0 until 3).map { i ->
            val wake = today.minusDays(i.toLong())
            val end = wake.atStartOfDay(ZoneOffset.UTC).plusHours(7).toInstant().toEpochMilli()
            SleepNight(wake.toString(), end - 8 * 3_600_000L, end, 8 * 3600.0, 7 * 3600.0, 3 * 3600.0, 2 * 3600.0, 2 * 3600.0, 3600.0, "com.example.watch")
        }
        val bp = row("bp1", "blood_pressure", now.toEpochMilli() - day, 121.0, 79.0, categoryValue = 2)
        return HealthCoachSnapshot(
            lastSyncMs = now.toEpochMilli() - 120_000,
            types = listOf(
                HealthCoachTypeSummary("steps", "activity", "Steps", "count", "SUM", "cumulative", 40, now.toEpochMilli() - 9 * day, now.toEpochMilli(), row("s1", "steps", now.toEpochMilli(), 900.0), historyLimitedBeforeMs = now.toEpochMilli() - 30 * day),
                HealthCoachTypeSummary("active_energy", "activity", "Active Energy", "kcal", "SUM", "cumulative", 3, now.toEpochMilli() - day, now.toEpochMilli(), null, null),
                HealthCoachTypeSummary("blood_pressure", "heart", "Blood Pressure", "mmHg", "MIN_MAX", "discrete", 1, bp.endMs, bp.endMs, bp, null),
                HealthCoachTypeSummary("sleep", "sleep", "Sleep", "s", "DURATION", "session", 12, nights.last().startMs, nights.first().endMs, null, null)
            ),
            daily = mapOf("steps" to stepsDays.sortedBy { it.day }, "active_energy" to energyDays),
            samples = mapOf("blood_pressure" to listOf(bp), "steps" to listOf(row("s1", "steps", now.toEpochMilli(), 900.0))),
            nights = nights.sortedBy { it.nightOf },
            zoneId = "UTC"
        )
    }

    private fun tools(snapshot: HealthCoachSnapshot?) = CoachTools(weights = emptyList(), bodyFats = emptyList(), foods = emptyList(), clock = clock, healthSnapshot = snapshot)

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject

    @Test
    fun healthToolsAreAdvertisedOnlyWithASnapshot() {
        assertEquals(CoachTools.TOOL_NAMES, tools(null).advertisedToolNames)
        assertEquals(CoachTools.TOOL_NAMES + CoachTools.HEALTH_TOOL_NAMES, tools(snapshot()).advertisedToolNames)
        val refused = json(tools(null).execute("get_health_data_types"))
        assertTrue(refused["error"].asString.startsWith("Unknown tool: get_health_data_types"))
        assertFalse(refused["error"].asString.contains("get_sleep_history"))
        // The companion list stays as it was for existing callers/tests.
        assertFalse(CoachTools.TOOL_NAMES.any { it.startsWith("get_health") })
    }

    @Test
    fun namesDescriptionsAndSchemasMatchTheSharedContract() {
        assertEquals(listOf("get_health_data_types", "get_health_summary", "get_health_samples", "get_sleep_history"), CoachTools.HEALTH_TOOL_NAMES)
        assertEquals(
            "List the health data types synced from the phone's health platform (steps, heart rate, sleep, blood pressure, ...) with record counts, units, and earliest/latest dates. Call this first before any other health tool, and to learn the exact data_type keys.",
            CoachTools.TOOL_DESCRIPTIONS["get_health_data_types"]
        )
        assertEquals(
            "Daily statistics for one health data type between two dates (inclusive): per-day sum, average, min, max and record count in the type's unit, plus range highlights. Use for questions like \"how did I sleep this week?\", \"average resting heart rate in March\", or step trends. data_type must be a key returned by get_health_data_types.",
            CoachTools.TOOL_DESCRIPTIONS["get_health_summary"]
        )
        assertEquals(
            "Individual health records (start/end time, value, source app and device) for one data type between two dates (inclusive). Use only when per-reading detail matters, e.g. a specific blood-pressure reading or a workout. Prefer get_health_summary for trends.",
            CoachTools.TOOL_DESCRIPTIONS["get_health_samples"]
        )
        assertEquals(
            "Per-night sleep sessions between two dates (inclusive): bedtime, wake time, time in bed, time asleep and light/deep/REM/awake durations in seconds, with the source device. Use for questions about sleep duration, quality or consistency.",
            CoachTools.TOOL_DESCRIPTIONS["get_sleep_history"]
        )
        assertFalse(CoachTools.parameterSchemaFor("get_health_data_types").containsKey("required"))
        @Suppress("UNCHECKED_CAST")
        val summaryProps = CoachTools.parameterSchemaFor("get_health_summary")["properties"] as Map<String, Any>
        assertEquals(setOf("data_type", "from", "to", "limit"), summaryProps.keys)
        assertEquals(listOf("data_type", "from", "to"), CoachTools.parameterSchemaFor("get_health_summary")["required"])
        assertEquals(listOf("data_type", "from", "to"), CoachTools.parameterSchemaFor("get_health_samples")["required"])
        assertEquals(listOf("from", "to"), CoachTools.parameterSchemaFor("get_sleep_history")["required"])
        @Suppress("UNCHECKED_CAST")
        val sleepProps = CoachTools.parameterSchemaFor("get_sleep_history")["properties"] as Map<String, Any>
        assertEquals(setOf("from", "to", "limit"), sleepProps.keys)
    }

    @Test
    fun dataTypesPayloadShape() {
        val payload = json(tools(snapshot()).execute("get_health_data_types"))
        assertTrue(payload["health_data_enabled"].asBoolean)
        assertEquals("2026-09-14T11:58:00Z", payload["last_sync"].asString)
        assertEquals(4, payload["count"].asInt)
        val steps = payload.getAsJsonArray("data_types").map { it.asJsonObject }.first { it["data_type"].asString == "steps" }
        assertEquals("activity", steps["category"].asString)
        assertEquals("count", steps["unit"].asString)
        assertEquals("SUM", steps["aggregation"].asString)
        assertEquals(40, steps["count"].asInt)
        assertEquals("2026-09-05", steps["first"].asString)
        assertEquals("2026-09-14", steps["last"].asString)
        assertEquals(900.0, steps.getAsJsonObject("latest")["value"].asDouble, 0.0)
        assertEquals("2026-08-15", steps["history_limited_before"].asString)
        val bp = payload.getAsJsonArray("data_types").map { it.asJsonObject }.first { it["data_type"].asString == "blood_pressure" }
        assertEquals("121.0/79.0", bp.getAsJsonObject("latest")["value"].asString)
        assertEquals("sitting_down", bp.getAsJsonObject("latest")["value_text"].asString)
        assertFalse(bp.has("history_limited_before"))
    }

    @Test
    fun summaryRespectsRangeLimitAndCarriesOwnSum() {
        val t = tools(snapshot())
        val payload = json(t.execute("get_health_summary", mapOf("data_type" to "steps", "from" to "2026-09-10", "to" to "2026-09-14", "limit" to 3)))
        assertEquals("steps", payload["data_type"].asString)
        assertEquals("count", payload["unit"].asString)
        val days = payload.getAsJsonArray("days")
        assertEquals(3, days.size())
        assertEquals("2026-09-12", days[0].asJsonObject["date"].asString)
        assertEquals("2026-09-14", days[2].asJsonObject["date"].asString)
        assertEquals(1000.0, days[2].asJsonObject["sum"].asDouble, 0.0)
        val highlights = payload.getAsJsonObject("highlights")
        assertEquals(6000.0, highlights["total"].asDouble, 0.0)
        assertEquals(2000.0, highlights["average"].asDouble, 0.0)
        assertEquals(100.0, highlights["min"].asDouble, 0.0)
        assertEquals(900.0, highlights["max"].asDouble, 0.0)

        val energy = json(t.execute("get_health_summary", mapOf("data_type" to "active_energy", "from" to "2026-09-14", "to" to "2026-09-14")))
        assertEquals(120.0, energy.getAsJsonArray("days")[0].asJsonObject["own_sum"].asDouble, 0.0)
        assertEquals(420.0, energy.getAsJsonObject("highlights")["total"].asDouble, 0.0)

        val unknown = json(t.execute("get_health_summary", mapOf("data_type" to "nope", "from" to "2026-09-01", "to" to "2026-09-14")))
        assertTrue(unknown["error"].asString.contains("Unknown data_type"))
        val capped = json(t.execute("get_health_summary", mapOf("data_type" to "steps", "from" to "2026-09-01", "to" to "2026-09-14", "limit" to 9_999)))
        assertTrue(capped.getAsJsonArray("days").size() <= 400)
    }

    @Test
    fun samplesAndSleepPayloads() {
        val t = tools(snapshot())
        val samples = json(t.execute("get_health_samples", mapOf("data_type" to "blood_pressure", "from" to "2026-09-13", "to" to "2026-09-14")))
        assertEquals(1, samples["count"].asInt)
        val record = samples.getAsJsonArray("records")[0].asJsonObject
        assertEquals("2026-09-13T11:59:00Z", record["start"].asString)
        assertEquals(121.0, record["value"].asDouble, 0.0)
        assertEquals(79.0, record["value2"].asDouble, 0.0)
        assertEquals(2, record["category_value"].asInt)
        assertEquals("sitting_down", record["value_text"].asString)
        assertEquals("com.example.watch", record["source"].asString)
        assertEquals("Acme Band", record["device"].asString)
        assertEquals(56, record.getAsJsonObject("extra")["activity_type"].asInt)

        val sleep = json(t.execute("get_sleep_history", mapOf("from" to "2026-09-13", "to" to "2026-09-14", "limit" to 1)))
        assertEquals(1, sleep["count"].asInt)
        val night = sleep.getAsJsonArray("nights")[0].asJsonObject
        assertEquals("2026-09-14", night["night_of"].asString)
        assertEquals(7 * 3600, night["asleep_s"].asInt)
        assertEquals(8 * 3600, night["in_bed_s"].asInt)
        assertEquals(2 * 3600, night["rem_s"].asInt)
        assertEquals("com.example.watch", night["source"].asString)
    }

    @Test
    fun promptSummaryIsShortAndCoversHeadlineTypes() {
        val lines = CoachHealthData(snapshot(), clock).promptSummary()
        assertTrue(lines.size <= 12)
        assertTrue(lines.first().startsWith("## Health data"))
        assertTrue(lines.any { it.startsWith("- Steps: avg") })
        assertTrue(lines.any { it.startsWith("- Active energy") && it.contains("own workout estimates") })
        assertTrue(lines.any { it.startsWith("- Sleep: avg 7.0 h") })
        assertTrue(lines.any { it.startsWith("- Blood pressure (latest): 121/79 mmHg") })
    }
}
