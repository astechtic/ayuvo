package com.ayuvo.health.actions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachActionToolsTest {
    private val env = FakeActionEnvironment()
    private val tools = CoachActionTools(ActionExecutor(ActionsTestFiles.catalog, env))

    @Test
    fun namesComeFromTheCatalog() {
        assertEquals(
            listOf("get_nutrition_targets", "get_water_intake", "get_fasting_status", "get_body_composition",
                "get_exercise_stats", "get_goals", "search_ayuvo", CoachActionTools.PROPOSE_TOOL),
            tools.names
        )
        val schema = Json.parseToJsonElement(tools.schemaJson("get_water_intake")!!).jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema["properties"]!!.jsonObject.containsKey("range"))
        assertFalse(tools.description(CoachActionTools.PROPOSE_TOOL)!!.contains("medication.dose.mark"))
    }

    @Test
    fun readToolsRunThroughTheExecutor() = runBlocking {
        val out = Json.parseToJsonElement(tools.execute("get_goals", emptyMap())).jsonObject
        assertEquals("goals.get", out["action"]!!.jsonPrimitive.content)
        assertEquals(2000, out["calories"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun proposeValidatesButNeverWrites() = runBlocking {
        val ok = Json.parseToJsonElement(tools.execute(CoachActionTools.PROPOSE_TOOL, mapOf("action_id" to "water.log", "params_json" to "{\"amount\": 500, \"unit\": \"ml\"}"))).jsonObject
        assertEquals("proposed", ok["status"]!!.jsonPrimitive.content)
        assertTrue(env.waterList.isEmpty())
        assertTrue(env.writes.isEmpty())
        assertEquals("water.log", tools.proposals.single().actionId)

        val meds = Json.parseToJsonElement(tools.execute(CoachActionTools.PROPOSE_TOOL, mapOf("action_id" to "medication.dose.mark", "params_json" to "{\"dose\":\"next\",\"action\":\"taken\"}"))).jsonObject
        assertEquals("not_allowed", meds["error"]!!.jsonPrimitive.content)
        val goals = Json.parseToJsonElement(tools.execute(CoachActionTools.PROPOSE_TOOL, mapOf("action_id" to "goals.update", "params_json" to "{\"goal\":\"water\",\"value\":3000}"))).jsonObject
        assertEquals("not_allowed", goals["error"]!!.jsonPrimitive.content)
        val bad = Json.parseToJsonElement(tools.execute(CoachActionTools.PROPOSE_TOOL, mapOf("action_id" to "water.log", "params_json" to "{\"amount\": 90000}"))).jsonObject
        assertEquals("out_of_range", bad["error"]!!.jsonPrimitive.content)
        assertEquals(1, tools.proposals.size)
    }
}
