package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.RecordType
import java.time.LocalDate
import java.util.Locale
import java.util.regex.Pattern

/** §12 rule fields, ported from `scripts/records_reference.py` (_doctors … _medications, extract_fields). */
class FieldExtractor(private val config: RecordTypesConfig, private val units: UnitsCatalog = UnitsCatalog.DEFAULT) {
    private val labRows = LabRowParser(units)

    private fun field(key: String, valueText: String, valueJson: kotlinx.serialization.json.JsonObject?, conf: Double, ln: TextLine, start: Int = 0) =
        RuleItem(key, valueText, valueJson, conf, ln.page, ln.p, Triple(ln.page, ln.index, start))

    private fun sectionLabel(f: String): Pair<String?, Int?>? {
        val b = Py.match(RE_BULLET, f)
        val off = b?.end() ?: 0
        for ((section, rx) in SECTION_LABELS) {
            val m = Py.match(rx, f.substring(off)) ?: continue
            val e = off + m.end()
            if (e < f.length && f[e] in 'a'..'z') continue
            val rest = f.substring(e)
            val rs = rest.trimStart(' ')
            if (rs == "" || rs == ":" || rs == "-" || rs == "–") return section to null
            if (rs[0] in ":-–") {
                var inline = e + (rest.length - rs.length) + 1
                while (inline < f.length && f[inline] == ' ') inline++
                return section to (if (inline < f.length) inline else null)
            }
            if (section == "symptom" && f.substring(off, e) == "c/o" && rest.startsWith(" ")) return section to e + 1
        }
        return null
    }

    private fun isStopLine(f: String): Boolean {
        if (f.isEmpty()) return true
        if (sectionLabel(f) != null) return true
        if (Py.match(RE_GENERIC_LABEL, f) != null) return true
        if (Py.has(RE_SIGNATURE, f)) return true
        return false
    }

    private fun splitItems(text: String, section: String): List<IntArray> {
        val seps = if (section == "diagnosis" || section == "procedure" || section == "recommendation") ";" else ",;"
        val out = mutableListOf<IntArray>()
        var start = 0
        val padded = text + seps[0]
        for (i in padded.indices) {
            if (i == text.length || padded[i] in seps) {
                out += intArrayOf(start, i)
                start = i + 1
            }
        }
        return out
    }

    private fun cleanSpan(p: String, s0: Int, e0: Int): IntArray {
        var s = s0
        var e = e0
        while (s < e && p[s] == ' ') s++
        Py.match(RE_BULLET, p.substring(s, e))?.let { s += it.end() }
        while (s < e && p[e - 1] in " .,;:-–") e--
        while (s < e && p[s] == ' ') s++
        return intArrayOf(s, e)
    }

    private fun sections(linesByPage: List<List<TextLine>>, recordType: String): List<RuleItem> {
        val items = mutableListOf<RuleItem>()
        for (lines in linesByPage) {
            var i = 0
            while (i < lines.size) {
                val ln = lines[i]
                val lab = if (ln.f.isNotEmpty()) sectionLabel(ln.f) else null
                if (lab == null || lab.first == null) {
                    if (ln.f.isNotEmpty() && recordType !in setOf("lab_report", "bill", "insurance") &&
                        Py.match(RE_STANDALONE_REC, ln.f) != null && ln.p.length <= 160
                    ) {
                        items += field("recommendation", Py.rstrip(ln.p, " .;,"), null, 0.7, ln)
                    }
                    i++
                    continue
                }
                val section = lab.first!!
                val inline = lab.second
                val spans = mutableListOf<Pair<TextLine, Int>>()
                if (inline != null) spans += ln to inline
                var j = i + 1
                if (inline == null) while (j < lines.size && lines[j].f.isEmpty()) j++
                while (j < lines.size && spans.size < 10) {
                    val nl = lines[j]
                    if (isStopLine(nl.f)) break
                    spans += nl to 0
                    j++
                }
                for ((sl, st) in spans) {
                    if (section == "recommendation" && Py.match(RE_MED_FORM, sl.f) != null) continue
                    for (span in splitItems(sl.p.substring(st), section)) {
                        val c = cleanSpan(sl.p, st + span[0], st + span[1])
                        val s = c[0]
                        val e = c[1]
                        if (e - s < 2 || e - s > SECTION_LIMIT.getValue(section) || !Py.has(Py.lower, sl.f.substring(s, e))) continue
                        items += field(section, sl.p.substring(s, e), null, SECTION_CONF.getValue(section), sl, s)
                    }
                }
                i = j
            }
        }
        return items
    }

    private fun bare(tk: String): String = RecordText.fold(tk).filter { it in 'a'..'z' }

    private fun cutName(pName: String, cutWords: Set<String>): String? {
        val toks = pName.split(" ")
        val kept = mutableListOf<String>()
        for (tk in toks) {
            val b = bare(tk)
            if (b in cutWords || (b in QUALIFICATIONS && tk.uppercase(Locale.ROOT) == tk && b.length > 1)) break
            kept += tk
        }
        while (kept.isNotEmpty() && Py.rstrip(kept.last(), ".-'") == "") kept.removeAt(kept.lastIndex)
        val value = Py.rstrip(kept.joinToString(" "), ".-'")
        if (kept.none { bare(it).length >= 2 }) return null
        return value
    }

    private class DoctorCand(val role: String?, val name: String, val conf: Double, val ln: TextLine, val start: Int, val end: Int)

    private fun doctors(linesByPage: List<List<TextLine>>): List<RuleItem> {
        val headIds = if (linesByPage.isNotEmpty()) TextLine.head(linesByPage[0]).map { it.page to it.index }.toSet() else emptySet()
        val cands = mutableListOf<DoctorCand>()
        for (lines in linesByPage) for (ln in lines) {
            if (ln.f.isEmpty()) continue
            val used = mutableListOf<IntArray>()
            for (m in Py.finditer(RE_DOCTOR_LABEL, ln.f)) {
                val role = if (m.group(1).startsWith("ref")) "referrer" else null
                val s = m.end()
                val nm = Py.match(RE_CAP_NAME, ln.p.substring(s, ln.cellEnd(s))) ?: continue
                if (RecordText.fold(nm.group()).startsWith("self")) {
                    used += intArrayOf(m.start(), s + nm.end())
                    continue
                }
                val name = cutName(nm.group(), NAME_CUT)
                used += intArrayOf(m.start(), s + nm.end())
                if (name != null) cands += DoctorCand(role, name, 0.85, ln, s, s + name.length)
            }
            for (m in Py.finditer(RE_DR_PREFIX, ln.p)) {
                if (used.any { it[0] <= m.start() && m.start() < it[1] }) continue
                val s = m.end()
                val nm = Py.match(RE_CAP_NAME, ln.p.substring(s, ln.cellEnd(s))) ?: continue
                val name = cutName(nm.group(), NAME_CUT) ?: continue
                val conf = if ((ln.page to ln.index) in headIds) 0.85 else 0.7
                cands += DoctorCand(null, name, conf, ln, s, s + name.length)
            }
        }
        val items = mutableListOf<RuleItem>()
        val best = LinkedHashMap<String, DoctorCand>()
        for (c in cands) {
            val k = c.role ?: ""
            val cur = best[k]
            if (cur == null || c.conf > cur.conf) best[k] = c
        }
        val ref = best["referrer"]
        val prim = best[""]
        if (ref != null) items += field("doctor_name", ref.name, RuleItem.obj("role" to "referrer"), ref.conf, ref.ln, ref.start)
        if (prim != null && !(ref != null && RecordText.normalizedValue(ref.name) == RecordText.normalizedValue(prim.name))) {
            items += field("doctor_name", prim.name, null, prim.conf, prim.ln, prim.start)
            val ln = prim.ln
            var spec = specialtyIn(ln, prim.end)
            if (spec == null) {
                val lines = linesByPage.first { pg -> pg.isNotEmpty() && pg[0].page == ln.page }
                val nxt = lines.drop(ln.index + 1).firstOrNull { it.p.isNotEmpty() }
                if (nxt != null) spec = specialtyIn(nxt, 0)
            }
            if (spec != null) items += field("doctor_specialty", spec.first, null, 0.7, spec.second, spec.third)
        }
        return items
    }

    private fun specialtyIn(ln: TextLine, start: Int): Triple<String, TextLine, Int>? {
        val ms = mutableListOf<IntArray>()
        for (m in Py.finditer(RE_SPECIALTY, ln.f.substring(start))) {
            val s = start + m.start()
            val e = start + m.end()
            val printed = ln.p.substring(s, e)
            val letters = m.group(1).filter { it in 'a'..'z' }
            if (letters.length <= 3 && letters in QUALIFICATIONS && printed.uppercase(Locale.ROOT) != printed) continue
            ms += intArrayOf(s, e)
        }
        if (ms.isEmpty()) return null
        val s = ms.first()[0]
        var e = ms.last()[1]
        val span = ln.p.substring(s, e)
        if (span.count { it == '(' } > span.count { it == ')' } && e < ln.p.length && ln.p[e] == ')') e++ // "MD (Internal Medicine)"
        return Triple(Py.rstrip(ln.p.substring(s, e), " ,"), ln, s)
    }

    private fun facility(linesByPage: List<List<TextLine>>): List<RuleItem> {
        for ((pi, lines) in linesByPage.withIndex()) {
            for (ln in TextLine.head(lines)) {
                if (ln.p.length > 80 || !Py.has(RE_FACILITY, ln.f)) continue
                if (Py.has(RE_FACILITY_SKIP, ln.f) || Py.match(RE_DR_PREFIX, ln.p) != null) continue
                var s = 0
                Py.match(RE_LEADING_LABEL, ln.f)?.let { s = it.end() } // "Hospital: City Care Hospital, Nagpur"
                var value = ln.p.substring(s)
                val kw = Py.search(RE_FACILITY, ln.f.substring(s))
                val comma = value.indexOf(',')
                if (comma >= 0 && ((kw != null && kw.start() < comma) || value.count { it == ',' } >= 3)) value = value.substring(0, comma)
                value = titleCase(Py.strip(value, " ,.-|"), 2)
                if (Py.findall(Py.alpha, value).size < 3) continue
                return listOf(field("facility", value, null, if (pi == 0) 0.8 else 0.6, ln, s))
            }
        }
        return emptyList()
    }

    private fun department(linesByPage: List<List<TextLine>>): List<RuleItem> {
        for (lines in linesByPage) for (ln in lines) {
            if (ln.f.isEmpty()) continue
            val m = Py.search(RE_DEPT_OF, ln.f) ?: Py.search(RE_X_DEPT, ln.f) ?: continue
            val s = m.start(1)
            val e = m.end(1)
            return listOf(field("department", ln.p.substring(s, e), null, 0.7, ln, s))
        }
        return emptyList()
    }

    private fun personNameAt(ln: TextLine, s0: Int): Pair<String, Int>? {
        var s = s0
        val end = ln.cellEnd(s)
        if (s > end) return null
        Py.match(RE_TITLE_AT, ln.f.substring(s, end))?.let { s += it.end() }
        if (s > end) return null
        val m = Py.match(RE_PERSON_WORDS, ln.f.substring(s, end)) ?: return null
        val toks = m.group().split(" ")
        var kept = 0
        for (tk in toks) {
            if (tk.filter { it in 'a'..'z' } in PATIENT_CUT) break
            kept++
        }
        if (kept == 0) return null
        val length = toks.take(kept).joinToString(" ").length
        val value = Py.rstrip(ln.p.substring(s, s + length), " .,-'")
        if (Py.findall(Py.alpha, value).size < 2) return null
        return value to s
    }

    private fun patient(linesByPage: List<List<TextLine>>): List<RuleItem> {
        if (linesByPage.isEmpty()) return emptyList()
        val head = TextLine.head(linesByPage[0])
        val items = mutableListOf<RuleItem>()
        fun context(i: Int): Boolean = (i - 1..i + 1).any { k -> k in head.indices && Py.has(RE_PATIENT_CONTEXT, head[k].f) }
        for ((i, ln) in head.withIndex()) {
            var found: Pair<String, Int>? = null
            Py.search(RE_PATIENT_STRONG, ln.f)?.let { found = personNameAt(ln, it.end()) }
            if (found == null && context(i)) {
                val m = Py.search(RE_PATIENT_WEAK, ln.f)
                if (m != null && !Py.has(RE_PATIENT_STRONG, ln.f)) found = personNameAt(ln, m.end())
                if (found == null) Py.search(RE_TITLE, ln.f)?.let { found = personNameAt(ln, it.start()) }
            }
            found?.let {
                items += field("patient_name", it.first, null, 0.75, ln, it.second)
            }
            if (found != null) break
        }
        for (ln in head) {
            var m = Py.search(RE_AGE_LABEL, ln.f)
            var slash = false
            if (m == null) { m = Py.search(RE_AGE_SLASH, ln.f); slash = m != null }
            if (m == null && Py.has(RE_PATIENT_CONTEXT, ln.f)) m = Py.search(RE_AGE_YEARS, ln.f)
            if (m != null) {
                val n = m.group(1).toInt()
                val unit = if (!slash) m.group(2) else null
                if (n <= 120) {
                    val txt = when {
                        unit != null && unit[0] == 'm' -> "$n months"
                        unit != null && unit[0] == 'd' -> "$n days"
                        else -> "$n years"
                    }
                    items += field("patient_age", txt, null, 0.75, ln, m.start())
                    break
                }
            }
        }
        for (ln in head) {
            var m = Py.search(RE_SEX_LABEL, ln.f)
            var sex = m?.group(1)
            if (m == null) {
                val m2 = Py.search(RE_AGE_SLASH, ln.f)
                if (m2 != null) { m = m2; sex = m2.group(2) }
            }
            if (m != null) {
                val v = when (sex) { "m", "male" -> "male"; "f", "female" -> "female"; else -> "other" }
                items += field("patient_sex", v, null, 0.75, ln, m.start())
                break
            }
        }
        return items
    }

    /**
     * Tokens of [A-Z'.-] with ≥ [minLetters] capitals become "Xxxx"; small words after the first token
     * are lowercased only when all caps; everything else is kept (reference `_title_case`).
     */
    private fun titleCase(p: String, minLetters: Int = 4): String {
        val small = setOf("and", "of", "the", "for", "with", "in", "on", "to")
        return p.split(" ").mapIndexed { i, tk ->
            val lo = tk.lowercase(Locale.ROOT)
            when {
                i > 0 && lo in small && tk.uppercase(Locale.ROOT) == tk -> lo
                Py.full(TITLE_TOKEN, tk) != null && Py.findall(UPPER_LETTER, tk).size >= minLetters -> tk[0] + tk.substring(1).lowercase(Locale.ROOT)
                else -> tk
            }
        }.joinToString(" ")
    }

    private fun reportName(linesByPage: List<List<TextLine>>, facilityLine: TextLine?): List<RuleItem> {
        if (linesByPage.isEmpty()) return emptyList()
        val panel = config.types.filter { it.type.raw in setOf("lab_report", "imaging_report", "diagnostic_report") }
            .flatMap { t -> t.rules.filter { it.weight >= 3 }.map { it.pattern } }
        val head = TextLine.head(linesByPage[0])
        var fallback: TextLine? = null
        for (ln in head) {
            if (sectionLabel(ln.f) != null) break // the report name precedes the first section
            if (ln.f[0] !in 'a'..'z') continue
            if (ln === facilityLine || LabRowParser.isHeaderLine(ln.f) || ln.p.split(" ").size > 8) continue
            if (labRows.parseLine(ln) != null || sectionLabel(ln.f) != null || Py.match(RE_GENERIC_LABEL, ln.f) != null) continue
            if (':' in ln.f || DateDetector.candidates(ln.f, LocalDate.of(2100, 1, 1), DateOrder.DMY).isNotEmpty()) continue
            if (panel.any { it.containsMatchIn(ln.f) } || Py.has(RE_PANEL_WORD, ln.f)) {
                return listOf(field("report_name", titleCase(Py.strip(ln.p, " .:-")), null, 0.8, ln))
            }
            if (fallback == null && Py.has(RE_REPORT_WORD, ln.f) && ln.p.split(" ").size <= 6 && !Py.has(Py.digit, ln.f)) fallback = ln
        }
        return fallback?.let { listOf(field("report_name", titleCase(Py.strip(it.p, " .:-")), null, 0.7, it)) }.orEmpty()
    }

    private fun medicationLine(ln: TextLine): RuleItem? {
        val f = ln.f
        val p = ln.p
        val m = Py.match(RE_MED_FORM, f)
        var form: String? = null
        val restStart: Int
        var fb: java.util.regex.Matcher? = null
        if (m != null) {
            val formWord = m.group(1)
            form = FORM_CANON.firstOrNull { formWord.startsWith(it.first) }?.second
            restStart = m.end()
        } else {
            fb = Py.match(RE_MED_FALLBACK, f) ?: return null
            restStart = fb.start(1)
        }
        val rest = f.substring(restStart)
        val found = LinkedHashMap<String, IntArray>()
        for ((name, rx) in listOf("strength" to RE_STRENGTH, "frequency" to RE_FREQ, "duration" to RE_DURATION, "instructions" to RE_INSTR, "dose" to RE_DOSE)) {
            Py.search(rx, rest)?.let { found[name] = intArrayOf(restStart + it.start(), restStart + it.end()) }
        }
        if (form == null) {
            val s = found["strength"] ?: return null
            if (listOf("frequency", "duration", "instructions").none { it in found }) return null
            if (s[0] != restStart + fb!!.group(1).length + 1 || f.substring(s[0], s[1]).endsWith("%")) return null
        }
        val spans = found.entries.sortedBy { it.value[0] }
        val clean = LinkedHashMap<String, IntArray>()
        var last = -1
        for ((k, se) in spans) if (se[0] >= last) { clean[k] = se; last = se[1] }
        var stop = (clean.values.map { it[0] } + f.length).min()
        for (sep in listOf(" - ", " (", " — ", " – ")) {
            val q = f.indexOf(sep, restStart)
            if (q in 0 until stop) stop = q
        }
        if (stop < restStart) return null
        val name = Py.strip(p.substring(restStart, stop), " -–:,.(")
        if (Py.findall(Py.alpha, name).size < 2 || name.length > 60 || name.split(" ").size > 6) return null
        val vals = LinkedHashMap<String, String>()
        clean.forEach { (k, se) -> vals[k] = p.substring(se[0], se[1]) }
        if (form in setOf("syrup", "suspension", "drops", "solution") && "strength" in vals && "dose" !in vals) {
            val st = vals.getValue("strength")
            if (RecordText.fold(st).replace(" ", "").endsWith("ml") && "/" !in st) vals["dose"] = vals.remove("strength")!!
        }
        val vj = RuleItem.obj(
            "name" to name, "strength" to vals["strength"], "form" to form, "dose" to vals["dose"],
            "frequency" to vals["frequency"], "duration" to vals["duration"], "instructions" to vals["instructions"]
        )
        val conf = if (vals["strength"] != null || vals["frequency"] != null) 0.8 else 0.6
        return field("medication", name, vj, conf, ln)
    }

    private fun medications(linesByPage: List<List<TextLine>>, recordType: String): List<RuleItem> {
        if (recordType !in MED_TYPES) return emptyList()
        return linesByPage.flatMap { lines -> lines.filter { it.f.isNotEmpty() }.mapNotNull { medicationLine(it) } }
    }

    fun fieldItems(linesByPage: List<List<TextLine>>, recordType: String): List<RuleItem> {
        val items = mutableListOf<RuleItem>()
        items += doctors(linesByPage)
        val fac = facility(linesByPage)
        items += fac
        items += department(linesByPage)
        items += patient(linesByPage)
        val facLine = fac.firstOrNull()?.let { f -> linesByPage.first { pg -> pg.isNotEmpty() && pg[0].page == f.pos.first }.first { it.index == f.pos.second } }
        if (recordType in setOf("lab_report", "imaging_report", "diagnostic_report", "other")) items += reportName(linesByPage, facLine)
        items += sections(linesByPage, recordType)
        items += medications(linesByPage, recordType)
        return RuleItem.dedupItems(items) {
            Triple(it.key, it.jsonString("role"), ExtractionWriter.normalizedKey(it.key, it.valueText, it.valueJson?.toString()))
        }
    }

    /** §12 non-date, non-lab-row fields, ordered by position. */
    fun extractItems(pages: List<String>, recordType: String): List<RuleItem> =
        fieldItems(pages.mapIndexed { i, t -> TextLine.pageLines(t, i) }, recordType)

    fun extract(pageTexts: List<String>, recordType: RecordType): List<ExtractedField> =
        extractItems(pageTexts, recordType.raw).map { it.toExtracted() }

    companion object {
        private val QUALIFICATIONS = setOf("mbbs", "md", "ms", "dm", "mch", "dnb", "mrcp", "frcs", "bds", "mds", "dgo", "dch",
            "facc", "fics", "mrcog", "frcp", "dmrd", "dmrt", "phd", "bams", "bhms", "mph", "do")
        private val NAME_CUT = setOf("consultant", "reg", "regd", "registration", "mci", "mobile", "ph", "phone", "timings",
            "sr", "senior", "junior", "head", "hod", "prof", "professor", "associate", "assistant",
            "department", "dept", "hospital", "clinic", "laboratories", "laboratory", "diagnostics",
            "labs", "centre", "center", "pathology", "radiology", "signature", "signed", "date",
            "the", "and", "for", "on", "at", "in", "of", "with")
        val RE_DR_PREFIX: Pattern = Py.re("(?<![A-Za-z])(?:Dr|DR|dr)(?:\\.[ ]?|[ ])")
        private val RE_CAP_NAME = Py.re("[A-Z][A-Za-z'.-]*(?: [A-Z][A-Za-z'.-]*){0,5}")
        private val RE_DOCTOR_LABEL = Py.re("(?<![a-z])(consultant|consulting doctor|treating doctor|attending doctor|" +
            "doctor|physician|surgeon|referred by|referring doctor|ref\\.? ?by|ref\\.? doctor|" +
            "ref\\.? dr\\.?)[ ]?[:\\-][ ]?(?:dr\\.?[ ]?)?")
        private val RE_SPECIALTY = Py.re("(?<![a-z])(m\\.?b\\.?b\\.?s\\.?|m\\.?d\\.?|m\\.?s\\.?|d\\.?m\\.?|m\\.?ch\\.?|d\\.?n\\.?b\\.?|" +
            "mrcp|frcs|b\\.?d\\.?s\\.?|m\\.?d\\.?s\\.?|dgo|dch|facc|mrcog|frcp|dmrd|" +
            "cardiologist|physician|pediatrician|paediatrician|gyn(?:a)?ecologist|obstetrician|" +
            "orthop(?:a)?edic(?: surgeon)?|dermatologist|neurologist|endocrinologist|pathologist|" +
            "radiologist|general medicine|internal medicine|diabetologist|nephrologist|" +
            "pulmonologist|gastroenterologist|psychiatrist|ophthalmologist|oncologist|urologist|" +
            "surgeon)(?![a-z])")
        private val RE_FACILITY = Py.re("(?<![a-z])(?:hospitals?|clinics?|medical cent(?:er|re)|health cent(?:er|re)|" +
            "diagnostic cent(?:er|re)|diagnostics|laborator(?:y|ies)|labs|path ?labs|pathology|" +
            "imaging|scans|nursing home|polyclinic|healthcare|health care|institute|" +
            "medical college|pharmacy|chemists?|medicos|medicals)(?![a-z])")
        private val RE_FACILITY_SKIP = Py.re("(?<![a-z])(?:report|department|dept|referred|ref|consultant|patient|name|test|" +
            "result|reference|range|sample|specimen|collected|date|dr)(?![a-z])")
        private val RE_DEPT_OF = Py.re("(?<![a-z])(?:department|dept\\.?) of ([a-z][a-z&]*(?: [a-z&]+){0,3})(?![a-z])")
        private val RE_X_DEPT = Py.re("(?:^|[,;:|(\\-] ?)([a-z][a-z&]*(?: [a-z&]+){0,3}) department(?![a-z])")
        private val RE_PATIENT_STRONG = Py.re("(?<![a-z])(?:patient'?s? name|name of (?:the )?patient|pt\\.? name)[ ]?[:\\-][ ]?")
        private val RE_PATIENT_WEAK = Py.re("(?<![a-z])(?:patient|name)[ ]?[:\\-][ ]?")
        private val RE_TITLE = Py.re("(?<![a-z])(?:mr|mrs|ms|miss|master|mstr|baby|smt|shri|sri|kumari|kum)\\.?[ ]")
        private val RE_TITLE_AT = Py.re("(?:mr|mrs|ms|miss|master|mstr|baby|smt|shri|sri|kumari|kum)\\.?[ ]")
        private val RE_PERSON_WORDS = Py.re("[a-z][a-z.'-]*(?:,? [a-z][a-z.'-]*){0,5}")
        private val PATIENT_CUT = setOf("age", "sex", "gender", "uhid", "mrn", "id", "dob", "ref", "date", "reg", "no", "ip", "op",
            "lab", "sample", "y", "yr", "yrs", "years", "m", "f", "male", "female", "bed", "ward",
            "phone", "mobile", "d", "o", "b")
        private val RE_PATIENT_CONTEXT = Py.re("(?<![a-z])(?:age|sex|gender|uhid|mrn|yrs?|years?|y/o)(?![a-z])|" +
            "[0-9]{1,3} ?y(?:rs?)?(?: ?/ ?| )(?:m|f)(?![a-z])|[0-9]{1,3} ?/ ?(?:m|f)(?![a-z])")
        private val RE_AGE_LABEL = Py.re("(?<![a-z])age(?: ?/ ?(?:sex|gender))?[ ]?[:\\-]?[ ]?([0-9]{1,3})(?:[ ]?" +
            "(years?|yrs?|y|months?|mths?|mos?|days?))?(?![a-z0-9])")
        private val RE_AGE_SLASH = Py.re("(?<![0-9a-z])([0-9]{1,3})[ ]?(?:y|yrs?|years?)?[ ]?[/,|][ ]?(m|f|male|female)(?![a-z])")
        private val RE_AGE_YEARS = Py.re("(?<![0-9a-z])([0-9]{1,3})[ ]?(years?|yrs?)(?: old)?(?![a-z])")
        private val RE_SEX_LABEL = Py.re("(?<![a-z])(?:sex|gender)[ ]?[:\\-]?[ ]?(?:[0-9]{1,3}[ ]?(?:y|yrs?|years?)?[ ]?/[ ]?)?" +
            "(male|female|other|transgender|m|f)(?![a-z])")
        private val RE_REPORT_WORD = Py.re("(?<![a-z])report(?![a-z])")
        private val TITLE_TOKEN = Py.re("[A-Z'.-]+")
        private val UPPER_LETTER = Py.re("[A-Z]")
        private val RE_PANEL_WORD = Py.re("(?<![a-z])(?:panel|profile|function tests?)(?![a-z])")
        private val RE_LEADING_LABEL = Py.re("[a-z][a-z ]{0,24}:[ ]?")

        private val SECTION_LABELS: List<Pair<String?, Pattern>> = listOf(
            "diagnosis" to Py.re("(?:(?:provisional|final|clinical|working|discharge|differential) )?diagnos[ie]s(?: on discharge)?|impression|dx"),
            "symptom" to Py.re("(?:(?:chief|presenting) )?complaints?|c/o|presenting symptoms?|symptoms?"),
            "procedure" to Py.re("(?:procedures?|operations?|surgery)(?: (?:done|performed))?"),
            "recommendation" to Py.re("advice(?: on discharge)?|discharge advice|advised|recommendations?|" +
                "plan(?: of care)?|instructions|suggested|suggestions?|follow[ -]?up advice"),
            null to Py.re("history(?: of present illness)?|hpi|past (?:medical )?history|examination|on examination|" +
                "o/e|vitals|investigations?|medications?|discharge medications?|treatment(?: given)?|rx|" +
                "course in (?:the )?hospital|hospital course|condition (?:at|on) discharge|findings|" +
                "technique|conclusion|interpretation|clinical (?:history|indication)|allergies|" +
                "comments?|notes?|remarks?|signature|medicines")
        )
        private val RE_BULLET = Py.re("(?:[0-9]{1,2}[.)][ ]?|[-•*·>][ ]?)")
        private val RE_GENERIC_LABEL = Py.re("[a-z][a-z0-9 /().&'-]{0,30}[ ]?:")
        private val RE_SIGNATURE = Py.re("^(?:dr\\.? |\\(dr)|(?<![a-z])(?:signature|signed|radiologist|pathologist|" +
            "end of report|authori[sz]ed signatory)(?![a-z])")
        private val SECTION_LIMIT = mapOf("diagnosis" to 120, "symptom" to 80, "procedure" to 120, "recommendation" to 160)
        private val SECTION_CONF = mapOf("diagnosis" to 0.75, "symptom" to 0.7, "procedure" to 0.7, "recommendation" to 0.7)
        private val RE_STANDALONE_REC = Py.re("(?:repeat|review|follow[ -]?up|consult) ")

        private const val RE_MED_INDEX = "(?:[0-9]{1,2}[.)][ ]?|[-•*·>][ ]?|rx[ :.]+)?"
        private val RE_MED_FORM = Py.re("^" + RE_MED_INDEX + "(tablets?|tabs?|capsules?|caps?|syrup|syp|syr|injection|inj|" +
            "ointment|oint|cream|gel|drops?|inhaler|sachets?|suspension|susp|lotion|spray|powder|" +
            "solution|soln|nebuli[sz]ation|neb|respules?)(?:\\.[ ]?|[ ]+)")
        private val FORM_CANON = listOf("tab" to "tablet", "cap" to "capsule", "sy" to "syrup", "inj" to "injection", "oint" to "ointment",
            "cream" to "cream", "gel" to "gel", "drop" to "drops", "inhaler" to "inhaler", "sachet" to "sachet",
            "susp" to "suspension", "lotion" to "lotion", "spray" to "spray", "powder" to "powder",
            "sol" to "solution", "neb" to "nebulisation", "respule" to "respules")
        private val RE_MED_FALLBACK = Py.re("^" + RE_MED_INDEX + "([a-z][a-z0-9'-]*(?: [a-z][a-z0-9'-]*){0,3}) ")
        private val RE_STRENGTH = Py.re("(?<![a-z0-9.])[0-9]+(?:\\.[0-9]+)?(?:[ ]?[/+][ ]?[0-9]+(?:\\.[0-9]+)?)*[ ]?" +
            "(?:mg|mcg|μg|ug|gm|g|ml|iu|units?|meq|%)(?:[ ]?/[ ]?[0-9]*(?:\\.[0-9]+)?[ ]?(?:ml|g))?(?![a-z0-9])")
        private val RE_FREQ = Py.re("(?<![a-z0-9/⁄])(?:[0-9](?:[/⁄][0-9])?[ ]?-[ ]?[0-9](?:[/⁄][0-9])?[ ]?-[ ]?[0-9](?:[/⁄][0-9])?" +
            "(?:[ ]?-[ ]?[0-9](?:[/⁄][0-9])?)?|[o0]d|bd|bid|tds|tid|qid|qds|hs|qhs|sos|prn|stat|qd|" +
            "once daily|twice daily|thrice daily|once a day|twice a day|thrice a day|three times a day|" +
            "four times a day|once weekly|weekly|daily|at night|every [0-9]{1,2} ?(?:hours|hrs|h)|" +
            "q[0-9]{1,2}h)(?![a-z0-9/⁄])")
        private val RE_DURATION = Py.re("(?:(?<![a-z])[x×*][ ]?[0-9]{1,3}[ ]?(?:days?|d|weeks?|wks?|w|months?|mths?|m)|" +
            "(?<![a-z0-9])for [0-9]{1,3} ?(?:days?|weeks?|wks?|months?|mths?)|" +
            "(?<![a-z0-9])[0-9]{1,3} ?(?:days|weeks|months))(?![a-z])")
        private val RE_INSTR = Py.re("(?<![a-z])(?:after food|before food|after meals?|before meals?|with food|with meals?|" +
            "on empty stomach|empty stomach|at bedtime|bedtime|after breakfast|before breakfast|" +
            "after lunch|after dinner|before dinner|with milk|with water|apply locally|" +
            "local application|if needed|when required|as needed|if required)(?![a-z])")
        private val RE_DOSE = Py.re("(?<![a-z0-9.])(?:[0-9]+(?:\\.[0-9]+)?|1⁄2)[ ]?(?:tabs?|tablets?|caps?|capsules?|puffs?|" +
            "drops?|sachets?|tsp|teaspoons?)(?![a-z])")
        private val MED_TYPES = setOf("prescription", "discharge_summary", "medication_list", "consultation_note")
    }
}
