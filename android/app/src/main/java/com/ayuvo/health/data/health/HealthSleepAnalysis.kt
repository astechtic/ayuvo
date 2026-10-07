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
    val sourceId: String,
    /** Asleep seconds of the wake day's other episodes (naps); never part of [startMs]..[endMs]. */
    val napS: Double = 0.0,
    /** How many nap episodes the wake day had. */
    val naps: Int = 0
)

/**
 * Nights are derived at read time (never stored), with the same episode rule as the derived contract's
 * `sleep_nights` algorithm 3 (scripts/derived_reference.py): rows sorted by (start, id) chain into episodes while the
 * next row starts at most [EPISODE_GAP_MS] after the episode's latest end; an episode belongs to the stored
 * `local_day` of its latest-ending row (the wake day). Per wake day the main episode is the one whose best source has
 * the most asleep time (ties: the later-ending episode); the others are naps, reported in [SleepNight.napS] and never
 * widening the night. Within the main episode the source with the most unioned asleep time wins (ties: more rows,
 * then the smaller source id); overlapping intervals within that source are unioned and other sources are ignored.
 * `out_of_bed` rows never count towards in-bed or asleep time. Identical on iOS.
 */
object HealthSleepAnalysis {
    /** Rows closer than this belong to one sleep episode (derived `episode_gap_hours`). */
    const val EPISODE_GAP_MS = 3 * 3_600_000L

    fun nights(rows: List<HealthSampleRow>): List<SleepNight> {
        val live = rows.filter { !it.deleted }.sortedWith(compareBy<HealthSampleRow> { it.startMs }.thenBy { it.id })
        val groups = java.util.TreeMap<String, MutableList<List<HealthSampleRow>>>()
        val episode = ArrayList<HealthSampleRow>()
        var end = 0L

        fun flush() {
            if (episode.isEmpty()) return
            var last = episode[0]
            for (r in episode) if (r.endMs > last.endMs) last = r
            groups.getOrPut(last.localDay) { ArrayList() }.add(ArrayList(episode))
        }

        for (r in live) {
            if (episode.isNotEmpty() && r.startMs > end + EPISODE_GAP_MS) {
                flush()
                episode.clear()
            }
            if (episode.isEmpty()) end = r.endMs
            episode += r
            end = maxOf(end, r.endMs)
        }
        flush()
        return groups.mapNotNull { (day, episodes) -> nightOf(day, episodes) }
    }

    /** The night of [day] from rows that carry that `local_day` (rollups rebuild one day at a time). */
    fun nightFor(day: String, rows: List<HealthSampleRow>): SleepNight? = nights(rows).firstOrNull { it.nightOf == day }

    private class Pick(val source: String, val rows: List<HealthSampleRow>, val asleepS: Double, val episodeEnd: Long)

    private fun bestSource(episode: List<HealthSampleRow>): Pick? {
        val live = episode.filter { it.categoryValue != HealthSleepCodes.OUT_OF_BED }
        if (live.isEmpty()) return null
        val bySource = live.groupBy { it.sourceId }
        val asleep = bySource.mapValues { (_, r) -> unionSeconds(r.filter { it.categoryValue in HealthSleepCodes.ASLEEP }) }
        val src = bySource.keys.sortedWith(
            compareByDescending<String> { asleep.getValue(it) }.thenByDescending { bySource.getValue(it).size }.thenBy { it }
        )[0]
        return Pick(src, bySource.getValue(src), asleep.getValue(src), episode.maxOf { it.endMs })
    }

    private fun nightOf(day: String, episodes: List<List<HealthSampleRow>>): SleepNight? {
        val picks = episodes.mapNotNull { bestSource(it) }
        if (picks.isEmpty()) return null
        val main = picks.sortedWith(compareByDescending<Pick> { it.asleepS }.thenByDescending { it.episodeEnd })[0]
        var napS = 0.0
        var naps = 0
        for (p in picks) if (p !== main && p.asleepS > 0) {
            napS += p.asleepS
            naps += 1
        }
        val sourceRows = main.rows
        val inBedRows = sourceRows.filter { it.categoryValue == HealthSleepCodes.IN_BED }
        val inBed = if (inBedRows.isNotEmpty()) unionSeconds(inBedRows) else unionSeconds(sourceRows)
        return SleepNight(
            nightOf = day,
            startMs = sourceRows.minOf { it.startMs },
            endMs = sourceRows.maxOf { it.endMs },
            inBedS = inBed,
            asleepS = main.asleepS,
            lightS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.LIGHT }),
            deepS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.DEEP }),
            remS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.REM }),
            awakeS = unionSeconds(sourceRows.filter { it.categoryValue == HealthSleepCodes.AWAKE }),
            sourceId = main.source,
            napS = napS,
            naps = naps
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
