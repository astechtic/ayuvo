package com.ayuvo.health.data.analytics

import com.ayuvo.health.data.analytics.engine.AnalyticsConfig
import com.ayuvo.health.data.analytics.engine.AnalyticsCore
import com.ayuvo.health.data.analytics.engine.AnalyticsForecast
import com.ayuvo.health.data.analytics.engine.AnalyticsMath
import com.ayuvo.health.data.analytics.engine.JMap
import com.ayuvo.health.insights.InsightsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One target's forecast as the UI shows it: only [deployed] models are ever shown. */
data class ForecastView(
    val target: String,
    val modelVersion: Int,
    val deployed: Boolean,
    val prediction: Double?,
    val intervalLow: Double?,
    val intervalHigh: Double?,
    val forDay: String,
    val metrics: JMap,
    val baselines: JMap,
    val trainEnd: String?,
    val nTest: Int
)

data class AnalyticsServiceState(
    val lastRunDay: String? = null,
    val forecasts: Map<String, ForecastView> = emptyMap(),
    /** Results written in the last run (rows skipped because their input hash was unchanged are not counted). */
    val written: Int = 0,
    val skipped: Int = 0
)

/**
 * Persists the analytics computed with each Insights snapshot (docs/health-analytics.md §6) and runs the optional
 * per-user forecast. It runs after the derived refresh: the derived revision is an Insights trigger, so every derived
 * write produces a new snapshot here.
 *
 * Incremental: each (metric, day) row stores the hash of its canonical input slice; a day whose slice, algorithm
 * version and config version are unchanged is not rewritten. New algorithm versions are written next to the old rows
 * ([AnalyticsRepository] keeps the two newest). Forecast models retrain at most every [RETRAIN_DAYS] days; daily
 * predictions use the stored model, never a silently refit one.
 */
class AnalyticsService(
    private val repository: () -> AnalyticsRepository?,
    private val config: () -> AnalyticsConfig?,
    private val forecastEnabled: suspend () -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(AnalyticsServiceState())
    val state: StateFlow<AnalyticsServiceState> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Runs [refresh] for every snapshot the flow emits (the app-wide Insights trigger chain). */
    fun observe(scope: CoroutineScope, snapshots: Flow<InsightsSnapshot?>) {
        snapshots.onEach { snap -> if (snap != null) runCatching { refresh(snap) } }.launchIn(scope)
    }

    suspend fun refresh(snap: InsightsSnapshot): AnalyticsServiceState = withContext(Dispatchers.Default) {
        mutex.withLock {
            val cfg = config() ?: return@withLock _state.value
            val inputs = snap.analyticsInputs ?: return@withLock _state.value
            val repo = repository()
            var written = 0
            var skipped = 0
            if (repo != null) {
                val rows = ArrayList<AnalyticsResultRow>()
                val today = snap.today
                // Recovery v2 for the last 30 days (history chart); each day hashed on its own input slice.
                val from = today.minusDays(InsightsSnapshot.RECOVERY_HISTORY_DAYS - 1L)
                val rc = cfg.root["recovery"] as Map<*, *>
                val recVersion = (rc["algorithm_version"] as Number).toInt()
                val stored = repo.inputHashes(RECOVERY, recVersion, from.toString(), today.toString())
                for (r in snap.recoveryHistory) {
                    val v2 = r.v2 ?: continue
                    val hash = hashOf(slice(inputs, r.day), recVersion, cfg.configVersion)
                    if (stored[r.day.toString()] == hash) {
                        skipped++
                        continue
                    }
                    rows += row(RECOVERY, r.day, v2, rc["algorithm_id"] as String, recVersion, cfg, hash, inputs, 60,
                        value = (v2["score"] as? Number)?.toDouble(), unit = "score")
                }
                val a = snap.analytics
                if (a != null) {
                    val dayHash = hashOf(slice(inputs, today), 0, cfg.configVersion)
                    fun add(metric: String, res: JMap?, id: String, version: Int, window: Int?, value: Double? = null, unit: String? = null) {
                        if (res == null) return
                        rows += row(metric, today, res, id, version, cfg, dayHash, inputs, window, value, unit)
                    }
                    fun algo(id: String): Int = (cfg.root["algorithms"] as List<*>).map { it as Map<*, *> }
                        .firstOrNull { it["id"] == id }?.let { (it["version"] as Number).toInt() } ?: 1
                    add(ANOMALY, a.anomaly, "ayuvo.anomaly", algo("ayuvo.anomaly"), 28)
                    add(HRV, a.hrv, "ayuvo.hrv.status", algo("ayuvo.hrv.status"), 28,
                        ((a.hrv?.get("baseline") as? Map<*, *>)?.get("value") as? Number)?.toDouble(), "ms")
                    add(SLEEP, a.sleep, "ayuvo.sleep.status", algo("ayuvo.sleep.status"), 28,
                        (a.sleep?.get("asleep_min") as? Number)?.toDouble(), "min")
                    add(LOAD, a.load, "ayuvo.load.ewma", algo("ayuvo.load.ewma"), 90)
                    add(HRR, a.hrr, "ayuvo.hrr", algo("ayuvo.hrr"), 90, (a.hrr?.get("latest") as? Number)?.toDouble(), "bpm")
                    add(ENERGY, a.energy, "ayuvo.energy", algo("ayuvo.energy"), null,
                        (a.energy?.get("estimated_daily_expenditure") as? Number)?.toDouble(), "kcal")
                    add(VO2MAX, a.vo2max, "ayuvo.fitness.vo2max_trend", algo("ayuvo.fitness.vo2max_trend"), 365)
                    add(MET_WEEK, a.met, "ayuvo.met.intensity", algo("ayuvo.met.intensity"), 7,
                        (a.met?.get("moderate_equivalent_min") as? Number)?.toDouble(), "min")
                    add(CORRELATION, a.correlation, "ayuvo.correlation", algo("ayuvo.correlation"), 120)
                    for ((metric, pair) in a.trends) {
                        add("trend:$metric", pair.second + mapOf("baseline" to pair.first), "ayuvo.trend", algo("ayuvo.trend"), 28,
                            (pair.first["value"] as? Number)?.toDouble())
                    }
                    // Day-level rows are rewritten only when the day's inputs (or the algorithm version) changed.
                    val unchanged = rows.filter { it.periodStart == today.toString() && it.metricId != RECOVERY }.filter { r ->
                        repo.inputHashes(r.metricId, r.algorithmVersion, r.periodStart, r.periodStart)[r.periodStart] == r.inputHash
                    }.toSet()
                    skipped += unchanged.size
                    rows.removeAll(unchanged)
                }
                written = rows.size
                repo.upsertResults(rows)
            }
            val forecasts = if (forecastEnabled()) runCatching { forecasts(snap, inputs, cfg, repo) }.getOrDefault(emptyMap()) else emptyMap()
            val next = AnalyticsServiceState(snap.today.toString(), forecasts, written, skipped)
            _state.value = next
            next
        }
    }

    /** Trains (at most every [RETRAIN_DAYS] days) and predicts tomorrow for each configured target. */
    private suspend fun forecasts(snap: InsightsSnapshot, inputs: JMap, cfg: AnalyticsConfig, repo: AnalyticsRepository?): Map<String, ForecastView> {
        val fc = cfg.root["forecast"] as Map<*, *>
        val algo = fc["algorithm_id"] as String
        val today = snap.today.toString()
        val from = snap.today.minusDays(com.ayuvo.health.insights.InsightsDataSource.HISTORY_DAYS - 1).toString()
        val out = LinkedHashMap<String, ForecastView>()
        @Suppress("UNCHECKED_CAST")
        val series = (inputs["series"] as? Map<String, Any?>).orEmpty()
        for (target in (fc["targets"] as List<*>).map { it as String }) {
            val modelId = "$algo.$target"
            val latest = repo?.latestModel(modelId)
            val stale = latest == null || latest.trainEnd == null ||
                LocalDate.parse(latest.trainEnd).plusDays(RETRAIN_DAYS + DAYS_AFTER_TRAIN_END).isBefore(snap.today) ||
                latest.featureSchemaVersion != (fc["feature_schema_version"] as Number).toInt()
            val model: MlModelRow? = if (stale) {
                val feats = AnalyticsForecast.forecastFeatures(inputs, from, today, cfg)
                val res = AnalyticsForecast.forecast(target, feats, AnalyticsCore.seriesOf(series[target]), today, cfg)
                if (res["status"] == "INSUFFICIENT_HISTORY" || res["status"] == "INVALID_INPUT") null else {
                    val half = run {
                        val lo = (res["interval_low"] as? Number)?.toDouble()
                        val hi = (res["interval_high"] as? Number)?.toDouble()
                        if (lo != null && hi != null) (hi - lo) / 2.0 else null
                    }
                    @Suppress("UNCHECKED_CAST")
                    val metrics = LinkedHashMap((res["metrics"] as? Map<String, Any?>).orEmpty()).apply {
                        put("interval_half_width", half)
                        put("n_test", res["n_test"])
                        put("n_rows", res["n_rows"])
                    }
                    val row = MlModelRow(
                        modelId = modelId, modelVersion = 0, algorithmVersion = (fc["model_version"] as Number).toInt(),
                        target = target, featureSchemaVersion = (fc["feature_schema_version"] as Number).toInt(),
                        trainStart = res["train_start"] as? String, trainEnd = res["train_end"] as? String,
                        valStart = res["val_start"] as? String, valEnd = res["val_end"] as? String,
                        testStart = res["test_start"] as? String, testEnd = res["test_end"] as? String,
                        lambda = (res["lambda"] as? Number)?.toDouble(),
                        coefficientsJson = json(mapOf("intercept" to res["intercept"], "coefficients" to res["coefficients"])),
                        normalizationJson = json(res["normalization"]), metricsJson = json(metrics),
                        baselineMetricsJson = json(res["baselines"]), deployed = res["deployed"] == true, createdMs = nowMs()
                    )
                    val version = repo?.insertModel(row) ?: 1
                    row.copy(modelVersion = version)
                }
            } else latest
            model ?: continue
            val todayFeatures = AnalyticsForecast.forecastFeatures(inputs, today, today, cfg)[today].orEmpty()
            val pred = predict(model, todayFeatures)
            val metrics = parse(model.metricsJson)
            val half = (metrics["interval_half_width"] as? Number)?.toDouble()
            out[target] = ForecastView(
                target = target, modelVersion = model.modelVersion, deployed = model.deployed,
                prediction = pred?.let { AnalyticsMath.roundTo(it, 1) },
                intervalLow = if (pred != null && half != null) AnalyticsMath.roundTo(pred - half, 1) else null,
                intervalHigh = if (pred != null && half != null) AnalyticsMath.roundTo(pred + half, 1) else null,
                forDay = snap.today.plusDays(1).toString(), metrics = metrics, baselines = parse(model.baselineMetricsJson),
                trainEnd = model.trainEnd, nTest = (metrics["n_test"] as? Number)?.toInt() ?: 0
            )
        }
        return out
    }

    companion object {
        const val RECOVERY = "recovery_indicator"
        const val ANOMALY = "anomaly"
        const val HRV = "hrv_status"
        const val SLEEP = "sleep_status"
        const val LOAD = "load"
        const val HRR = "hrr"
        const val ENERGY = "energy"
        const val VO2MAX = "vo2max_trend"
        const val MET_WEEK = "met_week"
        const val CORRELATION = "correlation"
        const val RETRAIN_DAYS = 7L
        /** The newest training row's target lies one day after train_end; the rest is validation + test. */
        private const val DAYS_AFTER_TRAIN_END = 0L
        /** Days of history each day's input slice covers (Recovery reads 60 baseline days, load up to 120). */
        const val SLICE_DAYS = 120L

        /**
         * The canonical input slice of [day]: every day-keyed map cut to [day - 120, day] and the workouts that start
         * in that range. Unchanged slices mean unchanged results.
         */
        @Suppress("UNCHECKED_CAST")
        fun slice(inputs: JMap, day: LocalDate): JMap {
            val lo = day.minusDays(SLICE_DAYS).toString()
            val hi = day.toString()
            fun cut(m: Any?): Any? = (m as? Map<String, Any?>)?.filterKeys { it.length == 10 && it[4] == '-' && it in lo..hi }
            fun cutAll(m: Any?): Any? = (m as? Map<String, Any?>)?.mapValues { cut(it.value) }
            val loMs = day.minusDays(SLICE_DAYS + 1).toEpochDay() * 86_400_000L
            val hiMs = day.plusDays(2).toEpochDay() * 86_400_000L
            return linkedMapOf(
                "day" to hi,
                "series" to cutAll(inputs["series"]), "contexts" to cutAll(inputs["contexts"]),
                "sources" to cutAll(inputs["sources"]), "nights" to cut(inputs["nights"]),
                "nutrition" to cut(inputs["nutrition"]),
                "workouts" to (inputs["workouts"] as? List<Map<String, Any?>>).orEmpty().filter {
                    val s = (it["start_ms"] as Number).toLong()
                    s in loMs until hiMs
                },
                "tracking" to inputs["tracking"], "time_zone" to inputs["time_zone"]
            )
        }

        fun hashOf(slice: JMap, algorithmVersion: Int, configVersion: Int): String =
            AnalyticsCore.inputHash(mapOf("value" to mapOf("slice" to slice, "v" to algorithmVersion, "c" to configVersion)))["hash"] as String

        /**
         * Ridge prediction from a stored model: intercept + Σ β·x, x standardised with the stored training mean / SD,
         * a missing value imputed with the stored training median plus its missing indicator (scripts/analytics_reference
         * `_vector`). Null when the model has no coefficients.
         */
        fun predict(model: MlModelRow, features: Map<String, Double?>): Double? {
            val coef = parse(model.coefficientsJson)
            val intercept = (coef["intercept"] as? Number)?.toDouble() ?: return null
            @Suppress("UNCHECKED_CAST")
            val betas = (coef["coefficients"] as? Map<String, Any?>).orEmpty()
            @Suppress("UNCHECKED_CAST")
            val norm = parse(model.normalizationJson) as Map<String, Map<String, Any?>>
            var s = intercept
            for ((key, b) in betas) {
                val beta = (b as? Number)?.toDouble() ?: continue
                val (f, kind) = key.split(':').let { it[0] to it.getOrElse(1) { "value" } }
                val n = norm[f] ?: continue
                val raw = features[f]
                val x = if (kind == "missing") (if (raw == null) 1.0 else 0.0) else {
                    val v = raw ?: (n["median"] as Number).toDouble()
                    (v - (n["mean"] as Number).toDouble()) / (n["sd"] as Number).toDouble()
                }
                s += beta * x
            }
            return s
        }

        fun json(v: Any?): String = AnalyticsMath.toJson(v).toString()

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): JMap = runCatching {
            AnalyticsMath.plain(com.ayuvo.health.medications.logic.MedicationJson.json.parseToJsonElement(text)) as JMap
        }.getOrDefault(emptyMap())

        /** One result row with provenance (docs/health-analytics.md §4.4). */
        fun row(
            metric: String,
            day: LocalDate,
            res: JMap,
            algorithmId: String,
            version: Int,
            cfg: AnalyticsConfig,
            hash: String,
            inputs: JMap,
            window: Int?,
            value: Double? = null,
            unit: String? = null
        ): AnalyticsResultRow {
            @Suppress("UNCHECKED_CAST")
            val series = (inputs["series"] as? Map<String, Map<String, Any?>>).orEmpty()
            @Suppress("UNCHECKED_CAST")
            val sources = (inputs["sources"] as? Map<String, Map<String, Any?>>).orEmpty()
            val lo = window?.let { day.minusDays(it.toLong()).toString() }
            fun inWindow(d: String) = lo == null || d in lo..day.toString()
            val sampleCount = series.values.sumOf { m -> m.keys.count(::inWindow) }
            val sourceIds = sources.values.flatMap { m -> m.filterKeys(::inWindow).values.mapNotNull { it as? String } }.toSortedSet()
            val provenance = linkedMapOf(
                "algorithm" to "$algorithmId@$version", "config_version" to cfg.configVersion,
                "inputs" to series.keys.sorted() + listOf("nights", "workouts").filter { (inputs[it] as? Collection<*>)?.isNotEmpty() == true || (inputs[it] as? Map<*, *>)?.isNotEmpty() == true },
                "sources" to sourceIds.toList(), "sample_count" to sampleCount, "window" to window,
                "coverage" to res["coverage"], "fallbacks" to linkedMapOf(
                    "overnight_fallback" to inputs["overnight_fallback"], "scan_fallback" to inputs["scan_fallback"]
                )
            )
            return AnalyticsResultRow(
                metricId = metric, periodStart = day.toString(), periodEnd = day.toString(), algorithmId = algorithmId,
                algorithmVersion = version, configVersion = cfg.configVersion, status = (res["status"] as? String) ?: "VALID",
                classification = (res["classification"] as? String) ?: "PERSONALIZED_STATISTICAL", value = value,
                unit = unit, confidence = (res["confidence"] as? Number)?.toDouble(), coverage = (res["coverage"] as? Number)?.toDouble(),
                inputCount = sampleCount, baselineWindowDays = window, resultJson = json(res),
                provenanceJson = json(provenance), inputHash = hash, computedMs = System.currentTimeMillis()
            )
        }
    }
}
