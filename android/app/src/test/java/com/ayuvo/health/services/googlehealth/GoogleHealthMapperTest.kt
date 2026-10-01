package com.ayuvo.health.services.googlehealth

import com.ayuvo.health.data.health.HealthSampleRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.math.abs

/**
 * `shared/health/test-vectors/google_health/mapping.json`: data points → rows, filter strings and the
 * echo guard, identical on iOS (GoogleHealthMapTests.swift).
 */
class GoogleHealthMapperTest {
    private val map = GoogleHealthTestFiles.map
    private val vectors = Json.parseToJsonElement(
        GoogleHealthTestFiles.shared("test-vectors/google_health/mapping.json")!!.readText()
    ).jsonObject
    private val defaultZone = vectors.str("zone") ?: "UTC"

    @Test
    fun mappingVectorsProduceTheExpectedRows() {
        val cases = vectors.getValue("mapping").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.str("name")!!
            val zone = ZoneId.of(case.str("zone") ?: defaultZone)
            val entry = map.type(case.str("gh_type")!!) ?: error("$name: unknown gh_type")
            val mapped = GoogleHealthMapper(map) { zone }.map(entry, case.getValue("point").jsonObject, nowMs = 1L)
            assertNotNull(name, mapped)
            val rows = mapped!!.allRows
            val expected = case.getValue("expected_rows").jsonArray.map { it.jsonObject }
            assertEquals("$name: row count", expected.size, rows.size)
            expected.zip(rows).forEach { (want, got) -> assertRow(name, want, got) }
        }
    }

    private fun assertRow(case: String, want: JsonObject, got: HealthSampleRow) {
        val where = "$case/${want.str("id")}"
        assertEquals("$where id", want.str("id"), got.id)
        assertEquals("$where type_id", want.str("type_id"), got.typeId)
        assertEquals("$where start_ms", want.long("start_ms"), got.startMs)
        assertEquals("$where end_ms", want.long("end_ms"), got.endMs)
        assertEquals("$where start_offset_s", want.long("start_offset_s")?.toInt(), got.startOffsetS)
        assertEquals("$where end_offset_s", want.long("end_offset_s")?.toInt(), got.endOffsetS)
        assertEquals("$where local_day", want.str("local_day"), got.localDay)
        assertNumber("$where value", want.num("value"), got.value)
        assertNumber("$where value2", want.num("value2"), got.value2)
        assertNumber("$where value3", want.num("value3"), got.value3)
        assertEquals("$where value_text", want.str("value_text"), got.valueText)
        assertEquals("$where unit", want.str("unit"), got.unit)
        assertEquals("$where category_value", want.long("category_value")?.toInt(), got.categoryValue)
        assertEquals("$where title", want.str("title"), got.title)
        val wantExtra = want["extra_json"]?.takeIf { it !is JsonNull }
        val gotExtra = got.extraJson?.let { Json.parseToJsonElement(it) }
        assertEquals("$where extra_json", wantExtra, gotExtra)
        assertEquals("$where count", want.long("count")?.toInt(), got.count)
        assertEquals("$where source_id", want.str("source_id"), got.sourceId)
        assertEquals("$where device", want.str("device"), got.device)
        assertEquals("$where recording_method", want.long("recording_method")?.toInt(), got.recordingMethod)
        assertEquals("$where client_record_id", want.str("client_record_id"), got.clientRecordId)
        assertEquals("$where origin", want.long("origin")?.toInt(), got.origin)
        assertEquals("$where deleted", want.long("deleted") == 1L, got.deleted)
        assertEquals("$where updated_ms is the sync clock", 1L, got.updatedMs)
    }

    private fun assertNumber(where: String, want: Double?, got: Double?) {
        if (want == null) {
            assertNull(where, got)
            return
        }
        assertNotNull(where, got)
        assertTrue("$where: want $want got $got", abs(want - got!!) <= 1e-6)
    }

    @Test
    fun filterVectorsFillPlaceholders() {
        val cases = vectors.getValue("filters").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val zone = ZoneId.of(case.str("zone") ?: defaultZone)
            val entry = map.type(case.str("gh_type")!!)!!
            assertEquals(case.str("gh_type"), case.str("expected"), GoogleHealthMapper(map) { zone }.filter(entry, case.long("from_ms")!!))
        }
    }

    @Test
    fun echoGuardVectors() {
        val cases = vectors.getValue("echo_guard").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        val mapper = GoogleHealthMapper(map)
        for (case in cases) {
            val os = when (case.str("platform")) { "ios" -> "IOS"; else -> "ANDROID" }
            val mirror = !mapper.isEcho(case.getValue("dataSource").jsonObject, os)
            assertEquals(case.str("name"), case["mirror"]!!.jsonPrimitive.booleanOrNull, mirror)
        }
    }

    @Test
    fun duplicateOfAPlatformRowIsNotMirrored() {
        val mapper = GoogleHealthMapper(map)
        val google = HealthSampleRow(
            id = "gh:x", typeId = "steps", startMs = 1_000_000, endMs = 1_900_000, localDay = "2026-03-04",
            value = 412.0, unit = "count", sourceId = "google_health:x", origin = HealthSampleRow.ORIGIN_GOOGLE_HEALTH, updatedMs = 1
        )
        val platform = google.copy(id = "hc-1", origin = HealthSampleRow.ORIGIN_PLATFORM, startMs = 1_030_000, value = 414.0)
        assertTrue(mapper.isDuplicateOfPlatform(google, listOf(platform)))
        assertFalse(mapper.isDuplicateOfPlatform(google, listOf(platform.copy(value = 500.0))))
        assertFalse(mapper.isDuplicateOfPlatform(google, listOf(platform.copy(startMs = 1_000_000 + 61_000))))
        assertFalse(mapper.isDuplicateOfPlatform(google, listOf(platform.copy(origin = HealthSampleRow.ORIGIN_IMPORT))))
    }

    @Test
    fun convertersHandleUnitsAndDurations() {
        assertEquals(123.5, GoogleHealthMapper.durationSeconds("123.5s")!!, 1e-9)
        assertEquals(19800, GoogleHealthMapper.offsetSeconds("19800s"))
        assertEquals(-25200, GoogleHealthMapper.offsetSeconds("-25200s"))
        assertEquals(1500.0, GoogleHealthMapper.convertMass(1.5, "GRAM", "mg")!!, 1e-9)
        assertEquals(2.0, GoogleHealthMapper.convertMass(2.0, null, "g")!!, 1e-9)
        val mapper = GoogleHealthMapper(map) { ZoneId.of("UTC") }
        val height = mapper.map(
            map.type("height")!!,
            Json.parseToJsonElement(
                """{"name":"users/me/dataTypes/height/dataPoints/h1","height":{"sampleTime":{"physicalTime":"2026-03-04T02:00:00Z"},"heightQuantity":{"value":180,"unit":"CENTIMETER"}}}"""
            ).jsonObject,
            nowMs = 5L
        )!!.row
        assertEquals(1.8, height.value!!, 1e-9)
        assertEquals("google_health:unknown", height.sourceId)
        assertEquals(0, height.recordingMethod)
        assertNull(height.device)
    }

    @Test
    fun sleepWithoutStagesGetsOneSpanningRow() {
        val mapper = GoogleHealthMapper(map) { ZoneId.of("UTC") }
        val point = Json.parseToJsonElement(
            """{"name":"x/dataPoints/s2","sleep":{"interval":{"startTime":"2026-03-06T22:00:00Z","endTime":"2026-03-07T06:00:00Z"},"sleepType":"CLASSIC",
               "outOfBedSegments":[{"startTime":"2026-03-07T02:00:00Z","endTime":"2026-03-07T02:10:00Z"}]}}"""
        ).jsonObject
        val mapped = mapper.map(map.type("sleep")!!, point, 1L)!!
        assertEquals(listOf("gh:s2", "gh:s2:0", "gh:s2:1"), mapped.allRows.map { it.id })
        assertEquals(1, mapped.extraRows[0].categoryValue)
        assertEquals(28_800.0, mapped.extraRows[0].value!!, 1e-9)
        assertEquals(6, mapped.extraRows[1].categoryValue)
        assertEquals("ayuvo_gh_s2", mapped.row.clientRecordId)
        assertNull(mapped.extraRows[0].clientRecordId)
    }

    @Test
    fun ecgDropsTheWaveform() {
        val mapper = GoogleHealthMapper(map) { ZoneId.of("UTC") }
        val point = Json.parseToJsonElement(
            """{"name":"x/dataPoints/e1","electrocardiogram":{"sampleTime":{"physicalTime":"2026-03-07T06:00:00Z"},
               "resultClassification":"UNCONFIRMED_AFIB","samplingFrequencyHz":250,"ecgWaveformSamples":[1,2,3]}}"""
        ).jsonObject
        val row = mapper.map(map.type("electrocardiogram")!!, point, 1L)!!.row
        assertEquals(4, row.categoryValue)
        val extra = Json.parseToJsonElement(row.extraJson!!).jsonObject
        assertEquals(250.0, extra["sampling_frequency_hz"]!!.jsonPrimitive.doubleOrNull!!, 0.0)
        assertFalse(extra.containsKey("ecgWaveformSamples"))
        assertNull(row.value)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
