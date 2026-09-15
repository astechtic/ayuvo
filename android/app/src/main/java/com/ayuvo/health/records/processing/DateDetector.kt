package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordType
import java.time.DateTimeException
import java.time.LocalDate
import java.util.regex.Pattern

/** Device date order for ambiguous numeric dates (§11). */
enum class DateOrder(val raw: String) { DMY("dmy"), MDY("mdy") }

/** §11 dates, ported from `scripts/records_reference.py` (_date_candidates, _date_items, extract_dates). */
object DateDetector {

    class Candidate(val start: Int, val end: Int, val dates: List<LocalDate>, val precision: String)

    /** Internal date item (reference dict) with its line and position. */
    class Item(
        val key: String,
        val date: LocalDate,
        val precision: String,
        val confidence: Double,
        val page: Int,
        val lineIndex: Int,
        val start: Int,
        val end: Int,
        val line: TextLine
    ) : Comparable<Item> {
        override fun compareTo(other: Item): Int = compareValuesBy(this, other, { it.page }, { it.lineIndex }, { it.start })
    }

    private const val MONTH_ALT = "january|february|march|april|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec"
    val MONTH_NUM: Map<String, Int> = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7,
        "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12, "jan" to 1,
        "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sept" to 9, "sep" to 9, "oct" to 10,
        "nov" to 11, "dec" to 12
    )

    private val RE_ISO = Py.re("(?<![0-9])([0-9]{4})([-/.])([0-9]{1,2})\\2([0-9]{1,2})(?![0-9])")
    private val RE_NUM = Py.re("(?<![0-9])([0-9]{1,2})([-/.])([0-9]{1,2})\\2([0-9]{4}|[0-9]{2})(?![0-9])")
    private val RE_DMY_TEXT = Py.re("(?<![0-9a-z])([0-9]{1,2})(?:st|nd|rd|th)?[ ./-]?($MONTH_ALT)\\.?(?![a-z])[ ,./'-]*([0-9]{4}|[0-9]{2})(?![0-9])")
    private val RE_MDY_TEXT = Py.re("(?<![a-z])($MONTH_ALT)\\.?[ ./-]?([0-9]{1,2})(?:st|nd|rd|th)?(?![0-9a-z])[ ,./-]*([0-9]{4})(?![0-9])")
    private val RE_MY_TEXT = Py.re("(?<![a-z])($MONTH_ALT)\\.?(?![a-z])[ ,'./-]*([0-9]{4})(?![0-9])")
    private val PATTERNS = listOf(RE_ISO, RE_NUM, RE_DMY_TEXT, RE_MDY_TEXT, RE_MY_TEXT)

    private fun twoDigitYear(yy: Int, today: LocalDate): Int = if (yy <= (today.year + 1) % 100) 2000 + yy else 1900 + yy

    fun valid(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (_: DateTimeException) {
        null
    }

    /** All date mentions in one folded line, non-overlapping, leftmost-longest. */
    fun candidates(f: String, today: LocalDate, order: DateOrder, bothOrders: Boolean = false): List<Candidate> {
        data class Raw(val start: Int, val negLen: Int, val prio: Int, val m: java.util.regex.MatchResult)
        val raw = mutableListOf<Raw>()
        for ((prio, rx) in PATTERNS.withIndex()) {
            var pos = 0
            while (true) {
                val m = Py.search(rx, f, pos) ?: break
                raw += Raw(m.start(), -(m.end() - m.start()), prio, m.toMatchResult())
                pos = m.start() + 1
            }
        }
        raw.sortWith(compareBy<Raw>({ it.start }, { it.negLen }, { it.prio }))
        val out = mutableListOf<Candidate>()
        var lastEnd = 0
        for (r in raw) {
            if (r.start < lastEnd) continue
            val m = r.m
            val dates = mutableListOf<LocalDate>()
            var precision = "day"
            when (r.prio) {
                0 -> valid(m.group(1).toInt(), m.group(3).toInt(), m.group(4).toInt())?.let { dates += it }
                1 -> {
                    val a = m.group(1).toInt()
                    val b = m.group(3).toInt()
                    val ytxt = m.group(4)
                    val sep = m.group(2)
                    if (ytxt.length == 2 && sep == ".") continue
                    val y = if (ytxt.length == 4) ytxt.toInt() else twoDigitYear(ytxt.toInt(), today)
                    if (a > 12 && b > 12) continue
                    val orders = when {
                        a > 12 -> listOf("dmy")
                        b > 12 -> listOf("mdy")
                        bothOrders -> listOf(order.raw, if (order == DateOrder.DMY) "mdy" else "dmy")
                        else -> listOf(order.raw)
                    }
                    for (o in orders) {
                        val d = if (o == "dmy") valid(y, b, a) else valid(y, a, b)
                        if (d != null && d !in dates) dates += d
                    }
                }
                2 -> {
                    val ytxt = m.group(3)
                    val y = if (ytxt.length == 4) ytxt.toInt() else twoDigitYear(ytxt.toInt(), today)
                    valid(y, MONTH_NUM.getValue(m.group(2)), m.group(1).toInt())?.let { dates += it }
                }
                3 -> valid(m.group(3).toInt(), MONTH_NUM.getValue(m.group(1)), m.group(2).toInt())?.let { dates += it }
                else -> {
                    valid(m.group(2).toInt(), MONTH_NUM.getValue(m.group(1)), 1)?.let { dates += it }
                    precision = "month"
                }
            }
            if (dates.isEmpty()) continue
            out += Candidate(m.start(), m.end(), dates, precision)
            lastEnd = m.end()
        }
        return out
    }

    fun addYears(d: LocalDate, n: Int): LocalDate = try {
        LocalDate.of(d.year + n, d.monthValue, d.dayOfMonth)
    } catch (_: DateTimeException) {
        LocalDate.of(d.year + n, d.monthValue, 28)
    }

    /** Python _add_months: clamps the day to the target month. */
    fun addMonths(d: LocalDate, n: Int): LocalDate {
        val total = d.year * 12 + (d.monthValue - 1) + n
        val y = Math.floorDiv(total, 12)
        val m = Math.floorMod(total, 12)
        val len = java.time.YearMonth.of(y, m + 1).lengthOfMonth()
        return LocalDate.of(y, m + 1, minOf(d.dayOfMonth, len))
    }

    fun plausible(d: LocalDate, key: String?, today: LocalDate): Boolean {
        if (d.isBefore(LocalDate.of(1900, 1, 1))) return false
        if (key == "follow_up_date") return !d.isAfter(addYears(today, 2))
        return !d.isAfter(today.plusDays(1))
    }

    private fun lbl(alt: String): Pattern = Py.re("(?<![a-z])(?:$alt)(?![a-z])")

    private class Label(val kind: String, val key: String?, val rx: Pattern)

    private val DATE_LABELS = listOf(
        Label("dob", null, lbl("dob|d\\.o\\.b\\.?|date of birth|birth ?date|born on|born")),
        Label("ignore", null, lbl("printed(?: on)?|print date|generated(?: on)?|registered(?: on)?|registration(?: date)?|" +
            "reg\\.? date|received(?: on)?|valid (?:till|upto|up to|until)|expiry(?: date)?|" +
            "exp\\.?(?: date)?|mfg\\.?(?: date)?|manufactur[a-z]*|lmp|edd")),
        Label("key", "collection_date", lbl("collected(?: on| at)?|collection(?: date)?|date of collection|sample date|" +
            "sample collected(?: on)?|sample collection(?: date)?|specimen collected|" +
            "drawn(?: on)?|coll\\.? date|date of sample")),
        Label("key", "report_date", lbl("reported(?: on)?|report date|date of report|reporting date|released(?: on)?|" +
            "authenticated(?: on)?|authori[sz]ed(?: on)?|result date|approved on|verified on")),
        Label("key", "visit_date", lbl("visit date|date of visit|consultation date|date of consultation|opd date|" +
            "visited on|seen on|appointment date|encounter date")),
        Label("key", "prescription_date", lbl("rx date|prescription date|date of prescription")),
        Label("key", "admission_date", lbl("date of admission|doa|admitted on|admission date|admitted")),
        Label("key", "discharge_date", lbl("date of discharge|dod|discharged on|discharge date|discharged")),
        Label("key", "follow_up_date", lbl("follow[ -]?up(?: on| date| visit)?|review on|review date|next review|" +
            "next visit(?: on)?|revisit(?: on)?|f/u|come (?:back|again) on|next due(?: on| date)?|due on")),
        Label("typed", null, lbl("invoice date|bill date|receipt date|date of issue|issue date|certificate date|" +
            "date of vaccination|vaccination date|vaccinated on|date of dose|dose date|" +
            "date administered|study date|date of study|exam date|date of examination|" +
            "examination date|scan date|test date|date of test|date of procedure|procedure date|" +
            "date of surgery|claim date|date of service|service date")),
        Label("bare", null, lbl("date|dated|dt"))
    )
    private val GAP_WORDS = setOf("on", "at", "dt", "date", "dated", "time", "and", "of", "is")

    fun typeDefaultKey(recordType: String): String = when (recordType) {
        "lab_report", "imaging_report", "diagnostic_report" -> "report_date"
        "consultation_note" -> "visit_date"
        "prescription" -> "prescription_date"
        else -> "report_date"
    }

    private class LabelMatch(val start: Int, val end: Int, val order: Int, val kind: String, val key: String?)

    private fun labelMatches(f: String): MutableList<LabelMatch> {
        val out = mutableListOf<LabelMatch>()
        for ((order, label) in DATE_LABELS.withIndex()) {
            var pos = 0
            while (true) {
                val m = Py.search(label.rx, f, pos) ?: break
                out += LabelMatch(m.start(), m.end(), order, label.kind, label.key)
                pos = m.start() + 1
            }
        }
        return out
    }

    private fun nonOverlappingLabels(f: String): List<LabelMatch> {
        val ms = labelMatches(f).sortedWith(compareBy<LabelMatch>({ it.start }, { -(it.end - it.start) }, { it.order }))
        val out = mutableListOf<LabelMatch>()
        var last = 0
        for (t in ms) if (t.start >= last) { out += t; last = t.end }
        return out
    }

    private val WORD_RUN = Py.re("[a-z0-9]+")

    private fun gapOk(gap: String): Boolean {
        if (gap.length > 24) return false
        return Py.findall(WORD_RUN, gap).all { it in GAP_WORDS }
    }

    val RE_TIME: Pattern = Py.re("(?<![0-9:])([01]?[0-9]|2[0-3]):([0-5][0-9])(?::[0-5][0-9])?[ ]?(am|pm|a\\.m\\.?|p\\.m\\.?)?(?![0-9a-z])")
    private val RE_REL_FOLLOW = Py.re("(?<![a-z])(?:follow[ -]?up|review|revisit|come back|next visit|see me)" +
        "(?: [a-z]+){0,4} (?:after|in) ([0-9]{1,3}) ?(days?|weeks?|wks?|months?|mths?)(?![a-z])")

    fun dateItems(linesByPage: List<List<TextLine>>, recordType: String, today: LocalDate, order: DateOrder): List<Item> {
        val labelled = mutableListOf<Item>()
        val unlabeled = mutableListOf<Item>()
        val relative = mutableListOf<Pair<java.util.regex.MatchResult, TextLine>>()
        val defaultKey = typeDefaultKey(recordType)
        val headIds = if (linesByPage.isNotEmpty()) TextLine.head(linesByPage[0]).map { it.page to it.index }.toSet() else emptySet()
        for (lines in linesByPage) {
            var prev: TextLine? = null
            for (ln in lines) {
                if (ln.f.isEmpty()) continue
                val cands = candidates(ln.f, today, order)
                val inHead = (ln.page to ln.index) in headIds
                val lineHasLabel = labelMatches(ln.f).isNotEmpty()
                val prevPairs = HashMap<Int, LabelMatch>()
                if (cands.isNotEmpty() && !lineHasLabel && prev != null && candidates(prev.f, today, order).isEmpty()) {
                    // Header row labels are matched per cell so "Dose  Date Given" is not read as "dose date".
                    val prevLine: TextLine = prev
                    val labels = prevLine.cells.flatMap { c ->
                        nonOverlappingLabels(prevLine.f.substring(c[0], c[1])).map { LabelMatch(it.start + c[0], it.end + c[0], it.order, it.kind, it.key) }
                    }
                    if (labels.isNotEmpty()) {
                        var rest = prev.f
                        for (l in labels.reversed()) rest = rest.substring(0, l.start) + " " + rest.substring(l.end)
                        val leftoverOk = gapOk(rest.trim()) || labels.size == cands.size
                        if (labels.size == cands.size && leftoverOk) {
                            cands.indices.forEach { prevPairs[it] = labels[it] }
                        } else if (gapOk(prev.f.substring(labels.last().end))) {
                            prevPairs[0] = labels.last()
                        }
                    }
                }
                var segStart = 0
                for ((ci, c) in cands.withIndex()) {
                    val seg = ln.f.substring(segStart, c.start)
                    segStart = c.end
                    val d = c.dates[0]
                    var lab: LabelMatch? = null
                    var conf = 0.0
                    val ms = labelMatches(seg)
                    if (ms.isNotEmpty()) {
                        ms.sortWith(compareBy<LabelMatch>({ -it.end }, { it.start }, { it.order }))
                        val best = ms[0]
                        val gap = seg.substring(best.end)
                        // Follow-up labels may carry a short phrase before the date ("Next due: MMR Dose 2 on").
                        if (gapOk(gap) || (best.key == "follow_up_date" && gap.length <= 32)) { lab = best; conf = 0.9 }
                    } else if (ci in prevPairs) {
                        lab = prevPairs[ci]; conf = 0.8
                    }
                    if (lab != null) {
                        var key = lab.key
                        if (lab.kind == "dob" || lab.kind == "ignore") continue
                        if (lab.kind == "typed") key = defaultKey
                        if (lab.kind == "bare") {
                            if (!inHead) continue
                            key = defaultKey
                            conf = if (recordType == "prescription" || recordType == "consultation_note") 0.8 else 0.6
                        }
                        if (!plausible(d, key, today)) continue
                        labelled += Item(key!!, d, c.precision, conf, ln.page, ln.index, c.start, c.end, ln)
                    } else if (inHead && plausible(d, defaultKey, today)) {
                        unlabeled += Item(defaultKey, d, c.precision, 0.6, ln.page, ln.index, c.start, c.end, ln)
                    }
                }
                if (labelled.none { it.key == "follow_up_date" && it.line === ln }) {
                    Py.search(RE_REL_FOLLOW, ln.f)?.let { relative += it.toMatchResult() to ln }
                }
                prev = ln
            }
        }
        if (unlabeled.isNotEmpty()) {
            val u = unlabeled[0]
            if (labelled.none { it.key == u.key } && labelled.none { it.date == u.date }) labelled += u
        }
        val base = bestDocumentDate(labelled)
        for ((m, ln) in relative) {
            if (base == null) continue
            val n = m.group(1).toInt()
            val unit = m.group(2)
            val d = when {
                unit.startsWith("d") -> base.date.plusDays(n.toLong())
                unit.startsWith("w") -> base.date.plusDays(7L * n)
                else -> addMonths(base.date, n)
            }
            if (plausible(d, "follow_up_date", today)) {
                labelled += Item("follow_up_date", d, "day", 0.7, ln.page, ln.index, m.start(), m.end(), ln)
            }
        }
        return labelled
    }

    val DOCUMENT_DATE_ORDER = listOf("report_date", "collection_date", "prescription_date", "discharge_date", "visit_date")

    fun bestDocumentDate(items: List<Item>): Item? {
        for (key in DOCUMENT_DATE_ORDER) {
            val c = items.filter { it.key == key }
            if (c.isNotEmpty()) return c.sortedWith(compareBy<Item>({ -it.confidence }, { it.page }, { it.lineIndex }, { it.start })).first()
        }
        return null
    }

    /** §11 extract_dates → field items (value_json `{"precision"}`), plus document_time. */
    fun extractItems(pages: List<String>, recordType: String, today: LocalDate, order: DateOrder): List<RuleItem> {
        val linesByPage = pages.mapIndexed { i, t -> TextLine.pageLines(t, i) }
        val raw = dateItems(linesByPage, recordType, today, order)
        val items = RuleItem.dedup(raw, { Triple(it.page, it.lineIndex, it.start) }, { it.confidence }, { it.key }, { it.key to it.date })
        val out = items.map {
            RuleItem(it.key, it.date.toString(), RuleItem.obj("precision" to it.precision), it.confidence, it.page, it.line.p, Triple(it.page, it.lineIndex, it.start))
        }.toMutableList()
        val primary = bestDocumentDate(items)
        if (primary != null) {
            val ln = primary.line
            var tail = ln.f.substring(primary.end)
            val nxt = candidates(tail, today, order)
            if (nxt.isNotEmpty()) tail = tail.substring(0, nxt[0].start)
            val m = Py.search(RE_TIME, tail)
            if (m != null) {
                var h = m.group(1).toInt()
                val mi = m.group(2).toInt()
                val ap = m.group(3)
                var ok = true
                if (ap != null) {
                    if (h < 1 || h > 12) ok = false
                    else if (ap.startsWith("a")) h = if (h == 12) 0 else h
                    else h = if (h == 12) 12 else h + 12
                }
                if (ok) out += RuleItem("document_time", "%02d:%02d".format(h, mi), null, 0.7, ln.page, ln.p, Triple(ln.page, ln.index, 0))
            }
        }
        return out
    }

    fun extract(pageTexts: List<String>, recordType: RecordType, today: LocalDate, order: DateOrder): List<RuleItem> =
        extractItems(pageTexts, recordType.raw, today, order)
}
