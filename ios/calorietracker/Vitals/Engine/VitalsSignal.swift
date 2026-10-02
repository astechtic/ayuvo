import Foundation

// Resampling, masking, filtering, spectrum and SNR: port of the "Resampling, masking, filtering" and
// "Spectrum, SNR, heart rate" sections of `scripts/vitals_reference.py`.

nonisolated extension VitalsEngine {
    private typealias M = VitalsMath

    /// Linear interpolation of an irregular frame series onto t0 + i*1000/fs. A grid sample is masked when either
    /// neighbouring frame is invalid or the frames around it are more than maxGapMs apart.
    static func resampleUniform(_ tMs: [Double], _ values: [Double], _ valid: [Bool], _ fs: Double,
                                _ maxGapMs: Double) -> (values: [Double], mask: [Bool]) {
        let n = M.floorInt((tMs[tMs.count - 1] - tMs[0]) * fs / 1000.0) + 1
        var out: [Double] = [], mask: [Bool] = []
        out.reserveCapacity(max(n, 0))
        mask.reserveCapacity(max(n, 0))
        var j = 0
        var i = 0
        while i < n {
            let ti = tMs[0] + Double(i) * 1000.0 / fs
            while j + 1 < tMs.count - 1 && tMs[j + 1] < ti { j += 1 }
            if j + 1 >= tMs.count {
                out.append(values[j])
                mask.append(!valid[j])
                i += 1
                continue
            }
            let span = tMs[j + 1] - tMs[j]
            let frac: Double
            if span <= 0.0 {
                frac = 0.0
            } else {
                frac = M.clamp((ti - tMs[j]) / span, 0.0, 1.0)
            }
            out.append(values[j] + (values[j + 1] - values[j]) * frac)
            mask.append(!valid[j] || !valid[j + 1] || span > maxGapMs)
            i += 1
        }
        return (out, mask)
    }

    /// Replaces masked samples by linear interpolation between the nearest valid neighbours (edges hold).
    static func fillMasked(_ x: [Double], _ mask: [Bool]) -> [Double] {
        let n = x.count
        var out = x
        var last = -1
        var i = 0
        while i < n {
            if !mask[i] {
                last = i
                i += 1
                continue
            }
            var k = i
            while k < n && mask[k] { k += 1 }
            for m in i..<k {
                if last < 0 && k >= n {
                    out[m] = x[m]
                } else if last < 0 {
                    out[m] = x[k]
                } else if k >= n {
                    out[m] = x[last]
                } else {
                    out[m] = x[last] + (x[k] - x[last]) * Double(m - last) / Double(k - last)
                }
            }
            i = k
        }
        return out
    }

    /// Centred moving average with an odd window w, truncated at the edges (prefix sums).
    static func movingAverage(_ x: [Double], _ w: Int) -> [Double] {
        let n = x.count
        var prefix: [Double] = [0.0]
        prefix.reserveCapacity(n + 1)
        for v in x { prefix.append(prefix[prefix.count - 1] + v) }
        let h = w / 2
        var out: [Double] = []
        out.reserveCapacity(n)
        for i in 0..<n {
            let lo = i - h > 0 ? i - h : 0
            let hi = i + h < n - 1 ? i + h : n - 1
            out.append((prefix[hi + 1] - prefix[lo]) / Double(hi - lo + 1))
        }
        return out
    }

    /// x / moving-average(x) - 1 (relative AC around a 1.5 s baseline).
    static func normalize(_ x: [Double], _ fs: Double, _ windowS: Double) -> [Double] {
        let ma = movingAverage(x, M.oddWindow(windowS, fs))
        var out: [Double] = []
        out.reserveCapacity(x.count)
        for i in 0..<x.count {
            out.append(ma[i] != 0.0 ? x[i] / ma[i] - 1.0 : 0.0)
        }
        return out
    }

    /// RBJ biquad coefficients [b0, b1, b2, a1, a2] (normalised by a0), Q = 1/sqrt(2).
    static func biquad(_ kind: String, _ fHz: Double, _ fs: Double) -> [Double] {
        let w0 = M.twoPi * fHz / fs
        let cw = cos(w0), sw = sin(w0)
        let alpha = sw / (2.0 * 0.7071067811865476)
        let a0 = 1.0 + alpha
        let b: [Double]
        if kind == "low" {
            b = [(1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0]
        } else {
            b = [(1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0]
        }
        return [b[0] / a0, b[1] / a0, b[2] / a0, (-2.0 * cw) / a0, (1.0 - alpha) / a0]
    }

    static func applyBiquad(_ x: [Double], _ c: [Double]) -> [Double] {
        var out: [Double] = []
        out.reserveCapacity(x.count)
        var x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0
        let c0 = c[0], c1 = c[1], c2 = c[2], c3 = c[3], c4 = c[4]
        for v in x {
            let y = c0 * v + c1 * x1 + c2 * x2 - c3 * y1 - c4 * y2
            x2 = x1; x1 = v
            y2 = y1; y1 = y
            out.append(y)
        }
        return out
    }

    /// Zero-phase Butterworth (RBJ biquad) high-pass + low-pass, forward and backward, odd-reflection padding.
    static func bandpass(_ x: [Double], _ fs: Double, _ loHz: Double, _ hiHz: Double) -> [Double] {
        let n = x.count
        if n < 3 { return x }
        var pad = M.floorInt(fs + 0.5) * 3
        if pad > n - 1 { pad = n - 1 }
        var padded: [Double] = []
        padded.reserveCapacity(n + 2 * pad)
        var k = pad
        while k > 0 {
            padded.append(2.0 * x[0] - x[k])
            k -= 1
        }
        padded.append(contentsOf: x)
        if pad >= 1 {
            for k in 1...pad { padded.append(2.0 * x[n - 1] - x[n - 1 - k]) }
        }
        let hp = biquad("high", loHz, fs)
        let lp = biquad("low", hiHz, fs)
        var y = applyBiquad(applyBiquad(padded, hp), lp)
        y.reverse()
        y = applyBiquad(applyBiquad(y, hp), lp)
        y.reverse()
        return Array(y[pad..<(pad + n)])
    }

    /// Artifact detector: non-overlapping windows whose RMS exceeds artifact_rms_factor x the lower-quartile window
    /// RMS are masked.
    static func amplitudeMask(_ x: [Double], _ fs: Double, _ cfg: VitalsConfig) -> [Bool] {
        let sig = cfg.signal
        let n = x.count
        let w = M.floorInt(sig.artifactWindowS * fs + 0.5)
        var rms: [Double] = []
        var start = 0
        while start < n {
            let end = start + w < n ? start + w : n
            var acc = 0.0
            for k in start..<end { acc += x[k] * x[k] }
            rms.append((acc / Double(end - start)).squareRoot())
            start += w
        }
        let ordered = rms.sorted()
        let ref = ordered[M.floorInt(0.25 * Double(ordered.count - 1))]
        var out: [Bool] = []
        out.reserveCapacity(n)
        for k in 0..<n {
            out.append(ref > 0.0 && rms[k / w] > sig.artifactRmsFactor * ref)
        }
        return out
    }

    static func unionMask(_ a: [Bool], _ b: [Bool]) -> [Bool] {
        var out: [Bool] = []
        out.reserveCapacity(a.count)
        for i in 0..<a.count { out.append(a[i] || b[i]) }
        return out
    }

    static func maskedShare(_ mask: [Bool]) -> Double {
        var count = 0
        for m in mask where m { count += 1 }
        return Double(count) / Double(mask.count)
    }

    static func preprocess(_ x: [Double], _ fs: Double, _ cfg: VitalsConfig, invert: Bool) -> [Double] {
        let sig = cfg.signal
        var norm = normalize(x, fs, sig.detrendWindowS)
        if invert {
            for i in 0..<norm.count { norm[i] = -norm[i] }
        }
        return bandpass(norm, fs, sig.hrBandHz[0], sig.hrBandHz[1])
    }

    // MARK: Spectrum, SNR, heart rate

    static func freqGrid(_ lo: Double, _ hi: Double, _ step: Double) -> [Double] {
        let count = M.floorInt((hi - lo) / step + 1e-9) + 1
        var out: [Double] = []
        out.reserveCapacity(max(count, 0))
        var k = 0
        while k < count {
            out.append(lo + Double(k) * step)
            k += 1
        }
        return out
    }

    /// Welch power at the given frequencies (Hann window, mean removed per segment, direct DFT). The cos / sin of
    /// every (frequency, offset) angle is computed once per call: the angle does not depend on the segment, so the
    /// values and the accumulation order are exactly the reference's.
    static func welch(_ x: [Double], _ fs: Double, _ freqs: [Double], _ segmentS: Double, _ overlap: Double) -> [Double] {
        let n = x.count
        var seg = M.floorInt(segmentS * fs + 0.5)
        if seg > n { seg = n }
        var hop = M.floorInt(Double(seg) * (1.0 - overlap) + 0.5)
        if hop < 1 { hop = 1 }
        var window: [Double] = []
        window.reserveCapacity(seg)
        for k in 0..<max(seg, 0) {
            window.append(seg > 1 ? 0.5 - 0.5 * cos(M.twoPi * Double(k) / Double(seg - 1)) : 1.0)
        }
        let nf = freqs.count
        var cosT = [Double](repeating: 0, count: nf * max(seg, 0))
        var sinT = [Double](repeating: 0, count: nf * max(seg, 0))
        if seg + 0 <= n && seg > 0 {
            for fi in 0..<nf {
                let base = fi * seg
                for k in 0..<seg {
                    let ang = M.twoPi * freqs[fi] * Double(k) / fs
                    cosT[base + k] = cos(ang)
                    sinT[base + k] = sin(ang)
                }
            }
        }
        var power = [Double](repeating: 0.0, count: nf)
        var segments = 0
        var start = 0
        var y = [Double](repeating: 0, count: max(seg, 0))
        while start + seg <= n {
            let part = Array(x[start..<(start + seg)])
            let m = M.mean(part)
            for k in 0..<seg { y[k] = (part[k] - m) * window[k] }
            for fi in 0..<nf {
                var re = 0.0, im = 0.0
                let base = fi * seg
                for k in 0..<seg {
                    re += y[k] * cosT[base + k]
                    im += y[k] * sinT[base + k]
                }
                power[fi] += re * re + im * im
            }
            segments += 1
            start += hop
        }
        for fi in 0..<power.count { power[fi] = power[fi] / Double(segments) }
        return power
    }

    static func spectralPeak(_ freqs: [Double], _ power: [Double]) -> Double {
        var best = 0
        for i in 1..<max(power.count, 1) where power[i] > power[best] { best = i }
        var f = freqs[best]
        if 0 < best && best < power.count - 1 {
            let a = power[best - 1], b = power[best], c = power[best + 1]
            let den = a - 2.0 * b + c
            if den != 0.0 {
                f = f + 0.5 * (a - c) / den * (freqs[1] - freqs[0])
            }
        }
        return f
    }

    static func snrDb(_ freqs: [Double], _ power: [Double], _ f0: Double) -> Double {
        var sig = 0.0, noise = 0.0
        for i in 0..<freqs.count {
            let f = freqs[i]
            if abs(f - f0) <= 0.1 || abs(f - 2.0 * f0) <= 0.2 {
                sig += power[i]
            } else {
                noise += power[i]
            }
        }
        if sig <= 0.0 { return -30.0 }
        if noise <= 0.0 { return 30.0 }
        return M.clamp(10.0 * log10(sig / noise), -30.0, 30.0)
    }

    static func hrSpectrum(_ x: [Double], _ fs: Double, _ cfg: VitalsConfig) -> (hrBpm: Double, snrDb: Double) {
        let sig = cfg.signal
        let freqs = freqGrid(sig.hrBandHz[0], sig.hrBandHz[1], sig.spectrumStepHz)
        let power = welch(x, fs, freqs, sig.welchSegmentS, sig.welchOverlap)
        let f0 = spectralPeak(freqs, power)
        return (f0 * 60.0, snrDb(freqs, power, f0))
    }
}
