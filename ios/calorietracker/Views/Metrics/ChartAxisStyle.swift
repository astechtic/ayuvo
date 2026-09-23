import Charts
import SwiftUI

/// Shared axis rules of every metric chart (docs/charts.md): nice y ticks sitting on hairline
/// gridlines, x labels centred under their bucket (shared `x_ticks`), one plot height.
enum ChartAxisStyle {
    static let plotHeight: CGFloat = 200
    static let gridOpacity = 0.08
    static let barWidth = MarkDimension.ratio(0.6)
    static let fadedOpacity = 0.4
    static let revealAnimation = Animation.easeOut(duration: 0.25)

    /// Top-only rounded bar corners.
    static let barShape = UnevenRoundedRectangle(topLeadingRadius: 4, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: 4)

    struct XMarks: Equatable {
        /// Gridline dates (bucket starts of the labelled buckets).
        var grid: [Date]
        /// Label dates: bucket middles (bucket starts on D, where labels mark the hour).
        var labels: [Date]
    }

    static func xMarks(range: HealthDetailRange, interval: DateInterval, calendar: Calendar, weekStart: MetricsReference.WeekStart = ActivitySettings.weekStart()) -> XMarks {
        let zone = MetricsReference.Zone(calendar: calendar)
        // Any instant of the interval's last day re-derives the same interval (6M counts back from the anchor month).
        let anchorMs = ms(interval.end) - 1
        let buckets = MetricsReference.bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs).buckets
        let picked = MetricsReference.xTicks(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart)
            .filter { buckets.indices.contains($0) }
            .map { buckets[$0] }
        let grid = picked.map { date($0.startMs) }
        let labels = range == .day ? grid : picked.map { date($0.startMs + ($0.endMs - $0.startMs) / 2) }
        return XMarks(grid: grid, labels: labels)
    }

    static func labelFormat(_ range: HealthDetailRange) -> Date.FormatStyle {
        switch range {
        case .day: return .dateTime.hour()
        case .week: return .dateTime.weekday(.narrow)
        case .month: return .dateTime.day()
        case .sixMonths, .year: return .dateTime.month(.narrow)
        }
    }

    /// Shared `nice_ticks` over the plotted values (display units). No values → [0, 1].
    static func yTicks(_ values: [Double], includeZero: Bool) -> MetricsReference.NiceTicks {
        let finite = values.filter(\.isFinite)
        return MetricsReference.niceTicks(min: finite.min() ?? 0, max: finite.max() ?? 0, count: 4, includeZero: includeZero)
    }

    @AxisContentBuilder
    static func xAxis(_ marks: XMarks, range: HealthDetailRange) -> some AxisContent {
        AxisMarks(values: marks.grid) { _ in
            AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [2, 3]))
                .foregroundStyle(Color.primary.opacity(gridOpacity))
        }
        AxisMarks(values: marks.labels) { _ in
            if range == .day {
                AxisValueLabel(format: labelFormat(range))
                    .foregroundStyle(Color.secondary)
            } else {
                // x_ticks already thins the labels; never let Charts drop more of them.
                AxisValueLabel(format: labelFormat(range), anchor: .top, collisionResolution: .disabled)
                    .foregroundStyle(Color.secondary)
            }
        }
    }

    @AxisContentBuilder
    static func yAxis(_ ticks: [Double], label: @escaping (Double) -> String = numberText) -> some AxisContent {
        AxisMarks(position: .trailing, values: ticks) { value in
            AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                .foregroundStyle(Color.primary.opacity(value.index == 0 ? 0.25 : gridOpacity))
            AxisValueLabel {
                if let v = value.as(Double.self) {
                    Text(label(v))
                }
            }
            .foregroundStyle(Color.secondary)
        }
    }

    nonisolated static func numberText(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(0...2)))
    }

    /// Date text of one bucket for callouts and the selected headline.
    static func bucketTitle(_ point: HealthChartPoint, range: HealthDetailRange) -> String {
        switch range {
        case .day:
            return "\(point.start.formatted(.dateTime.hour().minute())) – \(point.end.formatted(.dateTime.hour().minute()))"
        case .week, .month:
            return point.start.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        case .sixMonths:
            return "\(point.start.formatted(.dateTime.month(.abbreviated).day())) – \(point.end.addingTimeInterval(-1).formatted(.dateTime.month(.abbreviated).day()))"
        case .year:
            return point.start.formatted(.dateTime.month(.wide).year())
        }
    }

    /// 6M buckets are weeks clipped to the interval, so they are drawn from their own bounds
    /// (calendar `weekOfYear` units would misalign them); every other range bins by its unit.
    static func usesSpans(_ range: HealthDetailRange) -> Bool { range == .sixMonths }

    /// Bucket middle: where points, rules and callouts sit.
    static func mid(_ point: HealthChartPoint) -> Date {
        point.start.addingTimeInterval(point.end.timeIntervalSince(point.start) / 2)
    }

    /// A span bar's x bounds: 60% of the bucket, centred.
    static func span(_ point: HealthChartPoint) -> (start: Date, end: Date) {
        let inset = point.end.timeIntervalSince(point.start) * 0.2
        return (point.start.addingTimeInterval(inset), point.end.addingTimeInterval(-inset))
    }

    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
}

/// Floating label shown above the selected bucket.
struct ChartCallout: View {
    let value: String
    let caption: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(.footnote, design: .rounded, weight: .semibold))
            Text(caption)
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
