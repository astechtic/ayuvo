import Foundation

/// D / W / M / 6M / Y ranges of the metric detail screen (shared contract, docs/ui-structure.md §5).
nonisolated enum HealthDetailRange: String, CaseIterable, Sendable, Identifiable, Hashable {
    case day = "D"
    case week = "W"
    case month = "M"
    case sixMonths = "6M"
    case year = "Y"

    var id: String { rawValue }

    var englishTitle: String {
        switch self {
        case .day: return "Day"
        case .week: return "Week"
        case .month: return "Month"
        case .sixMonths: return "6 Months"
        case .year: return "Year"
        }
    }

    /// The calendar component one step of ‹ › moves by.
    var stepComponent: Calendar.Component {
        switch self {
        case .day: return .day
        case .week: return .weekOfYear
        case .month: return .month
        case .sixMonths: return .month
        case .year: return .year
        }
    }

    var stepCount: Int { self == .sixMonths ? 6 : 1 }

    /// Calendar-aligned interval covering `anchor` (shared contract `bucket_bounds`, docs/ui-structure.md §5).
    func interval(containing anchor: Date, calendar: Calendar, weekStart: MetricsReference.WeekStart = ActivitySettings.weekStart()) -> DateInterval {
        let bounds = bounds(containing: anchor, calendar: calendar, weekStart: weekStart)
        return DateInterval(start: Date(timeIntervalSince1970: Double(bounds.startMs) / 1000), end: Date(timeIntervalSince1970: Double(bounds.endMs) / 1000))
    }

    func bounds(containing anchor: Date, calendar: Calendar, weekStart: MetricsReference.WeekStart = ActivitySettings.weekStart(), now: Date = Date()) -> MetricsReference.Bounds {
        MetricsReference.bucketBounds(
            range: self,
            anchorMs: Int64((anchor.timeIntervalSince1970 * 1000).rounded()),
            zone: MetricsReference.Zone(calendar: calendar),
            weekStart: weekStart,
            nowMs: Int64((now.timeIntervalSince1970 * 1000).rounded())
        )
    }

    /// Shared anchor step (‹ ›): calendar units, never past today.
    func stepped(_ anchor: Date, direction: Int, calendar: Calendar, now: Date = Date()) -> Date {
        let result = MetricsReference.stepAnchor(
            range: self,
            anchorMs: Int64((anchor.timeIntervalSince1970 * 1000).rounded()),
            direction: direction,
            zone: MetricsReference.Zone(calendar: calendar),
            nowMs: Int64((now.timeIntervalSince1970 * 1000).rounded())
        )
        return Date(timeIntervalSince1970: Double(result.ms) / 1000)
    }
}

nonisolated struct HealthChartPoint: Sendable, Identifiable, Equatable {
    var start: Date
    var end: Date
    /// Primary plotted value (sum for cumulative/duration, average for discrete, count for category).
    var value: Double?
    var min: Double?
    var max: Double?
    /// Second series (diastolic for blood pressure).
    var value2: Double?
    var count: Int
    /// Sleep stage code for stage segments / stacked bars.
    var stage: Int?

    var id: String { "\(start.timeIntervalSince1970)-\(stage ?? -1)" }
}

nonisolated struct HealthHighlights: Sendable, Equatable {
    var total: Double?
    var average: Double?
    var min: Double?
    var max: Double?
    var latest: Double?
    var latestAt: Date?
    var count = 0
}

nonisolated struct HealthChartSeries: Sendable, Equatable {
    var range: HealthDetailRange
    var interval: DateInterval
    var points: [HealthChartPoint]
    /// Per-stage segments (D) or per-night stage totals (W/M/6M/Y) for `sleep`.
    var stagePoints: [HealthChartPoint]
    var highlights: HealthHighlights
    /// Headline stat above the chart (shared `headline`).
    var headline: MetricsReference.Headline?
    /// Sleep D: the fitted night window (shared `sleep_night_window`, docs/charts.md).
    var sleepWindow: MetricsReference.SleepWindow?
    /// Sleep W / M / 6M / Y: bedtime → wake bars on the clock axis (shared `sleep_range_series`).
    var sleepRange: MetricsReference.SleepRange?
    /// Sleep W / M: stage segments of each night on the clock axis.
    var sleepSegments: [SleepSegment] = []
    /// True when no bucket has a value (every bucket is always present).
    var isEmpty: Bool { !points.contains { $0.value != nil } && stagePoints.isEmpty }
}

/// One stage of one night on the sleep clock axis (minutes from 12:00 the day before waking).
nonisolated struct SleepSegment: Sendable, Equatable, Identifiable {
    var day: Date
    var stage: Int
    var startMin: Int
    var endMin: Int

    var id: String { "\(day.timeIntervalSince1970)-\(startMin)-\(stage)" }
}

/// Turns stored rows / daily rollups into chart-ready buckets. Pure; runs on the
/// detached task the store spawns for chart loads.
nonisolated enum HealthChartSeriesBuilder {
    static func build(
        range: HealthDetailRange,
        anchor: Date,
        type: HealthMetricType,
        rows: [HealthSampleRow],
        rollups: [HealthDailyRollupRow],
        calendar: Calendar,
        weekStart: MetricsReference.WeekStart = ActivitySettings.weekStart()
    ) -> HealthChartSeries {
        let bounds = range.bounds(containing: anchor, calendar: calendar, weekStart: weekStart)
        let interval = DateInterval(start: date(bounds.startMs), end: date(bounds.endMs))
        var series = HealthChartSeries(range: range, interval: interval, points: [], stagePoints: [], highlights: HealthHighlights())

        if type.isSleep {
            series.stagePoints = sleepStagePoints(range: range, rows: rows, calendar: calendar, interval: interval)
        }
        let zone = MetricsReference.Zone(calendar: calendar)

        let inRange = rollups.filter { inInterval($0.day, interval, calendar) }
        switch range {
        case .day:
            let raw = dayPoints(type: type, rows: rows, interval: interval, calendar: calendar)
            if type.isSleep {
                series.points = []
            } else if type.isBloodPressure || (type.kind == .category) {
                series.points = raw
            } else {
                series.points = bounds.buckets.map { bucket in
                    let start = date(bucket.startMs)
                    return raw.first { $0.start == start }
                        ?? HealthChartPoint(start: start, end: date(bucket.endMs), value: nil, min: nil, max: nil, value2: nil, count: 0, stage: nil)
                }
            }
        case .week, .month:
            let byDay = Dictionary(inRange.map { ($0.day, $0) }, uniquingKeysWith: { first, _ in first })
            series.points = bounds.buckets.map { bucket in
                byDay[bucket.label].flatMap { dailyPoint($0, type: type, calendar: calendar) }
                    ?? HealthChartPoint(start: date(bucket.startMs), end: date(bucket.endMs), value: nil, min: nil, max: nil, value2: nil, count: 0, stage: nil)
            }
        case .sixMonths, .year:
            series.points = bounds.buckets.map { bucket in
                let group = inRange.filter { rollup in
                    guard let day = dayDate(rollup.day, calendar: calendar) else { return false }
                    let ms = Int64((day.timeIntervalSince1970 * 1000).rounded())
                    return ms >= bucket.startMs && ms < bucket.endMs
                }
                return aggregatePoint(group, start: date(bucket.startMs), end: date(bucket.endMs), type: type)
            }
        }

        if type.isSleep {
            applySleep(&series, range: range, anchor: anchor, rows: rows, zone: zone, calendar: calendar, weekStart: weekStart)
        }

        series.highlights = highlights(type: type, points: series.points, rows: rows, rollups: rollups, interval: interval, calendar: calendar)
        if !type.isSleep {
            series.headline = headline(type: type, range: range, anchor: anchor, rows: rows, rollups: inRange, interval: interval, calendar: calendar, weekStart: weekStart)
        }
        return series
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    /// Shared headline over day rollups (D: hourly rows). Summed kinds use per-day totals.
    private static func headline(type: HealthMetricType, range: HealthDetailRange, anchor: Date, rows: [HealthSampleRow], rollups: [HealthDailyRollupRow], interval: DateInterval, calendar: Calendar, weekStart: MetricsReference.WeekStart) -> MetricsReference.Headline {
        let aggregation: MetricsReference.Aggregation
        switch type.kind {
        case .cumulative: aggregation = .sum
        case .duration, .session: aggregation = .duration
        case .category: aggregation = .count
        case .discrete, .series: aggregation = type.aggregation == .latest ? .last : .avg
        }
        var entries: [MetricsReference.Entry] = []
        if range == .day {
            for row in rows where !row.isDeleted && row.startDate >= interval.start && row.startDate < interval.end {
                let value: Double?
                switch aggregation {
                case .duration: value = row.durationSeconds
                case .count: value = 1
                default: value = row.value
                }
                entries.append(.init(tMs: row.startMs, value: value))
            }
        } else {
            for rollup in rollups {
                guard let day = dayDate(rollup.day, calendar: calendar) else { continue }
                let midnight = Int64((day.timeIntervalSince1970 * 1000).rounded())
                switch aggregation {
                case .count:
                    entries.append(.init(tMs: midnight, value: Double(rollup.count)))
                case .last:
                    entries.append(.init(tMs: rollup.lastAtMs ?? midnight, value: rollup.lastValue ?? rollup.avg))
                default:
                    entries.append(.init(tMs: midnight, value: primaryValue(rollup, type: type)))
                }
            }
        }
        // Rollup counts are already per-day totals; count them as sums.
        let effective: MetricsReference.Aggregation = (range != .day && aggregation == .count) ? .sum : aggregation
        return MetricsReference.headline(
            entries: entries, range: range, anchorMs: Int64((anchor.timeIntervalSince1970 * 1000).rounded()),
            zone: MetricsReference.Zone(calendar: calendar), weekStart: weekStart, aggregation: effective
        )
    }

    // MARK: - Day

    private static func dayPoints(type: HealthMetricType, rows: [HealthSampleRow], interval: DateInterval, calendar: Calendar) -> [HealthChartPoint] {
        let dayRows = rows.filter { $0.endDate > interval.start && $0.startDate < interval.end && !$0.isDeleted }
        if type.isBloodPressure {
            return dayRows.map { row in
                HealthChartPoint(start: row.startDate, end: row.endDate, value: row.value, min: row.value2, max: row.value, value2: row.value2, count: 1, stage: nil)
            }
        }
        if type.kind == .category, !type.isSleep {
            return dayRows.map { row in
                HealthChartPoint(start: row.startDate, end: row.endDate, value: row.value ?? 1, min: nil, max: nil, value2: nil, count: max(1, row.count), stage: row.categoryValue)
            }
        }
        return HealthRollupMath.hourlyBuckets(rows: dayRows, type: type, dayStart: interval.start, calendar: calendar).map { bucket in
            let start = interval.start.addingTimeInterval(Double(bucket.hour) * 3600)
            let value: Double?
            switch type.kind {
            case .cumulative, .duration, .session, .category: value = bucket.sum
            case .discrete, .series: value = bucket.avg
            }
            return HealthChartPoint(start: start, end: start.addingTimeInterval(3600), value: value, min: bucket.min, max: bucket.max, value2: nil, count: bucket.count, stage: nil)
        }
    }

    // MARK: - Daily / bucketed

    static func dayDate(_ day: String, calendar: Calendar) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var components = DateComponents()
        components.year = parts[0]
        components.month = parts[1]
        components.day = parts[2]
        return calendar.date(from: components)
    }

    private static func inInterval(_ day: String, _ interval: DateInterval, _ calendar: Calendar) -> Bool {
        guard let date = dayDate(day, calendar: calendar) else { return false }
        return date >= interval.start && date < interval.end
    }

    /// The number a day of `type` plots as (sum, duration, average, latest or count).
    static func primaryValue(_ rollup: HealthDailyRollupRow, type: HealthMetricType) -> Double? {
        switch type.kind {
        case .cumulative: return rollup.sum
        case .duration, .session: return rollup.durationS ?? rollup.sum
        case .discrete, .series:
            return type.aggregation == .latest ? (rollup.lastValue ?? rollup.avg) : rollup.avg
        case .category: return Double(rollup.count)
        }
    }

    private static func dailyPoint(_ rollup: HealthDailyRollupRow, type: HealthMetricType, calendar: Calendar) -> HealthChartPoint? {
        guard let start = dayDate(rollup.day, calendar: calendar) else { return nil }
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        return HealthChartPoint(
            start: start, end: end,
            value: primaryValue(rollup, type: type),
            min: type.isBloodPressure ? rollup.v2Min : rollup.min,
            max: type.isBloodPressure ? rollup.max : rollup.max,
            value2: rollup.v2Avg,
            count: rollup.count,
            stage: nil
        )
    }

    /// One 6M / Y bucket from its day rollups. Summed kinds plot the mean of the daily totals over
    /// days with data (shared `bucket_series`); discrete kinds a count-weighted average; latest the
    /// newest day's last value.
    private static func aggregatePoint(_ group: [HealthDailyRollupRow], start: Date, end: Date, type: HealthMetricType) -> HealthChartPoint {
        var point = HealthChartPoint(start: start, end: end, value: nil, min: nil, max: nil, value2: nil, count: 0, stage: nil)
        guard !group.isEmpty else { return point }
        let mins = group.compactMap { type.isBloodPressure ? $0.v2Min : $0.min }
        let maxs = group.compactMap(\.max)
        point.min = mins.min()
        point.max = maxs.max()
        point.count = group.reduce(0) { $0 + $1.count }
        switch type.kind {
        case .cumulative, .duration, .session:
            let daily = group.compactMap { primaryValue($0, type: type) }
            point.value = daily.isEmpty ? nil : MetricsReference.round3(daily.reduce(0, +) / Double(daily.count))
        case .discrete, .series:
            if type.aggregation == .latest {
                point.value = group.sorted { $0.day < $1.day }.last?.lastValue
            } else {
                var weighted = 0.0, weight = 0
                for rollup in group {
                    if let avg = rollup.avg {
                        weighted += avg * Double(max(1, rollup.count))
                        weight += max(1, rollup.count)
                    }
                }
                point.value = weight > 0 ? weighted / Double(weight) : nil
                if type.isBloodPressure {
                    let diastolic = group.compactMap(\.v2Avg)
                    point.value2 = diastolic.isEmpty ? nil : diastolic.reduce(0, +) / Double(diastolic.count)
                }
            }
        case .category:
            point.value = MetricsReference.round3(Double(point.count) / Double(group.count))
        }
        return point
    }

    // MARK: - Sleep

    /// D: the stage rows of the night that woke up on the anchor day (single source, raw times).
    private static func sleepStagePoints(range: HealthDetailRange, rows: [HealthSampleRow], calendar: Calendar, interval: DateInterval) -> [HealthChartPoint] {
        guard range == .day else { return [] }
        let nightRows = HealthSleepAnalysis.rowsByNight(rows)
            .filter { inInterval($0.key, interval, calendar) }
            .flatMap(\.value)
        guard let night = HealthSleepAnalysis.nights(rows: nightRows, calendar: calendar).last else { return [] }
        return nightRows
            .filter { $0.sourceID == night.source && $0.categoryValue != HealthSleepStage.outOfBed.rawValue }
            .map { HealthChartPoint(start: $0.startDate, end: $0.endDate, value: $0.durationSeconds, min: nil, max: nil, value2: nil, count: 1, stage: $0.categoryValue) }
            .sorted { $0.start < $1.start }
    }

    /// Sleep window (D) or clock-axis bars (W+) from the nights analysis and the shared sleep functions.
    /// W+ `points` carry one entry per bucket: value = mean asleep seconds, min / max = bed / wake offsets.
    private static func applySleep(_ series: inout HealthChartSeries, range: HealthDetailRange, anchor: Date, rows: [HealthSampleRow], zone: MetricsReference.Zone, calendar: Calendar, weekStart: MetricsReference.WeekStart) {
        if range == .day {
            series.points = []
            series.sleepWindow = MetricsReference.sleepNightWindow(
                rows: series.stagePoints.map { .init(startMs: ms($0.start), endMs: ms($0.end), stage: $0.stage ?? -1) },
                zone: zone
            )
            return
        }
        let live = rows.filter { !$0.isDeleted }
        let nights = HealthSleepAnalysis.nights(rows: live, calendar: calendar).filter { inInterval($0.nightOf, series.interval, calendar) }
        let result = MetricsReference.sleepRangeSeries(
            nights: nights.map { .init(wakeDay: $0.nightOf, bedtimeMs: $0.startMs, wakeMs: $0.endMs, asleepS: $0.asleepS, inBedS: $0.inBedS) },
            range: range, anchorMs: ms(anchor), zone: zone, weekStart: weekStart
        )
        series.sleepRange = result
        series.points = result.buckets.map { bucket in
            HealthChartPoint(
                start: date(bucket.startMs), end: date(bucket.endMs),
                value: bucket.count > 0 ? (bucket.asleepS ?? 0) : nil,
                min: bucket.bedOffsetMin, max: bucket.wakeOffsetMin, value2: bucket.inBedS, count: bucket.count, stage: nil
            )
        }
        guard range == .week || range == .month else { return }
        var segments: [SleepSegment] = []
        let byNight = HealthSleepAnalysis.rowsByNight(live)
        for night in nights {
            guard let wakeDay = MetricsReference.LocalDay.parse(night.nightOf) else { continue }
            let day = date(zone.midnight(wakeDay))
            for row in byNight[night.nightOf] ?? [] where row.sourceID == night.source {
                guard let code = row.categoryValue, (1...5).contains(code), row.endMs > row.startMs else { continue }
                segments.append(SleepSegment(
                    day: day, stage: code,
                    startMin: MetricsReference.sleepClockOffset(tMs: row.startMs, wakeDay: wakeDay, zone: zone),
                    endMin: MetricsReference.sleepClockOffset(tMs: row.endMs, wakeDay: wakeDay, zone: zone)
                ))
            }
        }
        series.sleepSegments = segments.sorted { ($0.day, $0.startMin) < ($1.day, $1.startMin) }
    }

    private static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    // MARK: - Highlights

    private static func highlights(
        type: HealthMetricType,
        points: [HealthChartPoint],
        rows: [HealthSampleRow],
        rollups: [HealthDailyRollupRow],
        interval: DateInterval,
        calendar: Calendar
    ) -> HealthHighlights {
        var highlights = HealthHighlights()
        let inRange = rollups.filter { inInterval($0.day, interval, calendar) }.sorted { $0.day < $1.day }
        if type.isSleep {
            let nights = HealthSleepAnalysis.nights(rows: rows.filter { !$0.isDeleted }, calendar: calendar).filter { inInterval($0.nightOf, interval, calendar) }
            let asleep = nights.map(\.asleepS)
            highlights.count = nights.count
            highlights.total = asleep.isEmpty ? nil : asleep.reduce(0, +)
            highlights.average = asleep.isEmpty ? nil : asleep.reduce(0, +) / Double(asleep.count)
            highlights.min = asleep.min()
            highlights.max = asleep.max()
            highlights.latest = nights.last?.asleepS
            highlights.latestAt = nights.last?.endDate
            return highlights
        }
        let values = points.compactMap(\.value)
        highlights.count = inRange.reduce(0) { $0 + $1.count }
        switch type.kind {
        case .cumulative, .duration, .session:
            if interval.duration <= 90_000 {
                highlights.total = values.isEmpty ? nil : values.reduce(0, +)
                highlights.average = values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
            } else {
                let daily = inRange.compactMap { primaryValue($0, type: type) }
                highlights.total = daily.isEmpty ? nil : daily.reduce(0, +)
                highlights.average = daily.isEmpty ? nil : daily.reduce(0, +) / Double(daily.count)
            }
            highlights.min = values.min()
            highlights.max = values.max()
        case .discrete, .series:
            var weighted = 0.0, weight = 0
            for rollup in inRange {
                if let avg = rollup.avg {
                    weighted += avg * Double(max(1, rollup.count))
                    weight += max(1, rollup.count)
                }
            }
            highlights.average = weight > 0 ? weighted / Double(weight) : (values.isEmpty ? nil : values.reduce(0, +) / Double(values.count))
            highlights.min = inRange.compactMap(\.min).min() ?? points.compactMap(\.min).min()
            highlights.max = inRange.compactMap(\.max).max() ?? points.compactMap(\.max).max()
        case .category:
            highlights.total = Double(highlights.count)
        }
        if let last = inRange.last {
            highlights.latest = type.kind == .cumulative || type.kind == .duration || type.kind == .session ? primaryValue(last, type: type) : last.lastValue
            highlights.latestAt = last.lastAtMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
        }
        return highlights
    }
}
