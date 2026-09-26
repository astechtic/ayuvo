package com.ayuvo.health.actions

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * `ayuvo://action/<id>?k=v` and `ayuvo://open/<section>` (docs/actions.md), ported from
 * `parse_deeplink` in scripts/actions_reference.py. Parsed by hand (not `Uri.getQueryParameter`)
 * so '+' and percent-escapes decode exactly as on iOS. Other `ayuvo://` hosts (widget links) are
 * [LinkError.NOT_ACTION_LINK] and keep their existing handlers.
 */
object ActionDeepLink {
    enum class LinkError(val raw: String) { NOT_ACTION_LINK("not_action_link"), BAD_LINK("bad_link"), DUPLICATE_PARAM("duplicate_param") }

    sealed interface Parsed {
        data class Ok(val id: String, val params: Map<String, String>) : Parsed
        data class Failed(val error: LinkError) : Parsed
    }

    private const val PREFIX = "ayuvo://"

    fun isActionLink(url: String?): Boolean =
        url != null && (url.startsWith("${PREFIX}action/") || url.startsWith("${PREFIX}open/"))

    fun parse(url: String): Parsed {
        if (!url.startsWith(PREFIX)) return Parsed.Failed(LinkError.NOT_ACTION_LINK)
        val rest = url.substring(PREFIX.length).substringBefore('#')
        val path = rest.substringBefore('?')
        val query = if ('?' in rest) rest.substringAfter('?') else ""
        val host = path.substringBefore('/')
        var tail = if ('/' in path) path.substringAfter('/') else ""
        if (host != "action" && host != "open") return Parsed.Failed(LinkError.NOT_ACTION_LINK)
        if (tail.endsWith("/")) tail = tail.dropLast(1)
        if (tail.isEmpty() || '/' in tail) return Parsed.Failed(LinkError.BAD_LINK)
        val segment = percentDecode(tail)
        if (segment.isNullOrEmpty()) return Parsed.Failed(LinkError.BAD_LINK)
        val params = LinkedHashMap<String, String>()
        if (query.isNotEmpty()) {
            for (piece in query.split('&')) {
                if (piece.isEmpty()) continue
                val k = piece.substringBefore('=')
                val v = if ('=' in piece) piece.substringAfter('=') else ""
                val key = percentDecode(k)
                val value = percentDecode(v)
                if (key.isNullOrEmpty() || value == null) return Parsed.Failed(LinkError.BAD_LINK)
                if (key in params) return Parsed.Failed(LinkError.DUPLICATE_PARAM)
                params[key] = value
            }
        }
        if (host == "open") return Parsed.Ok("open.section", mapOf("section" to segment))
        return Parsed.Ok(segment, params)
    }

    private fun hex(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /** Percent-decoding with '+' as space; null when an escape is malformed or the bytes are not UTF-8. */
    fun percentDecode(s: String): String? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                val h = s.getOrNull(i + 1)?.let(::hex) ?: return null
                val l = s.getOrNull(i + 2)?.let(::hex) ?: return null
                out.write(h * 16 + l)
                i += 3
                continue
            }
            if (c == '+') {
                out.write(0x20)
            } else {
                val cp = s.codePointAt(i)
                out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
                continue
            }
            i++
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
