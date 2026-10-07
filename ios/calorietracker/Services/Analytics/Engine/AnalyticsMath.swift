import Foundation

// Numbers and days for the analytics engine: line-by-line port of the helpers at the top of
// `scripts/analytics_reference.py`. Summation order, rounding (half-up) and the written-out transcendental forms
// are part of the contract, so the reference and both apps agree within `config.tolerance`.

nonisolated enum AMath {
    static func roundTo(_ x: Double, _ d: Int) -> Double { DerivedMath.roundTo(x, d) }
    @_disfavoredOverload
    static func roundTo(_ x: Double?, _ d: Int) -> Double? { x.map { DerivedMath.roundTo($0, d) } }

    static func roundInt(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }

    static func clamp(_ x: Double, _ lo: Double, _ hi: Double) -> Double { x < lo ? lo : (x > hi ? hi : x) }

    static func total(_ values: [Double]) -> Double {
        var t = 0.0
        for v in values { t += v }
        return t
    }

    static func mean(_ values: [Double]) -> Double { total(values) / Double(values.count) }

    static func sampleSD(_ values: [Double], _ m: Double) -> Double { DerivedMath.sampleSD(values, m) }

    static func median(_ values: [Double]) -> Double { DerivedMath.median(values) }

    /// Hyndman & Fan type 7.
    static func percentile(_ values: [Double], _ p: Double) -> Double {
        let s = values.sorted()
        let n = s.count
        if n == 1 { return s[0] }
        let h = Double(n - 1) * p / 100.0
        let lo = Int(h.rounded(.down))
        let hi = lo + 1 < n ? lo + 1 : n - 1
        return s[lo] + (h - Double(lo)) * (s[hi] - s[lo])
    }

    static func mad(_ values: [Double]) -> Double {
        let m = median(values)
        return median(values.map { abs($0 - m) })
    }

    static func atanh(_ r: Double) -> Double { 0.5 * Foundation.log((1.0 + r) / (1.0 - r)) }

    static func tanh(_ z: Double) -> Double {
        let e = Foundation.exp(2.0 * z)
        return (e - 1.0) / (e + 1.0)
    }

    /// Abramowitz & Stegun 7.1.26.
    static func normalCDF(_ x: Double) -> Double {
        let z = abs(x) / 2.0.squareRoot()
        let t = 1.0 / (1.0 + 0.3275911 * z)
        let poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        let erf = 1.0 - poly * Foundation.exp(-z * z)
        return x >= 0 ? 0.5 * (1.0 + erf) : 0.5 * (1.0 - erf)
    }

    // MARK: Days

    static func addDays(_ day: String, _ n: Int) -> String { InsightsDay.add(day, n) }
    static func between(_ a: String, _ b: String) -> Int { InsightsDay.between(a, b) }

    /// Days day+first … day+last inclusive, oldest first.
    static func windowDays(_ day: String, _ first: Int, _ last: Int) -> [String] {
        guard let o = InsightsDay.parse(day), last >= first else { return [] }
        return (first...last).map { InsightsDay.format(o + $0) }
    }

    static func localDayOf(_ ms: Int64, _ tz: String) -> String { DerivedDay.localDayOf(ms, DerivedDay.timeZone(tz)) }

    // MARK: Robust statistics

    /// (slope, intercept, slopes) — nil slope when no pair has distinct x.
    static func theilSen(_ points: [(Double, Double)]) -> (Double?, Double?, [Double]) {
        var slopes: [Double] = []
        for i in points.indices {
            for j in (i + 1)..<max(i + 1, points.count) {
                let dx = points[j].0 - points[i].0
                if dx != 0 { slopes.append((points[j].1 - points[i].1) / dx) }
            }
        }
        if slopes.isEmpty { return (nil, nil, []) }
        let b = median(slopes)
        let a = median(points.map { $0.1 - b * $0.0 })
        return (b, a, slopes)
    }

    static func ewma(_ values: [Double], _ span: Double) -> Double? {
        let alpha = 2.0 / (span + 1.0)
        var e: Double?
        for v in values { e = e.map { alpha * v + (1.0 - alpha) * $0 } ?? v }
        return e
    }

    static func cusum(_ z: [Double], _ k: Double, _ h: Double) -> (Int, String)? {
        var sp = 0.0, sn = 0.0
        for (i, x) in z.enumerated() {
            sp = max(0.0, sp + x - k)
            sn = max(0.0, sn - x - k)
            if sp > h { return (i, "up") }
            if sn > h { return (i, "down") }
        }
        return nil
    }

    /// 1-based ranks, ties share the average rank.
    static func ranks(_ values: [Double]) -> [Double] {
        let order = values.indices.sorted { values[$0] != values[$1] ? values[$0] < values[$1] : $0 < $1 }
        var r = [Double](repeating: 0, count: values.count)
        var i = 0
        while i < order.count {
            var j = i
            while j + 1 < order.count && values[order[j + 1]] == values[order[i]] { j += 1 }
            let avg = Double(i + j) / 2.0 + 1.0
            for k in i...j { r[order[k]] = avg }
            i = j + 1
        }
        return r
    }

    static func pearson(_ xs: [Double], _ ys: [Double]) -> Double? {
        let mx = mean(xs), my = mean(ys)
        var sxx = 0.0, syy = 0.0, sxy = 0.0
        for (x, y) in zip(xs, ys) {
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
            sxy += (x - mx) * (y - my)
        }
        if sxx == 0 || syy == 0 { return nil }
        return sxy / (sxx * syy).squareRoot()
    }

    static func bhQValues(_ p: [Double]) -> [Double] {
        let m = p.count
        let order = p.indices.sorted { p[$0] != p[$1] ? p[$0] < p[$1] : $0 < $1 }
        var q = [Double](repeating: 0, count: m)
        var running = 1.0
        var rank = m
        while rank >= 1 {
            let i = order[rank - 1]
            running = min(running, p[i] * Double(m) / Double(rank))
            q[i] = running
            rank -= 1
        }
        return q
    }

    /// Cholesky–Banachiewicz solve of A x = b; nil when A is not positive definite.
    static func choleskySolve(_ a: [[Double]], _ b: [Double]) -> [Double]? {
        let n = a.count
        var L = [[Double]](repeating: [Double](repeating: 0, count: n), count: n)
        for i in 0..<n {
            for j in 0...i {
                var s = a[i][j]
                for k in 0..<j { s -= L[i][k] * L[j][k] }
                if i == j {
                    if s <= 0 { return nil }
                    L[i][j] = s.squareRoot()
                } else {
                    L[i][j] = s / L[j][j]
                }
            }
        }
        var y = [Double](repeating: 0, count: n)
        for i in 0..<n {
            var s = b[i]
            for k in 0..<i { s -= L[i][k] * y[k] }
            y[i] = s / L[i][i]
        }
        var x = [Double](repeating: 0, count: n)
        for i in stride(from: n - 1, through: 0, by: -1) {
            var s = y[i]
            for k in (i + 1)..<max(i + 1, n) { s -= L[k][i] * x[k] }
            x[i] = s / L[i][i]
        }
        return x
    }

    // MARK: Canonical JSON and hashing

    static func canonical(_ v: AJ) -> String {
        switch v {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .num(let x):
            if x == x.rounded(.down) && abs(x) < 1e15 { return String(Int64(x)) }
            var s = String(format: "%.6f", roundTo(x, 6))
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
            return (s == "-0" || s.isEmpty) ? "0" : s
        case .str(let s):
            var out = "\""
            for u in s.unicodeScalars {
                switch u {
                case "\"": out += "\\\""
                case "\\": out += "\\\\"
                default:
                    if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
                }
            }
            return out + "\""
        case .arr(let a): return "[" + a.map(canonical).joined(separator: ",") + "]"
        case .obj(let o):
            let keys = o.keys.sorted(by: DerivedMath.pyLess)
            return "{" + keys.map { canonical(.str($0)) + ":" + canonical(o[$0]!) }.joined(separator: ",") + "}"
        }
    }

    static func fnv1a64(_ text: String) -> String {
        var h: UInt64 = 0xcbf2_9ce4_8422_2325
        for b in text.utf8 {
            h ^= UInt64(b)
            h = h &* 0x100_0000_01b3
        }
        return String(format: "%016llx", h)
    }
}
