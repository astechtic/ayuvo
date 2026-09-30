package com.ayuvo.health.data.workout

import com.ayuvo.health.data.derived.DerivedMath
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WorkoutHeartRateStats
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Heart rate for workouts (docs/workouts-gps.md §3): the samples during a workout, the person's resting and maximum
 * heart rate, and suggested strength windows. Samples come from Health Connect directly when it can be read (fresh
 * watch data), otherwise from the local Health mirror; one source is used, never averaged across devices.
 *
 * Resting heart rate: the platform's `resting_heart_rate` (native wins), else Ayuvo's `resting_hr_derived`
 * estimate, as the recent median of up to [RHR_LOOKBACK_DAYS] days. Maximum heart rate: Tanaka from the birthday.
 */
class WorkoutHeartRateSource(
    private val mirror: suspend () -> HealthDataStore?,
    /** Health Connect heart-rate samples in [from, to), or null when Health Connect cannot be read. */
    private val direct: suspend (Instant, Instant) -> List<HrSample>?,
    private val profile: suspend () -> UserProfile?,
    private val weightKg: suspend () -> Double?,
    private val config: () -> WorkoutConfig,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    data class Person(val hrMax: Double?, val rhr: Double?, val sex: String?, val age: Double?, val weightKg: Double?)

    suspend fun person(day: LocalDate): Person {
        val p = profile()
        val age = p?.birthday?.let { Period.between(LocalDate.ofInstant(it, zone()), day).years.toDouble() }?.takeIf { it in 10.0..110.0 }
        return Person(
            hrMax = age?.let(GpsWorkoutAnalysis::tanakaHrMax),
            rhr = restingHr(day),
            sex = when (p?.gender) { Gender.MALE -> "male"; Gender.FEMALE -> "female"; else -> null },
            age = age,
            weightKg = weightKg()?.takeIf { it.isFinite() && it > 0 } ?: p?.weightKg?.takeIf { it > 0 }
        )
    }

    /** Heart-rate samples in [fromMs, toMs), Health Connect first, then the mirror. */
    suspend fun samples(fromMs: Long, toMs: Long): List<HrSample> {
        if (toMs <= fromMs) return emptyList()
        val live = runCatching { direct(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)) }.getOrNull()
        if (!live.isNullOrEmpty()) return live.sortedBy { it.tMs }
        return mirrorSamples(fromMs, toMs)
    }

    /** `hr_workout` over [startMs, endMs), or null without any heart rate in the window. */
    suspend fun stats(startMs: Long, endMs: Long, samples: List<HrSample>? = null): WorkoutHeartRateStats? {
        val hr = samples ?: samples(startMs, endMs)
        if (hr.none { it.tMs in startMs until endMs }) return null
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(startMs), zone())
        return statsFor(hr, startMs, endMs, person(day), config())
    }

    /** Suggested strength-session windows on [day] from the mirror's minute heart rate. */
    suspend fun windows(day: LocalDate): List<HrWindow> {
        val z = zone()
        val from = DerivedMath.dayStartMs(day, z)
        val to = DerivedMath.dayStartMs(day.plusDays(1), z)
        val series = minuteSeries(mirrorSamples(from, to)) ?: return emptyList()
        val who = person(day)
        val result = HeartRateWorkout.workoutWindows(WorkoutWindowsInput(series, who.rhr, who.hrMax ?: 0.0), config())
        return result.windows
    }

    suspend fun mirrorSamples(fromMs: Long, toMs: Long): List<HrSample> {
        val db = mirror() ?: return emptyList()
        val rows = runCatching { db.samplesBetween(HEART_RATE, fromMs, toMs) }.getOrNull().orEmpty().filter { !it.deleted }
        if (rows.isEmpty()) return emptyList()
        val sourceOf = rows.associate { it.id to it.sourceId }
        val points = runCatching { db.seriesPoints(HEART_RATE, fromMs, toMs) }.getOrNull().orEmpty()
        val bySource = HashMap<String, MutableList<HrSample>>()
        if (points.isNotEmpty()) {
            for (p in points) {
                val src = sourceOf[p.sampleId] ?: continue
                bySource.getOrPut(src) { ArrayList() } += HrSample(p.tMs, p.value)
            }
        } else {
            for (r in rows) {
                val v = r.value ?: continue
                bySource.getOrPut(r.sourceId) { ArrayList() } += HrSample(r.startMs, v)
            }
        }
        val best = bySource.maxByOrNull { it.value.size }?.value ?: return emptyList()
        return best.filter { it.tMs in fromMs until toMs }.sortedBy { it.tMs }.distinctBy { it.tMs }
    }

    private suspend fun restingHr(day: LocalDate): Double? {
        val db = mirror() ?: return null
        val from = day.minusDays(RHR_LOOKBACK_DAYS - 1).toString()
        val native = runCatching { db.dailyRollups(RESTING_HR, from, day.toString()) }.getOrNull().orEmpty()
            .filter { it.count > 0 && it.avg != null }.maxByOrNull { it.day }?.avg
        if (native != null) return native
        val derived = runCatching { db.derivedValues(RESTING_HR_DERIVED, from, day.toString()) }.getOrNull().orEmpty()
            .mapNotNull { it.value }.sorted()
        return if (derived.isEmpty()) null else DerivedMath.median(derived)
    }

    companion object {
        private const val HEART_RATE = "heart_rate"
        private const val RESTING_HR = "resting_heart_rate"
        private const val RESTING_HR_DERIVED = "resting_hr_derived"
        private const val RHR_LOOKBACK_DAYS = 14L
        private const val MINUTE = 60_000L

        /** `hr_workout` for [person]; without a maximum heart rate only average, peak and coverage are kept. */
        fun statsFor(hr: List<HrSample>, startMs: Long, endMs: Long, person: Person, cfg: WorkoutConfig): WorkoutHeartRateStats {
            val hrMax = person.hrMax
            val r = HeartRateWorkout.hrWorkout(
                HrWorkoutInput(hr, startMs, endMs, hrMax ?: FALLBACK_HR_MAX, person.rhr, person.sex, person.age, person.weightKg), cfg
            )
            return WorkoutHeartRateStats(
                avgHr = r.avgHr,
                maxHr = r.maxHr,
                coveragePct = r.coveragePct,
                zoneSeconds = if (hrMax != null) r.zoneSeconds else emptyList(),
                trimp = if (hrMax != null) r.trimp else null,
                keytelKcal = if (hrMax != null) r.kcal else null,
                zoneMethod = if (hrMax != null) r.zoneMethod else null,
                restingHr = person.rhr,
                hrMax = hrMax
            )
        }

        /** Minute averages of [samples] (value i is minute `startMs + i · 60000`). */
        fun minuteSeries(samples: List<HrSample>): HrMinuteSeries? {
            if (samples.isEmpty()) return null
            val byMinute = samples.groupBy { it.tMs / MINUTE * MINUTE }.mapValues { (_, v) -> v.map { it.bpm }.average() }
            val start = byMinute.keys.min()
            val end = byMinute.keys.max()
            val n = ((end - start) / MINUTE + 1).toInt()
            return HrMinuteSeries(start, List(n) { i -> byMinute[start + i * MINUTE] })
        }

        /** Only used to run `hr_workout` for average/peak/coverage when the age (and so HRmax) is unknown. */
        private const val FALLBACK_HR_MAX = 200.0
    }
}
