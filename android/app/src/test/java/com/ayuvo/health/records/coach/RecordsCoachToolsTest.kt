package com.ayuvo.health.records.coach

import com.ayuvo.health.backup.CloudBackupArchive
import com.ayuvo.health.backup.CloudBackupValue
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.processing.RecordsTestFiles
import com.ayuvo.health.records.processing.UnitsCatalog
import com.ayuvo.health.services.ai.CoachTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Coach records tools contract (docs §26–§28), in the style of CoachHealthToolsTest: exact
 * advertised strings, schema bytes, gating through CoachTools, restriction, unavailability, the §30
 * gate, record refs and the persisted `record_refs` / backup exclusion.
 */
class RecordsCoachToolsTest {
    private val fileText = RecordsTestFiles.shared("coach_tools.json")!!.readText()
    private val contract = RecordsCoachContract.parse(fileText)
    private val catalog by lazy {
        val units = UnitsCatalog.parseOrDefault(RecordsTestFiles.shared("units.json")!!.readText())
        AnalyteCatalog.parse(RecordsTestFiles.shared("analytes.json")!!.readText(), units)
    }

    private val snapshotJson = """
        {"records":[
          {"id":"r1","seq":1,"title":"CBC","record_type":"lab_report","category":"lab_reports","sort_date":"2026-07-10","created_ms":1000,"review_status":"none","ai_mode_used":"none","page_count":1,"archived":0,"favorite":0,"source":"import","notes":null},
          {"id":"r2","seq":2,"title":"Complete Blood Count","record_type":"lab_report","category":"lab_reports","sort_date":"2026-09-09","created_ms":2000,"review_status":"none","ai_mode_used":"none","page_count":1,"archived":0,"favorite":0,"source":"import","notes":null}],
         "fields":[
          {"id":"f1","record_id":"r1","field_key":"patient_name","value_text":"Asha Rao","value_json":null,"state":"suggested","confidence":0.8,"method":"rules","source_page":0},
          {"id":"f2","record_id":"r1","field_key":"facility","value_text":"Metro Diagnostics","value_json":null,"state":"confirmed","confidence":0.9,"method":"rules","source_page":0}],
         "observations":[
          {"id":"o1","record_id":"r1","field_id":null,"analyte_id":"hemoglobin","analyte_method":"catalog","raw_name":"Hb","value_num":10.2,"value_text":"10.2","unit":"g/dL","canonical_value":10.2,"canonical_unit":"g/dL","ref_low":12,"ref_high":15.5,"ref_text":"12.0 - 15.5","flag":"low","observed_date":"2026-07-10","observed_date_method":"sort_date","method":"rules","confidence":0.9,"state":"suggested","source_page":0,"excluded_from_trends":0,"created_ms":1000,"updated_ms":1000},
          {"id":"o2","record_id":"r2","field_id":null,"analyte_id":"hemoglobin","analyte_method":"catalog","raw_name":"Hemoglobin","value_num":9.1,"value_text":"9.1","unit":"g/dL","canonical_value":9.1,"canonical_unit":"g/dL","ref_low":12,"ref_high":15.5,"ref_text":"12.0 - 15.5","flag":"low","observed_date":"2026-09-09","observed_date_method":"sort_date","method":"rules","confidence":0.9,"state":"suggested","source_page":0,"excluded_from_trends":0,"created_ms":2000,"updated_ms":2000}],
         "pages":[{"record_id":"r1","page_index":0,"text":"Patient Name: Asha Rao\nHb 10.2 g/dL 12.0 - 15.5"}]}
    """.trimIndent()

    private fun data() = SnapshotCoachData.parse(Json.parseToJsonElement(snapshotJson).jsonObject, catalog)

    private fun tools(
        selected: List<String> = emptyList(),
        available: Boolean = true,
        gate: (suspend () -> Boolean)? = null
    ) = RecordsCoachTools(contract, data(), selected, today = { LocalDate.parse("2026-09-15") }, stillAvailable = { available }, beforeFirstCall = gate)

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun namesDescriptionsAndSchemasMatchTheSharedFileExactly() {
        assertEquals(listOf("records_search", "records_get", "records_observation_series"), contract.names)
        val root = parse(fileText)
        root["tools"]!!.jsonArray.forEach { t ->
            val o = t.jsonObject
            val tool = contract.tool(o["name"]!!.jsonPrimitive.content)!!
            assertEquals(o["description"]!!.jsonPrimitive.content, tool.description)
        }
        // Byte-compare: the compact serialization equals the one-line schema text in the file.
        val schemaLines = fileText.lines().filter { it.trimStart().startsWith("\"input_schema\": ") }
            .map { it.trimStart().removePrefix("\"input_schema\": ") }
        assertEquals(contract.tools.map { it.schemaJson }, schemaLines)
        assertEquals("from must be on or before to", contract.errors.dateOrder)
    }

    @Test
    fun recordsToolsAreAdvertisedOnlyWithRecordsTools() {
        val without = CoachTools(weights = emptyList(), bodyFats = emptyList(), foods = emptyList())
        assertEquals(CoachTools.TOOL_NAMES, without.advertisedToolNames)
        assertNull(without.rawSchemaFor("records_get"))
        assertTrue(parse(without.execute("records_get")).getValue("error").jsonPrimitive.content.startsWith("Unknown tool: records_get"))

        val with = CoachTools(weights = emptyList(), bodyFats = emptyList(), foods = emptyList(), records = tools())
        assertEquals(CoachTools.TOOL_NAMES + contract.names, with.advertisedToolNames)
        assertEquals(contract.tool("records_search")!!.description, with.descriptionFor("records_search"))
        assertEquals(contract.tool("records_observation_series")!!.schemaJson, with.rawSchemaFor("records_observation_series"))
        assertEquals(CoachTools.TOOL_DESCRIPTIONS["get_food_entries"], with.descriptionFor("get_food_entries"))
        assertNull(runBlocking { with.executeRecords("get_food_entries", emptyMap()) })
    }

    @Test
    fun selectionRestrictsGetSeriesAndSearch() = runBlocking {
        val t = tools(selected = listOf("r1"))
        val refused = t.executeJson("records_get", RecordsCoachTools.toJson(mapOf("record_id" to "r2")))
        assertEquals("record 'r2' is not in the records the user selected for this conversation", refused["error"]!!.jsonPrimitive.content)
        val got = t.executeJson("records_get", RecordsCoachTools.toJson(mapOf("record_id" to "r1", "include_text" to true)))
        assertEquals("Metro Diagnostics", got["facility"]!!.jsonPrimitive.content)
        // PII: no patient_name field, and the name line is removed from the excerpt.
        assertFalse(got.toString().contains("Asha"))
        assertEquals("Hb 10.2 g/dL 12.0 - 15.5", got["text"]!!.jsonPrimitive.content)

        val series = t.executeJson("records_observation_series", RecordsCoachTools.toJson(mapOf("analyte" to "Hb")))
        assertEquals(listOf("r1"), series["points"]!!.jsonArray.map { it.jsonObject["record_id"]!!.jsonPrimitive.content })
        val search = t.executeJson("records_search", RecordsCoachTools.toJson(mapOf("query" to "")))
        assertEquals(listOf("r1"), search["records"]!!.jsonArray.map { it.jsonObject["record_id"]!!.jsonPrimitive.content })

        assertEquals(listOf(CoachRecordRef("r1", "CBC", "2026-07-10")), t.readRefs)
    }

    @Test
    fun unrestrictedSeriesAndArgumentErrors() = runBlocking {
        val t = tools()
        val series = t.executeJson("records_observation_series", RecordsCoachTools.toJson(mapOf("analyte" to "hemoglobin", "from" to "2026-08-01")))
        assertEquals(1, series["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("g/dL", series["unit"]!!.jsonPrimitive.content)
        assertEquals("invalid date '2026-02-30' (expected yyyy-MM-dd)", t.executeJson("records_search", RecordsCoachTools.toJson(mapOf("query" to "cbc", "from" to "2026-02-30")))["error"]!!.jsonPrimitive.content)
        assertEquals("from must be on or before to", t.executeJson("records_search", RecordsCoachTools.toJson(mapOf("query" to "", "from" to "2026-09-02", "to" to "2026-09-01")))["error"]!!.jsonPrimitive.content)
        assertEquals("no values found for 'ferritin'", t.executeJson("records_observation_series", RecordsCoachTools.toJson(mapOf("analyte" to "ferritin")))["error"]!!.jsonPrimitive.content)
        assertEquals("unknown record_id 'nope'; call records_search", t.executeJson("records_get", RecordsCoachTools.toJson(mapOf("record_id" to "nope")))["error"]!!.jsonPrimitive.content)
        // Errors never add record refs; the series point's record does (with the point date).
        assertEquals(listOf(CoachRecordRef("r2", "Complete Blood Count", "2026-09-09")), t.readRefs)
    }

    @Test
    fun unavailableWhenAccessWasTurnedOffOrTheGateDeclines() = runBlocking {
        val off = tools(available = false)
        assertEquals("health records are not available", off.executeJson("records_search", RecordsCoachTools.toJson(mapOf("query" to "")))["error"]!!.jsonPrimitive.content)
        var asked = 0
        val declined = tools(gate = { asked++; false })
        declined.executeJson("records_search", RecordsCoachTools.toJson(mapOf("query" to "")))
        val second = declined.executeJson("records_get", RecordsCoachTools.toJson(mapOf("record_id" to "r1")))
        assertEquals(1, asked)
        assertEquals("health records are not available", second["error"]!!.jsonPrimitive.content)
        assertTrue(declined.readRefs.isEmpty())
    }

    @Test
    fun providerArgumentsKeepTheirJsonTypes() {
        val json = RecordsCoachTools.toJson(mapOf("limit" to 5, "include_text" to "true", "from" to null, "nested" to mapOf("a" to 1.5)))
        assertEquals(JsonPrimitive(5), json["limit"])
        assertTrue(json["include_text"]!!.jsonPrimitive.isString)
        assertEquals("{\"a\":1.5}", json["nested"].toString())
    }

    /**
     * §26: an assistant reply persists the records it relied on. Messages live in `ayuvo_coach.db`
     * now, where this list is the `record_refs_json` column, so the wire shape is what matters.
     */
    @Test
    fun recordRefsKeepTheirPersistedShape() {
        val codec = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val refs = listOf(CoachRecordRef("r1", "CBC", "2026-07-10"))
        val encoded = codec.encodeToString(ListSerializer(CoachRecordRef.serializer()), refs)
        assertEquals("""[{"record_id":"r1","title":"CBC","date":"2026-07-10"}]""", encoded)
        assertEquals(refs, codec.decodeFromString(ListSerializer(CoachRecordRef.serializer()), encoded))
        // A reply that used no records stores nothing at all, and such a row reads back as empty.
        assertEquals("[]", codec.encodeToString(ListSerializer(CoachRecordRef.serializer()), emptyList()))
        assertTrue(codec.decodeFromString(ListSerializer(CoachRecordRef.serializer()), "[]").isEmpty())
    }

    @Test
    fun coachRecordsPreferencesNeverEnterTheCloudBackup() {
        val values = mapOf(
            "healthRecordsCoachAccessEnabled" to CloudBackupValue.bool(true),
            "healthRecordsCoachConsentedAt" to CloudBackupValue.string("2026-09-15T12:00:00Z"),
            "coachHealthDataEnabled" to CloudBackupValue.bool(true)
        )
        val unpack = CloudBackupArchive.unpack(CloudBackupArchive.pack(values = values, photos = emptyMap(), exportedAt = "2026-09-15T12:00:00Z", appVersion = "7.0"))
        assertEquals(setOf("coachHealthDataEnabled"), unpack.document.payload.values.keys)
    }

    @Test
    fun mentionsRecordsUsesTypeWordsAndRecordWords() {
        assertTrue(RecordsCoach.mentionsRecords("What did my blood test say?"))
        assertTrue(RecordsCoach.mentionsRecords("Check my records"))
        assertFalse(RecordsCoach.mentionsRecords("I recorded my weight"))
    }
}
