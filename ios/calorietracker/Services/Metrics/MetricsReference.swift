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
        default:
            return .null
        }
    }
}
