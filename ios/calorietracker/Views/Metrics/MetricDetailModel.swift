import Foundation
import Observation

/// State of one metric detail screen: range, anchor, selection and the loaded series.
@Observable
@MainActor
final class MetricDetailModel {
    let key: MetricKey
    var range: HealthDetailRange
    var anchor = Date()
    var selected: HealthChartPoint?
    private(set) var series: HealthChartSeries?
    private(set) var isLoading = false

    init(key: MetricKey, range: HealthDetailRange) {
        self.key = key
        self.range = range
    }

    func bounds(calendar: Calendar) -> MetricsReference.Bounds {
        range.bounds(containing: anchor, calendar: calendar)
    }

    func canGoForward(calendar: Calendar) -> Bool {
        bounds(calendar: calendar).canGoForward
    }

    /// Reload key: metric, range, anchor day and the data revision behind it.
    func loadKey(sources: MetricDataSources, calendar: Calendar) -> String {
        let revision: Int
        switch key {
        case .app(let metric): revision = sources.revision(for: metric)
        case .health: revision = sources.health.snapshotRevision
        }
        let day = Int(calendar.startOfDay(for: anchor).timeIntervalSince1970)
        return "\(key.id)|\(range.rawValue)|\(day)|\(revision)|\(ActivitySettings.weekStart().rawValue)"
    }

    func load(sources: MetricDataSources, calendar: Calendar) async {
        isLoading = true
        switch key {
        case .app(let metric):
            series = await MetricSeriesCache.shared.series(for: metric, range: range, anchor: anchor, sources: sources, calendar: calendar)
        case .health(let typeID):
            series = await sources.health.series(typeID: typeID, range: range, anchor: anchor)
        }
        isLoading = false
    }

    func step(_ direction: Int, calendar: Calendar) {
        if direction > 0, !canGoForward(calendar: calendar) { return }
        selected = nil
        anchor = range.stepped(anchor, direction: direction, calendar: calendar)
    }

    func rangeTitle(calendar: Calendar) -> String {
        let interval = range.interval(containing: anchor, calendar: calendar)
        let start = interval.start
        let end = interval.end.addingTimeInterval(-1)
        switch range {
        case .day:
            return start.formatted(date: .abbreviated, time: .omitted)
        case .week:
            return "\(start.formatted(.dateTime.month(.abbreviated).day())) – \(end.formatted(.dateTime.month(.abbreviated).day().year()))"
        case .month:
            return start.formatted(.dateTime.month(.wide).year())
        case .sixMonths:
            return "\(start.formatted(.dateTime.month(.abbreviated))) – \(end.formatted(.dateTime.month(.abbreviated).year()))"
        case .year:
            return start.formatted(.dateTime.year())
        }
    }
}
