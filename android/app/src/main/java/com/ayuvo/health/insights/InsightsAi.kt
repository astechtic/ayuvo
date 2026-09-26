package com.ayuvo.health.insights

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * "Explain with AI" (docs/insights.md §5, shared/insights/ai_explain.md): the compact payload of
 * derived values only, the prompt filled by plain string replacement, and the validator that
 * rejects any answer with an unknown number, a blocked term or the wrong shape. Ported from
 * `ai_payload`, `build_prompt` and `validate_ai_output`; the call itself lives in [InsightsExplainer].
 */
object InsightsAi {
    val KINDS = listOf("recovery", "health_age", "daily_review")
    private val NUMBER = Regex("[0-9]+(,[0-9][0-9][0-9])*([.][0-9]+)?")

    data class Prompts(val cloud: String, val local: String, val user: String)
    data class Prompt(val system: String, val user: String)
    data class Output(val headline: String, val bullets: List<String>)
    data class Validation(val ok: Boolean, val errors: List<String>, val output: Output?)

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = MedicationJson.obj(*pairs)
    private fun JsonObject.status(): String? = str("status")

    /** Only derived values: no raw samples, timestamps, dates or names. */
    fun payload(summary: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        summary.objOrNull("recovery")?.let { r ->
            out["recovery"] = if (r.status() == "ok") {
                val signals = (r.arr("positives").orEmpty() + r.arr("negatives").orEmpty()).map {
                    val s = it as JsonObject
                    obj("text" to s["text"], "impact" to s["impact"])
                }
                obj(
                    "status" to "ok", "score" to r["score"], "label" to r["label_text"], "confidence" to r["confidence"],
                    "training_load" to r.objOrNull("load")?.get("label"), "signals" to signals
                )
            } else {
                obj("status" to r["status"], "collecting" to r["collecting"])
            }
        }
        summary.objOrNull("health_age")?.let { h ->
            out["health_age"] = if (h.status() == "ok") {
                val sec = linkedMapOf<String, Any?>(
                    "status" to "ok", "actual_age" to h["actual_age"], "health_age" to h["health_age"],
                    "difference" to h["difference"], "confidence" to h["confidence"],
                    "markers" to h.arr("markers").orEmpty().map { it as JsonObject }.filter { MedicationJson.truthy(it["available"]) }.map {
                        obj("id" to it["id"], "offset_years" to it["offset_years"], "contribution_years" to it["contribution_years"])
                    }
                )
                summary.objOrNull("health_age_pace")?.takeIf { it.status() == "ok" }?.let { p ->
                    sec["pace"] = p["pace"]
                    sec["pace_direction"] = p["direction"]
                }
                MedicationJson.element(sec)
            } else {
                obj("status" to h["status"], "collecting" to h["collecting"])
            }
        }
        summary.objOrNull("daily_review")?.let { v ->
            val sec = linkedMapOf<String, Any?>(
                "day_score" to v["day_score"],
                "areas" to v.arr("areas").orEmpty().map { it as JsonObject }.filter { MedicationJson.truthy(it["included"]) }.map {
                    obj("id" to it["id"], "score" to it["score"])
                },
                "not_logged" to v.arr("not_logged").orEmpty().map { ((it as JsonObject).objOrNull("params") ?: JsonObject(emptyMap()))["area"] }
            )
            for (c in DailyReviewResult.CATEGORIES) sec[c] = v.arr(c).orEmpty().map { (it as JsonObject)["text"] }
            out["daily_review"] = MedicationJson.element(sec)
        }
        out["patterns"] = JsonArray((summary["patterns"] as? JsonArray).orEmpty().map { it as JsonObject }
            .filter { MedicationJson.truthy(it["surfaced"]) }
            .map { obj("id" to it["id"], "text" to it["text"], "n_exposed" to it["n_exposed"], "n_unexposed" to it["n_unexposed"]) })
        return JsonObject(out)
    }

    /** The fenced blocks of `ai_explain.md` under their `## ` headings. */
    fun parsePrompts(text: String): Prompts {
        val blocks = HashMap<String, String>()
        var heading: String? = null
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("## ")) {
                heading = line.substring(3).trim()
            } else if (line.startsWith("```") && heading != null) {
                var j = i + 1
                while (!lines[j].startsWith("```")) j++
                blocks[heading] = lines.subList(i + 1, j).joinToString("\n")
                i = j
            }
            i++
        }
        return Prompts(
            cloud = blocks.getValue("System prompt (cloud)"),
            local = blocks.getValue("System prompt (compact, on-device)"),
            user = blocks.getValue("User template")
        )
    }

    /** `{task}` first, then `{payload}`, each by plain replacement so payload text is never re-substituted. */
    fun buildPrompt(kind: String, payload: JsonObject, variant: String, cfg: InsightsConfig, prompts: Prompts): Prompt {
        require(kind in KINDS && (variant == "cloud" || variant == "local")) { "bad kind/variant" }
        val section = LinkedHashMap<String, JsonElement>()
        section[kind] = payload[kind] ?: JsonNull
        if (kind == "daily_review") section["patterns"] = payload["patterns"] ?: JsonArray(emptyList())
        val user = prompts.user.replace("{task}", cfg.ai.tasks.getValue(kind)).replace("{payload}", InsightsJson.canonical(JsonObject(section)))
        return Prompt(if (variant == "cloud") prompts.cloud else prompts.local, user)
    }

    /** Strips a ``` fence when present, then returns the first balanced `{…}` (string and escape aware). */
    fun extractJsonObject(text: String): String? {
        var t = text
        val f = t.indexOf("```")
        if (f >= 0) {
            val nl = t.indexOf('\n', f)
            val end = if (nl >= 0) t.indexOf("```", nl + 1) else -1
            if (nl >= 0 && end >= 0) t = t.substring(nl + 1, end)
        }
        val start = t.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until t.length) {
            val ch = t[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
            } else if (ch == '"') {
                inStr = true
            } else if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) return t.substring(start, i + 1)
            }
        }
        return null
    }

    private fun normNumber(token: String): String = InsightsJson.canonicalNumber(InsightsMath.roundTo(token.replace(",", "").toDouble(), 2))

    private fun payloadNumbers(e: JsonElement, out: MutableSet<String>) {
        when (e) {
            is JsonNull -> Unit
            is JsonPrimitive -> when {
                e.isString -> NUMBER.findAll(e.content).forEach { out += normNumber(it.value) }
                e.booleanOrNull != null -> Unit
                else -> e.doubleOrNull?.let { x -> for (d in intArrayOf(2, 1, 0)) out += InsightsJson.canonicalNumber(InsightsMath.roundTo(kotlin.math.abs(x), d)) }
            }
            is JsonArray -> e.forEach { payloadNumbers(it, out) }
            is JsonObject -> e.values.forEach { payloadNumbers(it, out) }
        }
    }

    private val strictJson = Json { isLenient = false }

    /** `{ok, errors, output}`: the explanation is shown only when ok, otherwise the deterministic text is. */
    fun validate(text: String?, payload: JsonObject, cfg: InsightsConfig): Validation {
        val a = cfg.ai
        val raw = extractJsonObject(text.orEmpty())
        val parsed = raw?.let { runCatching { strictJson.parseToJsonElement(it) }.getOrNull() }
        if (parsed !is JsonObject) return Validation(false, listOf("parse_error"), null)
        val head = (parsed["headline"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val bulletsEl = parsed["bullets"] as? JsonArray
        val bulletsOk = bulletsEl != null && bulletsEl.all { it is JsonPrimitive && it.isString }
        if (head == null || !bulletsOk) return Validation(false, listOf("bad_shape"), null)
        val headline = head.trim()
        val bullets = bulletsEl!!.map { (it as JsonPrimitive).content.trim() }
        val errors = ArrayList<String>()
        if (headline.length !in 1..a.headlineMax) errors += "headline_length"
        if (bullets.size !in 1..a.bulletsMax) errors += "bullet_count"
        if (bullets.any { it.length !in 1..a.bulletMax }) errors += "bullet_length"
        val allowed = HashSet<String>()
        a.allowedNumbers.forEach { allowed += InsightsJson.canonicalNumber(it) }
        payloadNumbers(payload, allowed)
        if ((listOf(headline) + bullets).any { s -> NUMBER.findAll(s).any { normNumber(it.value) !in allowed } }) errors += "unknown_number"
        val lower = (listOf(headline) + bullets).joinToString(" ").lowercase(Locale.ROOT)
        if (a.blockedTerms.any { it in lower }) errors += "blocked_term"
        return if (errors.isEmpty()) Validation(true, errors, Output(headline, bullets)) else Validation(false, errors, null)
    }
}
