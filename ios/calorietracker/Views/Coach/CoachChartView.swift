import Charts
import SwiftUI

/// A chart Coach drew (docs/coach.md §5). The spec has already been parsed and validated by
/// `CR.parseChartSpec`, so this view only lays out numbers it was handed — it never computes,
/// estimates or fills one in.
///
/// Visual rules come from `docs/charts.md` via `ChartAxisStyle`, so a chart in chat is
/// indistinguishable from a chart in the rest of the app.
struct CoachChartView: View {
    let spec: RJ

    private var type: String { spec["type"].string ?? "bar" }
    private var series: [Series] { Series.all(from: spec) }
    private var unit: String? { spec["unit"].string }
    /// Points the spec carried that held no readable reading (docs/coach.md §5).
    private var dropped: Int { spec["dropped"].double.map { Int($0) } ?? 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title = spec["title"].string {
                Text(title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
            }
            chart
                .frame(height: ChartAxisStyle.plotHeight)
            if series.count > 1 {
                legend
            }
            if let note = spec["note"].string {
                Text(note)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            // A reading that could not be read is left out of the plot, so the chart says so rather
            // than quietly showing fewer bars than the text talks about (docs/coach.md §5).
            if dropped > 0 {
                Label(
                    dropped == 1
                        ? String(localized: "1 reading could not be read and is not shown")
                        : String(localized: "\(dropped) readings could not be read and are not shown"),
                    systemImage: "exclamationmark.circle"
                )
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("coach.chart.dropped")
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(AppColors.calorie.opacity(0.12), lineWidth: 0.7)
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(CR.chartAccessibilityText(spec)))
        .contextMenu {
            Button {
                UIPasteboard.general.string = dataText
            } label: {
                Label("Copy data", systemImage: "doc.on.doc")
            }
        }
    }

    // MARK: - Marks

    @ViewBuilder private var chart: some View {
        switch type {
        case "pie": pieChart
        case "progress": progressChart
        default: cartesianChart
        }
    }

    private var cartesianChart: some View {
        Chart {
            ForEach(series) { entry in
                ForEach(entry.points) { point in
                    marks(for: point, in: entry)
                }
            }
        }
        .chartYScale(domain: yDomain)
        .chartYAxis {
            AxisMarks(values: yTicks) { value in
                AxisGridLine().foregroundStyle(Color.primary.opacity(ChartAxisStyle.gridOpacity))
                AxisValueLabel {
                    if let number = value.as(Double.self) {
                        Text(Self.numberText(number))
                            .font(.system(.caption2, design: .rounded))
                    }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 6)) { _ in
                AxisValueLabel()
                    .font(.system(.caption2, design: .rounded))
            }
        }
        .chartForegroundStyleScale(domain: series.map(\.label), range: Self.palette(series.count))
        .chartLegend(.hidden)
    }

    @ChartContentBuilder
    private func marks(for point: Point, in entry: Series) -> some ChartContent {
        switch type {
        case "line", "scatter":
            if type == "line" {
                LineMark(x: .value("", point.x), y: .value("", point.y))
                    .foregroundStyle(by: .value("", entry.label))
                    .interpolationMethod(.monotone)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            PointMark(x: .value("", point.x), y: .value("", point.y))
                .foregroundStyle(by: .value("", entry.label))
                .symbolSize(type == "scatter" ? 40 : 22)
        case "area":
            AreaMark(x: .value("", point.x), y: .value("", point.y))
                .foregroundStyle(by: .value("", entry.label))
                .interpolationMethod(.monotone)
                .opacity(0.25)
            LineMark(x: .value("", point.x), y: .value("", point.y))
                .foregroundStyle(by: .value("", entry.label))
                .interpolationMethod(.monotone)
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
        case "range":
            BarMark(
                x: .value("", point.x),
                yStart: .value("", min(point.y, point.y2 ?? point.y)),
                yEnd: .value("", max(point.y, point.y2 ?? point.y)),
                width: ChartAxisStyle.barWidth
            )
            .foregroundStyle(by: .value("", entry.label))
            .clipShape(Capsule())
        case "stacked_bar":
            BarMark(x: .value("", point.x), y: .value("", point.y), width: ChartAxisStyle.barWidth)
                .foregroundStyle(by: .value("", entry.label))
        case "grouped_bar":
            BarMark(x: .value("", point.x), y: .value("", point.y), width: ChartAxisStyle.barWidth)
                .foregroundStyle(by: .value("", entry.label))
                .position(by: .value("", entry.label))
        default:
            BarMark(x: .value("", point.x), y: .value("", point.y), width: ChartAxisStyle.barWidth)
                .foregroundStyle(by: .value("", entry.label))
                .clipShape(ChartAxisStyle.barShape)
        }
    }

    private var pieChart: some View {
        let slices = series.first?.points ?? []
        let colors = Self.palette(slices.count)
        return HStack(spacing: 16) {
            Chart(Array(slices.enumerated()), id: \.offset) { index, slice in
                SectorMark(angle: .value("", abs(slice.y)), innerRadius: .ratio(0.55), angularInset: 1.5)
                    .foregroundStyle(colors[index % colors.count])
                    .cornerRadius(3)
            }
            .frame(width: ChartAxisStyle.plotHeight * 0.8)

            VStack(alignment: .leading, spacing: 5) {
                ForEach(Array(slices.enumerated()), id: \.offset) { index, slice in
                    HStack(spacing: 6) {
                        Circle()
                            .fill(colors[index % colors.count])
                            .frame(width: 8, height: 8)
                        Text(slice.x)
                            .font(.system(.caption, design: .rounded))
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        Text(Self.numberText(slice.y))
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    /// Each point is a value against `max`; without one the largest value sets the scale.
    private var progressChart: some View {
        let points = series.first?.points ?? []
        let ceiling = spec["max"].double ?? points.map(\.y).max() ?? 1
        return VStack(alignment: .leading, spacing: 10) {
            ForEach(points) { point in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(point.x)
                            .font(.system(.caption, design: .rounded, weight: .medium))
                        Spacer(minLength: 8)
                        Text(Self.valueText(point.y, unit: unit))
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.secondary.opacity(0.15))
                            Capsule()
                                .fill(AppColors.calorie)
                                .frame(width: geometry.size.width * fraction(point.y, ceiling))
                        }
                    }
                    .frame(height: 10)
                }
            }
        }
    }

    private var legend: some View {
        let colors = Self.palette(series.count)
        return HStack(spacing: 12) {
            ForEach(Array(series.enumerated()), id: \.offset) { index, entry in
                HStack(spacing: 5) {
                    Circle()
                        .fill(colors[index % colors.count])
                        .frame(width: 8, height: 8)
                    Text(entry.label)
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: - Scale

    private var allValues: [Double] {
        series.flatMap { entry in entry.points.flatMap { [$0.y, $0.y2].compactMap { $0 } } }
    }

    private var yTicks: [Double] {
        ChartAxisStyle.yTicks(allValues, includeZero: type.hasSuffix("bar") || type == "area").ticks
    }

    private var yDomain: ClosedRange<Double> {
        let ticks = yTicks
        guard let low = ticks.first, let high = ticks.last, high > low else {
            let values = allValues
            let minimum = values.min() ?? 0
            let maximum = values.max() ?? 1
            return minimum == maximum ? (minimum - 1)...(maximum + 1) : minimum...maximum
        }
        return low...high
    }

    private func fraction(_ value: Double, _ ceiling: Double) -> Double {
        guard ceiling > 0 else { return 0 }
        return min(1, max(0, value / ceiling))
    }

    /// What "Copy data" puts on the clipboard: the numbers, not a picture of them.
    private var dataText: String {
        var lines: [String] = []
        if let title = spec["title"].string { lines.append(title) }
        for entry in series {
            if series.count > 1 { lines.append(entry.label) }
            for point in entry.points {
                let value = point.y2.map { "\(Self.numberText(point.y))–\(Self.numberText($0))" }
                    ?? Self.numberText(point.y)
                lines.append("\(point.x)\t\(value)")
            }
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - Model

    struct Point: Identifiable {
        let id = UUID()
        var x: String
        var y: Double
        var y2: Double?
    }

    struct Series: Identifiable {
        let id = UUID()
        var label: String
        var points: [Point]

        static func all(from spec: RJ) -> [Series] {
            (spec["series"].array ?? []).map { entry in
                Series(
                    label: entry["label"].string ?? "",
                    points: (entry["points"].array ?? []).map { point in
                        Point(x: point["x"].string ?? "",
                              y: point["y"].double ?? 0,
                              y2: point["y2"].double)
                    }
                )
            }
        }
    }

    /// Domain colours, in the order `docs/charts.md` lists them, so a chat chart and a Browse chart
    /// use the same palette.
    static func palette(_ count: Int) -> [Color] {
        let base: [Color] = [AppColors.calorie, AppColors.protein, AppColors.carbs, AppColors.fat]
        guard count > base.count else { return Array(base.prefix(max(1, count))) }
        return base
    }

    static func numberText(_ value: Double) -> String {
        if value == value.rounded(), abs(value) < 1e15 { return String(Int(value)) }
        return String(format: "%.2f", value)
    }

    static func valueText(_ value: Double, unit: String?) -> String {
        let text = numberText(value)
        return unit.map { "\(text) \($0)" } ?? text
    }
}
