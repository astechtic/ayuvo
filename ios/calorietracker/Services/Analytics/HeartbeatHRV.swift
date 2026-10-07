import Foundation
import HealthKit

/// Ayuvo RMSSD from Apple Watch beat-to-beat series (`ayuvo.hrv.rmssd`, docs/health-analytics.md §5.3).
///
/// Storage: the beats stay in Apple Health (the source of truth). Ayuvo stores one `analytics_results` row per day
/// (`metric_id = hrv_rr_day`) holding the engine's `hrv_rr_day` result: value = RMSSD, value2 = SDNN,
/// value3 = lnRMSSD, and as input hash the series ids plus last night's window, so a day is only re-read when its
/// series or night changed, or the algorithm version changed. Never mixed with Apple's SDNN.
nonisolated enum HeartbeatHRV {
    static let metricID = "hrv_rr_day"
    static let algorithmID = "ayuvo.hrv.rmssd"
    static let windowDays = 60

    static var algorithmVersion: Int {
        AnalyticsConfig.shared.raw["algorithms"].array.first { $0["id"].string == algorithmID }?["version"].int ?? 1
    }

    /// Beat times (seconds since the series start, `precededByGap`) → `[ibi_ms, preceded_by_gap]`.
    /// An interval that ends on a beat preceded by a gap spans missing beats, so it is dropped, and the next
    /// interval is flagged so no successive difference is taken across the gap.
    static func intervals(_ beats: [(time: Double, precededByGap: Bool)]) -> [(Double, Bool)] {
        var out: [(Double, Bool)] = []
        var gap = false
        for k in beats.indices.dropFirst() {
            if beats[k].precededByGap {
                gap = true
                continue
            }
            out.append(((beats[k].time - beats[k - 1].time) * 1000.0, gap))
            gap = false
        }
        return out
    }

    /// The day a series belongs to: the wake day of the night that contains its start, else its local start day.
    static func day(startMs: Int64, nights: [String: (startMs: Int64, endMs: Int64)], timeZone: String) -> String {
        for (wake, n) in nights where n.startMs <= startMs && startMs <= n.endMs { return wake }
        return DerivedDay.localDayOf(startMs, DerivedDay.timeZone(timeZone))
    }

    /// Input hash of one day: the series ids and start times, last night's window and the algorithm version.
    static func dayHash(seriesIDs: [(id: String, startMs: Int64)], night: (startMs: Int64, endMs: Int64)?) -> String {
        let value: AJ = .obj([
            "series": .arr(seriesIDs.sorted { $0.id < $1.id }.map { .arr([.str($0.id), .num(Double($0.startMs))]) }),
            "night": night.map { .arr([.num(Double($0.startMs)), .num(Double($0.endMs))]) } ?? .null,
            "version": .num(Double(algorithmVersion)),
        ])
        return AnalyticsCore.inputHash(value)["hash"].string ?? ""
    }

    /// One stored row from the engine's `hrv_rr_day` result.
    static func row(day: String, result: AJ, seriesCount: Int, hash: String, nowMs: Int64,
                    night: (startMs: Int64, endMs: Int64)?) -> AnalyticsResultRow {
        let provenance: AJ = .obj([
            "algorithm": .str("\(algorithmID)@\(algorithmVersion)"), "config_version": .num(Double(AnalyticsConfig.shared.configVersion)),
            "inputs": .arr([.str("heartbeat_series")]), "sources": .arr([.str("HealthKit")]),
            "sample_count": .num(Double(seriesCount)), "window": .str(day),
            "night": night.map { .arr([.num(Double($0.startMs)), .num(Double($0.endMs))]) } ?? .null,
            "context": result["context"],
        ])
        return AnalyticsResultRow(
            metricID: metricID, periodStart: day, periodEnd: day, algorithmID: algorithmID, algorithmVersion: algorithmVersion,
            configVersion: AnalyticsConfig.shared.configVersion, status: result["status"].string ?? "NO_DATA",
            classification: "SCIENTIFIC_DERIVED", value: result["rmssd"].double, value2: result["sdnn"].double,
            value3: result["ln_rmssd"].double, unit: "ms", confidence: nil, coverage: nil, inputCount: seriesCount,
            baselineWindowDays: nil, resultJSON: result.jsonString, provenanceJSON: provenance.jsonString, inputHash: hash,
            computedMs: nowMs
        )
    }
}

/// Reads heartbeat series from HealthKit and stores the per-day `hrv_rr_day` results. Missing permission or no
/// Apple Watch simply yields no rows.
enum HeartbeatHRVSync {
    /// Returns true when any day's result was written.
    static func run(database: HealthDatabase, timeZone: String, calendar: Calendar, now: Date = Date()) async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        let store = HealthKitManager.sharedHealthStore
        let today = InsightsDay.key(for: now, calendar: calendar)
        let from = InsightsDay.add(today, -HeartbeatHRV.windowDays)
        guard let start = InsightsDay.date(from, calendar: calendar) else { return false }
        let samples = await series(store: store, from: start, to: now)
        guard !samples.isEmpty else { return false }

        let sleepRows = (try? await database.rowsForDays(type: "sleep", fromDay: InsightsDay.add(from, -1), toDay: today)) ?? []
        var nights: [String: (startMs: Int64, endMs: Int64)] = [:]
        for night in HealthSleepAnalysis.nights(rows: sleepRows, calendar: calendar) { nights[night.nightOf] = (night.startMs, night.endMs) }

        var byDay: [String: [HKHeartbeatSeriesSample]] = [:]
        for s in samples {
            let ms = Int64((s.startDate.timeIntervalSince1970 * 1000).rounded())
            byDay[HeartbeatHRV.day(startMs: ms, nights: nights, timeZone: timeZone), default: []].append(s)
        }
        let stored = (try? await database.analyticsInputHashes(metric: HeartbeatHRV.metricID, algorithmVersion: HeartbeatHRV.algorithmVersion,
                                                               fromDay: from, toDay: today)) ?? [:]
        var rows: [AnalyticsResultRow] = []
        let nowMs = Int64((now.timeIntervalSince1970 * 1000).rounded())
        for (day, list) in byDay where day >= from && day <= today {
            let ids = list.map { (id: $0.uuid.uuidString.lowercased(), startMs: Int64(($0.startDate.timeIntervalSince1970 * 1000).rounded())) }
            let hash = HeartbeatHRV.dayHash(seriesIDs: ids, night: nights[day])
            if stored[day] == hash { continue }
            var beatSeries: [AnalyticsHRV.BeatSeries] = []
            for (sample, id) in zip(list, ids) {
                let beats = await beats(of: sample, store: store)
                beatSeries.append(AnalyticsHRV.BeatSeries(startMs: id.startMs, ibis: HeartbeatHRV.intervals(beats)))
            }
            let result = AnalyticsHRV.hrvRRDay(series: beatSeries, night: nights[day], AnalyticsConfig.shared)
            rows.append(HeartbeatHRV.row(day: day, result: result, seriesCount: list.count, hash: hash, nowMs: nowMs, night: nights[day]))
        }
        guard !rows.isEmpty else { return false }
        try? await database.upsertAnalyticsResults(rows)
        return true
    }

    private static func series(store: HKHealthStore, from: Date, to: Date) async -> [HKHeartbeatSeriesSample] {
        await withCheckedContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: from, end: to, options: .strictStartDate)
            let sort = [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]
            let query = HKSampleQuery(sampleType: HKSeriesType.heartbeat(), predicate: predicate, limit: HKObjectQueryNoLimit,
                                      sortDescriptors: sort) { _, samples, _ in
                continuation.resume(returning: (samples as? [HKHeartbeatSeriesSample]) ?? [])
            }
            store.execute(query)
        }
    }

    /// Thread-safe accumulator for the per-beat callbacks.
    private final class BeatBox: @unchecked Sendable {
        private let lock = NSLock()
        private var beats: [(time: Double, precededByGap: Bool)] = []
        private var finished = false

        func add(_ t: Double, _ gap: Bool) { lock.withLock { beats.append((t, gap)) } }

        /// The beats on the first call, nil afterwards (the continuation resumes once).
        func finish() -> [(time: Double, precededByGap: Bool)]? {
            lock.withLock {
                if finished { return nil }
                finished = true
                return beats
            }
        }
    }

    private static func beats(of sample: HKHeartbeatSeriesSample, store: HKHealthStore) async -> [(time: Double, precededByGap: Bool)] {
        await withCheckedContinuation { continuation in
            let box = BeatBox()
            let query = HKHeartbeatSeriesQuery(heartbeatSeries: sample) { _, time, gap, done, error in
                if error == nil { box.add(time, gap) }
                if done || error != nil, let beats = box.finish() {
                    continuation.resume(returning: error == nil ? beats : [])
                }
            }
            store.execute(query)
        }
    }
}
