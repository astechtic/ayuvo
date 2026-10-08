package com.ayuvo.health.partner.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * JSON helpers that reproduce the Python reference's value semantics (scripts/partner_reference.py):
 * `_is_int` / `_is_num`, truthiness, ASCII-only whitespace trimming/collapsing, whole-string regex matching,
 * code-point string lengths and ordering,
 * and `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)`.
 */
object PartnerJson {
    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    // -- type tests (Python isinstance) ------------------------------------------------------------

    fun isString(e: JsonElement?): Boolean = e is JsonPrimitive && e !is JsonNull && e.isString

    fun isBool(e: JsonElement?): Boolean =
        e is JsonPrimitive && e !is JsonNull && !e.isString && (e.content == "true" || e.content == "false")

    /** `_is_int`: a JSON integer literal (no fraction or exponent), never a bool. */
    fun isInt(e: JsonElement?): Boolean {
        if (e !is JsonPrimitive || e is JsonNull || e.isString || isBool(e)) return false
        val c = e.content
        if (c.isEmpty() || c.any { it == '.' || it == 'e' || it == 'E' }) return false
        return c.toLongOrNull() != null
    }

    /** `_is_num`: any JSON number, never a bool. */
    fun isNum(e: JsonElement?): Boolean =
        e is JsonPrimitive && e !is JsonNull && !e.isString && !isBool(e) && e.doubleOrNull != null

    fun str(e: JsonElement?): String? = if (isString(e)) (e as JsonPrimitive).content else null
    fun long(e: JsonElement?): Long? = if (isInt(e)) (e as JsonPrimitive).content.toLong() else null
    fun double(e: JsonElement?): Double? = if (isNum(e)) (e as JsonPrimitive).doubleOrNull else null
    fun bool(e: JsonElement?): Boolean? = if (isBool(e)) (e as JsonPrimitive).booleanOrNull else null

    /** Python `x is None` for `dict.get`: missing key or JSON null. */
    fun isNone(e: JsonElement?): Boolean = e == null || e is JsonNull

    /** Python truthiness of a JSON value. */
    fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
        is JsonPrimitive -> when {
            e.isString -> e.content.isNotEmpty()
            isBool(e) -> e.content == "true"
            else -> (e.doubleOrNull ?: 0.0) != 0.0
        }
    }

    /** Reference `_is_int(v) and v == n` (version fields): a real JSON integer, never a bool or a float. */
    fun isIntEqual(e: JsonElement?, n: Int): Boolean = long(e) == n.toLong()

    // -- strings ----------------------------------------------------------------------------------

    fun codePoints(s: String): IntArray = s.codePoints().toArray()

    fun codePointLength(s: String): Int = s.codePointCount(0, s.length)

    fun fromCodePoints(cps: IntArray, from: Int = 0, to: Int = cps.size): String = String(cps, from, to - from)

    /** `s.strip(" \t\n\r\x0b\x0c")`: ASCII whitespace only; NBSP and other Unicode spaces are never trimmed. */
    fun asciiStrip(s: String): String {
        val cps = codePoints(s)
        var a = 0
        var b = cps.size
        while (a < b && isAsciiSpace(cps[a])) a++
        while (b > a && isAsciiSpace(cps[b - 1])) b--
        return fromCodePoints(cps, a, b)
    }

    /** `s.rstrip(" \t\n\r\x0b\x0c")`. */
    fun asciiRstrip(s: String): String {
        val cps = codePoints(s)
        var b = cps.size
        while (b > 0 && isAsciiSpace(cps[b - 1])) b--
        return fromCodePoints(cps, 0, b)
    }

    /** Reference clean_name split: runs of ASCII whitespace `[ \t\n\r\x0b\x0c]` only; other spaces are kept. */
    fun isAsciiSpace(cp: Int): Boolean = cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D || cp == 0x0B || cp == 0x0C

    /** `[p for p in re.split("[ \t\n\r\x0b\x0c]+", s) if p]`. */
    fun splitAsciiSpace(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (cp in codePoints(s)) {
            if (isAsciiSpace(cp)) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
            } else cur.appendCodePoint(cp)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Python `s[:n]` (code points). */
    fun pyTake(s: String, n: Int): String {
        val cps = codePoints(s)
        return if (cps.size <= n) s else fromCodePoints(cps, 0, n)
    }

    /** Reference `_full`: whole-string match (no `$`-before-trailing-newline allowance). */
    fun pyFullMatch(re: Regex, s: String): Boolean = re.matches(s)

    /** Strict UTF-8 decode (Python `bytes.decode("utf-8")`); null on invalid input. */
    fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Python `str` ordering: Unicode scalar values, which equals UTF-8 byte order (never UTF-16 units). */
    fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        val aDone = i >= a.length
        val bDone = j >= b.length
        return when {
            aDone && bDone -> 0
            aDone -> -1
            else -> 1
        }
    }

    val CODE_POINT_ORDER: Comparator<String> = Comparator(::compareCodePoints)

    // -- building ---------------------------------------------------------------------------------

    fun of(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to of(v) })
        is Iterable<*> -> JsonArray(value.map(::of))
        else -> error("unsupported JSON value ${value.javaClass}")
    }

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*pairs).mapValues { of(it.value) })

    /** Python `_drop_none`: keeps only keys whose value is present and not null. */
    fun dropNone(vararg pairs: Pair<String, JsonElement?>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in pairs) if (!isNone(v)) m[k] = v!!
        return JsonObject(m)
    }

    // -- canonical serialisation ------------------------------------------------------------------

    /** `json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`. */
    fun dumpsCompactSorted(e: JsonElement): String = StringBuilder().also { writeSorted(e, it) }.toString()

    /** Appends [e] as [dumpsCompactSorted] would write it. */
    fun writeSorted(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sortedWith(CODE_POINT_ORDER).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    writeString(k, out)
                    out.append(':')
                    writeSorted(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); writeSorted(v, out) }
                out.append(']')
            }
            is JsonPrimitive -> if (e.isString) writeString(e.content, out) else out.append(e.content)
        }
    }

    fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }
}
