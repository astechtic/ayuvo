import Foundation

// Training load (sessions, session-RPE, TRIMP, EWMA acute/chronic, personal states) and heart rate recovery.
// Port of the "Training load" and "Heart rate recovery" sections of `scripts/analytics_reference.py`.

nonisolated struct AWorkout: Sendable, Equatable {
    var startMs: Int64
    var endMs: Int64
    var effort: Double?
    var trimp: Double?
    var activity: String?

    init(startMs: Int64, endMs: Int64, effort: Double? = nil, trimp: Double? = nil, activity: String? = nil) {
        self.startMs = startMs
        self.endMs = endMs
        self.effort = effort
        self.trimp = trimp
        self.activity = activity
    }

    init(_ j: AJ) {
        self.init(startMs: j["start_ms"].int64 ?? 0, endMs: j["end_ms"].int64 ?? 0, effort: j["effort"].double,
                  trimp: j["trimp"].double, activity: j["activity"].string)
    }
}

nonisolated enum AnalyticsLoad {
    struct Session: Sendable {
        var startMs: Int64
        var endMs: Int64
        var effort: Double?
        var trimp: Double?
        var day = ""
        var minutes = 0.0
    }

    struct DayLoad: Sendable {
        var minutes = 0.0
        var rpeLoad = 0.0
        var rpeMinutes = 0.0
        var trimp = 0.0
        var trimpMinutes = 0.0

        func value(_ method: String) -> Double {
            switch method {
            case "trimp": return trimp
            case "rpe_load": return rpeLoad
            default: return minutes
            }
        }
    }

    static let methods = ["trimp", "rpe_load", "minutes"]

    static func sessions(_ workouts: [AWorkout], timeZone: String) -> [Session] {
        let ws = workouts.enumerated().filter { $0.element.endMs > $0.element.startMs }.sorted { a, b in
            if a.element.startMs != b.element.startMs { return a.element.startMs < b.element.startMs }
            if a.element.endMs != b.element.endMs { return a.element.endMs < b.element.endMs }
            return a.offset < b.offset
        }.map(\.element)
        var out: [Session] = []
        for w in ws {
            if let last = out.last, w.startMs < last.endMs {
                var s = last
                s.endMs = max(s.endMs, w.endMs)
                if let e = w.effort { s.effort = s.effort.map { max($0, e) } ?? e }
                if let t = w.trimp { s.trimp = s.trimp.map { $0 + t } ?? t }
                out[out.count - 1] = s
            } else {
                out.append(Session(startMs: w.startMs, endMs: w.endMs, effort: w.effort, trimp: w.trimp))
            }
        }
        for i in out.indices {
            out[i].day = AMath.localDayOf(out[i].startMs, timeZone)
            out[i].minutes = Double(out[i].endMs - out[i].startMs) / 60000.0
        }
        return out
    }

    static func loadDays(_ workouts: [AWorkout], timeZone: String) -> [String: DayLoad] {
        var days: [String: DayLoad] = [:]
        for s in sessions(workouts, timeZone: timeZone) {
            var d = days[s.day] ?? DayLoad()
            d.minutes += s.minutes
            if let e = s.effort {
                d.rpeLoad += e * s.minutes
                d.rpeMinutes += s.minutes
            }
            if let t = s.trimp {
                d.trimp += t
                d.trimpMinutes += s.minutes
            }
            days[s.day] = d
        }
        return days
    }

    struct MethodResult: Sendable {
        var acute: Double
        var chronic: Double
        var ratio: Double?
        var coverage: Double
        var today: Double

        var json: AJ {
            .obj(["acute": .num(acute), "chronic": .num(chronic), "ratio": .n(ratio), "coverage": .num(coverage),
                  "today": .num(today)])
        }
    }

    struct Result: Sendable {
        var status = "NO_DATA"
        var day: String
        var state: String?
        var stateLabel: String?
        var primaryMethod: String?
        var historyDays = 0
        var methods: [String: MethodResult] = [:]
        var today: Double?
        var confidence: Double?

        var primary: MethodResult? { primaryMethod.flatMap { methods[$0] } }

        var json: AJ {
            var o: [String: AJ] = [
                "status": .str(status), "day": .str(day), "state": .s(state), "state_label": .s(stateLabel),
                "primary_method": .s(primaryMethod), "history_days": .i(historyDays),
                "methods": .obj(methods.mapValues(\.json)), "today": .n(today),
                "classification": .str("SCIENTIFIC_DERIVED"),
            ]
            if let confidence { o["confidence"] = .num(confidence) }
            return .obj(o)
        }
    }

    static func load(workouts: [AWorkout], day: String, timeZone: String, tracking: Bool, _ cfg: AnalyticsConfig) -> Result {
        let lc = cfg["load"]
        var out = Result(day: day)
        if !tracking { return out }
        let days = loadDays(workouts, timeZone: timeZone)
        let known = days.keys.filter { $0 <= day }.sorted()
        guard let first = known.first else {
            out.status = "INSUFFICIENT_HISTORY"
            return out
        }
        let start = max(first, AMath.addDays(day, -(lc["max_history_days"].int ?? 120)))
        var series: [String: [Double]] = Dictionary(uniqueKeysWithValues: methods.map { ($0, []) })
        var d = start
        while d <= day {
            let x = days[d]
            for m in methods { series[m]!.append(x?.value(m) ?? 0.0) }
            d = AMath.addDays(d, 1)
        }
        let n = series["minutes"]!.count
        out.historyDays = n
        let acuteDays = lc["acute_days"].int ?? 7
        let chronicDays = lc["chronic_days"].int ?? 28
        let la = 2.0 / (Double(acuteDays) + 1.0)
        let lch = 2.0 / (Double(chronicDays) + 1.0)
        var acute: [String: [Double]] = [:], chronic: [String: [Double]] = [:]
        for m in methods {
            let seed = Array(series[m]!.prefix(chronicDays))
            var a = AMath.mean(seed), c = a
            var aa = [Double](repeating: 0, count: n), cc = [Double](repeating: 0, count: n)
            for (i, x) in series[m]!.enumerated() {
                a = la * x + (1.0 - la) * a
                c = lch * x + (1.0 - lch) * c
                aa[i] = a
                cc[i] = c
            }
            acute[m] = aa
            chronic[m] = cc
        }
        let stateWindow = lc["state_window_days"].int ?? 90
        let lo = max(0, n - 1 - stateWindow)
        var mins = 0.0, tmins = 0.0, rmins = 0.0
        for w in AMath.windowDays(day, -stateWindow, 0) {
            if let x = days[w] {
                mins += x.minutes
                tmins += x.trimpMinutes
                rmins += x.rpeMinutes
            }
        }
        let cov: [String: Double] = ["minutes": mins > 0 ? 1.0 : 0.0, "trimp": mins > 0 ? tmins / mins : 0.0,
                                     "rpe_load": mins > 0 ? rmins / mins : 0.0]
        for m in methods {
            let a = acute[m]![n - 1], c = chronic[m]![n - 1]
            out.methods[m] = MethodResult(acute: AMath.roundTo(a, 1), chronic: AMath.roundTo(c, 1),
                                          ratio: c > 0 ? AMath.roundTo(a / c, 2) : nil, coverage: AMath.roundTo(cov[m]!, 2),
                                          today: AMath.roundTo(series[m]![n - 1], 1))
        }
        let minCov = lc["method_min_coverage"].double ?? 0.7
        let primary = cov["trimp"]! >= minCov ? "trimp" : (cov["rpe_load"]! >= minCov ? "rpe_load" : "minutes")
        out.primaryMethod = primary
        out.today = out.methods[primary]!.today
        let minHistory = lc["min_history_days"].int ?? 42
        if n < minHistory {
            out.status = "INSUFFICIENT_HISTORY"
            return out
        }
        var histA: [Double] = [], histR: [Double] = []
        let from = max(lo, minHistory - 1), to = n - acuteDays
        if from < to {
            for i in from..<to {
                histA.append(acute[primary]![i])
                if chronic[primary]![i] > 0 { histR.append(acute[primary]![i] / chronic[primary]![i]) }
            }
        }
        let stateMin = lc["state_min_days"].int ?? 28
        if histA.count < stateMin || histR.count < stateMin {
            out.status = "INSUFFICIENT_HISTORY"
            return out
        }
        let p = lc["percentiles"], gd = lc["guards"]
        let aNow = acute[primary]![n - 1]
        let cNow = chronic[primary]![n - 1]
        let rNow: Double? = cNow > 0 ? aNow / cNow : nil
        let aMed = AMath.median(histA)
        let state: String
        if let r = rNow, r >= gd["spike_min_ratio"].double!, r > AMath.percentile(histR, p["spike_ratio_above"].double!) {
            state = "LOAD_SPIKE"
        } else if aNow > AMath.percentile(histA, p["high_above"].double!), aNow >= gd["high_min_vs_median"].double! * aMed {
            state = "LOAD_HIGH"
        } else if let r = rNow, r >= gd["increasing_min_ratio"].double!,
                  r > AMath.percentile(histR, p["increasing_ratio_above"].double!) {
            state = "LOAD_INCREASING"
        } else if aNow < AMath.percentile(histA, p["reduced_below"].double!), aNow <= gd["reduced_max_vs_median"].double! * aMed {
            state = "LOAD_REDUCED"
        } else {
            state = "LOAD_STABLE"
        }
        let conf = AnalyticsCore.confidence("SCIENTIFIC_DERIVED", coverage: cov[primary]!, n: histA.count,
                                            targetN: stateWindow, context: "wearable", sourceChanged: false, cfg)
        out.status = AnalyticsCore.statusFor(conf, cfg)
        out.state = state
        out.stateLabel = lc["states"][state].string
        out.confidence = AMath.roundTo(conf, 2)
        return out
    }

    // MARK: Heart rate recovery

    static func hrr(samples input: [(Int64, Double)], endMs end: Int64, _ cfg: AnalyticsConfig) -> AJ {
        let hc = cfg["hrr"]
        let samples = input.enumerated().sorted { $0.element.0 != $1.element.0 ? $0.element.0 < $1.element.0 : $0.offset < $1.offset }
            .map(\.element)
        let tol = Int64((hc["tolerance_s"].double ?? 30) * 1000)
        let before = samples.filter { end - 60000 <= $0.0 && $0.0 <= end }.map(\.1)
        var out: [String: AJ] = ["status": .str("NO_DATA"), "peak": .null, "hrr1": .null, "hrr2": .null,
                                 "confidence": .num(0), "classification": .str("SCIENTIFIC_DERIVED")]
        if samples.isEmpty { return .obj(out) }
        func at(_ offset: Int64) -> Double? {
            // sorted((abs(t - target), t, b)) [0]
            let target = end + offset
            let c = samples.filter { abs($0.0 - target) <= tol }.sorted { a, b in
                let da = abs(a.0 - target), db = abs(b.0 - target)
                if da != db { return da < db }
                if a.0 != b.0 { return a.0 < b.0 }
                return a.1 < b.1
            }
            return c.first?.1
        }
        let h1 = at(60000), h2 = at(120000)
        guard !before.isEmpty, let h1 else {
            out["status"] = .str("INSUFFICIENT_DATA")
            return .obj(out)
        }
        let peak = before.max()!
        var maxGap: Int64 = .min
        for i in 0..<max(0, samples.count - 1) { maxGap = max(maxGap, samples[i + 1].0 - samples[i].0) }
        let dense = samples.count > 1 && maxGap <= Int64((hc["dense_max_gap_s"].double ?? 10) * 1000)
        let conf = dense ? (hc["confidence_dense"].double ?? 0.8) : (hc["confidence_sparse"].double ?? 0.4)
        out["status"] = .str(AnalyticsCore.statusFor(conf, cfg))
        out["peak"] = .num(AMath.roundTo(peak, 0))
        out["hrr1"] = .num(AMath.roundTo(peak - h1, 0))
        out["hrr2"] = .n(h2.map { AMath.roundTo(peak - $0, 0) })
        out["confidence"] = .num(conf)
        return .obj(out)
    }
}
