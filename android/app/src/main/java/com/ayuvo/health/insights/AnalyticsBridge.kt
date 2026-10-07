package com.ayuvo.health.insights

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.ayuvo.health.data.analytics.engine.AnalyticsConfig
import com.ayuvo.health.data.analytics.engine.AnalyticsCore
import com.ayuvo.health.data.analytics.engine.AnalyticsForecast
import com.ayuvo.health.data.analytics.engine.AnalyticsHealth
import com.ayuvo.health.data.analytics.engine.AnalyticsStats
import com.ayuvo.health.data.analytics.engine.JMap
import com.ayuvo.health.data.health.SleepNight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One main night as the analytics engine reads it (`nights` in scripts/analytics_reference.py). Clocks are wall-clock
 * minutes after 12:00 on the day before the wake day; WASO is the awake-stage time inside the night window (Health
 * Connect stages), efficiency = asleep / in bed.
 */
data class NightDetail(
    val asleepMin: Double,
    val inBedMin: Double?,
    val efficiency: Double?,
    val wasoMin: Double?,
    val bedtimeClock: Int,
    val wakeClock: Int,
    val midpointClock: Int,
    val napMin: Double
) {
    fun toJson(): JMap = linkedMapOf(
        "asleep_min" to asleepMin, "in_bed_min" to inBedMin, "efficiency" to efficiency, "waso_min" to wasoMin,
        "bedtime_clock" to bedtimeClock, "wake_clock" to wakeClock, "midpoint_clock" to midpointClock, "nap_min" to napMin
    )

    companion object {
        fun clock(ms: Long, wakeDay: LocalDate, zone: ZoneId): Int {
            val t = Instant.ofEpochMilli(ms).atZone(zone)
            val days = ChronoUnit.DAYS.between(wakeDay.minusDays(1), t.toLocalDate()).toInt()
            return days * 1440 + t.hour * 60 + t.minute - 720
        }

        fun of(n: SleepNight, wakeDay: LocalDate, zone: ZoneId): NightDetail {
            val asleep = n.asleepS / 60.0
            val inBed = (n.inBedS / 60.0).takeIf { it > 0 }
            return NightDetail(
                asleepMin = asleep,
                inBedMin = inBed,
                efficiency = inBed?.let { minOf(100.0, asleep * 100.0 / it) },
                wasoMin = n.awakeS / 60.0,
                bedtimeClock = clock(n.startMs, wakeDay, zone),
                wakeClock = clock(n.endMs, wakeDay, zone),
                midpointClock = clock(n.startMs + (n.endMs - n.startMs) / 2, wakeDay, zone),
                napMin = n.napS / 60.0
            )
        }
    }
}

/** A workout for load and MET intensity: effort CR-10, TRIMP when known, an analytics MET-table key, Ayuvo kcal. */
data class ActivityInput(
    val startMs: Long,
    val endMs: Long,
    val effort: Double?,
    val trimp: Double?,
    val key: String?,
    val kcal: Double? = null
)

/** Inputs the analytics engine needs beyond [InsightsInputs] (collected by [InsightsDataSource.build]). */
data class AnalyticsExtras(
    val nights: Map<LocalDate, NightDetail> = emptyMap(),
    /** Health Connect skin-temperature delta from the device baseline (daily mean, °C) as `wrist_temperature`. */
    val temperature: Map<LocalDate, Double> = emptyMap(),
    /** Dominant source per day for `hrv` / `resting_heart_rate` (source-change detection). */
    val sources: Map<String, Map<LocalDate, String>> = emptyMap(),
    /** Days a series was filled from a derived estimate (context `derived`). */
    val derivedDays: Map<String, Set<LocalDate>> = emptyMap(),
    val activities: List<ActivityInput> = emptyList(),
    /** Provider workouts (not written by Ayuvo): Ayuvo burns they overlap are not added again. */
    val providerWorkouts: List<Pair<Long, Long>> = emptyList(),
    /** Health Connect BMR record (kcal/day). */
    val basalKcal: Map<LocalDate, Double> = emptyMap(),
    val vo2Provider: Map<LocalDate, Double> = emptyMap(),
    val vo2Uth: Map<LocalDate, Double> = emptyMap(),
    val vo2Gps: Map<LocalDate, Double> = emptyMap(),
    /** HRR1 of Ayuvo GPS / strength sessions with heart rate, by workout day. */
    val hrr1: Map<LocalDate, Double> = emptyMap(),
    val weightKg: Double? = null
)

/** Every analytics result Insights shows for one day (raw reference-shaped maps; docs/health-analytics.md). */
data class AnalyticsDay(
    val recovery: JMap?,
    val anomaly: JMap?,
    val hrv: JMap?,
    val sleep: JMap?,
    val load: JMap?,
    val hrr: JMap?,
    val trends: Map<String, Pair<JMap, JMap>>,
    val energy: JMap?,
    val vo2max: JMap?,
    val met: JMap?,
    val correlation: JMap?,
    val responses: List<JMap>,
    val evidence: JMap
)

/**
 * Maps the Insights bundle onto the analytics engine (`data.analytics.engine`, a port of scripts/analytics_reference.py)
 * and Recovery v2 back onto the [RecoveryResult] shape the Daily Review, Patterns, notification, widgets and actions
 * already read. Pure: no storage, no clock.
 */
object AnalyticsBridge {

    /** Metrics with a Trends card (config `metrics`), in display order. */
    val TREND_METRICS = listOf(
        "hrv", "resting_heart_rate", "respiratory_rate", "wrist_temperature", "blood_oxygen", "sleep_duration",
        "sleep_efficiency", "steps", "active_energy", "weight", "vo2_max"
    )

    /** Health Connect exercise type -> MET table key (config `met.table`); null when the Compendium entry is unknown. */
    fun activityKey(exerciseType: Int?): String? = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "stationary_cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing_machine"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength_training"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dancing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "stair_climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "martial_arts"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "boxing"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "skiing_downhill"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "golf"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "table_tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "volleyball"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> "skating"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "mind_body"
        else -> null
    }

    /** GPS sport (shared/workout sports) -> MET table key. */
    fun gpsActivityKey(sport: String): String? = when (sport) {
        "walk" -> "walking"
        "run" -> "running"
        "cycle" -> "cycling"
        "hike" -> "hiking"
        else -> null
    }

    private fun days(m: Map<LocalDate, Double>): JMap = LinkedHashMap<String, Any?>().also { o -> m.toSortedMap().forEach { (d, v) -> o[d.toString()] = v } }

    /** The engine `inputs` object for [bundle] (series, contexts, sources, nights, workouts, nutrition, fallbacks). */
    fun inputs(bundle: InsightsBundle, day: LocalDate = bundle.today): JMap {
        val inp = bundle.inputs
        val x = bundle.extras
        val series = LinkedHashMap<String, Any?>()
        for (id in listOf("hrv", "resting_heart_rate", "respiratory_rate", "blood_oxygen", "steps", "active_energy", "weight", "vo2_max")) {
            inp.series[id]?.takeIf { it.isNotEmpty() }?.let { series[id] = days(it) }
        }
        if (x.temperature.isNotEmpty()) series["wrist_temperature"] = days(x.temperature)
        val contexts = LinkedHashMap<String, Any?>()
        val ctxIds = (bundle.scanFallback.keys + x.derivedDays.keys).toSet()
        for (id in ctxIds) {
            val m = LinkedHashMap<String, Any?>()
            x.derivedDays[id]?.forEach { m[it.toString()] = "derived" }
            bundle.scanFallback[id]?.forEach { m[it.toString()] = "camera" }
            if (m.isNotEmpty()) contexts[id] = m
        }
        val sources = LinkedHashMap<String, Any?>()
        for ((id, m) in x.sources) sources[id] = LinkedHashMap<String, Any?>().also { o -> m.forEach { (d, s) -> o[d.toString()] = s } }
        val nights = LinkedHashMap<String, Any?>()
        if (x.nights.isNotEmpty()) {
            x.nights.toSortedMap().forEach { (d, n) -> nights[d.toString()] = n.toJson() }
        } else {
            inp.sleep.toSortedMap().forEach { (d, n) ->
                nights[d.toString()] = linkedMapOf<String, Any?>("asleep_min" to n.asleepMin)
            }
        }
        val workouts = if (x.activities.isNotEmpty()) x.activities.map { a ->
            linkedMapOf<String, Any?>("start_ms" to a.startMs, "end_ms" to a.endMs, "effort" to a.effort, "trimp" to a.trimp, "activity" to a.key)
        } else inp.workouts.map { w ->
            linkedMapOf<String, Any?>("start_ms" to w.startMs, "end_ms" to w.endMs, "effort" to w.effort, "trimp" to null, "activity" to null)
        }
        val nutrition = LinkedHashMap<String, Any?>()
        inp.nutrition.toSortedMap().forEach { (d, n) -> nutrition[d.toString()] = linkedMapOf("calories" to n["calories"], "protein_g" to n["protein_g"]) }
        return linkedMapOf(
            "time_zone" to inp.timeZone.id, "series" to series, "contexts" to contexts, "sources" to sources,
            "nights" to nights, "workouts" to workouts, "tracking" to linkedMapOf("workouts" to inp.tracking.workouts),
            "overnight_fallback" to bundle.fallbackByDay[day].orEmpty().toList().sorted(),
            "scan_fallback" to bundle.scanFallback.filterValues { day in it }.keys.sorted(),
            "nutrition" to nutrition
        )
    }

    /** Inputs for [day] with that day's fallback flags. */
    fun inputsFor(base: JMap, bundle: InsightsBundle, day: LocalDate): JMap =
        if (day == bundle.today) base else LinkedHashMap(base).apply {
            put("overnight_fallback", bundle.fallbackByDay[day].orEmpty().toList().sorted())
            put("scan_fallback", bundle.scanFallback.filterValues { day in it }.keys.sorted())
        }

    fun recoveryV2(inputs: JMap, day: LocalDate, cfg: AnalyticsConfig): JMap =
        AnalyticsHealth.recovery(mapOf("inputs" to inputs, "day" to day.toString()), cfg)

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()

    /**
     * Recovery v2 in the v1 shape: VALID / LOW_CONFIDENCE -> `ok`, INSUFFICIENT_HISTORY -> `collecting`, NO_DATA ->
     * `no_sleep` / `no_heart_data`; drivers become the signals (text keyed for ContractStrings), the load state the
     * load row. The full v2 map rides along in [RecoveryResult.v2].
     */
    @Suppress("UNCHECKED_CAST")
    fun toRecoveryResult(v2: JMap, day: LocalDate): RecoveryResult {
        val status = when (v2["status"]) {
            "VALID", "LOW_CONFIDENCE" -> "ok"
            "INSUFFICIENT_HISTORY" -> "collecting"
            else -> (v2["reason"] as? String) ?: "no_sleep"
        }
        val collecting = (v2["collecting"] as? Map<String, Any?>)?.let { Collecting(num(it["have"])?.toInt() ?: 0, num(it["need"])?.toInt() ?: 0) }
        val comps = (v2["components"] as? List<Map<String, Any?>>).orEmpty().map { c ->
            val value = num(c["value"])
            val base = num(c["baseline"])
            val conf = num(c["confidence"]) ?: 0.0
            RecoveryComponent(
                id = c["id"] as String, weight = num(c["weight"]) ?: 0.0, available = c["available"] == true, value = value,
                baseline = base, delta = if (value != null && base != null) InsightsMath.roundTo(value - base, 2) else null,
                pct = if (value != null && base != null && base != 0.0) InsightsMath.roundTo((value - base) / kotlin.math.abs(base) * 100.0, 1) else null,
                z = num(c["z"]), subscore = num(c["subscore"]), impact = num(c["impact"]), baselineN = num(c["n"])?.toInt() ?: 0,
                baselineConfidence = if (conf >= 0.75) "high" else if (conf >= 0.5) "medium" else "low",
                fallback = false, consistencyDeviationMin = null, consistencySubscore = null
            )
        }
        val drivers = (v2["drivers"] as? List<Map<String, Any?>>).orEmpty()
        fun signal(d: Map<String, Any?>) = RecoverySignal(
            id = d["id"] as String, impact = num(d["impact"]) ?: 0.0, text = d["text"] as String,
            params = mapOf("v2_key" to "analytics.recovery.drivers.${d["text_key"] ?: "${d["id"]}.${d["direction"]}"}")
        )
        val pos = drivers.filter { it["direction"] == "positive" }.map(::signal).sortedByDescending { it.impact }
        val neg = drivers.filter { it["direction"] == "negative" }.map(::signal).sortedBy { it.impact }
        val load = (v2["load"] as? Map<String, Any?>)?.let { l ->
            val state = l["state"] as? String
            val category = when (state) {
                "LOAD_SPIKE", "LOAD_HIGH" -> "high"
                "LOAD_REDUCED" -> "light"
                null -> "none"
                else -> "moderate"
            }
            RecoveryLoad(
                day = runCatching { LocalDate.parse(l["day"] as String) }.getOrDefault(day.minusDays(1)),
                load = num(l["acute"]) ?: 0.0, mean28d = num(l["chronic"]) ?: 0.0, ratio = num(l["ratio"]),
                category = category, label = state ?: "none", modifier = num(l["modifier"])?.toInt() ?: 0
            )
        }
        return RecoveryResult(
            day = day, status = status, score = num(v2["score"])?.toInt(), label = v2["label"] as? String,
            labelText = v2["label_text"] as? String, recommendation = v2["recommendation"] as? String,
            confidence = if (status == "ok") v2["confidence_band"] as? String else null, collecting = collecting,
            components = comps, positives = pos, negatives = neg, load = if (status == "ok") load else null, v2 = v2
        )
    }

    private fun seriesOf(inputs: JMap, id: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return ((inputs["series"] as? Map<String, Any?>)?.get(id) as? Map<String, Any?>).orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun nightField(inputs: JMap, field: String): Map<String, Any?> =
        ((inputs["nights"] as? Map<String, Any?>).orEmpty()).mapNotNull { (d, n) ->
            ((n as? Map<String, Any?>)?.get(field) as? Number)?.let { d to it.toDouble() }
        }.toMap()

    /**
     * Everything else for [today]: signals, HRV, sleep, load, HRR, trends, energy, VO2 max, MET week, correlations
     * (+ response for the surfaced ones) and the Coach evidence. [recovery] is today's Recovery v2.
     */
    fun day(bundle: InsightsBundle, inputs: JMap, recovery: JMap?, cfg: AnalyticsConfig, profile: InsightsProfile): AnalyticsDay {
        val today = bundle.today.toString()
        val tz = bundle.inputs.timeZone.id
        val anomaly = runCatching { AnalyticsHealth.anomaly(mapOf("inputs" to inputs, "day" to today), cfg) }.getOrNull()
        val hrvSeries = seriesOf(inputs, "hrv")
        val hrv = if (hrvSeries.isEmpty()) null else runCatching {
            AnalyticsHealth.hrvStatus(mapOf("series" to hrvSeries, "day" to today, "kind" to "rmssd"), cfg)
        }.getOrNull()
        val nights = inputs["nights"] as? Map<*, *>
        val sleep = if (nights.isNullOrEmpty()) null else runCatching {
            AnalyticsHealth.sleepStatus(mapOf("nights" to nights, "day" to today), cfg)
        }.getOrNull()
        val workouts = inputs["workouts"] as? List<*>
        val load = if (workouts.isNullOrEmpty()) null else runCatching {
            AnalyticsHealth.load(mapOf("workouts" to workouts, "day" to today, "time_zone" to tz, "tracking" to bundle.inputs.tracking.workouts), cfg)
        }.getOrNull()
        val hrrSeries = days(bundle.extras.hrr1)
        val hrr = if (hrrSeries.isEmpty()) null else runCatching {
            val last = bundle.extras.hrr1.toSortedMap().lastKey()
            AnalyticsCore.trend(mapOf("series" to hrrSeries, "day" to today, "metric" to "hrr1", "window" to 90), cfg).let {
                LinkedHashMap(it).apply {
                    put("latest", bundle.extras.hrr1[last])
                    put("latest_day", last.toString())
                }
            }
        }.getOrNull()
        val trends = LinkedHashMap<String, Pair<JMap, JMap>>()
        for (id in TREND_METRICS) {
            val s: Map<String, Any?> = when (id) {
                "sleep_duration" -> nightField(inputs, "asleep_min")
                "sleep_efficiency" -> nightField(inputs, "efficiency")
                else -> seriesOf(inputs, id)
            }
            if (s.isEmpty()) continue
            val ctx = ((inputs["contexts"] as? Map<*, *>)?.get(id))
            runCatching {
                val q = mutableMapOf<String, Any?>("series" to s, "day" to today, "metric" to id, "window" to 28)
                if (ctx != null) q["contexts"] = ctx
                if (id == "steps") q["recent"] = "median7"
                AnalyticsCore.baseline(q, cfg) to AnalyticsCore.trend(mapOf("series" to s, "day" to today, "metric" to id), cfg)
            }.getOrNull()?.let { trends[id] = it }
        }
        val energy = runCatching { energy(bundle, inputs, profile, cfg) }.getOrNull()
        val vo2 = runCatching {
            AnalyticsStats.vo2maxTrend(
                mapOf("day" to today, "readings" to mapOf("provider" to days(bundle.extras.vo2Provider), "gps" to days(bundle.extras.vo2Gps), "uth" to days(bundle.extras.vo2Uth))),
                cfg
            )
        }.getOrNull()
        val met = runCatching {
            AnalyticsStats.metIntensity(
                mapOf("day" to today, "time_zone" to tz, "activities" to bundle.extras.activities.map {
                    mapOf("start_ms" to it.startMs, "end_ms" to it.endMs, "key" to it.key)
                }),
                cfg
            )
        }.getOrNull()
        val correlation = runCatching { AnalyticsStats.correlation(mapOf("as_of" to today, "pairs" to correlationPairs(inputs, bundle, cfg)), cfg) }.getOrNull()
        val responses = ArrayList<JMap>()
        if (correlation != null) {
            val pairs = correlationPairs(inputs, bundle, cfg).associateBy { it["id"] }
            @Suppress("UNCHECKED_CAST")
            for (r in (correlation["results"] as? List<Map<String, Any?>>).orEmpty().filter { it["surfaced"] == true }) {
                val p = pairs[r["id"]] ?: continue
                runCatching {
                    AnalyticsStats.response(mapOf("exposure" to p["exposure"], "outcome" to p["outcome"], "lag" to r["lag"], "as_of" to today), cfg)
                }.getOrNull()?.let { responses += LinkedHashMap(it).apply { put("id", r["id"]) } }
            }
        }
        val evidence = AnalyticsForecast.evidence(
            mapOf("recovery" to recovery, "anomaly" to anomaly, "hrv" to hrv, "sleep" to sleep, "load" to load).filterValues { it != null },
            cfg
        )
        return AnalyticsDay(recovery, anomaly, hrv, sleep, load, hrr, trends, energy, vo2, met, correlation, responses, evidence)
    }

    private fun energy(bundle: InsightsBundle, inputs: JMap, p: InsightsProfile, cfg: AnalyticsConfig): JMap {
        val today = bundle.today
        val zone = bundle.inputs.timeZone
        val d0 = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val d1 = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val age = p.birthday?.let { ChronoUnit.DAYS.between(it, today) / 365.25 }
        val profile = LinkedHashMap<String, Any?>()
        if (bundle.extras.weightKg != null && p.heightCm != null && age != null) {
            profile["weight_kg"] = bundle.extras.weightKg
            profile["height_cm"] = p.heightCm
            profile["age"] = age
            profile["sex"] = p.sex
        }
        return AnalyticsStats.energy(
            mapOf(
                "provider_basal_kcal" to bundle.extras.basalKcal[today],
                "provider_active_kcal" to bundle.inputs.series["active_energy"]?.get(today),
                "provider_workout_kcal" to null,
                "ayuvo_sessions" to bundle.extras.activities.filter { it.kcal != null && it.startMs in d0 until d1 }
                    .map { mapOf("start_ms" to it.startMs, "end_ms" to it.endMs, "kcal" to it.kcal) },
                "provider_workouts" to bundle.extras.providerWorkouts.filter { it.first < d1 && it.second > d0 }
                    .map { mapOf("start_ms" to it.first, "end_ms" to it.second) },
                "profile" to profile
            ),
            cfg
        )
    }

    /** Exposure / outcome day series for the configured correlation pairs (docs/health-analytics.md §5.9). */
    fun correlationPairs(inputs: JMap, bundle: InsightsBundle, cfg: AnalyticsConfig): List<JMap> {
        val asleep = nightField(inputs, "asleep_min")
        val bedtime = nightField(inputs, "bedtime_clock")
        val loadDays = AnalyticsHealth.loadDays((inputs["workouts"] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>(), bundle.inputs.timeZone.id)
        // Training load per day: session-RPE where logged, else minutes; a day without a session is 0 inside the tracked range.
        val load = LinkedHashMap<String, Any?>()
        if (loadDays.isNotEmpty()) {
            val first = loadDays.keys.minOrNull()!!
            var d = LocalDate.parse(first)
            while (!d.isAfter(bundle.today)) {
                val x = loadDays[d.toString()]
                load[d.toString()] = if (x == null) 0.0 else if (x.rpeMinutes > 0) x.rpeLoad else x.minutes
                d = d.plusDays(1)
            }
        }
        val intake = LinkedHashMap<String, Any?>()
        bundle.inputs.nutrition.forEach { (d, n) -> n["calories"]?.let { intake[d.toString()] = it } }
        val series = mapOf(
            "sleep_duration" to asleep, "bedtime" to bedtime, "training_load" to load, "energy_intake" to intake,
            "hrv" to seriesOf(inputs, "hrv"), "resting_heart_rate" to seriesOf(inputs, "resting_heart_rate"),
            "weight" to seriesOf(inputs, "weight"), "hrr1" to days(bundle.extras.hrr1)
        )
        @Suppress("UNCHECKED_CAST")
        val pairs = (cfg.root["correlation"] as Map<String, Any?>)["pairs"] as List<Map<String, Any?>>
        return pairs.map { p -> mapOf("id" to p["id"], "exposure" to series[p["exposure"]].orEmpty(), "outcome" to series[p["outcome"]].orEmpty()) }
    }
}
