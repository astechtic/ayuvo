package com.ayuvo.health.coach.logic

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.obj
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.coach.RecordsCoach
import com.ayuvo.health.records.processing.RecordText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of `scripts/coach_reference.py` (docs/coach.md). Every function is pure and is driven
 * by the vectors in `shared/coach/test-vectors` in `CoachVectorTests`. The Python reference wins over
 * this file and over the prose; keep the two in step in the same change.
 *
 * Reuses the records primitives rather than re-implementing them: [RecordText.fold] and
 * [RecordsCoach.piiLine] are the redaction rule of docs/health-records.md §28.
 */
object CoachReference {

    // -- Constants (docs/coach.md §4-§11) --------------------------------------------------------

    const val MAX_HEADING_LEVEL = 4
    const val MAX_LIST_DEPTH = 3
    const val MAX_QUOTE_DEPTH = 3
    const val TAB_WIDTH = 4
    const val INDENT_PER_DEPTH = 2

    const val CHART_FENCE = "ayuvo-chart"
    val CHART_TYPES = listOf("bar", "grouped_bar", "stacked_bar", "line", "area", "pie", "scatter",
                             "range", "progress")
    val RANGE_TYPES = setOf("range")
    val SINGLE_SERIES_TYPES = setOf("pie", "progress")

    /**
     * Why a chart could not be read, in the four groups the app has wording for. Every reason has a
     * group, so the renderer can always say what went wrong.
     */
    val CHART_REASON_GROUPS = mapOf(
        "invalid_json" to "malformed", "not_an_object" to "malformed", "bad_point" to "malformed",
        "unknown_type" to "unsupported",
        "too_many_series" to "too_big", "too_many_series_for_type" to "too_big",
        "too_many_points" to "too_big", "label_too_long" to "too_big",
        "no_series" to "no_readings", "no_points" to "no_readings", "bad_number" to "no_readings",
        "missing_y2" to "no_readings", "bad_max" to "no_readings"
    )

    /** §5. The wording group for a refusal; an unknown reason reads as malformed. */
    fun chartReasonGroup(reason: String?): String = CHART_REASON_GROUPS[reason ?: ""] ?: "malformed"

    /** Spellings of a type that mean one of [CHART_TYPES]; anything else is still `unknown_type`. */
    val CHART_TYPE_ALIASES = mapOf(
        "column" to "bar", "columns" to "bar", "bars" to "bar", "vertical_bar" to "bar",
        "horizontal_bar" to "bar", "grouped" to "grouped_bar", "group_bar" to "grouped_bar",
        "multi_bar" to "grouped_bar", "stacked" to "stacked_bar", "stacked_column" to "stacked_bar",
        "lines" to "line", "spline" to "line", "donut" to "pie", "doughnut" to "pie",
        "scatter_plot" to "scatter", "scatterplot" to "scatter", "bubble" to "scatter",
        "band" to "range", "gauge" to "progress", "progress_bar" to "progress"
    )
    /** Keys a spec may carry its parts under. The first key the object *has* wins, null or not. */
    val SERIES_KEYS = listOf("series", "datasets", "data")
    val POINT_KEYS = listOf("points", "data", "values", "y")
    val SERIES_LABEL_KEYS = listOf("label", "name", "title")
    val LABELS_KEYS = listOf("labels", "categories", "x_labels")
    val LABEL_KEYS = listOf("x", "label", "name", "t")
    val VALUE_KEYS = listOf("y", "value", "v")
    val HIGH_KEYS = listOf("y2", "high", "y_high")
    val TITLE_KEYS = listOf("title")
    val UNIT_KEYS = listOf("unit", "units")
    val X_LABEL_KEYS = listOf("x_label", "xLabel", "x_axis", "xAxis")
    val Y_LABEL_KEYS = listOf("y_label", "yLabel", "y_axis", "yAxis")
    val NOTE_KEYS = listOf("note", "subtitle", "caption")
    val MAX_KEYS = listOf("max", "target")
    /** Non-numbers a model writes where a reading is missing: they mean "no reading", never zero. */
    val JSON_NON_NUMBERS = listOf("-Infinity", "+Infinity", "Infinity", "-infinity", "+infinity",
                                  "infinity", "NaN", "nan", "NAN", "undefined")
    const val MAX_SERIES = 4
    const val MAX_POINTS = 60
    const val MAX_LABEL_CHARS = 60
    const val MAX_TITLE_CHARS = 120

    const val MAX_ATTACHMENT_PAGES = 20
    const val MAX_ATTACHMENT_CHARS = 20000
    const val MAX_TURN_CHARS = 40000

    const val MAX_TITLE_LENGTH = 48
    const val MIN_SENTENCE_LENGTH = 12

    val SOURCES = listOf("food", "health", "medications", "records")
    val FOOD_TOOLS = listOf("get_data_summary", "get_weight_history", "get_body_fat_history",
                            "get_calorie_totals", "get_food_entries", "get_fasting_history")
    val WORKOUT_TOOLS = listOf("get_workout_history", "get_workout_plans", "get_workout_preferences",
                               "get_training_summary", "get_exercise_lift_history")
    val HEALTH_TOOLS = listOf("get_health_data_types", "get_health_summary", "get_health_samples",
                              "get_sleep_history")
    val MEDICATION_TOOLS = listOf("get_medications", "get_dose_history", "get_medication_adherence")
    val RECORDS_TOOLS = listOf("records_search", "records_get", "records_observation_series")

    const val ARCHIVE_FORMAT = "ayuvo-coach-chats"
    const val ARCHIVE_VERSION = 1

    val CONVERSATION_COLUMNS = listOf("id", "title", "created_ms", "updated_ms", "last_message_ms",
                                      "pinned", "archived", "data_sources", "selected_record_ids",
                                      "provider_override")
    val MESSAGE_COLUMNS = listOf("id", "conversation_id", "seq", "role", "content", "created_ms",
                                 "updated_ms", "regenerated_from", "variant_index", "record_refs",
                                 "attachment_ids")
    val ATTACHMENT_COLUMNS = listOf("id", "kind", "filename", "mime_type", "bytes", "sha256",
                                    "page_count", "char_count", "excerpt", "created_ms")

    private val RE_RULE = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")
    private val RE_DELIM_CELL = Regex("^:?-{1,}:?$")
    private val RE_NUMBERED = Regex("^([0-9]{1,9})[.)] (.*)$")
    private val RE_TASK = Regex("^\\[([ xX])] (.*)$")
    private val RE_MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val RE_MD_MARKS = Regex("[*_`~]")
    private val RE_SENTENCE_END = Regex("[.!?]")
    private val RE_WS_RUN = Regex("[ \t\r\n]+")

    // -- Small helpers ---------------------------------------------------------------------------

    /** Runs of space/tab/CR/LF collapse to one space; result trimmed. */
    fun collapseWs(text: String?): String = RE_WS_RUN.replace(text ?: "", " ").trim(' ')

    /** Length in Unicode code points (a surrogate pair counts once, unlike [String.length]). */
    fun cpLen(text: String?): Int = (text ?: "").let { it.codePointCount(0, it.length) }

    /** First [limit] code points. */
    fun cpCut(text: String?, limit: Int): String {
        val s = text ?: ""
        if (limit <= 0) return ""
        val count = s.codePointCount(0, s.length)
        if (count <= limit) return s
        return s.substring(0, s.offsetByCodePoints(0, limit))
    }

    /** Tabs become [TAB_WIDTH] spaces so indentation is comparable across editors. */
    fun expandTabs(line: String): String {
        val out = StringBuilder()
        var column = 0
        for (ch in line) {
            if (ch == '\t') {
                val width = TAB_WIDTH - (column % TAB_WIDTH)
                repeat(width) { out.append(' ') }
                column += width
            } else {
                out.append(ch)
                column += 1
            }
        }
        return out.toString()
    }

    fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    fun depthFor(indent: Int): Int = min(MAX_LIST_DEPTH, indent / INDENT_PER_DEPTH)

    /** True for a JSON number that is neither bool, NaN nor +/-Infinity. */
    fun isFiniteNumber(e: JsonElement?): Boolean {
        val p = e as? JsonPrimitive ?: return false
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return false
        val d = p.doubleOrNull ?: return false
        return !d.isNaN() && !d.isInfinite()
    }

    /** Deterministic number -> string for coerced chart x labels: integers lose the ".0". */
    fun numText(e: JsonElement?): String {
        if (!isFiniteNumber(e)) return ""
        val p = e as JsonPrimitive
        p.longOrNull?.let { return it.toString() }
        val d = p.doubleOrNull ?: return ""
        return if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    private fun num(value: Double): JsonElement = MedicationJson.num(value)

    // -- §4 parse_blocks -------------------------------------------------------------------------

    fun headingLevel(trimmed: String): Int? {
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes < 1 || hashes > MAX_HEADING_LEVEL) return null
        if (trimmed.length <= hashes || trimmed[hashes] != ' ') return null
        return hashes
    }

    /** Leading '>' markers (each optionally followed by one space). depth 0 = not a quote. */
    fun quoteDepth(trimmed: String): Pair<Int, String> {
        var depth = 0
        var index = 0
        while (index < trimmed.length && trimmed[index] == '>' && depth < MAX_QUOTE_DEPTH) {
            depth += 1
            index += 1
            if (index < trimmed.length && trimmed[index] == ' ') index += 1
        }
        return if (depth == 0) 0 to trimmed else depth to trimmed.substring(index)
    }

    /** Body after a '-', '*' or '+' marker, or null. */
    fun bulletText(body: String): String? {
        if (body.length < 2) return null
        if (body[0] != '-' && body[0] != '*' && body[0] != '+') return null
        if (body[1] != ' ') return null
        return body.substring(2).trim(' ')
    }

    /** Table row cells. A leading and a trailing pipe are decoration and are dropped. */
    fun splitRow(line: String): List<String> {
        var text = line.trim(' ')
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.substring(0, text.length - 1)
        return text.split("|").map { it.trim(' ') }
    }

    /** Delimiter row -> alignments, or null when the row is not a delimiter row. */
    fun alignments(cells: List<String>): List<String>? {
        if (cells.isEmpty()) return null
        val out = mutableListOf<String>()
        for (cell in cells) {
            if (!RE_DELIM_CELL.matches(cell)) return null
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            out += if (left && right) "center" else if (right) "right" else "left"
        }
        return out
    }

    /** §4. Block-level markdown for one assistant message. */
    fun parseBlocks(markdown: String?): JsonObject {
        val raw = (markdown ?: "").replace("\r\n", "\n").replace("\r", "\n")
        val lines = raw.split("\n").map(::expandTabs).toMutableList()
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)

        val blocks = mutableListOf<JsonElement>()
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()
        var quoteLevel = 0
        var index = 0

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += obj("kind" to "paragraph", "text" to paragraph.joinToString(" "))
            paragraph.clear()
        }
        fun flushQuote() {
            if (quote.isEmpty()) return
            blocks += obj("kind" to "quote", "depth" to quoteLevel, "text" to quote.joinToString(" "))
            quote.clear()
        }
        fun flushAll() { flushParagraph(); flushQuote() }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim(' ')

            if (trimmed.startsWith("```")) {
                flushAll()
                val info = trimmed.substring(3).trim(' ').lowercase()
                val body = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trim(' ').startsWith("```")) {
                    body += lines[index]
                    index += 1
                }
                index += 1 // skip the closing fence (or run past the end)
                val text = body.joinToString("\n")
                blocks += if (info == CHART_FENCE) {
                    chartBlock(text)
                } else {
                    obj("kind" to "code", "lang" to info.ifEmpty { null }, "text" to text)
                }
                continue
            }

            if (trimmed.isEmpty()) {
                flushAll()
                index += 1
                continue
            }

            if (RE_RULE.matches(trimmed)) {
                flushAll()
                blocks += obj("kind" to "rule")
                index += 1
                continue
            }

            val level = headingLevel(trimmed)
            if (level != null) {
                flushAll()
                blocks += obj("kind" to "heading", "level" to level,
                              "text" to trimmed.substring(level).trim(' '))
                index += 1
                continue
            }

            if (trimmed.contains("|") && index + 1 < lines.size) {
                val aligns = alignments(splitRow(lines[index + 1]))
                if (aligns != null) {
                    val headers = splitRow(line)
                    if (aligns.size == headers.size) {
                        flushAll()
                        val rows = mutableListOf<JsonElement>()
                        index += 2
                        while (index < lines.size) {
                            val rowLine = lines[index].trim(' ')
                            if (rowLine.isEmpty() || !rowLine.contains("|")) break
                            val cells = splitRow(lines[index]).toMutableList()
                            while (cells.size < headers.size) cells += ""
                            rows += MedicationJson.element(cells.take(headers.size))
                            index += 1
                        }
                        blocks += obj("kind" to "table", "headers" to headers, "aligns" to aligns,
                                      "rows" to rows)
                        continue
                    }
                }
            }

            val (depth, body) = quoteDepth(trimmed)
            if (depth > 0) {
                flushParagraph()
                if (quote.isNotEmpty() && depth != quoteLevel) flushQuote()
                quoteLevel = depth
                quote += body.trim(' ')
                index += 1
                continue
            }
            flushQuote()

            val indent = indentOf(line)
            val bullet = bulletText(trimmed)
            if (bullet != null) {
                flushParagraph()
                val task = RE_TASK.matchEntire(bullet)
                blocks += if (task != null) {
                    obj("kind" to "task", "depth" to depthFor(indent),
                        "checked" to (task.groupValues[1] != " "),
                        "text" to task.groupValues[2].trim(' '))
                } else {
                    obj("kind" to "bullet", "depth" to depthFor(indent), "text" to bullet)
                }
                index += 1
                continue
            }

            val numbered = RE_NUMBERED.matchEntire(trimmed)
            if (numbered != null) {
                flushParagraph()
                blocks += obj("kind" to "numbered", "depth" to depthFor(indent),
                              "marker" to numbered.groupValues[1],
                              "text" to numbered.groupValues[2].trim(' '))
                index += 1
                continue
            }

            paragraph += trimmed
            index += 1
        }

        flushAll()
        return obj("blocks" to blocks)
    }

    // -- Strict JSON (docs/coach.md §5) ----------------------------------------------------------
    //
    // Platform JSON parsers disagree about what they accept, so a chart spec is never handed to one.
    // Apple's JSONSerialization accepts trailing commas; org.json additionally accepts single quotes,
    // unquoted keys and comments; Python's json accepts NaN and Infinity. A spec that renders on one
    // phone and not another is a parity bug, so all three ports run this scanner instead: RFC 8259
    // with no extensions.

    const val MAX_JSON_CHARS = 20000
    const val MAX_JSON_DEPTH = 32

    private class JsonScanner(private val text: String) {
        var index = 0

        fun skipWhitespace() {
            while (index < text.length && (text[index] == ' ' || text[index] == '\t' ||
                        text[index] == '\n' || text[index] == '\r')) index += 1
        }

        fun atEnd(): Boolean = index >= text.length

        fun string(): String? {
            if (index >= text.length || text[index] != '"') return null
            index += 1
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) return null
                val ch = text[index]
                if (ch == '"') { index += 1; return out.toString() }
                if (ch == '\\') {
                    index += 1
                    if (index >= text.length) return null
                    when (val esc = text[index]) {
                        'u' -> {
                            if (index + 4 >= text.length) return null
                            val digits = text.substring(index + 1, index + 5)
                            if (!digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
                            out.append(digits.toInt(16).toChar())
                            index += 5
                            continue
                        }
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        else -> { if (esc.code >= 0) return null }
                    }
                    index += 1
                    continue
                }
                if (ch.code < 0x20) return null
                out.append(ch)
                index += 1
            }
        }

        fun number(): JsonElement? {
            val start = index
            if (index < text.length && text[index] == '-') index += 1
            if (index >= text.length || !isAsciiDigit(text[index])) return null
            if (text[index] == '0') {
                index += 1
            } else {
                while (index < text.length && isAsciiDigit(text[index])) index += 1
            }
            var isFloat = false
            if (index < text.length && text[index] == '.') {
                isFloat = true
                index += 1
                if (index >= text.length || !isAsciiDigit(text[index])) return null
                while (index < text.length && isAsciiDigit(text[index])) index += 1
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                isFloat = true
                index += 1
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
                if (index >= text.length || !isAsciiDigit(text[index])) return null
                while (index < text.length && isAsciiDigit(text[index])) index += 1
            }
            val raw = text.substring(start, index)
            return if (isFloat) {
                val value = raw.toDoubleOrNull() ?: return null
                if (!value.isFinite()) null else JsonPrimitive(value)
            } else {
                JsonPrimitive(raw.toLongOrNull() ?: return null)
            }
        }

        fun literal(word: String): Boolean {
            if (!text.startsWith(word, index)) return false
            index += word.length
            return true
        }

        fun value(depth: Int): JsonElement? {
            if (depth > MAX_JSON_DEPTH || index >= text.length) return null
            when (text[index]) {
                '{' -> {
                    val out = linkedMapOf<String, JsonElement>()
                    index += 1
                    skipWhitespace()
                    if (index < text.length && text[index] == '}') { index += 1; return JsonObject(out) }
                    while (true) {
                        val key = string() ?: return null
                        skipWhitespace()
                        if (index >= text.length || text[index] != ':') return null
                        index += 1
                        skipWhitespace()
                        val member = value(depth + 1) ?: return null
                        out[key] = member // a repeated key keeps the last value
                        skipWhitespace()
                        if (index < text.length && text[index] == ',') {
                            index += 1
                            skipWhitespace()
                            continue // a ',' must be followed by a member, never by '}'
                        }
                        if (index < text.length && text[index] == '}') { index += 1; return JsonObject(out) }
                        return null
                    }
                }
                '[' -> {
                    val out = mutableListOf<JsonElement>()
                    index += 1
                    skipWhitespace()
                    if (index < text.length && text[index] == ']') { index += 1; return JsonArray(out) }
                    while (true) {
                        out += value(depth + 1) ?: return null
                        skipWhitespace()
                        if (index < text.length && text[index] == ',') {
                            index += 1
                            skipWhitespace()
                            continue
                        }
                        if (index < text.length && text[index] == ']') { index += 1; return JsonArray(out) }
                        return null
                    }
                }
                '"' -> return string()?.let { JsonPrimitive(it) }
            }
            if (literal("true")) return JsonPrimitive(true)
            if (literal("false")) return JsonPrimitive(false)
            if (literal("null")) return JsonNull
            return number()
        }

        private fun isAsciiDigit(c: Char) = c in '0'..'9'
    }

    /** RFC 8259 with no extensions. null = refused; see the note above. */
    fun strictJson(raw: String?): JsonElement? {
        val text = raw ?: ""
        if (cpLen(text) > MAX_JSON_CHARS) return null
        val scanner = JsonScanner(text)
        scanner.skipWhitespace()
        val value = scanner.value(0) ?: return null
        scanner.skipWhitespace()
        if (!scanner.atEnd()) return null
        return value
    }

    // -- §5 parse_chart_spec ---------------------------------------------------------------------

    fun chartBlock(raw: String): JsonObject {
        val parsed = parseChartSpec(raw)
        return if (parsed["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) {
            obj("kind" to "chart", "ok" to true, "spec" to parsed["spec"])
        } else {
            obj("kind" to "chart", "ok" to false, "reason" to parsed["reason"], "text" to raw)
        }
    }

    private fun bad(reason: String): JsonObject = obj("ok" to false, "reason" to reason)

    private fun textOrNull(value: JsonElement?, limit: Int): JsonElement {
        val p = value as? JsonPrimitive ?: return JsonNull
        if (p is JsonNull || !p.isString) return JsonNull
        val text = collapseWs(p.content)
        return if (text.isEmpty()) JsonNull else JsonPrimitive(cpCut(text, limit))
    }

    private fun matchesAt(text: String, start: Int, word: String): Boolean = text.startsWith(word, start)

    /** Past whitespace and // or /* */ comments, which a model sometimes leaves in a spec. */
    private fun skipWsComments(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') { i++; continue }
            if (ch == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            if (ch == '/' && i + 1 < text.length && text[i + 1] == '*') {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i = min(text.length, i + 2)
                continue
            }
            break
        }
        return i
    }

    /** True when `text[i until i + length]` is not part of a longer bare word. */
    private fun wordBoundary(text: String, i: Int, length: Int): Boolean {
        fun bare(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')
        return !bare(if (i > 0) text[i - 1] else null) &&
            !bare(if (i + length < text.length) text[i + length] else null)
    }

    private val NUMBER_CHARS = "0123456789+-.eE".toSet()

    /** The maximal run of number characters starting at [i] (a number token as written). */
    private fun numberRun(text: String, i: Int): String {
        var end = i
        while (end < text.length && text[end] in NUMBER_CHARS) end++
        return text.substring(i, end)
    }

    /**
     * From the `{` at [start]: the root value alone (trailing prose dropped), or, when the body was
     * cut short, the same text with the containers it left open closed. Completing a truncated body
     * adds structure, never a value: a body cut off inside a string is left as it is.
     */
    private fun completeOrCut(text: String, start: Int): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\') { i += 2; continue }
                if (ch == '"') inString = false
                i++
                continue
            }
            if (ch == '"') { inString = true; i++; continue }
            if (ch == '{' || ch == '[') {
                stack.addLast(if (ch == '{') '}' else ']')
                i++
                continue
            }
            if (ch == '}' || ch == ']') {
                if (stack.isNotEmpty()) stack.removeLast()
                i++
                if (stack.isEmpty()) return text.substring(start, i)
                continue
            }
            i++
        }
        if (inString || stack.isEmpty()) return text.substring(start)
        var body = text.substring(start).trimEnd(' ', '\t', '\n')
        while (body.endsWith(",")) body = body.dropLast(1).trimEnd(' ', '\t', '\n')
        return body + stack.reversed().joinToString("")
    }

    /**
     * §5. The small, deterministic repairs a fence body gets before it is parsed: comments dropped,
     * a comma before `}`/`]` dropped, NaN/Infinity/undefined and unreadable number tokens (`7.1.0`,
     * `01`) read as "no reading", prose after the root value dropped and a cut-short body's open
     * containers closed. Nothing here invents, rounds or moves a number, and nothing reaches inside
     * a string.
     */
    fun repairChartJson(raw: String?): String {
        val text = (raw ?: "").replace("\r\n", "\n").replace("\r", "\n")
        if (cpLen(text) > MAX_JSON_CHARS) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') {                       // a string is copied verbatim, escapes and all
                out.append(ch)
                i++
                while (i < text.length) {
                    out.append(text[i])
                    if (text[i] == '\\' && i + 1 < text.length) {
                        out.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (text[i] == '"') { i++; break }
                    i++
                }
                continue
            }
            if (ch == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*')) {
                i = skipWsComments(text, i)
                continue
            }
            if (ch == ',') {
                val next = skipWsComments(text, i + 1)
                if (next < text.length && (text[next] == '}' || text[next] == ']')) { i++; continue }
                out.append(ch)
                i++
                continue
            }
            val word = JSON_NON_NUMBERS.firstOrNull {
                matchesAt(text, i, it) && wordBoundary(text, i, it.length)
            }
            if (word != null) {
                out.append("null")
                i += word.length
                continue
            }
            if (ch in '0'..'9' || ch == '+' || ch == '-') {
                val token = numberRun(text, i)
                val parsed = strictJson(token)
                out.append(if (parsed != null && isFiniteNumber(parsed)) token else "null")
                i += token.length
                // A quote glued to the end of a number (`5.38"`) can never start a string in JSON.
                // Left in, it opens one that swallows the rest of the line and every reading in it.
                if (i < text.length && text[i] == '"') i++
                continue
            }
            out.append(ch)
            i++
        }
        val repaired = out.toString()
        val start = repaired.indexOf('{')
        if (start == -1) return repaired.trim(' ', '\t', '\n')
        return completeOrCut(repaired, start).trim(' ', '\t', '\n')
    }

    /** The value of the first of [keys] the object *has* (null counts as having it), or null. */
    private fun firstKey(doc: JsonObject, keys: List<String>): JsonElement? {
        for (key in keys) if (doc.containsKey(key)) return doc[key]
        return null
    }

    /** A written type -> one of [CHART_TYPES], or null. */
    private fun chartType(value: JsonElement?): String? {
        val prim = value as? JsonPrimitive ?: return null
        if (prim is JsonNull || !prim.isString) return null
        var text = prim.content.trim(' ', '\t', '\n', '\r').lowercase()
            .replace(' ', '_').replace('-', '_')
        if (text.endsWith("_chart")) text = text.dropLast("_chart".length)
        if (text in CHART_TYPES) return text
        return CHART_TYPE_ALIASES[text]
    }

    /**
     * A finite number, or a string holding exactly one. A quoted number is still the reading the
     * model took; a string with a unit in it ("6.2 h") is not a number.
     */
    private fun chartNumber(value: JsonElement?): JsonElement? {
        if (value == null) return null
        if (isFiniteNumber(value)) return value
        val prim = value as? JsonPrimitive ?: return null
        if (prim is JsonNull || !prim.isString) return null
        val parsed = strictJson(prim.content.trim(' ', '\t', '\n', '\r')) ?: return null
        return if (isFiniteNumber(parsed)) parsed else null
    }

    /** True when a point carries no reading at all, as opposed to an unreadable one. */
    private fun chartMissing(value: JsonElement?): Boolean {
        if (value == null || value is JsonNull) return true
        val prim = value as? JsonPrimitive ?: return false
        return prim.isString && prim.content.trim(' ', '\t', '\n', '\r').isEmpty()
    }

    /** Shared x labels, for the `{"labels": [...], "series": [{"values": [...]}]}` shape. */
    private fun chartLabels(doc: JsonObject): List<String> {
        val raw = firstKey(doc, LABELS_KEYS) as? JsonArray ?: return emptyList()
        return raw.map { item ->
            val prim = item as? JsonPrimitive
            when {
                prim != null && prim !is JsonNull && prim.isString -> collapseWs(prim.content)
                isFiniteNumber(item) -> numText(item)
                else -> ""
            }
        }
    }

    private fun positionalLabel(labels: List<String>, index: Int): String =
        if (index in labels.indices && labels[index].isNotEmpty()) labels[index] else "${index + 1}"

    private sealed class PointResult {
        data class Ok(val value: JsonObject) : PointResult()
        object Skip : PointResult()
        data class Bad(val reason: String) : PointResult()
    }

    /** One point -> the normalised object, [PointResult.Skip] when it carries no reading, or a reason. */
    private fun point(raw: JsonElement, index: Int, needsY2: Boolean, labels: List<String>): PointResult {
        var xValue: JsonElement? = null
        var yValue: JsonElement? = null
        var y2Value: JsonElement? = null
        when {
            raw is JsonArray -> {
                if (raw.isEmpty()) return PointResult.Bad("bad_point")
                if (raw.size == 1) {
                    yValue = raw[0]
                } else {
                    xValue = raw[0]
                    yValue = raw[1]
                    if (raw.size > 2) y2Value = raw[2]
                }
            }
            raw is JsonObject -> {
                xValue = firstKey(raw, LABEL_KEYS)
                yValue = firstKey(raw, VALUE_KEYS)
                y2Value = firstKey(raw, HIGH_KEYS)
            }
            raw is JsonNull -> yValue = raw
            raw is JsonPrimitive && (raw.isString || isFiniteNumber(raw)) -> yValue = raw
            else -> return PointResult.Bad("bad_point")
        }

        val xPrim = xValue as? JsonPrimitive
        val label = when {
            xPrim != null && xPrim !is JsonNull && xPrim.isString &&
                xPrim.content.trim(' ', '\t', '\n', '\r').isNotEmpty() -> collapseWs(xPrim.content)
            isFiniteNumber(xValue) -> numText(xValue)
            chartMissing(xValue) -> positionalLabel(labels, index)
            else -> return PointResult.Bad("bad_point")
        }
        if (cpLen(label) > MAX_LABEL_CHARS) return PointResult.Bad("label_too_long")

        // No reading for this point: it is left out, never drawn as zero.
        val y = chartNumber(yValue)
            ?: return if (chartMissing(yValue)) PointResult.Skip else PointResult.Bad("bad_number")

        val out = linkedMapOf<String, JsonElement>("x" to JsonPrimitive(label), "y" to y)
        if (needsY2) {
            val y2 = chartNumber(y2Value) ?: return PointResult.Bad("missing_y2")
            out["y2"] = y2
        } else if (!chartMissing(y2Value)) {
            val y2 = chartNumber(y2Value) ?: return PointResult.Bad("bad_number")
            out["y2"] = y2
        }
        return PointResult.Ok(JsonObject(out))
    }

    /**
     * §5. The body of an ```ayuvo-chart fence. The body is repaired first ([repairChartJson]), then
     * read strictly; a known alias, a recognised key and a quoted number are all understood. What
     * cannot be understood still fails closed, and a point with no reading is left out rather than
     * drawn as zero: a chart never renders from a partially understood spec.
     */
    fun parseChartSpec(raw: String?): JsonObject {
        val doc = strictJson(repairChartJson(raw)) ?: return bad("invalid_json")
        if (doc !is JsonObject) return bad("not_an_object")

        val kind = chartType(doc["type"]) ?: return bad("unknown_type")

        val seriesElement = firstKey(doc, SERIES_KEYS)
        var rawSeries: List<JsonElement> = when {
            seriesElement is JsonObject -> listOf(seriesElement)
            seriesElement is JsonArray -> seriesElement.toList()
            else -> return bad("no_series")
        }
        if (rawSeries.isEmpty()) return bad("no_series")
        if (rawSeries.none { it is JsonObject && firstKey(it, POINT_KEYS) is JsonArray }) {
            // Nothing in the list carries points, so the list is not a list of series: it is either a
            // list of point lists (one series each) or one series' points written bare.
            val nested = rawSeries.all { entry ->
                entry is JsonArray && entry.isNotEmpty() &&
                    entry.all { it is JsonArray || it is JsonObject }
            }
            rawSeries = if (nested) rawSeries.map { obj("points" to it) }
                        else listOf(obj("points" to rawSeries))
        }
        if (rawSeries.size > MAX_SERIES) return bad("too_many_series")
        if (kind in SINGLE_SERIES_TYPES && rawSeries.size > 1) return bad("too_many_series_for_type")

        val labels = chartLabels(doc)
        val needsY2 = kind in RANGE_TYPES
        val series = mutableListOf<JsonElement>()
        var dropped = 0
        for ((position, entry) in rawSeries.withIndex()) {
            if (entry !is JsonObject) return bad("no_series")
            val rawPoints = firstKey(entry, POINT_KEYS) as? JsonArray ?: return bad("no_points")
            if (rawPoints.isEmpty()) return bad("no_points")
            if (rawPoints.size > MAX_POINTS) return bad("too_many_points")
            val points = mutableListOf<JsonElement>()
            for ((i, rawPoint) in rawPoints.withIndex()) {
                when (val p = point(rawPoint, i, needsY2, labels)) {
                    is PointResult.Bad -> return bad(p.reason)
                    // A reading the model did not have, or wrote unreadably.
                    is PointResult.Skip -> { dropped++; continue }
                    is PointResult.Ok -> points += p.value
                }
            }
            if (points.isEmpty()) continue               // every reading was missing: series left out
            val label = textOrNull(firstKey(entry, SERIES_LABEL_KEYS), MAX_LABEL_CHARS)
            series += obj("label" to (if (label is JsonNull) JsonPrimitive("${position + 1}") else label),
                          "points" to points)
        }
        if (series.isEmpty()) return bad("no_points")

        val rawMax = firstKey(doc, MAX_KEYS)
        var maximum: JsonElement = JsonNull
        if (!chartMissing(rawMax)) {
            maximum = chartNumber(rawMax) ?: return bad("bad_max")
        }
        if (kind == "progress" && maximum !is JsonNull &&
            ((maximum as JsonPrimitive).doubleOrNull ?: 0.0) <= 0.0) return bad("bad_max")

        val spec = obj(
            "type" to kind,
            "title" to textOrNull(firstKey(doc, TITLE_KEYS), MAX_TITLE_CHARS),
            "unit" to textOrNull(firstKey(doc, UNIT_KEYS), MAX_LABEL_CHARS),
            "x_label" to textOrNull(firstKey(doc, X_LABEL_KEYS), MAX_LABEL_CHARS),
            "y_label" to textOrNull(firstKey(doc, Y_LABEL_KEYS), MAX_LABEL_CHARS),
            "note" to textOrNull(firstKey(doc, NOTE_KEYS), MAX_TITLE_CHARS),
            "max" to maximum,
            "series" to series,
            "dropped" to dropped
        )
        return obj("ok" to true, "spec" to spec)
    }

    private val CHART_KIND_LABELS = mapOf(
        "bar" to "Bar chart", "grouped_bar" to "Grouped bar chart", "stacked_bar" to "Stacked bar chart",
        "line" to "Line chart", "area" to "Area chart", "pie" to "Pie chart", "scatter" to "Scatter chart",
        "range" to "Range chart", "progress" to "Progress chart"
    )

    /** One sentence describing a parsed chart, for TalkBack. */
    fun chartAccessibilityText(spec: JsonObject): String {
        val parts = mutableListOf(CHART_KIND_LABELS[spec.str("type")] ?: "Chart")
        spec.str("title")?.let { parts += it }
        val values = mutableListOf<Double>()
        for (entry in spec.arr("series").orEmpty()) {
            for (p in (entry as? JsonObject)?.arr("points").orEmpty()) {
                (p as? JsonObject)?.double("y")?.let { values += it }
                (p as? JsonObject)?.double("y2")?.let { values += it }
            }
        }
        if (values.isNotEmpty()) {
            val unit = spec.str("unit")?.let { " $it" } ?: ""
            parts += "${numText(num(values.min()))} to ${numText(num(values.max()))}$unit"
        }
        return parts.joinToString(", ")
    }

    // -- §6 attachment_excerpt -------------------------------------------------------------------

    /**
     * §6. Page texts of one attachment -> the text actually sent to the provider, redacted by the
     * records rule so a lab PDF in chat is treated exactly like a record read through `records_get`.
     */
    fun attachmentExcerpt(pages: List<String>, maxPages: Int = MAX_ATTACHMENT_PAGES,
                          maxChars: Int = MAX_ATTACHMENT_CHARS): JsonObject {
        val total = pages.size
        val kept = mutableListOf<Pair<Int, String>>()
        var redacted = 0
        for ((offset, page) in pages.take(maxPages).withIndex()) {
            val text = page.replace("\r\n", "\n").replace("\r", "\n")
            val lines = mutableListOf<String>()
            for (line in text.split("\n")) {
                if (RecordsCoach.piiLine(RecordText.fold(line), emptyList())) redacted += 1 else lines += line
            }
            val body = lines.joinToString("\n").trim('\n')
            if (body.trim(' ', '\t', '\n').isEmpty()) continue
            kept += (offset + 1) to body
        }

        val parts = if (total > 1) kept.map { "--- page ${it.first} ---\n${it.second}" } else kept.map { it.second }
        var text = parts.joinToString("\n\n")
        val truncated = cpLen(text) > maxChars || total > maxPages
        text = cpCut(text, maxChars)
        return obj(
            "text" to (if (text.isEmpty()) null else text),
            "pages_total" to total,
            "pages_used" to kept.size,
            "pages_skipped" to max(0, total - maxPages),
            "chars" to cpLen(text),
            "redacted_lines" to redacted,
            "truncated" to truncated
        )
    }

    /** §6. Excerpts of every document attached to one turn, cut to the per-turn budget in order. */
    fun turnExcerpts(attachments: List<JsonObject>, maxTurnChars: Int = MAX_TURN_CHARS): JsonObject {
        var used = 0
        val out = mutableListOf<JsonElement>()
        for (item in attachments) {
            val text = item.str("text") ?: ""
            val cut = cpCut(text, max(0, maxTurnChars - used))
            used += cpLen(cut)
            out += obj("id" to item["id"], "filename" to item["filename"],
                       "chars" to cpLen(cut), "truncated" to (cpLen(cut) < cpLen(text)))
        }
        return obj("attachments" to out, "chars" to used)
    }

    // -- §7 conversation_title -------------------------------------------------------------------

    /** `[text](url)` -> `text`. */
    fun stripMarkdownLinks(text: String): String =
        RE_MD_LINK.replace(text) { it.groupValues[1] }

    /**
     * §7. Title derived from the first user message. Empty input -> "" (the platform then shows its
     * own localized "New chat").
     */
    fun conversationTitle(text: String?): JsonObject {
        var stripped = stripMarkdownLinks(text ?: "")
        stripped = RE_MD_MARKS.replace(stripped, "")
        stripped = collapseWs(stripped)
        while (stripped.startsWith("#")) stripped = stripped.substring(1)
        while (stripped.startsWith(">")) stripped = stripped.substring(1)
        stripped = stripped.trim(' ')
        if (stripped.isEmpty()) return obj("title" to "")

        val match = RE_SENTENCE_END.find(stripped)
        if (match != null && match.range.first + 1 >= MIN_SENTENCE_LENGTH) {
            stripped = stripped.substring(0, match.range.first).trim(' ')
        }
        if (cpLen(stripped) <= MAX_TITLE_LENGTH) return obj("title" to stripped)

        var cut = cpCut(stripped, MAX_TITLE_LENGTH)
        val space = cut.lastIndexOf(' ')
        if (space >= 0) {
            val head = cut.substring(0, space)
            if (cpLen(head) >= MIN_SENTENCE_LENGTH) cut = head
        }
        return obj("title" to cut.trim(' ') + "…")
    }

    // -- §8 resolve_data_sources -----------------------------------------------------------------

    /**
     * §8. A source is effective only when it is available, consented and not switched off. The
     * switch can only narrow: turning it on never grants consent.
     */
    fun resolveDataSources(available: JsonObject?, consents: JsonObject?, switches: JsonObject?,
                           workoutsAvailable: Boolean): JsonObject {
        val effective = linkedMapOf<String, JsonElement>()
        val blocked = mutableListOf<JsonElement>()
        val on = mutableMapOf<String, Boolean>()
        for (name in SOURCES) {
            val isAvailable = MedicationJson.truthy(available?.get(name))
            val consented = if (name == "food") true else MedicationJson.truthy(consents?.get(name))
            val switchedOn = (switches?.get(name) as? JsonPrimitive)?.booleanOrNull != false
            val value = isAvailable && consented && switchedOn
            effective[name] = JsonPrimitive(value)
            on[name] = value
            when {
                !isAvailable -> blocked += obj("source" to name, "reason" to "unavailable")
                !consented -> blocked += obj("source" to name, "reason" to "not_consented")
                !switchedOn -> blocked += obj("source" to name, "reason" to "switched_off")
            }
        }

        val tools = mutableListOf<String>()
        if (on["food"] == true) {
            tools += FOOD_TOOLS
            if (workoutsAvailable) tools += WORKOUT_TOOLS
        }
        if (on["health"] == true) tools += HEALTH_TOOLS
        if (on["medications"] == true) tools += MEDICATION_TOOLS
        if (on["records"] == true) tools += RECORDS_TOOLS
        return obj("sources" to JsonObject(effective), "tools" to tools, "blocked" to blocked)
    }

    // -- §9 prompt gallery -----------------------------------------------------------------------

    /**
     * §9. The gallery entries offered for a set of effective sources, grouped by category in catalog
     * order. An entry whose `requires` is not fully satisfied is absent, never greyed out.
     */
    fun galleryFor(sources: JsonObject?, catalog: JsonObject): JsonObject {
        val entries = catalog.arr("prompts").orEmpty().filterIsInstance<JsonObject>()
        val categories = MedicationJson.strings(catalog["categories"])
        val active = SOURCES.filter { MedicationJson.truthy(sources?.get(it)) }.toSet()
        val chosen = entries
            .filter { entry -> MedicationJson.strings(entry["requires"]).all { it in active } }
            .sortedWith(compareBy({ it.double("order") ?: 0.0 }, { it.str("id") ?: "" }))
        val grouped = mutableListOf<JsonElement>()
        for (category in categories) {
            val ids = chosen.filter { it.str("category") == category }.mapNotNull { it.str("id") }
            if (ids.isNotEmpty()) grouped += obj("category" to category, "ids" to ids)
        }
        return obj("categories" to grouped, "count" to chosen.size)
    }

    /**
     * The chips above the composer, by weight goal. They are gallery ids, not their own strings, so a
     * chip and the gallery card that says the same thing are one entry with one translation.
     */
    val CHIP_GOALS: Map<String, List<String>> = mapOf(
        "lose" to listOf("reach_my_goal", "week_review", "dinner_tonight", "one_thing_to_change"),
        "gain" to listOf("reach_my_goal", "protein_sources", "week_review", "one_thing_to_change"),
        "maintain" to listOf("month_over_month", "week_review", "macro_balance", "whole_picture")
    )

    /** No goal set yet: nothing that assumes a direction. */
    val CHIP_DEFAULT = listOf("week_review", "reach_my_goal", "one_thing_to_change")

    const val MAX_CHIPS = 6

    /**
     * §9. The chips offered above the composer: the goal's own prompts, with a training and a sleep
     * prompt in front when there is something to read. Gated by [galleryFor], so a chip can never
     * promise data the user has not connected.
     */
    fun chipsFor(
        goal: String?,
        hasWorkouts: Boolean,
        hasSleep: Boolean,
        sources: JsonObject?,
        catalog: JsonObject
    ): JsonObject {
        val offered = mutableSetOf<String>()
        for (group in galleryFor(sources, catalog).arr("categories").orEmpty().filterIsInstance<JsonObject>()) {
            offered += MedicationJson.strings(group["ids"])
        }
        val wanted = buildList {
            if (hasSleep) add("sleep_week")
            if (hasWorkouts) add("training_review")
            addAll(CHIP_GOALS[goal.orEmpty()] ?: CHIP_DEFAULT)
        }
        val ids = mutableListOf<String>()
        for (entry in wanted) {
            if (entry in offered && entry !in ids) ids += entry
        }
        return obj("ids" to ids.take(MAX_CHIPS))
    }

    // -- §11 archive -----------------------------------------------------------------------------

    private fun rows(snapshot: JsonObject?, table: String): List<JsonObject> =
        snapshot?.arr(table).orEmpty().filterIsInstance<JsonObject>()

    private fun live(list: List<JsonObject>): List<JsonObject> =
        list.filter { !MedicationJson.truthy(it["deleted"]) }

    private fun pick(row: JsonObject, columns: List<String>): JsonObject =
        JsonObject(columns.associateWith { (row[it] ?: JsonNull) })

    /**
     * §11. A store snapshot -> the `ayuvo-coach-chats` document. Tombstones are local and never
     * exported; ordering is fixed so a re-export is byte-identical.
     */
    fun chatArchive(snapshot: JsonObject?): JsonObject {
        val conversations = live(rows(snapshot, "conversations"))
            .sortedWith(compareBy({ it.double("created_ms") ?: 0.0 }, { it.str("id") ?: "" }))
        val known = conversations.mapNotNull { it.str("id") }.toSet()
        val messages = live(rows(snapshot, "messages"))
            .filter { (it.str("conversation_id") ?: "") in known }
            .sortedWith(compareBy({ it.str("conversation_id") ?: "" },
                                  { it.double("seq") ?: 0.0 }, { it.str("id") ?: "" }))
        val used = mutableSetOf<String>()
        for (message in messages) used += MedicationJson.strings(message["attachment_ids"])
        val attachments = live(rows(snapshot, "attachments"))
            .filter { (it.str("id") ?: "") in used }
            .sortedWith(compareBy({ it.double("created_ms") ?: 0.0 }, { it.str("id") ?: "" }))
        return obj(
            "format" to ARCHIVE_FORMAT,
            "format_version" to ARCHIVE_VERSION,
            "conversations" to conversations.map { pick(it, CONVERSATION_COLUMNS) },
            "messages" to messages.map { pick(it, MESSAGE_COLUMNS) },
            "attachments" to attachments.map { pick(it, ATTACHMENT_COLUMNS) }
        )
    }

    private fun mergeRows(local: MutableList<MutableMap<String, JsonElement>>, incoming: List<JsonObject>,
                          columns: List<String>, counts: MutableMap<String, Int>, prefix: String) {
        val byId = mutableMapOf<String, Int>()
        local.forEachIndexed { index, row ->
            ((row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content)?.let { byId[it] = index }
        }
        for (row in incoming) {
            val id = row.str("id")
            if (id.isNullOrEmpty()) {
                counts[prefix + "_rejected"] = (counts[prefix + "_rejected"] ?: 0) + 1
                continue
            }
            val position = byId[id]
            if (position == null) {
                val merged = linkedMapOf<String, JsonElement>()
                for (key in columns) merged[key] = row[key] ?: JsonNull
                merged["deleted"] = JsonPrimitive(0)
                local += merged
                byId[id] = local.size - 1
                counts[prefix + "_inserted"] = (counts[prefix + "_inserted"] ?: 0) + 1
                continue
            }
            if (MedicationJson.truthy(local[position]["deleted"])) {
                counts[prefix + "_skipped_tombstoned"] = (counts[prefix + "_skipped_tombstoned"] ?: 0) + 1
                continue
            }
            val incomingUpdated = (row["updated_ms"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val localUpdated = (local[position]["updated_ms"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            if (incomingUpdated > localUpdated) {
                for (key in columns) local[position][key] = row[key] ?: JsonNull
                counts[prefix + "_updated"] = (counts[prefix + "_updated"] ?: 0) + 1
            } else {
                counts[prefix + "_skipped_older"] = (counts[prefix + "_skipped_older"] ?: 0) + 1
            }
        }
    }

    /** §11. Merge an archive into a snapshot. Nothing is ever deleted; a local tombstone always wins. */
    fun mergeChatArchive(snapshot: JsonObject?, archive: JsonObject?): JsonObject {
        val conversations = rows(snapshot, "conversations").map { LinkedHashMap(it.toMap()) as MutableMap<String, JsonElement> }.toMutableList()
        val messages = rows(snapshot, "messages").map { LinkedHashMap(it.toMap()) as MutableMap<String, JsonElement> }.toMutableList()
        val attachments = rows(snapshot, "attachments").map { LinkedHashMap(it.toMap()) as MutableMap<String, JsonElement> }.toMutableList()

        val counts = linkedMapOf<String, Int>()
        for (prefix in listOf("conversations", "messages", "attachments")) {
            for (suffix in listOf("inserted", "updated", "skipped_older", "skipped_tombstoned", "rejected")) {
                counts["${prefix}_$suffix"] = 0
            }
        }
        counts["messages_skipped_orphan"] = 0

        fun result(error: String?): JsonObject = obj(
            "snapshot" to obj(
                "conversations" to conversations.map { JsonObject(it) },
                "messages" to messages.map { JsonObject(it) },
                "attachments" to attachments.map { JsonObject(it) }
            ),
            "counts" to JsonObject(counts.mapValues { JsonPrimitive(it.value) }),
            "error" to error
        )

        if (archive?.str("format") != ARCHIVE_FORMAT) return result("bad_format")
        if ((archive.double("format_version") ?: 0.0) > ARCHIVE_VERSION.toDouble()) return result("newer_version")

        mergeRows(conversations, archive.arr("conversations").orEmpty().filterIsInstance<JsonObject>(),
                  CONVERSATION_COLUMNS, counts, "conversations")
        val liveIds = conversations
            .filter { !MedicationJson.truthy(it["deleted"]) }
            .mapNotNull { (it["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .toSet()
        val keptMessages = mutableListOf<JsonObject>()
        for (row in archive.arr("messages").orEmpty().filterIsInstance<JsonObject>()) {
            if ((row.str("conversation_id") ?: "") in liveIds) {
                keptMessages += row
            } else {
                counts["messages_skipped_orphan"] = (counts["messages_skipped_orphan"] ?: 0) + 1
            }
        }
        mergeRows(messages, keptMessages, MESSAGE_COLUMNS, counts, "messages")
        mergeRows(attachments, archive.arr("attachments").orEmpty().filterIsInstance<JsonObject>(),
                  ATTACHMENT_COLUMNS, counts, "attachments")
        return result(null)
    }

    // -- §10 Export ------------------------------------------------------------------------------

    const val MAX_SLUG_CHARS = 40
    private val RE_SLUG_DROP = Regex("[^a-z0-9]+")

    /**
     * A filename stem from a conversation title: lowercase, runs of anything else become one `-`,
     * trimmed, capped. An empty title gives "chat", so a file is never named by an accident.
     */
    fun exportSlug(title: String?): String {
        var slug = RE_SLUG_DROP.replace((title ?: "").lowercase(), "-").trim('-')
        slug = cpCut(slug, MAX_SLUG_CHARS).trim('-')
        return slug.ifEmpty { "chat" }
    }

    /**
     * §10 "Export as Markdown". A readable transcript. Attachment *contents* are never inlined —
     * only their names, because the excerpt was a redacted copy of a file the user still has.
     */
    fun conversationMarkdown(
        conversation: JsonObject?,
        messages: List<JsonObject>,
        attachments: List<JsonObject>,
        localDay: String,
        provider: String? = null
    ): JsonObject {
        val byId = attachments.mapNotNull { a -> a.str("id")?.let { it to a } }.toMap()
        val rows = messages.sortedWith(
            compareBy({ it.double("seq") ?: 0.0 }, { it.double("variant_index") ?: 0.0 })
        )
        val shown = latestVariants(rows)

        val title = conversation?.str("title")?.ifEmpty { null } ?: "New chat"
        val header = mutableListOf(localDay, "${shown.size} messages")
        if (provider != null) header += provider
        val lines = mutableListOf("# " + collapseWs(title), "", header.joinToString(" · "), "", "---")

        for (message in shown) {
            lines += ""
            val speaker = if (message.str("role") == "assistant") "Coach" else "You"
            lines += "**$speaker:** " + (message.str("content") ?: "")
            val names = MedicationJson.strings(message["attachment_ids"])
                .map { byId[it]?.str("filename") ?: it }
            if (names.isNotEmpty()) {
                lines += ""
                lines += "Attached: " + names.joinToString(", ")
            }
            val refs = (message["record_refs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            if (refs.isNotEmpty()) {
                lines += ""
                lines += "Used records: " + refs.joinToString("; ") { ref ->
                    val name = ref.str("title") ?: ref.str("record_id").orEmpty()
                    "$name — ${ref.str("date").orEmpty()}"
                }
            }
        }
        return obj(
            "text" to lines.joinToString("\n") + "\n",
            "filename" to "${exportSlug(title)}-$localDay.md"
        )
    }

    /**
     * §10 "Export as JSON": one conversation in the §11 archive shape, so a file exported from one
     * chat imports through exactly the same reader as a full backup.
     */
    fun conversationJson(
        conversation: JsonObject?,
        messages: List<JsonObject>,
        attachments: List<JsonObject>,
        localDay: String
    ): JsonObject {
        val id = conversation?.str("id").orEmpty()
        val snapshot = obj(
            "conversations" to listOfNotNull(conversation),
            "messages" to messages.filter { it.str("conversation_id") == id },
            "attachments" to attachments
        )
        val title = conversation?.str("title")?.ifEmpty { null } ?: "New chat"
        return obj(
            "archive" to chatArchive(snapshot),
            "filename" to "${exportSlug(title)}-$localDay.json"
        )
    }

    /**
     * §10 "Regenerate": which user turn to re-send, and what the new reply row looks like.
     *
     * Nothing is deleted — the new reply keeps the seq it replaces and takes the next variant index,
     * so the stepper can walk the versions. `regenerated_from` always points at the FIRST variant,
     * so a chain of regenerations stays a flat set rather than a linked list.
     */
    fun regeneratePlan(messages: List<JsonObject>, seq: Int): JsonObject {
        val rows = messages.sortedWith(
            compareBy({ it.double("seq") ?: 0.0 }, { it.double("variant_index") ?: 0.0 })
        )
        val variants = rows.filter {
            (it.double("seq") ?: -1.0).toInt() == seq && it.str("role") == "assistant"
        }
        val first = variants.firstOrNull()
            ?: return obj("ok" to false, "reason" to "not_a_reply")
        var prompt: JsonObject? = null
        for (message in rows) {
            if ((message.double("seq") ?: 0.0).toInt() < seq && message.str("role") == "user") {
                prompt = message
            }
        }
        if (prompt == null) return obj("ok" to false, "reason" to "no_prompt")
        val next = (variants.maxOfOrNull { (it.double("variant_index") ?: 0.0).toInt() } ?: 0) + 1
        return obj(
            "ok" to true,
            "prompt_id" to prompt.str("id"),
            "prompt_seq" to prompt["seq"],
            "seq" to seq,
            "variant_index" to next,
            "regenerated_from" to (first.str("regenerated_from") ?: first.str("id")),
            "attachment_ids" to MedicationJson.strings(prompt["attachment_ids"])
        )
    }

    /** Only the newest version of each reply is shown; the stepper reaches the rest. */
    fun latestVariants(rows: List<JsonObject>): List<JsonObject> {
        val best = linkedMapOf<Int, JsonObject>()
        for (row in rows) {
            val seq = (row.double("seq") ?: 0.0).toInt()
            val existing = best[seq]
            val variant = (row.double("variant_index") ?: 0.0).toInt()
            if (existing == null || (existing.double("variant_index") ?: 0.0).toInt() < variant) {
                best[seq] = row
            }
        }
        return best.values.toList()
    }
}
