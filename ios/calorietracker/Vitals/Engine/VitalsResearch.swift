import Foundation

// Experimental SpO2 and research blood pressure (Milestone 4): port of that section of `scripts/vitals_reference.py`.
// Both are unavailable unless the user has a personal calibration; neither is ever written to Health.

nonisolated extension VitalsEngine {
    private typealias M = VitalsMath

    /// One personal SpO2 calibration pair for this device model: the scan's ratio of ratios and the oximeter reading.
    struct Spo2Calibration: Sendable, Equatable {
        var ratio: Double
        var spo2: Double
    }

    /// One cuff calibration: taken `scanGapMin` minutes from a finger scan whose `bp_features` are `features`.
    struct BpCalibration: Sendable, Equatable {
        var tMs: Double
        var scanGapMin: Double
        var features: [String: Double]?
        var sbp: Double
        var dbp: Double
    }

    struct Spo2Estimate: Sendable {
        var value: Double?
        var confidence: Double?
        var reason: String?
    }

    struct BpEstimate: Sendable {
        var sbp: Double?
        var dbp: Double?
        var confidence: Double?
        var reason: String?

        var json: RJ { .obj(["sbp": .f(sbp), "dbp": .f(dbp), "confidence": .f(confidence), "reason": .s(reason)]) }
    }

    /// Median over beats of (AC/DC)_ch1 / (AC/DC)_ch2, AC/DC from the normalised band-passed channels.
    static func spo2Ratio(_ channels: [String: [Double]], _ peaks: [Peak], _ fs: Double, _ cfg: VitalsConfig) -> Double? {
        var xs: [[Double]] = []
        for name in cfg.research.spo2Channels {
            xs.append(preprocess(channels[name] ?? [], fs, cfg, invert: true))
        }
        var ratios: [Double] = []
        for p in peaks {
            if p.masked || p.footI == p.i { continue }
            let a1 = xs[0][p.i] - xs[0][p.footI]
            let a2 = xs[1][p.i] - xs[1][p.footI]
            if a1 > 0.0 && a2 > 0.0 { ratios.append(a1 / a2) }
        }
        if ratios.count < 5 { return nil }
        return M.median(ratios)
    }

    static func linearFit(_ xs: [Double], _ ys: [Double]) -> (a: Double, b: Double, rmse: Double)? {
        let mx = M.mean(xs), my = M.mean(ys)
        var sxx = 0.0, sxy = 0.0
        for i in 0..<xs.count {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
        }
        if sxx < 1e-9 { return nil }
        let b = sxy / sxx
        let a = my - b * mx
        var res = 0.0
        for i in 0..<xs.count {
            let e = ys[i] - (a + b * xs[i])
            res += e * e
        }
        return (a, b, (res / Double(xs.count)).squareRoot())
    }

    /// Unavailable unless a personal calibration exists, the ratio lies inside its range and the result is plausible.
    static func spo2Estimate(ratio: Double?, quality: Double, calibrations cals: [Spo2Calibration],
                             _ cfg: VitalsConfig) -> Spo2Estimate {
        let rs = cfg.research
        guard let ratio else { return Spo2Estimate(value: nil, confidence: nil, reason: "low_quality") }
        if Double(cals.count) < rs.spo2MinCalibrations {
            return Spo2Estimate(value: nil, confidence: nil, reason: "needs_calibration")
        }
        var xs: [Double] = [], ys: [Double] = []
        for c in cals {
            xs.append(c.ratio)
            ys.append(c.spo2)
        }
        guard let fit = linearFit(xs, ys) else { return Spo2Estimate(value: nil, confidence: nil, reason: "needs_calibration") }
        let lo = M.minimum(xs) - rs.spo2MaxRMargin, hi = M.maximum(xs) + rs.spo2MaxRMargin
        if !(lo <= ratio && ratio <= hi) { return Spo2Estimate(value: nil, confidence: nil, reason: "outside_calibration") }
        let value = fit.a + fit.b * ratio
        if !(rs.spo2Range[0] <= value && value <= rs.spo2Range[1]) {
            return Spo2Estimate(value: nil, confidence: nil, reason: "outside_calibration")
        }
        let conf = quality / 100.0 * M.clamp(1.0 - fit.rmse / 3.0, 0.0, 1.0) * M.clamp(Double(cals.count) / 6.0, 0.0, 1.0)
        if conf < 0.5 { return Spo2Estimate(value: nil, confidence: nil, reason: "low_quality") }
        return Spo2Estimate(value: value, confidence: conf, reason: nil)
    }

    /// Median pulse morphology over clean beats (ms / bpm), rounded to 0.1. nil when fewer than 5 usable beats.
    static func bpFeatures(x: [Double], peaks: [Peak], fs: Double, hr: Double, _ cfg: VitalsConfig) -> [String: Double]? {
        var rise: [Double] = [], decay: [Double] = [], w50: [Double] = [], w25: [Double] = []
        if peaks.count > 1 {
            for k in 0..<(peaks.count - 1) {
                let p = peaks[k], nxt = peaks[k + 1]
                guard !p.masked, !nxt.masked, let corr = p.corr, !(corr < cfg.ibi.minTemplateCorr) else { continue }
                let f1 = p.footI, pi = p.i, f2 = nxt.footI
                if !(f1 < pi && pi < f2) { continue }
                let amp = x[pi] - x[f1]
                if amp <= 0.0 { continue }
                rise.append(Double(pi - f1) * 1000.0 / fs)
                decay.append(Double(f2 - pi) * 1000.0 / fs)
                var c50 = 0, c25 = 0
                for m in f1...f2 {
                    if x[m] >= x[f1] + 0.5 * amp { c50 += 1 }
                    if x[m] >= x[f1] + 0.25 * amp { c25 += 1 }
                }
                w50.append(Double(c50) * 1000.0 / fs)
                w25.append(Double(c25) * 1000.0 / fs)
            }
        }
        if rise.count < 5 { return nil }
        return ["rise_ms": M.roundTo(M.median(rise), 1), "decay_ms": M.roundTo(M.median(decay), 1),
                "width50_ms": M.roundTo(M.median(w50), 1), "width25_ms": M.roundTo(M.median(w25), 1),
                "hr": M.roundTo(hr, 1)]
    }

    /// Gaussian elimination with partial pivoting; nil when singular.
    static func solveLinear(_ a: [[Double]], _ b: [Double]) -> [Double]? {
        let n = b.count
        var m: [[Double]] = []
        for i in 0..<n { m.append(a[i] + [b[i]]) }
        for col in 0..<n {
            var piv = col
            for r in (col + 1)..<max(n, col + 1) where abs(m[r][col]) > abs(m[piv][col]) { piv = r }
            if abs(m[piv][col]) < 1e-12 { return nil }
            m.swapAt(col, piv)
            for r in (col + 1)..<max(n, col + 1) {
                let f = m[r][col] / m[col][col]
                for c in col...n { m[r][c] -= f * m[col][c] }
            }
        }
        var out = [Double](repeating: 0.0, count: n)
        var i = n - 1
        while i >= 0 {
            var acc = m[i][n]
            for c in (i + 1)..<max(n, i + 1) { acc -= m[i][c] * out[c] }
            out[i] = acc / m[i][i]
            i -= 1
        }
        return out
    }

    /// Per-user ridge regression from pulse morphology to cuff readings (research only).
    static func bpResearch(features: [String: Double]?, calibrations: [BpCalibration], nowMs: Double, quality: Double,
                           _ cfg: VitalsConfig) -> BpEstimate {
        let rs = cfg.research
        var none = BpEstimate(sbp: nil, dbp: nil, confidence: nil, reason: nil)
        let names = rs.bpFeatures
        // The reference raises KeyError on a feature dict missing a configured name; here such input counts as absent.
        func complete(_ f: [String: Double]?) -> Bool { f.map { f in names.allSatisfy { f[$0] != nil } } ?? false }
        guard let features, complete(features) else {
            none.reason = "few_beats"
            return none
        }
        let maxAge = rs.bpCalibrationMaxAgeDays * 86400000.0
        var cals: [BpCalibration] = []
        for c in calibrations {
            if !complete(c.features) || c.scanGapMin > rs.bpCalibrationMaxGapMin { continue }
            if nowMs - c.tMs > maxAge { continue }
            cals.append(c)
        }
        if Double(cals.count) < rs.bpMinCalibrations {
            none.reason = "needs_calibration"
            return none
        }
        var means: [Double] = [], sds: [Double] = []
        for name in names {
            var col: [Double] = []
            for c in cals { col.append(c.features![name]!) }
            means.append(M.mean(col))
            let s = M.std(col)
            sds.append(s > 1e-6 ? s : 0.0)
        }
        var z: [[Double]] = []
        for c in cals {
            var row: [Double] = []
            for j in 0..<names.count {
                row.append(sds[j] > 0.0 ? (c.features![names[j]]! - means[j]) / sds[j] : 0.0)
            }
            z.append(row)
        }
        var zc: [Double] = []
        for j in 0..<names.count {
            let zj = sds[j] > 0.0 ? (features[names[j]]! - means[j]) / sds[j] : 0.0
            if abs(zj) > rs.bpMaxAbsZ {
                none.reason = "outside_calibration"
                return none
            }
            zc.append(zj)
        }
        var preds: [String: Double] = [:]
        for target in ["sbp", "dbp"] {
            var ys: [Double] = []
            for c in cals { ys.append(target == "sbp" ? c.sbp : c.dbp) }
            let ym = M.mean(ys)
            let p = names.count
            var ata: [[Double]] = [], atb: [Double] = []
            for i in 0..<p {
                var row: [Double] = []
                for j in 0..<p {
                    var acc = 0.0
                    for r in 0..<z.count { acc += z[r][i] * z[r][j] }
                    row.append(acc + (i == j ? rs.bpRidgeLambda : 0.0))
                }
                ata.append(row)
                var acc = 0.0
                for r in 0..<z.count { acc += z[r][i] * (ys[r] - ym) }
                atb.append(acc)
            }
            guard let beta = solveLinear(ata, atb) else {
                none.reason = "needs_calibration"
                return none
            }
            var pred = ym
            for j in 0..<p { pred += beta[j] * zc[j] }
            preds[target] = pred
        }
        let sbp = preds["sbp"]!, dbp = preds["dbp"]!
        if !(70.0 <= sbp && sbp <= 200.0 && 40.0 <= dbp && dbp <= 130.0 && dbp < sbp) {
            none.reason = "outside_calibration"
            return none
        }
        let conf = M.clamp(Double(cals.count) / 10.0, 0.0, 0.5) * quality / 100.0
        return BpEstimate(sbp: M.roundTo(sbp, 0), dbp: M.roundTo(dbp, 0), confidence: conf, reason: nil)
    }
}
