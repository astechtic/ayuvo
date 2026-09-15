package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.SplitSegment
import java.time.LocalDate
import kotlin.math.min

/** §14 multi-record boundaries, ported from the reference `detect_boundaries`. Never splits on its own. */
class BoundaryDetector(private val classifier: DocumentClassifier, private val units: UnitsCatalog = UnitsCatalog.DEFAULT) {
    private val fields = FieldExtractor(classifier.config, units)

    data class PageScore(val page: Int, val score: Int, val signals: List<String>)

    data class Result(val pageScores: List<PageScore>, val segments: List<SplitSegment>) {
        val propose: Boolean get() = segments.size >= 2
    }

    private class PageInfo(
        val type: RecordType?,
        val names: Set<String>,
        val report: String?,
        val dates: Set<LocalDate>,
        val numbering: Pair<Int, Int>?,
        val letters: Int,
        val head: Set<String>,
        val first: String
    )

    private fun pageNumbering(lines: List<TextLine>): Pair<Int, Int>? {
        val ne = TextLine.nonEmpty(lines)
        val firstThree = ne.take(3)
        val zone = firstThree + ne.takeLast(3).filter { ln -> firstThree.none { it === ln } }
        for (ln in zone) {
            Py.search(RE_PAGE_OF, ln.f)?.let { return it.group(1).toInt() to it.group(2).toInt() }
            Py.full(RE_BARE_OF, ln.f)?.let { return it.group(1).toInt() to it.group(2).toInt() }
        }
        return null
    }

    private fun letters(lines: List<TextLine>): Int = lines.sumOf { ln -> ln.f.count { it in 'a'..'z' } }

    private fun headWords(lines: List<TextLine>): Set<String> =
        TextLine.nonEmpty(lines).take(5).flatMap { RecordText.words(it.f) }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    fun analyze(pages: List<String>, today: LocalDate, dateOrder: DateOrder): Result {
        val lps = pages.mapIndexed { i, t -> TextLine.pageLines(t, i) }
        val n = pages.size
        val info = lps.map { lines ->
            val headIds = TextLine.head(lines).map { it.index }.toSet()
            val ptype = classifier.pageBestType(lines)
            val typeRaw = ptype?.raw ?: "other"
            val items = fields.fieldItems(listOf(lines), typeRaw)
            val names = items.filter { it.key == "patient_name" }.map { RecordText.normalizedValue(it.valueText) }.toSet()
            val rname = items.filter { it.key == "report_name" }.map { RecordText.normalizedValue(it.valueText) }
            val dates = DateDetector.dateItems(listOf(lines), typeRaw, today, dateOrder).filter { it.line.index in headIds && it.key != "follow_up_date" }.map { it.date }.toSet()
            val ne = TextLine.nonEmpty(lines)
            PageInfo(ptype, names, rname.firstOrNull(), dates, pageNumbering(lines), letters(lines), headWords(lines), ne.firstOrNull()?.f ?: "")
        }
        val scores = mutableListOf<PageScore>()
        val starts = mutableListOf(0)
        if (n >= 3) {
            var segType = info[0].type
            val segNames = info[0].names.toMutableSet()
            val segDates = info[0].dates.toMutableSet()
            var segReport = info[0].report
            for (i in 1 until n) {
                val cur = info[i]
                val prev = info[i - 1]
                var score = 0
                val signals = mutableListOf<String>()
                if (cur.numbering != null && cur.numbering.first == 1) { score += 3; signals += "page_one_of" }
                if (prev.numbering != null && prev.numbering.first == prev.numbering.second && prev.numbering.first >= 1) { score += 2; signals += "previous_last_page" }
                if (cur.type != null && segType != null && cur.type != segType) { score += 3; signals += "type_change" }
                if (jaccard(cur.head, prev.head) < 0.3) { score += 2; signals += "head_change" }
                if ((cur.names.isNotEmpty() && segNames.isNotEmpty() && cur.names.intersect(segNames).isEmpty()) ||
                    (cur.dates.isNotEmpty() && segDates.isNotEmpty() && cur.dates.intersect(segDates).isEmpty())
                ) { score += 2; signals += "patient_or_date_change" }
                if (cur.report != null && segReport != null && cur.report != segReport) { score += 2; signals += "report_name_change" }
                if (prev.letters < 20) { score += 1; signals += "previous_blank" }
                if ((cur.numbering != null && cur.numbering.first > 1) || Py.match(RE_CONTINUED, cur.first) != null) { score -= 3; signals += "continuation" }
                scores += PageScore(i, score, signals)
                if (score >= 5) {
                    starts += i
                    segType = cur.type
                    segNames.clear(); segNames += cur.names
                    segDates.clear(); segDates += cur.dates
                    segReport = cur.report
                } else {
                    if (segType == null) segType = cur.type // the segment keeps its first confident type
                    segNames += cur.names
                    segDates += cur.dates
                    if (segReport == null) segReport = cur.report
                }
            }
        }
        val breakScores = scores.associate { it.page to it.score }
        val segments = starts.mapIndexed { k, s ->
            val e = if (k + 1 < starts.size) starts[k + 1] - 1 else n - 1
            val range = pages.subList(s, e + 1)
            val cls = classifier.classify(range)
            val fl = fields.extractItems(range, cls.type.raw)
            val rn = fl.filter { it.key == "report_name" }.map { it.valueText }
            val fac = fl.filter { it.key == "facility" }.map { it.valueText }
            val label = TYPE_LABEL.getValue(cls.type.raw)
            val title = rn.firstOrNull() ?: if (fac.isNotEmpty()) "$label — ${fac[0]}" else label
            val sc = when {
                s in breakScores -> breakScores.getValue(s)
                starts.size > 1 -> breakScores.getValue(starts[1])
                else -> 0
            }
            SplitSegment(s, e, cls.type, title, RecordText.round2(min(0.95, 0.5 + 0.05 * sc)))
        }
        return Result(scores, segments)
    }

    /** Segments to propose: empty unless ≥ 2 (page_count ≥ 3 PDFs/scans only). */
    fun detect(pageTexts: List<String>, fileTypeIsPdfOrScan: Boolean = true, today: LocalDate = LocalDate.now(), dateOrder: DateOrder = DateOrder.DMY): List<SplitSegment> {
        if (!fileTypeIsPdfOrScan || pageTexts.size < 3) return emptyList()
        return analyze(pageTexts, today, dateOrder).segments.takeIf { it.size >= 2 }.orEmpty()
    }

    companion object {
        private val RE_PAGE_OF = Py.re("(?<![a-z0-9])(?:page|pg\\.?)[ ]?([0-9]{1,3})[ ]?(?:of|/)[ ]?([0-9]{1,3})(?![0-9])")
        private val RE_BARE_OF = Py.re("([0-9]{1,3}) ?(?:of|/) ?([0-9]{1,3})")
        private val RE_CONTINUED = Py.re("(?:continued|contd|cont'd|cont\\.)(?![a-z])")

        /** English type labels used in stored titles (reference TYPE_LABEL). */
        val TYPE_LABEL = mapOf(
            "lab_report" to "Lab Report", "prescription" to "Prescription", "consultation_note" to "Doctor Note",
            "discharge_summary" to "Discharge Summary", "imaging_report" to "Imaging Report",
            "diagnostic_report" to "Diagnostic Report", "medication_list" to "Medication List",
            "vaccination_record" to "Vaccination Record", "bill" to "Bill", "insurance" to "Insurance",
            "personal_note" to "Note", "other" to "Record"
        )
    }
}
