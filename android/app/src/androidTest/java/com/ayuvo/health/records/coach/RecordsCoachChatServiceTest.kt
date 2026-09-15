package com.ayuvo.health.records.coach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.processing.UnitsCatalog
import com.ayuvo.health.services.ai.ChatService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Drives Coach's OpenAI-compatible tool loop against a local server (no API key, no real provider):
 * the three records tools are advertised with the contract's exact descriptions and schema bytes,
 * the system prompt carries the §26 lines, a `records_get` outside the selection is refused, and the
 * reply's record refs are the records actually read (docs §26–§28).
 */
@RunWith(AndroidJUnit4::class)
class RecordsCoachChatServiceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AyuvoApp
    private val container = app.container
    private lateinit var server: MockWebServer

    private var originalProvider: AIProvider? = null
    private var originalSeparate = false
    private var originalBaseUrl: String? = null
    private var originalTextFallback = false

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer().apply { start() }
        originalProvider = container.prefs.selectedAIProvider.first()
        originalSeparate = container.prefs.separateTextProviderEnabled.first()
        originalBaseUrl = container.prefs.customBaseUrl(AIProvider.OLLAMA).first()
        originalTextFallback = container.prefs.textFallbackEnabled.first()
        container.prefs.setSelectedAIProvider(AIProvider.OLLAMA)
        container.prefs.setSeparateTextProviderEnabled(false)
        container.prefs.setTextFallbackEnabled(false)
        container.prefs.setCustomBaseUrl(AIProvider.OLLAMA, server.url("/v1").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = runBlocking {
        originalProvider?.let { container.prefs.setSelectedAIProvider(it) }
        container.prefs.setSeparateTextProviderEnabled(originalSeparate)
        container.prefs.setTextFallbackEnabled(originalTextFallback)
        container.prefs.setCustomBaseUrl(AIProvider.OLLAMA, originalBaseUrl)
        server.shutdown()
    }

    private fun snapshot(): SnapshotCoachData {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val units = UnitsCatalog.parseOrDefault(context.assets.open("records/units.json").bufferedReader().use { it.readText() })
        val catalog = AnalyteCatalog.parse(context.assets.open(AnalyteCatalog.ASSET_PATH).bufferedReader().use { it.readText() }, units)
        val json = """
            {"records":[
              {"id":"r1","seq":1,"title":"CBC","record_type":"lab_report","sort_date":"2026-07-10","created_ms":1000,"page_count":1,"archived":0},
              {"id":"r2","seq":2,"title":"Lipid Profile","record_type":"lab_report","sort_date":"2026-09-01","created_ms":2000,"page_count":1,"archived":0}],
             "fields":[
              {"id":"f1","record_id":"r1","field_key":"patient_name","value_text":"Asha Rao","value_json":null,"state":"suggested","confidence":0.8,"method":"rules","source_page":0},
              {"id":"f2","record_id":"r1","field_key":"facility","value_text":"Metro Diagnostics","value_json":null,"state":"confirmed","confidence":0.9,"method":"rules","source_page":0}],
             "observations":[
              {"id":"o1","record_id":"r1","analyte_id":"hemoglobin","raw_name":"Hb","value_num":10.2,"value_text":"10.2","unit":"g/dL","canonical_value":10.2,"canonical_unit":"g/dL","ref_low":12,"ref_high":15.5,"ref_text":"12.0 - 15.5","flag":"low","observed_date":"2026-07-10","method":"rules","confidence":0.9,"state":"suggested","source_page":0,"excluded_from_trends":0,"created_ms":1000}]}
        """.trimIndent()
        return SnapshotCoachData.parse(Json.parseToJsonElement(json).jsonObject, catalog)
    }

    @Test
    fun openAiCompatibleToolLoopRunsRestrictedRecordsGet() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[
              {"id":"c1","type":"function","function":{"name":"records_get","arguments":"{\"record_id\":\"r2\"}"}},
              {"id":"c2","type":"function","function":{"name":"records_get","arguments":"{\"record_id\":\"r1\",\"include_text\":true}"}}]}}]}"""
        ))
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Your hemoglobin was low on the CBC of 2026-07-10."}}]}"""))

        val contract = container.recordsCoachContract
        val data = snapshot()
        val selected = listOf("r1")
        val lines = RecordsCoach.promptLines(data, contract, accessEnabled = true, selectedIds = selected)
        val tools = RecordsCoachTools(contract, data, selected, today = { LocalDate.parse("2026-09-15") })
        val chat = ChatService(container.prefs, container.keyStore, OkHttpClient())
        val reply = chat.send(
            history = emptyList(), newUserMessage = "Explain this report", profile = UserProfile(),
            weights = emptyList(), bodyFats = emptyList(), foods = emptyList(), heightMetric = true, weightMetric = true,
            records = ChatService.RecordsTurn(tools = tools, availableLine = lines.availableLine, selectedLines = lines.selectedLines, guardrails = lines.guardrails)
        )
        assertEquals("Your hemoglobin was low on the CBC of 2026-07-10.", reply.text)
        assertEquals(listOf(CoachRecordRef("r1", "CBC", "2026-07-10")), reply.recordRefs)

        val first = server.takeRequest().body.readUtf8()
        for (tool in contract.tools) {
            assertTrue("schema bytes of ${tool.name}", first.contains("\"parameters\":${tool.schemaJson}"))
            assertTrue("description of ${tool.name}", first.contains("\"description\":${JsonPrimitive(tool.description)}"))
        }
        val system = JSONObject(first).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("- 2 health records are stored in Ayuvo (latest 2026-09-01). Use records_search, records_get and records_observation_series to read them."))
        assertTrue(system.contains(contract.prompt.selectedHeader + "\n- r1: CBC — 2026-07-10 (Lab Report)"))
        assertTrue(system.contains(contract.prompt.guardrails))

        val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val toolRows = (0 until second.length()).map { second.getJSONObject(it) }.filter { it.optString("role") == "tool" }
        assertEquals(2, toolRows.size)
        assertEquals("record 'r2' is not in the records the user selected for this conversation", JSONObject(toolRows[0].getString("content")).getString("error"))
        val got = JSONObject(toolRows[1].getString("content"))
        assertEquals("r1", got.getString("record_id"))
        assertFalse("patient name never leaves the device", toolRows[1].getString("content").contains("Asha"))
    }
}
