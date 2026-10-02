import Foundation

// Pulses, IBI, HRV and respiration: port of the "Pulses, IBI, HRV" and "Respiration" sections of
// `scripts/vitals_reference.py`.

nonisolated extension VitalsEngine {
    private typealias M = VitalsMath

    /// One detected systolic peak. `tMs` is relative to sample 0.
    struct Peak: Sendable {
        var i: Int
        var tMs: Double
        var amp: Double
        var footI: Int
        var foot: Double
        var corr: Double?
        var masked: Bool
    }

    /// `ibi_clean` output: rounded IBIs with per-IBI quality and acceptance.
    struct IbiSeries: Sendable {
        var ibiMs: [Double]
        var ibiTMs: [Double]
        var ibiQuality: [Double]
        var accepted: [Bool]
        var acceptedCount: Int
        var acceptedFraction: Double
    }

    struct HrvTime: Sendable {
        var rmssd: Double
        var sdnn: Double?
        var meanNN: Double
        var pnn50: Double
        var n: Int
    }

    struct HrvFreq: Sendable {
        var lfHf: Double
        var lfNu: Double
        var hfNu: Double
    }

    /// Elgendi two-moving-average systolic peak detector with parabolic sub-sample timing, feet, and per-beat
    /// template correlation (`pulse_detect`).
    static func pulseDetect(_ x: [Double], _ fs: Double, _ mask: [Bool], _ cfg: VitalsConfig,
                            hrHintBpm: Double? = nil) -> [Peak] {
        let sig = cfg.signal
        let n = x.count
        var y: [Double] = []
        y.reserveCapacity(n)
        for v in x { y.append(v > 0.0 ? v * v : 0.0) }
        let maPeak = movingAverage(y, M.oddWindow(sig.peakWindowS, fs))
        let maBeat = movingAverage(y, M.oddWindow(sig.beatWindowS, fs))
        let offset = sig.peakOffset * M.mean(y)
        let w1 = M.oddWindow(sig.peakWindowS, fs)
        var cands: [Int] = []
        var i = 0
        while i < n {
            if maPeak[i] > maBeat[i] + offset {
                var k = i
                while k < n && maPeak[k] > maBeat[k] + offset { k += 1 }
                if k - i >= w1 {
                    var best = i
                    for m in i..<k where x[m] > x[best] { best = m }
                    cands.append(best)
                }
                i = k
            } else {
                i += 1
            }
        }
        var refractory = sig.peakRefractoryS * fs
        if let hint = hrHintBpm, hint > 0.0 {
            let adaptive = sig.refractoryFraction * 60.0 / hint * fs
            if adaptive > refractory { refractory = adaptive }
        }
        var idx: [Int] = []
        for c in cands {
            if let last = idx.last, Double(c - last) < refractory {
                if x[c] > x[last] { idx[idx.count - 1] = c }
            } else {
                idx.append(c)
            }
        }
        var peaks: [Peak] = []
        peaks.reserveCapacity(idx.count)
        for k in 0..<idx.count {
            let p = idx[k]
            var delta = 0.0
            var amp = x[p]
            if 0 < p && p < n - 1 {
                let a = x[p - 1], b = x[p], c = x[p + 1]
                let den = a - 2.0 * b + c
                if den != 0.0 {
                    delta = M.clamp(0.5 * (a - c) / den, -0.5, 0.5)
                    amp = b - 0.25 * (a - c) * delta
                }
            }
            var lo = k > 0 ? idx[k - 1] : p - M.floorInt(0.6 * fs)
            if lo < 0 { lo = 0 }
            var footI = p
            var m = lo
            while m <= p {
                if x[m] < x[footI] { footI = m }
                m += 1
            }
            peaks.append(Peak(i: p, tMs: (Double(p) + delta) * 1000.0 / fs, amp: amp, footI: footI, foot: x[footI],
                              corr: nil, masked: mask[p]))
        }
        if peaks.count >= 3 {
            var diffs: [Double] = []
            for k in 1..<idx.count { diffs.append(Double(idx[k] - idx[k - 1])) }
            let med = M.median(diffs)
            let before = M.floorInt(0.3 * med + 0.5)
            let after = M.floorInt(0.6 * med + 0.5)
            var segs: [[Double]] = [], owners: [Int] = []
            for k in 0..<peaks.count {
                let p = peaks[k].i
                if p - before >= 0 && p + after < n {
                    segs.append(Array(x[(p - before)...(p + after)]))
                    owners.append(k)
                }
            }
            if segs.count >= 2 {
                let length = before + after + 1
                var template: [Double] = []
                template.reserveCapacity(length)
                for j in 0..<length {
                    var acc = 0.0
                    for s in segs { acc += s[j] }
                    template.append(acc / Double(segs.count))
                }
                for q in 0..<segs.count {
                    peaks[owners[q]].corr = M.pearson(segs[q], template)
                }
            }
        }
        return peaks
    }

    /// IBIs between consecutive peaks with per-IBI quality (`ibi_clean`).
    static func ibiClean(_ peaks: [Peak], _ cfg: VitalsConfig) -> IbiSeries {
        let ib = cfg.ibi
        var raw: [Double] = [], times: [Double] = [], baseQ: [Double?] = []
        if peaks.count > 1 {
            for k in 1..<peaks.count {
                let a = peaks[k - 1], b = peaks[k]
                raw.append(b.tMs - a.tMs)
                times.append(b.tMs)
                let ca = a.corr ?? 0.8
                let cb = b.corr ?? 0.8
                let q = ca < cb ? ca : cb
                let bad = a.masked || b.masked || q < ib.minTemplateCorr
                baseQ.append(bad ? nil : M.clamp(q, 0.0, 1.0))
            }
        }
        var inRange: [Bool] = []
        for v in raw { inRange.append(ib.minMs <= v && v <= ib.maxMs) }
        var quality: [Double] = [], accepted: [Bool] = []
        let h = ib.medianWindow / 2
        for k in 0..<raw.count {
            var ok = inRange[k] && baseQ[k] != nil
            if ok {
                var local: [Double] = []
                for m in (k - h)...(k + h) where 0 <= m && m < raw.count && inRange[m] {
                    local.append(raw[m])
                }
                let med = M.median(local)
                if abs(raw[k] - med) / med > ib.maxRelDeviation { ok = false }
            }
            accepted.append(ok)
            quality.append(ok ? M.roundTo(baseQ[k]!, 2) : 0.0)
        }
        var count = 0
        for a in accepted where a { count += 1 }
        return IbiSeries(ibiMs: M.roundList(raw, 1), ibiTMs: M.roundList(times, 1), ibiQuality: quality,
                         accepted: accepted, acceptedCount: count,
                         acceptedFraction: raw.isEmpty ? 0.0 : M.roundTo(Double(count) / Double(raw.count), 3))
    }

    static func hrvTime(ibiMs: [Double], accepted: [Bool]) -> HrvTime? {
        var acc: [Double] = [], diffs: [Double] = []
        for k in 0..<ibiMs.count where accepted[k] {
            acc.append(ibiMs[k])
            if k > 0 && accepted[k - 1] { diffs.append(ibiMs[k] - ibiMs[k - 1]) }
        }
        if acc.count < 2 || diffs.isEmpty { return nil }
        var sq = 0.0
        var over = 0
        for d in diffs {
            sq += d * d
            if abs(d) > 50.0 { over += 1 }
        }
        return HrvTime(rmssd: (sq / Double(diffs.count)).squareRoot(), sdnn: M.sampleStd(acc), meanNN: M.mean(acc),
                       pnn50: 100.0 * Double(over) / Double(diffs.count), n: acc.count)
    }

    static func lombScargle(_ tS: [Double], _ y: [Double], _ freqs: [Double]) -> [Double] {
        var power: [Double] = []
        power.reserveCapacity(freqs.count)
        for f in freqs {
            let w = M.twoPi * f
            var s2 = 0.0, c2 = 0.0
            for t in tS {
                s2 += sin(2.0 * w * t)
                c2 += cos(2.0 * w * t)
            }
            let tau = atan2(s2, c2) / (2.0 * w)
            var yc = 0.0, ys = 0.0, cc = 0.0, ss = 0.0
            for k in 0..<tS.count {
                let c = cos(w * (tS[k] - tau))
                let s = sin(w * (tS[k] - tau))
                yc += y[k] * c
                ys += y[k] * s
                cc += c * c
                ss += s * s
            }
            var p = 0.0
            if cc > 0.0 { p += yc * yc / cc }
            if ss > 0.0 { p += ys * ys / ss }
            power.append(0.5 * p)
        }
        return power
    }

    static func hrvFreq(ibiMs: [Double], ibiTMs: [Double], accepted: [Bool], _ cfg: VitalsConfig) -> HrvFreq? {
        let hv = cfg.hrv
        var tS: [Double] = [], vals: [Double] = []
        for k in 0..<ibiMs.count where accepted[k] {
            tS.append(ibiTMs[k] / 1000.0)
            vals.append(ibiMs[k])
        }
        if Double(vals.count) < hv.minBeats { return nil }
        let m = M.mean(vals)
        var y: [Double] = []
        for v in vals { y.append(v - m) }
        let freqs = freqGrid(hv.lfBand[0], hv.hfBand[1], hv.freqGridStepHz)
        let power = lombScargle(tS, y, freqs)
        var lf = 0.0, hf = 0.0
        for i in 0..<freqs.count {
            if freqs[i] < hv.lfBand[1] - 1e-9 {
                lf += power[i]
            } else {
                hf += power[i]
            }
        }
        if lf + hf <= 0.0 || hf <= 0.0 { return nil }
        return HrvFreq(lfHf: lf / hf, lfNu: 100.0 * lf / (lf + hf), hfNu: 100.0 * hf / (lf + hf))
    }

    // MARK: Respiration

    static func respFromSeries(_ tMs: [Double], _ values: [Double], _ cfg: VitalsConfig) -> Double? {
        let rp = cfg.respiration
        if values.count < 4 { return nil }
        let fs = rp.resampleHz
        let valid = [Bool](repeating: true, count: values.count)
        let grid = resampleUniform(tMs, values, valid, fs, 1e12).values
        if grid.count < 8 { return nil }
        let m = M.mean(grid)
        var y: [Double] = []
        y.reserveCapacity(grid.count)
        for k in 0..<grid.count {
            let win = 0.5 - 0.5 * cos(M.twoPi * Double(k) / Double(grid.count - 1))
            y.append((grid[k] - m) * win)
        }
        let freqs = freqGrid(rp.bandHz[0], rp.bandHz[1], rp.gridStepHz)
        var power: [Double] = []
        power.reserveCapacity(freqs.count)
        for f in freqs {
            var re = 0.0, im = 0.0
            for k in 0..<y.count {
                let ang = M.twoPi * f * Double(k) / fs
                re += y[k] * cos(ang)
                im += y[k] * sin(ang)
            }
            power.append(re * re + im * im)
        }
        return spectralPeak(freqs, power) * 60.0
    }

    struct RespResult: Sendable {
        var value: Double?
        var estimates: [Double]
        var spread: Double?
    }

    /// Smart fusion (Karlen 2013) of respiratory modulation series `[(t_ms, values)]`: the estimates must agree within
    /// agreement_per_min (sample SD), else unavailable.
    static func respRate(_ seriesList: [(tMs: [Double], values: [Double])], _ cfg: VitalsConfig) -> RespResult {
        var est: [Double] = []
        for s in seriesList {
            if let r = respFromSeries(s.tMs, s.values, cfg) { est.append(r) }
        }
        if est.count < 2 { return RespResult(value: nil, estimates: M.roundList(est, 1), spread: nil) }
        let spread = M.sampleStd(est)!
        let value: Double? = spread <= cfg.respiration.agreementPerMin ? M.mean(est) : nil
        return RespResult(value: value, estimates: M.roundList(est, 1), spread: spread)
    }
}
