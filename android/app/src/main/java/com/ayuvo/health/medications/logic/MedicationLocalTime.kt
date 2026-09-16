package com.ayuvo.health.medications.logic

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Constants of `scripts/medications_reference.py` §3 (docs/medications.md). */
object MedicationConstants {
    /** 120 min: a dose is missed this long after its (snoozed) time. */
    const val GRACE_MS = 7_200_000L
    /** 5 min: a dose that came due this recently still fires "now". */
    const val LATE_FIRE_MS = 300_000L
    const val ADHERENCE_WINDOW_MS = 7L * 86_400_000L
    /** 64 pending notifications − 12 reserved for the rest of the app (iOS only; Android arms one alarm). */
    const val IOS_BUDGET = 52
    const val MAX_TIMES = 12
    const val NAME_MAX = 80
    const val STRENGTH_MAX = 40
    const val INSTRUCTIONS_MAX = 200
    const val NOTE_MAX = 200
    const val DOSE_QUANTITY_MAX = 1000.0
    val INTERVAL_HOURS: List<Int> = listOf(1, 2, 3, 4, 6, 8, 12, 24)
    val SNOOZE_MINUTES: List<Int> = listOf(10, 30, 60)
    const val DEFAULT_SNOOZE_MINUTES = 10

    /** Meal-anchored default slots (§13). `1-0-1` = morning / afternoon / night positions of [SLOTS_3]. */
    val SLOTS_1: List<String> = listOf("08:00")
    val SLOTS_2: List<String> = listOf("08:00", "20:00")
    val SLOTS_3: List<String> = listOf("08:00", "14:00", "20:00")
    val SLOTS_4: List<String> = listOf("08:00", "13:00", "18:00", "22:00")
    val SLOT_NIGHT: List<String> = listOf("22:00")
    const val INTERVAL_ANCHOR = "08:00"
    val WEEKLY_DEFAULT_DAYS: List<Int> = listOf(1)

    const val ARCHIVE_FORMAT = "ayuvo-medications"
    const val ARCHIVE_VERSION = 1

    /** `medications_meta` keys. */
    const val META_SCHEMA_VERSION = "schema_version"
    const val META_MISSED_CURSOR = "missed_materialized_until_ms"
    const val META_LAST_PLANNED = "last_planned_ms"
    /** Default look-back of the missed-dose cursor on first run. */
    const val MISSED_LOOKBACK_MS = 30L * 86_400_000L
}

/**
 * Local wall-clock resolution (docs §5). `LocalDateTime.atZone(zone)` implements exactly the
 * reference's `fold=0` rule: a time inside a spring-forward gap lands gap-length later on the
 * wall clock (02:30 → 03:30), a time inside a fall-back overlap is the EARLIER instant.
 */
object MedicationLocalTime {
    private val hhmm = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$")
    private val ymd = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** `'HH:mm'` → (hour, minute) or null. */
    fun parseHhmm(s: String?): Pair<Int, Int>? {
        val m = hhmm.matchEntire(s ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** `'yyyy-MM-dd'` → [LocalDate] or null (calendar-checked). */
    fun parseDate(s: String?): LocalDate? {
        val m = ymd.matchEntire(s ?: return null) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    fun formatDate(d: LocalDate): String = d.format(dateFormat)

    fun formatHhmm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    fun zone(timeZone: String): ZoneId = ZoneId.of(timeZone)

    /** Epoch ms of the local wall-clock time on the date in the zone (`local_instant`). */
    fun instant(date: String, hhmm: String, timeZone: String): Long {
        val d = parseDate(date) ?: throw IllegalArgumentException("bad date $date")
        val hm = parseHhmm(hhmm) ?: throw IllegalArgumentException("bad time $hhmm")
        return instant(d, hm.first, hm.second, zone(timeZone))
    }

    fun instant(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** `yyyy-MM-dd` of an instant in the zone. */
    fun localDateOf(ms: Long, timeZone: String): String = formatDate(localDate(ms, zone(timeZone)))

    fun localHhmmOf(ms: Long, timeZone: String): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone(timeZone))
        return formatHhmm(t.hour, t.minute)
    }

    fun nextDate(date: String): String = formatDate(parseDate(date)!!.plusDays(1))

    /** `[start_ms, end_ms)` of a local calendar day. */
    fun dayWindow(date: String, timeZone: String): Pair<Long, Long> =
        instant(date, "00:00", timeZone) to instant(nextDate(date), "00:00", timeZone)

    /** Sort key for medication names: lowercase, single spaces, trimmed. */
    fun foldName(s: String?): String = (s ?: "").lowercase().split(' ', '\t', '\n', '\r', '', '')
        .filter { it.isNotEmpty() }.joinToString(" ")

    /** Unicode code points, the reference's `len()`. */
    fun codePoints(s: String): Int = s.codePointCount(0, s.length)
}
