package com.ayuvo.health.actions

/** Where a request came from; `raw` is the catalog `surfaces` name (docs/actions.md). */
enum class ActionSource(val raw: String) {
    APP("app"),
    SIRI("siri"),
    SHORTCUTS("shortcuts"),
    ANDROID("android"),
    DEEPLINK("deeplink"),
    COACH("coach");

    /** True for requests that arrive through an exported entry point (any app can send those). */
    val isExternal: Boolean get() = this == ANDROID || this == DEEPLINK
}

/** One unvalidated request: catalog id plus raw parameter values (strings from intents, JSON values from Coach). */
data class ActionRequest(
    val id: String,
    val params: Map<String, Any?> = emptyMap(),
    val source: ActionSource = ActionSource.APP,
    /** Distinguishes repeated identical requests delivered to the UI. */
    val nonce: Long = System.nanoTime()
)

/** Validation and runtime error codes. The first ten mirror `scripts/actions_reference.py`. */
enum class ActionErrorCode(val raw: String) {
    UNKNOWN_ACTION("unknown_action"),
    NOT_ALLOWED("not_allowed"),
    UNKNOWN_PARAM("unknown_param"),
    MISSING_PARAM("missing_param"),
    BAD_TYPE("bad_type"),
    BAD_ENUM("bad_enum"),
    BAD_VALUE("bad_value"),
    TOO_LONG("too_long"),
    OUT_OF_RANGE("out_of_range"),
    REQUIRES_ONE_OF("requires_one_of"),
    PERMISSION_REQUIRED("permission_required"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    UNAVAILABLE("unavailable"),
    AI_FAILED("ai_failed");

    companion object {
        fun fromRaw(raw: String): ActionErrorCode? = entries.firstOrNull { it.raw == raw }
    }
}

class ActionException(
    val code: ActionErrorCode,
    val param: String? = null,
    /** Optional detail for the user (an AI provider message, a conflicting state). Never a health value. */
    val detail: String? = null
) : Exception("${code.raw}${param?.let { " ($it)" } ?: ""}${detail?.let { ": $it" } ?: ""}")

/** A request that passed validation: normalised typed params (String / Long / Double) and the confirm rule. */
data class ValidatedAction(
    val spec: ActionSpec,
    val params: Map<String, Any?>,
    val source: ActionSource,
    val confirm: Boolean,
    val ai: Boolean
) {
    fun string(name: String): String? = params[name] as? String
    fun number(name: String): Double? = (params[name] as? Number)?.toDouble()
    fun int(name: String): Int? = (params[name] as? Number)?.toInt()
}

sealed interface ValidationResult {
    data class Ok(val action: ValidatedAction) : ValidationResult
    data class Failed(val code: ActionErrorCode, val param: String?) : ValidationResult
}

/**
 * A structured action result. [fields] follow the catalog output field names (units in the names:
 * `_ml`, `_kg`, `_s`, `_ms`); [items] carries list outputs. Text for people is built separately
 * ([ActionResultText]) so the same result feeds Coach JSON, shortcuts and the in-app snackbar.
 */
data class ActionResult(
    val actionId: String,
    val fields: Map<String, Any?> = emptyMap(),
    val items: List<Map<String, Any?>> = emptyList(),
    /** Resolved screen to show after the action (catalog `screen` with params filled in). */
    val screen: String? = null
) {
    fun toJson(): kotlinx.serialization.json.JsonObject {
        val out = LinkedHashMap<String, Any?>()
        out["action"] = actionId
        out.putAll(fields)
        if (items.isNotEmpty() || fields.isEmpty()) out["items"] = items
        return ActionValues.toJson(out) as kotlinx.serialization.json.JsonObject
    }
}

/** "Ask Ayuvo Coach" with a question typed in for the user to send (never sent automatically). */
data class CoachPromptRequest(val prompt: String, val id: Long = System.nanoTime())
