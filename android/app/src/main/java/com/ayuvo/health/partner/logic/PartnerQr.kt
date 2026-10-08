package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A parsed pairing QR (docs/partner-sync.md §4); carries no health data. */
data class QrPayload(
    val deviceId: String,
    val name: String,
    val x25519: String,
    val ed25519: String,
    val token: String,
    val expMs: Long,
    val hosts: List<String>,
    val fingerprint: String
) {
    fun toJson(): JsonObject = PartnerJson.obj(
        "device_id" to deviceId, "name" to name, "x25519" to x25519, "ed25519" to ed25519, "token" to token,
        "exp_ms" to expMs, "hosts" to hosts, "fingerprint" to fingerprint
    )
}

data class QrParseResult(val payload: QrPayload?, val error: String?) {
    val ok: Boolean get() = payload != null

    fun toJson(): JsonObject =
        if (payload != null) PartnerJson.obj("ok" to true, "payload" to payload.toJson())
        else PartnerJson.obj("ok" to false, "error" to error)
}

object PartnerQr {
    val QR_FIELDS = listOf("protocol", "v", "device_id", "name", "x25519", "ed25519", "token", "exp_ms", "hosts")

    internal val RE_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val RE_HOST = Regex("[0-9a-fA-F.:%a-z]+:[0-9]{1,5}")

    /** Trim, collapse whitespace, cap at name_max code points; empty becomes "Ayuvo". */
    fun cleanName(name: String?): String {
        if (name == null) return "Ayuvo"
        val s = PartnerJson.asciiRstrip(PartnerJson.pyTake(PartnerJson.splitAsciiSpace(name).joinToString(" "), PartnerCatalog.current.nameMax))
        return s.ifEmpty { "Ayuvo" }
    }

    fun cleanName(name: JsonElement?): String = cleanName(PartnerJson.str(name))

    /** Canonical QR text: prefix + base64url(compact JSON with sorted keys). */
    fun encode(payload: JsonObject): String {
        val body = LinkedHashMap<String, JsonElement>()
        for (k in QR_FIELDS) payload[k]?.let { body[k] = it }
        body["name"] = PartnerJson.of(cleanName(body["name"]))
        val text = PartnerJson.dumpsCompactSorted(JsonObject(body))
        return PartnerCatalog.current.qrPrefix + PartnerKdf.b64urlEncode(text.toByteArray(Charsets.UTF_8))
    }

    /** Builds the QR text this device shows. */
    fun encode(deviceId: String, name: String, x25519: String, ed25519: String, token: String, expMs: Long, hosts: List<String>): String {
        val c = PartnerCatalog.current
        return encode(
            PartnerJson.obj(
                "protocol" to c.protocolId, "v" to c.protocolVersion, "device_id" to deviceId, "name" to name,
                "x25519" to x25519, "ed25519" to ed25519, "token" to token, "exp_ms" to expMs, "hosts" to hosts.take(8)
            )
        )
    }

    /** Codes: not_ayuvo, unsupported_version, malformed, bad_key, self, expired. */
    fun parse(text: String?, nowMs: Long, selfDeviceId: String?): QrParseResult {
        fun fail(code: String) = QrParseResult(null, code)
        val c = PartnerCatalog.current
        if (text == null) return fail("not_ayuvo")
        val t = PartnerJson.asciiStrip(text)
        if (!t.startsWith("ayuvo-partner:")) return fail("not_ayuvo")
        if (!t.startsWith(c.qrPrefix)) return fail("unsupported_version")
        val raw = PartnerKdf.b64urlDecode(t.substring(c.qrPrefix.length)) ?: return fail("malformed")
        val decoded = PartnerJson.decodeUtf8(raw) ?: return fail("malformed")
        val obj = runCatching { PartnerJson.parse(decoded) }.getOrNull() as? JsonObject ?: return fail("malformed")
        if (PartnerJson.str(obj["protocol"]) != c.protocolId) return fail("malformed")
        if (!PartnerJson.isIntEqual(obj["v"], c.protocolVersion)) return fail("unsupported_version")
        val dev = PartnerJson.str(obj["device_id"])
        if (dev == null || !PartnerJson.pyFullMatch(RE_UUID, dev)) return fail("malformed")
        val exp = PartnerJson.long(obj["exp_ms"]) ?: return fail("malformed")
        val hostsEl = if (obj.containsKey("hosts")) obj["hosts"] else JsonArray(emptyList())
        if (hostsEl !is JsonArray || hostsEl.size > 8 ||
            hostsEl.any { h -> PartnerJson.str(h)?.let { PartnerJson.pyFullMatch(RE_HOST, it) } != true }
        ) return fail("malformed")
        for (key in listOf("x25519", "ed25519", "token")) {
            val b = PartnerKdf.b64urlDecode(PartnerJson.str(obj[key]))
            if (b == null || b.size != 32) return fail("bad_key")
        }
        if (dev == selfDeviceId) return fail("self")
        if (nowMs > exp || exp - nowMs > c.qrTtlMs) return fail("expired")
        val x = PartnerJson.str(obj["x25519"])!!
        val e = PartnerJson.str(obj["ed25519"])!!
        return QrParseResult(
            QrPayload(
                deviceId = dev, name = cleanName(obj["name"]), x25519 = x, ed25519 = e,
                token = PartnerJson.str(obj["token"])!!, expMs = exp, hosts = hostsEl.map { PartnerJson.str(it)!! },
                fingerprint = PartnerKdf.fingerprint(x, e)
            ),
            null
        )
    }
}
