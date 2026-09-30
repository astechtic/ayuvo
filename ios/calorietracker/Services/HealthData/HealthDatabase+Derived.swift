import Foundation

/// One `derived_daily_values` row (docs/derived-metrics.md §3): a value Ayuvo computed on the device for a derived
/// metric and day. Never exported; rebuilt whenever inputs, switches or `algo_version` change.
nonisolated struct DerivedDailyValueRow: Sendable, Hashable {
    var metricID: String
    var day: String
    var value: Double?
    var value2: Double?
    var value3: Double?
    var quality: Double?
    var sourceKind: String = "derived"
    var algoVersion: Int
    var computedMs: Int64
}

extension HealthDatabase {
    private nonisolated static let derivedColumns =
        "metric_id, day, value, value2, value3, quality, source_kind, algo_version, computed_ms"

    nonisolated static func decodeDerived(_ s: HealthDBStatement) -> DerivedDailyValueRow {
        DerivedDailyValueRow(
            metricID: s.text(0) ?? "",
            day: s.text(1) ?? "",
            value: s.double(2),
            value2: s.double(3),
            value3: s.double(4),
            quality: s.double(5),
            sourceKind: s.text(6) ?? "derived",
            algoVersion: s.int(7) ?? 0,
            computedMs: s.int64(8) ?? 0
        )
    }

    /// Replaces the values of `metricIDs` on `days` with `rows` in one transaction: every (metric, day) pair in the
    /// cross product is cleared first, so a day that no longer produces a value loses its stale row.
    func replaceDerivedValues(_ rows: [DerivedDailyValueRow], metricIDs: [String], days: [String]) throws {
        try connection.inTransaction {
            let delete = try connection.prepare("DELETE FROM derived_daily_values WHERE metric_id=? AND day=?")
            for metric in metricIDs {
                for day in days {
                    delete.reset()
                    try delete.bind([.text(metric), .text(day)])
                    _ = try delete.step()
                }
            }
            guard !rows.isEmpty else { return }
            let insert = try connection.prepare(
                "INSERT OR REPLACE INTO derived_daily_values (\(Self.derivedColumns)) VALUES (?,?,?,?,?,?,?,?,?)"
            )
            for r in rows {
                insert.reset()
                try insert.bind([
                    .text(r.metricID), .text(r.day), .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3),
                    .optionalReal(r.quality), .text(r.sourceKind), .int(Int64(r.algoVersion)), .int(r.computedMs),
                ])
                _ = try insert.step()
            }
        }
    }

    func derivedValues(metric: String, fromDay: String, toDay: String) throws -> [DerivedDailyValueRow] {
        var rows: [DerivedDailyValueRow] = []
        try connection.query(
            "SELECT \(Self.derivedColumns) FROM derived_daily_values WHERE metric_id=? AND day BETWEEN ? AND ? ORDER BY day",
            [.text(metric), .text(fromDay), .text(toDay)]
        ) { rows.append(Self.decodeDerived($0)) }
        return rows
    }

    func latestDerivedValue(metric: String) throws -> DerivedDailyValueRow? {
        var row: DerivedDailyValueRow?
        try connection.query(
            "SELECT \(Self.derivedColumns) FROM derived_daily_values WHERE metric_id=? AND value IS NOT NULL ORDER BY day DESC LIMIT 1",
            [.text(metric)]
        ) { row = Self.decodeDerived($0) }
        return row
    }

    /// Metric ids that have at least one stored value (Browse / Coach listing).
    func derivedMetricIDsWithValues() throws -> [String] {
        var ids: [String] = []
        try connection.query("SELECT DISTINCT metric_id FROM derived_daily_values WHERE value IS NOT NULL ORDER BY metric_id") {
            if let id = $0.text(0) { ids.append(id) }
        }
        return ids
    }

    /// Days whose stored values were computed by an older algorithm (recomputed on the next pass).
    func staleDerivedDays(algoVersion: Int) throws -> [String] {
        var days: [String] = []
        try connection.query(
            "SELECT DISTINCT day FROM derived_daily_values WHERE algo_version<>? ORDER BY day", [.int(Int64(algoVersion))]
        ) { if let d = $0.text(0) { days.append(d) } }
        return days
    }

    /// A switched-off metric loses every stored value (docs/derived-metrics.md §1).
    func deleteDerivedValues(metricIDs: [String]) throws {
        guard !metricIDs.isEmpty else { return }
        try connection.inTransaction {
            let statement = try connection.prepare("DELETE FROM derived_daily_values WHERE metric_id=?")
            for id in metricIDs {
                statement.reset()
                try statement.bind([.text(id)])
                _ = try statement.step()
            }
        }
    }

    func deleteAllDerivedValues() throws {
        try connection.inTransaction {
            try connection.exec("DELETE FROM derived_daily_values")
        }
    }
}
