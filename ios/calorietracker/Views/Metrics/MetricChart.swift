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

/// How a chart turns canonical values into axis values and text (app metrics follow the unit preferences;
/// nutrient metrics use their reference unit).
struct MetricChartFormat {
    let title: String
    let unit: String
    let text: (Double?) -> String
    let chartValue: (Double?) -> Double?

    static func app(_ metric: AppMetric) -> MetricChartFormat {
        MetricChartFormat(
            title: MetricCatalog.descriptor(for: .app(metric)).title,
            unit: AppMetricFormat.chartUnit(metric),
            text: { AppMetricFormat.text($0, metric: metric) },
            chartValue: { AppMetricFormat.chartValue($0, metric: metric) }
        )
    }

    static func nutrient(_ key: String) -> MetricChartFormat {
        MetricChartFormat(
            title: NutrientCatalog.title(key),
            unit: NutrientCatalog.unit(key),
            text: { NutrientCatalog.text($0, key: key) },
            chartValue: { $0 }
        )
    }
}

/// A dashed horizontal reference rule (goal, Recommended, Limit, Upper limit), in canonical units.
struct ChartReferenceLine: Hashable, Identifiable {
    let label: String
    let value: Double
    /// Upper limits use the warning colour; goals and recommendations the metric's tint.
    var isWarning = false

    var id: String { "\(label)|\(value)" }

    /// The single neutral goal rule of app and health metrics (profile / prefs goal sources).
    static func goal(_ value: Double?) -> [ChartReferenceLine] {
        value.map { [ChartReferenceLine(label: String(localized: "Goal"), value: $0)] } ?? []
    }
}

/// A reference rule placed on the y axis (display units).
struct ShownReferenceLine: Identifiable {
    let line: ChartReferenceLine
    let y: Double
    var id: String { line.id }
}

/// Dashed reference rules shared by `MetricChart` (app and `nutrient:` metrics) and `HealthMetricChart` (health
/// types, including the health nutrition types, docs/nutrients.md §5a). Shown on W / M / 6M / Y only: those bars
/// are daily totals or means of daily totals, so a daily reference is comparable; on D (hourly bars) they hide.
struct ReferenceRuleMarks: ChartContent {
    let lines: [ShownReferenceLine]
    /// Every rule of the chart (visible or not): with more than one the rules take colours and split labels.
    let ruleCount: Int
    let tint: Color
    /// The y-domain: a rule at the top of the plot puts its label under the line, so it is never clipped.
    let domain: ClosedRange<Double>
    let text: (Double) -> String

    /// The rules to draw, in display units. `y` converts a canonical value to the axis value.
    static func shown(_ lines: [ChartReferenceLine], range: HealthDetailRange, y: (Double?) -> Double?) -> [ShownReferenceLine] {
        guard range != .day else { return [] }
        return lines.compactMap { line in y(line.value).map { ShownReferenceLine(line: line, y: $0) } }
    }

    var body: some ChartContent {
        ForEach(lines) { item in
            RuleMark(y: .value(item.line.label, item.y))
                .foregroundStyle(color(item.line))
                .lineStyle(StrokeStyle(lineWidth: ruleCount > 1 ? 1.5 : 1, dash: [6, 4]))
                // Two rules can sit close together: the recommendation labels on the left, limits on the right.
                .annotation(position: nearTop(item.y) ? .bottom : .top, alignment: ruleCount > 1 && !item.line.isWarning ? .leading : .trailing, spacing: 2) {
                    ReferenceRuleLabel(text: item.line.label, colour: ruleCount > 1 ? color(item.line) : .secondary)
                }
                .accessibilityLabel(Text(item.line.label))
                .accessibilityValue(Text(text(item.line.value)))
        }
    }

    private func nearTop(_ y: Double) -> Bool {
        let span = domain.upperBound - domain.lowerBound
        return span > 0 && y >= domain.upperBound - span * 0.1
    }

    /// A single goal rule stays neutral grey (the app metrics' look); with reference rules the recommendation
    /// takes the domain tint and the upper limit the warning colour.
    private func color(_ line: ChartReferenceLine) -> Color {
        if line.isWarning { return SettingsTint.warning }
        return ruleCount > 1 || line.label != String(localized: "Goal") ? tint : Color.secondary.opacity(0.8)
    }
}

/// Rule label on a solid chip in the card colour (light and dark), so a bar or the other rule running through it
/// never makes it unreadable.
struct ReferenceRuleLabel: View {
    let text: String
    let colour: Color

    var body: some View {
        Text(text)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(colour)
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: Capsule())
    }
}

/// Swift Charts rendering of an app or nutrient metric (docs/charts.md): top-rounded bars (summed) or a
/// monotone line with a soft area (latest), dashed reference rules, nice y ticks and a persistent
/// selection that the detail headline mirrors.
struct MetricChart: View {
    let format: MetricChartFormat
    let chartKind: MetricChartKind
    let tint: Color
    let series: HealthChartSeries
    @Binding var selected: HealthChartPoint?
    /// Reference rules in canonical units (hidden on D: a daily amount against hourly bars says nothing).
    let referenceLines: [ChartReferenceLine]
    var calendar: Calendar = .current
    /// Tap on a bucket (drill-down or select); nil → the tap toggles the selection.
    var onTap: ((HealthChartPoint) -> Void)?

    init(metric: AppMetric, chartKind: MetricChartKind, tint: Color, series: HealthChartSeries, selected: Binding<HealthChartPoint?>,
         goal: Double?, calendar: Calendar = .current, onTap: ((HealthChartPoint) -> Void)? = nil) {
        self.init(format: .app(metric), chartKind: chartKind, tint: tint, series: series, selected: selected,
                  referenceLines: ChartReferenceLine.goal(goal),
                  calendar: calendar, onTap: onTap)
    }

    init(format: MetricChartFormat, chartKind: MetricChartKind, tint: Color, series: HealthChartSeries, selected: Binding<HealthChartPoint?>,
         referenceLines: [ChartReferenceLine], calendar: Calendar = .current, onTap: ((HealthChartPoint) -> Void)? = nil) {
        self.format = format
        self.chartKind = chartKind
        self.tint = tint
        self.series = series
        _selected = selected
        self.referenceLines = referenceLines
        self.calendar = calendar
        self.onTap = onTap
    }

    private var plotted: [HealthChartPoint] { series.points.filter { $0.value != nil } }
    private var unit: String { format.unit }

    /// Rules shown on W / M / 6M / Y only (daily totals or means of daily totals).
    private var shownLines: [ShownReferenceLine] {
        ReferenceRuleMarks.shown(referenceLines, range: series.range, y: y)
    }

    private func y(_ value: Double?) -> Double? { format.chartValue(value) }

    private var ticks: MetricsReference.NiceTicks {
        var values = plotted.compactMap { y($0.value) }
        if chartKind == .line {
            values += plotted.compactMap { y($0.min) } + plotted.compactMap { y($0.max) }
        }
        values += shownLines.map(\.y)
        return ChartAxisStyle.yTicks(values, includeZero: chartKind != .line)
    }

    var body: some View {
        let ticks = ticks
        Chart { marks(floor: ticks.min, ceiling: ticks.max) }
            .chartYScale(domain: ticks.min...ticks.max)
            .chartXScale(domain: series.interval.start...series.interval.end)
            .chartXAxis { ChartAxisStyle.xAxis(ChartAxisStyle.xMarks(range: series.range, interval: series.interval, calendar: calendar), range: series.range) }
            .chartYAxis { ChartAxisStyle.yAxis(ticks.ticks) }
            .chartOverlay { proxy in
                ChartScrubOverlay(proxy: proxy, points: plotted, date: { ChartAxisStyle.mid($0) }, selected: $selected, onTap: onTap, persistent: true) { point in
                    ChartCallout(value: format.text(point.value), caption: ChartAxisStyle.bucketTitle(point, range: series.range))
                }
            }
            .animation(ChartAxisStyle.revealAnimation, value: series)
            .frame(height: ChartAxisStyle.plotHeight)
            // Headroom so the top axis label is never clipped.
            .padding(.top, 8)
            .clipped()
            .accessibilityIdentifier("metric.chart")
            .accessibilityLabel(Text(format.title))
    }

    private var spans: Bool { ChartAxisStyle.usesSpans(series.range) }

    @ChartContentBuilder
    private func marks(floor: Double, ceiling: Double) -> some ChartContent {
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
                            .accessibilityValue(Text(format.text(point.value)))
                    } else {
                        BarMark(x: .value("Time", point.start, unit: series.range.barUnit), y: .value(unit, value), width: ChartAxisStyle.barWidth)
                            .foregroundStyle(tint)
                            .clipShape(ChartAxisStyle.barShape)
                            .opacity(faded(point))
                            .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                            .accessibilityValue(Text(format.text(point.value)))
                    }
                }
            }
        }
        ReferenceRuleMarks(lines: shownLines, ruleCount: referenceLines.count, tint: tint, domain: floor...ceiling,
                           text: { format.text($0) })
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
                .accessibilityValue(Text(format.text(point.value)))
        }
    }
}
