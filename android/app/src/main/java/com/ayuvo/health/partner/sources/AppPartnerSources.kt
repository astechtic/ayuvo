package com.ayuvo.health.partner.sources

import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.SleepNight
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.partner.logic.PartnerJson
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Base for sources whose whole set is small and loaded from one blob: render = filter of a full pass. */
abstract class SmallPartnerSource : PartnerSource {
    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val want = ids.toHashSet()
        val out = HashMap<String, SourceRecord>()
        forEach(null) { if (it.recordId in want) out[it.recordId] = it }
        return out
    }

    protected fun inScope(day: String?, dayFrom: String?): Boolean =
        dayFrom == null || day == null || PartnerJson.compareCodePoints(day, dayFrom) >= 0
}

/** `food_entry` from the diary blob (PreferencesStore food entries). A corrupt blob is unavailable, never empty. */
class FoodEntrySource(
    private val entries: suspend () -> List<FoodEntry>,
    private val corrupt: suspend () -> Boolean,
    private val zone: () -> ZoneId
) : SmallPartnerSource() {
    override val type = "food_entry"

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        if (corrupt()) throw SourceUnavailableException("food diary unreadable")
        val z = zone()
        for (e in entries()) {
            val r = PartnerSourceMaps.food(e, z)
            if (inScope(r.day, dayFrom)) onRecord(r)
        }
    }
}

/** `water_day` totals from the water log blob. */
class WaterDaySource(
    private val entries: suspend () -> List<WaterEntry>,
    private val goalMl: suspend () -> Int?,
    private val zone: () -> ZoneId
) : SmallPartnerSource() {
    override val type = "water_day"

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val z = zone()
        val today = LocalDate.now(z).toString()
        for (r in PartnerSourceMaps.waterDays(entries(), z, today, goalMl())) if (inScope(r.day, dayFrom)) onRecord(r)
    }
}

/** `weight` from the app's own weight entries. */
class WeightSource(private val entries: suspend () -> List<WeightEntry>, private val zone: () -> ZoneId) : SmallPartnerSource() {
    override val type = "weight"

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val z = zone()
        for (e in entries()) {
            if (!e.weightKg.isFinite() || e.weightKg <= 0) continue
            val r = PartnerSourceMaps.weight(e, z)
            if (inScope(r.day, dayFrom)) onRecord(r)
        }
    }
}

/**
 * `workout`: the workout diary plus Health Connect / Google Health exercise sessions from `health_samples`.
 * Sessions Ayuvo wrote itself (client id `ayuvo_…`, or its own package as source, or origin 2) are skipped because
 * the diary already holds them; a Google Health copy of a platform session from the same app is skipped too.
 */
class WorkoutSource(
    private val diary: suspend () -> List<WorkoutSession>,
    private val db: () -> SQLiteDatabase?,
    private val ownPackagePrefix: String,
    private val zone: () -> ZoneId
) : SmallPartnerSource() {
    override val type = "workout"

    private class Row(val id: String, val code: Int?, val title: String?, val start: Long, val end: Long, val day: String,
                      val sourceId: String, val clientId: String?, val origin: Int, val sourceName: String?)

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val z = zone()
        for (s in diary()) {
            val r = PartnerSourceMaps.workout(s, z)
            if (inScope(r.day, dayFrom)) onRecord(r)
        }
        val d = db() ?: return
        val rows = ArrayList<Row>()
        var lastStart = Long.MIN_VALUE
        var lastId = ""
        while (true) {
            var n = 0
            d.rawQuery(
                "SELECT s.id, s.category_value, s.title, s.start_ms, s.end_ms, s.local_day, s.source_id, s.client_record_id, s.origin, src.name " +
                    "FROM health_samples s LEFT JOIN health_sources src ON src.id = s.source_id " +
                    "WHERE s.type_id = 'workout' AND s.deleted = 0 AND (s.start_ms > ? OR (s.start_ms = ? AND s.id > ?)) " +
                    "ORDER BY s.start_ms, s.id LIMIT ${HealthSql.PAGE}",
                arrayOf(lastStart.toString(), lastStart.toString(), lastId)
            ).use { c ->
                while (c.moveToNext()) {
                    n++
                    lastStart = c.getLong(3); lastId = c.getString(0)
                    rows += Row(
                        c.getString(0), if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getString(2),
                        c.getLong(3), c.getLong(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7),
                        c.getInt(8), if (c.isNull(9)) null else c.getString(9)
                    )
                }
            }
            if (n < HealthSql.PAGE) break
        }
        val external = rows.filter { r ->
            r.origin != HealthSampleRow.ORIGIN_LOCAL_APP && r.clientId?.startsWith("ayuvo_") != true &&
                !r.sourceId.startsWith(ownPackagePrefix) && !r.sourceId.removePrefix(GH_PREFIX).startsWith(ownPackagePrefix)
        }
        for (r in external) {
            if (r.origin == HealthSampleRow.ORIGIN_GOOGLE_HEALTH) {
                val pkg = r.sourceId.removePrefix(GH_PREFIX)
                val dup = external.any { o -> o.origin != HealthSampleRow.ORIGIN_GOOGLE_HEALTH && o.sourceId == pkg && abs(o.start - r.start) <= DEDUP_MS }
                if (dup) continue
            }
            if (!inScope(r.day, dayFrom)) continue
            onRecord(PartnerSourceMaps.healthWorkout(r.id, r.code, r.title, r.start, r.end, r.day, r.sourceName))
        }
    }

    companion object {
        const val GH_PREFIX = "google_health:"
        const val DEDUP_MS = 120_000L
    }
}

/**
 * `sleep_night`: one row per wake day from [nights] (HealthDataRepository.sleepNights → HealthSleepAnalysis), read
 * a month at a time, with the night's average heart rate, HRV and breathing rate when readings exist.
 */
class SleepNightSource(
    private val nights: suspend (LocalDate, LocalDate) -> List<SleepNight>,
    private val db: () -> SQLiteDatabase,
    private val zone: () -> ZoneId
) : PartnerSource {
    override val type = "sleep_night"

    private fun avg(sql: String, args: Array<String>): Double? =
        db().rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }

    private fun vitals(n: SleepNight): PartnerSourceMaps.NightVitals {
        val a = arrayOf(n.startMs.toString(), n.endMs.toString())
        val hr = avg("SELECT AVG(value) FROM health_series_points WHERE type_id = 'heart_rate' AND t_ms >= ? AND t_ms <= ?", a)
            ?: avg("SELECT AVG(value) FROM health_samples WHERE type_id = 'heart_rate' AND deleted = 0 AND value IS NOT NULL AND start_ms >= ? AND end_ms <= ?", a)
        val hrv = avg("SELECT AVG(value) FROM health_samples WHERE type_id = 'hrv_rmssd' AND deleted = 0 AND value IS NOT NULL AND start_ms >= ? AND start_ms <= ?", a)
        val resp = avg("SELECT AVG(value) FROM health_samples WHERE type_id = 'respiratory_rate' AND deleted = 0 AND value IS NOT NULL AND start_ms >= ? AND start_ms <= ?", a)
        return PartnerSourceMaps.NightVitals(hr, hrv, resp)
    }

    /** Nights whose wake day lies in [from]..[to]; rows of the previous day are included so a night is complete. */
    private suspend fun range(from: LocalDate, to: LocalDate, onRecord: (SourceRecord) -> Unit) {
        val lo = from.toString()
        val hi = to.toString()
        for (n in nights(from.minusDays(1), to)) {
            if (n.nightOf < lo || n.nightOf > hi) continue
            onRecord(PartnerSourceMaps.sleepNight(n, vitals(n)))
        }
    }

    override suspend fun forEach(dayFrom: String?, onRecord: (SourceRecord) -> Unit) {
        val today = LocalDate.now(zone())
        val first = dayFrom?.let { LocalDate.parse(it) } ?: db().rawQuery(
            "SELECT MIN(local_day) FROM health_samples WHERE type_id = 'sleep' AND deleted = 0", null
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) runCatching { LocalDate.parse(c.getString(0)) }.getOrNull() else null } ?: return
        var start = first
        while (!start.isAfter(today)) {
            val end = minOf(start.plusDays(CHUNK_DAYS - 1), today)
            range(start, end, onRecord)
            start = end.plusDays(1)
        }
    }

    override suspend fun render(ids: Collection<String>): Map<String, SourceRecord> {
        val days = ids.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.distinct().sorted()
        val want = ids.toHashSet()
        val out = HashMap<String, SourceRecord>()
        var i = 0
        while (i < days.size) {
            val from = days[i]
            var j = i
            while (j + 1 < days.size && ChronoUnit.DAYS.between(from, days[j + 1]) < CHUNK_DAYS) j++
            range(from, days[j]) { if (it.recordId in want) out[it.recordId] = it }
            i = j + 1
        }
        return out
    }

    companion object {
        const val CHUNK_DAYS = 31L
    }
}
