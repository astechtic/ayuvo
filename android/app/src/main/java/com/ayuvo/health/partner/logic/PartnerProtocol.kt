package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MessageCheck(val ok: Boolean, val error: String?) {
    fun toJson(): JsonObject = PartnerJson.obj("ok" to ok, "error" to error)
}

data class SessionCheck(val ok: Boolean, val error: String?, val steps: Int) {
    fun toJson(): JsonObject = PartnerJson.obj("ok" to ok, "error" to error, "steps" to steps)
}

/** docs/partner-sync.md §12: protocol v1 messages and the inbound session order. */
object PartnerProtocol {

    private fun knownCategories(e: JsonElement?, categories: List<String>): Boolean =
        e is JsonArray && e.all { PartnerJson.str(it)?.let { s -> s in categories } == true }

    /** Codes: malformed, unknown_message, unsupported_version. */
    fun validate(msg: JsonElement?): MessageCheck {
        val c = PartnerCatalog.current
        fun fail(code: String) = MessageCheck(false, code)
        if (msg !is JsonObject) return fail("malformed")
        val t = PartnerJson.str(msg["t"]) ?: return fail("malformed")
        val required = c.messages[t] ?: return fail("unknown_message")
        for (f in required) if (!msg.containsKey(f)) return fail("malformed")
        when (t) {
            "HELLO" -> {
                if (PartnerJson.str(msg["protocol"]) != c.protocolId) return fail("malformed")
                if (!PartnerJson.isIntEqual(msg["v"], c.protocolVersion)) return fail("unsupported_version")
                if (!knownCategories(msg["grants"], c.categories)) return fail("malformed")
            }
            "SYNC_REQ" -> {
                val cursor = PartnerJson.long(msg["cursor"])
                if (cursor == null || cursor < 0) return fail("malformed")
            }
            "CHANGES" -> {
                val from = PartnerJson.long(msg["from_rev"])
                val to = PartnerJson.long(msg["to_rev"])
                val records = msg["records"]
                if (from == null || to == null || to < from || !PartnerJson.isBool(msg["has_more"]) ||
                    records !is JsonArray || records.size > c.batchMax
                ) return fail("malformed")
            }
            "ACK" -> if (!PartnerJson.isInt(msg["committed_rev"])) return fail("malformed")
            "PAIR_CONFIRM" -> {
                if (!PartnerJson.isBool(msg["accepted"])) return fail("malformed")
                if (PartnerJson.bool(msg["accepted"]) == true) {
                    // The initiator's identity is only known from here (its X25519 key came in the IK handshake).
                    val dev = PartnerJson.str(msg["device_id"])
                    val key = PartnerKdf.b64urlDecode(PartnerJson.str(msg["ed25519"]))
                    if (dev == null || !PartnerJson.pyFullMatch(PartnerQr.RE_UUID, dev) || key == null || key.size != 32 ||
                        !PartnerJson.isString(msg["name"]) || !knownCategories(msg["grants"], c.categories)
                    ) return fail("malformed")
                }
            }
        }
        return MessageCheck(true, null)
    }

    /**
     * `session_script`: replays the inbound message types of one session. HELLO first; at most one SYNC_REQ; ACK
     * only after the peer's SYNC_REQ; CHANGES only until a page with has_more=false; DONE only after that last
     * page; nothing after DONE; ERROR ends the session. ok also requires a complete pull and the peer's DONE.
     */
    fun sessionScript(inbound: List<JsonObject>): SessionCheck {
        var hello = false
        var syncReq = false
        var done = false
        var pulling = true
        var steps = 0
        for (m in inbound) {
            val t = PartnerJson.str(m["t"])
            if (t == "ERROR") {
                val code = m["code"]
                val err = if (PartnerJson.truthy(code)) (code as? JsonPrimitive)?.content ?: code.toString() else "internal"
                return SessionCheck(false, err, steps)
            }
            val legal = (t == "HELLO" && !hello) || (hello && !done && (
                (t == "SYNC_REQ" && !syncReq) || (t == "ACK" && syncReq) || (t == "CHANGES" && pulling) ||
                    (t == "DONE" && !pulling)))
            if (!legal) return SessionCheck(false, "malformed", steps)
            when (t) {
                "HELLO" -> hello = true
                "SYNC_REQ" -> syncReq = true
                "CHANGES" -> if (!PartnerJson.truthy(m["has_more"])) pulling = false
                "DONE" -> done = true
            }
            steps++
        }
        val ok = hello && !pulling && done
        return SessionCheck(ok, if (ok) null else "incomplete", steps)
    }
}
