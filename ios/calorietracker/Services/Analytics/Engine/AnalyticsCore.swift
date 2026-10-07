import Foundation

// Status, confidence, source policy, units, input hashing, the robust personal baseline and the trend engine.
// Port of the matching sections of `scripts/analytics_reference.py`.

nonisolated enum AnalyticsCore {
    // MARK: Status and confidence

    static func worstStatus(_ statuses: [String], _ cfg: AnalyticsConfig) -> String {
        let rank = cfg["status_rank"]
        var best = "VALID"
        for s in statuses where (rank[s].int ?? 0) > (rank[best].int ?? 0) { best = s }
        return best
    }

    static func confidence(_ classification: String, coverage: Double, n: Int, targetN: Int, context: String?,
                           sourceChanged: Bool, _ cfg: AnalyticsConfig) -> Double {
        let c = cfg["confidence"]
        let fCov = AMath.clamp(coverage / (c["target_coverage"].double ?? 0.7), 0.0, 1.0)
        let fN = AMath.clamp(Double(n) / Double(targetN), 0.0, 1.0)
        let fCtx = c["context_weight"][context ?? "wearable"].double ?? 1.0
        let fSrc = sourceChanged ? (c["source_change_factor"].double ?? 1.0) : 1.0
        return cfg.cap(classification) * (fCov * fN).squareRoot() * fCtx * fSrc
    }

    static func confidenceBand(_ conf: Double, _ cfg: AnalyticsConfig) -> String {
        for b in cfg["confidence"]["bands"].array where conf >= (b["min"].double ?? 0) { return b["id"].string ?? "low" }
        return "low"
    }

    static func statusFor(_ conf: Double, _ cfg: AnalyticsConfig) -> String {
        conf >= (cfg["confidence"]["low_confidence_below"].double ?? 0.5) ? "VALID" : "LOW_CONFIDENCE"
    }

    static func inputHash(_ value: AJ) -> AJ {
        let c = AMath.canonical(value)
        return .obj(["canonical": .str(c), "hash": .str(AMath.fnv1a64(c))])
    }

    // MARK: Source policy

    struct SourceRow: Sendable {
        var id: String
        var source: String
        var origin: Int
        var deviceType: Int?
        var tMs: Int64
        var value: Double?
        var count: Int?
    }

    struct SourceSelection: Sendable, Equatable {
        var strategy: String
        var value: Double?
        var min: Double?
        var max: Double?
        var count: Int
        var source: String?
        var droppedDuplicates: Int
        var sources: [String]

        var json: AJ {
            .obj(["strategy": .str(strategy), "value": .n(value), "min": .n(min), "max": .n(max), "count": .i(count),
                  "source": .s(source), "dropped_duplicates": .i(droppedDuplicates), "sources": .arr(sources.map { .str($0) })])
        }
    }

    static func strategy(for metric: String, _ policy: AJ) -> String {
        policy["metrics"][metric].string ?? policy["default_strategy"].string ?? "average_all"
    }

    private static func stripGH(_ s: String) -> String {
        s.hasPrefix("google_health:") ? String(s.dropFirst("google_health:".count)) : s
    }

    static func sourceSelect(metric: String, rows input: [SourceRow], policy: AJ) -> SourceSelection {
        let strategy = strategy(for: metric, policy)
        let rows = input.enumerated().sorted { a, b in
            if a.element.tMs != b.element.tMs { return a.element.tMs < b.element.tMs }
            if a.element.id != b.element.id { return DerivedMath.pyLess(a.element.id, b.element.id) }
            return a.offset < b.offset
        }.map(\.element).filter { $0.value != nil }
        var out = SourceSelection(strategy: strategy, value: nil, min: nil, max: nil, count: 0, source: nil,
                                  droppedDuplicates: 0, sources: Array(Set(rows.map(\.source))).sorted(by: DerivedMath.pyLess))
        if rows.isEmpty { return out }
        let win = Int64((policy["dedup_window_s"].double ?? 60) * 1000)
        let tolAbs = policy["dedup_value_tolerance"][0].double ?? 0.5
        let tolRel = policy["dedup_value_tolerance"][1].double ?? 0.01
        var kept: [SourceRow] = []
        var dropped = 0
        for r in rows {
            if r.origin == 3 {
                let dup = rows.contains { o in
                    o.origin != 3 && o.source == stripGH(r.source) && abs(o.tMs - r.tMs) <= win
                        && abs(o.value! - r.value!) <= max(tolAbs, tolRel * abs(o.value!))
                }
                if dup {
                    dropped += 1
                    continue
                }
            }
            kept.append(r)
        }
        out.droppedDuplicates = dropped
        var chosen = kept
        if strategy == "single_best_source_per_day" {
            var by: [String: [SourceRow]] = [:]
            for r in kept { by[r.source, default: []].append(r) }
            let wearable = Set(policy["wearable_device_types"].array.compactMap(\.int))
            func key(_ src: String) -> (Int, Int) {
                let rs = by[src]!
                let isWear = rs.contains { $0.deviceType.map(wearable.contains) ?? false }
                var n = 0
                for r in rs { n += max(1, (r.count ?? 1) == 0 ? 1 : (r.count ?? 1)) }
                return (isWear ? 0 : 1, -n)
            }
            let src = by.keys.sorted { a, b in
                let ka = key(a), kb = key(b)
                if ka != kb { return ka < kb }
                return DerivedMath.pyLess(a, b)
            }[0]
            chosen = by[src]!
            out.source = src
        }
        var ws = 0.0
        var w = 0
        var lo: Double?, hi: Double?
        for r in chosen {
            let n = max(1, (r.count ?? 1) == 0 ? 1 : (r.count ?? 1))
            ws += r.value! * Double(n)
            w += n
            lo = lo.map { Swift.min($0, r.value!) } ?? r.value!
            hi = hi.map { Swift.max($0, r.value!) } ?? r.value!
        }
        out.value = AMath.roundTo(ws / Double(w), 6)
        out.min = lo
        out.max = hi
        out.count = w
        return out
    }

    // MARK: Units

    static let unitFactors: [String: Double] = [
        "lb>kg": 0.45359237, "kg>lb": 1.0 / 0.45359237, "ft>m": 0.3048, "m>ft": 1.0 / 0.3048, "in>m": 0.0254,
        "m>in": 1.0 / 0.0254, "cm>m": 0.01, "m>cm": 100.0, "km>m": 1000.0, "m>km": 0.001, "mi>m": 1609.344,
        "m>mi": 1.0 / 1609.344, "kJ>kcal": 1.0 / 4.184, "kcal>kJ": 4.184, "min>s": 60.0, "s>min": 1.0 / 60.0,
        "h>s": 3600.0, "s>h": 1.0 / 3600.0, "h>min": 60.0, "min>h": 1.0 / 60.0, "ms>s": 0.001, "s>ms": 1000.0,
        "L>mL": 1000.0, "mL>L": 0.001, "g>mg": 1000.0, "mg>g": 0.001, "fraction>%": 100.0, "%>fraction": 0.01,
    ]

    /// nil value → (nil, true); unknown pair → (nil, false).
    static func unitConvert(_ v: Double?, from a: String, to b: String) -> (value: Double?, ok: Bool) {
        guard let v else { return (nil, true) }
        if a == b { return (AMath.roundTo(v, 9), true) }
        if a == "degF" && b == "degC" { return (AMath.roundTo((v - 32.0) * 5.0 / 9.0, 9), true) }
        if a == "degC" && b == "degF" { return (AMath.roundTo(v * 9.0 / 5.0 + 32.0, 9), true) }
        guard let f = unitFactors["\(a)>\(b)"] else { return (nil, false) }
        return (AMath.roundTo(v * f, 9), true)
    }

    // MARK: Robust baseline

    struct Robust: Sendable {
        var windowDays: Int
        var n: Int
        var needed: Int
        var coverage: Double
        var value: Double?
        var context: String?
        var median: Double?
        var mad: Double?
        var spread: Double?
        var p10: Double?
        var p90: Double?
        var deviation: Double?
        var pct: Double?
        var z: Double?
        var sourceChanged = false
    }

    static let defaultAllowed: Set<String> = ["wearable", "provider", "manual", "derived"]

    static func robust(_ series: [String: Double], day: String, window: Int, minPoints: Int, spreadFloor: Double,
                       _ cfg: AnalyticsConfig, contexts: [String: String]? = nil, allowed: Set<String>? = nil,
                       sources: [String: String]? = nil, recent: String = "day") -> Robust {
        let contexts = contexts ?? [:]
        let allowed = allowed ?? defaultAllowed
        var vals: [Double] = []
        var srcs: [String] = []
        for d in AMath.windowDays(day, -window, -1) {
            guard let v = series[d], allowed.contains(contexts[d] ?? "wearable") else { continue }
            vals.append(v)
            if let s = sources?[d] { srcs.append(s) }
        }
        var today: Double?
        if recent == "median7" {
            let last = AMath.windowDays(day, -6, 0).compactMap { series[$0] }
            today = last.isEmpty ? nil : AMath.median(last)
        } else {
            today = series[day]
        }
        let n = vals.count
        var b = Robust(windowDays: window, n: n, needed: minPoints, coverage: Double(n) / Double(window), value: today,
                       context: today != nil ? (contexts[day] ?? "wearable") : nil)
        if n < minPoints { return b }
        let med = AMath.median(vals)
        let m = AMath.mad(vals)
        let pcs = cfg["baseline"]["percentiles"].array.compactMap(\.double)
        b.median = med
        b.mad = m
        b.spread = max(cfg.madScale * m, spreadFloor)
        b.p10 = AMath.percentile(vals, pcs.first ?? 10)
        b.p90 = AMath.percentile(vals, pcs.last ?? 90)
        if let sources, !srcs.isEmpty, let todaySource = sources[day] {
            var counts: [String: Int] = [:]
            for s in srcs { counts[s, default: 0] += 1 }
            let top = counts.keys.sorted { a, b in
                counts[a]! != counts[b]! ? counts[a]! > counts[b]! : DerivedMath.pyLess(a, b)
            }[0]
            b.sourceChanged = todaySource != top
        }
        if let today {
            b.deviation = today - med
            b.pct = med == 0 ? nil : (today - med) / abs(med) * 100.0
            b.z = (today - med) / b.spread!
        }
        return b
    }

    static func zDir(_ z: Double, _ direction: String) -> Double {
        if direction == "higher_better" { return z }
        if direction == "lower_better" { return -z }
        return -abs(z)
    }

    static func baselineOut(_ b: Robust, metric m: AJ, _ cfg: AnalyticsConfig) -> AJ {
        var status: String
        var conf = 0.0
        if b.median == nil {
            status = "INSUFFICIENT_HISTORY"
        } else if b.value == nil {
            status = "NO_DATA"
        } else {
            conf = confidence(m["classification"].string ?? "PERSONALIZED_STATISTICAL", coverage: b.coverage, n: b.n,
                              targetN: cfg["confidence"]["target_n"].int ?? 28, context: b.context,
                              sourceChanged: b.sourceChanged, cfg)
            status = statusFor(conf, cfg)
        }
        let d = (m["decimals"].int ?? 0) + 1
        return .obj([
            "status": .str(status), "window_days": .i(b.windowDays), "n": .i(b.n), "needed": .i(b.needed),
            "coverage": .n(AMath.roundTo(b.coverage, 2)), "median": .n(AMath.roundTo(b.median, d)),
            "mad": .n(AMath.roundTo(b.mad, d)), "spread": .n(AMath.roundTo(b.spread, d)),
            "p10": .n(AMath.roundTo(b.p10, d)), "p90": .n(AMath.roundTo(b.p90, d)), "value": .n(AMath.roundTo(b.value, d)),
            "context": .s(b.context), "deviation": .n(AMath.roundTo(b.deviation, d)), "pct": .n(AMath.roundTo(b.pct, 1)),
            "z": .n(AMath.roundTo(b.z, 2)),
            "z_dir": .n(b.z.map { AMath.roundTo(zDir($0, m["direction"].string ?? "band"), 2) }),
            "source_changed": .bool(b.sourceChanged), "confidence": .n(AMath.roundTo(conf, 2)),
            "classification": .str("PERSONALIZED_STATISTICAL"),
        ])
    }

    /// `baseline(inp)`: series, day, metric, window (default 28), contexts, sources, recent.
    static func baseline(series: [String: Double], day: String, metric: String, window: Int = 28,
                         contexts: [String: String]? = nil, sources: [String: String]? = nil, recent: String = "day",
                         _ cfg: AnalyticsConfig) -> AJ {
        let m = cfg.metric(metric)
        let b = robust(series, day: day, window: window, minPoints: cfg.minPoints(window: window),
                       spreadFloor: m["spread_floor"].double ?? 0, cfg, contexts: contexts, sources: sources, recent: recent)
        return baselineOut(b, metric: m, cfg)
    }

    static func baselines(series: [String: Double], day: String, metric: String, contexts: [String: String]? = nil,
                          sources: [String: String]? = nil, recent: String = "day", _ cfg: AnalyticsConfig) -> AJ {
        var out: [String: AJ] = [:]
        for w in cfg["baseline"]["windows"].array.compactMap(\.int) {
            out[String(w)] = baseline(series: series, day: day, metric: metric, window: w, contexts: contexts,
                                      sources: sources, recent: recent, cfg)
        }
        return .obj(out)
    }

    // MARK: Trend

    static func trend(series: [String: Double], day: String, metric: String, window: Int? = nil,
                      _ cfg: AnalyticsConfig) -> AJ {
        let t = cfg["trend"]
        let m = cfg.metric(metric)
        let window = window ?? (t["window_days"].int ?? 28)
        let days = AMath.windowDays(day, -(window - 1), 0)
        var pts: [(Double, Double)] = []
        for (i, d) in days.enumerated() { if let v = series[d] { pts.append((Double(i), v)) } }
        var out = AJObject()
        out["status"] = .str("INSUFFICIENT_DATA")
        out["label"] = .str("INSUFFICIENT_DATA")
        out["window_days"] = .i(window)
        out["sample_count"] = .i(pts.count)
        out["coverage"] = .n(AMath.roundTo(Double(pts.count) / Double(window), 2))
        for k in ["slope_per_day", "pct_per_week", "recent_median", "prior_median", "pct_change", "ewma", "latest_z",
                  "change_point_day", "change_direction"] { out[k] = .null }
        out["confidence"] = .num(0)
        if pts.count < (t["min_points"].int ?? 10) { return out.value }
        let ys = pts.map(\.1)
        let med = AMath.median(ys)
        let slope = AMath.theilSen(pts).0!
        let pctWeek: Double? = med == 0 ? nil : slope * 7.0 / abs(med) * 100.0
        let recentDays = Double(t["recent_days"].int ?? 7)
        let recent = pts.filter { $0.0 >= Double(window) - recentDays }.map(\.1)
        let prior = pts.filter { $0.0 < Double(window) - recentDays }.map(\.1)
        let rm = recent.isEmpty ? nil : AMath.median(recent)
        let pm = prior.isEmpty ? nil : AMath.median(prior)
        let floor = m["spread_floor"].double ?? 0
        let spread = max(cfg.madScale * AMath.mad(ys), floor)
        var latestZ: Double?
        if let today = series[day], prior.count >= 1 {
            let others = pts.filter { $0.0 != Double(window - 1) }.map(\.1)
            let oSpread = max(cfg.madScale * AMath.mad(others), floor)
            latestZ = (today - AMath.median(others)) / oSpread
        }
        var cpDay: String?
        var cpDir: String?
        let c = t["cusum"]
        let refN = c["reference_points"].int ?? 14
        if pts.count >= (c["min_points"].int ?? 28) {
            let ref = pts.prefix(refN).map(\.1)
            let rMed = AMath.median(ref)
            let rSpread = max(cfg.madScale * AMath.mad(ref), floor)
            let z = pts.dropFirst(refN).map { ($0.1 - rMed) / rSpread }
            if let (idx, dir) = AMath.cusum(z, c["k"].double ?? 0.5, c["h"].double ?? 4) {
                cpDay = days[Int(pts[refN + idx].0)]
                cpDir = dir
            }
        }
        let direction = m["direction"].string ?? "band"
        let label: String
        if let latestZ, abs(latestZ) >= (t["unusual_abs_z"].double ?? 3) {
            label = "UNUSUAL"
        } else if pctWeek == nil || abs(pctWeek!) < (m["trend_stable_pct_per_week"].double ?? 0) {
            label = "STABLE"
        } else if direction == "band" {
            label = "CHANGING"
        } else if (pctWeek! > 0) == (direction == "higher_better") {
            label = "IMPROVING"
        } else {
            label = "DECLINING"
        }
        let conf = confidence(m["classification"].string ?? "PERSONALIZED_STATISTICAL",
                              coverage: Double(pts.count) / Double(window), n: pts.count, targetN: window,
                              context: "wearable", sourceChanged: false, cfg)
        let d = (m["decimals"].int ?? 0) + 1
        out["status"] = .str(statusFor(conf, cfg))
        out["label"] = .str(label)
        out["slope_per_day"] = .n(AMath.roundTo(slope, 4))
        out["pct_per_week"] = .n(AMath.roundTo(pctWeek, 2))
        out["recent_median"] = .n(AMath.roundTo(rm, d))
        out["prior_median"] = .n(AMath.roundTo(pm, d))
        var pctChange: Double?
        if let rm, let pm, pm != 0 { pctChange = (rm - pm) / abs(pm) * 100.0 }
        out["pct_change"] = .n(AMath.roundTo(pctChange, 1))
        out["ewma"] = .n(AMath.roundTo(AMath.ewma(ys, t["ewma_span_days"].double ?? 7), d))
        out["latest_z"] = .n(AMath.roundTo(latestZ, 2))
        out["change_point_day"] = .s(cpDay)
        out["change_direction"] = .s(cpDir)
        out["confidence"] = .n(AMath.roundTo(conf, 2))
        out["median"] = .n(AMath.roundTo(med, d))
        out["spread"] = .n(AMath.roundTo(spread, d))
        return out.value
    }
}
