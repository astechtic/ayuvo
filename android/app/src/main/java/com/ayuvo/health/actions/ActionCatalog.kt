package com.ayuvo.health.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `assets/actions/action_catalog.json`, a byte copy of `shared/actions/action_catalog.json`
 * (docs/actions.md). Every surface (deep links, shortcuts, App Actions, Coach) reads the same ids,
 * parameters and rules from here; `scripts/actions_reference.py` is the normative algorithm.
 */
class ActionCatalog(
    val actions: List<ActionSpec>,
    val enums: Map<String, List<String>>,
    val units: Map<String, UnitFamily>,
    val entities: Map<String, String>
) {
    private val byId = actions.associateBy { it.id }

    fun action(id: String): ActionSpec? = byId[id]

    val coachReadActions: List<ActionSpec> get() = actions.filter { it.coachMode == CoachMode.READ }
    val coachProposeActions: List<ActionSpec> get() = actions.filter { it.coachMode == CoachMode.PROPOSE }

    companion object {
        const val ASSET_PATH = "actions/action_catalog.json"

        @Volatile
        var active: ActionCatalog? = null

        fun parse(text: String): ActionCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            val enums = root.getValue("enums").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            val units = root.getValue("units").jsonObject.mapValues { (_, v) ->
                val o = v.jsonObject
                UnitFamily(
                    canonical = o.str("canonical")!!,
                    enumName = o.str("enum")!!,
                    factors = o.getValue("factors").jsonObject.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 1.0 }
                )
            }
            val entities = root["entities"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val actions = root.getValue("actions").jsonArray.map { parseAction(it.jsonObject) }
            return ActionCatalog(actions, enums, units, entities)
        }

        private fun parseAction(o: JsonObject): ActionSpec {
            val output = o.getValue("output").jsonObject
            val examples = o["examples"]?.jsonObject
            return ActionSpec(
                id = o.str("id")!!,
                kind = ActionKind.fromRaw(o.str("kind")!!),
                domain = o.str("domain")!!,
                title = o.str("title")!!,
                summary = o.str("summary")!!,
                params = o.getValue("params").jsonArray.map { parseParam(it.jsonObject) },
                outputKind = output.str("kind")!!,
                outputEntity = output.str("entity"),
                outputFields = output["fields"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                permissions = o.getValue("permissions").jsonArray.map { it.jsonPrimitive.content },
                confirmation = Confirmation.fromRaw(o.str("confirmation")!!),
                requiresUnlock = o.bool("requires_unlock") ?: true,
                opensApp = o.bool("opens_app") ?: false,
                surfaces = o.getValue("surfaces").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                coachMode = CoachMode.fromRaw(o.str("coach_mode")!!),
                coachTool = o.str("coach_tool"),
                screen = o.str("screen")!!,
                requiresOneOf = o["requires_one_of"]?.jsonArray?.map { g -> g.jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
                aiUnless = o["ai_unless"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                androidExamples = examples?.get("android")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            )
        }

        private fun parseParam(o: JsonObject): ParamSpec {
            val rangeBy = o["range_by"]?.jsonObject
            return ParamSpec(
                name = o.str("name")!!,
                type = ParamType.fromRaw(o.str("type")!!),
                required = o.bool("required") ?: false,
                default = o["default"]?.let(::plain),
                defaultPref = o.str("default_pref"),
                enumName = o.str("enum"),
                entity = o.str("entity"),
                min = o.num("min"),
                max = o.num("max"),
                unitParam = o.str("unit_param"),
                unitFamily = o.str("unit_family"),
                maxLength = o.num("max_length")?.toInt(),
                allowed = o["allowed"]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 },
                rangeByParam = rangeBy?.str("param"),
                rangeByRanges = rangeBy?.get("ranges")?.jsonObject?.mapValues { (_, v) ->
                    val pair = v.jsonArray
                    pair[0].jsonPrimitive.doubleOrNull!! to pair[1].jsonPrimitive.doubleOrNull!!
                },
                summary = o.str("summary").orEmpty()
            )
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        /** JSON → the plain Kotlin values the validator works with (see [ActionValues.fromJson]). */
        private fun plain(e: JsonElement): Any? = ActionValues.fromJson(e)
    }
}

data class UnitFamily(val canonical: String, val enumName: String, val factors: Map<String, Double>)

enum class ActionKind(val raw: String) {
    GET("get"), SET("set"), SEARCH("search"), OPEN("open");

    companion object {
        fun fromRaw(raw: String): ActionKind = entries.first { it.raw == raw }
    }
}

enum class Confirmation(val raw: String) {
    NEVER("never"), ALWAYS("always"), WHEN_AI("when_ai");

    companion object {
        fun fromRaw(raw: String): Confirmation = entries.first { it.raw == raw }
    }
}

enum class CoachMode(val raw: String) {
    NONE("none"), READ("read"), PROPOSE("propose");

    companion object {
        fun fromRaw(raw: String): CoachMode = entries.first { it.raw == raw }
    }
}

enum class ParamType(val raw: String) {
    NUMBER("number"), INTEGER("integer"), STRING("string"), ENUM("enum"), METRIC("metric"), ENTITY("entity");

    companion object {
        fun fromRaw(raw: String): ParamType = entries.first { it.raw == raw }
    }
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val default: Any?,
    val defaultPref: String?,
    val enumName: String?,
    val entity: String?,
    val min: Double?,
    val max: Double?,
    val unitParam: String?,
    val unitFamily: String?,
    val maxLength: Int?,
    val allowed: List<Double>?,
    val rangeByParam: String?,
    val rangeByRanges: Map<String, Pair<Double, Double>>?,
    val summary: String
)

data class ActionSpec(
    val id: String,
    val kind: ActionKind,
    val domain: String,
    val title: String,
    val summary: String,
    val params: List<ParamSpec>,
    val outputKind: String,
    val outputEntity: String?,
    val outputFields: List<String>,
    val permissions: List<String>,
    val confirmation: Confirmation,
    val requiresUnlock: Boolean,
    val opensApp: Boolean,
    val surfaces: Set<String>,
    val coachMode: CoachMode,
    val coachTool: String?,
    val screen: String,
    val requiresOneOf: List<List<String>>,
    val aiUnless: List<String>,
    val androidExamples: List<String>
) {
    fun param(name: String): ParamSpec? = params.firstOrNull { it.name == name }

    /** The catalog `screen` with `{param}` placeholders filled from [params]. */
    fun screenFor(params: Map<String, Any?>): String =
        Regex("\\{([a-z_]+)\\}").replace(screen) { m -> params[m.groupValues[1]]?.toString().orEmpty() }
}

/** Conversions between JSON and the plain values actions use: String, Long, Double, Boolean, List, Map. */
object ActionValues {
    fun fromJson(e: JsonElement): Any? = when (e) {
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.contentOrNull == null -> null
            else -> {
                val c = e.content
                if (c.contains('.') || c.contains('e') || c.contains('E')) c.toDouble() else c.toLongOrNull() ?: c.toDouble()
            }
        }
        is JsonArray -> e.map { fromJson(it) }
        is JsonObject -> e.mapValues { fromJson(it.value) }
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v.toDouble())
        is Number -> JsonPrimitive(v.toDouble())
        is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }
}
