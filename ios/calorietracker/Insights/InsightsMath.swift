import Foundation

// Numbers, days and text shared by every Insights engine. Line-by-line port of the helpers at the top of
// `scripts/insights_reference.py` (docs/insights.md §2.2): the reference wins over prose.

nonisolated enum InsightsMath {
    static let minus = "\u{2212}"

    /// `floor(x * 10^d + 0.5) / 10^d`, never -0.
    static func roundTo(_ x: Double, _ decimals: Int) -> Double {
        let scale: Double
        switch decimals {
        case 0: scale = 1
        case 1: scale = 10
        case 2: scale = 100
        default: scale = pow(10, Double(decimals))
        }
        let v = (x * scale + 0.5).rounded(.down) / scale
        return v == 0 ? 0 : v
    }

    @_disfavoredOverload
    static func roundTo(_ x: Double?, _ decimals: Int) -> Double? {
        x.map { roundTo($0, decimals) }
    }

    static func roundInt(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }

    @_disfavoredOverload
    static func roundInt(_ x: Double?) -> Int? { x.map { roundInt($0) } }

    static func clamp(_ x: Double, _ lo: Double, _ hi: Double) -> Double {
        x < lo ? lo : (x > hi ? hi : x)
    }

    /// Chronological / list-order sum divided by n.
    static func mean(_ values: [Double]) -> Double {
        var total = 0.0
        for v in values { total += v }
        return total / Double(values.count)
    }

    /// Sample standard deviation (n − 1). Needs n ≥ 2.
    static func sampleSD(_ values: [Double], _ m: Double) -> Double {
        var total = 0.0
        for v in values { total += (v - m) * (v - m) }
        return (total / Double(values.count - 1)).squareRoot()
    }

    /// Least-squares slope of (x, y); nil when x has no spread.
    static func olsSlope(_ points: [(Double, Double)]) -> Double? {
        let mx = mean(points.map(\.0))
        let my = mean(points.map(\.1))
        var sxx = 0.0, sxy = 0.0
        for (x, y) in points {
            sxx += (x - mx) * (x - mx)
            sxy += (x - mx) * (y - my)
        }
        return sxx == 0 ? nil : sxy / sxx
    }

    /// Piecewise-linear y(x) over [[x, y]] with x ascending; flat beyond the ends.
    static func interp(_ points: [[Double]], _ x: Double) -> Double {
        if x <= points[0][0] { return points[0][1] }
        for i in 0..<(points.count - 1) {
            let (x0, y0, x1, y1) = (points[i][0], points[i][1], points[i + 1][0], points[i + 1][1])
            if x <= x1 { return y0 + (y1 - y0) * (x - x0) / (x1 - x0) }
        }
        return points[points.count - 1][1]
    }

    /// Age at which a monotonic population curve [[age, value]] equals `value` (inverse linear interpolation,
    /// linear extrapolation from the end segments), clamped to [ageMin, ageMax].
    static func inverseAge(_ table: [[Double]], _ value: Double, _ ageMin: Double, _ ageMax: Double) -> Double {
        let n = table.count
        var seg = n - 2
        var found = false
        for i in 0..<(n - 1) {
            let lo = min(table[i][1], table[i + 1][1]), hi = max(table[i][1], table[i + 1][1])
            if lo <= value, value <= hi {
                seg = i
                found = true
                break
            }
        }
        if !found {
            let increasing = table[n - 1][1] > table[0][1]
            let belowFirst = increasing ? value < table[0][1] : value > table[0][1]
            seg = belowFirst ? 0 : n - 2
        }
        let (a0, v0, a1, v1) = (table[seg][0], table[seg][1], table[seg + 1][0], table[seg + 1][1])
        let age = a0 + (value - v0) * (a1 - a0) / (v1 - v0)
        return clamp(age, ageMin, ageMax)
    }

    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    static func floorMod(_ a: Int64, _ b: Int64) -> Int64 { a - floorDiv(a, b) * b }
}

// MARK: - Days ("YYYY-MM-DD")

nonisolated enum InsightsDay {
    /// Days since 1970-01-01 for a civil date (proleptic Gregorian).
    static func ordinal(year: Int, month: Int, day: Int) -> Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    static func civil(_ ordinal: Int) -> (year: Int, month: Int, day: Int) {
        let z = ordinal + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        return (yoe + era * 400 + (m <= 2 ? 1 : 0), m, d)
    }

    /// Ordinal of a "YYYY-MM-DD" key; nil when malformed.
    static func parse(_ s: String) -> Int? {
        let u = Array(s.utf8)
        guard u.count == 10, u[4] == 45, u[7] == 45 else { return nil }
        func num(_ r: Range<Int>) -> Int? {
            var v = 0
            for i in r {
                guard u[i] >= 48, u[i] <= 57 else { return nil }
                v = v * 10 + Int(u[i] - 48)
            }
            return v
        }
        guard let y = num(0..<4), let m = num(5..<7), let d = num(8..<10), (1...12).contains(m), (1...31).contains(d)
        else { return nil }
        return ordinal(year: y, month: m, day: d)
    }

    static func format(_ ordinal: Int) -> String {
        let c = civil(ordinal)
        return String(format: "%04d-%02d-%02d", c.year, c.month, c.day)
    }

    static func add(_ day: String, _ n: Int) -> String {
        guard let o = parse(day) else { return day }
        return format(o + n)
    }

    /// b − a in whole days.
    static func between(_ a: String, _ b: String) -> Int {
        (parse(b) ?? 0) - (parse(a) ?? 0)
    }

    static func timeZone(_ id: String) -> TimeZone {
        TimeZone(identifier: id) ?? TimeZone(identifier: "UTC")!
    }

    private static func localMs(_ ms: Int64, _ tz: TimeZone) -> Int64 {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        return ms + Int64(tz.secondsFromGMT(for: date)) * 1000
    }

    static func localDayOrdinal(ms: Int64, _ tz: TimeZone) -> Int {
        Int(InsightsMath.floorDiv(localMs(ms, tz), 86_400_000))
    }

    static func localDay(ms: Int64, _ tz: TimeZone) -> String { format(localDayOrdinal(ms: ms, tz)) }

    /// Wall-clock minutes since local midnight.
    static func localMinutes(ms: Int64, _ tz: TimeZone) -> Int {
        Int(InsightsMath.floorMod(localMs(ms, tz), 86_400_000) / 60_000)
    }

    /// Wall-clock minutes from 12:00 on the day before `wakeDay` to the night's midpoint: a 23:00–07:00 night → 900.
    static func sleepMidpointMinutes(startMs: Int64, endMs: Int64, wakeDay: String, _ tz: TimeZone) -> Int {
        let mid = startMs + InsightsMath.floorDiv(endMs - startMs, 2)
        let days = localDayOrdinal(ms: mid, tz) - ((parse(wakeDay) ?? 0) - 1)
        return days * 1440 + localMinutes(ms: mid, tz) - 720
    }

    /// Local day key of `date` in `calendar`'s time zone.
    static func key(for date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return format(ordinal(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1))
    }

    /// Local noon of a day key, for display.
    static func date(_ day: String, calendar: Calendar = .current) -> Date? {
        guard let o = parse(day) else { return nil }
        let c = civil(o)
        return calendar.date(from: DateComponents(year: c.year, month: c.month, day: c.day, hour: 12))
    }
}

// MARK: - Text

nonisolated enum InsightsFormat {
    static func groupInt(_ n: Int) -> String {
        var s = String(n.magnitude)
        var out: [String] = []
        while s.count > 3 {
            out.insert(String(s.suffix(3)), at: 0)
            s = String(s.dropLast(3))
        }
        out.insert(s, at: 0)
        return (n < 0 ? "-" : "") + out.joined(separator: ",")
    }

    /// Integral → grouped integer ("8,432"), otherwise one decimal ("7.5").
    static func number(_ x: Double) -> String {
        if x == x.rounded(.down) { return groupInt(Int(x)) }
        return String(format: "%.1f", InsightsMath.roundTo(x, 1))
    }

    /// "+8", "−3", "+0.4" or "0".
    static func signed(_ x: Double, _ decimals: Int) -> String {
        let r = InsightsMath.roundTo(x, decimals)
        if r == 0 { return "0" }
        let body = decimals == 0 ? groupInt(Int(abs(r))) : String(format: "%.\(decimals)f", abs(r))
        return (r > 0 ? "+" : InsightsMath.minus) + body
    }

    /// "7h 48m" or "45m".
    static func duration(_ minutes: Double) -> String {
        let m = InsightsMath.roundInt(minutes)
        return m < 60 ? "\(m)m" : "\(m / 60)h \(m % 60)m"
    }

    /// Replaces each `{name}` with its param: numbers via `number`, strings as is.
    static func fill(_ template: String, _ params: [String: InsightsParam]) -> String {
        var out = ""
        var rest = Substring(template)
        while let open = rest.firstIndex(of: "{") {
            out += rest[rest.startIndex..<open]
            let after = rest.index(after: open)
            guard let close = rest[after...].firstIndex(of: "}") else {
                rest = rest[open...]
                break
            }
            let name = rest[after..<close]
            if isPlaceholderName(name), let value = params[String(name)] {
                out += value.templateText
                rest = rest[rest.index(after: close)...]
            } else {
                out += "{"
                rest = rest[after...]
            }
        }
        out += rest
        return out
    }

    private static func isPlaceholderName(_ s: Substring) -> Bool {
        guard let first = s.unicodeScalars.first, first == "_" || ("a"..."z").contains(first) else { return false }
        return s.unicodeScalars.allSatisfy { $0 == "_" || ("a"..."z").contains($0) || ("0"..."9").contains($0) }
    }
}
