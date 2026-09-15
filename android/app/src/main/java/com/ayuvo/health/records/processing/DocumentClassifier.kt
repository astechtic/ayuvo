package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordType
import kotlin.math.min

/** Keyword classifier over folded lines (docs/health-records.md §10), ported from the reference `classify`. */
class DocumentClassifier(val config: RecordTypesConfig) {

    data class Classification(
        val type: RecordType,
        val category: RecordCategory,
        val confidence: Double,
        /** Score of the best type (even when the result is `other`). */
        val score: Double,
        /** Per type, in file order. */
        val scores: Map<RecordType, Double>
    )

    /** Rules match line by line; each rule counts once per scope. */
    fun scoreLines(linesByPage: List<List<TextLine>>): LinkedHashMap<RecordType, Double> {
        val head = if (linesByPage.isNotEmpty()) TextLine.head(linesByPage[0]).map { it.f } else emptyList()
        val body = linesByPage.flatten().map { it.f }.filter { it.isNotEmpty() }
        val scores = LinkedHashMap<RecordType, Double>()
        for (t in config.types) {
            var s = 0.0
            for (r in t.rules) {
                val scope = if (r.scope == RecordTypesConfig.Scope.HEAD) head else body
                if (scope.any { r.pattern.containsMatchIn(it) }) s += r.weight
            }
            scores[t.type] = s
        }
        return scores
    }

    fun classify(pageTexts: List<String>): Classification =
        classifyLines(pageTexts.mapIndexed { i, t -> TextLine.pageLines(t, i) })

    fun classifyLines(linesByPage: List<List<TextLine>>): Classification {
        val scores = scoreLines(linesByPage)
        val entries = scores.entries.toList()
        if (entries.isEmpty()) return Classification(RecordType.OTHER, RecordCategory.OTHER, 0.0, 0.0, scores)
        var bestI = 0
        for (i in entries.indices) if (entries[i].value > entries[bestI].value) bestI = i
        val best = entries[bestI].value
        val second = entries.filterIndexed { i, _ -> i != bestI }.maxOfOrNull { it.value } ?: 0.0
        if (best >= config.threshold && best - second >= config.margin) {
            val type = entries[bestI].key
            // Grows with the lead beyond the margin (capped at 3) and the absolute score (capped at 2 × threshold).
            val excess = min(best - second - config.margin, 3.0)
            val strength = min(best / config.threshold, 2.0)
            val conf = RecordText.round2(min(0.95, 0.55 + 0.10 * excess + 0.05 * strength))
            return Classification(type, config.rulesFor(type)?.category ?: type.defaultCategory, conf, best, scores)
        }
        return Classification(RecordType.OTHER, RecordCategory.OTHER, 0.0, best, scores)
    }

    /** Boundary helper: best type of one page by score alone (margin ignored); null below threshold. */
    fun pageBestType(lines: List<TextLine>): RecordType? {
        val entries = scoreLines(listOf(lines)).entries.toList()
        if (entries.isEmpty()) return null
        var bestI = 0
        for (i in entries.indices) if (entries[i].value > entries[bestI].value) bestI = i
        return if (entries[bestI].value >= config.threshold) entries[bestI].key else null
    }

    companion object {
        const val HEAD_LINES = 25

        fun round2(value: Double): Double = RecordText.round2(value)
    }
}
