import Foundation

/// Swift port of `scripts/metrics_reference.py` (docs/ui-structure.md §5, §7). Pure: "now" and the
/// time zone are always inputs. `MetricsVectorTests` runs every `shared/metrics/test-vectors` file
/// through `runCase`, so keep this line-for-line with the reference.
nonisolated enum MetricsReference {
    static let hourMs: Int64 = 3_600_000
    static let favMax = 12

    enum WeekStart: String, Sendable {
        case monday, sunday
    }

    enum Aggregation: String, Sendable {
        case sum, avg, last, count, duration

        var isSummed: Bool { self == .sum || self == .duration || self == .count }
    }

    // MARK: - Rounding

    /// Half away from zero, 3 decimals.
    static func round3(_ x: Double?) -> Double? {
        guard let x else { return nil }
        let sign: Double = x < 0 ? -1 : 1
        return sign * (floor(abs(x) * 1000 + 0.5) / 1000)
    }

    // MARK: - Local days

    struct LocalDay: Hashable, Comparable, Sendable {
        let year: Int
        let month: Int
        let day: Int

        /// Days since 1970-01-01 (proleptic Gregorian).
        var ordinal: Int {
            let y = month <= 2 ? year - 1 : year
            let era = (y >= 0 ? y : y - 399) / 400
            let yoe = y - era * 400
            let mp = (month + 9) % 12
            let doy = (153 * mp + 2) / 5 + day - 1
            let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        init(year: Int, month: Int, day: Int) {
            self.year = year
            self.month = month
            self.day = day
        }

        init(ordinal: Int) {
            let z = ordinal + 719_468
            let era = (z >= 0 ? z : z - 146_096) / 146_097
            let doe = z - era * 146_097
            let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
            let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            let mp = (5 * doy + 2) / 153
            let d = doy - (153 * mp + 2) / 5 + 1
            let m = mp < 10 ? mp + 3 : mp - 9
            self.init(year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d)
        }

        func adding(days: Int) -> LocalDay { LocalDay(ordinal: ordinal + days) }

        /// ISO weekday: Monday 1 … Sunday 7.
        var isoWeekday: Int {
            let w = (ordinal + 3) % 7 // 1970-01-01 was a Thursday (ISO 4)
            return (w < 0 ? w + 7 : w) + 1
        }

        static func daysInMonth(year: Int, month: Int) -> Int {
            switch month {
            case 1, 3, 5, 7, 8, 10, 12: return 31
            case 4, 6, 9, 11: return 30
            default:
                let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
                return leap ? 29 : 28
            }
        }

        /// Calendar month step with the day clamped to the target month's length.
        func addingMonths(_ n: Int) -> LocalDay {
            let total = year * 12 + (month - 1) + n
            let y = Int(floor(Double(total) / 12))
            let m = total - y * 12 + 1
            return LocalDay(year: y, month: m, day: min(day, Self.daysInMonth(year: y, month: m)))
        }

        var text: String { String(format: "%04d-%02d-%02d", year, month, day) }

        static func parse(_ text: String?) -> LocalDay? {
            guard let text else { return nil }
            let parts = text.split(separator: "-", omittingEmptySubsequences: false)
            guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
                  parts.allSatisfy({ $0.allSatisfy { $0.isASCII && $0.isNumber } }),
                  let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
                  (1...12).contains(m), (1...daysInMonth(year: y, month: m)).contains(d)
            else { return nil }
            return LocalDay(year: y, month: m, day: d)
        }

        static func < (lhs: LocalDay, rhs: LocalDay) -> Bool { lhs.ordinal < rhs.ordinal }
    }

    /// Time-zone helper around a Gregorian calendar.
    struct Zone: Sendable {
        let calendar: Calendar

        init(_ identifier: String) {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: identifier) ?? TimeZone(secondsFromGMT: 0)!
            self.calendar = calendar
        }

        init(calendar: Calendar) {
            var gregorian = Calendar(identifier: .gregorian)
            gregorian.timeZone = calendar.timeZone
            self.calendar = gregorian
        }

        func midnight(_ day: LocalDay) -> Int64 {
            let components = DateComponents(year: day.year, month: day.month, day: day.day, hour: 0, minute: 0, second: 0)
            let date = calendar.date(from: components) ?? Date(timeIntervalSince1970: Double(day.ordinal) * 86_400)
            return Int64((date.timeIntervalSince1970 * 1000).rounded())
        }

        func day(of ms: Int64) -> LocalDay {
            let parts = calendar.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(ms) / 1000))
            return LocalDay(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
        }

        func hour(of ms: Int64) -> Int {
            calendar.component(.hour, from: Date(timeIntervalSince1970: Double(ms) / 1000))
        }

        /// Local date, hour and minute of `ms`.
        func wallClock(of ms: Int64) -> (day: LocalDay, hour: Int, minute: Int) {
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: Date(timeIntervalSince1970: Double(ms) / 1000))
            return (LocalDay(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1), parts.hour ?? 0, parts.minute ?? 0)
        }

        /// Epoch ms of wall-clock `hour`:`minute` on `day` (reference `local_instant`).
        func instant(_ day: LocalDay, hour: Int, minute: Int) -> Int64 {
            let components = DateComponents(year: day.year, month: day.month, day: day.day, hour: hour, minute: minute, second: 0)
            let date = calendar.date(from: components) ?? Date(timeIntervalSince1970: Double(day.ordinal) * 86_400 + Double(hour * 3600 + minute * 60))
            return Int64((date.timeIntervalSince1970 * 1000).rounded())
        }
    }

    static func weekStartOf(_ day: LocalDay, _ weekStart: WeekStart) -> LocalDay {
        let offset = weekStart == .sunday ? day.isoWeekday % 7 : day.isoWeekday - 1
        return day.adding(days: -offset)
    }

    // MARK: - bucket_bounds / step_anchor

    struct Bucket: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let label: String
    }

    struct Bounds: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let buckets: [Bucket]
        let canGoForward: Bool
    }

    private static func intervalDays(_ range: HealthDetailRange, _ anchor: LocalDay, _ weekStart: WeekStart) -> (LocalDay, LocalDay) {
        switch range {
        case .day:
            return (anchor, anchor.adding(days: 1))
        case .week:
            let s = weekStartOf(anchor, weekStart)
            return (s, s.adding(days: 7))
        case .month:
            let s = LocalDay(year: anchor.year, month: anchor.month, day: 1)
            return (s, s.addingMonths(1))
        case .sixMonths:
            let first = LocalDay(year: anchor.year, month: anchor.month, day: 1)
            return (first.addingMonths(-5), first.addingMonths(1))
        case .year:
            return (LocalDay(year: anchor.year, month: 1, day: 1), LocalDay(year: anchor.year + 1, month: 1, day: 1))
        }
    }

    static func bucketBounds(range: HealthDetailRange, anchorMs: Int64, zone: Zone, weekStart: WeekStart, nowMs: Int64) -> Bounds {
        let anchor = zone.day(of: anchorMs)
        let (first, after) = intervalDays(range, anchor, weekStart)
        let startMs = zone.midnight(first)
        let endMs = zone.midnight(after)
        var buckets: [Bucket] = []
        switch range {
        case .day:
            var t = startMs
            while t < endMs {
                buckets.append(Bucket(startMs: t, endMs: min(t + hourMs, endMs), label: String(format: "%02d", zone.hour(of: t))))
                t += hourMs
            }
        case .week, .month:
            var d = first
            while d < after {
                let n = d.adding(days: 1)
                buckets.append(Bucket(startMs: zone.midnight(d), endMs: zone.midnight(n), label: d.text))
                d = n
            }
        case .sixMonths:
            var d = first
            while d < after {
                let n = min(weekStartOf(d, weekStart).adding(days: 7), after)
                buckets.append(Bucket(startMs: zone.midnight(d), endMs: zone.midnight(n), label: d.text))
                d = n
            }
        case .year:
            for m in 1...12 {
                let d = LocalDay(year: first.year, month: m, day: 1)
                buckets.append(Bucket(startMs: zone.midnight(d), endMs: zone.midnight(d.addingMonths(1)), label: d.text))
            }
        }
        return Bounds(startMs: startMs, endMs: endMs, buckets: buckets, canGoForward: endMs <= nowMs)
    }

    static func stepAnchor(range: HealthDetailRange, anchorMs: Int64, direction: Int, zone: Zone, nowMs: Int64) -> (day: LocalDay, ms: Int64) {
        let d = zone.day(of: anchorMs)
        var r: LocalDay
        switch range {
        case .day: r = d.adding(days: direction)
        case .week: r = d.adding(days: 7 * direction)
        case .month: r = d.addingMonths(direction)
        case .sixMonths: r = d.addingMonths(6 * direction)
        case .year: r = d.addingMonths(12 * direction)
        }
        let today = zone.day(of: nowMs)
        if r > today { r = today }
        return (r, zone.midnight(r))
    }

    // MARK: - bucket_series / headline / sparkline

    struct Entry: Sendable, Equatable {
        let tMs: Int64
        let value: Double?
    }

    private struct Item {
        let t: Int64
        let index: Int
        let value: Double
    }

    struct SeriesBucket: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let value: Double?
        let count: Int
        let min: Double?
        let max: Double?
    }

    private static func clean(_ entries: [Entry]) -> [Item] {
        entries.enumerated().compactMap { index, entry in
            entry.value.map { Item(t: entry.tMs, index: index, value: $0) }
        }
    }

    private static func unitValue(_ aggregation: Aggregation, _ value: Double) -> Double {
        aggregation == .count ? 1 : value
    }

    private static func latest(_ items: [Item]) -> Item? {
        var best: Item?
        for item in items {
            if let b = best {
                if item.t > b.t || (item.t == b.t && item.index > b.index) { best = item }
            } else {
                best = item
            }
        }
        return best
    }

    private static func aggregate(_ items: [Item], _ aggregation: Aggregation, dailyMean: Bool, zone: Zone) -> Double? {
        guard !items.isEmpty else { return nil }
        switch aggregation {
        case .avg:
            return items.reduce(0) { $0 + $1.value } / Double(items.count)
        case .last:
            return latest(items)?.value
        case .sum, .count, .duration:
            if dailyMean {
                var days: [LocalDay: Double] = [:]
                var order: [LocalDay] = []
                for item in items {
                    let key = zone.day(of: item.t)
                    if days[key] == nil { order.append(key) }
                    days[key, default: 0] += unitValue(aggregation, item.value)
                }
                return order.reduce(0) { $0 + days[$1]! } / Double(days.count)
            }
            return items.reduce(0) { $0 + unitValue(aggregation, $1.value) }
        }
    }

    static func bucketSeries(entries: [Entry], range: HealthDetailRange, anchorMs: Int64, zone: Zone, weekStart: WeekStart, aggregation: Aggregation) -> [SeriesBucket] {
        let bounds = bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs)
        let items = clean(entries)
        let dailyMean = (range == .sixMonths || range == .year) && aggregation.isSummed
        return bounds.buckets.map { bucket in
            let inside = items.filter { bucket.startMs <= $0.t && $0.t < bucket.endMs }
            let value = aggregate(inside, aggregation, dailyMean: dailyMean, zone: zone)
            var lo: Double?
            var hi: Double?
            if !inside.isEmpty, aggregation == .avg || aggregation == .last {
                lo = inside.map(\.value).min()
                hi = inside.map(\.value).max()
            }
            return SeriesBucket(startMs: bucket.startMs, endMs: bucket.endMs, value: round3(value), count: inside.count, min: round3(lo), max: round3(hi))
        }
    }

    enum HeadlineKind: String, Sendable {
        case total = "TOTAL", average = "AVERAGE", latest = "LATEST"
    }

    struct Headline: Equatable, Sendable {
        let kind: HeadlineKind
        let value: Double?
        let fromMs: Int64
        let toMs: Int64
        let daysWithData: Int
    }

    static func headline(entries: [Entry], range: HealthDetailRange, anchorMs: Int64, zone: Zone, weekStart: WeekStart, aggregation: Aggregation) -> Headline {
        let bounds = bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs)
        let s = bounds.startMs, e = bounds.endMs
        let items = clean(entries).filter { s <= $0.t && $0.t < e }
        let days = Set(items.map { zone.day(of: $0.t) }).count
        switch aggregation {
        case .sum, .duration, .count:
            let kind: HeadlineKind = range == .day ? .total : .average
            let total = items.reduce(0) { $0 + unitValue(aggregation, $1.value) }
            let value: Double? = items.isEmpty ? nil : (kind == .total ? total : total / Double(days))
            return Headline(kind: kind, value: round3(value), fromMs: s, toMs: e, daysWithData: days)
        case .avg:
            let value: Double? = items.isEmpty ? nil : items.reduce(0) { $0 + $1.value } / Double(items.count)
            return Headline(kind: .average, value: round3(value), fromMs: s, toMs: e, daysWithData: days)
        case .last:
            guard let best = latest(items) else {
                return Headline(kind: .latest, value: nil, fromMs: s, toMs: e, daysWithData: 0)
            }
            return Headline(kind: .latest, value: round3(best.value), fromMs: best.t, toMs: best.t, daysWithData: days)
        }
    }

    struct Sparkline: Equatable, Sendable {
        let values: [Double?]
        let min: Double?
        let max: Double?
        let hasData: Bool
    }

    static func sparkline7d(entries: [Entry], nowMs: Int64, zone: Zone, aggregation: Aggregation) -> Sparkline {
        let today = zone.day(of: nowMs)
        let items = clean(entries)
        var values: [Double?] = []
        for k in stride(from: 6, through: 0, by: -1) {
            let d = today.adding(days: -k)
            let s = zone.midnight(d), e = zone.midnight(d.adding(days: 1))
            let inside = items.filter { s <= $0.t && $0.t < e }
            values.append(round3(aggregate(inside, aggregation, dailyMean: false, zone: zone)))
        }
        let present = values.compactMap { $0 }
        return Sparkline(values: values, min: present.min(), max: present.max(), hasData: !present.isEmpty)
    }

    // MARK: - Fasting and workouts

    struct FastingInput: Sendable {
        let startedAtMs: Int64
        let endedAtMs: Int64?
    }

    static func fastingSecondsPerDay(sessions: [FastingInput], nowMs: Int64, zone: Zone) -> [(day: LocalDay, seconds: Int64)] {
        var perDay: [LocalDay: Int64] = [:]
        for session in sessions {
            let start = session.startedAtMs
            let end = session.endedAtMs ?? nowMs
            if end <= start { continue }
            var d = zone.day(of: start)
            while true {
                let ds = zone.midnight(d)
                let de = zone.midnight(d.adding(days: 1))
                if ds >= end { break }
                let overlap = min(end, de) - max(start, ds)
                if overlap > 0 {
                    perDay[d, default: 0] += overlap / 1000
                }
                d = d.adding(days: 1)
            }
        }
        return perDay.keys.sorted().compactMap { day in
            let seconds = perDay[day] ?? 0
            return seconds > 0 ? (day, seconds) : nil
        }
    }

    struct WorkoutInput: Sendable {
        let diaryDate: String?
        let startedAtMs: Int64
        let durationS: Int
        let calories: Double?
    }

    struct WorkoutBucket: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let count: Int
        let durationS: Int
        let burnKcal: Double?
        let burnCount: Int
    }

    static func workoutDay(_ session: WorkoutInput, zone: Zone) -> LocalDay {
        LocalDay.parse(session.diaryDate) ?? zone.day(of: session.startedAtMs)
    }

    static func workoutStatsPerBucket(sessions: [WorkoutInput], range: HealthDetailRange, anchorMs: Int64, zone: Zone, weekStart: WeekStart) -> [WorkoutBucket] {
        let bounds = bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs)
        let placed = sessions.map { (zone.midnight(workoutDay($0, zone: zone)), $0) }
        return bounds.buckets.map { bucket in
            let inside = placed.filter { bucket.startMs <= $0.0 && $0.0 < bucket.endMs }.map(\.1)
            let burns = inside.compactMap(\.calories)
            return WorkoutBucket(
                startMs: bucket.startMs, endMs: bucket.endMs, count: inside.count,
                durationS: inside.reduce(0) { $0 + $1.durationS },
                burnKcal: burns.isEmpty ? nil : burns.reduce(0, +), burnCount: burns.count
            )
        }
    }

    // MARK: - Rings and favourites

    enum RingState: String, Sendable {
        case value, noGoal = "no_goal", noData = "no_data"
    }

    struct Ring: Equatable, Sendable {
        let state: RingState
        let progress: Double?
        let percent: Int?
        let over: Bool
    }

    static func ringProgress(value: Double?, goal: Double?) -> Ring {
        guard let goal, goal > 0 else { return Ring(state: .noGoal, progress: nil, percent: nil, over: false) }
        guard let value else { return Ring(state: .noData, progress: nil, percent: nil, over: false) }
        let p = max(0, min(1, value / goal))
        return Ring(state: .value, progress: round3(p), percent: Int(floor(value * 100 / goal + 0.5)), over: value > goal)
    }

    enum PinSource: String, Sendable {
        case new, migrated, `default`
    }

    private static func validPin(_ key: String, known: Set<String>, catalog: MetricCatalogData) -> Bool {
        if key.hasPrefix("app:") { return catalog.appMetricsByKey[key] != nil }
        return known.contains(key)
    }

    private static func parsePins(_ raw: String, known: Set<String>, max: Int, catalog: MetricCatalogData) -> [String] {
        var out: [String] = []
        for part in raw.split(separator: ",", omittingEmptySubsequences: false) {
            let key = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if key.isEmpty || out.contains(key) || !validPin(key, known: known, catalog: catalog) { continue }
            out.append(key)
        }
        return Array(out.prefix(max))
    }

    static func favouritePinsMigrate(newRaw: String?, legacyRaw: String?, knownHealthIDs: [String], max: Int, catalog: MetricCatalogData = .shared) -> (favourites: [String], source: PinSource) {
        let known = Set(knownHealthIDs)
        if let newRaw {
            return (parsePins(newRaw, known: known, max: max, catalog: catalog), .new)
        }
        if let legacyRaw {
            if legacyRaw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return ([], .migrated) }
            var pins = parsePins(legacyRaw, known: known, max: max, catalog: catalog)
            for key in catalog.defaultFavourites {
                if pins.count >= max { break }
                if key.hasPrefix("app:"), !pins.contains(key) { pins.append(key) }
            }
            return (pins, .migrated)
        }
        let pins = catalog.defaultFavourites.filter { validPin($0, known: known, catalog: catalog) }
        return (Array(pins.prefix(max)), .default)
    }

    // MARK: - resolve_metric

    struct Resolved: Equatable, Sendable {
        let source: String
        let domain: String
        let colourHex: String
        let colourHexDark: String
        let aggregation: String
        let chartKind: String
        let unit: String
        let goalSource: String
        let defaultFavouriteOrder: Int?
        let browseHidden: Bool
        /// Metric icon, then override icon, then domain icon, then the Other domain icon.
        let iconAndroid: String
        let iconIOS: String
    }

    static func resolveMetric(_ key: String, catalog: MetricCatalogData = .shared) -> Resolved {
        if key.hasPrefix("app:") {
            guard let metric = catalog.appMetricsByKey[key], let domain = catalog.domain(metric.domain) else {
                return unknown(catalog)
            }
            let fav = metric.defaultFavourite
            return Resolved(
                source: "app", domain: metric.domain, colourHex: domain.colourHex, colourHexDark: domain.colourHexDark,
                aggregation: metric.aggregation, chartKind: metric.chartKind, unit: metric.unit.canonical,
                goalSource: metric.goalSource, defaultFavouriteOrder: fav?.enabled == true ? fav?.order : nil,
                browseHidden: false,
                iconAndroid: metric.icon.android, iconIOS: metric.icon.ios
            )
        }
        guard let type = HealthMetricRegistry.type(id: key) else { return unknown(catalog) }
        let override = catalog.override(key)
        let domainID = override?.domain ?? catalog.health.categoryDomains[type.category.rawValue] ?? "other"
        guard let domain = catalog.domain(domainID) else { return unknown(catalog) }
        let fav = override?.defaultFavourite
        return Resolved(
            source: "health", domain: domainID, colourHex: domain.colourHex, colourHexDark: domain.colourHexDark,
            aggregation: catalog.health.aggregationMap[type.aggregation.rawValue] ?? "last",
            chartKind: catalog.health.chartKindMap[type.aggregation.rawValue] ?? "line",
            unit: type.unit, goalSource: override?.goalSource ?? "none",
            defaultFavouriteOrder: fav?.enabled == true ? fav?.order : nil,
            browseHidden: override?.browseHidden ?? false,
            iconAndroid: override?.icon?.android ?? domain.icon.android,
            iconIOS: override?.icon?.ios ?? domain.icon.ios
        )
    }

    private static func unknown(_ catalog: MetricCatalogData) -> Resolved {
        let domain = catalog.domain("other")
        return Resolved(
            source: "unknown", domain: "other", colourHex: domain?.colourHex ?? "#8E8E93",
            colourHexDark: domain?.colourHexDark ?? "#98989D", aggregation: "last", chartKind: "line",
            unit: "none", goalSource: "none", defaultFavouriteOrder: nil, browseHidden: false,
            iconAndroid: domain?.icon.android ?? "Filled.MoreHoriz", iconIOS: domain?.icon.ios ?? "ellipsis.circle"
        )
    }

    // MARK: - Charts: axis ticks, drill-down, sleep (docs/charts.md)

    static let niceSteps: [Double] = [1, 2, 2.5, 5, 10]
    static let sleepAsleepStages: Set<Int> = [1, 3, 4, 5]
    static let sleepStageNames: [(code: Int, name: String)] = [(1, "unspecified"), (2, "awake"), (3, "core"), (4, "deep"), (5, "rem")]
    static let sleepMinDaySpanMs: Int64 = 4 * hourMs
    static let sleepMinRangeSpanMin = 240

    struct NiceTicks: Equatable, Sendable {
        let min: Double
        let max: Double
        let step: Double
        let ticks: [Double]
    }

    /// Y-axis ticks: 1 / 2 / 2.5 / 5 / 10 × 10ⁿ steps, domain grown to whole steps (reference `nice_ticks`).
    static func niceTicks(min lowIn: Double, max highIn: Double, count: Int, includeZero: Bool) -> NiceTicks {
        var lo = lowIn, hi = highIn
        if includeZero {
            lo = Swift.min(lo, 0)
            hi = Swift.max(hi, 0)
        }
        if hi <= lo {
            if lo == 0 {
                hi = 1
            } else {
                lo -= 1
                hi += 1
            }
        }
        let intervals = Swift.max(1, count - 1)
        let raw = (hi - lo) / Double(intervals)
        let mag = pow(10.0, floor(log10(raw)))
        var step = 20.0 * mag
        for s in niceSteps {
            let cand = s * mag
            if Int(ceil(hi / cand - 1e-9)) - Int(floor(lo / cand + 1e-9)) <= intervals {
                step = cand
                break
            }
        }
        let first = Int(floor(lo / step + 1e-9))
        let last = Int(ceil(hi / step - 1e-9))
        let ticks = (first...last).map { round3(Double($0) * step)! }
        return NiceTicks(min: ticks[0], max: ticks[ticks.count - 1], step: round3(step)!, ticks: ticks)
    }

    /// Bucket indices that carry an x label (reference `x_ticks`).
    static func xTicks(range: HealthDetailRange, anchorMs: Int64, zone: Zone, weekStart: WeekStart) -> [Int] {
        let buckets = bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs).buckets
        switch range {
        case .day:
            var out: [Int] = []
            var seen = Set<Int>()
            for (i, b) in buckets.enumerated() {
                let h = zone.hour(of: b.startMs)
                if h % 6 == 0, !seen.contains(h) {
                    seen.insert(h)
                    out.append(i)
                }
            }
            return out
        case .week, .year:
            return Array(buckets.indices)
        case .month:
            return buckets.indices.filter { i in
                guard let d = LocalDay.parse(buckets[i].label) else { return false }
                return weekStartOf(d, weekStart) == d
            }
        case .sixMonths:
            var out: [Int] = []
            var prev: Int?
            for (i, b) in buckets.enumerated() {
                let m = LocalDay.parse(b.label)?.month
                if m != prev {
                    out.append(i)
                    prev = m
                }
            }
            return out
        }
    }

    struct DrillTarget: Equatable, Sendable {
        let range: HealthDetailRange
        let anchorDay: LocalDay
        let anchorMs: Int64
    }

    /// W / M day bucket with data → D for that day when the metric offers D (reference `drill_target`).
    static func drillTarget(range: HealthDetailRange, bucketStartMs: Int64, hasData: Bool, metricRanges: [HealthDetailRange], zone: Zone) -> DrillTarget? {
        guard range == .week || range == .month, hasData, metricRanges.contains(.day) else { return nil }
        let d = zone.day(of: bucketStartMs)
        return DrillTarget(range: .day, anchorDay: d, anchorMs: zone.midnight(d))
    }

    private static func unionMs(_ intervals: [(Int64, Int64)]) -> Int64 {
        var total: Int64 = 0
        var cur: (Int64, Int64)?
        for (s, e) in intervals.sorted(by: { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }) {
            if e <= s { continue }
            if let c = cur {
                if s > c.1 {
                    total += c.1 - c.0
                    cur = (s, e)
                } else if e > c.1 {
                    cur = (c.0, e)
                }
            } else {
                cur = (s, e)
            }
        }
        if let c = cur { total += c.1 - c.0 }
        return total
    }

    private static func pct(_ part: Int64, _ whole: Int64) -> Int? {
        guard whole != 0 else { return nil }
        return Int(floor(Double(part) * 100 / Double(whole) + 0.5))
    }

    struct SleepRow: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let stage: Int
    }

    struct SleepWindow: Equatable, Sendable {
        let bedtimeMs: Int64
        let wakeMs: Int64
        let domainStartMs: Int64
        let domainEndMs: Int64
        let tickStepMs: Int64
        let ticks: [Int64]
        let asleepS: Int64
        let inBedS: Int64
        /// awake, core, deep, rem, unspecified → seconds.
        let stages: [String: Int64]
        /// unspecified, core, deep, rem → whole percent of asleep (nil when asleep is 0).
        let pct: [String: Int?]
    }

    /// Day-range sleep window of one night (reference `sleep_night_window`).
    static func sleepNightWindow(rows: [SleepRow], zone: Zone) -> SleepWindow? {
        let use = rows.filter { $0.endMs > $0.startMs && (0...5).contains($0.stage) }
        guard !use.isEmpty else { return nil }
        let bed = use.map(\.startMs).min()!
        let wake = use.map(\.endMs).max()!
        let b = zone.wallClock(of: bed)
        var start = zone.instant(b.day, hour: b.hour, minute: 0)
        if start > bed { start -= hourMs }
        let w = zone.wallClock(of: wake)
        var end = zone.instant(w.day, hour: w.hour, minute: 0)
        if end < wake { end += hourMs }
        if end - start < sleepMinDaySpanMs { end = start + sleepMinDaySpanMs }
        let span = end - start
        let step = span <= 4 * hourMs ? hourMs : (span <= 8 * hourMs ? 2 * hourMs : 3 * hourMs)
        let ticks = Array(stride(from: start, through: end, by: Int(step)))
        let asleep = unionMs(use.filter { sleepAsleepStages.contains($0.stage) }.map { ($0.startMs, $0.endMs) }) / 1000
        let inBed = unionMs(use.map { ($0.startMs, $0.endMs) }) / 1000
        var stages: [String: Int64] = [:]
        for (code, name) in sleepStageNames {
            stages[name] = unionMs(use.filter { $0.stage == code }.map { ($0.startMs, $0.endMs) }) / 1000
        }
        var pcts: [String: Int?] = [:]
        for name in ["unspecified", "core", "deep", "rem"] {
            pcts[name] = pct(stages[name] ?? 0, asleep)
        }
        return SleepWindow(bedtimeMs: bed, wakeMs: wake, domainStartMs: start, domainEndMs: end, tickStepMs: step, ticks: ticks,
                           asleepS: asleep, inBedS: inBed, stages: stages, pct: pcts)
    }

    /// Wall-clock minutes from 12:00 on the day before `wakeDay` (reference `sleep_clock_offset`).
    static func sleepClockOffset(tMs: Int64, wakeDay: LocalDay, zone: Zone) -> Int {
        let local = zone.wallClock(of: tMs)
        let days = local.day.ordinal - wakeDay.adding(days: -1).ordinal
        return days * 1440 + local.hour * 60 + local.minute - 720
    }

    struct SleepNightInput: Equatable, Sendable {
        let wakeDay: String
        let bedtimeMs: Int64
        let wakeMs: Int64
        let asleepS: Double
        let inBedS: Double
    }

    struct SleepRangeBucket: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let count: Int
        let bedOffsetMin: Double?
        let wakeOffsetMin: Double?
        let asleepS: Double?
        let inBedS: Double?
    }

    struct SleepRangeDomain: Equatable, Sendable {
        let min: Int
        let max: Int
        let ticks: [Int]
    }

    struct SleepRangeHeadline: Equatable, Sendable {
        let nights: Int
        let asleepS: Double?
        let bedOffsetMin: Double?
        let wakeOffsetMin: Double?
    }

    struct SleepRange: Equatable, Sendable {
        let buckets: [SleepRangeBucket]
        let domain: SleepRangeDomain?
        let headline: SleepRangeHeadline
    }

    /// W / M / 6M / Y sleep bars: mean bedtime → wake per bucket (reference `sleep_range_series`).
    static func sleepRangeSeries(nights: [SleepNightInput], range: HealthDetailRange, anchorMs: Int64, zone: Zone, weekStart: WeekStart) -> SleepRange {
        let bounds = bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs)
        var placed: [(t: Int64, night: SleepNightInput, bed: Int, wake: Int)] = []
        for n in nights {
            guard let d = LocalDay.parse(n.wakeDay) else { continue }
            placed.append((zone.midnight(d), n, sleepClockOffset(tMs: n.bedtimeMs, wakeDay: d, zone: zone),
                           sleepClockOffset(tMs: n.wakeMs, wakeDay: d, zone: zone)))
        }
        func mean(_ xs: [Double]) -> Double? { xs.isEmpty ? nil : round3(xs.reduce(0, +) / Double(xs.count)) }
        var out: [SleepRangeBucket] = []
        var every: [(t: Int64, night: SleepNightInput, bed: Int, wake: Int)] = []
        for b in bounds.buckets {
            let inside = placed.filter { b.startMs <= $0.t && $0.t < b.endMs }
            every.append(contentsOf: inside)
            out.append(SleepRangeBucket(
                startMs: b.startMs, endMs: b.endMs, count: inside.count,
                bedOffsetMin: mean(inside.map { Double($0.bed) }), wakeOffsetMin: mean(inside.map { Double($0.wake) }),
                asleepS: mean(inside.map(\.night.asleepS)), inBedS: mean(inside.map(\.night.inBedS))
            ))
        }
        let filled = out.filter { $0.count > 0 }
        var domain: SleepRangeDomain?
        if !filled.isEmpty {
            let lo = Int(floor(filled.compactMap(\.bedOffsetMin).min()! / 60)) * 60
            var hi = Int(ceil(filled.compactMap(\.wakeOffsetMin).max()! / 60)) * 60
            if hi - lo < sleepMinRangeSpanMin { hi = lo + sleepMinRangeSpanMin }
            domain = SleepRangeDomain(min: lo, max: hi, ticks: Array(stride(from: lo, through: hi, by: 120)))
        }
        let head = SleepRangeHeadline(
            nights: every.count, asleepS: mean(every.map(\.night.asleepS)),
            bedOffsetMin: mean(every.map { Double($0.bed) }), wakeOffsetMin: mean(every.map { Double($0.wake) })
        )
        return SleepRange(buckets: out, domain: domain, headline: head)
    }

    // MARK: - Vector dispatch

    private static func int64(_ value: RJ) -> Int64 { Int64(value.double ?? 0) }

    private static func entries(_ value: RJ) -> [Entry] {
        (value.array ?? []).map { Entry(tMs: int64($0["t_ms"]), value: $0["value"].double) }
    }

    private static func range(_ value: RJ) -> HealthDetailRange {
        HealthDetailRange(rawValue: value.string ?? "") ?? .week
    }

    private static func weekStart(_ value: RJ) -> WeekStart {
        WeekStart(rawValue: value.string ?? "") ?? .monday
    }

    private static func ms(_ value: Int64) -> RJ { .int(Int(value)) }

    static func runCase(function: String, input: RJ) -> RJ {
        let zone = Zone(input["time_zone"].string ?? "UTC")
        switch function {
        case "bucket_bounds":
            let b = bucketBounds(range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), zone: zone, weekStart: weekStart(input["week_start"]), nowMs: int64(input["now_ms"]))
            return .obj([
                "interval": .obj(["start_ms": ms(b.startMs), "end_ms": ms(b.endMs)]),
                "buckets": .arr(b.buckets.map { .obj(["start_ms": ms($0.startMs), "end_ms": ms($0.endMs), "label": .str($0.label)]) }),
                "can_go_forward": .bool(b.canGoForward),
            ])
        case "step_anchor":
            let r = stepAnchor(range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), direction: Int(input["direction"].double ?? 0), zone: zone, nowMs: int64(input["now_ms"]))
            return .obj(["anchor_date": .str(r.day.text), "anchor_ms": ms(r.ms)])
        case "bucket_series":
            let buckets = bucketSeries(entries: entries(input["entries"]), range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), zone: zone, weekStart: weekStart(input["week_start"]), aggregation: Aggregation(rawValue: input["aggregation"].string ?? "") ?? .sum)
            return .obj(["buckets": .arr(buckets.map {
                .obj(["start_ms": ms($0.startMs), "end_ms": ms($0.endMs), "value": .number($0.value), "count": .int($0.count), "min": .number($0.min), "max": .number($0.max)])
            })])
        case "headline":
            let h = headline(entries: entries(input["entries"]), range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), zone: zone, weekStart: weekStart(input["week_start"]), aggregation: Aggregation(rawValue: input["aggregation"].string ?? "") ?? .sum)
            return .obj(["kind": .str(h.kind.rawValue), "value": .number(h.value), "from_ms": ms(h.fromMs), "to_ms": ms(h.toMs), "days_with_data": .int(h.daysWithData)])
        case "sparkline_7d":
            let s = sparkline7d(entries: entries(input["entries"]), nowMs: int64(input["now_ms"]), zone: zone, aggregation: Aggregation(rawValue: input["aggregation"].string ?? "") ?? .sum)
            return .obj(["values": .arr(s.values.map { RJ.number($0) }), "min": .number(s.min), "max": .number(s.max), "has_data": .bool(s.hasData)])
        case "fasting_hours_per_day":
            let sessions = (input["sessions"].array ?? []).map {
                FastingInput(startedAtMs: int64($0["started_at_ms"]), endedAtMs: $0["ended_at_ms"].double.map { Int64($0) })
            }
            let days = fastingSecondsPerDay(sessions: sessions, nowMs: int64(input["now_ms"]), zone: zone)
            return .obj(["days": .arr(days.map { .obj(["day": .str($0.day.text), "seconds": ms($0.seconds)]) })])
        case "workout_stats_per_bucket":
            let sessions = (input["sessions"].array ?? []).map {
                WorkoutInput(diaryDate: $0["diary_date"].string, startedAtMs: int64($0["started_at_ms"]), durationS: Int($0["duration_s"].double ?? 0), calories: $0["calories"].double)
            }
            let buckets = workoutStatsPerBucket(sessions: sessions, range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), zone: zone, weekStart: weekStart(input["week_start"]))
            return .obj(["buckets": .arr(buckets.map {
                .obj(["start_ms": ms($0.startMs), "end_ms": ms($0.endMs), "count": .int($0.count), "duration_s": .int($0.durationS), "burn_kcal": .number($0.burnKcal), "burn_count": .int($0.burnCount)])
            })])
        case "ring_progress":
            let r = ringProgress(value: input["value"].double, goal: input["goal"].double)
            return .obj(["state": .str(r.state.rawValue), "progress": .number(r.progress), "percent": r.percent.map { RJ.int($0) } ?? .null, "over": .bool(r.over)])
        case "favourite_pins_migrate":
            let r = favouritePinsMigrate(
                newRaw: input["new_raw"].string, legacyRaw: input["legacy_raw"].string,
                knownHealthIDs: (input["known_health_ids"].array ?? []).compactMap(\.string),
                max: Int(input["max"].double ?? Double(favMax))
            )
            return .obj(["favourites": .arr(r.favourites.map { .str($0) }), "source": .str(r.source.rawValue)])
        case "resolve_metric":
            let r = resolveMetric(input["key"].string ?? "")
            return .obj([
                "source": .str(r.source), "domain": .str(r.domain), "colour_hex": .str(r.colourHex),
                "colour_hex_dark": .str(r.colourHexDark), "aggregation": .str(r.aggregation),
                "chart_kind": .str(r.chartKind), "unit": .str(r.unit), "goal_source": .str(r.goalSource),
                "default_favourite_order": r.defaultFavouriteOrder.map { RJ.int($0) } ?? .null,
                "browse_hidden": .bool(r.browseHidden),
                "icon_android": .str(r.iconAndroid), "icon_ios": .str(r.iconIOS),
            ])
        case "nice_ticks":
            let t = niceTicks(min: input["min"].double ?? 0, max: input["max"].double ?? 0, count: Int(input["count"].double ?? 4), includeZero: input["include_zero"].bool ?? false)
            return .obj(["min": .number(t.min), "max": .number(t.max), "step": .number(t.step), "ticks": .arr(t.ticks.map { RJ.number($0) })])
        case "x_ticks":
            let ix = xTicks(range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), zone: zone, weekStart: weekStart(input["week_start"]))
            return .obj(["indices": .arr(ix.map { RJ.int($0) })])
        case "drill_target":
            let ranges = (input["metric_ranges"].array ?? []).compactMap { HealthDetailRange(rawValue: $0.string ?? "") }
            guard let t = drillTarget(range: range(input["range"]), bucketStartMs: int64(input["bucket_start_ms"]), hasData: input["has_data"].bool ?? false, metricRanges: ranges, zone: zone) else {
                return .obj(["target": .null])
            }
            return .obj(["target": .obj(["range": .str(t.range.rawValue), "anchor_date": .str(t.anchorDay.text), "anchor_ms": ms(t.anchorMs)])])
        case "sleep_night_window":
            let rows = (input["rows"].array ?? []).map { SleepRow(startMs: int64($0["start_ms"]), endMs: int64($0["end_ms"]), stage: Int($0["stage"].double ?? -1)) }
            guard let w = sleepNightWindow(rows: rows, zone: zone) else { return .obj(["window": .null]) }
            return .obj(["window": .obj([
                "bedtime_ms": ms(w.bedtimeMs), "wake_ms": ms(w.wakeMs), "domain_start_ms": ms(w.domainStartMs),
                "domain_end_ms": ms(w.domainEndMs), "tick_step_ms": ms(w.tickStepMs), "ticks": .arr(w.ticks.map { ms($0) }),
                "asleep_s": ms(w.asleepS), "in_bed_s": ms(w.inBedS),
                "stages": .obj(w.stages.mapValues { ms($0) }),
                "pct": .obj(w.pct.mapValues { $0.map { RJ.int($0) } ?? .null }),
            ])])
        case "sleep_clock_offset":
            let day = LocalDay.parse(input["wake_day"].string) ?? LocalDay(year: 1970, month: 1, day: 1)
            return .obj(["offset_min": .int(sleepClockOffset(tMs: int64(input["t_ms"]), wakeDay: day, zone: zone))])
        case "sleep_range_series":
            let nights = (input["nights"].array ?? []).map {
                SleepNightInput(wakeDay: $0["wake_day"].string ?? "", bedtimeMs: int64($0["bedtime_ms"]), wakeMs: int64($0["wake_ms"]),
                                asleepS: $0["asleep_s"].double ?? 0, inBedS: $0["in_bed_s"].double ?? 0)
            }
            let r = sleepRangeSeries(nights: nights, range: range(input["range"]), anchorMs: int64(input["anchor_ms"]), zone: zone, weekStart: weekStart(input["week_start"]))
            return .obj([
                "buckets": .arr(r.buckets.map {
                    .obj(["start_ms": ms($0.startMs), "end_ms": ms($0.endMs), "count": .int($0.count),
                          "bed_offset_min": .number($0.bedOffsetMin), "wake_offset_min": .number($0.wakeOffsetMin),
                          "asleep_s": .number($0.asleepS), "in_bed_s": .number($0.inBedS)])
                }),
                "domain": r.domain.map { d in RJ.obj(["min": .int(d.min), "max": .int(d.max), "ticks": .arr(d.ticks.map { RJ.int($0) })]) } ?? .null,
                "headline": .obj(["nights": .int(r.headline.nights), "asleep_s": .number(r.headline.asleepS),
                                  "bed_offset_min": .number(r.headline.bedOffsetMin), "wake_offset_min": .number(r.headline.wakeOffsetMin)]),
            ])
        default:
            return .null
        }
    }
}
