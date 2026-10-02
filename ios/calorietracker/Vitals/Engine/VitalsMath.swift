import Foundation

// Small math helpers: line-by-line port of the "Small math helpers" section of `scripts/vitals_reference.py`.
// Every loop accumulates in the reference's order and every expression keeps its operation order, so results are
// bit-identical to the reference (both use IEEE doubles and the platform libm).

nonisolated enum VitalsMath {
    /// `TWO_PI = 2.0 * math.pi`.
    static let twoPi = 2.0 * Double.pi

    private static let scales: [Double] = [1, 10, 100, 1000, 10000, 100000, 1000000]

    /// `round_to`: `floor(x * 10^d + 0.5) / 10^d`, never -0.
    static func roundTo(_ x: Double, _ decimals: Int) -> Double {
        let scale = decimals >= 0 && decimals < scales.count ? scales[decimals] : pow(10, Double(decimals))
        let v = (x * scale + 0.5).rounded(.down) / scale
        return v == 0 ? 0 : v
    }

    @_disfavoredOverload
    static func roundTo(_ x: Double?, _ decimals: Int) -> Double? {
        x.map { roundTo($0, decimals) }
    }

    static func roundList(_ xs: [Double], _ decimals: Int) -> [Double] {
        var out: [Double] = []
        out.reserveCapacity(xs.count)
        for x in xs { out.append(roundTo(x, decimals)) }
        return out
    }

    static func clamp(_ x: Double, _ lo: Double, _ hi: Double) -> Double {
        x < lo ? lo : (x > hi ? hi : x)
    }

    static func total(_ values: [Double]) -> Double {
        var t = 0.0
        for v in values { t += v }
        return t
    }

    static func mean(_ values: [Double]) -> Double {
        total(values) / Double(values.count)
    }

    /// Population variance (divides by n).
    static func variance(_ values: [Double]) -> Double {
        let m = mean(values)
        var acc = 0.0
        for v in values { acc += (v - m) * (v - m) }
        return acc / Double(values.count)
    }

    static func std(_ values: [Double]) -> Double {
        variance(values).squareRoot()
    }

    static func sampleStd(_ values: [Double]) -> Double? {
        if values.count < 2 { return nil }
        let m = mean(values)
        var acc = 0.0
        for v in values { acc += (v - m) * (v - m) }
        return (acc / Double(values.count - 1)).squareRoot()
    }

    static func median(_ values: [Double]) -> Double {
        let s = values.sorted()
        let n = s.count
        if n % 2 == 1 { return s[n / 2] }
        return (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    static func mad(_ values: [Double]) -> Double {
        let m = median(values)
        var dev: [Double] = []
        dev.reserveCapacity(values.count)
        for v in values { dev.append(abs(v - m)) }
        return median(dev)
    }

    static func pearson(_ a: [Double], _ b: [Double]) -> Double {
        let n = a.count
        let ma = mean(a), mb = mean(b)
        var sab = 0.0, saa = 0.0, sbb = 0.0
        for i in 0..<n {
            let da = a[i] - ma, db = b[i] - mb
            sab += da * db
            saa += da * da
            sbb += db * db
        }
        if saa <= 0.0 || sbb <= 0.0 { return 0.0 }
        return sab / (saa * sbb).squareRoot()
    }

    static func confidenceLabel(_ c: Double?) -> String? {
        guard let c else { return nil }
        if c >= 0.8 { return "high" }
        if c >= 0.6 { return "medium" }
        return "low"
    }

    /// `int(math.floor(x))`.
    static func floorInt(_ x: Double) -> Int { Int(x.rounded(.down)) }

    static func oddWindow(_ seconds: Double, _ fs: Double) -> Int {
        var w = floorInt(seconds * fs + 0.5)
        if w < 1 { w = 1 }
        if w % 2 == 0 { w += 1 }
        return w
    }

    /// `min(xs)` / `max(xs)` in list order (first extreme wins, like Python).
    static func minimum(_ xs: [Double]) -> Double {
        var m = xs[0]
        for x in xs.dropFirst() where x < m { m = x }
        return m
    }

    static func maximum(_ xs: [Double]) -> Double {
        var m = xs[0]
        for x in xs.dropFirst() where x > m { m = x }
        return m
    }
}
