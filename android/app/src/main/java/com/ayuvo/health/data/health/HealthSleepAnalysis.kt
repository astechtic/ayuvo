package com.ayuvo.health.data.health

/** Canonical sleep stage codes shared with iOS (`category_value` on `sleep` rows). */
object HealthSleepCodes {
    const val IN_BED = 0
    const val ASLEEP_UNSPECIFIED = 1
    const val AWAKE = 2
    const val LIGHT = 3
    const val DEEP = 4
    const val REM = 5
    const val OUT_OF_BED = 6

    val ASLEEP: Set<Int> = setOf(ASLEEP_UNSPECIFIED, LIGHT, DEEP, REM)

    fun label(code: Int?): String = when (code) {
        IN_BED -> "in_bed"
        ASLEEP_UNSPECIFIED -> "asleep_unspecified"
        AWAKE -> "awake"
        LIGHT -> "light"
        DEEP -> "deep"
        REM -> "rem"
        OUT_OF_BED -> "out_of_bed"
        else -> "unknown"
    }
}

data class SleepNight(
    /** Wake day, yyyy-MM-dd. */
    val nightOf: String,
    val startMs: Long,
    val endMs: Long,
    val inBedS: Double,
    val asleepS: Double,
    val lightS: Double,
    val deepS: Double,
    val remS: Double,
    val awakeS: Double,
    val sourceId: String
)

/**
 * Nights are derived at read time (never stored): per wake day pick the source with the
 * longest asleep time, union overlapping intervals within that source and ignore other
 * sources. `out_of_bed` rows never count towards in-bed or asleep time. Identical on iOS.
 */
object HealthSleepAnalysis {

    fun nights(rows: List<HealthSampleRow>): List<SleepNight> =
        rows.asSequence()
            .filter { !it.deleted }
            .groupBy { it.localDay }
            .mapNotNull { (day, dayRows) -> nightFor(day, dayRows) }
            .sortedBy { it.nightOf }

    fun nightFor(day: String, rows: List<HealthSampleRow>): SleepNight? {
        val live = rows.filter { !it.deleted && it.categoryValue != HealthSleepCodes.OUT_OF_BED }
        if (live.isEmpty()) return null
        val bySource = live.groupBy { it.sourceId }
        val chosen = bySource.maxWithOrNull(
            compareBy<Map.Entry<String, List<HealthSampleRow>>> { (_, r) -> unionSeconds(r.filter { it.categoryValue in HealthSleepCodes.ASLEEP }) }
                .thenBy { (_, r) -> unionSeconds(r) }
                .thenBy { it.key }
        ) ?: return null
        val sourceRows = chosen.value
        val asleepRows = sourceRows.filter { it.categoryValue in HealthSleepCodes.ASLEEP }
        val inBedRows = sourceRows.filter { it.categoryValue == HealthSleepCodes.IN_BED }
        val inBed = if (inBedRows.isNotEmpty()) unionSeconds(inBedRows) else unionSeconds(sourceRows)
        return SleepNight(
            nightOf = day,
            startMs = sourceRows.minOf { it.startMs },
            endMs = sourceRows.maxOf { it.endMs },
            inBedS = inBed,
            asleepS = unionSeconds(asleepRows),
            lightS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.LIGHT }),
            deepS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.DEEP }),
            remS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.REM }),
            awakeS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.AWAKE }),
            sourceId = chosen.key
        )
    }

    /** Total seconds covered by the union of the rows' `[start, end)` intervals. */
    fun unionSeconds(rows: List<HealthSampleRow>): Double {
        if (rows.isEmpty()) return 0.0
        val sorted = rows.map { it.startMs to maxOf(it.startMs, it.endMs) }.sortedBy { it.first }
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) {
                if (e > curEnd) curEnd = e
            } else {
                total += curEnd - curStart
                curStart = s
                curEnd = e
            }
        }
        total += curEnd - curStart
        return total / 1000.0
    }
}
