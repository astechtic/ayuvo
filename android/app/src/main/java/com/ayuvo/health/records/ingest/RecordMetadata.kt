package com.ayuvo.health.records.ingest

import java.time.DateTimeException
import java.time.LocalDate

/** Title derivation (docs/health-records.md §4.4). */
object RecordTitles {
    const val UNTITLED = "Untitled record"
    const val MAX_LENGTH = 200

    /**
     * User-entered title, else the filename without its extension (underscores and dashes
     * become spaces), else "<Type> — <date>" when both parts are known, else [UNTITLED].
     */
    fun derive(userTitle: String?, filename: String?, typeLabel: String?, dateLabel: String?): String {
        userTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(MAX_LENGTH) }
        fromFilename(filename)?.let { return it }
        if (!typeLabel.isNullOrBlank() && !dateLabel.isNullOrBlank()) return "$typeLabel — $dateLabel"
        return UNTITLED
    }

    fun fromFilename(filename: String?): String? {
        val base = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null
        val stem = if (base.lastIndexOf('.') > 0) base.substringBeforeLast('.') else base
        val cleaned = stem.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        return cleaned.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)
    }
}

/** Basic metadata dates (§4.6): PDF /CreationDate, EXIF DateTimeOriginal, filename patterns. */
object RecordDates {
    const val PDF_SCAN_BYTES = 64 * 1024
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100

    private val isoDashed = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val compact = Regex("(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)")
    private val dayFirst = Regex("(?<!\\d)(\\d{2})-(\\d{2})-(\\d{4})(?!\\d)")
    private val exif = Regex("^\\s*(\\d{4})[:\\-](\\d{2})[:\\-](\\d{2})")
    private val pdfCreation = Regex("/CreationDate\\s*\\(\\s*(?:D:)?(\\d{4})(\\d{2})(\\d{2})")

    /** First plausible `yyyy-MM-dd`, `yyyyMMdd` or `dd-MM-yyyy` date in a filename. */
    fun fromFilename(filename: String?, today: LocalDate = LocalDate.now()): LocalDate? {
        if (filename.isNullOrBlank()) return null
        val name = filename.substringAfterLast('/')
        isoDashed.findAll(name).forEach { m -> date(m, 1, 2, 3, today)?.let { return it } }
        compact.findAll(name).forEach { m -> date(m, 1, 2, 3, today)?.let { return it } }
        dayFirst.findAll(name).forEach { m -> date(m, 3, 2, 1, today)?.let { return it } }
        return null
    }

    /** EXIF `DateTimeOriginal` ("yyyy:MM:dd HH:mm:ss"). */
    fun fromExif(value: String?, today: LocalDate = LocalDate.now()): LocalDate? {
        val m = value?.let(exif::find) ?: return null
        return date(m, 1, 2, 3, today)
    }

    /** `/CreationDate (D:yyyyMMdd…` found in a PDF byte window (header or trailer). */
    fun fromPdfBytes(bytes: ByteArray, today: LocalDate = LocalDate.now()): LocalDate? {
        if (bytes.isEmpty()) return null
        // ISO-8859-1 maps every byte to one char, so binary streams never break the scan.
        val text = String(bytes, Charsets.ISO_8859_1)
        pdfCreation.findAll(text).forEach { m -> date(m, 1, 2, 3, today)?.let { return it } }
        return null
    }

    private fun date(m: MatchResult, yearGroup: Int, monthGroup: Int, dayGroup: Int, today: LocalDate): LocalDate? {
        val year = m.groupValues[yearGroup].toInt()
        val month = m.groupValues[monthGroup].toInt()
        val day = m.groupValues[dayGroup].toInt()
        if (year !in MIN_YEAR..MAX_YEAR) return null
        val parsed = try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            return null
        }
        // A document dated more than a day in the future is a mis-read, not a record date.
        return parsed.takeIf { !it.isAfter(today.plusDays(1)) }
    }
}
