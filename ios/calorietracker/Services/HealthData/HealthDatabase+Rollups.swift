import Foundation

extension HealthDatabase {
    private nonisolated static let rollupColumns =
        "type_id, day, tz, sum, avg, min, max, count, last_value, last_at_ms, v2_avg, v2_min, v2_max, duration_s, own_sum, from_platform_aggregate"

    nonisolated static func decodeRollup(_ s: HealthDBStatement) -> HealthDailyRollupRow {
        HealthDailyRollupRow(
            typeID: s.text(0) ?? "",
            day: s.text(1) ?? "",
            tz: s.text(2) ?? "",
            sum: s.double(3),
            avg: s.double(4),
            min: s.double(5),
            max: s.double(6),
            count: s.int(7) ?? 0,
            lastValue: s.double(8),
            lastAtMs: s.int64(9),
            v2Avg: s.double(10),
            v2Min: s.double(11),
            v2Max: s.double(12),
            durationS: s.double(13),
            ownSum: s.double(14),
            fromPlatformAggregate: s.int(15) ?? 0
        )
    }

    func replaceDailyRollups(_ rollups: [HealthDailyRollupRow]) throws {
        try connection.inTransaction {
            try replaceDailyRollupsInTransaction(rollups)
        }
    }

    func replaceDailyRollupsInTransaction(_ rollups: [HealthDailyRollupRow]) throws {
        guard !rollups.isEmpty else { return }
        let statement = try connection.prepare(
            "INSERT OR REPLACE INTO health_daily_rollups (\(Self.rollupColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        for r in rollups {
            statement.reset()
            try statement.bind([
                .text(r.typeID), .text(r.day), .text(r.tz), .optionalReal(r.sum), .optionalReal(r.avg), .optionalReal(r.min),
                .optionalReal(r.max), .int(Int64(r.count)), .optionalReal(r.lastValue), .optionalInt64(r.lastAtMs),
                .optionalReal(r.v2Avg), .optionalReal(r.v2Min), .optionalReal(r.v2Max), .optionalReal(r.durationS),
                .optionalReal(r.ownSum), .int(Int64(r.fromPlatformAggregate)),
            ])
            _ = try statement.step()
        }
    }

    func deleteDailyRollupsInTransaction(type: String, days: [String]) throws {
        guard !days.isEmpty else { return }
        let statement = try connection.prepare("DELETE FROM health_daily_rollups WHERE type_id=? AND day=?")
        for day in days {
            statement.reset()
            try statement.bind([.text(type), .text(day)])
            _ = try statement.step()
        }
    }

    func dailyRollups(type: String, fromDay: String, toDay: String) throws -> [HealthDailyRollupRow] {
        var rollups: [HealthDailyRollupRow] = []
        try connection.query(
            "SELECT \(Self.rollupColumns) FROM health_daily_rollups WHERE type_id=? AND day BETWEEN ? AND ? ORDER BY day",
            [.text(type), .text(fromDay), .text(toDay)]
        ) { rollups.append(Self.decodeRollup($0)) }
        return rollups
    }

    func latestRollup(type: String) throws -> HealthDailyRollupRow? {
        var rollup: HealthDailyRollupRow?
        try connection.query(
            "SELECT \(Self.rollupColumns) FROM health_daily_rollups WHERE type_id=? ORDER BY day DESC LIMIT 1",
            [.text(type)]
        ) { rollup = Self.decodeRollup($0) }
        return rollup
    }

    func rollupCount(type: String? = nil) throws -> Int {
        if let type {
            return Int(try connection.scalarInt64("SELECT COUNT(*) FROM health_daily_rollups WHERE type_id=?", [.text(type)]) ?? 0)
        }
        return Int(try connection.scalarInt64("SELECT COUNT(*) FROM health_daily_rollups") ?? 0)
    }

    /// Rebuilds the daily rollups of `type` for `days` from the stored rows (the only
    /// rollup path on iOS, so "incremental == full rebuild" holds by construction).
    /// Returns the number of rollup rows written.
    @discardableResult
    func rebuildRollups(
        type: HealthMetricType,
        days: [String],
        tz: String,
        calendar: Calendar,
        ownBundleID: String
    ) throws -> Int {
        var written = 0
        // A sleep row dated D can belong to the night that wakes on D + 1 (a stage ending before midnight), and a new
        // row on D can pull an episode away from D − 1, so both neighbours are rebuilt too.
        let days = type.isSleep
            ? Array(Set(days + days.compactMap { HealthDatabase.nextDay($0) } + days.compactMap { HealthDatabase.previousDay($0) })).sorted()
            : days
        for chunk in stride(from: 0, to: days.count, by: 200).map({ Array(days[$0..<min($0 + 200, days.count)]) }) {
            try connection.inTransaction {
                var toWrite: [HealthDailyRollupRow] = []
                var toDelete: [String] = []
                for day in chunk {
                    let rows = type.isSleep
                        // D − 1 holds stages of night D that end before midnight; D + 1 completes an episode that
                        // only starts on D, so it is never mistaken for a night of D.
                        ? try rowsForDays(type: type.id, fromDay: HealthDatabase.previousDay(day) ?? day,
                                          toDay: HealthDatabase.nextDay(day) ?? day)
                        : try rowsForDay(type: type.id, day: day)
                    if let rollup = HealthRollupMath.dailyRollup(rows: rows, type: type, day: day, tz: tz, ownBundleID: ownBundleID, calendar: calendar) {
                        toWrite.append(rollup)
                    } else {
                        toDelete.append(day)
                    }
                }
                try replaceDailyRollupsInTransaction(toWrite)
                try deleteDailyRollupsInTransaction(type: type.id, days: toDelete)
                written += toWrite.count
            }
        }
        return written
    }

    /// `yyyy-MM-dd` ± 1 (Gregorian, UTC arithmetic on the key itself).
    nonisolated static func nextDay(_ day: String) -> String? { shiftDay(day, 1) }
    nonisolated static func previousDay(_ day: String) -> String? { shiftDay(day, -1) }

    private nonisolated static func shiftDay(_ day: String, _ n: Int) -> String? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, let date = cal.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
              let shifted = cal.date(byAdding: .day, value: n, to: date) else { return nil }
        let c = cal.dateComponents([.year, .month, .day], from: shifted)
        return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    /// Re-runs every type's rollups once when `HealthSchema.rollupRuleVersion` grew (rule v2: per-night sleep,
    /// per-metric source policy). Returns true when a rebuild ran.
    @discardableResult
    func rebuildRollupsIfRuleChanged(types: [HealthMetricType], tz: String, calendar: Calendar, ownBundleID: String) throws -> Bool {
        let stored = try metaValue("rollup_rule_version").flatMap(Int.init) ?? 0
        guard stored < HealthSchema.rollupRuleVersion else { return false }
        for type in types {
            try rebuildAllRollups(type: type, tz: tz, calendar: calendar, ownBundleID: ownBundleID)
        }
        try setMeta("rollup_rule_version", "\(HealthSchema.rollupRuleVersion)")
        return true
    }

    /// Full rebuild for one type (import, "Rebuild summaries", rule-version change).
    @discardableResult
    func rebuildAllRollups(type: HealthMetricType, tz: String, calendar: Calendar, ownBundleID: String) throws -> Int {
        let days = try distinctDays(type: type.id)
        try connection.inTransaction {
            try connection.run("DELETE FROM health_daily_rollups WHERE type_id=?", [.text(type.id)])
        }
        return try rebuildRollups(type: type, days: days, tz: tz, calendar: calendar, ownBundleID: ownBundleID)
    }
}
