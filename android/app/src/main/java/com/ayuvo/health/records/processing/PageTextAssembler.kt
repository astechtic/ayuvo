package com.ayuvo.health.records.processing

import kotlin.math.abs

/** One recognised text line in page pixels/points, origin top-left. */
data class OcrLine(val text: String, val left: Float, val top: Float, val width: Float, val height: Float, val confidence: Float? = null)

data class AssembledPage(val text: String, val blocksJson: String, val ocrConfidence: Double?)

/** Reading order and row joining for OCR / PDF text lines (docs/health-records.md §9.1). */
object PageTextAssembler {

    fun assemble(lines: List<OcrLine>, pageWidth: Float, pageHeight: Float): AssembledPage {
        val usable = lines.filter { it.text.isNotBlank() }
        val sorted = usable.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, line)) row.add(line) else rows.add(mutableListOf(line))
        }
        val text = rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
        val w = if (pageWidth > 0) pageWidth else 1f
        val h = if (pageHeight > 0) pageHeight else 1f
        val blocks = sorted.map { l ->
            mapOf(
                "t" to l.text.trim(),
                "b" to listOf(round4(l.left / w), round4(l.top / h), round4(l.width / w), round4(l.height / h))
            )
        }
        val confidences = usable.mapNotNull { it.confidence }
        return AssembledPage(
            text = text,
            blocksJson = RecordJson.element(blocks).toString(),
            ocrConfidence = if (confidences.isEmpty()) null else confidences.map(Float::toDouble).average()
        )
    }

    /**
     * §9.1 "vertical centres within half a line height", made tolerant of slight skew: the line is
     * compared with the horizontally nearest member of the current row (so a tilted table row is
     * chained word group by word group instead of against its first box), and "a line height" is the
     * taller of the two boxes (short boxes such as "< 200" have no ascenders or descenders).
     */
    private fun sameRow(row: List<OcrLine>, line: OcrLine): Boolean {
        val center = line.top + line.height / 2
        val lineMid = line.left + line.width / 2
        val nearest = row.minByOrNull { abs((it.left + it.width / 2) - lineMid) } ?: return false
        val tolerance = maxOf(nearest.height, line.height) / 2
        return abs((nearest.top + nearest.height / 2) - center) <= tolerance
    }

    private fun round4(v: Float): Double = Math.round(v.coerceIn(0f, 1f) * 10_000.0) / 10_000.0
}
