package com.ayuvo.health.data.derived

import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.health.DerivedDailyValue
import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Computes derived metrics (docs/derived-metrics.md §3) from the Health Connect mirror and stores them in
 * `derived_daily_values`. Engines are the pure ports of `scripts/derived_reference.py`; this class only builds their
 * inputs, applies "native wins" to the inputs other metrics depend on (a platform resting heart rate is used for zones
 * before Ayuvo's estimate), and writes one row per enabled metric and day. Values are never written to Health Connect.
 *
 * Writes go through [HealthDataStore.replaceDerivedValues], which bumps only `derivedRevision`, so recomputing never
 * re-triggers itself through the mirror's `revision`.
 */
class DerivedMetricsService(
    private val store: () -> HealthDataStore?,
    private val prefs: PreferencesStore,
    private val catalog: () -> DerivedCatalog,
    private val config: () -> DerivedConfig,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * VO₂max values measured by recorded GPS workouts (docs/workouts-gps.md §1), by workout day. The latest one on or
     * before a day wins over the Uth-Sørensen estimate for `vo2max_estimate` (quality [GPS_VO2MAX_QUALITY]).
     */
    private val gpsVo2max: suspend () -> List<Pair<LocalDate, Double>> = { emptyList() }
) {
    private val mutex = Mutex()

    /** Recomputes after mirror writes and switch changes (debounced); runs until [scope] is cancelled. */
    @OptIn(FlowPreview::class)
    fun observe(scope: CoroutineScope, healthRevision: kotlinx.coroutines.flow.Flow<Long>) {
        scope.launch {
            combine(healthRevision, prefs.derivedMetricsEnabled, prefs.derivedMetricsDisabled, prefs.derivedBmiScheme) { rev, on, off, bmi ->
                Triple(rev, on to off, bmi)
            }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { runCatching { refresh() } }
        }
    }

    /** Applies the switches (disabled metrics lose every stored value) and recomputes the recent history. */
    suspend fun refresh(historyDays: Long = HISTORY_DAYS) = mutex.withLock {
        val db = store() ?: return@withLock
        val cat = catalog()
        val masterOn = prefs.derivedMetricsEnabled.first()
        val disabled = prefs.derivedMetricsDisabled.first()
        val enabled = cat.enabledIds(masterOn, disabled)
        val off = cat.metrics.map { it.id }.filter { it !in enabled }
        db.deleteDerivedValues(off)
        if (enabled.isEmpty()) return@withLock
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs()), zone())
        compute(db, enabled, today.minusDays(historyDays - 1), today)
    }

    // ---------------------------------------------------------------------------------------------

    private class Context(
        val zone: ZoneId,
        val cfg: DerivedConfig,
        val profile: UserProfile?,
        val sex: String?,
        val stepGoal: Int,
        val bmiScheme: String,
        val weights: Map<LocalDate, Double>
    )

    private suspend fun compute(db: HealthDataStore, enabled: Set<String>, from: LocalDate, to: LocalDate) =
        withContext(Dispatchers.Default) {
            val z = zone()
            val cfg = config()
            val cat = catalog()
            val profile = prefs.userProfile.first()
            val ctx = Context(
                zone = z, cfg = cfg, profile = profile,
                sex = when (profile?.gender) { Gender.MALE -> "male"; Gender.FEMALE -> "female"; else -> null },
                stepGoal = prefs.dailyStepGoal.first(),
                bmiScheme = prefs.derivedBmiScheme.first(),
                weights = prefs.weightEntries.first()
                    .sortedBy { it.date }
                    .associate { LocalDate.ofInstant(it.date, z) to it.weightKg }
            )
            val age = profile?.birthday?.let { Period.between(LocalDate.ofInstant(it, z), to).years.toDouble() }
            val lookFrom = from.minusDays(cfg.thresholds.hrMaxLookbackDays.toLong())

            // Low-volume inputs for the whole range at once.
            val sleepRows = db.samplesBetween(SLEEP, startMs(from.minusDays(RANGE_PAD), z), startMs(to.plusDays(1), z))
                .filter { !it.deleted && it.categoryValue != null }
                .map { SleepRow(it.startMs, it.endMs, it.categoryValue!!, it.sourceId) }
            val nights = SleepDerivation.sleepNights(SleepNightsInput(z, sleepRows), cfg).nights
            val nativeRhr = dailyAvg(db, RESTING_HR, lookFrom, to)
            val stepsTotal = dailySum(db, STEPS, from.minusDays(STREAK_WINDOW.toLong()), to)
            val activeByDay = db.dailyRollups(ACTIVE_ENERGY, from.toString(), to.toString()).associateBy { LocalDate.parse(it.day) }
            val restingByDay = dailySum(db, RESTING_ENERGY, from, to)
            val bmrByDay = dailyAvg(db, BMR, from, to)
            val storedObserved = db.derivedValues("observed_max_hr", lookFrom.toString(), to.toString())
                .mapNotNull { r -> r.value?.let { LocalDate.parse(r.day) to it } }.toMap().toMutableMap()
            val storedRhr = db.derivedValues("resting_hr_derived", lookFrom.toString(), to.toString())
                .mapNotNull { r -> r.value?.let { LocalDate.parse(r.day) to it } }.toMap().toMutableMap()
            val gpsVo2 = runCatching { gpsVo2max() }.getOrDefault(emptyList())
            val intake = runCatching { DerivedIntakeInputs.load(prefs, z, ctx.weights) }.getOrNull() // nutrition_day / energy_balance

            val rows = ArrayList<DerivedDailyValue>()
            val computed = nowMs()
            var day = from
            while (!day.isAfter(to)) {
                val d = day
                val out = HashMap<String, Map<String, Any?>>()
                val night = nights[d]
                // Heart: minute heart rate and wearable minute steps around the day (the night can start the day before).
                val winStart = minOf(night?.window?.startMs ?: Long.MAX_VALUE, startMs(d, z)) - 60_000L
                val winEnd = startMs(d.plusDays(1), z)
                val hr = heartRateMinutes(db, winStart, winEnd, z, d)
                val stepRows = db.samplesBetween(STEPS, winStart, winEnd).filter { !it.deleted && it.value != null }
                val minuteSteps = wearableMinuteSteps(stepRows, d, z)
                if (hr != null) {
                    val first = HeartDerivation.heartDay(HeartDayInput(z, d, ctx.sex, hr, minuteSteps, night?.window, 220.0, null), cfg)
                    first.restingHr?.let { storedRhr[d] = it }
                    first.observedMax?.let { storedObserved[d] = it }
                    val rhrRef = nativeRhr[d] ?: (if ("resting_hr_derived" in enabled) recentRhr(storedRhr, d) else null)
                    val hrMax = age?.let {
                        HeartDerivation.hrMax(HrMaxInput(it, storedObserved.filterKeys { k -> !k.isAfter(d) && !k.isBefore(d.minusDays(cfg.thresholds.hrMaxLookbackDays.toLong())) }.values.toList(), rhrRef), cfg)
                    }
                    val heart = if (hrMax != null) {
                        HeartDerivation.heartDay(HeartDayInput(z, d, ctx.sex, hr, minuteSteps, night?.window, hrMax.hrMax, rhrRef), cfg)
                    } else first
                    out["heart_day"] = heart.toJson().toPlain() + mapOf("resting_hr" to first.restingHr)
                    if (hrMax != null) {
                        out["hr_max"] = hrMax.toJson().toPlain()
                        out["vo2max_uth"] = HeartDerivation.vo2maxUth(Vo2MaxInput(hrMax.hrMax, hrMax.method, rhrRef), cfg).toJson().toPlain()
                    }
                }
                // A GPS measurement replaces the resting-heart-rate formula on days it would show, and on workout days.
                latestGpsVo2max(gpsVo2, d)?.let { v ->
                    if (out.containsKey("vo2max_uth") || gpsVo2.any { it.first == d }) {
                        out["vo2max_uth"] = mapOf("vo2max" to v, "confidence" to GPS_CONFIDENCE)
                    }
                }
                val rhrSeries = (storedRhr + nativeRhr).filterKeys { !it.isAfter(d) }
                out["rhr_strain"] = HeartDerivation.rhrStrain(RhrStrainInput(rhrSeries, d), cfg).toJson().toPlain()

                // Sleep
                night?.let { out["sleep_night"] = SleepDerivation.sleepNight(SleepNightInput(z, d, it.rows), cfg).toJson().toPlain() }
                val window = nights.filterKeys { !it.isAfter(d) && it.isAfter(d.minusDays(cfg.thresholds.regularityWindowDays.toLong())) }
                if (window.isNotEmpty()) {
                    out["sleep_regularity"] = SleepDerivation.sleepRegularity(
                        SleepRegularityInput(z, d, null, window.mapValues { it.value.rows }), cfg
                    ).toJson().toPlain()
                }

                // Activity
                val hourly = hourlyBySource(db, stepRows, d, z)
                if (hourly.isNotEmpty()) {
                    out["activity_day"] = ActivityDerivation.activityDay(
                        ActivityDayInput(z, d, hourly, minuteSteps, hr, stepsTotal[d]), cfg
                    ).toJson().toPlain()
                }
                if (ctx.stepGoal > 0 && stepsTotal[d] != null) {
                    out["step_streak"] = ActivityDerivation.stepStreak(StepStreakInput(stepsTotal, d, ctx.stepGoal.toDouble(), STREAK_WINDOW), cfg).toJson().toPlain()
                }

                // Energy
                val resting = restingByDay[d] ?: bmrByDay[d]
                if (resting != null) {
                    val a = activeByDay[d]
                    // Provider active energy without Ayuvo's own workout-burn records; never negative.
                    val active = a?.sum?.let { maxOf(0.0, it - (a.ownSum ?: 0.0)) }
                    out["energy_day"] = EnergyDerivation.energyDay(
                        EnergyDayInput(resting, active, weightOn(ctx.weights, d), profile?.heightCm, age, ctx.sex), cfg
                    ).toJson().toPlain()
                }

                // Body
                if (ctx.weights.isNotEmpty()) {
                    out["body_trend"] = BodyDerivation.bodyTrend(
                        BodyTrendInput(ctx.weights, d, profile?.heightCm?.takeIf { it > 0 }?.div(100.0), profile?.goalWeightKg, ctx.bmiScheme), cfg
                    ).toJson().toPlain()
                }

                intake?.addTo(out, d, nights[d.plusDays(1)]?.rows)
                rows += rowsFor(cat, enabled, out, d, cfg, computed)
                day = day.plusDays(1)
            }
            db.replaceDerivedValues(enabled, dayList(from, to).map { it.toString() }, rows)
        }

    /** One row per enabled metric whose function ran for [day] and produced a value. */
    private fun rowsFor(
        cat: DerivedCatalog,
        enabled: Set<String>,
        out: Map<String, Map<String, Any?>>,
        day: LocalDate,
        cfg: DerivedConfig,
        computed: Long
    ): List<DerivedDailyValue> {
        val rows = ArrayList<DerivedDailyValue>()
        for (m in cat.metrics) {
            if (m.id !in enabled || m.requires.any { it !in enabled }) continue
            val result = out[m.function] ?: continue
            val v = (result[m.field] as? Number)?.toDouble() ?: continue
            val quality = when (m.function) {
                "vo2max_uth" -> when (result["confidence"]) {
                    GPS_CONFIDENCE -> GPS_VO2MAX_QUALITY
                    "medium" -> 0.6
                    else -> 0.3
                }
                else -> 1.0
            }
            rows += DerivedDailyValue(
                metricId = m.id, day = day.toString(), value = v,
                value2 = m.value2Field?.let { (result[it] as? Number)?.toDouble() },
                value3 = m.value3Field?.let { (result[it] as? Number)?.toDouble() },
                quality = quality, algoVersion = cfg.algoVersion, computedMs = computed
            )
        }
        return rows
    }

    // -- Input builders ------------------------------------------------------------------------------

    /**
     * Minute heart rate for the window from the single source with the most minutes on [day] (never averaged across
     * devices). Android rows are condensed per record; their per-sample points live in `health_series_points`.
     */
    private suspend fun heartRateMinutes(db: HealthDataStore, fromMs: Long, toMs: Long, zone: ZoneId, day: LocalDate): MinuteSeries? {
        val rows = db.samplesBetween(HEART_RATE, fromMs, toMs).filter { !it.deleted }
        if (rows.isEmpty()) return null
        val sourceOf = rows.associate { it.id to it.sourceId }
        val points = db.seriesPoints(HEART_RATE, fromMs, toMs)
        val bySource = HashMap<String, HashMap<Long, MutableList<Double>>>()
        if (points.isNotEmpty()) {
            for (p in points) {
                val src = sourceOf[p.sampleId] ?: continue
                bySource.getOrPut(src) { HashMap() }.getOrPut(p.tMs / MINUTE * MINUTE) { ArrayList() } += p.value
            }
        } else {
            for (r in rows) {
                val v = r.value ?: continue
                bySource.getOrPut(r.sourceId) { HashMap() }.getOrPut(r.startMs / MINUTE * MINUTE) { ArrayList() } += v
            }
        }
        val d0 = startMs(day, zone)
        val d1 = startMs(day.plusDays(1), zone)
        val best = bySource.maxByOrNull { (src, m) -> m.keys.count { it in d0 until d1 } * 1_000_000L - src.hashCode().toLong().mod(1000L) } ?: return null
        val minutes = best.value.mapValues { (_, list) -> list.average() }
        return toSeries(minutes, fromMs / MINUTE * MINUTE, toMs)
    }

    /** Wearable minute steps for [day]: the non-phone source with the most steps, each record spread evenly over its minutes. */
    private fun wearableMinuteSteps(rows: List<HealthSampleRow>, day: LocalDate, zone: ZoneId): MinuteSeries? {
        val wear = rows.filter { kindOf(it) == "wearable" }
        if (wear.isEmpty()) return null
        val source = wear.groupBy { it.sourceId }.maxByOrNull { (_, list) -> list.sumOf { it.value ?: 0.0 } }?.key ?: return null
        val minutes = HashMap<Long, Double>()
        for (r in wear.filter { it.sourceId == source }) {
            val s = r.startMs / MINUTE * MINUTE
            val n = maxOf(1L, (r.endMs - s + MINUTE - 1) / MINUTE)
            val per = (r.value ?: 0.0) / n
            for (i in 0 until n) minutes[s + i * MINUTE] = (minutes[s + i * MINUTE] ?: 0.0) + per
        }
        return toSeries(minutes, startMs(day, zone) - 18 * 3_600_000L, startMs(day.plusDays(1), zone))
    }

    /** 24 hourly totals per source for the local day (records attributed to their start hour). */
    private suspend fun hourlyBySource(db: HealthDataStore, rows: List<HealthSampleRow>, day: LocalDate, zone: ZoneId): Map<String, StepSource> {
        val d0 = startMs(day, zone)
        val d1 = startMs(day.plusDays(1), zone)
        val names = db.sources().associate { it.id to it.name }
        val out = LinkedHashMap<String, Array<Double?>>()
        val kinds = HashMap<String, String>()
        for (r in rows) {
            if (r.startMs < d0 || r.startMs >= d1) continue
            val key = names[r.sourceId] ?: r.sourceId
            val arr = out.getOrPut(key) { arrayOfNulls(24) }
            val h = DerivedMath.localTime(r.startMs, zone).hour
            arr[h] = (arr[h] ?: 0.0) + (r.value ?: 0.0)
            kinds[key] = kindOf(r)
        }
        return out.mapValues { (k, v) -> StepSource(kinds[k] ?: "wearable", v.toList()) }
    }

    private fun kindOf(r: HealthSampleRow): String = when (r.deviceType) {
        DEVICE_PHONE -> "phone"
        else -> if (r.device?.contains("phone", ignoreCase = true) == true) "phone" else "wearable"
    }

    private fun toSeries(minutes: Map<Long, Double>, fromMs: Long, toMs: Long): MinuteSeries? {
        if (minutes.isEmpty()) return null
        val start = maxOf(fromMs, minutes.keys.min())
        val end = minOf(toMs, minutes.keys.max() + MINUTE)
        if (end <= start) return null
        val n = ((end - start) / MINUTE).toInt()
        return MinuteSeries(start, List(n) { i -> minutes[start + i * MINUTE] })
    }

    private fun recentRhr(series: Map<LocalDate, Double>, day: LocalDate): Double? {
        val recent = (0L until RHR_REF_DAYS).mapNotNull { series[day.minusDays(it)] }.sorted()
        if (recent.isEmpty()) return null
        return DerivedMath.median(recent)
    }

    private fun weightOn(weights: Map<LocalDate, Double>, day: LocalDate): Double? =
        weights.filterKeys { !it.isAfter(day) }.maxByOrNull { it.key }?.value

    private suspend fun dailyAvg(db: HealthDataStore, type: String, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        db.dailyRollups(type, from.toString(), to.toString()).filter { it.count > 0 && it.avg != null }
            .associate { LocalDate.parse(it.day) to it.avg!! }

    private suspend fun dailySum(db: HealthDataStore, type: String, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        db.dailyRollups(type, from.toString(), to.toString()).filter { it.sum != null && (it.count > 0 || it.fromPlatformAggregate) }
            .associate { LocalDate.parse(it.day) to it.sum!! }

    private fun startMs(day: LocalDate, zone: ZoneId): Long = DerivedMath.dayStartMs(day, zone)

    private fun dayList(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) { out += d; d = d.plusDays(1) }
        return out
    }

    private fun kotlinx.serialization.json.JsonObject.toPlain(): Map<String, Any?> = mapValues { (_, e) ->
        val p = e as? kotlinx.serialization.json.JsonPrimitive
        when {
            p == null -> null
            p is kotlinx.serialization.json.JsonNull -> null
            p.isString -> p.content
            else -> p.content.toBooleanStrictOrNull() ?: p.content.toDoubleOrNull()
        }
    }

    companion object {
        const val HISTORY_DAYS = 200L
        private const val DEBOUNCE_MS = 1_500L
        private const val MINUTE = 60_000L
        private const val RANGE_PAD = 16L
        private const val STREAK_WINDOW = 90
        private const val RHR_REF_DAYS = 14L
        /** Health Connect `Device.TYPE_PHONE`. */
        private const val DEVICE_PHONE = 2
        private const val SLEEP = "sleep"
        private const val HEART_RATE = "heart_rate"
        private const val RESTING_HR = "resting_heart_rate"
        private const val STEPS = "steps"
        private const val ACTIVE_ENERGY = "active_energy"
        private const val RESTING_ENERGY = "resting_energy"
        private const val BMR = "basal_metabolic_rate"

        private const val GPS_CONFIDENCE = "gps"
        const val GPS_VO2MAX_QUALITY = 0.7
        /** A GPS VO₂max older than this no longer replaces the estimate. */
        const val GPS_VO2MAX_LOOKBACK_DAYS = 365L

        /** The latest GPS VO₂max measured on or before [day] within [GPS_VO2MAX_LOOKBACK_DAYS]. */
        fun latestGpsVo2max(values: List<Pair<LocalDate, Double>>, day: LocalDate): Double? =
            values.filter { (d, v) -> !d.isAfter(day) && d.isAfter(day.minusDays(GPS_VO2MAX_LOOKBACK_DAYS)) && v.isFinite() && v > 0 }
                .maxByOrNull { it.first }?.second

        /** [HealthDailyRollup] value a native metric shows for a day (docs/derived-metrics.md §1: native wins). */
        fun nativeValue(r: HealthDailyRollup?): Double? = r?.let { it.avg ?: it.lastValue ?: it.sum }
    }
}
