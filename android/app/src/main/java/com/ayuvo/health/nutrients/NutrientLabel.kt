package com.ayuvo.health.nutrients

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One accepted label item: [amount] per ONE dose unit in the canonical [unit]. */
data class LabelItem(val key: String, val amount: Double, val unit: String, val form: String?)

data class LabelRejection(val index: Int, val code: String)

/** `parse_label_output` result; nothing here is saved — the review sheet shows it for confirmation. */
data class LabelParseResult(
    val ok: Boolean,
    val error: String?,
    /** The printed serving size as a number (raw value of the model's `serving_units`), null when rejected. */
    val servingUnits: Double?,
    val items: List<LabelItem>,
    val rejected: List<LabelRejection>
)

/** The four prompt blocks of `shared/nutrients/ai_supplement_label.md`. */
data class LabelPrompts(val cloud: String, val local: String, val userPhoto: String, val userText: String) {
    fun fill(template: String, name: String, strength: String, doseUnit: String): String =
        template.replace("{name}", name.trim()).replace("{strength}", strength.trim()).replace("{dose_unit}", doseUnit)
}

/**
 * AI supplement-label output (docs/nutrients.md §7): the lenient JSON extraction and the item
 * validator of `scripts/nutrients_reference.py`, every item through [Nutrients.convertAmount].
 */
object NutrientLabel {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** First balanced `{...}` (string and escape aware) after stripping a ``` fence; null if absent. */
    fun extractJsonObject(text: String?): String? {
        if (text == null) return null
        var t = text.trim()
        if (t.startsWith("```")) {
            val nl = t.indexOf('\n')
            t = if (nl >= 0) t.substring(nl + 1) else ""
            if (t.trimEnd().endsWith("```")) t = t.trimEnd().dropLast(3)
        }
        val start = t.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until t.length) {
            val c = t[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else if (c == '"') {
                inStr = true
            } else if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) return t.substring(start, i + 1)
            }
        }
        return null
    }

    private fun prim(e: JsonElement?): JsonPrimitive? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }

    /** Python `_is_number`: a JSON number, never a string or a bool. */
    private fun number(e: JsonElement?): Double? {
        val p = prim(e) ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun string(e: JsonElement?): String? = prim(e)?.takeIf { it.isString }?.content

    fun parse(text: String?): LabelParseResult {
        val raw = extractJsonObject(text) ?: return LabelParseResult(false, "parse_error", null, emptyList(), emptyList())
        val doc = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return LabelParseResult(false, "parse_error", null, emptyList(), emptyList())
        }
        val itemsRaw = (doc as? JsonObject)?.get("items") as? JsonArray
            ?: return LabelParseResult(false, "bad_shape", null, emptyList(), emptyList())
        doc as JsonObject
        val servingEl = doc["serving_units"]
        val serving: Double? = if (servingEl == null || servingEl is JsonNull) 1.0 else number(servingEl)
        if (serving == null || serving <= 0) {
            return LabelParseResult(true, null, null, emptyList(), itemsRaw.indices.map { LabelRejection(it, "bad_serving") })
        }
        val ref = NutrientReference.current
        val items = mutableListOf<LabelItem>()
        val rejected = mutableListOf<LabelRejection>()
        val seen = HashSet<String>()
        for ((i, el) in itemsRaw.withIndex()) {
            val it = el as? JsonObject
            val rawKey = it?.let { o -> string(o["key"]) }
            if (it == null || rawKey == null) {
                rejected += LabelRejection(i, "bad_item"); continue
            }
            val formEl = it["form"]
            val form = if (formEl == null || formEl is JsonNull) null else string(formEl)
            if (formEl != null && formEl !is JsonNull && form == null) {
                rejected += LabelRejection(i, "bad_item"); continue
            }
            val key = rawKey.trim()
            if (ref.unitOf(key) == null) {
                rejected += LabelRejection(i, "unknown_nutrient"); continue
            }
            if (key in seen) {
                rejected += LabelRejection(i, "duplicate_nutrient"); continue
            }
            val c = Nutrients.convertAmount(number(it["amount"]), string(it["unit"]), key, form)
            if (!c.ok) {
                rejected += LabelRejection(i, c.error ?: "invalid_amount"); continue
            }
            val amount = Nutrients.roundTo(c.amount!! / serving, Nutrients.AMOUNT_DECIMALS)!!
            if (amount <= 0) {
                rejected += LabelRejection(i, "invalid_amount"); continue
            }
            if (amount > ref.amountPerUnitMax.getValue(c.unit!!)) {
                rejected += LabelRejection(i, "amount_too_large"); continue
            }
            seen += key
            items += LabelItem(key, amount, c.unit, form)
        }
        return LabelParseResult(true, null, serving, items, rejected)
    }

    /** `{cloud, local, user_photo, user_text}` from the fenced blocks under the matching headings. */
    fun parsePrompts(text: String): LabelPrompts {
        fun block(heading: String): String {
            val i = text.indexOf(heading).also { require(it >= 0) { "missing $heading" } }
            val a = text.indexOf("```\n", i).also { require(it >= 0) } + 4
            val b = text.indexOf("\n```", a).also { require(it >= 0) }
            return text.substring(a, b)
        }
        return LabelPrompts(
            cloud = block("## System prompt (cloud)"),
            local = block("## System prompt (compact, on-device)"),
            userPhoto = block("## User template (label photo)"),
            userText = block("## User template (name and strength)")
        )
    }

    /** Installed at app start from the bundled prompt asset. */
    @Volatile
    var prompts: LabelPrompts? = null
}
