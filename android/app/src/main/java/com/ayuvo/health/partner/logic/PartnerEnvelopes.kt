package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class EnvelopeCheck(val ok: Boolean, val error: String?, val category: String?) {
    fun toJson(): JsonObject = PartnerJson.obj("ok" to ok, "error" to error, "category" to category)
}

/** docs/partner-sync.md §7: record envelopes `{type, id, category, rev, deleted, updated_ms, day?, data?}`. */
object PartnerEnvelopes {
    internal val RE_DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** Category of a record; health-derived types take it from the type_id allow-list (null = not shareable). */
    fun categoryOf(type: String, data: JsonObject?): String? {
        val c = PartnerCatalog.current
        val spec = c.types[type] ?: return null
        if (spec.category != "health") return spec.category
        val tid = PartnerJson.str(data?.get("type_id")) ?: return null
        val cat = c.healthTypeCategory[tid] ?: return null
        if (type == "sample" && tid !in c.intradayTypes) return null
        if (type == "metric_hour" && tid !in c.hourlyTypes) return null
        return cat
    }

    private fun hasForbidden(value: JsonElement, forbidden: Set<String>): Boolean = when (value) {
        is JsonObject -> value.entries.any { (k, v) -> k in forbidden || hasForbidden(v, forbidden) }
        is JsonArray -> value.any { hasForbidden(it, forbidden) }
        else -> false
    }

    private fun tooBig(value: JsonElement, c: PartnerCatalog): Boolean = when (value) {
        is JsonObject -> value.values.any { tooBig(it, c) }
        is JsonArray -> value.size > c.arrayMax || value.any { tooBig(it, c) }
        is JsonPrimitive -> value.isString && PartnerJson.codePointLength(value.content) > c.stringMax
    }

    /** Codes: malformed, unknown_type, not_shareable, category_mismatch, missing_field, bad_day, forbidden_field, too_large. */
    @Suppress("UNUSED_PARAMETER")
    fun validate(env: JsonElement?, nowMs: Long): EnvelopeCheck {
        val c = PartnerCatalog.current
        fun fail(code: String) = EnvelopeCheck(false, code, null)
        if (env !is JsonObject) return fail("malformed")
        val type = PartnerJson.str(env["type"])
        val id = PartnerJson.str(env["id"])
        val rev = PartnerJson.long(env["rev"])
        if (type == null || id == null || id.isEmpty() || PartnerJson.codePointLength(id) > 200 || rev == null || rev < 1) return fail("malformed")
        if (!PartnerJson.isBool(env["deleted"]) || !PartnerJson.isInt(env["updated_ms"])) return fail("malformed")
        val spec = c.types[type] ?: return fail("unknown_type")
        val claimed = PartnerJson.str(env["category"])
        if (PartnerJson.bool(env["deleted"]) == true) {
            // Tombstones carry no data; the category is taken as claimed but must be a known one.
            if (claimed == null || claimed !in c.categories || (spec.category != "health" && spec.category != claimed)) {
                return fail("category_mismatch")
            }
            return EnvelopeCheck(true, null, claimed)
        }
        val data = env["data"] as? JsonObject ?: return fail("malformed")
        val cat = categoryOf(type, data) ?: return fail("not_shareable")
        if (claimed != cat) return fail("category_mismatch")
        for (f in spec.required) if (PartnerJson.isNone(data[f])) return fail("missing_field")
        if (hasForbidden(data, c.forbiddenKeys)) return fail("forbidden_field")
        if (tooBig(data, c)) return fail("too_large")
        val day = env["day"]
        if (spec.day) {
            val d = PartnerJson.str(day)
            if (d == null || !PartnerJson.pyFullMatch(RE_DAY, d)) return fail("bad_day")
        } else if (!PartnerJson.isNone(day)) {
            return fail("bad_day")
        }
        return EnvelopeCheck(true, null, cat)
    }

    /** The type's primary instant (`ts` field of record_types.json) when the envelope carries data. */
    fun ts(env: JsonObject): Long? {
        val spec = PartnerJson.str(env["type"])?.let { PartnerCatalog.current.types[it] } ?: return null
        val field = spec.ts ?: return null
        if (PartnerJson.truthy(env["deleted"])) return null
        val data = env["data"] as? JsonObject ?: return null
        return PartnerJson.long(data[field])
    }
}
