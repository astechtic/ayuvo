import Foundation

// Numbers, time and interval helpers shared by every derived-metrics engine. Line-by-line port of the helpers at the
// top of `scripts/derived_reference.py` (docs/derived-metrics.md): the reference wins over prose.

nonisolated enum DerivedMath {
    static let minute: Int64 = 60_000

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

    /// List-order sum divided by n.
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

    static func median(_ values: [Double]) -> Double {
        let s = values.sorted()
        let n = s.count
        if n % 2 == 1 { return s[n / 2] }
        return (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    static func floorMod(_ a: Int64, _ b: Int64) -> Int64 { a - floorDiv(a, b) * b }

    /// Python `str` ordering (code point by code point), for source ids and names.
    static func pyLess(_ a: String, _ b: String) -> Bool {
        var x = a.unicodeScalars.makeIterator(), y = b.unicodeScalars.makeIterator()
        while true {
            switch (x.next(), y.next()) {
            case (nil, nil): return false
            case (nil, _): return true
            case (_, nil): return false
            case let (p?, q?):
                if p.value != q.value { return p.value < q.value }
            }
        }
    }

    /// `{minute_ms: value}` from a minute series; nulls and values outside [lo, hi] are dropped.
    static func minuteMap(_ series: DerivedMinuteSeries?, lo: Double? = nil, hi: Double? = nil) -> [Int64: Double] {
        var out: [Int64: Double] = [:]
        guard let series else { return out }
        for (i, value) in series.values.enumerated() {
            guard let v = value else { continue }
            if let lo, let hi, v < lo || v > hi { continue }
            out[series.startMs + Int64(i) * minute] = v
        }
        return out
    }

    /// Merged [start, end) intervals sorted by (start, end); empty ones dropped.
    static func union(_ intervals: [(Int64, Int64)]) -> [(Int64, Int64)] {
        let s = intervals.filter { $0.1 > $0.0 }.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
        var out: [(Int64, Int64)] = []
        for (a, b) in s {
            if let last = out.last, a <= last.1 {
                if b > last.1 { out[out.count - 1] = (last.0, b) }
            } else {
                out.append((a, b))
            }
        }
        return out
    }

    static func totalMs(_ intervals: [(Int64, Int64)]) -> Int64 {
        var t: Int64 = 0
        for (a, b) in intervals { t += b - a }
        return t
    }

    /// JSON leaf for an optional (`NSNull` for nil).
    static func json(_ x: Any?) -> Any { x ?? NSNull() }
}

// MARK: - Days and wall clock (Python zoneinfo semantics)

nonisolated enum DerivedDay {
    struct LocalTime: Sendable, Equatable {
        let dayOrdinal: Int
        let hour: Int
        let minute: Int
    }

    static func parse(_ day: String) -> Int? { InsightsDay.parse(day) }

    static func format(_ ordinal: Int) -> String { InsightsDay.format(ordinal) }

    static func addDays(_ day: String, _ n: Int) -> String { InsightsDay.add(day, n) }

    static func timeZone(_ id: String) -> TimeZone { InsightsDay.timeZone(id) }

    /// ISO weekday 1 (Monday) … 7 (Sunday); 1970-01-01 was a Thursday.
    static func isoWeekday(_ day: String) -> Int {
        Int(DerivedMath.floorMod(Int64(parse(day) ?? 0) + 3, 7)) + 1
    }

    private static func offsetMs(_ ms: Int64, _ tz: TimeZone) -> Int64 {
        Int64(tz.secondsFromGMT(for: Date(timeIntervalSince1970: Double(ms) / 1000.0))) * 1000
    }

    /// Epoch ms of local midnight, as `datetime(y, m, d, tzinfo=ZoneInfo(tz))` with fold=0: an ambiguous midnight
    /// resolves to the first occurrence and a skipped one uses the offset in force before the transition.
    static func dayStartMs(_ day: String, _ tz: TimeZone) -> Int64 {
        let wall = Int64(parse(day) ?? 0) * 86_400_000
        let before = offsetMs(wall - 86_400_000, tz)
        let after = offsetMs(wall + 86_400_000, tz)
        let early = wall - before
        if before == after || offsetMs(early, tz) == before { return early }
        let late = wall - after
        if offsetMs(late, tz) == after { return late }
        return early
    }

    static func dayStartMs(_ day: String, _ tz: String) -> Int64 { dayStartMs(day, timeZone(tz)) }

    static func localTime(_ ms: Int64, _ tz: TimeZone) -> LocalTime {
        let local = ms + offsetMs(ms, tz)
        let ordinal = Int(DerivedMath.floorDiv(local, 86_400_000))
        let minuteOfDay = Int(DerivedMath.floorMod(local, 86_400_000) / 60_000)
        return LocalTime(dayOrdinal: ordinal, hour: minuteOfDay / 60, minute: minuteOfDay % 60)
    }

    static func localDayOf(_ ms: Int64, _ tz: TimeZone) -> String { format(localTime(ms, tz).dayOrdinal) }

    /// Wall-clock minutes after 12:00 of the day before `wakeDay` (23:00 → 660, 07:00 → 1140).
    static func clockMin(_ ms: Int64, wakeDay: String, _ tz: TimeZone) -> Int {
        let t = localTime(ms, tz)
        let days = t.dayOrdinal - ((parse(wakeDay) ?? 0) - 1)
        return days * 1440 + t.hour * 60 + t.minute - 720
    }
}

// MARK: - Shared inputs

/// `{"start_ms": t0, "values": [v | null, ...]}`: value i belongs to minute t0 + i × 60 000.
nonisolated struct DerivedMinuteSeries: Sendable {
    var startMs: Int64
    var values: [Double?]
}

/// `[start_ms, end_ms, code]`, codes as the registry's "sleep" metric (0 in bed, 1 asleep, 2 awake, 3 core,
/// 4 deep, 5 REM, 6 out of bed).
nonisolated struct DerivedSleepRow: Sendable, Equatable {
    var startMs: Int64
    var endMs: Int64
    var code: Int

    static let asleepCodes: Set<Int> = [1, 3, 4, 5]
    var isAsleep: Bool { Self.asleepCodes.contains(code) }
    var jsonObject: [Any] { [startMs, endMs, code] }
}

/// An engine result that serializes to the reference's dict (same keys, null for missing).
nonisolated protocol DerivedOutput: Sendable {
    var jsonObject: [String: Any] { get }
}
