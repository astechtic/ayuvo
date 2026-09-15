package com.ayuvo.health.records.processing

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.floor

/**
 * Text folding shared by FTS, rules and validation (docs/health-records.md §10), ported from
 * `scripts/records_reference.py` (fold / pfold / words / norm_text / page_lines).
 */
object RecordText {
    private const val ZERO_WIDTH = "\u00AD\u200B\u200C\u200D\u2060\uFEFF"

    /** CRLF/CR → LF, NFKD, drop combining marks (Mn, Mc, Me) and zero-width characters. */
    fun pre(s: String): String {
        val normalized = Normalizer.normalize(s.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFKD)
        val out = StringBuilder(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val type = Character.getType(cp)
            val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (!mark && !(cp < 0x10000 && ZERO_WIDTH.indexOf(cp.toChar()) >= 0)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** Runs of ' ' and '\t' → one ' ', trimmed of ' '. */
    fun collapse(line: String): String {
        val out = StringBuilder(line.length)
        var prevSpace = false
        for (ch in line) {
            if (ch == ' ' || ch == '\t') {
                if (!prevSpace) out.append(' ')
                prevSpace = true
            } else {
                out.append(ch)
                prevSpace = false
            }
        }
        return out.toString().trim(' ')
    }

    /** Per-code-point lowercase without context; multi-char mappings keep the code point. */
    fun lowerCp(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** "Printed fold": fold without lowercasing (same length as [fold]). */
    fun pfold(s: String?): String = pre(s ?: "").split('\n').joinToString("\n") { collapse(it) }

    /**
     * §10 fold: NFKD, combining marks and zero-width characters removed, CRLF/CR → LF, spaces/tabs
     * collapsed per line, lines trimmed, lowercase per code point. Line count is preserved.
     */
    fun fold(text: String?): String = if (text.isNullOrEmpty()) "" else lowerCp(pfold(text))

    fun isAlnumCp(cp: Int): Boolean {
        if (cp < 128) return cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code || cp in 'A'.code..'Z'.code
        val type = Character.getType(cp)
        return type == Character.DECIMAL_DIGIT_NUMBER.toInt() || type == Character.UPPERCASE_LETTER.toInt() ||
            type == Character.LOWERCASE_LETTER.toInt() || type == Character.TITLECASE_LETTER.toInt() ||
            type == Character.MODIFIER_LETTER.toInt() || type == Character.OTHER_LETTER.toInt()
    }

    /** Maximal runs of alphanumeric code points. */
    fun words(folded: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (isAlnumCp(cp)) cur.appendCodePoint(cp) else if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
            i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** §8.1 normalized text: fold, runs of non-alphanumerics → one space, trimmed. */
    fun normalizedValue(text: String?): String = words(fold(text ?: "")).joinToString(" ")

    /** Folded with all whitespace runs collapsed to one space (reference `_flat`). */
    fun collapsed(text: String?): String = fold(text ?: "").replace(Regex("[ \t\n]+"), " ").trim(' ')

    fun letterCount(text: String?): Int = text?.count { it.isLetter() } ?: 0

    /** Round half up to 2 decimals: floor(x·100 + 0.5 + 1e-9) / 100. */
    fun round2(x: Double): Double = floor(x * 100 + 0.5 + 1e-9) / 100.0
}

/** One line of a page (reference `Line`): p = printed fold, f = folded, cells = spans split by tab / 2+ spaces. */
class TextLine(val page: Int, val index: Int, val p: String, val cells: List<IntArray>) {
    val f: String = RecordText.lowerCp(p)

    fun cellEnd(pos: Int): Int {
        for (c in cells) if (c[0] <= pos && pos < c[1]) return c[1]
        return p.length
    }

    companion object {
        private val cellSplit = Pattern.compile("[ ]*\t[ \t]*|[ ]{2,}")

        fun pageLines(text: String?, page: Int): List<TextLine> =
            RecordText.pre(text ?: "").split('\n').mapIndexed { i, raw ->
                val parts = Py.split(cellSplit, raw).map { it.trim(' ', '\t') }.filter { it.isNotEmpty() }
                val cells = mutableListOf<IntArray>()
                var pos = 0
                for (c in parts) {
                    cells += intArrayOf(pos, pos + c.length)
                    pos += c.length + 1
                }
                TextLine(page, i, parts.joinToString(" "), cells)
            }

        fun nonEmpty(lines: List<TextLine>): List<TextLine> = lines.filter { it.p.isNotEmpty() }

        /** §10 head: first 25 non-empty lines. */
        fun head(lines: List<TextLine>): List<TextLine> = nonEmpty(lines).take(25)
    }
}

/** Python `re` call shapes over java.util.regex (match = lookingAt, search = find, fullmatch = matches). */
internal object Py {
    fun re(pattern: String): Pattern = Pattern.compile(pattern)

    fun match(p: Pattern, s: String): Matcher? = p.matcher(s).let { if (it.lookingAt()) it else null }
    fun search(p: Pattern, s: String, pos: Int = 0): Matcher? = p.matcher(s).let { if (pos <= s.length && it.find(pos)) it else null }
    fun full(p: Pattern, s: String): Matcher? = p.matcher(s).let { if (it.matches()) it else null }

    fun finditer(p: Pattern, s: String): List<Matcher> {
        val out = mutableListOf<Matcher>()
        val m = p.matcher(s)
        var pos = 0
        while (pos <= s.length && m.find(pos)) {
            out += p.matcher(s).also { it.find(m.start()) }
            pos = if (m.end() == m.start()) m.end() + 1 else m.end()
        }
        return out
    }

    /** re.findall: group 1 when the pattern has groups, else the whole match. */
    fun findall(p: Pattern, s: String): List<String> = finditer(p, s).map { if (it.groupCount() >= 1) it.group(1) ?: "" else it.group() }

    fun has(p: Pattern, s: String): Boolean = p.matcher(s).find()

    /** re.split without capture groups (empty strings kept, like Python). */
    fun split(p: Pattern, s: String): List<String> {
        val out = mutableListOf<String>()
        var last = 0
        val m = p.matcher(s)
        while (m.find()) {
            if (m.end() == m.start()) continue
            out += s.substring(last, m.start())
            last = m.end()
        }
        out += s.substring(last)
        return out
    }

    /** Python str.strip(chars). */
    fun strip(s: String, chars: String): String = s.trim { it in chars }
    fun rstrip(s: String, chars: String): String = s.trimEnd { it in chars }
    fun lstrip(s: String, chars: String): String = s.trimStart { it in chars }

    val digit: Pattern = re("[0-9]")
    val lower: Pattern = re("[a-z]")
    val alpha: Pattern = re("[A-Za-z]")
}

/**
 * A rules/AI field item in the reference shape `{key, value_text, value_json, confidence, source_page,
 * evidence}` plus its position (page, line, start) used for ordering and de-duplication.
 */
class RuleItem(
    val key: String,
    val valueText: String,
    val valueJson: JsonObject?,
    val confidence: Double,
    val sourcePage: Int,
    val evidence: String?,
    val pos: Triple<Int, Int, Int>
) {
    fun toExtracted(method: com.ayuvo.health.records.model.ExtractionMethod = com.ayuvo.health.records.model.ExtractionMethod.RULES) =
        com.ayuvo.health.records.model.ExtractedField(
            key = key, valueText = valueText, valueJson = valueJson?.toString(), method = method,
            confidence = confidence, sourcePage = sourcePage, evidence = evidence
        )

    fun toJson(): JsonObject = obj(
        "key" to key, "value_text" to valueText, "value_json" to valueJson, "confidence" to confidence,
        "source_page" to sourcePage, "evidence" to evidence
    )

    fun jsonString(name: String): String? = (valueJson?.get(name) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    companion object {
        val FIELD_KEYS = com.ayuvo.health.records.model.FieldKey.ALL

        val posOrder: Comparator<Triple<Int, Int, Int>> = compareBy({ it.first }, { it.second }, { it.third })

        /** Ordered JSON object; integral doubles become integers (reference `_json_num`). */
        fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*pairs.map { (k, v) -> k to element(v) }.toTypedArray()))

        fun element(v: Any?): JsonElement = when (v) {
            is Double -> num(v)
            is Float -> num(v.toDouble())
            else -> RecordJson.element(v)
        }

        fun num(x: Double?): JsonElement = when {
            x == null -> JsonNull
            x == Math.floor(x) && !x.isInfinite() && kotlin.math.abs(x) < 1e15 -> JsonPrimitive(x.toLong())
            else -> JsonPrimitive(x)
        }

        /**
         * Reference `_dedup`: per key the highest confidence item (earliest on ties), output sorted by
         * (pos, FIELD_KEYS index).
         */
        fun <T> dedup(items: List<T>, pos: (T) -> Triple<Int, Int, Int>, confidence: (T) -> Double, fieldKey: (T) -> String, keyFn: (T) -> Any?): List<T> {
            val best = LinkedHashMap<Any?, T>()
            for (it in items.sortedWith { a, b -> posOrder.compare(pos(a), pos(b)) }) {
                val k = keyFn(it)
                val cur = best[k]
                if (cur == null && !best.containsKey(k)) best[k] = it
                else if (cur != null && confidence(it) > confidence(cur)) best[k] = it
            }
            return best.values.sortedWith { a, b ->
                val c = posOrder.compare(pos(a), pos(b))
                if (c != 0) c else FIELD_KEYS.indexOf(fieldKey(a)).compareTo(FIELD_KEYS.indexOf(fieldKey(b)))
            }
        }

        fun dedupItems(items: List<RuleItem>, keyFn: (RuleItem) -> Any?): List<RuleItem> =
            dedup(items, { it.pos }, { it.confidence }, { it.key }, keyFn)
    }
}
