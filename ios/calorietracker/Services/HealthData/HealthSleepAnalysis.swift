import Foundation

/// Night derivation shared with Android (`docs/health-data.md` §1.3): rows are chained into episodes and grouped by
/// wake day (the `local_day` of the episode's last row, see `episodesByNight`); only the main episode of a wake day is
/// the night (other episodes are naps, `napS`); within it the source with the most asleep time wins, overlapping
/// intervals within that source are unioned, other sources are ignored.
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

    /// Rows closer than this belong to one sleep episode.
    static let episodeGapMs: Int64 = 3 * 3_600_000

    /// Sleep episodes grouped by wake day, oldest episode first. HealthKit stores each stage as its own sample, so a
    /// stage that ends before midnight carries the previous `local_day`; chaining rows into episodes (gaps under 3 h)
    /// and keying each episode by the `local_day` of its last row puts the whole night on its wake day, like Android's
    /// session rows. Same grouping as `sleep_nights` in `scripts/derived_reference.py` (algorithm 3).
    static func episodesByNight(_ rows: [HealthSampleRow]) -> [String: [[HealthSampleRow]]] {
        let live = rows.filter { !$0.isDeleted }.sorted { $0.startMs != $1.startMs ? $0.startMs < $1.startMs : $0.id < $1.id }
        var groups: [String: [[HealthSampleRow]]] = [:]
        var episode: [HealthSampleRow] = []
        var episodeEnd: Int64 = .min

        func flush() {
            guard let last = episode.max(by: { $0.endMs != $1.endMs ? $0.endMs < $1.endMs : $0.id < $1.id }) else { return }
            groups[last.localDay, default: []].append(episode)
            episode.removeAll()
        }

        for row in live {
            if !episode.isEmpty, row.startMs > episodeEnd + episodeGapMs { flush() }
            if episode.isEmpty { episodeEnd = row.endMs }
            episode.append(row)
            episodeEnd = max(episodeEnd, row.endMs)
        }
        flush()
        return groups
    }

    /// The winning source of one episode and its asleep time: most unioned asleep time, then most rows, then the
    /// smaller source id. Out-of-bed rows are ignored.
    static func bestSource(_ episode: [HealthSampleRow]) -> (source: String, rows: [HealthSampleRow], asleepMs: Int64)? {
        let candidates = episode.filter { !$0.isDeleted && $0.categoryValue != HealthSleepStage.outOfBed.rawValue }
        guard !candidates.isEmpty else { return nil }
        let bySource = Dictionary(grouping: candidates, by: \.sourceID)
        let scored = bySource.map { (source: $0.key, rows: $0.value, asleepMs: asleepMs($0.value)) }
        return scored.min { lhs, rhs in
            if lhs.asleepMs != rhs.asleepMs { return lhs.asleepMs > rhs.asleepMs }
            if lhs.rows.count != rhs.rows.count { return lhs.rows.count > rhs.rows.count }
            return lhs.source < rhs.source
        }
    }

    static func asleepMs(_ rows: [HealthSampleRow]) -> Int64 {
        unionMs(rows.filter { asleepCodes.contains($0.categoryValue ?? -1) }.map { ($0.startMs, $0.endMs) })
    }

    /// The main episode of each wake day (most best-source asleep time; ties: the later end) and the asleep time of
    /// the day's other episodes (naps). Naps never widen the night.
    static func mainEpisodes(_ rows: [HealthSampleRow]) -> [String: (rows: [HealthSampleRow], napMs: Int64)] {
        var out: [String: (rows: [HealthSampleRow], napMs: Int64)] = [:]
        for (day, episodes) in episodesByNight(rows) {
            var picks: [(rows: [HealthSampleRow], asleepMs: Int64, endMs: Int64)] = []
            for episode in episodes {
                if let best = bestSource(episode) {
                    picks.append((episode, best.asleepMs, episode.map(\.endMs).max() ?? 0))
                }
            }
            guard !picks.isEmpty else { continue }
            var mainIndex = 0
            for i in picks.indices.dropFirst() {
                let p = picks[i], m = picks[mainIndex]
                if p.asleepMs > m.asleepMs || (p.asleepMs == m.asleepMs && p.endMs > m.endMs) { mainIndex = i }
            }
            var napMs: Int64 = 0
            for i in picks.indices where i != mainIndex { napMs += picks[i].asleepMs }
            out[day] = (picks[mainIndex].rows, napMs)
        }
        return out
    }

    /// Rows of each wake day's main sleep episode (every source; `night` picks the winning one).
    static func rowsByNight(_ rows: [HealthSampleRow]) -> [String: [HealthSampleRow]] {
        mainEpisodes(rows).mapValues(\.rows)
    }

    /// Nights for every wake day present in `rows`, oldest first.
    static func nights(rows: [HealthSampleRow], calendar: Calendar) -> [HealthSleepNight] {
        let byDay = mainEpisodes(rows)
        return byDay.keys.sorted().compactMap { day in
            guard let main = byDay[day], var night = night(rows: main.rows, nightOf: day) else { return nil }
            night.napS = Double(main.napMs) / 1000
            return night
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
