import Charts
import SwiftUI

/// Swift Charts rendering for one metric: bars for cumulative/duration/session, min–max
/// range bars + average points for discrete/series, paired bars for blood pressure, stage
/// strips / stacked stage bars for sleep, points for category types.
struct HealthMetricChart: View {
    let type: HealthMetricType
    let series: HealthChartSeries
    @Binding var selected: HealthChartPoint?
    let calendar: Calendar

    private var isDurationInHours: Bool { type.isSleep || type.id == "time_in_daylight" }

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

    private var barUnit: Calendar.Component {
        switch series.range {
        case .day: return .hour
        case .week, .month: return .day
        case .sixMonths: return .weekOfYear
        case .year: return .month
        }
    }

    var body: some View {
        Chart {
            if type.isSleep {
                sleepMarks
            } else if type.isBloodPressure {
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
            if let selected, !type.isSleep {
                RuleMark(x: .value("Selected", selected.start, unit: barUnit))
                    .foregroundStyle(Color.primary.opacity(0.25))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
        }
        .chartXScale(domain: series.interval.start...series.interval.end)
        .chartXAxis { xAxis }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) {
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6))
                    .foregroundStyle(Color.primary.opacity(0.10))
                AxisValueLabel()
                    .foregroundStyle(Color.secondary)
            }
        }
        .chartPlotStyle { plotArea in
            plotArea.background(
                type.category.tint.opacity(0.04),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
        }
        .chartLegend(type.isSleep && series.range != .day ? .visible : .hidden)
        .chartOverlay { proxy in
            if !type.isSleep {
                ChartScrubOverlay(proxy: proxy, points: series.points, date: { $0.start }, selected: $selected) { point in
                    HealthChartInspectorLabel(type: type, point: point, range: series.range, displayY: displayY, unit: yUnitLabel)
                }
            }
        }
        .animation(.snappy(duration: 0.16), value: selected?.start)
        .frame(height: 210)
        .clipped()
        .accessibilityLabel(Text(type.displayName))
        .accessibilityValue(Text(accessibilitySummary))
    }

    // MARK: - Marks

    @ChartContentBuilder
    private var barMarks: some ChartContent {
        ForEach(series.points) { point in
            BarMark(
                x: .value("Time", point.start, unit: barUnit),
                y: .value(yUnitLabel, displayY(point.value) ?? 0)
            )
            .foregroundStyle(
                LinearGradient(colors: [type.category.tint.opacity(0.55), type.category.tint], startPoint: .bottom, endPoint: .top)
            )
            .clipShape(.rect(cornerRadius: 3))
            .opacity(selected == nil || selected == point ? 1 : 0.45)
        }
    }

    @ChartContentBuilder
    private var rangeMarks: some ChartContent {
        ForEach(series.points) { point in
            if let low = displayY(point.min), let high = displayY(point.max), high > low {
                BarMark(
                    x: .value("Time", point.start, unit: barUnit),
                    yStart: .value("Min", low),
                    yEnd: .value("Max", high),
                    width: .ratio(0.55)
                )
                .foregroundStyle(type.category.tint.opacity(0.35))
                .clipShape(.rect(cornerRadius: 3))
            }
            if let value = displayY(point.value) {
                PointMark(
                    x: .value("Time", point.start, unit: barUnit),
                    y: .value(yUnitLabel, value)
                )
                .symbolSize(series.points.count > 40 ? 12 : 28)
                .foregroundStyle(type.category.tint)
            }
        }
    }

    @ChartContentBuilder
    private var bloodPressureMarks: some ChartContent {
        ForEach(series.points) { point in
            if let systolic = point.value {
                let diastolic = point.value2 ?? point.min ?? systolic
                BarMark(
                    x: .value("Time", point.start, unit: barUnit),
                    yStart: .value("Diastolic", diastolic),
                    yEnd: .value("Systolic", systolic),
                    width: .ratio(0.5)
                )
                .foregroundStyle(type.category.tint.opacity(0.75))
                .clipShape(.rect(cornerRadius: 3))
            }
        }
    }

    @ChartContentBuilder
    private var categoryMarks: some ChartContent {
        ForEach(series.points) { point in
            PointMark(
                x: .value("Time", point.start, unit: barUnit),
                y: .value("Count", point.value ?? 1)
            )
            .foregroundStyle(type.category.tint)
        }
    }

    @ChartContentBuilder
    private var sleepMarks: some ChartContent {
        if series.range == .day {
            ForEach(series.stagePoints) { point in
                BarMark(
                    xStart: .value("Start", point.start),
                    xEnd: .value("End", point.end),
                    y: .value("Stage", Self.stageName(point.stage))
                )
                .foregroundStyle(Self.stageColor(point.stage))
                .clipShape(.rect(cornerRadius: 3))
            }
        } else {
            ForEach(series.stagePoints) { point in
                BarMark(
                    x: .value("Night", point.start, unit: .day),
                    y: .value("Hours", (point.value ?? 0) / 3600)
                )
                .foregroundStyle(by: .value("Stage", Self.stageName(point.stage)))
                .clipShape(.rect(cornerRadius: 2))
            }
        }
    }

    // MARK: - Axis

    private var xAxis: some AxisContent {
        let (component, count, format): (Calendar.Component, Int, Date.FormatStyle) = {
            switch series.range {
            case .day: return (.hour, 6, .dateTime.hour())
            case .week: return (.day, 1, .dateTime.weekday(.narrow))
            case .month: return (.day, 7, .dateTime.day())
            case .sixMonths: return (.month, 1, .dateTime.month(.narrow))
            case .year: return (.month, 1, .dateTime.month(.narrow))
            }
        }()
        return AxisMarks(values: .stride(by: component, count: count)) { _ in
            AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4]))
                .foregroundStyle(Color.primary.opacity(0.11))
            AxisValueLabel(format: format)
                .foregroundStyle(Color.secondary)
        }
    }

    // MARK: - Helpers

    static func stageName(_ code: Int?) -> String {
        guard let code, let stage = HealthSleepStage(rawValue: code) else { return String(localized: "Asleep") }
        return String(localized: String.LocalizationValue(stage.englishName))
    }

    static func stageColor(_ code: Int?) -> Color {
        switch code.flatMap(HealthSleepStage.init(rawValue:)) {
        case .inBed: return .gray.opacity(0.5)
        case .awake: return .orange
        case .light, .asleepUnspecified: return .blue
        case .deep: return .indigo
        case .rem: return .cyan
        case .outOfBed, .none: return .gray
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

    private var timeText: String {
        switch range {
        case .day: return point.start.formatted(.dateTime.hour().minute())
        case .week, .month: return point.start.formatted(.dateTime.month(.abbreviated).day())
        case .sixMonths: return "\(point.start.formatted(.dateTime.month(.abbreviated).day())) – \(point.end.addingTimeInterval(-1).formatted(.dateTime.month(.abbreviated).day()))"
        case .year: return point.start.formatted(.dateTime.month(.wide).year())
        }
    }

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
