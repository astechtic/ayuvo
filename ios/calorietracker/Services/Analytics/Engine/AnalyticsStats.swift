import Foundation

// Correlation with FDR control, personal response (Theil–Sen), energy, VO2 max trend and MET intensity. Port of the
// matching sections of `scripts/analytics_reference.py`.

nonisolated enum AnalyticsStats {
    static func paired(_ exposure: [String: Double], _ outcome: [String: Double], lag: Int, asOf: String,
                       window: Int) -> [(Double, Double)] {
        var out: [(Double, Double)] = []
        for d in AMath.windowDays(asOf, -window, -lag) {
            if let x = exposure[d], let y = outcome[AMath.addDays(d, lag)] { out.append((x, y)) }
        }
        return out
    }

    struct Pair: Sendable {
        var id: String
        var exposure: [String: Double]
        var outcome: [String: Double]
    }

    private struct Row {
        var id: String
        var lag: Int
        var n: Int
        var status = "INSUFFICIENT_DATA"
        var pearsonR: Double?
        var spearmanRho: Double?
        var ciLow: Double?
        var ciHigh: Double?
        var p: Double?
        var q: Double?
        var surfaced = false
        var text: String?
    }

    static func correlation(pairs: [Pair], asOf: String, _ cfg: AnalyticsConfig) -> AJ {
        let cc = cfg["correlation"]
        var meta: [String: AJ] = [:]
        for p in cc["pairs"].array { if let id = p["id"].string { meta[id] = p } }
        let window = cc["window_days"].int ?? 120
        let minN = cc["min_n"].int ?? 21
        let se1 = cc["spearman_se_factor"].double ?? 1.06
        let z975 = cc["z_975"].double ?? 1.959964
        var results: [Row] = []
        var tested: [Int] = []
        for pr in pairs {
            for lagJ in cc["lags"].array {
                let lag = lagJ.int ?? 0
                let pts = paired(pr.exposure, pr.outcome, lag: lag, asOf: asOf, window: window)
                var r = Row(id: pr.id, lag: lag, n: pts.count)
                if pts.count >= minN {
                    let xs = pts.map(\.0), ys = pts.map(\.1)
                    if let prR = AMath.pearson(xs, ys), let rho = AMath.pearson(AMath.ranks(xs), AMath.ranks(ys)) {
                        let z = AMath.atanh(AMath.clamp(rho, -0.9999, 0.9999))
                        let se = (se1 / Double(pts.count - 3)).squareRoot()
                        r.status = "VALID"
                        r.pearsonR = prR
                        r.spearmanRho = rho
                        r.ciLow = AMath.tanh(z - z975 * se)
                        r.ciHigh = AMath.tanh(z + z975 * se)
                        r.p = 2.0 * (1.0 - AMath.normalCDF(abs(z) / se))
                        tested.append(results.count)
                    }
                }
                results.append(r)
            }
        }
        let qs = AMath.bhQValues(tested.map { results[$0].p! })
        let qLevel = cc["q_level"].double ?? 0.1
        let minRho = cc["min_abs_rho"].double ?? 0.3
        for (k, i) in tested.enumerated() {
            results[i].q = qs[k]
            results[i].surfaced = qs[k] <= qLevel && abs(results[i].spearmanRho!) >= minRho
            if results[i].surfaced, let m = meta[results[i].id] {
                let rho = results[i].spearmanRho!
                results[i].text = (cc["template"].string ?? "")
                    .replacingOccurrences(of: "{exposure}", with: m["exposure_label"].string ?? "")
                    .replacingOccurrences(of: "{direction}", with: cc["direction_words"][rho > 0 ? "higher" : "lower"].string ?? "")
                    .replacingOccurrences(of: "{outcome}", with: m["outcome_label"].string ?? "")
                    .replacingOccurrences(of: "{lag}", with: cc["lag_words"][String(results[i].lag)].string ?? "")
                    .replacingOccurrences(of: "{n}", with: String(results[i].n))
            }
        }
        let rows: [AJ] = results.map { r in
            .obj(["id": .str(r.id), "lag": .i(r.lag), "n": .i(r.n), "status": .str(r.status),
                  "pearson_r": .n(AMath.roundTo(r.pearsonR, 3)), "spearman_rho": .n(AMath.roundTo(r.spearmanRho, 3)),
                  "ci_low": .n(AMath.roundTo(r.ciLow, 3)), "ci_high": .n(AMath.roundTo(r.ciHigh, 3)),
                  "p": .n(AMath.roundTo(r.p, 4)), "q": .n(AMath.roundTo(r.q, 4)), "surfaced": .bool(r.surfaced),
                  "text": .s(r.text)])
        }
        return .obj(["algorithm_id": cc["algorithm_id"], "algorithm_version": cc["algorithm_version"], "as_of": .str(asOf),
                     "tested": .i(tested.count), "results": .arr(rows), "classification": .str("PERSONALIZED_STATISTICAL")])
    }

    static func response(exposure: [String: Double], outcome: [String: Double], lag: Int, asOf: String, window: Int?,
                         _ cfg: AnalyticsConfig) -> AJ {
        let rc = cfg["response"]
        let pts = paired(exposure, outcome, lag: lag, asOf: asOf, window: window ?? (cfg["correlation"]["window_days"].int ?? 120))
        var out: [String: AJ] = ["algorithm_id": rc["algorithm_id"], "algorithm_version": rc["algorithm_version"],
                                 "status": .str("INSUFFICIENT_DATA"), "n": .i(pts.count), "lag": .i(lag), "effect": .null,
                                 "intercept": .null, "ci_low": .null, "ci_high": .null, "excludes_zero": .bool(false),
                                 "confidence": .num(0), "classification": .str("PERSONALIZED_STATISTICAL")]
        if pts.count < (rc["min_n"].int ?? 28) { return .obj(out) }
        let (bOpt, a, slopes) = AMath.theilSen(pts)
        guard let b = bOpt else { return .obj(out) }
        let n = Double(pts.count)
        let s = slopes.sorted()
        let bigN = Double(s.count)
        let c = (rc["z_975"].double ?? 1.959964) * (n * (n - 1) * (2 * n + 5) / 18.0).squareRoot()
        let lo = Int(AMath.clamp(((bigN - c) / 2.0).rounded(.down), 0, bigN - 1))
        let hi = Int(AMath.clamp(((bigN + c) / 2.0).rounded(.up), 0, bigN - 1))
        let conf = AMath.clamp(n / 60.0, 0.0, 1.0) * cfg.cap("PERSONALIZED_STATISTICAL")
        out["status"] = .str(AnalyticsCore.statusFor(conf, cfg))
        out["effect"] = .num(AMath.roundTo(b, 4))
        out["intercept"] = .n(AMath.roundTo(a, 3))
        out["ci_low"] = .num(AMath.roundTo(s[lo], 4))
        out["ci_high"] = .num(AMath.roundTo(s[hi], 4))
        out["excludes_zero"] = .bool(s[lo] > 0 || s[hi] < 0)
        out["confidence"] = .num(AMath.roundTo(conf, 2))
        return .obj(out)
    }

    // MARK: Energy

    struct EnergyInput: Sendable {
        var providerBasalKcal: Double?
        var providerActiveKcal: Double?
        var providerWorkoutKcal: Double?
        var ayuvoSessions: [(startMs: Int64, endMs: Int64, kcal: Double?)] = []
        var providerWorkouts: [(startMs: Int64, endMs: Int64)] = []
        var weightKg: Double?
        var heightCm: Double?
        var age: Double?
        var sex: String?
    }

    static func energy(_ inp: EnergyInput, _ cfg: AnalyticsConfig) -> AJ {
        let ec = cfg["energy"]
        var pred: Double?
        if let w = inp.weightKg, w != 0, let h = inp.heightCm, h != 0, let age = inp.age {
            let k = ec["mifflin"][inp.sex ?? "other"].double ?? ec["mifflin"]["other"].double ?? 0
            pred = 10.0 * w + 6.25 * h - 5.0 * age + k
        }
        var out: [String: AJ] = ["algorithm_id": ec["algorithm_id"], "algorithm_version": ec["algorithm_version"],
                                 "status": .str("NO_DATA"), "predicted_resting_kcal": .n(AMath.roundTo(pred, 0)),
                                 "provider_basal_kcal": .null, "provider_active_kcal": .null,
                                 "provider_workout_kcal": .null, "ayuvo_extra_kcal": .num(0), "resting_kcal": .null,
                                 "resting_source": .null, "active_kcal": .null, "estimated_daily_expenditure": .null,
                                 "confidence": .num(0), "classification": .str("SCIENTIFIC_DERIVED")]
        let basal = inp.providerBasalKcal, active = inp.providerActiveKcal
        for v in [basal, active, inp.providerWorkoutKcal] {
            if let v, v < 0 {
                out["status"] = .str("INVALID_INPUT")
                return .obj(out)
            }
        }
        out["provider_basal_kcal"] = .n(AMath.roundTo(basal, 0))
        out["provider_active_kcal"] = .n(AMath.roundTo(active, 0))
        out["provider_workout_kcal"] = .n(AMath.roundTo(inp.providerWorkoutKcal, 0))
        var extra = 0.0
        for s in inp.ayuvoSessions {
            let covered = inp.providerWorkouts.contains { $0.startMs < s.endMs && s.startMs < $0.endMs }
            if !covered, let k = s.kcal, k > 0 { extra += k }
        }
        out["ayuvo_extra_kcal"] = .num(AMath.roundTo(extra, 0))
        let c = ec["confidence"]
        var rest: Double, src: String, cRest: Double
        if let basal, basal > 0, pred == nil || basal >= (ec["bmr_min_share"].double ?? 0.7) * pred! {
            (rest, src, cRest) = (basal, "provider", c["provider_resting"].double ?? 0.8)
        } else if let pred {
            (rest, src, cRest) = (pred, "predicted", c["predicted_resting"].double ?? 0.6)
        } else {
            return .obj(out)
        }
        out["resting_kcal"] = .num(AMath.roundTo(rest, 0))
        out["resting_source"] = .str(src)
        guard let active else {
            out["status"] = .str("INSUFFICIENT_DATA")
            return .obj(out)
        }
        let act = active + extra
        let tdee = (rest + act) / (1.0 - (ec["tef_share"].double ?? 0.1))
        let conf = cRest * (c["provider_active"].double ?? 1) * cfg.cap("SCIENTIFIC_DERIVED")
        out["status"] = .str(AnalyticsCore.statusFor(conf, cfg))
        out["active_kcal"] = .num(AMath.roundTo(act, 0))
        out["estimated_daily_expenditure"] = .num(AMath.roundTo(tdee, 0))
        out["confidence"] = .num(AMath.roundTo(conf, 2))
        return .obj(out)
    }

    // MARK: VO2 max trend

    static func vo2maxTrend(readings: [String: [String: Double]], day: String, _ cfg: AnalyticsConfig) -> AJ {
        let fc = cfg["fitness"]
        var kinds: [String: AJ] = [:]
        var primary: String?
        let window = AMath.windowDays(day, -((fc["window_days"].int ?? 365) - 1), 0)
        for kindJ in fc["kinds"].array {
            let kind = kindJ.string ?? ""
            let s = readings[kind] ?? [:]
            let days = window.filter { s[$0] != nil }
            var r: [String: AJ] = ["n": .i(days.count), "latest": .null, "latest_day": .null, "change": .null,
                                   "slope_per_30d": .null, "classification": fc["kind_classification"][kind],
                                   "status": .str("NO_DATA")]
            if let last = days.last {
                r["latest"] = .num(AMath.roundTo(s[last]!, 1))
                r["latest_day"] = .str(last)
                r["status"] = .str(kind == "provider" ? "PROVIDER_REPORTED" : "VALID")
                let gap = fc["change_min_gap_days"].int ?? 60
                let old = days.filter { AMath.between($0, last) >= gap }.map { s[$0]! }
                if !old.isEmpty { r["change"] = .num(AMath.roundTo(s[last]! - AMath.median(old), 1)) }
                if days.count >= (fc["slope_min_points"].int ?? 4) {
                    let b = AMath.theilSen(days.map { (Double(AMath.between(days[0], $0)), s[$0]!) }).0
                    r["slope_per_30d"] = .n(b.map { AMath.roundTo($0 * 30.0, 2) })
                }
                if kind == "uth" { r["status"] = .str("EXPERIMENTAL") }
                if primary == nil { primary = kind }
            }
            kinds[kind] = .obj(r)
        }
        return .obj(["algorithm_id": fc["algorithm_id"], "algorithm_version": fc["algorithm_version"], "day": .str(day),
                     "kinds": .obj(kinds), "primary": .s(primary)])
    }

    // MARK: MET intensity

    struct Activity: Sendable {
        var startMs: Int64
        var endMs: Int64
        var key: String?
    }

    static func metIntensity(activities: [Activity], day: String, timeZone: String, _ cfg: AnalyticsConfig) -> AJ {
        let mc = cfg["met"]
        let acts = activities.enumerated().filter { $0.element.endMs > $0.element.startMs }.sorted { a, b in
            if a.element.startMs != b.element.startMs { return a.element.startMs < b.element.startMs }
            if a.element.endMs != b.element.endMs { return a.element.endMs < b.element.endMs }
            return a.offset < b.offset
        }.map(\.element)
        var merged: [(startMs: Int64, endMs: Int64, met: Double?, key: String?)] = []
        for a in acts {
            let met = mc["table"][a.key ?? ""]["met"].double
            if let last = merged.last, a.startMs < last.endMs {
                var m = last
                m.endMs = max(m.endMs, a.endMs)
                if let met, m.met == nil || met > m.met! {
                    m.met = met
                    m.key = a.key
                }
                merged[merged.count - 1] = m
            } else {
                merged.append((a.startMs, a.endMs, met, a.key))
            }
        }
        let first = AMath.addDays(day, -((mc["window_days"].int ?? 7) - 1))
        var light = 0.0, moderate = 0.0, vigorous = 0.0, unknown = 0.0, metMin = 0.0
        var sessions = 0
        let vig = mc["bands"]["vigorous_min"].double ?? 6, mod = mc["bands"]["moderate_min"].double ?? 3
        for m in merged {
            let d = AMath.localDayOf(m.startMs, timeZone)
            if d < first || d > day { continue }
            let mins = Double(m.endMs - m.startMs) / 60000.0
            sessions += 1
            guard let met = m.met else {
                unknown += mins
                continue
            }
            metMin += met * mins
            if met >= vig { vigorous += mins } else if met >= mod { moderate += mins } else { light += mins }
        }
        let modEq = moderate + 2.0 * vigorous
        return .obj([
            "algorithm_id": mc["algorithm_id"], "algorithm_version": mc["algorithm_version"], "day": .str(day),
            "light_min": .num(AMath.roundTo(light, 1)), "moderate_min": .num(AMath.roundTo(moderate, 1)),
            "vigorous_min": .num(AMath.roundTo(vigorous, 1)), "unknown_min": .num(AMath.roundTo(unknown, 1)),
            "met_minutes": .num(AMath.roundTo(metMin, 1)), "moderate_equivalent_min": .num(AMath.roundTo(modEq, 1)),
            "meets_who": .bool(modEq >= (mc["who_weekly_moderate_equivalent_min"].double ?? 150)),
            "sessions": .i(sessions), "classification": .str("SCIENTIFIC_DERIVED"),
        ])
    }
}
