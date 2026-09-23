import Charts
import SwiftUI

/// Swift Charts rendering for one health type (docs/charts.md): top-rounded bars for
/// cumulative/duration/session, min–max capsules + average dots for discrete/series, paired
/// bars for blood pressure, points for category types. Sleep has its own `SleepChart`.
struct HealthMetricChart: View {
    let type: HealthMetricType
    let series: HealthChartSeries
    @Binding var selected: HealthChartPoint?
    let calendar: Calendar
    /// Daily goal in canonical units (steps); hidden on D.
    var goal: Double?
    /// Tap on a bucket (drill-down or select); nil → the tap toggles the selection.
    var onTap: ((HealthChartPoint) -> Void)?

    private var isDurationInHours: Bool { type.isSleep || type.id == "time_in_daylight" }
    private var plotted: [HealthChartPoint] { series.points.filter { $0.value != nil } }
    private var tint: Color { type.category.tint }
    private var shownGoal: Double? { series.range == .day ? nil : displayY(goal) }
    private var isBarKind: Bool {
        !type.isBloodPressure && (type.kind == .cumulative || type.kind == .duration || type.kind == .session)
    }

    /// Y-axis value in display units (lb / °F / hours / minutes ...).
    func displayY(_ value: Double?) -> Double? {
        guard let value else { return nil }
        if type.unit == "s" {
            return isDurationInHours ? value / 3600 : value / 60
        }
        return HealthUnitFormatting.convertedValue(value, type: type)
    }

    var yUnitLabel: String {
        if type.unit == "s" { return isDurationInHours ? "h" : "min" }
        return HealthUnitFormatting.unitLabel(for: type)
    }

    private var barUnit: Calendar.Component { series.range.barUnit }

    private var ticks: MetricsReference.NiceTicks {
        var values: [Double] = []
        for point in plotted {
            if type.isBloodPressure {
                values += [point.value, point.value2 ?? point.min].compactMap { $0 }
            } else {
                values += [displayY(point.value), displayY(point.min), displayY(point.max)].compactMap { $0 }
            }
        }
        if let goal = shownGoal { values.append(goal) }
        return ChartAxisStyle.yTicks(values, includeZero: isBarKind || type.kind == .category)
    }

    var body: some View {
        let ticks = ticks
        Chart {
            if type.isBloodPressure {
                bloodPressureMarks
            } else {
                switch type.kind {
                case .cumulative, .duration, .session:
                    barMarks
                case .discrete, .series:
                    rangeMarks
                case .category:
                    categoryMarks
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
                selectionRule(selected)
                    .foregroundStyle(Color.primary.opacity(0.28))
                    .lineStyle(StrokeStyle(lineWidth: 1))
            }
        }
        .chartYScale(domain: ticks.min...ticks.max)
        .chartXScale(domain: series.interval.start...series.interval.end)
        .chartXAxis { ChartAxisStyle.xAxis(ChartAxisStyle.xMarks(range: series.range, interval: series.interval, calendar: calendar), range: series.range) }
        .chartYAxis { ChartAxisStyle.yAxis(ticks.ticks) }
        .chartOverlay { proxy in
            ChartScrubOverlay(proxy: proxy, points: plotted, date: { ChartAxisStyle.mid($0) }, selected: $selected, onTap: onTap, persistent: true) { point in
                HealthChartInspectorLabel(type: type, point: point, range: series.range, displayY: displayY, unit: yUnitLabel)
            }
        }
        .animation(ChartAxisStyle.revealAnimation, value: series)
        .frame(height: ChartAxisStyle.plotHeight)
        // Headroom so the top axis label is never clipped.
        .padding(.top, 8)
        .clipped()
        .accessibilityLabel(Text(type.displayName))
        .accessibilityValue(Text(accessibilitySummary))
    }

    private func faded(_ point: HealthChartPoint) -> Double {
        selected == nil || selected == point ? 1 : ChartAxisStyle.fadedOpacity
    }

    // MARK: - Marks

    private var spans: Bool { ChartAxisStyle.usesSpans(series.range) }

    /// Bar from `low` to `high` in the point's bucket (6M: its own bounds; else the range unit).
    @ChartContentBuilder
    private func bucketBar(_ point: HealthChartPoint, low: Double, high: Double) -> some ChartContent {
        if spans {
            let span = ChartAxisStyle.span(point)
            RectangleMark(xStart: .value("Start", span.start), xEnd: .value("End", span.end), yStart: .value("Low", low), yEnd: .value(yUnitLabel, high))
        } else {
            BarMark(x: .value("Time", point.start, unit: barUnit), yStart: .value("Low", low), yEnd: .value(yUnitLabel, high), width: ChartAxisStyle.barWidth)
        }
    }

    @ChartContentBuilder
    private func bucketPoint(_ point: HealthChartPoint, value: Double) -> some ChartContent {
        if spans {
            PointMark(x: .value("Time", ChartAxisStyle.mid(point)), y: .value(yUnitLabel, value))
        } else {
            PointMark(x: .value("Time", point.start, unit: barUnit), y: .value(yUnitLabel, value))
        }
    }

    @ChartContentBuilder
    private var barMarks: some ChartContent {
        ForEach(plotted) { point in
            if let value = displayY(point.value) {
                bucketBar(point, low: 0, high: value)
                    .foregroundStyle(tint)
                    .clipShape(ChartAxisStyle.barShape)
                    .opacity(faded(point))
                    .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                    .accessibilityValue(Text(HealthUnitFormatting.text(point.value, type: type)))
            }
        }
    }

    @ChartContentBuilder
    private var rangeMarks: some ChartContent {
        ForEach(plotted) { point in
            if let low = displayY(point.min), let high = displayY(point.max), high > low {
                bucketBar(point, low: low, high: high)
                    .foregroundStyle(tint.opacity(0.35))
                    .clipShape(Capsule())
                    .opacity(faded(point))
            }
            if let value = displayY(point.value) {
                bucketPoint(point, value: value)
                    .symbolSize(selected == point ? 70 : (plotted.count > 40 ? 12 : 28))
                    .foregroundStyle(tint)
                    .opacity(faded(point))
                    .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                    .accessibilityValue(Text(HealthUnitFormatting.text(point.value, type: type)))
            }
        }
    }

    @ChartContentBuilder
    private var bloodPressureMarks: some ChartContent {
        ForEach(plotted) { point in
            if let systolic = point.value {
                let diastolic = point.value2 ?? point.min ?? systolic
                bucketBar(point, low: diastolic, high: systolic)
                    .foregroundStyle(tint.opacity(0.75))
                    .clipShape(Capsule())
                    .opacity(faded(point))
                    .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                    .accessibilityValue(Text(HealthUnitFormatting.bloodPressureText(systolic: systolic, diastolic: diastolic)))
            }
        }
    }

    @ChartContentBuilder
    private var categoryMarks: some ChartContent {
        ForEach(plotted) { point in
            if let value = point.value {
                bucketPoint(point, value: value)
                    .foregroundStyle(tint)
                    .opacity(faded(point))
            }
        }
    }

    @ChartContentBuilder
    private func selectionRule(_ point: HealthChartPoint) -> some ChartContent {
        if spans {
            RuleMark(x: .value("Selected", ChartAxisStyle.mid(point)))
        } else {
            RuleMark(x: .value("Selected", point.start, unit: barUnit))
        }
    }

    private var accessibilitySummary: String {
        let highlights = series.highlights
        var parts: [String] = []
        if let total = highlights.total { parts.append(String(localized: "Total \(HealthUnitFormatting.text(total, type: type))")) }
        if let average = highlights.average { parts.append(String(localized: "Average \(HealthUnitFormatting.text(average, type: type))")) }
        if let min = highlights.min, let max = highlights.max {
            parts.append(String(localized: "Range \(HealthUnitFormatting.text(min, type: type)) to \(HealthUnitFormatting.text(max, type: type))"))
        }
        return parts.isEmpty ? String(localized: "No data in this range") : parts.joined(separator: ", ")
    }
}

/// Floating value label shown while scrubbing.
struct HealthChartInspectorLabel: View {
    let type: HealthMetricType
    let point: HealthChartPoint
    let range: HealthDetailRange
    let displayY: (Double?) -> Double?
    let unit: String

    private var valueText: String {
        if type.isBloodPressure {
            return HealthUnitFormatting.bloodPressureText(systolic: point.value, diastolic: point.value2 ?? point.min) + " mmHg"
        }
        if type.unit == "s", let value = point.value {
            return HealthUnitFormatting.durationText(seconds: value)
        }
        if type.kind == .discrete || type.kind == .series, let low = point.min, let high = point.max, high > low, point.count > 1 {
            let lowText = HealthUnitFormatting.display(low, type: type).value
            return "\(lowText)–\(HealthUnitFormatting.display(high, type: type).text)"
        }
        return HealthUnitFormatting.text(point.value, type: type)
    }

    private var timeText: String { ChartAxisStyle.bucketTitle(point, range: range) }

    var body: some View {
        VStack(spacing: 2) {
            Text(valueText)
                .font(.system(.footnote, design: .rounded, weight: .semibold))
            Text(timeText)
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
