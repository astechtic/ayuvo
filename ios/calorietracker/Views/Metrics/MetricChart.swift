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

    var axisStride: (component: Calendar.Component, count: Int) {
        switch self {
        case .day: return (.hour, 6)
        case .week: return (.day, 1)
        case .month: return (.day, 7)
        case .sixMonths, .year: return (.month, 1)
        }
    }

    var axisFormat: Date.FormatStyle {
        switch self {
        case .day: return .dateTime.hour()
        case .week: return .dateTime.weekday(.narrow)
        case .month: return .dateTime.day()
        case .sixMonths, .year: return .dateTime.month(.narrow)
        }
    }
}

/// Swift Charts rendering of an app metric: flat bars (summed) or a line with points (latest),
/// a dashed goal rule and a scrub marker in the theme accent.
struct MetricChart: View {
    let metric: AppMetric
    let chartKind: MetricChartKind
    let tint: Color
    let series: HealthChartSeries
    @Binding var selected: HealthChartPoint?
    /// Goal in canonical units.
    let goal: Double?

    private var plotted: [HealthChartPoint] { series.points.filter { $0.value != nil } }
    private var unit: String { AppMetricFormat.chartUnit(metric) }

    private func y(_ value: Double?) -> Double? { AppMetricFormat.chartValue(value, metric: metric) }

    private var yDomain: ClosedRange<Double>? {
        guard chartKind == .line else { return nil }
        var values = plotted.compactMap { y($0.value) }
        values += plotted.compactMap { y($0.min) } + plotted.compactMap { y($0.max) }
        if let goal = y(goal) { values.append(goal) }
        guard let low = values.min(), let high = values.max() else { return nil }
        let pad = max((high - low) * 0.15, 1)
        return (low - pad)...(high + pad)
    }

    var body: some View {
        chart
            .chartXScale(domain: series.interval.start...series.interval.end)
            .chartXAxis {
                AxisMarks(values: .stride(by: series.range.axisStride.component, count: series.range.axisStride.count)) { _ in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4]))
                        .foregroundStyle(Color.primary.opacity(0.11))
                    AxisValueLabel(format: series.range.axisFormat)
                        .foregroundStyle(Color.secondary)
                }
            }
            .chartYAxis {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) {
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6))
                        .foregroundStyle(Color.primary.opacity(0.10))
                    AxisValueLabel()
                        .foregroundStyle(Color.secondary)
                }
            }
            .chartOverlay { proxy in
                ChartScrubOverlay(proxy: proxy, points: plotted, date: { $0.start }, selected: $selected) { point in
                    VStack(spacing: 2) {
                        Text(AppMetricFormat.text(point.value, metric: metric))
                            .font(.system(.footnote, design: .rounded, weight: .semibold))
                        Text(timeText(point))
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
            .animation(.snappy(duration: 0.16), value: selected?.start)
            .frame(height: 210)
            // Headroom so the top axis label is never clipped.
            .padding(.top, 8)
            .clipped()
            .accessibilityIdentifier("metric.chart")
            .accessibilityLabel(Text(MetricCatalog.descriptor(for: .app(metric)).title))
    }

    @ViewBuilder
    private var chart: some View {
        if let domain = yDomain {
            Chart { marks }
                .chartYScale(domain: domain)
        } else {
            Chart { marks }
        }
    }

    @ChartContentBuilder
    private var marks: some ChartContent {
        if chartKind == .line {
            ForEach(plotted) { point in
                if let value = y(point.value) {
                    LineMark(x: .value("Time", point.start, unit: series.range.barUnit), y: .value(unit, value))
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(tint)
                    PointMark(x: .value("Time", point.start, unit: series.range.barUnit), y: .value(unit, value))
                        .symbolSize(plotted.count > 31 ? 10 : 26)
                        .foregroundStyle(tint)
                }
            }
        } else {
            ForEach(plotted) { point in
                if let value = y(point.value) {
                    BarMark(x: .value("Time", point.start, unit: series.range.barUnit), y: .value(unit, value))
                        .foregroundStyle(tint)
                        .clipShape(.rect(cornerRadius: 3))
                        .opacity(selected == nil || selected == point ? 1 : 0.45)
                }
            }
        }
        if let goal = y(goal) {
            RuleMark(y: .value("Goal", goal))
                .foregroundStyle(Color.secondary.opacity(0.8))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [6, 4]))
        }
        if let selected {
            RuleMark(x: .value("Selected", selected.start, unit: series.range.barUnit))
                .foregroundStyle(AppColors.calorie.opacity(0.7))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
        }
    }

    private func timeText(_ point: HealthChartPoint) -> String {
        switch series.range {
        case .day: return point.start.formatted(.dateTime.hour().minute())
        case .week, .month: return point.start.formatted(.dateTime.month(.abbreviated).day())
        case .sixMonths: return "\(point.start.formatted(.dateTime.month(.abbreviated).day())) – \(point.end.addingTimeInterval(-1).formatted(.dateTime.month(.abbreviated).day()))"
        case .year: return point.start.formatted(.dateTime.month(.wide).year())
        }
    }
}
