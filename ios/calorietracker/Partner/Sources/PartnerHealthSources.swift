import Foundation

// Health-mirror sources (docs/partner-sync.md §7.1–§7.3). They read `health.sqlite` through their own READ-ONLY
// connection (`PartnerSQLiteReader`), page every query, read named columns only and run the shared mappers, which
// drop any type_id outside the record_types.json allow-lists (cycle, symptoms, mental wellbeing, hearing, mobility…).

nonisolated enum PartnerHealthTypes {
    static var catalog: PartnerCatalog { .shared }

    /// Every allow-listed health type id, sorted (SQL `IN` lists).
    static var allowListed: [String] { catalog.healthTypeCategory.keys.sorted() }
    static var intraday: [String] { catalog.intradayTypes.sorted() }
    static var hourly: [String] { catalog.hourlyTypes.sorted() }

    /// Registry unit of a type id (health_type_meta for imported/unknown ids, which are never allow-listed anyway).
    static func unit(_ typeID: String) -> String? { HealthMetricRegistry.byID[typeID]?.unit }

    static func placeholders(_ n: Int) -> String { Array(repeating: "?", count: n).joined(separator: ",") }

    /// `today - intraday_days` (the intraday window start).
    static func intradayDayFrom(now: Date, timeZone: TimeZone = .current) -> String {
        PartnerDay.adding(-catalog.intradayDays, to: PartnerDay.string(now, timeZone: timeZone), timeZone: timeZone)
    }

    /// Splits `<a>:<b>` ids at the LAST colon.
    static func splitLast(_ id: String) -> (String, String)? {
        guard let i = id.lastIndex(of: ":") else { return nil }
        return (String(id[..<i]), String(id[id.index(after: i)...]))
    }
}

// MARK: - metric_day (health_daily_rollups, all history)

nonisolated struct PartnerRollupSource: PartnerRecordSource {
    let type = "metric_day"
    let reader: PartnerSQLiteReader

    private static let columns = "type_id, day, sum, avg, min, max, count, last_value, last_at_ms, v2_avg, v2_min, v2_max, duration_s"

    private static func row(_ s: HealthDBStatement) -> RJ {
        let typeID = s.text(0) ?? ""
        return .obj([
            "type_id": .str(typeID), "day": s.rjText(1), "unit": RJ.string(PartnerHealthTypes.unit(typeID)),
            "sum": s.rjNumber(2), "avg": s.rjNumber(3), "min": s.rjNumber(4), "max": s.rjNumber(5), "count": s.rjInt(6),
            "last_value": s.rjNumber(7), "last_at_ms": s.rjInt(8), "v2_avg": s.rjNumber(9), "v2_min": s.rjNumber(10),
            "v2_max": s.rjNumber(11), "duration_s": s.rjNumber(12),
        ])
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        let ids = PartnerHealthTypes.allowListed
        let from = scope.dayFrom
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            var sql = "SELECT \(Self.columns) FROM health_daily_rollups WHERE type_id IN (\(PartnerHealthTypes.placeholders(ids.count)))"
            var values: [SQLValue] = ids.map(SQLValue.text)
            if let from { sql += " AND day >= ?"; values.append(.text(from)) }
            let (clause, keys) = PartnerKeysetPager.after(["type_id", "day"], after)
            sql += clause + " ORDER BY type_id, day LIMIT ?"
            return (sql, values + keys)
        }, keyColumns: [0, 1], map: { PartnerSourceRecord(mapped: PartnerRef.mapRollup(Self.row($0))) }, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        guard let (typeID, day) = PartnerHealthTypes.splitLast(id), PartnerHealthTypes.catalog.healthTypeCategory[typeID] != nil else { return nil }
        return try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM health_daily_rollups WHERE type_id=? AND day=?", [.text(typeID), .text(day)]) { s in
                out = PartnerSourceRecord(mapped: PartnerRef.mapRollup(Self.row(s)))
            }
            return out
        } ?? nil
    }
}

// MARK: - Generic keyset pager for single-key tables

/// Pages `SELECT … ORDER BY <key> LIMIT n` with `<key> > last`, mapping each row. Unshareable rows still advance
/// the cursor, and the loop ends on a short page.
nonisolated enum PartnerKeysetPager {
    /// `keyColumns` are the result columns forming the ORDER BY key; `query` receives the previous page's last key
    /// (nil first) and must add `AND (k1, k2…) > (?, ?…)` plus `ORDER BY k1, k2… LIMIT ?` (the limit value is appended).
    static func run(
        reader: PartnerSQLiteReader, type: String, pageSize: Int,
        query: @escaping @Sendable (_ after: [SQLValue]?) -> (sql: String, values: [SQLValue]),
        keyColumns: [Int32],
        map: @escaping @Sendable (HealthDBStatement) -> PartnerSourceRecord?,
        onPage: ([PartnerSourceRecord]) async throws -> Void
    ) async throws {
        var after: [SQLValue]?
        while true {
            let result: (records: [PartnerSourceRecord], last: [SQLValue]?, rows: Int)
            do {
                result = try await reader.read { c -> ([PartnerSourceRecord], [SQLValue]?, Int) in
                    let q = query(after)
                    var out: [PartnerSourceRecord] = []
                    var last: [SQLValue]?
                    var rows = 0
                    try c.query(q.sql, q.values + [.int(Int64(pageSize))]) { s in
                        rows += 1
                        last = keyColumns.map { i in
                            switch s.columnType(i) {
                            case 1: return .int(s.int64(i) ?? 0)
                            case 5: return .null
                            default: return .text(s.text(i) ?? "")
                            }
                        }
                        if let r = map(s) { out.append(r) }
                    }
                    return (out, last, rows)
                } ?? ([], nil, 0)
            } catch {
                throw PartnerSourceUnavailable(type: type, reason: String(describing: error))
            }
            if !result.records.isEmpty { try await onPage(result.records) }
            guard result.rows >= pageSize, let last = result.last else { return }
            after = last
        }
    }

    /// `AND (a, b) > (?, ?)` for the given columns.
    static func after(_ columns: [String], _ values: [SQLValue]?) -> (String, [SQLValue]) {
        guard let values else { return ("", []) }
        let list = columns.joined(separator: ", ")
        return (" AND (\(list)) > (\(PartnerHealthTypes.placeholders(columns.count)))", values)
    }
}

// MARK: - metric_hour (health_hourly_rollups, hourly_types, last 7 days)

nonisolated struct PartnerHourlySource: PartnerRecordSource {
    let type = "metric_hour"
    let reader: PartnerSQLiteReader
    var now: @Sendable () -> Date = { Date() }
    var timeZone: TimeZone = .current

    private static let columns = "type_id, day, hour, sum, avg, min, max, count"

    private func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let typeID = s.text(0) ?? "", day = s.text(1) ?? ""
        let hour = s.int(2) ?? 0
        guard let start = PartnerDay.hourStartMs(day: day, hour: hour, timeZone: timeZone) else { return nil }
        let row: RJ = .obj([
            "type_id": .str(typeID), "day": .str(day), "hour": .int(hour), "hour_start_ms": .int(Int(start)),
            "unit": RJ.string(PartnerHealthTypes.unit(typeID)), "sum": s.rjNumber(3), "avg": s.rjNumber(4),
            "min": s.rjNumber(5), "max": s.rjNumber(6), "count": s.rjInt(7),
        ])
        return PartnerSourceRecord(mapped: PartnerRef.mapHourly(row))
    }

    private func windowStart(_ scope: PartnerSourceScope) -> String {
        let floor = PartnerHealthTypes.intradayDayFrom(now: now(), timeZone: timeZone)
        if let from = scope.dayFrom, utf8Less(floor, from) { return from }
        return floor
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        let ids = PartnerHealthTypes.hourly
        let from = windowStart(scope)
        let selfCopy = self
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            var sql = "SELECT \(Self.columns) FROM health_hourly_rollups WHERE type_id IN (\(PartnerHealthTypes.placeholders(ids.count))) AND day >= ?"
            let values: [SQLValue] = ids.map(SQLValue.text) + [.text(from)]
            let (clause, keys) = PartnerKeysetPager.after(["type_id", "day", "hour"], after)
            sql += clause + " ORDER BY type_id, day, hour LIMIT ?"
            return (sql, values + keys)
        }, keyColumns: [0, 1, 2], map: { selfCopy.map($0) }, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        guard let (rest, hourText) = PartnerHealthTypes.splitLast(id), let hour = Int(hourText),
              let (typeID, day) = PartnerHealthTypes.splitLast(rest), !utf8Less(day, windowStart(.full)) else { return nil }
        let selfCopy = self
        return try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM health_hourly_rollups WHERE type_id=? AND day=? AND hour=?",
                        [.text(typeID), .text(day), .int(Int64(hour))]) { out = selfCopy.map($0) }
            return out
        } ?? nil
    }
}

// MARK: - sample (health_samples, intraday_types, deleted = 0, last 7 days)

nonisolated struct PartnerSampleSource: PartnerRecordSource {
    let type = "sample"
    let reader: PartnerSQLiteReader
    var now: @Sendable () -> Date = { Date() }
    var timeZone: TimeZone = .current

    /// Named columns only (never value_text, title, extra_json, device or client ids).
    private static let select = """
        SELECT s.id, s.type_id, s.start_ms, s.end_ms, s.local_day, s.value, s.value2, s.value3, s.unit, s.category_value,
        s.deleted, s.updated_ms, src.name FROM health_samples s LEFT JOIN health_sources src ON src.id = s.source_id
        """

    private func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let row: RJ = .obj([
            "id": s.rjText(0), "type_id": s.rjText(1), "start_ms": s.rjInt(2), "end_ms": s.rjInt(3), "local_day": s.rjText(4),
            "value": s.rjNumber(5), "value2": s.rjNumber(6), "value3": s.rjNumber(7), "unit": s.rjText(8),
            "category_value": s.rjInt(9), "deleted": s.rjInt(10), "source_name": s.rjText(12),
        ])
        let nowMs = Int((now().timeIntervalSince1970 * 1000).rounded())
        return PartnerSourceRecord(mapped: PartnerRef.mapSample(row, nowMs: nowMs), updatedMs: s.int64(11))
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        let ids = PartnerHealthTypes.intraday
        var from = PartnerHealthTypes.intradayDayFrom(now: now(), timeZone: timeZone)
        if let f = scope.dayFrom, utf8Less(from, f) { from = f }
        let floor = from
        let selfCopy = self
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            var sql = Self.select + " WHERE s.type_id IN (\(PartnerHealthTypes.placeholders(ids.count))) AND s.deleted=0 AND s.local_day >= ?"
            let values: [SQLValue] = ids.map(SQLValue.text) + [.text(floor)]
            let (clause, keys) = PartnerKeysetPager.after(["s.id"], after)
            sql += clause + " ORDER BY s.id LIMIT ?"
            return (sql, values + keys)
        }, keyColumns: [0], map: { selfCopy.map($0) }, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        let selfCopy = self
        return try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query(Self.select + " WHERE s.id=?", [.text(id)]) { out = selfCopy.map($0) }
            return out
        } ?? nil
    }
}

// MARK: - derived_day (derived_daily_values)

nonisolated struct PartnerDerivedSource: PartnerRecordSource {
    let type = "derived_day"
    let reader: PartnerSQLiteReader
    /// Derived metric id → derived_config category. Hearing and mobility metrics are never shared (docs §7.1), and an
    /// id the catalog does not know is not shared either.
    var categoryOf: @Sendable (String) -> String? = { DerivedCatalog.shared.byID[$0]?.category }
    static let excludedCategories: Set<String> = ["hearing", "mobility", "cycle_tracking", "symptoms", "mental_wellbeing"]

    private func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let metric = s.text(0) ?? "", day = s.text(1) ?? ""
        guard let cat = categoryOf(metric), !Self.excludedCategories.contains(cat), !s.isNull(2) else { return nil }
        let data = PartnerRef.dropNone([
            "metric_id": .str(metric), "day": .str(day), "value": s.rjNumber(2), "value2": s.rjNumber(3), "value3": s.rjNumber(4),
            "quality": s.rjNumber(5), "algo_version": s.rjInt(6),
        ])
        return PartnerSourceRecord(type: type, recordID: "\(metric):\(day)", category: "vitals", day: day, data: data, updatedMs: s.int64(7))
    }

    private static let columns = "metric_id, day, value, value2, value3, quality, algo_version, computed_ms"

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        guard try await reader.hasTable("derived_daily_values") else { return }
        let selfCopy = self
        let from = scope.dayFrom
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            var sql = "SELECT \(Self.columns) FROM derived_daily_values WHERE 1=1"
            var values: [SQLValue] = []
            if let from { sql += " AND day >= ?"; values.append(.text(from)) }
            let (clause, keys) = PartnerKeysetPager.after(["metric_id", "day"], after)
            sql += clause + " ORDER BY metric_id, day LIMIT ?"
            return (sql, values + keys)
        }, keyColumns: [0, 1], map: { selfCopy.map($0) }, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        guard let (metric, day) = PartnerHealthTypes.splitLast(id), try await reader.hasTable("derived_daily_values") else { return nil }
        let selfCopy = self
        return try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM derived_daily_values WHERE metric_id=? AND day=?", [.text(metric), .text(day)]) {
                out = selfCopy.map($0)
            }
            return out
        } ?? nil
    }
}

// MARK: - analytics_day (single-day analytics_results of the allow-listed metrics)

nonisolated struct PartnerAnalyticsSource: PartnerRecordSource {
    let type = "analytics_day"
    let reader: PartnerSQLiteReader

    private static let columns = "metric_id, period_start, period_end, status, classification, value, value2, value3, unit, confidence, coverage, computed_ms"

    private static func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let row: RJ = .obj([
            "metric_id": s.rjText(0), "period_start": s.rjText(1), "period_end": s.rjText(2), "status": s.rjText(3),
            "classification": s.rjText(4), "value": s.rjNumber(5), "value2": s.rjNumber(6), "value3": s.rjNumber(7),
            "unit": s.rjText(8), "confidence": s.rjNumber(9), "coverage": s.rjNumber(10),
        ])
        return PartnerSourceRecord(mapped: PartnerRef.mapAnalytics(row), updatedMs: s.int64(11))
    }

    /// The newest algorithm version per (metric, day).
    private static let latestVersion =
        "algorithm_version = (SELECT MAX(b.algorithm_version) FROM analytics_results b WHERE b.metric_id = a.metric_id AND b.period_start = a.period_start)"

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        guard try await reader.hasTable("analytics_results") else { return }
        let metrics = PartnerCatalog.shared.analyticsMetrics.sorted()
        let from = scope.dayFrom
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            var sql = "SELECT \(Self.columns) FROM analytics_results a WHERE a.metric_id IN (\(PartnerHealthTypes.placeholders(metrics.count))) AND a.period_start = a.period_end AND \(Self.latestVersion)"
            var values: [SQLValue] = metrics.map(SQLValue.text)
            if let from { sql += " AND a.period_start >= ?"; values.append(.text(from)) }
            let (clause, keys) = PartnerKeysetPager.after(["a.metric_id", "a.period_start"], after)
            sql += clause + " ORDER BY a.metric_id, a.period_start LIMIT ?"
            return (sql, values + keys)
        }, keyColumns: [0, 1], map: Self.map, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        guard let (metric, day) = PartnerHealthTypes.splitLast(id), PartnerCatalog.shared.analyticsMetrics.contains(metric),
              try await reader.hasTable("analytics_results") else { return nil }
        return try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM analytics_results a WHERE a.metric_id=? AND a.period_start=? AND a.period_end=? AND \(Self.latestVersion)",
                        [.text(metric), .text(day), .text(day)]) { out = Self.map($0) }
            return out
        } ?? nil
    }
}

// MARK: - sleep_night (HealthSleepAnalysis: one row per wake day)

nonisolated struct PartnerSleepNightSource: PartnerRecordSource {
    let type = "sleep_night"
    let reader: PartnerSQLiteReader
    var timeZone: TimeZone = .current
    /// Days of sleep rows read per chunk on a full scan (bounded memory).
    var chunkDays = 31

    private func rows(fromDay: String, toDay: String) async throws -> [HealthSampleRow] {
        try await reader.read { c -> [HealthSampleRow] in
            var out: [HealthSampleRow] = []
            try c.query("SELECT \(HealthDatabase.sampleColumns) FROM health_samples WHERE type_id='sleep' AND deleted=0 AND local_day BETWEEN ? AND ? ORDER BY start_ms, id",
                        [.text(fromDay), .text(toDay)]) { out.append(HealthDatabase.decodeSample($0)) }
            return out
        } ?? []
    }

    private func average(_ typeIDs: [String], startMs: Int64, endMs: Int64) async throws -> Double? {
        try await reader.read { c -> Double? in
            for typeID in typeIDs {
                var avg: Double?
                try c.query("SELECT AVG(value) FROM health_samples WHERE type_id=? AND deleted=0 AND value IS NOT NULL AND start_ms>=? AND start_ms<?",
                            [.text(typeID), .int(startMs), .int(endMs)]) { avg = $0.double(0) }
                if let avg { return avg }
            }
            return nil
        } ?? nil
    }

    private static func minutes(_ seconds: Double) -> Int { Int((seconds / 60).rounded()) }
    private static func oneDecimal(_ v: Double?) -> RJ { RJ.number(v.map { ($0 * 10).rounded() / 10 }) }

    func render(_ night: HealthSleepNight) async throws -> PartnerSourceRecord {
        let hr = try await average(["heart_rate"], startMs: night.startMs, endMs: night.endMs)
        let hrv = try await average(["hrv_sdnn", "hrv_rmssd"], startMs: night.startMs, endMs: night.endMs)
        let resp = try await average(["respiratory_rate"], startMs: night.startMs, endMs: night.endMs)
        let hasStages = night.deepS + night.remS + night.lightS > 0
        let efficiency: RJ = night.inBedS > 0 && night.asleepS > 0 ? .int(Int((night.asleepS / night.inBedS * 100).rounded())) : .null
        let data = PartnerRef.dropNone([
            "day": .str(night.nightOf), "start_ms": .int(Int(night.startMs)), "end_ms": .int(Int(night.endMs)),
            "asleep_min": .int(Self.minutes(night.asleepS)),
            "in_bed_min": night.inBedS > 0 ? .int(Self.minutes(night.inBedS)) : .null,
            "deep_min": hasStages ? .int(Self.minutes(night.deepS)) : .null,
            "rem_min": hasStages ? .int(Self.minutes(night.remS)) : .null,
            "light_min": hasStages ? .int(Self.minutes(night.lightS)) : .null,
            "awake_min": night.awakeS > 0 || hasStages ? .int(Self.minutes(night.awakeS)) : .null,
            "avg_hr": Self.oneDecimal(hr), "avg_hrv": Self.oneDecimal(hrv), "avg_resp_rate": Self.oneDecimal(resp),
            "efficiency": efficiency,
        ])
        return PartnerSourceRecord(type: type, recordID: night.nightOf, category: "sleep", day: night.nightOf, data: data)
    }

    /// Nights whose wake day is in [fromDay, toDay]; rows from the day before are read so a night that starts
    /// before midnight is complete.
    private func nights(fromDay: String, toDay: String) async throws -> [HealthSleepNight] {
        let rows = try await rows(fromDay: PartnerDay.adding(-1, to: fromDay, timeZone: timeZone), toDay: toDay)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return HealthSleepAnalysis.nights(rows: rows, calendar: cal).filter { !utf8Less($0.nightOf, fromDay) && !utf8Less(toDay, $0.nightOf) }
    }

    private func dayRange() async throws -> (String, String)? {
        try await reader.read { c -> (String, String)? in
            var out: (String, String)?
            try c.query("SELECT MIN(local_day), MAX(local_day) FROM health_samples WHERE type_id='sleep' AND deleted=0") { s in
                if let a = s.text(0), let b = s.text(1) { out = (a, b) }
            }
            return out
        } ?? nil
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        do {
            guard let (minDay, maxDay) = try await dayRange() else { return }
            var start = minDay
            if let from = scope.dayFrom, utf8Less(start, from) { start = from }
            while !utf8Less(maxDay, start) {
                let end = PartnerDay.adding(chunkDays - 1, to: start, timeZone: timeZone)
                var page: [PartnerSourceRecord] = []
                for night in try await nights(fromDay: start, toDay: end) {
                    page.append(try await render(night))
                    if page.count >= pageSize { try await onPage(page); page.removeAll() }
                }
                if !page.isEmpty { try await onPage(page) }
                start = PartnerDay.adding(1, to: end, timeZone: timeZone)
            }
        } catch let e as PartnerSourceUnavailable {
            throw e
        } catch {
            throw PartnerSourceUnavailable(type: type, reason: String(describing: error))
        }
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        guard PyStr.isDay(id), let night = try await nights(fromDay: id, toDay: id).first else { return nil }
        return try await render(night)
    }
}
