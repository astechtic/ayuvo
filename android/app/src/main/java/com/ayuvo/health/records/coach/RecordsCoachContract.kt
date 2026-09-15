package com.ayuvo.health.records.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `assets/records/coach_tools.json` (a byte copy of `shared/records/coach_tools.json`, docs §26–§30):
 * tool names, descriptions and input schemas, prompt lines, guardrails and error strings. Every
 * string Coach sends about Health Records comes from here, so both platforms stay identical.
 */
class RecordsCoachContract private constructor(
    val tools: List<Tool>,
    val prompt: Prompt,
    val errors: Errors
) {
    /** [schemaJson] is the input schema serialized compactly with the file's key order. */
    data class Tool(val name: String, val description: String, val schema: JsonObject) {
        val schemaJson: String get() = schema.toString()
    }

    data class Prompt(
        val availableLine: String,
        val selectedHeader: String,
        val selectedLine: String,
        val notAvailableLine: String,
        val guardrails: String
    )

    data class Errors(
        val unknownRecord: String,
        val notSelected: String,
        val unknownAnalyte: String,
        val badDate: String,
        val dateOrder: String,
        val unavailable: String
    )

    val names: List<String> get() = tools.map { it.name }

    fun tool(name: String): Tool? = tools.firstOrNull { it.name == name }

    companion object {
        const val ASSET_PATH = "records/coach_tools.json"
        const val FORMAT = "ayuvo-records-coach-tools"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): RecordsCoachContract {
            val root = json.parseToJsonElement(text).jsonObject
            require((root["format"] as? JsonPrimitive)?.content == FORMAT) { "not a coach tools file" }
            val tools = root.getValue("tools").jsonArray.map { e ->
                val o = e.jsonObject
                Tool(o.str("name"), o.str("description"), o.getValue("input_schema").jsonObject)
            }
            val p = root.getValue("prompt").jsonObject
            val er = root.getValue("errors").jsonObject
            return RecordsCoachContract(
                tools = tools,
                prompt = Prompt(p.str("available_line"), p.str("selected_header"), p.str("selected_line"), p.str("not_available_line"), p.str("guardrails")),
                errors = Errors(er.str("unknown_record"), er.str("not_selected"), er.str("unknown_analyte"), er.str("bad_date"), er.str("date_order"), er.str("unavailable"))
            )
        }

        private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

        /** Installed by the app container from the bundled asset; tests parse the shared file. */
        @Volatile
        var active: RecordsCoachContract? = null
    }
}
