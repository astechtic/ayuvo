package com.ayuvo.health.records.share

import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.processing.LabRowParser
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordText
import com.ayuvo.health.records.processing.TextLine
import com.ayuvo.health.records.processing.UnitsCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/** One redacted line of a page: its index in `blocks_json`, the classes it matched and its box. */
data class RedactionLine(val index: Int, val classes: List<RedactionClass>, val box: List<Double>)

/** The lines to paint on one page. */
data class RedactionPage(val pageIndex: Int, val lines: List<RedactionLine>)

data class RedactionResult(
    val recordId: String,
    val classes: List<RedactionClass>,
    val pages: List<RedactionPage>,
    /** Pages that cannot be redacted and are therefore left out of the share (§34). */
    val excludedPages: List<Int>,
    val warnings: List<ShareWarningItem>
)

/**
 * §34 redaction detection (reference `redaction_targets`, vectors `test-vectors/redaction.json`).
 * Pure: it decides which `blocks_json` lines are identifiers and returns their inflated boxes; the
 * caller rasterizes the page and paints them.
 */
object RedactionTargets {

    /** Each side, in the normalized 0–1 box space (§34). */
    const val INFLATE = 0.02

    /** Rasterization long side for redacted output (§34). */
    const val RASTER_LONG_SIDE = 2000

    /** An address label covers at most this many lines, itself included. */
    const val ADDRESS_LINES = 4

    const val MIN_NAME_PART = 3
    const val MIN_PHONE_DIGITS = 7
    const val MAX_PHONE_DIGITS = 15
    const val MIN_ID_LENGTH = 6

    private const val NOT_WORD = "(?<![a-z0-9])"
    private const val NOT_WORD_AFTER = "(?![a-z0-9])"
    private val ADDRESS_LABEL = Regex("$NOT_WORD(?:address|addr|residence|residential)$NOT_WORD_AFTER")
    private val PHONE_LABEL = Regex("$NOT_WORD(?:phone|mobile|mob|tel|telephone|cell|contact|whatsapp|ph)$NOT_WORD_AFTER")
    private val PATIENT_ID_LABEL = Regex(
        "$NOT_WORD(?:uhid|uhid no|mrn|mr no|mrno|patient id|patient no|hospital no|hosp no|reg no|regn no|" +
            "registration no|ip no|op no)$NOT_WORD_AFTER"
    )
    private val INSURANCE_ID_LABEL = Regex(
        "$NOT_WORD(?:policy no|policy number|member id|member no|claim no|claim id|tpa id|tpa no|insurance id)$NOT_WORD_AFTER"
    )
    private val ID_LABEL = Regex(
        "$NOT_WORD(?:id|ids|no|number|ref|reference|barcode|code|serial|accession|sample id|lab no)$NOT_WORD_AFTER"
    )
    private val DATE_RUN = Regex("[0-9]{1,4}-[0-9]{1,2}-[0-9]{1,4}")
    private val PHONE_CHARS = "0123456789 -+()".toSet()

    /** Requested classes in contract order; unknown names are ignored. */
    fun orderedClasses(classes: Collection<RedactionClass>): List<RedactionClass> =
        RedactionClass.entries.filter { it in classes }

    /**
     * §34 detection over the record's [pages] (in `page_index` order) using its non-rejected
     * `patient_name` rows from [fields].
     */
    fun forRecord(
        recordId: String,
        pages: List<RecordPage>,
        fields: List<RecordField>,
        classes: Collection<RedactionClass>,
        /** §13 lab-row parsing keeps printed result lines out of the `phone` class (§34). */
        units: UnitsCatalog = UnitsCatalog.active
    ): RedactionResult {
        val wanted = orderedClasses(classes)
        val rows = fields.filter { it.state != FieldState.REJECTED }
        val name = if (RedactionClass.NAME in wanted) patientNameTargets(rows) else emptyList<String>() to emptyList()
        val full = name.first
        val parts = name.second.toSet()

        val out = mutableListOf<RedactionPage>()
        val excluded = mutableListOf<Int>()
        val warnings = mutableListOf<ShareWarningItem>()

        for (page in pages.sortedBy { it.pageIndex }) {
            val blocks = parseBlocks(page.blocksJson)
            if (blocks == null) {
                if (wanted.isNotEmpty()) {
                    excluded += page.pageIndex
                    warnings += ShareWarnings.pageNotRedactable(recordId, page.pageIndex)
                }
                continue
            }
            val labRows = LabRowParser(units)
            val folded = blocks.map { RecordText.fold(RecordsShareText.collapse(it.text)) }
            val lineWords = folded.map { RecordText.words(it) }
            val hits = linkedMapOf<Int, MutableSet<RedactionClass>>()
            fun add(i: Int, cls: RedactionClass) {
                hits.getOrPut(i) { linkedSetOf() }.add(cls)
            }
            for (i in folded.indices) {
                val f = folded[i]
                if (RedactionClass.NAME in wanted && lineHasName(lineWords[i], full, parts)) add(i, RedactionClass.NAME)
                if (RedactionClass.PHONE in wanted) {
                    // A phone label always wins; a bare digit run only when the line is not a result line.
                    if (PHONE_LABEL.containsMatchIn(f)) {
                        add(i, RedactionClass.PHONE)
                    } else if (lineHasPhoneNumber(f) && !labResultLine(blocks[i].text, f, units, labRows)) {
                        add(i, RedactionClass.PHONE)
                    }
                }
                if (RedactionClass.PATIENT_ID in wanted && PATIENT_ID_LABEL.containsMatchIn(f)) add(i, RedactionClass.PATIENT_ID)
                if (RedactionClass.INSURANCE_ID in wanted && INSURANCE_ID_LABEL.containsMatchIn(f)) add(i, RedactionClass.INSURANCE_ID)
                if (RedactionClass.OTHER_IDS in wanted && lineHasOtherId(f, lineWords[i])) add(i, RedactionClass.OTHER_IDS)
                if (RedactionClass.ADDRESS in wanted && ADDRESS_LABEL.containsMatchIn(f)) {
                    for (j in i until minOf(folded.size, i + ADDRESS_LINES)) {
                        if (j > i && lineWords[j].isEmpty()) break
                        add(j, RedactionClass.ADDRESS)
                    }
                }
            }
            var bad = false
            val lines = mutableListOf<RedactionLine>()
            for (i in hits.keys.sorted()) {
                val box = blocks[i].box
                if (box == null) {
                    bad = true
                    break
                }
                lines += RedactionLine(i, RedactionClass.entries.filter { it in hits.getValue(i) }, inflate(box))
            }
            if (bad) {
                excluded += page.pageIndex
                warnings += ShareWarnings.pageNotRedactable(recordId, page.pageIndex)
                continue
            }
            out += RedactionPage(page.pageIndex, lines)
        }
        return RedactionResult(recordId, wanted, out, excluded, warnings)
    }

    /** One `blocks_json` entry: its text and its `[left, top, width, height]`, or null when unusable. */
    data class Block(val text: String?, val box: List<Double>?)

    /** Parsed `blocks_json`, or null when the page has none (it cannot be redacted). */
    fun parseBlocks(blocksJson: String?): List<Block>? {
        val raw = blocksJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val array: JsonArray = RecordJson.parseArray(raw) ?: return null
        return array.map { element ->
            val o = element as? JsonObject ?: return@map Block(null, null)
            val text = (o["t"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val b = (o["b"] as? JsonArray)
            val box = if (b == null || b.size != 4) null else {
                val values = b.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && !p.isString }?.doubleOrNull }
                if (values.any { it == null }) null else values.map { it!! }
            }
            Block(text, box)
        }
    }

    /** The record's best non-rejected `patient_name` as (whole word sequence, parts ≥ 3 characters). */
    fun patientNameTargets(rows: List<RecordField>): Pair<List<String>, List<String>> {
        val value = ShareSummaryText.best(rows, FieldKey.PATIENT_NAME) ?: return emptyList<String>() to emptyList()
        val full = RecordText.words(RecordText.fold(value))
        return full to full.filter { it.length >= MIN_NAME_PART }
    }

    private fun lineHasName(lineWords: List<String>, full: List<String>, parts: Set<String>): Boolean {
        if (full.isNotEmpty() && full.size <= lineWords.size) {
            for (i in 0..lineWords.size - full.size) {
                if (lineWords.subList(i, i + full.size) == full) return true
            }
        }
        return lineWords.any { it in parts }
    }

    /** A run of 7–15 digits made only of digits, spaces, `-`, `+`, `(` and `)` that is not a numeric date. */
    fun lineHasPhoneNumber(folded: String): Boolean {
        var i = 0
        while (i < folded.length) {
            if (folded[i] !in PHONE_CHARS) {
                i++
                continue
            }
            var j = i
            while (j < folded.length && folded[j] in PHONE_CHARS) j++
            val run = folded.substring(i, j).trim(' ')
            val digits = run.count { it in '0'..'9' }
            if (digits in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS && !DATE_RUN.matches(run)) return true
            i = j
        }
        return false
    }

    /**
     * §34: a printed result line, so its digit runs are values and ranges rather than a phone number —
     * it parses as a §13 lab row, carries a number immediately followed by a `units.json` unit, or is a
     * printed numeric range ("4000 - 11000", "13.0 - 17.0").
     */
    fun labResultLine(raw: String?, folded: String, units: UnitsCatalog, labRows: LabRowParser): Boolean {
        val lines = TextLine.pageLines(raw ?: "", 0)
        if (lines.isNotEmpty() && labRows.parseLine(lines[0]) != null) return true
        var pos = 0
        while (folded.isNotEmpty() && pos <= folded.length) {
            val match = NUMBER_UNIT.find(folded, pos) ?: break
            if (units.matchAt(folded, match.range.last + 1) != null) return true
            pos = match.range.first + 1
        }
        val trimmed = folded.trim(' ')
        return RANGE_SPACED.matches(trimmed) || RANGE_DECIMAL.matches(trimmed)
    }

    private const val NUM = "(?:[0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)"
    private val NUMBER_UNIT = Regex("(?<![0-9a-z.])$NUM[ ]?")
    private val RANGE_SPACED = Regex("[0-9]+(?:[.][0-9]+)? [-\u2013\u2014] [0-9]+(?:[.][0-9]+)?")
    private val RANGE_DECIMAL = Regex(
        "[0-9]+[.][0-9]+ ?[-\u2013\u2014] ?[0-9]+(?:[.][0-9]+)?|[0-9]+(?:[.][0-9]+)? ?[-\u2013\u2014] ?[0-9]+[.][0-9]+"
    )

    private fun lineHasOtherId(folded: String, lineWords: List<String>): Boolean {
        if (!ID_LABEL.containsMatchIn(folded)) return false
        return lineWords.any { w -> w.length >= MIN_ID_LENGTH && w.any { it in 'a'..'z' } && w.any { it in '0'..'9' } }
    }

    /** Inflated by [INFLATE] on every side, clamped to 0..1, rounded to 4 decimals. */
    fun inflate(box: List<Double>): List<Double> {
        val x0 = maxOf(0.0, box[0] - INFLATE)
        val y0 = maxOf(0.0, box[1] - INFLATE)
        var x1 = minOf(1.0, box[0] + box[2] + INFLATE)
        var y1 = minOf(1.0, box[1] + box[3] + INFLATE)
        if (x1 < x0) x1 = x0
        if (y1 < y0) y1 = y0
        return listOf(round4(x0), round4(y0), round4(x1 - x0), round4(y1 - y0))
    }

    private fun round4(x: Double): Double = floor(x * 10000 + 0.5 + 1e-9) / 10000.0
}

/** Shared whitespace collapsing for the share module (§29 rule). */
internal object RecordsShareText {
    fun collapse(s: String?): String = com.ayuvo.health.records.coach.RecordsCoach.collapseWs(s) ?: ""
}
