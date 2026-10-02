import Foundation

// Face rPPG: gates, the six rPPG methods per ROI and ROI fusion. Port of the "Face rPPG" section of
// `scripts/vitals_reference.py` (Jacobi and FastICA run fixed iteration counts so every port takes the same path).

nonisolated extension VitalsEngine {
    private typealias M = VitalsMath

    /// Per-frame face statistics (never images). `rois[name][i] = [r, g, b, skin_frac]`; `motion` is the mean landmark
    /// displacement since the previous frame divided by the inter-ocular distance.
    struct FaceFrames: Sendable {
        var tMs: [Double]
        var rois: [String: [[Double]]]
        var motion: [Double]
        var yaw: [Double]
        var pitch: [Double]
        var luma: [Double]
        var faceCount: [Double]
        var faceFraction: [Double]

        /// The reference's face frame encoding.
        var json: RJ {
            var rj: [String: RJ] = [:]
            for (name, rows) in rois { rj[name] = .arr(rows.map { r in .arr(r.map { RJ.num($0) }) }) }
            return .obj(["t_ms": .fs(tMs), "rois": .obj(rj), "motion": .fs(motion), "yaw": .fs(yaw), "pitch": .fs(pitch),
                         "luma": .fs(luma), "face_count": .arr(faceCount.map { .int(Int($0)) }),
                         "face_fraction": .fs(faceFraction)])
        }
    }

    /// nil when frame i passes every face gate, else the guidance key.
    static func faceFrameGate(_ face: FaceFrames, _ i: Int, _ roiNames: [String], _ cfg: VitalsConfig) -> String? {
        let g = cfg.face.gates
        if face.faceCount[i] == 0 { return "face_none" }
        if face.faceCount[i] > 1 { return "face_multiple" }
        if face.faceFraction[i] < g.minFaceFraction { return "face_far" }
        if face.faceFraction[i] > g.maxFaceFraction { return "face_near" }
        if abs(face.yaw[i]) > g.maxYawDeg || abs(face.pitch[i]) > g.maxPitchDeg { return "face_angle" }
        if face.luma[i] < g.lumaMin { return "light_dark" }
        if face.luma[i] > g.lumaMax { return "light_bright" }
        if face.motion[i] > g.maxMotion { return "hold_still" }
        var skin = 0.0
        for name in roiNames { skin += face.rois[name]![i][3] }
        if skin / Double(roiNames.count) < g.minSkinFraction { return "face_none" }
        return nil
    }

    /// Symmetric 3x3 eigen-decomposition, 12 fixed cyclic sweeps. Returns (values desc, vectors as rows), each
    /// vector's largest-magnitude component made positive.
    static func jacobiEigen(_ a: [[Double]]) -> (values: [Double], vectors: [[Double]]) {
        var m = [a[0], a[1], a[2]]
        var v: [[Double]] = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
        let pairsPQ = [(0, 1), (0, 2), (1, 2)]
        for _ in 0..<12 {
            for (p, q) in pairsPQ {
                if abs(m[p][q]) < 1e-300 { continue }
                let theta = (m[q][q] - m[p][p]) / (2.0 * m[p][q])
                let t = (theta >= 0.0 ? 1.0 : -1.0) / (abs(theta) + (theta * theta + 1.0).squareRoot())
                let c = 1.0 / (t * t + 1.0).squareRoot()
                let s = t * c
                for k in 0..<3 {
                    let mkp = m[k][p], mkq = m[k][q]
                    m[k][p] = c * mkp - s * mkq
                    m[k][q] = s * mkp + c * mkq
                }
                for k in 0..<3 {
                    let mpk = m[p][k], mqk = m[q][k]
                    m[p][k] = c * mpk - s * mqk
                    m[q][k] = s * mpk + c * mqk
                }
                for k in 0..<3 {
                    let vkp = v[k][p], vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
                }
            }
        }
        var pairs: [(value: Double, index: Int, vec: [Double])] = []
        for i in 0..<3 {
            var vec = [v[0][i], v[1][i], v[2][i]]
            var big = 0
            for k in 1..<3 where abs(vec[k]) > abs(vec[big]) { big = k }
            if vec[big] < 0.0 { vec = [-vec[0], -vec[1], -vec[2]] }
            pairs.append((m[i][i], i, vec))
        }
        // key=(-value, index): larger value first, then the original index.
        pairs.sort { x, y in
            if -x.value != -y.value { return -x.value < -y.value }
            return x.index < y.index
        }
        return (pairs.map(\.value), pairs.map(\.vec))
    }

    static func cov3(_ rows: [[Double]]) -> [[Double]] {
        let n = rows[0].count
        var c: [[Double]] = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
        for i in 0..<3 {
            for j in 0..<3 {
                var acc = 0.0
                let ri = rows[i], rj = rows[j]
                for k in 0..<n { acc += ri[k] * rj[k] }
                c[i][j] = acc / Double(n)
            }
        }
        return c
    }

    static func center(_ x: [Double]) -> [Double] {
        let m = M.mean(x)
        var out: [Double] = []
        out.reserveCapacity(x.count)
        for v in x { out.append(v - m) }
        return out
    }

    static func project(_ vec: [Double], _ rows: [[Double]]) -> [Double] {
        let r0 = rows[0], r1 = rows[1], r2 = rows[2]
        let v0 = vec[0], v1 = vec[1], v2 = vec[2]
        var out: [Double] = []
        out.reserveCapacity(r0.count)
        for k in 0..<r0.count { out.append(v0 * r0[k] + v1 * r1[k] + v2 * r2[k]) }
        return out
    }

    static func bestBySnr(_ cands: [[Double]], _ fs: Double, _ cfg: VitalsConfig) -> [Double]? {
        var best: [Double]?
        var bestSnr: Double?
        for c in cands {
            let s = hrSpectrum(c, fs, cfg).snrDb
            if bestSnr == nil || s > bestSnr! {
                best = c
                bestSnr = s
            }
        }
        return best
    }

    /// rgb: [R, G, B] uniformly sampled, gap-filled ROI means. Returns {method: band-passed pulse signal or nil},
    /// every output sign-aligned to the green method (pulse peaks positive).
    static func rppgMethods(_ rgb: [[Double]], _ fs: Double, _ cfg: VitalsConfig) -> [String: [Double]?] {
        let sig = cfg.signal
        let lo = sig.hrBandHz[0], hi = sig.hrBandHz[1]
        let n = rgb[0].count
        let w = M.oddWindow(sig.detrendWindowS, fs)
        var norm: [[Double]] = []
        for c in 0..<3 {
            let ma = movingAverage(rgb[c], w)
            var row: [Double] = []
            row.reserveCapacity(n)
            for k in 0..<n { row.append(ma[k] != 0.0 ? rgb[c][k] / ma[k] : 1.0) }
            norm.append(row)
        }
        var out: [String: [Double]?] = [:]
        var gInv: [Double] = []
        gInv.reserveCapacity(n)
        for k in 0..<n { gInv.append(1.0 - norm[1][k]) }
        out["green"] = bandpass(gInv, fs, lo, hi)
        var chroma: [Double] = []
        chroma.reserveCapacity(n)
        for k in 0..<n {
            let s = rgb[0][k] + rgb[1][k] + rgb[2][k]
            chroma.append(s != 0.0 ? rgb[1][k] / s : 0.0)
        }
        let maC = movingAverage(chroma, w)
        var cn: [Double] = []
        cn.reserveCapacity(n)
        for k in 0..<n { cn.append(maC[k] != 0.0 ? 1.0 - chroma[k] / maC[k] : 0.0) }
        out["normalized"] = bandpass(cn, fs, lo, hi)
        var xs: [Double] = [], ys: [Double] = []
        xs.reserveCapacity(n)
        ys.reserveCapacity(n)
        for k in 0..<n {
            xs.append(3.0 * norm[0][k] - 2.0 * norm[1][k])
            ys.append(1.5 * norm[0][k] + norm[1][k] - 1.5 * norm[2][k])
        }
        let xf = bandpass(xs, fs, lo, hi), yf = bandpass(ys, fs, lo, hi)
        let sy = M.std(yf)
        let alpha = sy > 0.0 ? M.std(xf) / sy : 0.0
        var chrom: [Double] = []
        chrom.reserveCapacity(n)
        for k in 0..<n { chrom.append(xf[k] - alpha * yf[k]) }
        out["chrom"] = chrom
        let l = M.floorInt(cfg.face.posWindowS * fs + 0.5)
        var h = [Double](repeating: 0.0, count: n)
        if n >= l {
            var s1 = [Double](repeating: 0, count: l), s2 = [Double](repeating: 0, count: l)
            var hw = [Double](repeating: 0, count: l)
            for m in 0...(n - l) {
                var means: [Double] = []
                for c in 0..<3 {
                    var acc = 0.0
                    for k in m..<(m + l) { acc += rgb[c][k] }
                    means.append(acc / Double(l))
                }
                if means[0] == 0.0 || means[1] == 0.0 || means[2] == 0.0 { continue }
                for k in m..<(m + l) {
                    let rn = rgb[0][k] / means[0], gn = rgb[1][k] / means[1], bn = rgb[2][k] / means[2]
                    s1[k - m] = gn - bn
                    s2[k - m] = gn + bn - 2.0 * rn
                }
                let sd2 = M.std(s2)
                let a = sd2 > 0.0 ? M.std(s1) / sd2 : 0.0
                for k in 0..<l { hw[k] = s1[k] + a * s2[k] }
                let hm = M.mean(hw)
                for k in 0..<l { h[m + k] += hw[k] - hm }
            }
        }
        out["pos"] = bandpass(h, fs, lo, hi)
        var bpRows: [[Double]] = []
        for c in 0..<3 { bpRows.append(center(bandpass(norm[c], fs, lo, hi))) }
        let (values, vectors) = jacobiEigen(cov3(bpRows))
        var pcs: [[Double]] = []
        for vec in vectors { pcs.append(project(vec, bpRows)) }
        out["pca"] = bestBySnr(pcs, fs, cfg)
        out["ica"] = .some(nil)
        if values[2] > 1e-14 {
            var white: [[Double]] = []
            for i in 0..<3 {
                var row: [Double] = []
                for j in 0..<3 { row.append(vectors[i][j] / values[i].squareRoot()) }
                white.append(row)
            }
            var z: [[Double]] = []
            for i in 0..<3 { z.append(project(white[i], bpRows)) }
            var wm: [[Double]] = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
            let dn = Double(n)
            let z0 = z[0], z1 = z[1], z2 = z[2]
            for _ in 0..<60 {
                var new: [[Double]] = []
                for i in 0..<3 {
                    let wx = project(wm[i], z)
                    var g0 = 0.0, g1 = 0.0, g2 = 0.0
                    var dsum = 0.0
                    for k in 0..<n {
                        // Cube nonlinearity: + * only, bit-identical on every platform (see the reference).
                        let g = wx[k] * wx[k] * wx[k]
                        dsum += 3.0 * wx[k] * wx[k]
                        g0 += z0[k] * g
                        g1 += z1[k] * g
                        g2 += z2[k] * g
                    }
                    let gsum = [g0, g1, g2]
                    var row: [Double] = []
                    for j in 0..<3 { row.append(gsum[j] / dn - dsum / dn * wm[i][j]) }
                    new.append(row)
                }
                var wwt: [[Double]] = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
                for i in 0..<3 {
                    for j in 0..<3 {
                        var acc = 0.0
                        for k in 0..<3 { acc += new[i][k] * new[j][k] }
                        wwt[i][j] = acc
                    }
                }
                let (ev, evec) = jacobiEigen(wwt)
                if ev[2] <= 1e-300 { break }
                var invSqrt: [[Double]] = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
                for i in 0..<3 {
                    for j in 0..<3 {
                        var acc = 0.0
                        for k in 0..<3 { acc += evec[k][i] * evec[k][j] / ev[k].squareRoot() }
                        invSqrt[i][j] = acc
                    }
                }
                wm = []
                for i in 0..<3 {
                    var row: [Double] = []
                    for j in 0..<3 {
                        var acc = 0.0
                        for k in 0..<3 { acc += invSqrt[i][k] * new[k][j] }
                        row.append(acc)
                    }
                    wm.append(row)
                }
            }
            var ics: [[Double]] = []
            for i in 0..<3 { ics.append(project(wm[i], z)) }
            out["ica"] = bestBySnr(ics, fs, cfg)
        }
        let ref = out["green"]!!
        for name in cfg.face.methods {
            guard name != "green", let entry = out[name], let s = entry else { continue }
            if M.pearson(s, ref) < 0.0 {
                var flipped: [Double] = []
                flipped.reserveCapacity(s.count)
                for v in s { flipped.append(-v) }
                out[name] = flipped
            }
        }
        return out
    }

    struct RoiCombination: Sendable {
        var method: String
        var rois: [String]
        var weights: [String: Double]
        var snrDb: [String: [String: Double?]]
        var signal: [Double]
    }

    /// per_roi: {roi: {method: signal}}. Picks the method with the highest median SNR across ROIs (config order breaks
    /// ties), then SNR-weights (linear power ratio) the unit-variance ROI signals at or above min_roi_snr_db.
    static func roiCombine(_ perRoi: [String: [String: [Double]?]], _ fs: Double, _ cfg: VitalsConfig) -> RoiCombination {
        let rois = perRoi.keys.sorted()
        let methods = cfg.face.methods
        var table: [String: [String: Double?]] = [:]
        for roi in rois {
            var row: [String: Double?] = [:]
            for name in methods {
                if let entry = perRoi[roi]?[name], let s = entry {
                    row[name] = .some(hrSpectrum(s, fs, cfg).snrDb)
                } else {
                    row[name] = .some(nil)
                }
            }
            table[roi] = row
        }
        var bestM: String?
        var bestMed: Double?
        for name in methods {
            var vals: [Double] = []
            for roi in rois {
                if let v = table[roi]![name]! { vals.append(v) }
            }
            if vals.isEmpty { continue }
            let med = M.median(vals)
            if bestMed == nil || med > bestMed! {
                bestM = name
                bestMed = med
            }
        }
        let method = bestM!
        func snr(_ roi: String) -> Double? { table[roi]![method]! }
        var used: [String] = []
        for roi in rois {
            if let v = snr(roi), v >= cfg.face.minRoiSnrDb { used.append(roi) }
        }
        if used.isEmpty {
            var top = rois[0]
            for roi in rois {
                // The reference compares against table[top] directly (a TypeError if that is None); None ranks lowest.
                if let v = snr(roi), v > (snr(top) ?? -Double.infinity) { top = roi }
            }
            used = [top]
        }
        let n = (perRoi[used[0]]![method]!)!.count
        var combined = [Double](repeating: 0.0, count: n)
        var wsum = 0.0
        var weights: [String: Double] = [:]
        for roi in used {
            let s = (perRoi[roi]![method]!)!
            let sd = M.std(s)
            let w = pow(10.0, snr(roi)! / 10.0)
            weights[roi] = w
            wsum += w
            for k in 0..<n { combined[k] += w * (sd > 0.0 ? s[k] / sd : 0.0) }
        }
        for k in 0..<n { combined[k] = combined[k] / wsum }
        var snrOut: [String: [String: Double?]] = [:]
        for roi in rois {
            var row: [String: Double?] = [:]
            for name in methods { row[name] = .some(M.roundTo(table[roi]![name]!, 2)) }
            snrOut[roi] = row
        }
        var wOut: [String: Double] = [:]
        for roi in used { wOut[roi] = M.roundTo(weights[roi]! / wsum, 3) }
        return RoiCombination(method: method, rois: used, weights: wOut, snrDb: snrOut, signal: combined)
    }
}
