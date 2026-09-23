import Charts
import SwiftUI

extension HealthDetailRange {
    /// Bar width unit of the chart.
    var barUnit: Calendar.Component {
        switch self {
        case .day: return .hour
        case .week, .month: return .day
        case .sixMonths: return .weekOfYear
        case .year: return .month
        }
    }
}

/// Swift Charts rendering of an app metric (docs/charts.md): top-rounded bars (summed) or a
/// monotone line with a soft area (latest), a dashed goal rule, nice y ticks and a persistent
/// selection that the detail headline mirrors.
struct MetricChart: View {
    let metric: AppMetric
    let chartKind: MetricChartKind
    let tint: Color
    let series: HealthChartSeries
    @Binding var selected: HealthChartPoint?
    /// Goal in canonical units (hidden on D: a daily goal against hourly bars says nothing).
    let goal: Double?
    var calendar: Calendar = .current
    /// Tap on a bucket (drill-down or select); nil → the tap toggles the selection.
    var onTap: ((HealthChartPoint) -> Void)?

    private var plotted: [HealthChartPoint] { series.points.filter { $0.value != nil } }
    private var unit: String { AppMetricFormat.chartUnit(metric) }
    private var shownGoal: Double? { series.range == .day ? nil : y(goal) }

    private func y(_ value: Double?) -> Double? { AppMetricFormat.chartValue(value, metric: metric) }

    private var ticks: MetricsReference.NiceTicks {
        var values = plotted.compactMap { y($0.value) }
        if chartKind == .line {
            values += plotted.compactMap { y($0.min) } + plotted.compactMap { y($0.max) }
        }
        if let goal = shownGoal { values.append(goal) }
        return ChartAxisStyle.yTicks(values, includeZero: chartKind != .line)
    }

    var body: some View {
        let ticks = ticks
        Chart { marks(floor: ticks.min) }
            .chartYScale(domain: ticks.min...ticks.max)
            .chartXScale(domain: series.interval.start...series.interval.end)
            .chartXAxis { ChartAxisStyle.xAxis(ChartAxisStyle.xMarks(range: series.range, interval: series.interval, calendar: calendar), range: series.range) }
            .chartYAxis { ChartAxisStyle.yAxis(ticks.ticks) }
            .chartOverlay { proxy in
                ChartScrubOverlay(proxy: proxy, points: plotted, date: { ChartAxisStyle.mid($0) }, selected: $selected, onTap: onTap, persistent: true) { point in
                    ChartCallout(value: AppMetricFormat.text(point.value, metric: metric), caption: ChartAxisStyle.bucketTitle(point, range: series.range))
                }
            }
            .animation(ChartAxisStyle.revealAnimation, value: series)
            .frame(height: ChartAxisStyle.plotHeight)
            // Headroom so the top axis label is never clipped.
            .padding(.top, 8)
            .clipped()
            .accessibilityIdentifier("metric.chart")
            .accessibilityLabel(Text(MetricCatalog.descriptor(for: .app(metric)).title))
    }

    private var spans: Bool { ChartAxisStyle.usesSpans(series.range) }

    @ChartContentBuilder
    private func marks(floor: Double) -> some ChartContent {
        if chartKind == .line {
            ForEach(plotted) { point in
                if let value = y(point.value) {
                    if spans {
                        lineMarks(point, x: .value("Time", ChartAxisStyle.mid(point)), value: value, floor: floor)
                    } else {
                        lineMarks(point, x: .value("Time", point.start, unit: series.range.barUnit), value: value, floor: floor)
                    }
                }
            }
        } else {
            ForEach(plotted) { point in
                if let value = y(point.value) {
                    if spans {
                        let span = ChartAxisStyle.span(point)
                        BarMark(xStart: .value("Start", span.start), xEnd: .value("End", span.end), y: .value(unit, value))
                            .foregroundStyle(tint)
                            .clipShape(ChartAxisStyle.barShape)
                            .opacity(faded(point))
                            .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                            .accessibilityValue(Text(AppMetricFormat.text(point.value, metric: metric)))
                    } else {
                        BarMark(x: .value("Time", point.start, unit: series.range.barUnit), y: .value(unit, value), width: ChartAxisStyle.barWidth)
                            .foregroundStyle(tint)
                            .clipShape(ChartAxisStyle.barShape)
                            .opacity(faded(point))
                            .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                            .accessibilityValue(Text(AppMetricFormat.text(point.value, metric: metric)))
                    }
                }
            }
        }
        if let goal = shownGoal {
            RuleMark(y: .value("Goal", goal))
                .foregroundStyle(Color.secondary.opacity(0.8))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [6, 4]))
                .annotation(position: .top, alignment: .trailing, spacing: 2) {
                    Text("Goal")
                        .font(.system(.caption2, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }
        }
        if let selected {
            if spans {
                RuleMark(x: .value("Selected", ChartAxisStyle.mid(selected)))
                    .foregroundStyle(Color.primary.opacity(0.28))
                    .lineStyle(StrokeStyle(lineWidth: 1))
            } else {
                RuleMark(x: .value("Selected", selected.start, unit: series.range.barUnit))
                    .foregroundStyle(Color.primary.opacity(0.28))
                    .lineStyle(StrokeStyle(lineWidth: 1))
            }
        }
    }

    private func faded(_ point: HealthChartPoint) -> Double {
        selected == nil || selected == point ? 1 : ChartAxisStyle.fadedOpacity
    }

    @ChartContentBuilder
    private func lineMarks(_ point: HealthChartPoint, x: PlottableValue<Date>, value: Double, floor: Double) -> some ChartContent {
        AreaMark(x: x, yStart: .value("Floor", floor), yEnd: .value(unit, value))
            .interpolationMethod(.monotone)
            .foregroundStyle(LinearGradient(colors: [tint.opacity(0.22), tint.opacity(0.02)], startPoint: .top, endPoint: .bottom))
        LineMark(x: x, y: .value(unit, value))
            .interpolationMethod(.monotone)
            .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            .foregroundStyle(tint)
        if plotted.count <= 40 || selected == point {
            PointMark(x: x, y: .value(unit, value))
                .symbolSize(selected == point ? 70 : 28)
                .foregroundStyle(tint)
                .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                .accessibilityValue(Text(AppMetricFormat.text(point.value, metric: metric)))
        }
    }
}
