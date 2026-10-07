import Foundation

/// Runs the platform side of the analytics engine (docs/health-analytics.md §6):
/// - after every derived pass, reads new Apple Watch beat-to-beat series (`HeartbeatHRVSync`);
/// - after every Insights computation, stores the results in `analytics_results` with status, classification,
///   confidence and provenance. A row is only rewritten when its input fingerprint changed (incremental);
/// - stores each forecast retrain in `ml_models` (at most weekly, only while the forecast switch is on).
/// Nothing here is exported or uploaded.
@MainActor
@Observable
final class AnalyticsService {
    static let shared = AnalyticsService()

    /// Bumped when new beat-to-beat results were stored (Insights recomputes).
    private(set) var revision = 0
    /// Rows written by the last `persist` (tests and diagnostics).
    @ObservationIgnored private(set) var lastWrittenCount = 0

    private let defaults: UserDefaults
    private let calendar: Calendar
    /// Minimum time between stored forecast models.
    static let modelRetrainInterval: TimeInterval = 7 * 86_400

    init(defaults: UserDefaults = .standard, calendar: Calendar = .current) {
        self.defaults = defaults
        self.calendar = calendar
    }

    private func database() async -> HealthDatabase? {
        guard defaults.bool(forKey: "healthKitEnabled") else { return nil }
        let runtime = HealthDataRuntime.shared
        guard await runtime.openIfNeeded() else { return nil }
        return runtime.writer
    }

    /// Ayuvo RMSSD for days with new heartbeat series.
    func syncHeartbeats() async {
        guard let db = await database() else { return }
        if await HeartbeatHRVSync.run(database: db, timeZone: calendar.timeZone.identifier, calendar: calendar) {
            revision += 1
        }
    }

    /// Stores the analytics results of `report` (incremental) and, when due, the forecast models.
    func persist(_ report: InsightsReport, now: Date = Date()) async {
        guard let bundle = report.analytics, let db = await database() else { return }
        await Self.persist(bundle, database: db, nowMs: Int64((now.timeIntervalSince1970 * 1000).rounded()))
        if !bundle.forecasts.isEmpty { await storeModels(bundle, database: db, now: now) }
    }

    /// Writes the rows whose fingerprint differs from the stored one. Returns the number written.
    @discardableResult
    nonisolated static func persist(_ bundle: AnalyticsBundle, database db: HealthDatabase, nowMs: Int64) async -> Int {
        let candidates = AnalyticsRows.rows(bundle, nowMs: nowMs)
        guard let first = candidates.map(\.periodStart).min(), let last = candidates.map(\.periodStart).max() else { return 0 }
        var stored: [AnalyticsRows.Key: [String: String]] = [:]
        for key in Set(candidates.map { AnalyticsRows.Key(metric: $0.metricID, version: $0.algorithmVersion) }) {
            stored[key] = (try? await db.analyticsInputHashes(metric: key.metric, algorithmVersion: key.version, fromDay: first, toDay: last)) ?? [:]
        }
        let changed = candidates.filter {
            stored[AnalyticsRows.Key(metric: $0.metricID, version: $0.algorithmVersion)]?[$0.periodStart] != $0.inputHash
        }
        guard !changed.isEmpty else { return 0 }
        try? await db.upsertAnalyticsResults(changed)
        return changed.count
    }

    private func storeModels(_ bundle: AnalyticsBundle, database db: HealthDatabase, now: Date) async {
        for (_, f) in bundle.forecasts.sorted(by: { $0.key < $1.key }) {
            guard let row = AnalyticsRows.model(f, nowMs: Int64((now.timeIntervalSince1970 * 1000).rounded())) else { continue }
            let latest = try? await db.latestMLModel(modelID: row.modelID)
            let due = latest.map { now.timeIntervalSince1970 * 1000 - Double($0.createdMs) >= Self.modelRetrainInterval * 1000 } ?? true
            let schemaChanged = latest.map { $0.featureSchemaVersion != row.featureSchemaVersion || $0.algorithmVersion != row.algorithmVersion } ?? false
            guard due || schemaChanged else { continue }
            _ = try? await db.insertMLModel(row)
        }
    }
}

/// `analytics_results` / `ml_models` rows from an `AnalyticsBundle`.
nonisolated enum AnalyticsRows {
    struct Key: Hashable { let metric: String; let version: Int }

    /// Registry entry (`algorithms[]`) of an engine function.
    static func algorithm(_ function: String, _ cfg: AnalyticsConfig = .shared) -> (id: String, version: Int, classification: String) {
        let a = cfg["algorithms"].array.first { $0["function"].string == function }
        return (a?["id"].string ?? "ayuvo.\(function)", a?["version"].int ?? 1, a?["classification"].string ?? "PERSONALIZED_STATISTICAL")
    }

    static func rows(_ b: AnalyticsBundle, nowMs: Int64) -> [AnalyticsResultRow] {
        var out: [AnalyticsResultRow] = []
        func add(_ metric: String, _ function: String, day: String, _ j: AJ, value: Double?, value2: Double? = nil,
                 value3: Double? = nil, unit: String? = nil, inputs: [String], window: Int? = nil) {
            guard j.has else { return }
            let algo = algorithm(function)
            out.append(row(metric: metric, algo: algo, day: day, result: j, value: value, value2: value2, value3: value3,
                           unit: unit, inputs: inputs, window: window, nowMs: nowMs))
        }
        for (day, r) in b.recoveryByDay.sorted(by: { $0.key < $1.key }) {
            add("recovery_indicator", "recovery", day: day, r, value: r["score"].double, value2: r["coverage"].double, unit: "score",
                inputs: ["hrv", "resting_heart_rate", "sleep", "respiratory_rate", "wrist_temperature", "blood_oxygen", "workouts"], window: 60)
        }
        for (day, r) in b.anomalyByDay.sorted(by: { $0.key < $1.key }) {
            add("multi_signal_deviation", "anomaly", day: day, r, value: r["flagged_count"].double, unit: "signals",
                inputs: r["signals"].array.compactMap { $0["id"].string }, window: 28)
        }
        let t = b.today
        add("hrv_status", "hrv_status", day: t, b.hrv, value: b.hrv["baseline"]["value"].double, value2: b.hrv["baseline"]["median"].double,
            value3: b.hrv["cv_ln_7d"].double, unit: "ms", inputs: ["hrv"], window: 28)
        add("hrv_status_ayuvo", "hrv_status", day: t, b.hrvAyuvo, value: b.hrvAyuvo["baseline"]["value"].double,
            value2: b.hrvAyuvo["baseline"]["median"].double, value3: b.hrvAyuvo["cv_ln_7d"].double, unit: "ms", inputs: ["ayuvo_rmssd"], window: 28)
        add("sleep_status", "sleep_status", day: t, b.sleep, value: b.sleep["asleep_min"].double, value2: b.sleep["need_min"].double,
            value3: b.sleep["debt_min"].double, unit: "min", inputs: ["sleep"], window: 28)
        let pm = b.load["primary_method"].string ?? ""
        add("training_load", "load", day: t, b.load, value: b.load["methods"][pm]["acute"].double,
            value2: b.load["methods"][pm]["chronic"].double, value3: b.load["methods"][pm]["ratio"].double, unit: "AU",
            inputs: ["workouts"], window: 90)
        for row in b.trends {
            add("trend:\(row.id)", "trend", day: row.day, .obj(["baseline": row.baseline, "trend": row.trend]),
                value: row.baseline["value"].double, value2: row.baseline["median"].double, value3: row.trend["pct_per_week"].double,
                inputs: [row.id], window: 28)
        }
        if let hrr = b.hrr {
            add("trend:hrr1", "trend", day: hrr.day, .obj(["baseline": hrr.baseline, "trend": hrr.trend]),
                value: hrr.baseline["value"].double, value2: hrr.baseline["median"].double, unit: "bpm", inputs: ["heart_rate_recovery_one_minute"], window: 90)
        }
        add("energy", "energy", day: b.energy["day"].string ?? t, b.energy, value: b.energy["estimated_daily_expenditure"].double,
            value2: b.energy["resting_kcal"].double, value3: b.energy["active_kcal"].double, unit: "kcal",
            inputs: ["resting_energy", "active_energy", "workouts", "profile"])
        add("vo2max_trend", "vo2max_trend", day: t, b.vo2max, value: nil, inputs: ["vo2_max", "vo2max_estimate"], window: 365)
        add("met_week", "met_intensity", day: t, b.met, value: b.met["moderate_equivalent_min"].double, value2: b.met["met_minutes"].double,
            unit: "min", inputs: ["workouts"], window: 7)
        add("correlation", "correlation", day: t, b.correlation, value: b.correlation["tested"].double, inputs: ["patterns"], window: 120)
        for (target, f) in b.forecasts {
            add("forecast:\(target)", "forecast", day: t, f, value: f["prediction"].double, value2: f["interval_low"].double,
                value3: f["interval_high"].double, inputs: [target], window: f["n_rows"].int)
        }
        return out
    }

    static func row(metric: String, algo: (id: String, version: Int, classification: String), day: String, result: AJ,
                    value: Double?, value2: Double?, value3: Double?, unit: String?, inputs: [String], window: Int?,
                    nowMs: Int64, cfg: AnalyticsConfig = .shared) -> AnalyticsResultRow {
        let status = result["status"].string ?? result["baseline"]["status"].string ?? "VALID"
        let classification = result["classification"].string ?? algo.classification
        let confidence = result["confidence"].double ?? result["baseline"]["confidence"].double
        let coverage = result["coverage"].double ?? result["baseline"]["coverage"].double
        let count = result["n"].int ?? result["baseline"]["n"].int ?? result["sample_count"].int
        // Fingerprint of everything the result was computed from as it appears in the result (component values,
        // baselines, counts, contexts), plus the algorithm and config versions: unchanged → the row is not rewritten.
        let fingerprint = AnalyticsCore.inputHash(.obj([
            "result": result, "algorithm": .str("\(algo.id)@\(algo.version)"), "config": .num(Double(cfg.configVersion)),
        ]))["hash"].string ?? ""
        let fallbacks = result["warnings"].array.compactMap { w -> AJ? in
            guard let code = w["code"].string, code != "missing" else { return nil }
            return .str("\(code):\(w["metric"].string ?? "")")
        }
        let provenance: AJ = .obj([
            "algorithm": .str("\(algo.id)@\(algo.version)"), "config_version": .num(Double(cfg.configVersion)),
            "inputs": .arr(inputs.map { .str($0) }), "sources": .arr([.str("HealthKit"), .str("Ayuvo")]),
            "sample_count": .i(count), "window_days": .i(window), "coverage": .n(coverage), "fallbacks": .arr(fallbacks),
            "hash_basis": .str("result_inputs"),
        ])
        return AnalyticsResultRow(
            metricID: metric, periodStart: day, periodEnd: day, algorithmID: algo.id, algorithmVersion: algo.version,
            configVersion: cfg.configVersion, status: status, classification: classification, value: value, value2: value2,
            value3: value3, unit: unit, confidence: confidence, coverage: coverage, inputCount: count,
            baselineWindowDays: window, resultJSON: result.jsonString, provenanceJSON: provenance.jsonString,
            inputHash: fingerprint, computedMs: nowMs
        )
    }

    /// A model row from a forecast result that trained (any status with metrics); nil otherwise.
    static func model(_ f: AJ, nowMs: Int64, cfg: AnalyticsConfig = .shared) -> MLModelRow? {
        guard f["metrics"].has, let id = f["model_id"].string else { return nil }
        return MLModelRow(
            modelID: id, modelVersion: 0, algorithmVersion: f["model_version"].int ?? 1, target: f["target"].string ?? "",
            featureSchemaVersion: f["feature_schema_version"].int ?? 1, trainStart: f["train_start"].string,
            trainEnd: f["train_end"].string, valStart: f["val_start"].string, valEnd: f["val_end"].string,
            testStart: f["test_start"].string, testEnd: f["test_end"].string, lambda: f["lambda"].double,
            coefficientsJSON: AJ.obj(["intercept": f["intercept"], "coefficients": f["coefficients"]]).jsonString,
            normalizationJSON: f["normalization"].jsonString, metricsJSON: f["metrics"].jsonString,
            baselineMetricsJSON: f["baselines"].jsonString, deployed: f["deployed"].bool ?? false, createdMs: nowMs
        )
    }
}
