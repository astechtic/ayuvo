import Foundation

// HRV from beat-to-beat intervals, HRV status, personal sleep need and sleep status. Port of the "HRV" and "Sleep"
// sections of `scripts/analytics_reference.py`.

nonisolated enum AnalyticsHRV {
    /// One beat-to-beat series: `ibis` = [(interval ms, preceded by a gap)].
    static func hrvRR(_ ibis: [(Double, Bool)], _ cfg: AnalyticsConfig) -> AJ {
        let rr = cfg["hrv"]["rr"]
        var out = AJObject()
        out["status"] = .str("NO_DATA")
        for k in ["rmssd", "sdnn", "ln_rmssd", "mean_hr", "artifact_fraction"] { out[k] = .null }
        out["n_beats"] = .i(ibis.count)
        out["n_accepted"] = .i(0)
        out["n_diffs"] = .i(0)
        out["classification"] = .str("SCIENTIFIC_DERIVED")
        if ibis.isEmpty { return out.value }
        let raw = ibis.map(\.0), gap = ibis.map(\.1)
        let minMs = rr["min_ms"].double ?? 300, maxMs = rr["max_ms"].double ?? 2000
        let inRange = raw.map { minMs <= $0 && $0 <= maxMs }
        let h = (rr["median_window"].int ?? 5) / 2
        let maxRel = rr["max_rel_deviation"].double ?? 0.2
        var accepted: [Bool] = []
        for k in raw.indices {
            var ok = inRange[k]
            if ok {
                var local: [Double] = []
                for j in (k - h)...(k + h) where j >= 0 && j < raw.count && inRange[j] { local.append(raw[j]) }
                let med = AMath.median(local)
                if abs(raw[k] - med) / med > maxRel { ok = false }
            }
            accepted.append(ok)
        }
        let acc = raw.indices.filter { accepted[$0] }.map { raw[$0] }
        var diffs: [Double] = []
        for k in 1..<max(1, raw.count) where accepted[k] && accepted[k - 1] && !gap[k] { diffs.append(raw[k] - raw[k - 1]) }
        let rejected = raw.count - acc.count
        out["n_accepted"] = .i(acc.count)
        out["n_diffs"] = .i(diffs.count)
        out["artifact_fraction"] = .num(AMath.roundTo(Double(rejected) / Double(raw.count), 3))
        if Double(rejected) / Double(raw.count) > (rr["max_artifact_fraction"].double ?? 0.05) {
            out["status"] = .str("INVALID_INPUT")
            return out.value
        }
        if acc.count < (rr["min_beats"].int ?? 30) || diffs.count < 2 {
            out["status"] = .str("INSUFFICIENT_DATA")
            return out.value
        }
        var sq = 0.0
        for d in diffs { sq += d * d }
        let rmssd = (sq / Double(diffs.count)).squareRoot()
        let m = AMath.mean(acc)
        out["status"] = .str("VALID")
        out["rmssd"] = .num(AMath.roundTo(rmssd, 2))
        out["sdnn"] = .num(AMath.roundTo(AMath.sampleSD(acc, m), 2))
        out["ln_rmssd"] = rmssd > 0 ? .num(AMath.roundTo(Foundation.log(rmssd), 3)) : .null
        out["mean_hr"] = .num(AMath.roundTo(60000.0 / m, 1))
        return out.value
    }

    struct BeatSeries: Sendable {
        var startMs: Int64
        var ibis: [(Double, Bool)]
    }

    static func hrvRRDay(series input: [BeatSeries], night: (startMs: Int64, endMs: Int64)?, _ cfg: AnalyticsConfig) -> AJ {
        var results: [(Bool, AJ)] = []
        let sorted = input.enumerated().sorted { $0.element.startMs != $1.element.startMs
            ? $0.element.startMs < $1.element.startMs : $0.offset < $1.offset }.map(\.element)
        for s in sorted {
            let r = hrvRR(s.ibis, cfg)
            if r["status"].string == "VALID" {
                let inside = night.map { $0.startMs <= s.startMs && s.startMs <= $0.endMs } ?? false
                results.append((inside, r))
            }
        }
        var out = AJObject()
        out["status"] = .str("NO_DATA")
        for k in ["rmssd", "sdnn", "ln_rmssd", "context"] { out[k] = .null }
        out["n_series"] = .i(0)
        out["classification"] = .str("SCIENTIFIC_DERIVED")
        if input.isEmpty { return out.value }
        if results.isEmpty {
            out["status"] = .str("INSUFFICIENT_DATA")
            return out.value
        }
        let overnight = results.filter(\.0).map(\.1)
        let use = overnight.isEmpty ? results.map(\.1) : overnight
        let rm = AMath.median(use.compactMap { $0["rmssd"].double })
        out["status"] = .str("VALID")
        out["rmssd"] = .num(AMath.roundTo(rm, 2))
        out["sdnn"] = .num(AMath.roundTo(AMath.median(use.compactMap { $0["sdnn"].double }), 2))
        out["ln_rmssd"] = rm > 0 ? .num(AMath.roundTo(Foundation.log(rm), 3)) : .null
        out["n_series"] = .i(use.count)
        out["context"] = .str(overnight.isEmpty ? "daytime" : "overnight")
        return out.value
    }

    static func hrvStatus(series: [String: Double], day: String, kind: String?, contexts: [String: String]? = nil,
                          sources: [String: String]? = nil, _ cfg: AnalyticsConfig) -> AJ {
        let hc = cfg["hrv"]
        let b = AnalyticsCore.baseline(series: series, day: day, metric: "hrv", window: hc["baseline_window_days"].int ?? 28,
                                       contexts: contexts, sources: sources, cfg)
        let t = AnalyticsCore.trend(series: series, day: day, metric: "hrv", cfg)
        var lnv: [Double] = []
        for d in AMath.windowDays(day, -((hc["stability_days"].int ?? 7) - 1), 0) {
            if let v = series[d], v > 0 { lnv.append(Foundation.log(v)) }
        }
        var cv: Double?, lnMean: Double?
        if lnv.count >= (hc["stability_min_days"].int ?? 5) {
            let m = AMath.mean(lnv)
            lnMean = m
            cv = AMath.sampleSD(lnv, m) / m * 100.0
        }
        let provider = kind == "sdnn" || kind == "rmssd"
        return .obj(["kind": .s(kind), "baseline": b, "trend": t, "ln_mean_7d": .n(AMath.roundTo(lnMean, 3)),
                     "cv_ln_7d": .n(AMath.roundTo(cv, 2)), "stability_days": .i(lnv.count),
                     "classification": .str(provider ? "PROVIDER_DERIVED" : "SCIENTIFIC_DERIVED")])
    }
}

/// One night as the analytics engine sees it (`nights` encoding of the reference).
nonisolated struct ANight: Sendable, Equatable {
    var asleepMin: Double?
    var inBedMin: Double?
    var efficiency: Double?
    var wasoMin: Double?
    var bedtimeClock: Double?
    var wakeClock: Double?
    var midpointClock: Double?
    var napMin: Double?

    init(asleepMin: Double?, inBedMin: Double? = nil, efficiency: Double? = nil, wasoMin: Double? = nil,
         bedtimeClock: Double? = nil, wakeClock: Double? = nil, midpointClock: Double? = nil, napMin: Double? = nil) {
        self.asleepMin = asleepMin
        self.inBedMin = inBedMin
        self.efficiency = efficiency
        self.wasoMin = wasoMin
        self.bedtimeClock = bedtimeClock
        self.wakeClock = wakeClock
        self.midpointClock = midpointClock
        self.napMin = napMin
    }

    init(_ j: AJ) {
        self.init(asleepMin: j["asleep_min"].double, inBedMin: j["in_bed_min"].double, efficiency: j["efficiency"].double,
                  wasoMin: j["waso_min"].double, bedtimeClock: j["bedtime_clock"].double, wakeClock: j["wake_clock"].double,
                  midpointClock: j["midpoint_clock"].double, napMin: j["nap_min"].double)
    }
}

nonisolated enum AnalyticsSleep {
    static func validNights(_ nights: [String: ANight], _ cfg: AnalyticsConfig) -> [String: ANight] {
        let floor = cfg["sleep"]["min_night_minutes"].double ?? 120
        return nights.filter { ($0.value.asleepMin ?? -1) >= floor && $0.value.asleepMin != nil }
    }

    /// Either `nights` or `asleep` ({day: minutes}).
    static func sleepNeed(nights: [String: ANight]? = nil, asleep: [String: Double]? = nil, day: String,
                          _ cfg: AnalyticsConfig) -> (needMin: Double, source: String, n: Int) {
        let s = cfg["sleep"]
        let floor = s["min_night_minutes"].double ?? 120
        var minutes: [String: Double] = [:]
        if let asleep {
            minutes = asleep.filter { $0.value >= floor }
        } else {
            for (d, n) in validNights(nights ?? [:], cfg) { minutes[d] = n.asleepMin! }
        }
        let vals = AMath.windowDays(day, -(s["need_window_days"].int ?? 60), -1).compactMap { minutes[$0] }
        if vals.count < (s["need_min_nights"].int ?? 14) {
            return (s["need_default_min"].double ?? 480, "default", vals.count)
        }
        let lo = s["need_clamp_min"][0].double ?? 420, hi = s["need_clamp_min"][1].double ?? 540
        return (AMath.roundTo(AMath.clamp(AMath.median(vals), lo, hi), 1), "personal", vals.count)
    }

    static func sleepNeedJSON(_ r: (needMin: Double, source: String, n: Int)) -> AJ {
        .obj(["need_min": .num(r.needMin), "source": .str(r.source), "n": .i(r.n),
              "classification": .str("PERSONALIZED_STATISTICAL")])
    }

    static func sleepStatus(nights raw: [String: ANight], day: String, _ cfg: AnalyticsConfig) -> AJ {
        let s = cfg["sleep"]
        let nights = validNights(raw, cfg)
        let need = sleepNeed(nights: raw, day: day, cfg)
        var out = AJObject()
        out["status"] = .str("NO_DATA")
        out["day"] = .str(day)
        for k in ["asleep_min", "in_bed_min", "efficiency", "waso_min", "nap_min", "debt_min", "bedtime_sd", "wake_sd",
                  "duration", "efficiency_baseline"] { out[k] = .null }
        out["need_min"] = .num(need.needMin)
        out["need_source"] = .str(need.source)
        out["debt_nights"] = .i(0)
        out["variability_nights"] = .i(0)
        out["confidence"] = .num(0)
        let debtDays = AMath.windowDays(day, -((s["debt_window_days"].int ?? 14) - 1), 0).filter { nights[$0] != nil }
        if !debtDays.isEmpty {
            var debt = 0.0
            for d in debtDays { debt += max(0.0, need.needMin - nights[d]!.asleepMin!) }
            out["debt_min"] = .num(AMath.roundTo(debt, 0))
            out["debt_nights"] = .i(debtDays.count)
        }
        let varDays = AMath.windowDays(day, -((s["variability_window_days"].int ?? 14) - 1), 0).filter {
            nights[$0]?.bedtimeClock != nil && nights[$0]?.wakeClock != nil
        }
        out["variability_nights"] = .i(varDays.count)
        if varDays.count >= (s["variability_min_nights"].int ?? 5) {
            let beds = varDays.map { nights[$0]!.bedtimeClock! }
            let wakes = varDays.map { nights[$0]!.wakeClock! }
            out["bedtime_sd"] = .num(AMath.roundTo(AMath.sampleSD(beds, AMath.mean(beds)), 1))
            out["wake_sd"] = .num(AMath.roundTo(AMath.sampleSD(wakes, AMath.mean(wakes)), 1))
        }
        var dur: [String: Double] = [:], eff: [String: Double] = [:]
        for (d, n) in nights {
            dur[d] = n.asleepMin!
            if let e = n.efficiency { eff[d] = e }
        }
        let w = s["baseline_window_days"].int ?? 28
        out["duration"] = AnalyticsCore.baseline(series: dur, day: day, metric: "sleep_duration", window: w, cfg)
        out["efficiency_baseline"] = AnalyticsCore.baseline(series: eff, day: day, metric: "sleep_efficiency", window: w, cfg)
        if let last = nights[day] {
            let rawNight = raw[day]
            out["asleep_min"] = .n(AMath.roundTo(last.asleepMin, 0))
            out["in_bed_min"] = .n(AMath.roundTo(last.inBedMin, 0))
            out["efficiency"] = .n(AMath.roundTo(last.efficiency, 1))
            out["waso_min"] = .n(AMath.roundTo(last.wasoMin, 0))
            out["nap_min"] = .n(AMath.roundTo(rawNight?.napMin, 0))
            let nHist = AMath.windowDays(day, -w, -1).filter { nights[$0] != nil }.count
            let conf = AnalyticsCore.confidence("PROVIDER_DERIVED", coverage: Double(nHist) / Double(w), n: nHist,
                                                targetN: cfg["confidence"]["target_n"].int ?? 28, context: "wearable",
                                                sourceChanged: false, cfg)
            out["confidence"] = .num(AMath.roundTo(conf, 2))
            out["status"] = .str(AnalyticsCore.statusFor(conf, cfg))
        }
        return out.value
    }
}
