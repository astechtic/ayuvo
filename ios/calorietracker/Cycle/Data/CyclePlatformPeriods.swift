import Foundation

/// Periods from Apple Health / Health Connect data that the health sync already stored in `health_samples`
/// (docs/cycle-tracking.md §4). They enter the engine as `source: healthkit | health_connect`; an app period that
/// overlaps one wins there. Samples Ayuvo wrote itself are skipped so a written period never counts twice.
nonisolated enum CyclePlatformPeriods {
    static let flowType = "menstrual_flow"
    static let periodType = "menstruation_period"
    /// `HKMetadataKeyMenstrualCycleStart` as stored in `extra_json`.
    static let cycleStartKey = "HKMenstrualCycleStart"
    /// Metadata key Ayuvo adds to every sample it writes.
    static let ownMetadataKey = "ayuvo_cycle"
    /// HealthKit `menstrualFlow` value "none".
    static let flowNone = 5

    /// Whether Ayuvo wrote this sample (own bundle id, `ayuvo:` client record id, or the `ayuvo_cycle` metadata key).
    static func isOwn(_ row: HealthSampleRow, ownSourceID: String) -> Bool {
        if !ownSourceID.isEmpty && row.sourceID == ownSourceID { return true }
        if let crid = row.clientRecordID, crid.hasPrefix("ayuvo:") { return true }
        if let extra = row.extraJSON, extra.contains("\"\(ownMetadataKey)\"") { return true }
        return false
    }

    /// Flow days become runs of consecutive days; a gap of two days or more, or a sample flagged as a cycle start,
    /// begins a new period. `menstruation_period` rows become one period each (start day to the day of `end − 1 ms`).
    static func periods(flowRows: [HealthSampleRow], periodRows: [HealthSampleRow], ownSourceID: String) -> [CyclePeriodInput] {
        var out: [CyclePeriodInput] = []
        var days: [Int: (id: String, cycleStart: Bool)] = [:]
        for row in flowRows where !row.isDeleted && !isOwn(row, ownSourceID: ownSourceID) {
            if row.categoryValue == flowNone { continue }
            guard let day = CycleDay.ordinal(row.localDay) else { continue }
            let start = (row.extra[cycleStartKey] as? NSNumber)?.boolValue ?? false
            if let existing = days[day] {
                days[day] = (min(existing.id, row.id), existing.cycleStart || start)
            } else {
                days[day] = (row.id, start)
            }
        }
        var runStart: Int?, runEnd = 0, runID = ""
        for day in days.keys.sorted() {
            let info = days[day]!
            if runStart != nil, day == runEnd + 1, !info.cycleStart {
                runEnd = day
                continue
            }
            if let s = runStart {
                out.append(CyclePeriodInput(id: "healthkit:" + runID, start: CycleDay.string(s), end: CycleDay.string(runEnd), source: "healthkit"))
            }
            runStart = day
            runEnd = day
            runID = info.id
        }
        if let s = runStart {
            out.append(CyclePeriodInput(id: "healthkit:" + runID, start: CycleDay.string(s), end: CycleDay.string(runEnd), source: "healthkit"))
        }
        for row in periodRows where !row.isDeleted && !isOwn(row, ownSourceID: ownSourceID) {
            let startDay = localOrdinal(ms: row.startMs, offsetS: row.startOffsetS)
            var endDay = localOrdinal(ms: max(row.startMs, row.endMs - 1), offsetS: row.endOffsetS ?? row.startOffsetS)
            if endDay < startDay { endDay = startDay }
            out.append(CyclePeriodInput(id: "health_connect:" + row.id, start: CycleDay.string(startDay), end: CycleDay.string(endDay),
                                        source: "health_connect"))
        }
        return out
    }

    /// The local day of an instant at a zone offset (the device zone when the platform gave none).
    static func localOrdinal(ms: Int64, offsetS: Int?) -> Int {
        let offset = Int64(offsetS ?? TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: Double(ms) / 1000)))
        let local = ms + offset * 1000
        let dayMs: Int64 = 86_400_000
        return Int(local >= 0 ? local / dayMs : (local - dayMs + 1) / dayMs)
    }

    /// Every platform period in the health mirror.
    static func load(from db: HealthDatabase, ownSourceID: String = Bundle.main.bundleIdentifier ?? "") async throws -> [CyclePeriodInput] {
        let flow = try await db.rows(type: flowType, startMs: Int64.min / 2, endMs: Int64.max / 2)
        let spans = try await db.rows(type: periodType, startMs: Int64.min / 2, endMs: Int64.max / 2)
        return periods(flowRows: flow, periodRows: spans, ownSourceID: ownSourceID)
    }
}
