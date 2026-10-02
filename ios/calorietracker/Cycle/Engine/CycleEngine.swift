import Foundation

// Swift port of `scripts/cycle_reference.py` (docs/cycle-tracking.md §3). The reference wins over prose; this file
// follows it function by function and `CycleVectorTests` requires identical output for every case in
// `shared/cycle/test-vectors`. Pure: no clock, no storage. Days are integer ordinals (days since 1970-01-01).

// MARK: - Days

nonisolated enum CycleDay {
    /// 'yyyy-MM-dd' → days since 1970-01-01 (Hinnant days_from_civil). Nil when the text is not a date.
    static func ordinal(_ text: String) -> Int? {
        let u = Array(text.utf8)
        guard u.count >= 10, u[4] == 0x2D, u[7] == 0x2D else { return nil }
        func num(_ a: Int, _ b: Int) -> Int? {
            var v = 0
            for i in a..<b {
                guard u[i] >= 0x30, u[i] <= 0x39 else { return nil }
                v = v * 10 + Int(u[i] - 0x30)
            }
            return v
        }
        guard var y = num(0, 4), let m = num(5, 7), let d = num(8, 10), (1...12).contains(m), (1...31).contains(d) else { return nil }
        if m <= 2 { y -= 1 }
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = m > 2 ? m - 3 : m + 9
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// Ordinal for text the caller already validated (vectors, stored rows); 0 for malformed text.
    static func o(_ text: String) -> Int { ordinal(text) ?? 0 }

    /// days since 1970-01-01 → 'yyyy-MM-dd' (Hinnant civil_from_days).
    static func string(_ n: Int) -> String {
        let z = n + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        var y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        if m <= 2 { y += 1 }
        return String(format: "%04ld-%02ld-%02ld", y, m, d)
    }

    static func opt(_ n: Int?) -> String? { n.map { string($0) } }

    /// Today's local day in `calendar` (the device zone by default).
    static func today(_ date: Date = Date(), calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return ordinal(String(format: "%04ld-%02ld-%02ld", c.year ?? 1970, c.month ?? 1, c.day ?? 1)) ?? 0
    }
}

// MARK: - Inputs

nonisolated struct CycleReminderInput: Sendable, Hashable {
    /// nil = default (on); the reference treats an explicit JSON null as off.
    var periodSoon: Bool?
    var daysBefore: Int?
    var periodEnd: Bool?
    var daily: Bool?

    init(periodSoon: Bool? = nil, daysBefore: Int? = nil, periodEnd: Bool? = nil, daily: Bool? = nil) {
        self.periodSoon = periodSoon
        self.daysBefore = daysBefore
        self.periodEnd = periodEnd
        self.daily = daily
    }
}

nonisolated struct CycleSettingsInput: Sendable, Hashable {
    var cycleLength: Int?
    var periodLength: Int?
    var lutealLength: Int?
    var reminders: CycleReminderInput

    init(cycleLength: Int? = nil, periodLength: Int? = nil, lutealLength: Int? = nil, reminders: CycleReminderInput = CycleReminderInput()) {
        self.cycleLength = cycleLength
        self.periodLength = periodLength
        self.lutealLength = lutealLength
        self.reminders = reminders
    }
}

nonisolated struct CyclePeriodInput: Sendable, Hashable {
    var id: String
    var start: String
    var end: String?
    /// `app` | `healthkit` | `health_connect`.
    var source: String

    init(id: String, start: String, end: String?, source: String = "app") {
        self.id = id
        self.start = start
        self.end = end
        self.source = source
    }
}

nonisolated struct CycleLogInput: Sendable, Hashable {
    var day: String
    var flow: String?
    var pain: Int?
    var painLocations: [String]
    var symptoms: [String]
    var moods: [String]

    init(day: String, flow: String? = nil, pain: Int? = nil, painLocations: [String] = [], symptoms: [String] = [], moods: [String] = []) {
        self.day = day
        self.flow = flow
        self.pain = pain
        self.painLocations = painLocations
        self.symptoms = symptoms
        self.moods = moods
    }
}

nonisolated struct CycleState: Sendable, Hashable {
    var today: String
    var settings: CycleSettingsInput
    var periods: [CyclePeriodInput]
    var logs: [CycleLogInput]

    init(today: String, settings: CycleSettingsInput = CycleSettingsInput(), periods: [CyclePeriodInput] = [], logs: [CycleLogInput] = []) {
        self.today = today
        self.settings = settings
        self.periods = periods
        self.logs = logs
    }
}

// MARK: - Results

nonisolated struct CycleEffectiveSettings: Sendable, Hashable {
    struct Reminders: Sendable, Hashable {
        var periodSoon: Bool
        var daysBefore: Int
        var periodEnd: Bool
        var daily: Bool
    }

    var cycleLength: Int
    var periodLength: Int
    var lutealLength: Int
    var reminders: Reminders

    var json: RJ {
        .obj(["cycle_length": .int(cycleLength), "period_length": .int(periodLength), "luteal_length": .int(lutealLength),
              "reminders": .obj(["period_soon": .bool(reminders.periodSoon), "days_before": .int(reminders.daysBefore),
                                 "period_end": .bool(reminders.periodEnd), "daily": .bool(reminders.daily)])])
    }
}

nonisolated struct CycleNormalizedPeriod: Sendable, Hashable, Identifiable {
    var id: String
    var start: Int
    /// nil only while ongoing.
    var end: Int?
    var ongoing: Bool
    var autoClosed: Bool
    var source: String
    var members: [String]
    var length: Int?
    var valid: Bool

    var json: RJ {
        .obj(["id": .str(id), "start": .str(CycleDay.string(start)), "end": .s(CycleDay.opt(end)), "ongoing": .bool(ongoing),
              "auto_closed": .bool(autoClosed), "source": .str(source), "members": .arr(members.map { .str($0) }),
              "length": length.map { .int($0) } ?? .null, "valid": .bool(valid)])
    }
}

nonisolated struct CycleDropped: Sendable, Hashable {
    var id: String
    /// `future` | `end_before_start` | `duplicate`.
    var reason: String
    var json: RJ { .obj(["id": .str(id), "reason": .str(reason)]) }
}

nonisolated struct CycleCycle: Sendable, Hashable {
    var start: Int
    var cycleLength: Int?
    var periodLength: Int?
    var cycleValid: Bool
    var periodValid: Bool

    var json: RJ {
        .obj(["start": .str(CycleDay.string(start)), "cycle_length": cycleLength.map { .int($0) } ?? .null,
              "period_length": periodLength.map { .int($0) } ?? .null, "cycle_valid": .bool(cycleValid),
              "period_valid": .bool(periodValid)])
    }
}

nonisolated struct CycleStats: Sendable, Hashable {
    var cycleCount: Int
    var cycleLengths: [Int]
    var cycleMedian: Double?
    var cycleMean: Double?
    var cycleSD: Double?
    /// [low, high] typical range.
    var cycleRange: [Int]?
    /// `insufficient` | `regular` | `high`.
    var variability: String
    var periodCount: Int
    var periodMedian: Double?

    var json: RJ {
        .obj(["cycle_count": .int(cycleCount), "cycle_lengths": .arr(cycleLengths.map { .int($0) }), "cycle_median": .f(cycleMedian),
              "cycle_mean": .f(cycleMean), "cycle_sd": .f(cycleSD), "cycle_range": cycleRange.map { .arr($0.map { .int($0) }) } ?? .null,
              "variability": .str(variability), "period_count": .int(periodCount), "period_median": .f(periodMedian)])
    }
}

nonisolated struct CycleFrame: Sendable, Hashable {
    var start: Int
    var boundary: Int
    var rawBoundary: Int
    var loggedEnd: Int?
    var periodEnd: Int
    var predicted: Bool
    var ongoing: Bool
    var isLast: Bool
    var estimate: Bool
    var ovulation: Int?
    /// [low, high].
    var fertile: [Int]?
}

nonisolated struct CycleModel: Sendable, Hashable {
    var today: Int
    var settings: CycleEffectiveSettings
    var norm: [CycleNormalizedPeriod]
    var dropped: [CycleDropped]
    var cycles: [CycleCycle]
    var stats: CycleStats
    var frames: [CycleFrame] = []
    /// `none` | `default` | `limited` | `history`.
    var basis: String = "none"
    var length = 0
    var range: [Int] = []
    var period = 0
    var rawNext = 0
    var eff = 0
    var late = 0
    var nextRange: [Int] = []
}

nonisolated struct CycleWindow: Sendable, Hashable {
    var cycleStart: String
    var predictedStart: Bool
    var ovulation: String?
    var fertile: [String]?
    var periodEnd: String

    var json: RJ {
        .obj(["cycle_start": .str(cycleStart), "predicted_start": .bool(predictedStart), "ovulation": .s(ovulation),
              "fertile": fertile.map { .arr($0.map { .str($0) }) } ?? .null, "period_end": .str(periodEnd)])
    }
}

nonisolated struct CyclePrediction: Sendable, Hashable {
    var basis: String
    var cycleLength: Int?
    var cycleRange: [Int]?
    var periodLength: Int?
    var lutealLength: Int
    var nextStart: String?
    var nextRange: [String]?
    var rawNextStart: String?
    var lateDays: Int
    var ongoing: Bool
    var expectedEnd: String?
    var currentCycleDay: Int?
    var windows: [CycleWindow]

    var json: RJ {
        .obj(["basis": .str(basis), "cycle_length": cycleLength.map { .int($0) } ?? .null,
              "cycle_range": cycleRange.map { .arr($0.map { .int($0) }) } ?? .null,
              "period_length": periodLength.map { .int($0) } ?? .null, "luteal_length": .int(lutealLength),
              "next_start": .s(nextStart), "next_range": nextRange.map { .arr($0.map { .str($0) }) } ?? .null,
              "raw_next_start": .s(rawNextStart), "late_days": .int(lateDays), "ongoing": .bool(ongoing),
              "expected_end": .s(expectedEnd), "current_cycle_day": currentCycleDay.map { .int($0) } ?? .null,
              "windows": .arr(windows.map(\.json))])
    }
}

nonisolated struct CycleDayStatus: Sendable, Hashable {
    var day: String
    var cycleDay: Int?
    /// `period` | `predicted_period` | `follicular` | `fertile` | `ovulation` | `luteal` | `late` | `unknown`.
    var phase: String
    var periodDay: Int?
    var estimated: Bool

    var json: RJ {
        .obj(["day": .str(day), "cycle_day": cycleDay.map { .int($0) } ?? .null, "phase": .str(phase),
              "period_day": periodDay.map { .int($0) } ?? .null, "estimated": .bool(estimated)])
    }
}

nonisolated struct CycleInsight: Sendable, Hashable {
    var key: String
    /// Template placeholders (`count`, `low`, `high`, `days`, `total`).
    var params: [String: Int]
    var json: RJ { .obj(["key": .str(key), "params": .obj(params.mapValues { .int($0) })]) }

    /// The config template with `{name}` placeholders filled in (pass a translated template to localize).
    func text(template: String) -> String {
        var out = template
        for (k, v) in params { out = out.replacingOccurrences(of: "{\(k)}", with: "\(v)") }
        return out
    }
}

nonisolated struct CycleReminder: Sendable, Hashable {
    /// `daily_log` | `period_soon` | `period_end`.
    var kind: String
    var day: String?
    var json: RJ { .obj(["kind": .str(kind), "day": .s(day)]) }
}

nonisolated struct CycleSnapshot: Sendable, Hashable {
    var periods: [CycleNormalizedPeriod]
    var stats: CycleStats
    var prediction: CyclePrediction
    var today: CycleDayStatus
    var insights: [CycleInsight]
    var reminders: [CycleReminder]

    var json: RJ {
        .obj(["periods": .arr(periods.map(\.json)), "stats": stats.json, "prediction": prediction.json, "today": today.json,
              "insights": .arr(insights.map(\.json)), "reminders": .arr(reminders.map(\.json))])
    }
}

nonisolated struct CycleTrendCycle: Sendable, Hashable {
    var start: String
    var cycleLength: Int?
    var periodLength: Int?
    var painMax: Int?
    var painMean: Double?
    var symptomDays: [String: Int]
    var moodDays: [String: Int]
    /// Period flow key per period day (first 10 days), nil when not logged or not a period flow.
    var flow: [String?]

    var json: RJ {
        .obj(["start": .str(start), "cycle_length": cycleLength.map { .int($0) } ?? .null,
              "period_length": periodLength.map { .int($0) } ?? .null, "pain_max": painMax.map { .int($0) } ?? .null,
              "pain_mean": .f(painMean), "symptom_days": .obj(symptomDays.mapValues { .int($0) }),
              "mood_days": .obj(moodDays.mapValues { .int($0) }), "flow": .arr(flow.map { .s($0) })])
    }
}

nonisolated struct CycleFrequency: Sendable, Hashable {
    var key: String
    var cycles: Int
    var json: RJ { .obj(["key": .str(key), "cycles": .int(cycles)]) }
}

nonisolated struct CycleTrends: Sendable, Hashable {
    var cycles: [CycleTrendCycle]
    var symptomFrequency: [CycleFrequency]
    var moodFrequency: [CycleFrequency]
    /// Mean flow rank per period day.
    var flowPattern: [Double]
    var windowCycles: Int

    var json: RJ {
        .obj(["cycles": .arr(cycles.map(\.json)), "symptom_frequency": .arr(symptomFrequency.map(\.json)),
              "mood_frequency": .arr(moodFrequency.map(\.json)), "flow_pattern": .fs(flowPattern),
              "window_cycles": .int(windowCycles)])
    }
}

nonisolated struct CyclePeriodCandidate: Sendable, Hashable {
    var id: String?
    var start: String
    var end: String?
}

nonisolated struct CycleValidation: Sendable, Hashable {
    var ok: Bool
    /// `future_start` | `future_end` | `end_before_start` | `too_long` | `open_not_latest` | `overlap`.
    var errors: [String]
    var overlaps: [String]
    /// [start, end or nil] covering the candidate and every overlapping period.
    var mergedStart: String?
    var mergedEnd: String?
    var duration: Int?

    var json: RJ {
        .obj(["ok": .bool(ok), "errors": .arr(errors.map { .str($0) }), "overlaps": .arr(overlaps.map { .str($0) }),
              "merged": mergedStart.map { .arr([.str($0), .s(mergedEnd)]) } ?? .null,
              "duration": duration.map { .int($0) } ?? .null])
    }
}

nonisolated enum CyclePeriodOp: Sendable, Hashable {
    case insert(start: String, end: String?)
    case update(id: String, start: String, end: String?)
    case delete(id: String)

    var json: RJ {
        switch self {
        case .insert(let s, let e): .obj(["op": .str("insert"), "start": .str(s), "end": .s(e)])
        case .update(let id, let s, let e): .obj(["op": .str("update"), "id": .str(id), "start": .str(s), "end": .s(e)])
        case .delete(let id): .obj(["op": .str("delete"), "id": .str(id)])
        }
    }
}

nonisolated struct CyclePeriodDayResult: Sendable, Hashable {
    var ops: [CyclePeriodOp]
    /// `future` or nil.
    var error: String?
    var json: RJ { .obj(["ops": .arr(ops.map(\.json)), "error": .s(error)]) }
}

// MARK: - Engine

nonisolated enum CycleEngine {
    // MARK: Helpers

    static func roundTo(_ x: Double, _ decimals: Int) -> Double {
        var scale = 1.0
        for _ in 0..<decimals { scale *= 10 }
        let v = (x * scale + 0.5).rounded(.down) / scale
        return v == 0 ? 0.0 : v
    }

    static func roundOpt(_ x: Double?, _ decimals: Int) -> Double? { x.map { roundTo($0, decimals) } }

    static func roundHalfUpInt(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }

    static func total(_ values: [Double]) -> Double {
        var t = 0.0
        for v in values { t += v }
        return t
    }

    static func median(_ values: [Int]) -> Double {
        let s = values.sorted()
        let n = s.count
        if n % 2 == 1 { return Double(s[n / 2]) }
        return Double(s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    static func sampleSD(_ values: [Int]) -> Double? {
        let n = values.count
        guard n >= 2 else { return nil }
        let xs = values.map(Double.init)
        let m = total(xs) / Double(n)
        var acc = 0.0
        for v in xs { acc += (v - m) * (v - m) }
        return (acc / Double(n - 1)).squareRoot()
    }

    private static func clamp(_ x: Int, _ lo: Int, _ hi: Int) -> Int { x < lo ? lo : (x > hi ? hi : x) }

    // MARK: Settings

    static func effectiveSettings(_ s: CycleSettingsInput, _ cfg: CycleConfig) -> CycleEffectiveSettings {
        let d = cfg.defaults, lim = cfg.limits
        let cycle = clamp(s.cycleLength ?? d.cycleLength, lim.settingCycleMin, lim.settingCycleMax)
        var period = clamp(s.periodLength ?? d.periodLength, lim.settingPeriodMin, lim.settingPeriodMax)
        if period > cycle - 1 { period = cycle - 1 }
        let luteal = clamp(s.lutealLength ?? d.lutealLength, lim.lutealMin, lim.lutealMax)
        let r = s.reminders
        return CycleEffectiveSettings(
            cycleLength: cycle, periodLength: period, lutealLength: luteal,
            reminders: .init(periodSoon: r.periodSoon ?? true, daysBefore: clamp(r.daysBefore ?? d.reminderDaysBefore, 1, 3),
                             periodEnd: r.periodEnd ?? true, daily: r.daily ?? false))
    }

    // MARK: Periods and cycles

    private struct Item {
        var id: String
        var start: Int
        var end: Int?
        var source: String
        var members: [String] = []
    }

    private static func effEnd(_ start: Int, _ end: Int?, _ today: Int) -> Int { end ?? today }

    static func normalizePeriods(_ periods: [CyclePeriodInput], today: Int, settings: CycleEffectiveSettings,
                                 _ cfg: CycleConfig) -> ([CycleNormalizedPeriod], [CycleDropped]) {
        let lim = cfg.limits
        var items: [Item] = []
        var dropped: [CycleDropped] = []
        for p in periods {
            let s = CycleDay.o(p.start)
            var e = p.end.map(CycleDay.o)
            if s > today {
                dropped.append(CycleDropped(id: p.id, reason: "future"))
                continue
            }
            if let ee = e, ee < s {
                dropped.append(CycleDropped(id: p.id, reason: "end_before_start"))
                continue
            }
            if let ee = e, ee > today { e = today }
            items.append(Item(id: p.id, start: s, end: e, source: p.source.isEmpty ? "app" : p.source))
        }
        let apps = items.filter { $0.source == "app" }
        var kept: [Item] = []
        for it in items {
            if it.source != "app" {
                var hit = false
                for a in apps where it.start <= effEnd(a.start, a.end, today) + 1 && a.start <= effEnd(it.start, it.end, today) + 1 {
                    hit = true
                    break
                }
                if hit {
                    dropped.append(CycleDropped(id: it.id, reason: "duplicate"))
                    continue
                }
            }
            kept.append(it)
        }
        kept.sort { a, b in
            if a.start != b.start { return a.start < b.start }
            let ra = a.source == "app" ? 0 : 1, rb = b.source == "app" ? 0 : 1
            if ra != rb { return ra < rb }
            return a.id < b.id
        }
        var merged: [Item] = []
        for it in kept {
            if let last = merged.last, it.start <= effEnd(last.start, last.end, today) + 1 {
                var m = last
                if m.end == nil || it.end == nil {
                    m.end = nil
                } else if let ie = it.end, let me = m.end, ie > me {
                    m.end = ie
                }
                m.members.append(it.id)
                if it.source == "app" { m.source = "app" }
                merged[merged.count - 1] = m
                continue
            }
            var first = it
            first.members = [it.id]
            merged.append(first)
        }
        var out: [CycleNormalizedPeriod] = []
        for m in merged {
            var ongoing = false, autoClosed = false
            var end = m.end
            if end == nil {
                if today - m.start + 1 > lim.periodMax {
                    end = m.start + settings.periodLength - 1
                    autoClosed = true
                } else {
                    ongoing = true
                }
            }
            let length: Int? = ongoing ? nil : (end! - m.start + 1)
            let valid = length.map { !autoClosed && lim.periodMin <= $0 && $0 <= lim.periodMax } ?? false
            out.append(CycleNormalizedPeriod(id: m.id, start: m.start, end: end, ongoing: ongoing, autoClosed: autoClosed,
                                             source: m.source, members: m.members, length: length, valid: valid))
        }
        return (out, dropped)
    }

    static func buildCycles(_ norm: [CycleNormalizedPeriod], _ cfg: CycleConfig) -> [CycleCycle] {
        let lim = cfg.limits
        var out: [CycleCycle] = []
        for (i, p) in norm.enumerated() {
            let length: Int? = i + 1 < norm.count ? norm[i + 1].start - p.start : nil
            out.append(CycleCycle(start: p.start, cycleLength: length, periodLength: p.length,
                                  cycleValid: length.map { lim.cycleMin <= $0 && $0 <= lim.cycleMax } ?? false,
                                  periodValid: p.valid))
        }
        return out
    }

    static func cycleStats(_ cycles: [CycleCycle], _ cfg: CycleConfig) -> CycleStats {
        let window = cfg.prediction.historyWindow
        var lengths: [Int] = []
        for c in cycles where c.cycleValid { lengths.append(c.cycleLength!) }
        lengths = Array(lengths.suffix(window))
        var periods: [Int] = []
        for c in cycles where c.periodValid { periods.append(c.periodLength!) }
        periods = Array(periods.suffix(window))
        let n = lengths.count
        var out = CycleStats(cycleCount: n, cycleLengths: lengths, cycleMedian: nil, cycleMean: nil, cycleSD: nil, cycleRange: nil,
                             variability: "insufficient", periodCount: periods.count, periodMedian: nil)
        if n > 0 {
            let s = lengths.sorted()
            out.cycleMedian = roundTo(median(lengths), 1)
            out.cycleMean = roundTo(total(lengths.map(Double.init)) / Double(n), 1)
            out.cycleSD = roundOpt(sampleSD(lengths), 2)
            out.cycleRange = n >= cfg.prediction.trimmedRangeMinCycles ? [s[1], s[n - 2]] : [s[0], s[n - 1]]
            let v = cfg.variability
            if n >= v.minCycles {
                let sd = sampleSD(lengths)
                let high = s[n - 1] - s[0] >= v.highRangeDays || (sd.map { $0 > v.highSdDays } ?? false)
                out.variability = high ? "high" : "regular"
            }
        }
        if !periods.isEmpty { out.periodMedian = roundTo(median(periods), 1) }
        return out
    }

    // MARK: Prediction and per-day status

    static func model(_ state: CycleState, _ cfg: CycleConfig) -> CycleModel {
        let today = CycleDay.o(state.today)
        let settings = effectiveSettings(state.settings, cfg)
        let (norm, dropped) = normalizePeriods(state.periods, today: today, settings: settings, cfg)
        let cycles = buildCycles(norm, cfg)
        let stats = cycleStats(cycles, cfg)
        var model = CycleModel(today: today, settings: settings, norm: norm, dropped: dropped, cycles: cycles, stats: stats)
        guard let last = norm.last else { return model }
        let pc = cfg.prediction
        let span = pc.defaultRangeDays
        let n = stats.cycleCount
        let basis: String
        let length: Int
        var lo: Int, hi: Int
        if n == 0 {
            basis = "default"
            length = settings.cycleLength
            lo = length - span
            hi = length + span
        } else if n < pc.historyMinCycles {
            basis = "limited"
            let vals = stats.cycleLengths + [settings.cycleLength]
            length = roundHalfUpInt(median(vals))
            let s = vals.sorted()
            lo = min(s[0], length - span)
            hi = max(s[s.count - 1], length + span)
        } else {
            basis = "history"
            length = roundHalfUpInt(stats.cycleMedian!)
            lo = min(stats.cycleRange![0], length)
            hi = max(stats.cycleRange![1], length)
        }
        var period = stats.periodCount > 0 ? roundHalfUpInt(stats.periodMedian!) : settings.periodLength
        if period > length - 1 { period = length - 1 }
        let rawNext = last.start + length
        let eff: Int, late: Int
        if rawNext > today {
            eff = rawNext
            late = 0
        } else {
            eff = today + 1
            late = today - rawNext
        }
        var rangeLo = eff + (lo - length)
        if rangeLo < today + 1 { rangeLo = today + 1 }
        var rangeHi = eff + (hi - length)
        if rangeHi < rangeLo { rangeHi = rangeLo }
        let lim = cfg.limits
        var frames: [CycleFrame] = []
        for (i, p) in norm.enumerated() {
            let isLast = i + 1 == norm.count
            let boundary = isLast ? eff : norm[i + 1].start
            let rawBoundary = isLast ? rawNext : boundary
            let loggedEnd: Int
            var periodEnd: Int
            if p.ongoing {
                loggedEnd = today
                periodEnd = p.start + period - 1
                if periodEnd < today { periodEnd = today }
            } else {
                loggedEnd = p.end!
                periodEnd = p.end!
            }
            let span = boundary - p.start
            let estimate = isLast || (lim.cycleMin <= span && span <= lim.cycleMax)
            frames.append(CycleFrame(start: p.start, boundary: boundary, rawBoundary: rawBoundary, loggedEnd: loggedEnd,
                                     periodEnd: periodEnd, predicted: false, ongoing: p.ongoing, isLast: isLast, estimate: estimate))
        }
        for k in 0..<pc.projectCycles {
            let s = eff + k * length
            frames.append(CycleFrame(start: s, boundary: s + length, rawBoundary: s + length, loggedEnd: nil, periodEnd: s + period - 1,
                                     predicted: true, ongoing: false, isLast: false, estimate: true))
        }
        for i in frames.indices where frames[i].estimate {
            (frames[i].ovulation, frames[i].fertile) = ovulation(frames[i], luteal: settings.lutealLength, cfg)
        }
        model.basis = basis
        model.length = length
        model.range = [lo, hi]
        model.period = period
        model.rawNext = rawNext
        model.eff = eff
        model.late = late
        model.nextRange = [rangeLo, rangeHi]
        model.frames = frames
        return model
    }

    static func ovulation(_ f: CycleFrame, luteal: Int, _ cfg: CycleConfig) -> (Int?, [Int]?) {
        let rb = f.rawBoundary
        let pend = f.periodEnd
        var ov = rb - luteal
        if ov < pend + 1 { ov = pend + 1 }
        if ov > rb - 2 { ov = rb - 2 }
        if ov <= pend { return (nil, nil) }
        var lo = ov - cfg.fertile.daysBeforeOvulation
        if lo < pend + 1 { lo = pend + 1 }
        var hi = ov + cfg.fertile.daysAfterOvulation
        if hi > rb - 1 { hi = rb - 1 }
        return (ov, [lo, hi])
    }

    static func status(_ model: CycleModel, day: Int) -> CycleDayStatus {
        let frames = model.frames
        guard let first = frames.first, let lastFrame = frames.last, day >= first.start, day < lastFrame.boundary,
              let f = frames.first(where: { $0.start <= day && day < $0.boundary })
        else { return CycleDayStatus(day: CycleDay.string(day), cycleDay: nil, phase: "unknown", periodDay: nil, estimated: false) }
        let cycleDay = day - f.start + 1
        var periodDay: Int?
        var estimated = true
        let phase: String
        if !f.predicted, let le = f.loggedEnd, day <= le {
            phase = "period"
            periodDay = cycleDay
            estimated = false
        } else if day <= f.periodEnd {
            phase = "predicted_period"
            periodDay = cycleDay
        } else if f.isLast && !f.ongoing && model.rawNext <= day && day <= model.today {
            phase = "late"
        } else if let ov = f.ovulation, let fertile = f.fertile {
            if day == ov {
                phase = "ovulation"
            } else if fertile[0] <= day && day <= fertile[1] {
                phase = "fertile"
            } else if day < fertile[0] {
                phase = "follicular"
            } else {
                phase = "luteal"
            }
        } else {
            phase = "unknown"
        }
        return CycleDayStatus(day: CycleDay.string(day), cycleDay: cycleDay, phase: phase, periodDay: periodDay, estimated: estimated)
    }

    static func dayStatuses(_ model: CycleModel, from: Int, to: Int) -> [CycleDayStatus] {
        guard from <= to else { return [] }
        return (from...to).map { status(model, day: $0) }
    }

    static func prediction(_ model: CycleModel) -> CyclePrediction {
        guard model.basis != "none", let last = model.norm.last else {
            return CyclePrediction(basis: "none", cycleLength: nil, cycleRange: nil, periodLength: nil,
                                   lutealLength: model.settings.lutealLength, nextStart: nil, nextRange: nil, rawNextStart: nil,
                                   lateDays: 0, ongoing: false, expectedEnd: nil, currentCycleDay: nil, windows: [])
        }
        var windows: [CycleWindow] = []
        for f in model.frames where f.isLast || f.predicted {
            windows.append(CycleWindow(cycleStart: CycleDay.string(f.start), predictedStart: f.predicted,
                                       ovulation: CycleDay.opt(f.ovulation),
                                       fertile: f.fertile.map { [CycleDay.string($0[0]), CycleDay.string($0[1])] },
                                       periodEnd: CycleDay.string(f.periodEnd)))
        }
        return CyclePrediction(
            basis: model.basis, cycleLength: model.length, cycleRange: model.range, periodLength: model.period,
            lutealLength: model.settings.lutealLength, nextStart: CycleDay.string(model.eff),
            nextRange: [CycleDay.string(model.nextRange[0]), CycleDay.string(model.nextRange[1])],
            rawNextStart: CycleDay.string(model.rawNext), lateDays: model.late, ongoing: last.ongoing,
            expectedEnd: last.ongoing ? CycleDay.string(last.start + model.period - 1) : nil,
            currentCycleDay: model.today - last.start + 1, windows: windows)
    }

    // MARK: Trends, insights, reminders

    static func trends(_ model: CycleModel, logs: [CycleLogInput], _ cfg: CycleConfig) -> CycleTrends {
        var byDay: [Int: CycleLogInput] = [:]
        for lg in logs { byDay[CycleDay.o(lg.day)] = lg }
        let days = byDay.keys.sorted()
        var ranks: [String: Int] = [:]
        for fl in cfg.flowLevels where fl.period { ranks[fl.key] = fl.rank }
        let window = cfg.prediction.historyWindow
        var cyclesOut: [CycleTrendCycle] = []
        for f in model.frames where !f.predicted {
            let end = f.isLast ? model.today + 1 : f.boundary
            var pains: [Int] = []
            var symptoms: [String: Int] = [:], moods: [String: Int] = [:]
            for d in days where d >= f.start && d < end {
                let lg = byDay[d]!
                if let p = lg.pain { pains.append(p) }
                for s in lg.symptoms { symptoms[s, default: 0] += 1 }
                for m in lg.moods { moods[m, default: 0] += 1 }
            }
            var flow: [String?] = []
            let plen = (f.loggedEnd ?? f.start) - f.start + 1
            for k in 0..<max(0, min(plen, 10)) {
                let fk = byDay[f.start + k]?.flow
                flow.append(fk.flatMap { ranks[$0] != nil ? $0 : nil })
            }
            var painMax: Int?
            for v in pains where painMax == nil || v > painMax! { painMax = v }
            cyclesOut.append(CycleTrendCycle(
                start: CycleDay.string(f.start), cycleLength: f.isLast ? nil : f.boundary - f.start,
                periodLength: f.ongoing ? nil : plen, painMax: painMax,
                painMean: pains.isEmpty ? nil : roundTo(total(pains.map(Double.init)) / Double(pains.count), 1),
                symptomDays: symptoms, moodDays: moods, flow: flow))
        }
        let recent = Array(cyclesOut.suffix(window))

        func frequency(_ keys: [String], _ field: (CycleTrendCycle) -> [String: Int]) -> [CycleFrequency] {
            var res: [(n: Int, index: Int, item: CycleFrequency)] = []
            for (index, key) in keys.enumerated() {
                var n = 0
                for c in recent where (field(c)[key] ?? 0) > 0 { n += 1 }
                if n > 0 { res.append((n, index, CycleFrequency(key: key, cycles: n))) }
            }
            res.sort { $0.n != $1.n ? $0.n > $1.n : $0.index < $1.index }
            return res.map(\.item)
        }

        var flowPattern: [Double] = []
        for k in 0..<10 {
            var vals: [Double] = []
            for c in recent where k < c.flow.count {
                if let key = c.flow[k], let r = ranks[key] { vals.append(Double(r)) }
            }
            if vals.isEmpty { break }
            flowPattern.append(roundTo(total(vals) / Double(vals.count), 2))
        }
        return CycleTrends(cycles: cyclesOut, symptomFrequency: frequency(cfg.symptoms.map(\.key), \.symptomDays),
                           moodFrequency: frequency(cfg.moods.map(\.key), \.moodDays), flowPattern: flowPattern,
                           windowCycles: recent.count)
    }

    static func insights(_ model: CycleModel, trends: CycleTrends, _ cfg: CycleConfig) -> [CycleInsight] {
        let rules = cfg.insightRules
        var out: [CycleInsight] = []
        if model.basis == "none" { return out }
        let lengths = model.stats.cycleLengths
        let k = rules.recentCycles
        if lengths.count >= k {
            let recent = Array(lengths.suffix(k)).sorted()
            if recent[0] == recent[recent.count - 1] {
                out.append(CycleInsight(key: "recent_same", params: ["count": k, "low": recent[0]]))
            } else {
                out.append(CycleInsight(key: "recent_range", params: ["count": k, "low": recent[0], "high": recent[recent.count - 1]]))
            }
        } else {
            out.append(CycleInsight(key: "few_cycles", params: [:]))
        }
        if model.stats.variability == "high" { out.append(CycleInsight(key: "variable", params: [:])) }
        if model.late >= rules.lateDays { out.append(CycleInsight(key: "late", params: ["days": model.late])) }
        let lastK = Array(lengths.suffix(k))
        if lastK.count >= rules.outOfRangeMinCount {
            var nOut = 0
            for v in lastK where v < rules.typicalCycleLow || v > rules.typicalCycleHigh { nOut += 1 }
            if nOut >= rules.outOfRangeMinCount {
                out.append(CycleInsight(key: "out_of_range", params: ["count": nOut, "total": lastK.count,
                                                                       "low": rules.typicalCycleLow, "high": rules.typicalCycleHigh]))
            }
        }
        var lastPeriod: CycleNormalizedPeriod?
        for p in model.norm where !p.ongoing && !p.autoClosed { lastPeriod = p }
        if let lp = lastPeriod, let len = lp.length, len >= rules.longPeriodDays {
            out.append(CycleInsight(key: "long_period", params: ["days": len]))
        }
        let withPain = trends.cycles.compactMap(\.painMax)
        if withPain.count >= 2, Double(withPain[withPain.count - 1]) >= rules.severePain, Double(withPain[withPain.count - 2]) >= rules.severePain {
            out.append(CycleInsight(key: "severe_pain", params: [:]))
        }
        return out
    }

    static func reminders(_ model: CycleModel, _ cfg: CycleConfig) -> [CycleReminder] {
        let r = model.settings.reminders
        var out: [CycleReminder] = []
        if r.daily { out.append(CycleReminder(kind: "daily_log", day: nil)) }
        guard model.basis != "none", let last = model.norm.last else { return out }
        if r.periodSoon && !last.ongoing && model.late == 0 && model.rawNext > model.today {
            let day = model.eff - r.daysBefore
            if day >= model.today { out.append(CycleReminder(kind: "period_soon", day: CycleDay.string(day))) }
        }
        if r.periodEnd && last.ongoing {
            var day = last.start + model.period - 1 + cfg.prediction.openPeriodExtraDays
            if day < model.today { day = model.today }
            out.append(CycleReminder(kind: "period_end", day: CycleDay.string(day)))
        }
        return out
    }

    // MARK: Public entry points

    static func snapshot(_ state: CycleState, _ cfg: CycleConfig) -> CycleSnapshot {
        let m = model(state, cfg)
        return snapshot(model: m, logs: state.logs, cfg)
    }

    static func snapshot(model m: CycleModel, logs: [CycleLogInput], _ cfg: CycleConfig) -> CycleSnapshot {
        let t = trends(m, logs: logs, cfg)
        return CycleSnapshot(periods: m.norm, stats: m.stats, prediction: prediction(m), today: status(m, day: m.today),
                             insights: insights(m, trends: t, cfg), reminders: reminders(m, cfg))
    }

    /// Checks a period the user is about to save against today and their other app periods.
    static func validatePeriod(_ cand: CyclePeriodCandidate, periods: [CyclePeriodInput], today todayText: String,
                               _ cfg: CycleConfig) -> CycleValidation {
        let today = CycleDay.o(todayText)
        let s = CycleDay.o(cand.start)
        let e = cand.end.map(CycleDay.o)
        var errors: [String] = []
        if s > today { errors.append("future_start") }
        if let e, e > today { errors.append("future_end") }
        if let e, e < s { errors.append("end_before_start") }
        let duration: Int? = e.flatMap { $0 < s ? nil : $0 - s + 1 }
        if let duration, duration > cfg.limits.periodMax { errors.append("too_long") }
        var overlaps: [String] = []
        var mergedStart: String?, mergedEnd: String?
        if errors.isEmpty && e == nil {
            for p in periods where p.id != cand.id && (p.source.isEmpty || p.source == "app") && CycleDay.o(p.start) > s {
                errors.append("open_not_latest")
                break
            }
        }
        if errors.isEmpty {
            let lo = s, hi = e ?? today
            var mLo = s, mHi = hi, openEnd = e == nil
            for p in periods where p.id != cand.id && (p.source.isEmpty || p.source == "app") {
                let ps = CycleDay.o(p.start)
                let pe = p.end.map(CycleDay.o) ?? today
                if ps <= hi + 1 && lo <= pe + 1 {
                    overlaps.append(p.id)
                    if ps < mLo { mLo = ps }
                    if pe > mHi { mHi = pe }
                    if p.end == nil { openEnd = true }
                }
            }
            if !overlaps.isEmpty {
                errors.append("overlap")
                mergedStart = CycleDay.string(mLo)
                mergedEnd = openEnd ? nil : CycleDay.string(mHi)
            }
        }
        overlaps.sort()
        return CycleValidation(ok: errors.isEmpty, errors: errors, overlaps: overlaps, mergedStart: mergedStart, mergedEnd: mergedEnd,
                               duration: duration)
    }

    /// "This is a period day" on or off for one day: the operations on the user's app periods.
    static func applyPeriodDay(day dayText: String, on: Bool, periods: [CyclePeriodInput], today todayText: String) -> CyclePeriodDayResult {
        let today = CycleDay.o(todayText)
        let day = CycleDay.o(dayText)
        if day > today { return CyclePeriodDayResult(ops: [], error: "future") }
        struct P { var id: String; var start: Int; var end: Int? }
        var apps: [P] = []
        for p in periods where p.source.isEmpty || p.source == "app" {
            apps.append(P(id: p.id, start: CycleDay.o(p.start), end: p.end.map(CycleDay.o)))
        }
        apps.sort { $0.start != $1.start ? $0.start < $1.start : $0.id < $1.id }
        let containing = apps.first { $0.start <= day && day <= ($0.end ?? today) }
        var ops: [CyclePeriodOp] = []
        if on {
            if containing != nil { return CyclePeriodDayResult(ops: [], error: nil) }
            var before: P?, after: P?
            for p in apps {
                if let e = p.end, e + 1 == day { before = p }
                if p.start - 1 == day { after = p }
            }
            if let b = before, let a = after {
                ops.append(.update(id: b.id, start: CycleDay.string(b.start), end: CycleDay.opt(a.end)))
                ops.append(.delete(id: a.id))
            } else if let b = before {
                ops.append(.update(id: b.id, start: CycleDay.string(b.start), end: day == today ? nil : CycleDay.string(day)))
            } else if let a = after {
                ops.append(.update(id: a.id, start: CycleDay.string(day), end: CycleDay.opt(a.end)))
            } else {
                ops.append(.insert(start: CycleDay.string(day), end: day == today ? nil : CycleDay.string(day)))
            }
            return CyclePeriodDayResult(ops: ops, error: nil)
        }
        guard let p = containing else { return CyclePeriodDayResult(ops: [], error: nil) }
        let end = p.end ?? today
        if p.start == day && end == day {
            ops.append(.delete(id: p.id))
        } else if p.start == day {
            ops.append(.update(id: p.id, start: CycleDay.string(day + 1), end: CycleDay.opt(p.end)))
        } else {
            ops.append(.update(id: p.id, start: CycleDay.string(p.start), end: CycleDay.string(day - 1)))
        }
        return CyclePeriodDayResult(ops: ops, error: nil)
    }
}
