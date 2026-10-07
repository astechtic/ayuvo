import Foundation

// Sleep: night grouping across sources, per-night architecture and multi-night regularity. Port of the "Sleep"
// section of `scripts/derived_reference.py`.

nonisolated enum SleepDerivation {
    // MARK: Night grouping

    /// `[start_ms, end_ms, code, source_id]`: every sleep row, any source, deleted rows removed.
    struct SourceRow: Sendable {
        var startMs: Int64
        var endMs: Int64
        var code: Int
        var source: String
    }

    struct Night: Sendable {
        var source: String
        var rows: [DerivedSleepRow]
        /// First to last asleep instant; nil when the source has no asleep rows.
        var window: (startMs: Int64, endMs: Int64)?
        /// Best-source asleep minutes of the wake day's other episodes (naps), and how many there were.
        var napMin: Double = 0
        var naps: Int = 0

        var jsonObject: [String: Any] {
            ["source": source, "rows": rows.map(\.jsonObject),
             "window": window.map { ["start_ms": $0.startMs, "end_ms": $0.endMs] as [String: Any] } ?? NSNull(),
             "nap_min": napMin, "naps": naps]
        }
    }

    struct NightsResult: DerivedOutput {
        var nights: [String: Night]
        var jsonObject: [String: Any] { nights.mapValues { $0.jsonObject } }
    }

    private static func asleepUnion(_ rows: [DerivedSleepRow]) -> [(Int64, Int64)] {
        DerivedMath.union(rows.filter(\.isAsleep).map { ($0.startMs, $0.endMs) })
    }

    private static func asleepUnion(_ rows: [SourceRow]) -> [(Int64, Int64)] {
        DerivedMath.union(rows.filter { DerivedSleepRow.asleepCodes.contains($0.code) }.map { ($0.startMs, $0.endMs) })
    }

    /// Rows sorted by (start, input order) chain into episodes while each next row starts at most episode_gap_hours
    /// after the episode's latest end; an episode belongs to the local day of its latest end (the wake day). Per wake
    /// day the source with the most unioned asleep time wins (ties: more rows, then the smaller source id);
    /// out-of-bed rows are ignored. Algorithm 3: only the wake day's main episode (most best-source asleep time; ties:
    /// the later end) is the night; other episodes are naps (`napMin`, `naps`) and never widen the window.
    static func sleepNights(timeZone: String, rows input: [SourceRow], config: DerivedConfig) -> NightsResult {
        let tz = DerivedDay.timeZone(timeZone)
        let gap = Int64(config.thresholds.episodeGapHours * 3_600_000)
        let rows = input.filter { $0.endMs >= $0.startMs }
        // Stable: equal starts keep input order.
        let order = rows.indices.sorted { rows[$0].startMs != rows[$1].startMs ? rows[$0].startMs < rows[$1].startMs : $0 < $1 }
        var groups: [String: [[SourceRow]]] = [:]
        var episode: [SourceRow] = []
        var end: Int64 = 0

        func flush() {
            guard !episode.isEmpty else { return }
            // `max(episode, key=end)`: the first row with the latest end.
            var last = episode[0]
            for r in episode.dropFirst() where r.endMs > last.endMs { last = r }
            groups[DerivedDay.localDayOf(last.endMs, tz), default: []].append(episode)
        }

        for i in order {
            let r = rows[i]
            if !episode.isEmpty && r.startMs > end + gap {
                flush()
                episode = []
            }
            if episode.isEmpty { end = r.endMs }
            episode.append(r)
            end = max(end, r.endMs)
        }
        flush()

        var out: [String: Night] = [:]
        for day in groups.keys.sorted() {
            // One pick per episode: (best source, its rows, its asleep ms, episode end).
            var picks: [(src: String, rows: [SourceRow], asleep: Int64, end: Int64)] = []
            for ep in groups[day]! {
                if let b = bestSource(ep) {
                    picks.append((b.src, b.rows, b.asleep, ep.map(\.endMs).max()!))
                }
            }
            if picks.isEmpty { continue }
            // `sorted(picks, key=(-asleep, -end))[0]`, stable.
            var mainIndex = 0
            for i in picks.indices.dropFirst() {
                let p = picks[i], m = picks[mainIndex]
                if p.asleep > m.asleep || (p.asleep == m.asleep && p.end > m.end) { mainIndex = i }
            }
            var napMs: Int64 = 0
            var naps = 0
            for i in picks.indices where i != mainIndex && picks[i].asleep > 0 {
                napMs += picks[i].asleep
                naps += 1
            }
            let main = picks[mainIndex]
            let chosen = main.rows.enumerated().sorted { a, b in
                let x = a.element, y = b.element
                if x.startMs != y.startMs { return x.startMs < y.startMs }
                if x.endMs != y.endMs { return x.endMs < y.endMs }
                if x.code != y.code { return x.code < y.code }
                return a.offset < b.offset
            }.map { DerivedSleepRow(startMs: $0.element.startMs, endMs: $0.element.endMs, code: $0.element.code) }
            let asleep = asleepUnion(chosen)
            out[day] = Night(source: main.src, rows: chosen,
                             window: asleep.isEmpty ? nil : (asleep[0].0, asleep[asleep.count - 1].1),
                             napMin: DerivedMath.roundTo(Double(napMs) / 60000.0, 1), naps: naps)
        }
        return NightsResult(nights: out)
    }

    /// The winning source of one episode: most unioned asleep time, then more rows, then the smaller source id.
    private static func bestSource(_ episode: [SourceRow]) -> (src: String, rows: [SourceRow], asleep: Int64)? {
        var by: [String: [SourceRow]] = [:]
        for r in episode where r.code != 6 { by[r.source, default: []].append(r) }
        if by.isEmpty { return nil }
        let ranked = by.keys.map { s in (s, DerivedMath.totalMs(asleepUnion(by[s]!)), by[s]!.count) }
            .sorted { a, b in
                if a.1 != b.1 { return a.1 > b.1 }
                if a.2 != b.2 { return a.2 > b.2 }
                return DerivedMath.pyLess(a.0, b.0)
            }
        return (ranked[0].0, by[ranked[0].0]!, ranked[0].1)
    }

    // MARK: One night

    struct NightResult: DerivedOutput {
        var asleepMin: Double?
        var inBedMin: Double?
        var efficiency: Double?
        var onsetLatencyMin: Double?
        var deepPct: Double?
        var remPct: Double?
        var lightPct: Double?
        var wakeups: Int?
        var wasoMin: Double?
        var bedtimeClock: Int?
        var wakeClock: Int?
        var midpointClock: Int?
        var deepLatencyMin: Double?
        var remLatencyMin: Double?
        var staged = false

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["asleep_min": j(asleepMin), "in_bed_min": j(inBedMin), "efficiency": j(efficiency),
                    "onset_latency_min": j(onsetLatencyMin), "deep_pct": j(deepPct), "rem_pct": j(remPct),
                    "light_pct": j(lightPct), "wakeups": j(wakeups), "waso_min": j(wasoMin),
                    "bedtime_clock": j(bedtimeClock), "wake_clock": j(wakeClock), "midpoint_clock": j(midpointClock),
                    "deep_latency_min": j(deepLatencyMin), "rem_latency_min": j(remLatencyMin), "staged": staged]
        }
    }

    /// `rows`: the chosen source's rows for the night whose wake day is `wakeDay`.
    static func sleepNight(timeZone: String, wakeDay wd: String, rows input: [DerivedSleepRow],
                           config: DerivedConfig) -> NightResult {
        let th = config.thresholds
        let tz = DerivedDay.timeZone(timeZone)
        let rows = input.filter { $0.code != 6 && $0.endMs > $0.startMs }
        let asleep = asleepUnion(rows)
        var out = NightResult()
        if asleep.isEmpty { return out }
        let asleepMs = DerivedMath.totalMs(asleep)
        let bedRows = rows.filter { $0.code == 0 }.map { ($0.startMs, $0.endMs) }
        let firstRow = rows.map(\.startMs).min()!
        var inBed = bedRows.isEmpty
            ? DerivedMath.totalMs(DerivedMath.union(rows.map { ($0.startMs, $0.endMs) }))
            : DerivedMath.totalMs(DerivedMath.union(bedRows))
        if inBed < asleepMs { inBed = rows.map(\.endMs).max()! - firstRow }
        let onset = asleep[0].0, final = asleep[asleep.count - 1].1
        out.asleepMin = DerivedMath.roundTo(Double(asleepMs) / 60000.0, 1)
        out.inBedMin = DerivedMath.roundTo(Double(inBed) / 60000.0, 1)
        out.efficiency = DerivedMath.roundTo(min(100.0, Double(asleepMs) * 100.0 / Double(inBed)), 1)
        if !bedRows.isEmpty {
            let bedStart = bedRows.map(\.0).min()!
            out.onsetLatencyMin = DerivedMath.roundTo(Double(max(0, onset - bedStart)) / 60000.0, 1)
        }
        let staged = rows.filter { [3, 4, 5].contains($0.code) }
        if !staged.isEmpty {
            out.staged = true
            func stage(_ code: Int) -> Int64 {
                DerivedMath.totalMs(DerivedMath.union(rows.filter { $0.code == code }.map { ($0.startMs, $0.endMs) }))
            }
            let deep = stage(4), rem = stage(5), light = stage(3)
            out.deepPct = DerivedMath.roundTo(Double(deep) * 100.0 / Double(asleepMs), 1)
            out.remPct = DerivedMath.roundTo(Double(rem) * 100.0 / Double(asleepMs), 1)
            out.lightPct = DerivedMath.roundTo(Double(light) * 100.0 / Double(asleepMs), 1)
            // `for r in sorted(staged)`: the first start per stage is its earliest start.
            var firsts: [Int: Int64] = [:]
            for r in staged where firsts[r.code] == nil || r.startMs < firsts[r.code]! { firsts[r.code] = r.startMs }
            if let d = firsts[4] { out.deepLatencyMin = DerivedMath.roundTo(Double(d - onset) / 60000.0, 1) }
            if let r = firsts[5] { out.remLatencyMin = DerivedMath.roundTo(Double(r - onset) / 60000.0, 1) }
        }
        var wakeups = 0
        var waso: Int64 = 0
        for i in 0..<(asleep.count - 1) {
            let gap = asleep[i + 1].0 - asleep[i].1
            waso += gap
            if Double(gap) >= th.wakeupMinGapMin * Double(DerivedMath.minute) { wakeups += 1 }
        }
        out.wakeups = wakeups
        out.wasoMin = DerivedMath.roundTo(Double(waso) / 60000.0, 1)
        out.bedtimeClock = DerivedDay.clockMin(firstRow, wakeDay: wd, tz)
        out.wakeClock = DerivedDay.clockMin(final, wakeDay: wd, tz)
        out.midpointClock = DerivedDay.clockMin(onset + DerivedMath.floorDiv(final - onset, 2), wakeDay: wd, tz)
        return out
    }

    // MARK: Regularity

    struct RegularityResult: DerivedOutput {
        var nights = 0
        var bedtimeSd: Double?
        var wakeSd: Double?
        var sri: Double?
        var sriPairs = 0
        var socialJetlagMin: Double?
        var msfscClock: Double?
        var sleepDebtMin: Double?

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["nights": nights, "bedtime_sd": j(bedtimeSd), "wake_sd": j(wakeSd), "sri": j(sri),
                    "sri_pairs": sriPairs, "social_jetlag_min": j(socialJetlagMin), "msfsc_clock": j(msfscClock),
                    "sleep_debt_min": j(sleepDebtMin)]
        }
    }

    private struct NightSummary {
        var asleep: [(Int64, Int64)]
        var asleepMin: Double
        var bed: Int
        var wake: Int
        var mid: Int
    }

    /// `nights`: {wake_day: rows} for the window; `needMin` nil (or 0) uses the configured default.
    static func sleepRegularity(timeZone: String, day: String, needMin: Double?, nights input: [String: [DerivedSleepRow]],
                                config: DerivedConfig) -> RegularityResult {
        let th = config.thresholds
        let tz = DerivedDay.timeZone(timeZone)
        let need = (needMin == nil || needMin == 0) ? th.sleepNeedMin : needMin!
        let win = stride(from: th.regularityWindowDays - 1, through: 0, by: -1).map { DerivedDay.addDays(day, -$0) }
        var nights: [String: NightSummary] = [:]
        for d in win {
            guard let raw = input[d] else { continue }
            let rows = raw.filter { $0.code != 6 && $0.endMs > $0.startMs }
            let asleep = asleepUnion(rows)
            if asleep.isEmpty { continue }
            let first = rows.map(\.startMs).min()!
            let onset = asleep[0].0, final = asleep[asleep.count - 1].1
            nights[d] = NightSummary(asleep: asleep, asleepMin: Double(DerivedMath.totalMs(asleep)) / 60000.0,
                                     bed: DerivedDay.clockMin(first, wakeDay: d, tz),
                                     wake: DerivedDay.clockMin(final, wakeDay: d, tz),
                                     mid: DerivedDay.clockMin(onset + DerivedMath.floorDiv(final - onset, 2), wakeDay: d, tz))
        }
        var out = RegularityResult()
        out.nights = nights.count
        let days = win.filter { nights[$0] != nil }
        if days.count >= th.regularityMinNights {
            let beds = days.map { Double(nights[$0]!.bed) }
            let wakes = days.map { Double(nights[$0]!.wake) }
            out.bedtimeSd = DerivedMath.roundTo(DerivedMath.sampleSD(beds, DerivedMath.mean(beds)), 1)
            out.wakeSd = DerivedMath.roundTo(DerivedMath.sampleSD(wakes, DerivedMath.mean(wakes)), 1)
        }
        // SRI over consecutive noon-to-noon windows
        var agree = 0, total = 0, pairs = 0
        for i in 0..<max(0, win.count - 1) {
            let a = win[i], b = win[i + 1]
            guard let na = nights[a], let nb = nights[b] else { continue }
            pairs += 1
            let sa = DerivedDay.dayStartMs(DerivedDay.addDays(a, -1), tz) + 12 * 3_600_000
            let sb = DerivedDay.dayStartMs(DerivedDay.addDays(b, -1), tz) + 12 * 3_600_000
            for m in 0..<1440 {
                let ta = sa + Int64(m) * DerivedMath.minute, tb = sb + Int64(m) * DerivedMath.minute
                let xa = na.asleep.contains { $0.0 <= ta && ta < $0.1 }
                let xb = nb.asleep.contains { $0.0 <= tb && tb < $0.1 }
                if xa == xb { agree += 1 }
                total += 1
            }
        }
        out.sriPairs = pairs
        if pairs >= th.sriMinPairs {
            out.sri = DerivedMath.roundTo(200.0 * Double(agree) / Double(total) - 100.0, 1)
        }
        let free = days.filter { th.freeWakeWeekdays.contains(DerivedDay.isoWeekday($0)) }
        let work = days.filter { !th.freeWakeWeekdays.contains(DerivedDay.isoWeekday($0)) }
        if free.count >= th.socialMinFree && work.count >= th.socialMinWork {
            let mf = DerivedMath.mean(free.map { Double(nights[$0]!.mid) })
            let mw = DerivedMath.mean(work.map { Double(nights[$0]!.mid) })
            out.socialJetlagMin = DerivedMath.roundTo(abs(mf - mw), 1)
            let sdf = DerivedMath.mean(free.map { nights[$0]!.asleepMin })
            let sdw = DerivedMath.mean(work.map { nights[$0]!.asleepMin })
            var msf = mf
            if sdf > sdw {
                let sdweek = (5.0 * sdw + 2.0 * sdf) / 7.0
                msf = mf - (sdf - sdweek) / 2.0
            }
            out.msfscClock = DerivedMath.roundTo(msf, 1)
        }
        if !days.isEmpty {
            var debt = 0.0
            for d in days { debt += max(0.0, need - nights[d]!.asleepMin) }
            out.sleepDebtMin = DerivedMath.roundTo(debt, 1)
        }
        return out
    }
}
