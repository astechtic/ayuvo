package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordType
import java.time.LocalDate
import java.util.regex.Pattern

/** §13 lab result rows, ported from `scripts/records_reference.py` (parse_lab_line / parse_lab_rows). */
class LabRowParser(private val units: UnitsCatalog = UnitsCatalog.DEFAULT) {

    class Tail(
        var value: String? = null,
        var valueNum: Double? = null,
        var qualitative: Boolean = false,
        var comparator: String? = null,
        var rangeValue: Boolean = false,
        var flagRaw: String? = null,
        var unit: String? = null,
        var refText: String? = null,
        var refLow: Double? = null,
        var refHigh: Double? = null
    )

    private fun matchUnit(t: String, pos: Int): Pair<String, Int>? =
        units.matchAt(t, pos)?.let { it.canonical to pos + it.folded.length }

    private fun parseTail(t: String, tp: String): Tail? {
        val n = t.length
        val res = Tail()
        fun boundaryOk(e: Int) = e == n || t[e] == ' '
        var pos: Int
        val range = Py.match(RE_VALUE_RANGE, t)
        if (range != null && (range.end() == n || t[range.end()] == ' ') && !t.startsWith(".", range.end())) {
            res.value = tp.substring(0, range.end()); res.rangeValue = true
            pos = range.end()
        } else {
            val m = Py.match(RE_VALUE_NUM, t)
            if (m != null) {
                val e = m.end()
                res.value = tp.substring(0, e); res.comparator = m.group(1)
                res.valueNum = numValue(m.group(2))
                pos = e
                if (!(e == n || t[e] in " ([" || matchUnit(t, e) != null)) {
                    val am = Py.match(RE_FLAG_ATTACHED, t.substring(e)) ?: return null
                    res.flagRaw = am.group(1)
                    pos = e + am.end()
                }
            } else {
                val q = Py.match(RE_VALUE_QUAL, t) ?: return null
                res.value = tp.substring(0, q.end()); res.qualitative = true
                pos = q.end()
            }
        }
        fun skip(p: Int): Int { var x = p; while (x < n && t[x] == ' ') x++; return x }
        fun tryFlag(p: Int): Int {
            if (res.flagRaw == null && p < n) {
                val fm = Py.match(RE_FLAG, t.substring(p))
                if (fm != null) { res.flagRaw = fm.group(1); return p + fm.end() }
            }
            return p
        }
        fun tryUnit(p: Int): Int {
            if (res.unit == null && p < n) {
                val u = matchUnit(t, p)
                if (u != null) { res.unit = u.first; return u.second }
            }
            return p
        }
        fun tryRef(p: Int): Int {
            if (res.refText != null || p >= n) return p
            if (t[p] == '(' || t[p] == '[') {
                val close = if (t[p] == '(') ')' else ']'
                val q = t.indexOf(close, p + 1)
                if (q < 0) return p
                val inner = t.substring(p + 1, q).trim(' ')
                if (!Py.has(Py.digit, inner)) return p
                res.refText = tp.substring(p, q + 1)
                for ((rx, kind) in REF_PATTERNS) {
                    val fm = Py.full(rx, inner)
                    if (fm != null) { setRef(res, fm, kind); break }
                }
                return q + 1
            }
            for ((rx, kind) in REF_PATTERNS) {
                val fm = Py.match(rx, t.substring(p))
                if (fm != null && boundaryOk(p + fm.end())) {
                    res.refText = tp.substring(p, p + fm.end())
                    setRef(res, fm, kind)
                    return p + fm.end()
                }
            }
            return p
        }
        pos = tryFlag(skip(pos))
        pos = tryUnit(skip(pos))
        pos = tryFlag(skip(pos))
        pos = tryRef(skip(pos))
        pos = tryUnit(skip(pos))
        pos = tryFlag(skip(pos))
        val lp = skip(pos)
        val leftover = t.substring(lp)
        if (leftover.isNotEmpty()) {
            if (Py.has(Py.digit, leftover) || leftover.split(" ").size > 4) return null
            if (res.qualitative && res.refText == null && leftover.split(" ").size <= 3) {
                // Qualitative rows print a qualitative reference: "Protein  Trace  Nil".
                res.refText = tp.substring(lp)
            } else if (res.unit == null && res.refText == null) {
                return null
            }
        }
        return res
    }

    private fun validName(fname: String): Boolean {
        if (fname.isEmpty() || fname.length > 60 || fname[0] !in 'a'..'z') return false
        if (Py.findall(Py.lower, fname).size < 2) return false
        if (fname.split(" ").any { Py.full(NUMERIC_TOKEN, it) != null }) return false
        if (Py.has(COMPARATOR_CHAR, fname)) return false
        val first = Py.findall(LETTER_RUN, fname)
        if (first.isNotEmpty() && first[0] in META_FIRST) return false
        if (DateDetector.candidates(fname, LocalDate.of(2100, 1, 1), DateOrder.DMY).isNotEmpty()) return false
        return true
    }

    /** One line → test_result item or null (§13). */
    fun parseLine(ln: TextLine): RuleItem? {
        val f = ln.f
        val p = ln.p
        if (f.isEmpty() || isHeaderLine(f)) return null
        val lead = Py.match(RE_LEAD, f)
        val off = lead?.end() ?: 0
        val candidates = mutableListOf<Int>()
        if (ln.cells.size >= 2) candidates += ln.cells[1][0]
        for (i in off + 1 until f.length) {
            if (f[i - 1] == ' ' && f[i] != ' ' && i !in candidates) candidates += i
        }
        for (i in candidates) {
            if (i < off) continue
            val nameF = Py.rstrip(f.substring(off, i), " :.=-–_")
            val nameP = p.substring(off, off + nameF.length)
            if (!validName(nameF)) continue
            val res = parseTail(f.substring(i), p.substring(i)) ?: continue
            if (res.rangeValue && res.unit == null) continue
            if (res.comparator != null && res.unit == null) continue
            if (!res.qualitative && res.unit == null && res.refText == null && res.flagRaw == null && nameF.split(" ").size > 4) continue
            val parts = 2 + (if (res.unit != null) 1 else 0) + (if (res.refText != null) 1 else 0)
            val conf = when (parts) {
                4 -> 0.9
                3 -> 0.8
                else -> if (res.qualitative) 0.7 else 0.6
            }
            val flag = resolveFlag(res)
            val vj = RuleItem.obj(
                "name" to nameP, "value" to res.value, "value_num" to RuleItem.num(res.valueNum),
                "unit" to res.unit, "ref_text" to res.refText, "ref_low" to RuleItem.num(res.refLow),
                "ref_high" to RuleItem.num(res.refHigh), "flag" to flag
            )
            return RuleItem("test_result", nameP, vj, conf, ln.page, p, Triple(ln.page, ln.index, 0))
        }
        return null
    }

    fun countUnitLines(linesByPage: List<List<TextLine>>): Int {
        var n = 0
        for (lines in linesByPage) for (ln in lines) {
            var pos = 0
            while (ln.f.isNotEmpty()) {
                val m = Py.search(RE_NUMBER_UNIT, ln.f, pos) ?: break
                if (matchUnit(ln.f, m.end()) != null) { n++; break }
                pos = m.start() + 1
            }
        }
        return n
    }

    fun parseRows(pages: List<String>, recordType: String): List<RuleItem> {
        val linesByPage = pages.mapIndexed { i, t -> TextLine.pageLines(t, i) }
        if (recordType != "lab_report" && recordType != "diagnostic_report" && countUnitLines(linesByPage) < 3) return emptyList()
        val out = linesByPage.flatMap { lines -> lines.mapNotNull { parseLine(it) } }
        return RuleItem.dedupItems(out) { com.ayuvo.health.records.processing.ExtractionWriter.normalizedKey("test_result", it.valueText, it.valueJson?.toString()) }
    }

    fun parseRows(pages: List<String>, recordType: RecordType): List<RuleItem> = parseRows(pages, recordType.raw)

    companion object {
        const val NUM = "(?:[0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)"
        private val RE_VALUE_RANGE = Py.re("([0-9]{1,3})-([0-9]{1,3})")
        val RE_VALUE_NUM: Pattern = Py.re("(?:(<=|>=|<|>|≤|≥)[ ]?)?($NUM)")
        private val RE_VALUE_QUAL = Py.re("(non[ -]?reactive|not detected|positive|negative|reactive|detected|nil|absent|present|trace|normal|abnormal)(?![a-z])")
        val RE_FLAG: Pattern = Py.re("(critical high|critical low|critical|\\(high\\)|\\(low\\)|\\(h\\)|\\(l\\)|\\[h\\]|\\[l\\]|high|low|hh|ll|h|l|\\*|↑↑|↓↓|↑|↓)(?![^ ])")
        val RE_FLAG_ATTACHED: Pattern = Py.re("(hh|ll|h|l|\\*|↑|↓)(?![^ ])")
        private val FLAG_MAP = mapOf(
            "critical high" to "critical_high", "hh" to "critical_high", "↑↑" to "critical_high",
            "critical low" to "critical_low", "ll" to "critical_low", "↓↓" to "critical_low",
            "high" to "high", "h" to "high", "↑" to "high", "(h)" to "high", "[h]" to "high", "(high)" to "high",
            "low" to "low", "l" to "low", "↓" to "low", "(l)" to "low", "[l]" to "low", "(low)" to "low",
            "*" to "abnormal", "critical" to "critical"
        )
        val RE_REF_RANGE: Pattern = Py.re("($NUM)[ ]?(?:-|–|—|to)[ ]?($NUM)")
        val RE_REF_HIGH: Pattern = Py.re("(?:<=|≤|<|up ?to|less than|below)[ ]?($NUM)")
        val RE_REF_LOW: Pattern = Py.re("(?:>=|≥|>|more than|greater than|above)[ ]?($NUM)")
        val REF_PATTERNS = listOf(RE_REF_RANGE to "range", RE_REF_HIGH to "high", RE_REF_LOW to "low")
        private val HEADER_WORDS = setOf(
            "test", "tests", "parameter", "parameters", "investigation", "investigations", "result",
            "results", "unit", "units", "reference", "ref", "range", "interval", "value", "values",
            "flag", "method", "observed", "biological", "normal", "description", "name"
        )
        private val META_FIRST = setOf(
            "age", "sex", "gender", "name", "patient", "pt", "uhid", "mrn", "reg", "regn",
            "registration", "lab", "sample", "specimen", "ref", "referred", "bill", "invoice",
            "receipt", "date", "time", "page", "phone", "mobile", "mob", "tel", "pin", "pincode",
            "report", "collected", "received", "reported", "printed", "dr", "doctor", "consultant",
            "visit", "bed", "ward", "ip", "op", "opd", "ipd", "policy", "claim", "member", "batch",
            "lot", "dose", "id", "sr", "sl", "no", "plot", "sector", "gst", "gstin", "amount",
            "qty", "rate", "barcode", "accession", "order", "client", "location"
        )
        private val RE_LEAD = Py.re("(?:[0-9]{1,2}[.)] |[-•*·>] ?)")
        private val NUMERIC_TOKEN = Py.re("[0-9.,:;/-]+")
        private val COMPARATOR_CHAR = Py.re("[<>=≤≥]")
        private val QUAL_NEGATIVE = setOf("negative", "nil", "absent", "non-reactive", "nonreactive", "non reactive", "not detected", "normal")
        private val QUAL_POSITIVE = setOf("positive", "reactive", "present", "detected", "trace", "abnormal")

        private fun qualFamily(f: String): String? = when (f) {
            in QUAL_NEGATIVE -> "neg"
            in QUAL_POSITIVE -> "pos"
            else -> null
        }
        private val LETTER_RUN = Py.re("[a-z]+")
        private val RE_NUMBER_UNIT = Py.re("(?<![0-9a-z.])$NUM[ ]?")

        fun numValue(txt: String): Double = txt.replace(",", "").toDouble()

        fun isHeaderLine(f: String): Boolean {
            if (Py.has(Py.digit, f)) return false
            return Py.findall(LETTER_RUN, f).toSet().intersect(HEADER_WORDS).size >= 2
        }

        fun setRef(res: Tail, fm: java.util.regex.MatchResult, kind: String) {
            when (kind) {
                "range" -> { res.refLow = numValue(fm.group(1)); res.refHigh = numValue(fm.group(2)) }
                "high" -> res.refHigh = numValue(fm.group(1))
                else -> res.refLow = numValue(fm.group(1))
            }
        }

        fun resolveFlag(res: Tail): String {
            val raw = res.flagRaw
            val lo = res.refLow
            val hi = res.refHigh
            val v = res.valueNum
            val computable = v != null && res.comparator == null && !res.rangeValue
            if (raw != null) {
                val f = FLAG_MAP.getValue(raw)
                if (f == "abnormal" && computable && lo != null && v!! < lo) return "low"
                if (f == "abnormal" && computable && hi != null && v!! > hi) return "high"
                if (f != "critical") return f
                if (computable && lo != null && v!! < lo) return "critical_low"
                if (computable && hi != null && v!! > hi) return "critical_high"
                return "abnormal"
            }
            if (res.qualitative) {
                val vf = qualFamily(RecordText.fold(res.value))
                val rf = qualFamily(Py.strip(RecordText.fold(res.refText ?: ""), " ()[]"))
                if (RecordText.fold(res.value) == "abnormal") return "abnormal"
                if (vf != null && rf != null) return if (vf == rf) "normal" else "abnormal"
                return "unknown"
            }
            if (res.rangeValue && (lo != null || hi != null)) {
                val parts = RecordText.fold(res.value).split("-").map { it.toDouble() }
                val a = parts[0]
                val b = parts[1]
                if (hi != null && a > hi) return "high"
                if (lo != null && b < lo) return "low"
                if ((lo == null || a >= lo) && (hi == null || b <= hi)) return "normal"
                return "unknown"
            }
            if (computable && (lo != null || hi != null)) {
                if (lo != null && v!! < lo) return "low"
                if (hi != null && v!! > hi) return "high"
                return "normal"
            }
            return "unknown"
        }
    }
}
