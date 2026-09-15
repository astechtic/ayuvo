import Foundation

/// Night derivation shared with Android (`docs/health-data.md` §1.3): rows are grouped by
/// wake day (`local_day`, end-attributed), the source with the most asleep time wins,
/// overlapping intervals within that source are unioned, other sources are ignored.
nonisolated enum HealthSleepAnalysis {
    typealias Interval = (start: Int64, end: Int64)

    static let asleepCodes: Set<Int> = [
        HealthSleepStage.asleepUnspecified.rawValue, HealthSleepStage.light.rawValue,
        HealthSleepStage.deep.rawValue, HealthSleepStage.rem.rawValue,
    ]

    /// Total length of the union of `intervals`, in milliseconds.
    static func unionMs(_ intervals: [Interval]) -> Int64 {
        let sorted = intervals.filter { $0.end > $0.start }.sorted { $0.start < $1.start }
        var total: Int64 = 0
        var current: Interval?
        for interval in sorted {
            if let open = current {
                if interval.start <= open.end {
                    current = (open.start, max(open.end, interval.end))
                } else {
                    total += open.end - open.start
                    current = interval
                }
            } else {
                current = interval
            }
        }
        if let open = current {
            total += open.end - open.start
        }
        return total
    }

    /// Nights for every wake day present in `rows`, oldest first.
    static func nights(rows: [HealthSampleRow], calendar: Calendar) -> [HealthSleepNight] {
        let byDay = Dictionary(grouping: rows.filter { !$0.isDeleted }, by: \.localDay)
        return byDay.keys.sorted().compactMap { day in
            night(rows: byDay[day] ?? [], nightOf: day)
        }
    }

    /// One night from rows that all share a wake day.
    static func night(rows: [HealthSampleRow], nightOf: String) -> HealthSleepNight? {
        let candidates = rows.filter { !$0.isDeleted && $0.categoryValue != HealthSleepStage.outOfBed.rawValue }
        guard !candidates.isEmpty else { return nil }
        let bySource = Dictionary(grouping: candidates, by: \.sourceID)

        func asleepMs(_ sourceRows: [HealthSampleRow]) -> Int64 {
            unionMs(sourceRows.filter { asleepCodes.contains($0.categoryValue ?? -1) }.map { ($0.startMs, $0.endMs) })
        }

        // Longest asleep time wins; ties fall back to the most rows, then a stable name order.
        guard let (source, chosen) = bySource.max(by: { lhs, rhs in
            let l = asleepMs(lhs.value), r = asleepMs(rhs.value)
            if l != r { return l < r }
            if lhs.value.count != rhs.value.count { return lhs.value.count < rhs.value.count }
            return lhs.key > rhs.key
        }) else { return nil }

        func stageMs(_ code: HealthSleepStage) -> Int64 {
            unionMs(chosen.filter { $0.categoryValue == code.rawValue }.map { ($0.startMs, $0.endMs) })
        }

        let asleep = asleepMs(chosen)
        let awake = stageMs(.awake)
        let inBedRows = chosen.filter { $0.categoryValue == HealthSleepStage.inBed.rawValue }
        let inBed: Int64
        if inBedRows.isEmpty {
            inBed = unionMs(chosen.map { ($0.startMs, $0.endMs) })
        } else {
            inBed = unionMs(inBedRows.map { ($0.startMs, $0.endMs) })
        }
        let start = chosen.map(\.startMs).min() ?? 0
        let end = chosen.map(\.endMs).max() ?? start

        return HealthSleepNight(
            nightOf: nightOf,
            startMs: start,
            endMs: end,
            inBedS: Double(inBed) / 1000,
            asleepS: Double(asleep) / 1000,
            lightS: Double(stageMs(.light)) / 1000,
            deepS: Double(stageMs(.deep)) / 1000,
            remS: Double(stageMs(.rem)) / 1000,
            awakeS: Double(awake) / 1000,
            source: source
        )
    }
}
