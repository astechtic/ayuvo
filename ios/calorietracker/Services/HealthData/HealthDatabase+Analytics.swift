import Foundation

/// One `analytics_results` row (schema v5, docs/health-analytics.md): a versioned analytics result with its status,
/// classification, confidence and provenance. Never exported; recomputable from the mirror.
nonisolated struct AnalyticsResultRow: Sendable, Hashable {
    var metricID: String
    var periodStart: String
    var periodEnd: String
    var algorithmID: String
    var algorithmVersion: Int
    var configVersion: Int
    var status: String
    var classification: String
    var value: Double?
    var value2: Double?
    var value3: Double?
    var unit: String?
    var confidence: Double?
    var coverage: Double?
    var inputCount: Int?
    var baselineWindowDays: Int?
    var resultJSON: String
    var provenanceJSON: String
    var inputHash: String
    var computedMs: Int64
}

/// `analytics_state`: incremental-processing bookkeeping per metric.
nonisolated struct AnalyticsStateRow: Sendable, Hashable {
    var metricID: String
    var algorithmVersion: Int
    var configVersion: Int
    var lastProcessedDay: String?
    var updatedMs: Int64
}

/// `ml_models`: one trained per-user forecast model with its governance fields.
nonisolated struct MLModelRow: Sendable, Hashable {
    var modelID: String
    var modelVersion: Int
    var algorithmVersion: Int
    var target: String
    var featureSchemaVersion: Int
    var trainStart: String?
    var trainEnd: String?
    var valStart: String?
    var valEnd: String?
    var testStart: String?
    var testEnd: String?
    var lambda: Double?
    var coefficientsJSON: String
    var normalizationJSON: String
    var metricsJSON: String
    var baselineMetricsJSON: String
    var deployed: Bool
    var createdMs: Int64
}

extension HealthDatabase {
    /// Algorithm versions kept per metric: the current one and the one before it (recomputation history).
    nonisolated static let analyticsVersionsKept = 2

    private nonisolated static let analyticsColumns =
        "metric_id, period_start, period_end, algorithm_id, algorithm_version, config_version, status, classification, " +
        "value, value2, value3, unit, confidence, coverage, input_count, baseline_window_days, result_json, " +
        "provenance_json, input_hash, computed_ms"

    nonisolated static func decodeAnalytics(_ s: HealthDBStatement) -> AnalyticsResultRow {
        AnalyticsResultRow(
            metricID: s.text(0) ?? "", periodStart: s.text(1) ?? "", periodEnd: s.text(2) ?? "",
            algorithmID: s.text(3) ?? "", algorithmVersion: s.int(4) ?? 0, configVersion: s.int(5) ?? 0,
            status: s.text(6) ?? "", classification: s.text(7) ?? "", value: s.double(8), value2: s.double(9),
            value3: s.double(10), unit: s.text(11), confidence: s.double(12), coverage: s.double(13),
            inputCount: s.int(14), baselineWindowDays: s.int(15), resultJSON: s.text(16) ?? "{}",
            provenanceJSON: s.text(17) ?? "{}", inputHash: s.text(18) ?? "", computedMs: s.int64(19) ?? 0
        )
    }

    /// Inserts or replaces results (same metric, period start and algorithm version), then drops versions older than
    /// the newest `analyticsVersionsKept` of each touched metric. Older versions are never overwritten in place.
    func upsertAnalyticsResults(_ rows: [AnalyticsResultRow]) throws {
        guard !rows.isEmpty else { return }
        try connection.inTransaction {
            let insert = try connection.prepare(
                "INSERT OR REPLACE INTO analytics_results (\(Self.analyticsColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            for r in rows {
                insert.reset()
                try insert.bind([
                    .text(r.metricID), .text(r.periodStart), .text(r.periodEnd), .text(r.algorithmID),
                    .int(Int64(r.algorithmVersion)), .int(Int64(r.configVersion)), .text(r.status), .text(r.classification),
                    .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3), .optionalText(r.unit),
                    .optionalReal(r.confidence), .optionalReal(r.coverage), .optionalInt64(r.inputCount.map(Int64.init)),
                    .optionalInt64(r.baselineWindowDays.map(Int64.init)), .text(r.resultJSON), .text(r.provenanceJSON),
                    .text(r.inputHash), .int(r.computedMs),
                ])
                _ = try insert.step()
            }
            for metric in Set(rows.map(\.metricID)) {
                try connection.run(
                    """
                    DELETE FROM analytics_results WHERE metric_id=? AND algorithm_version NOT IN (
                      SELECT DISTINCT algorithm_version FROM analytics_results WHERE metric_id=?
                      ORDER BY algorithm_version DESC LIMIT ?)
                    """,
                    [.text(metric), .text(metric), .int(Int64(Self.analyticsVersionsKept))]
                )
            }
        }
    }

    /// Results of one metric and algorithm version whose period starts in [fromDay, toDay], oldest first.
    func analyticsResults(metric: String, algorithmVersion: Int, fromDay: String, toDay: String) throws -> [AnalyticsResultRow] {
        var rows: [AnalyticsResultRow] = []
        try connection.query(
            "SELECT \(Self.analyticsColumns) FROM analytics_results WHERE metric_id=? AND algorithm_version=? AND period_start BETWEEN ? AND ? ORDER BY period_start",
            [.text(metric), .int(Int64(algorithmVersion)), .text(fromDay), .text(toDay)]
        ) { rows.append(Self.decodeAnalytics($0)) }
        return rows
    }

    func latestAnalyticsResult(metric: String, algorithmVersion: Int) throws -> AnalyticsResultRow? {
        var row: AnalyticsResultRow?
        try connection.query(
            "SELECT \(Self.analyticsColumns) FROM analytics_results WHERE metric_id=? AND algorithm_version=? ORDER BY period_start DESC LIMIT 1",
            [.text(metric), .int(Int64(algorithmVersion))]
        ) { row = Self.decodeAnalytics($0) }
        return row
    }

    /// Stored input hashes by period start (skip unchanged days).
    func analyticsInputHashes(metric: String, algorithmVersion: Int, fromDay: String, toDay: String) throws -> [String: String] {
        var out: [String: String] = [:]
        try connection.query(
            "SELECT period_start, input_hash FROM analytics_results WHERE metric_id=? AND algorithm_version=? AND period_start BETWEEN ? AND ?",
            [.text(metric), .int(Int64(algorithmVersion)), .text(fromDay), .text(toDay)]
        ) { s in
            if let d = s.text(0), let h = s.text(1) { out[d] = h }
        }
        return out
    }

    func deleteAllAnalytics() throws {
        try connection.inTransaction {
            try connection.exec("DELETE FROM analytics_results; DELETE FROM analytics_state; DELETE FROM ml_models;")
        }
    }

    // MARK: State

    func analyticsState(metric: String) throws -> AnalyticsStateRow? {
        var row: AnalyticsStateRow?
        try connection.query(
            "SELECT metric_id, algorithm_version, config_version, last_processed_day, updated_ms FROM analytics_state WHERE metric_id=?",
            [.text(metric)]
        ) { s in
            row = AnalyticsStateRow(metricID: s.text(0) ?? "", algorithmVersion: s.int(1) ?? 0, configVersion: s.int(2) ?? 0,
                                    lastProcessedDay: s.text(3), updatedMs: s.int64(4) ?? 0)
        }
        return row
    }

    func setAnalyticsState(_ r: AnalyticsStateRow) throws {
        try connection.inTransaction {
            try connection.run(
                "INSERT OR REPLACE INTO analytics_state (metric_id, algorithm_version, config_version, last_processed_day, updated_ms) VALUES (?,?,?,?,?)",
                [.text(r.metricID), .int(Int64(r.algorithmVersion)), .int(Int64(r.configVersion)), .optionalText(r.lastProcessedDay),
                 .int(r.updatedMs)]
            )
        }
    }

    // MARK: Models

    private nonisolated static let modelColumns =
        "model_id, model_version, algorithm_version, target, feature_schema_version, train_start, train_end, val_start, " +
        "val_end, test_start, test_end, lambda, coefficients_json, normalization_json, metrics_json, baseline_metrics_json, " +
        "deployed, created_ms"

    /// Stores a new model; its `modelVersion` is one more than the newest stored version of `modelID` (models are
    /// never silently replaced). Returns the stored row.
    @discardableResult
    func insertMLModel(_ model: MLModelRow) throws -> MLModelRow {
        var r = model
        try connection.inTransaction {
            let newest = try connection.scalarInt64("SELECT MAX(model_version) FROM ml_models WHERE model_id=?", [.text(model.modelID)]) ?? 0
            r.modelVersion = Int(newest) + 1
            try connection.run(
                "INSERT INTO ml_models (\(Self.modelColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                [.text(r.modelID), .int(Int64(r.modelVersion)), .int(Int64(r.algorithmVersion)), .text(r.target),
                 .int(Int64(r.featureSchemaVersion)), .optionalText(r.trainStart), .optionalText(r.trainEnd),
                 .optionalText(r.valStart), .optionalText(r.valEnd), .optionalText(r.testStart), .optionalText(r.testEnd),
                 .optionalReal(r.lambda), .text(r.coefficientsJSON), .text(r.normalizationJSON), .text(r.metricsJSON),
                 .text(r.baselineMetricsJSON), .int(r.deployed ? 1 : 0), .int(r.createdMs)]
            )
        }
        return r
    }

    func latestMLModel(modelID: String) throws -> MLModelRow? {
        var row: MLModelRow?
        try connection.query(
            "SELECT \(Self.modelColumns) FROM ml_models WHERE model_id=? ORDER BY model_version DESC LIMIT 1", [.text(modelID)]
        ) { s in
            row = MLModelRow(
                modelID: s.text(0) ?? "", modelVersion: s.int(1) ?? 0, algorithmVersion: s.int(2) ?? 0, target: s.text(3) ?? "",
                featureSchemaVersion: s.int(4) ?? 0, trainStart: s.text(5), trainEnd: s.text(6), valStart: s.text(7),
                valEnd: s.text(8), testStart: s.text(9), testEnd: s.text(10), lambda: s.double(11),
                coefficientsJSON: s.text(12) ?? "{}", normalizationJSON: s.text(13) ?? "{}", metricsJSON: s.text(14) ?? "{}",
                baselineMetricsJSON: s.text(15) ?? "{}", deployed: (s.int(16) ?? 0) != 0, createdMs: s.int64(17) ?? 0
            )
        }
        return row
    }
}
