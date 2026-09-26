package com.ayuvo.health.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** A write Coach suggested; nothing changes until the user taps Confirm on its card. */
data class ActionProposal(
    val actionId: String,
    val title: String,
    val params: Map<String, Any?>,
    val lines: List<String>,
    val ai: Boolean,
    val id: String = UUID.randomUUID().toString()
)

/**
 * Coach's view of the action catalog (docs/actions.md, AI Coach): `coach_mode: read` actions are
 * tools named by their `coach_tool`, run through the same [ActionExecutor] as every other surface
 * with [ActionSource.COACH]; `coach_mode: propose` actions are reachable only through
 * [PROPOSE_TOOL], which validates and records an [ActionProposal] without writing anything.
 * Medication and goal changes are never proposable (catalog rule, checked by the contract).
 */
class CoachActionTools(private val executor: ActionExecutor) {
    private val catalog = executor.catalog
    private val readByTool = catalog.coachReadActions.associateBy { it.coachTool!! }

    /** Proposals collected during one Coach turn, in call order. */
    val proposals: MutableList<ActionProposal> = mutableListOf()

    val names: List<String> = readByTool.keys.toList() + PROPOSE_TOOL

    fun handles(name: String): Boolean = name in readByTool || name == PROPOSE_TOOL

    fun description(name: String): String? = when (name) {
        PROPOSE_TOOL -> "Suggest a change to the user's Ayuvo log (for example logging water, a weight, a food with known calories, a workout set, or starting or ending a fast). " +
            "It does NOT change anything: the user sees a card and decides. Only call it when the user asked for the change. " +
            "action_id is one of: " + catalog.coachProposeActions.joinToString("; ") { "${it.id} (${it.summary})" } +
            ". params_json is a JSON object of that action's parameters: " +
            catalog.coachProposeActions.joinToString("; ") { a -> "${a.id}: " + a.params.joinToString(", ") { "${it.name} (${it.summary})" } }
        else -> readByTool[name]?.let { "${it.summary} (Ayuvo action ${it.id})" }
    }

    /** JSON schema text for [name]'s parameters. */
    fun schemaJson(name: String): String? {
        if (name == PROPOSE_TOOL) {
            val ids = catalog.coachProposeActions.map { it.id }
            return ActionValues.toJson(linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "action_id" to linkedMapOf("type" to "string", "enum" to ids, "description" to "The Ayuvo action to propose."),
                    "params_json" to linkedMapOf("type" to "string", "description" to "The action's parameters as a JSON object, e.g. {\"amount\": 500, \"unit\": \"ml\"}.")
                ),
                "required" to listOf("action_id")
            )).toString()
        }
        val spec = readByTool[name] ?: return null
        val props = LinkedHashMap<String, Any?>()
        for (p in spec.params) {
            val prop = LinkedHashMap<String, Any?>()
            when (p.type) {
                ParamType.NUMBER -> prop["type"] = "number"
                ParamType.INTEGER -> prop["type"] = "integer"
                ParamType.ENUM -> { prop["type"] = "string"; prop["enum"] = catalog.enums[p.enumName].orEmpty() }
                else -> prop["type"] = "string"
            }
            if (p.min != null && p.unitParam == null) prop["minimum"] = p.min
            if (p.max != null && p.unitParam == null) prop["maximum"] = p.max
            prop["description"] = p.summary
            props[p.name] = prop
        }
        val schema = linkedMapOf<String, Any?>("type" to "object", "properties" to props)
        spec.params.filter { it.required }.map { it.name }.takeIf { it.isNotEmpty() }?.let { schema["required"] = it }
        return ActionValues.toJson(schema).toString()
    }

    suspend fun execute(name: String, args: Map<String, Any?>): String {
        if (name == PROPOSE_TOOL) return propose(args)
        val spec = readByTool[name] ?: return error("unknown_tool")
        return try {
            executor.perform(ActionRequest(spec.id, args.filterValues { it != null }, ActionSource.COACH)).toJson().toString()
        } catch (e: ActionException) {
            error(e.code.raw, e.param)
        }
    }

    private suspend fun propose(args: Map<String, Any?>): String {
        val id = args["action_id"] as? String ?: return error("missing_param", "action_id")
        val params: Map<String, Any?> = when (val raw = args["params_json"] ?: args["params"]) {
            null -> emptyMap()
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
            is String -> if (raw.isBlank()) emptyMap() else runCatching {
                @Suppress("UNCHECKED_CAST")
                ActionValues.fromJson(Json.parseToJsonElement(raw) as JsonObject) as Map<String, Any?>
            }.getOrElse { return error("bad_type", "params_json") }
            else -> return error("bad_type", "params_json")
        }
        val spec = catalog.action(id)
        if (spec == null || spec.coachMode != CoachMode.PROPOSE) return error("not_allowed")
        return when (val v = executor.validate(ActionRequest(id, params, ActionSource.COACH))) {
            is ValidationResult.Failed -> error(v.code.raw, v.param)
            is ValidationResult.Ok -> {
                val proposal = ActionProposal(id, spec.title, v.action.params, ActionResultText.paramLines(spec, v.action.params), v.action.ai)
                proposals += proposal
                ActionValues.toJson(linkedMapOf(
                    "ok" to true,
                    "status" to "proposed",
                    "note" to "Nothing was changed. The user now sees a card with Confirm and Dismiss; tell them to review it."
                )).toString()
            }
        }
    }

    private fun error(code: String, param: String? = null): String =
        ActionValues.toJson(linkedMapOf("ok" to false, "error" to code, "param" to param)).toString()

    companion object {
        const val PROPOSE_TOOL = "propose_action"
    }
}
