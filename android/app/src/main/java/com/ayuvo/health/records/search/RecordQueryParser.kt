package com.ayuvo.health.records.search

import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.processing.DateDetector
import com.ayuvo.health.records.processing.DateOrder
import com.ayuvo.health.records.processing.RecordText
import java.time.LocalDate

/** A recognised part of the query shown as a removable chip; [token] is the query text it came from. */
data class ParsedChip(val kind: String, val label: String, val token: String) {
    val key: String get() = "$kind:$token"
}

/** `RecordQueryParser.parse` output (docs/health-records.md §17, reference `parse_query`). */
data class ParsedRecordQuery(
    val terms: List<String> = emptyList(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
    /** In RECORD_TYPES order. */
    val recordTypes: Set<RecordType> = emptySet(),
    /** In abnormal, low, high, critical order. */
    val flags: Set<String> = emptySet(),
    val doctor: String? = null,
    val facility: String? = null,
    val favorites: Boolean = false,
    /** `source = received`. */
    val received: Boolean = false,
    val needsReview: Boolean = false,
    val archived: Boolean = false,
    val chips: List<ParsedChip> = emptyList(),
    /** FTS4 MATCH expression (`term*` joined by spaces) or null. */
    val match: String? = null
) {
    val source: String? get() = if (received) "received" else null

    fun toFilters(): RecordAdvancedFilters = RecordAdvancedFilters(
        dateFrom = dateFrom,
        dateTo = dateTo,
        doctor = doctor,
        facility = facility,
        types = recordTypes,
        flags = flags,
        favorites = favorites,
        received = received,
        needsReview = needsReview,
        archived = archived
    )
}

/** Pure universal-search parser, ported line by line from `scripts/records_reference.py` `parse_query`. */
object RecordQueryParser {

    private data class Tok(val kind: String, val word: String?, val date: LocalDate?, val text: String) {
        val v: String? get() = word
    }

    private val Q_TYPES: List<Pair<List<String>, List<String>>> = listOf(
        listOf("blood", "tests") to listOf("lab_report"), listOf("blood", "test") to listOf("lab_report"),
        listOf("blood", "reports") to listOf("lab_report"), listOf("blood", "report") to listOf("lab_report"),
        listOf("test", "reports") to listOf("lab_report"), listOf("test", "report") to listOf("lab_report"),
        listOf("lab", "reports") to listOf("lab_report"), listOf("lab", "report") to listOf("lab_report"),
        listOf("doctor", "notes") to listOf("consultation_note"), listOf("doctor", "note") to listOf("consultation_note"),
        listOf("discharge", "summary") to listOf("discharge_summary"), listOf("discharge", "summaries") to listOf("discharge_summary"),
        listOf("x", "ray") to listOf("imaging_report"), listOf("x", "rays") to listOf("imaging_report"),
        listOf("ct", "scan") to listOf("imaging_report"), listOf("ct", "scans") to listOf("imaging_report"),
        listOf("mri", "scan") to listOf("imaging_report"),
        listOf("reports") to listOf("lab_report", "diagnostic_report", "imaging_report"),
        listOf("report") to listOf("lab_report", "diagnostic_report", "imaging_report"),
        listOf("lab") to listOf("lab_report"), listOf("labs") to listOf("lab_report"),
        listOf("prescription") to listOf("prescription"), listOf("prescriptions") to listOf("prescription"), listOf("rx") to listOf("prescription"),
        listOf("medicine") to listOf("prescription"), listOf("medicines") to listOf("prescription"),
        listOf("scan") to listOf("imaging_report"), listOf("scans") to listOf("imaging_report"), listOf("imaging") to listOf("imaging_report"),
        listOf("xray") to listOf("imaging_report"), listOf("xrays") to listOf("imaging_report"), listOf("mri") to listOf("imaging_report"),
        listOf("ct") to listOf("imaging_report"), listOf("ultrasound") to listOf("imaging_report"), listOf("usg") to listOf("imaging_report"),
        listOf("discharge") to listOf("discharge_summary"),
        listOf("consultation") to listOf("consultation_note"), listOf("consultations") to listOf("consultation_note"),
        listOf("visit") to listOf("consultation_note"), listOf("visits") to listOf("consultation_note"),
        listOf("bill") to listOf("bill"), listOf("bills") to listOf("bill"), listOf("invoice") to listOf("bill"), listOf("invoices") to listOf("bill"),
        listOf("receipt") to listOf("bill"), listOf("receipts") to listOf("bill"),
        listOf("insurance") to listOf("insurance"), listOf("claim") to listOf("insurance"), listOf("claims") to listOf("insurance"),
        listOf("vaccine") to listOf("vaccination_record"), listOf("vaccines") to listOf("vaccination_record"),
        listOf("vaccination") to listOf("vaccination_record"), listOf("vaccinations") to listOf("vaccination_record"),
        listOf("note") to listOf("personal_note"), listOf("notes") to listOf("personal_note")
    )
    private val Q_TYPE_AND_TERM = setOf("mri", "ct", "xray", "ultrasound", "usg")
    private val Q_FLAGS: List<Pair<List<String>, String>> = listOf(
        listOf("out", "of", "range") to "abnormal", listOf("abnormal") to "abnormal", listOf("abnormalities") to "abnormal",
        listOf("abnormality") to "abnormal", listOf("low") to "low", listOf("high") to "high", listOf("elevated") to "high",
        listOf("raised") to "high", listOf("critical") to "critical"
    )
    private val Q_STATES: List<Pair<List<String>, String>> = listOf(
        listOf("shared", "with", "me") to "source", listOf("needs", "review") to "needs_review",
        listOf("to", "review") to "needs_review", listOf("favorites") to "favorites", listOf("favorite") to "favorites",
        listOf("favourites") to "favorites", listOf("favourite") to "favorites", listOf("starred") to "favorites",
        listOf("received") to "source", listOf("archived") to "archived"
    )
    private val Q_STOP = setOf(
        "a", "an", "the", "my", "of", "with", "where", "was", "were", "is", "are", "from", "for", "in",
        "on", "show", "find", "all", "me", "and", "or", "to", "by", "at", "any", "which", "that",
        "had", "has", "have", "please", "get", "list", "records", "record", "documents", "document",
        "files", "file", "last", "latest", "recent", "this", "since", "before", "after", "between",
        "doctor", "dr", "hospital"
    )
    private val Q_ORDER_FLAGS = listOf("abnormal", "low", "high", "critical")
    private val Q_MONTH_FULL = setOf("january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december")
    private val Q_PERIODS = mapOf("day" to "day", "days" to "day", "week" to "week", "weeks" to "week", "month" to "month", "months" to "month", "year" to "year", "years" to "year")
    private val RESERVED: Set<String> = Q_TYPES.flatMap { it.first }.toSet() + Q_FLAGS.flatMap { it.first } + Q_STATES.flatMap { it.first } + Q_STOP
    private val YEAR4 = Regex("[0-9]{4}")
    private val NUM3 = Regex("[0-9]{1,3}")

    private fun tokens(text: String, today: LocalDate, order: DateOrder): List<Tok> {
        val f = RecordText.fold(text).split('\n').joinToString(" ")
        val toks = mutableListOf<Tok>()
        var pos = 0
        for (c in DateDetector.candidates(f, today, order)) {
            if (c.precision != "day") continue
            RecordText.words(f.substring(pos, c.start)).forEach { toks += Tok("w", it, null, it) }
            toks += Tok("date", null, c.dates[0], f.substring(c.start, c.end))
            pos = c.end
        }
        RecordText.words(f.substring(pos)).forEach { toks += Tok("w", it, null, it) }
        return toks
    }

    private fun periodRange(unit: String, start: LocalDate): Pair<LocalDate, LocalDate> = when (unit) {
        "day" -> start to start
        "week" -> start.minusDays((start.dayOfWeek.value - 1).toLong()).let { it to it.plusDays(6) }
        "month" -> start.withDayOfMonth(1).let { it to DateDetector.addMonths(it, 1).minusDays(1) }
        else -> LocalDate.of(start.year, 1, 1) to LocalDate.of(start.year, 12, 31)
    }

    private data class Spec(val from: LocalDate, val to: LocalDate, val consumed: Int)

    private fun dateSpec(toks: List<Tok>, i: Int, today: LocalDate, allowBareAbbrev: Boolean): Spec? {
        if (i >= toks.size) return null
        val t = toks[i]
        if (t.kind == "date") return Spec(t.date!!, t.date, 1)
        val v = t.v!!
        if (v == "today") return Spec(today, today, 1)
        if (v == "yesterday") return today.minusDays(1).let { Spec(it, it, 1) }
        val nxt = toks.getOrNull(i + 1)?.takeIf { it.kind == "w" }?.v
        val nxt2 = toks.getOrNull(i + 2)?.takeIf { it.kind == "w" }?.v
        if ((v == "this" || v == "current") && nxt in setOf("week", "month", "year")) {
            return periodRange(nxt!!, today).let { Spec(it.first, it.second, 2) }
        }
        if (v in setOf("last", "past", "previous") && nxt in setOf("week", "month", "year")) {
            val ref = when (nxt) {
                "week" -> today.minusDays(7)
                "month" -> DateDetector.addMonths(today.withDayOfMonth(1), -1)
                else -> LocalDate.of(today.year - 1, 1, 1)
            }
            return periodRange(nxt!!, ref).let { Spec(it.first, it.second, 2) }
        }
        if (v in setOf("last", "past", "previous") && nxt != null && NUM3.matches(nxt) && nxt2 in Q_PERIODS) {
            val n = nxt.toInt()
            val s = when (Q_PERIODS.getValue(nxt2!!)) {
                "day" -> today.minusDays(n.toLong())
                "week" -> today.minusDays(7L * n)
                "month" -> DateDetector.addMonths(today, -n)
                else -> DateDetector.addYears(today, -n)
            }
            return Spec(s, today, 3)
        }
        DateDetector.MONTH_NUM[v]?.let { mo ->
            if (nxt != null && YEAR4.matches(nxt) && nxt.toInt() in 1900..2100) {
                val s = LocalDate.of(nxt.toInt(), mo, 1)
                return Spec(s, DateDetector.addMonths(s, 1).minusDays(1), 2)
            }
            if (v in Q_MONTH_FULL || allowBareAbbrev) {
                val y = if (mo <= today.monthValue) today.year else today.year - 1
                val s = LocalDate.of(y, mo, 1)
                return Spec(s, DateDetector.addMonths(s, 1).minusDays(1), 1)
            }
            return null
        }
        if (YEAR4.matches(v) && v.toInt() in 1900..2100) return Spec(LocalDate.of(v.toInt(), 1, 1), LocalDate.of(v.toInt(), 12, 31), 1)
        return null
    }

    private fun <T> matchPhrase(toks: List<Tok>, i: Int, table: List<Pair<List<String>, T>>): Pair<List<String>, T>? {
        for ((phrase, value) in table) {
            val n = phrase.size
            if (i + n <= toks.size && (0 until n).all { k -> toks[i + k].kind == "w" && toks[i + k].v == phrase[k] }) return phrase to value
        }
        return null
    }

    fun parse(text: String, today: LocalDate, dateOrder: DateOrder = DateOrder.DMY): ParsedRecordQuery {
        val toks = tokens(text, today, dateOrder)
        val terms = mutableListOf<String>()
        val types = HashSet<String>()
        val flags = HashSet<String>()
        val ranges = mutableListOf<Pair<LocalDate?, LocalDate?>>()
        val chips = mutableListOf<ParsedChip>()
        var doctor: String? = null
        var facility: String? = null
        var favorites = false
        var needsReview = false
        var archived = false
        var received = false
        fun chip(kind: String, a: Int, b: Int) {
            val t = toks.subList(a, b).joinToString(" ") { it.text }
            chips += ParsedChip(kind, t, t)
        }
        var i = 0
        loop@ while (i < toks.size) {
            val tok = toks[i]
            val v = tok.v
            if (tok.kind == "w" && (v == "from" || v == "between")) {
                val a = dateSpec(toks, i + 1, today, true)
                if (a != null) {
                    val j = i + 1 + a.consumed
                    if (j < toks.size && toks[j].text in setOf("to", "and", "till", "until")) {
                        val b = dateSpec(toks, j + 1, today, true)
                        if (b != null) {
                            ranges += a.from to b.to
                            chip("date", i, j + 1 + b.consumed)
                            i = j + 1 + b.consumed
                            continue@loop
                        }
                    }
                    ranges += a.from to a.to
                    chip("date", i, j)
                    i = j
                    continue@loop
                }
            }
            if (tok.kind == "w" && v in setOf("since", "after", "before", "in", "during")) {
                val a = dateSpec(toks, i + 1, today, true)
                if (a != null) {
                    ranges += when (v) {
                        "since" -> a.from to null
                        "after" -> a.to.plusDays(1) to null
                        "before" -> null to a.from.minusDays(1)
                        else -> a.from to a.to
                    }
                    chip("date", i, i + 1 + a.consumed)
                    i += 1 + a.consumed
                    continue@loop
                }
            }
            val spec = dateSpec(toks, i, today, false)
            if (spec != null) {
                ranges += spec.from to spec.to
                chip("date", i, i + spec.consumed)
                i += spec.consumed
                continue@loop
            }
            if (tok.kind != "w") { i++; continue@loop }
            matchPhrase(toks, i, Q_STATES)?.let { (phrase, what) ->
                when (what) {
                    "favorites" -> favorites = true
                    "needs_review" -> needsReview = true
                    "archived" -> archived = true
                    else -> received = true
                }
                chip(what, i, i + phrase.size)
                i += phrase.size
                continue@loop
            }
            matchPhrase(toks, i, Q_TYPES)?.let { (phrase, vals) ->
                types += vals
                chip("type", i, i + phrase.size)
                if (phrase.size == 1 && phrase[0] in Q_TYPE_AND_TERM && phrase[0] !in terms) terms += phrase[0]
                i += phrase.size
                continue@loop
            }
            matchPhrase(toks, i, Q_FLAGS)?.let { (phrase, value) ->
                flags += value
                chip("flag", i, i + phrase.size)
                i += phrase.size
                continue@loop
            }
            if (v in setOf("doctor", "dr", "at", "hospital")) {
                var j = i + 1
                val name = mutableListOf<String>()
                while (j < toks.size && toks[j].kind == "w" && name.size < 3 && toks[j].v !in RESERVED && dateSpec(toks, j, today, false) == null) {
                    name += toks[j].v!!
                    j++
                }
                if (name.isNotEmpty()) {
                    if (v == "doctor" || v == "dr") { doctor = name.joinToString(" "); chip("doctor", i, j) } else { facility = name.joinToString(" "); chip("facility", i, j) }
                    i = j
                    continue@loop
                }
            }
            if (v !in Q_STOP && v !in terms) terms += v!!
            i++
        }
        var dateFrom: String? = null
        var dateTo: String? = null
        if (ranges.isNotEmpty()) {
            dateFrom = ranges.mapNotNull { it.first }.maxOrNull()?.toString()
            dateTo = ranges.mapNotNull { it.second }.minOrNull()?.toString()
        }
        val orderedTypes = RecordType.entries.filter { it.raw in types }.toCollection(LinkedHashSet())
        val orderedFlags = Q_ORDER_FLAGS.filter { it in flags }.toCollection(LinkedHashSet())
        return ParsedRecordQuery(
            terms = terms,
            dateFrom = dateFrom,
            dateTo = dateTo,
            recordTypes = orderedTypes,
            flags = orderedFlags,
            doctor = doctor,
            facility = facility,
            favorites = favorites,
            received = received,
            needsReview = needsReview,
            archived = archived,
            chips = chips,
            match = if (terms.isEmpty()) null else terms.joinToString(" ") { "$it*" }
        )
    }
}
