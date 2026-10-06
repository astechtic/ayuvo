import Charts
import SwiftUI

/// Sleep chart (docs/charts.md › Sleep). D: a hypnogram over the night's own window
/// (bedtime → wake, never midnight → midnight). W / M: one bedtime → wake bar per night on a
/// clock axis, evening at the top, with the stages inside. 6M / Y: mean bedtime → wake bars.
struct SleepChart: View {
    let series: HealthChartSeries
    @Binding var selected: HealthChartPoint?
    let calendar: Calendar
    /// Tap on a night (drill-down to D, or select).
    var onTap: ((HealthChartPoint) -> Void)?

    var body: some View {
        if series.range == .day, let window = series.sleepWindow {
            VStack(alignment: .leading, spacing: 14) {
                SleepHypnogram(window: window, stages: series.stagePoints)
                if window.asleepS == 0 && (window.stages["awake"] ?? 0) == 0 {
                    Text("No sleep stages recorded for this night.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    SleepStageBreakdown(window: window)
                }
            }
        } else if let range = series.sleepRange, let domain = range.domain {
            SleepRangeBars(series: series, domain: domain, selected: $selected, calendar: calendar, onTap: onTap)
        }
    }

    /// Lane index of a stage code, top to bottom; nil for in bed / out of bed.
    static func laneIndex(_ stage: Int?) -> Int? {
        guard let lane = Lane(stage: stage) else { return nil }
        return Lane.allCases.firstIndex(of: lane)
    }

    /// Wall-clock text of a clock-axis offset (minutes from 12:00 the day before waking).
    static func clockText(_ offset: Double, calendar: Calendar, minutes: Bool = true) -> String {
        let total = Int(offset.rounded())
        let dayMinutes = ((total + 720) % 1440 + 1440) % 1440
        var components = DateComponents()
        components.year = 2001
        components.month = 1
        components.day = 1
        components.hour = dayMinutes / 60
        components.minute = dayMinutes % 60
        let date = calendar.date(from: components) ?? Date()
        return minutes ? date.formatted(.dateTime.hour().minute()) : date.formatted(.dateTime.hour())
    }

    enum Lane: String, CaseIterable {
        case awake, rem, core, deep

        var title: String {
            switch self {
            case .awake: return String(localized: "Awake")
            case .rem: return String(localized: "REM")
            case .core: return String(localized: "sleep.stage.core", defaultValue: "Core", comment: "Sleep stage (Apple Health 'Core' sleep), not the muscle group")
            case .deep: return String(localized: "Deep")
            }
        }

        /// Lane of a stage code; "asleep" without a stage sits in the Core lane. In bed → nil.
        init?(stage: Int?) {
            switch stage {
            case 2: self = .awake
            case 5: self = .rem
            case 1, 3: self = .core
            case 4: self = .deep
            default: return nil
            }
        }
    }

    static func stageTitle(_ code: Int?) -> String {
        guard let code, let stage = HealthSleepStage(rawValue: code) else { return String(localized: "Asleep") }
        return stage.displayName
    }
}

// MARK: - Day

/// Sleep › D (docs/charts.md): four stage lanes (Awake, REM, Core, Deep from the top), each a grey
/// pill track under a "<stage> · <total>" title. Segments fill their track, the corner facing a
/// stage change is squared off and a thin gradient stem joins the two lanes. The night runs from
/// bedtime to the time out of bed at a fixed scale per minute, so short stages stay readable and a
/// long night scrolls sideways under pinned lane titles. Tap a segment to read it; no drag gesture
/// sits on the plot, because one (even simultaneous) stops the ScrollView from panning.
private struct SleepHypnogram: View {
    let window: MetricsReference.SleepWindow
    let stages: [HealthChartPoint]
    @State private var inspected: HealthChartPoint?

    private static let titleHeight: CGFloat = 26
    private static let trackHeight: CGFloat = 34
    private static let laneGap: CGFloat = 8
    private static let hourRowHeight: CGFloat = 18
    private static let pointsPerMinute: CGFloat = 1.8
    private static let minSegmentWidth: CGFloat = 4
    private static let stemWidth: CGFloat = 2
    private static var plotHeight: CGFloat { (titleHeight + trackHeight) * 4 + laneGap * 3 }

    private var lanes: [SleepChart.Lane] { SleepChart.Lane.allCases }
    private var staged: [HealthChartPoint] { stages.filter { SleepChart.Lane(stage: $0.stage) != nil }.sorted { $0.start < $1.start } }
    private var inBed: [HealthChartPoint] { stages.filter { $0.stage == HealthSleepStage.inBed.rawValue } }

    private var start: Date { min(ChartAxisStyle.date(window.bedtimeMs), staged.first?.start ?? .distantFuture) }
    private var end: Date {
        max(ChartAxisStyle.date(window.wakeMs), staged.map(\.end).max() ?? .distantPast, start.addingTimeInterval(60))
    }

    private func seconds(_ lane: SleepChart.Lane) -> Int64 {
        switch lane {
        case .awake: return window.stages["awake"] ?? 0
        case .rem: return window.stages["rem"] ?? 0
        case .core: return (window.stages["core"] ?? 0) + (window.stages["unspecified"] ?? 0)
        case .deep: return window.stages["deep"] ?? 0
        }
    }

    /// True when `b` follows `a` without a gap in another lane, so a stem joins them.
    private static func joins(_ a: HealthChartPoint?, _ b: HealthChartPoint?) -> Bool {
        guard let a, let b, let la = SleepChart.laneIndex(a.stage), let lb = SleepChart.laneIndex(b.stage) else { return false }
        return la != lb && b.start.timeIntervalSince(a.end) <= 60
    }

    private static func trackTop(_ lane: Int) -> CGFloat {
        (titleHeight + trackHeight + laneGap) * CGFloat(lane) + titleHeight
    }

    private func point(at x: CGFloat, width: CGFloat) -> HealthChartPoint? {
        let t = start.addingTimeInterval(Double(min(max(x / width, 0), 1)) * end.timeIntervalSince(start))
        return staged.last { $0.start <= t && t < $0.end } ?? inBed.first { $0.start <= t && t < $0.end }
    }

    private func time(_ date: Date) -> String { date.formatted(.dateTime.hour().minute()) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // The readout row keeps its height when empty, so selecting never shifts the chart.
            HStack(spacing: 8) {
                if let inspected {
                    Text(verbatim: "\(SleepChart.stageTitle(inspected.stage)) · \(HealthUnitFormatting.durationText(seconds: inspected.end.timeIntervalSince(inspected.start)))")
                        .font(.system(.subheadline, design: .rounded, weight: .bold))
                        .foregroundStyle(AyuvoPalette.hypnogramStage(inspected.stage))
                    Text("\(time(inspected.start)) – \(time(inspected.end))", comment: "Time range: start – end")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    Text(verbatim: " ").font(.system(.subheadline, design: .rounded, weight: .bold))
                }
            }
            .lineLimit(1)
            .padding(.bottom, 6)
            GeometryReader { geometry in
                let width = max(geometry.size.width, CGFloat(end.timeIntervalSince(start) / 60) * Self.pointsPerMinute)
                ZStack(alignment: .topLeading) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 0) {
                            plot(width: width)
                            hourRow(width: width)
                        }
                    }
                    laneTitles
                }
            }
            .frame(height: Self.plotHeight + Self.hourRowHeight)
            footer
        }
        .sensoryFeedback(.selection, trigger: inspected)
        .padding(.top, 8)
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("sleep.hypnogram")
        .accessibilityLabel(Text("Sleep stages"))
        .accessibilityValue(Text(verbatim: lanes.map { "\($0.title) \(HealthUnitFormatting.durationText(seconds: Double(seconds($0))))" }.joined(separator: ", ")))
    }

    private func plot(width: CGFloat) -> some View {
        let staged = staged
        let selected = inspected
        let span = end.timeIntervalSince(start)
        let start = start
        return Canvas { context, size in
            let xOf: (Date) -> CGFloat = { CGFloat($0.timeIntervalSince(start) / span) * size.width }
            let radius = Self.trackHeight / 2
            for lane in 0..<4 {
                let rect = CGRect(x: 0, y: Self.trackTop(lane), width: size.width, height: Self.trackHeight)
                context.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(Color.primary.opacity(0.08)))
            }
            // Stems: centre of one track to the centre of the other, under the segments.
            for (a, b) in zip(staged, staged.dropFirst()) where Self.joins(a, b) {
                guard let la = SleepChart.laneIndex(a.stage), let lb = SleepChart.laneIndex(b.stage) else { continue }
                let ya = Self.trackTop(la) + radius, yb = Self.trackTop(lb) + radius
                let upper = AyuvoPalette.hypnogramStage(la < lb ? a.stage : b.stage).opacity(0.5)
                let lower = AyuvoPalette.hypnogramStage(la < lb ? b.stage : a.stage).opacity(0.5)
                let x = xOf(b.start)
                let rect = CGRect(x: x - Self.stemWidth / 2, y: min(ya, yb), width: Self.stemWidth, height: abs(yb - ya))
                context.fill(Path(rect), with: .linearGradient(Gradient(colors: [upper, lower]),
                                                               startPoint: CGPoint(x: x, y: rect.minY), endPoint: CGPoint(x: x, y: rect.maxY)))
            }
            for (index, point) in staged.enumerated() {
                guard let lane = SleepChart.laneIndex(point.stage) else { continue }
                let x0 = xOf(point.start)
                let x1 = max(xOf(point.end), x0 + Self.minSegmentWidth)
                let corner = min(radius, (x1 - x0) / 2)
                let prev = index > 0 && Self.joins(staged[index - 1], point) ? SleepChart.laneIndex(staged[index - 1].stage) : nil
                let next = index + 1 < staged.count && Self.joins(point, staged[index + 1]) ? SleepChart.laneIndex(staged[index + 1].stage) : nil
                let radii = RectangleCornerRadii(
                    topLeading: (prev ?? 99) < lane ? 0 : corner,
                    bottomLeading: (prev ?? -1) > lane ? 0 : corner,
                    bottomTrailing: (next ?? -1) > lane ? 0 : corner,
                    topTrailing: (next ?? 99) < lane ? 0 : corner
                )
                let rect = CGRect(x: x0, y: Self.trackTop(lane), width: x1 - x0, height: Self.trackHeight)
                let faded = selected != nil && selected != point
                context.fill(Path(roundedRect: rect, cornerRadii: radii),
                             with: .color(AyuvoPalette.hypnogramStage(point.stage).opacity(faded ? ChartAxisStyle.fadedOpacity : 1)))
            }
            if let selected {
                let x = xOf(selected.start.addingTimeInterval(selected.end.timeIntervalSince(selected.start) / 2))
                context.fill(Path(CGRect(x: x - 0.5, y: 0, width: 1, height: size.height)), with: .color(Color.primary.opacity(0.28)))
            }
        }
        .frame(width: width, height: Self.plotHeight)
        .contentShape(Rectangle())
        .onTapGesture { location in
            let hit = point(at: location.x, width: width)
            inspected = hit == inspected ? nil : hit
        }
    }

    /// Hour marks under the lanes; they scroll with the plot.
    private func hourRow(width: CGFloat) -> some View {
        let span = end.timeIntervalSince(start)
        let ticks = window.ticks.map { ChartAxisStyle.date($0) }.filter { $0 >= start && $0 < end }
        return ZStack(alignment: .topLeading) {
            ForEach(ticks, id: \.self) { tick in
                let x = CGFloat(tick.timeIntervalSince(start) / span) * width
                if x < width - 40 {
                    Text(tick.formatted(.dateTime.hour()))
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                        .offset(x: x, y: 2)
                }
            }
        }
        .frame(width: width, height: Self.hourRowHeight, alignment: .topLeading)
    }

    /// Lane titles stay pinned while the night scrolls underneath.
    private var laneTitles: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(lanes.enumerated()), id: \.offset) { index, lane in
                Text(verbatim: "\(lane.title) · \(HealthUnitFormatting.durationText(seconds: Double(seconds(lane))))")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .foregroundStyle(.primary.opacity(0.85))
                    .lineLimit(1)
                    .frame(height: Self.titleHeight, alignment: .leading)
                Spacer().frame(height: Self.trackHeight + (index < lanes.count - 1 ? Self.laneGap : 0))
            }
        }
        .allowsHitTesting(false)
    }

    private var footer: some View {
        VStack(spacing: 2) {
            HStack {
                Text(time(start))
                Spacer()
                Text(time(start.addingTimeInterval(end.timeIntervalSince(start) / 2)))
                Spacer()
                Text(time(end))
            }
            .font(.system(.subheadline, design: .rounded))
            .foregroundStyle(.secondary)
            HStack(alignment: .top) {
                Text("Time that you went to bed", comment: "Sleep D chart: caption under the bedtime at the start of the night")
                Spacer(minLength: 12)
                Text("Time out of bed", comment: "Sleep D chart: caption under the time the night ended")
                    .multilineTextAlignment(.trailing)
            }
            .font(.system(.footnote, design: .rounded, weight: .semibold))
            .foregroundStyle(.secondary)
        }
        .monospacedDigit()
        .padding(.top, 8)
    }
}

/// Apple's sleep header: TIME IN BED and TIME ASLEEP side by side, the numbers large and the unit
/// words small. A night with no asleep data shows TIME IN BED alone; nothing is invented.
struct SleepDayHeader: View {
    let window: MetricsReference.SleepWindow

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            stat(label: String(localized: "Time in Bed"), seconds: window.inBedS)
            if window.asleepS > 0 {
                stat(label: String(localized: "Time Asleep"), seconds: window.asleepS)
            }
            Spacer(minLength: 0)
        }
        .accessibilityIdentifier("metric.headline")
    }

    private func stat(label: String, seconds: Int64) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label.uppercased())
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
            value(seconds)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(HealthUnitFormatting.durationText(seconds: Double(seconds))))
    }

    @ViewBuilder
    private func value(_ seconds: Int64) -> some View {
        let total = Int(seconds)
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            if total <= 0 {
                Text(verbatim: "—").font(.ayuvoNumber(.largeTitle))
            } else {
                if hours > 0 {
                    Text(hours.formatted()).font(.ayuvoNumber(.largeTitle))
                    unit(String(localized: "hr"))
                }
                if minutes > 0 || hours == 0 {
                    Text(minutes.formatted()).font(.ayuvoNumber(.largeTitle))
                    unit(String(localized: "min"))
                }
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }

    private func unit(_ text: String) -> some View {
        Text(text)
            .font(.system(.headline, design: .rounded))
            .foregroundStyle(.secondary)
    }
}

/// Awake / REM / Core / Deep durations with their share of the time asleep.
private struct SleepStageBreakdown: View {
    let window: MetricsReference.SleepWindow

    private var rows: [(code: Int, key: String, showPct: Bool)] {
        var out: [(Int, String, Bool)] = [(2, "awake", false), (5, "rem", true), (3, "core", true), (4, "deep", true)]
        if (window.stages["unspecified"] ?? 0) > 0 { out.append((1, "unspecified", true)) }
        return out.map { (code: $0.0, key: $0.1, showPct: $0.2) }
    }

    var body: some View {
        if window.asleepS > 0 || (window.stages["awake"] ?? 0) > 0 {
            VStack(spacing: 8) {
                ForEach(rows, id: \.code) { row in
                    let seconds = window.stages[row.key] ?? 0
                    HStack(spacing: 10) {
                        Circle()
                            .fill(AyuvoPalette.hypnogramStage(row.code))
                            .frame(width: 10, height: 10)
                        Text(SleepChart.stageTitle(row.code))
                            .font(.system(.subheadline, design: .rounded))
                        Spacer()
                        Text(seconds > 0 ? HealthUnitFormatting.durationText(seconds: Double(seconds)) : "—")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .monospacedDigit()
                        if row.showPct {
                            Text((window.pct[row.key] ?? nil).map { "\($0)%" } ?? "—")
                                .font(.system(.footnote, design: .rounded))
                                .foregroundStyle(.secondary)
                                .frame(width: 44, alignment: .trailing)
                                .monospacedDigit()
                        } else {
                            Spacer().frame(width: 44)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .accessibilityIdentifier("sleep.stages")
        }
    }
}

// MARK: - W / M / 6M / Y

/// One bar per night (W / M) or per week / month (6M / Y) from bedtime down to wake.
private struct SleepRangeBars: View {
    let series: HealthChartSeries
    let domain: MetricsReference.SleepRangeDomain
    @Binding var selected: HealthChartPoint?
    let calendar: Calendar
    var onTap: ((HealthChartPoint) -> Void)?

    private var plotted: [HealthChartPoint] { series.points.filter { $0.value != nil && $0.min != nil && $0.max != nil } }
    private var stagedDays: Set<Date> { Set(series.sleepSegments.map(\.day)) }
    private var barUnit: Calendar.Component { series.range.barUnit }

    @ChartContentBuilder
    private func nightBar(_ point: HealthChartPoint) -> some ChartContent {
        if ChartAxisStyle.usesSpans(series.range) {
            let span = ChartAxisStyle.span(point)
            RectangleMark(xStart: .value("Start", span.start), xEnd: .value("End", span.end),
                    yStart: .value("Bedtime", -(point.min ?? 0)), yEnd: .value("Wake", -(point.max ?? 0)))
        } else {
            BarMark(x: .value("Night", point.start, unit: barUnit), yStart: .value("Bedtime", -(point.min ?? 0)),
                    yEnd: .value("Wake", -(point.max ?? 0)), width: ChartAxisStyle.barWidth)
        }
    }

    private func faded(_ date: Date) -> Double {
        selected == nil || selected?.start == date ? 1 : ChartAxisStyle.fadedOpacity
    }

    var body: some View {
        Chart {
            ForEach(plotted) { point in
                nightBar(point)
                .foregroundStyle(stagedDays.contains(point.start) ? AyuvoPalette.sleepInBed : AyuvoPalette.sleepAsleep)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .opacity(faded(point.start))
                .accessibilityLabel(Text(ChartAxisStyle.bucketTitle(point, range: series.range)))
                .accessibilityValue(Text(HealthUnitFormatting.durationText(seconds: point.value ?? 0)))
            }
            ForEach(series.sleepSegments) { segment in
                BarMark(
                    x: .value("Night", segment.day, unit: .day),
                    yStart: .value("Start", -Double(segment.startMin)),
                    yEnd: .value("End", -Double(max(segment.endMin, segment.startMin + 1))),
                    width: ChartAxisStyle.barWidth
                )
                .foregroundStyle(AyuvoPalette.sleepStage(segment.stage))
                .opacity(faded(segment.day))
            }
            if let selected {
                if ChartAxisStyle.usesSpans(series.range) {
                    RuleMark(x: .value("Selected", ChartAxisStyle.mid(selected)))
                        .foregroundStyle(Color.primary.opacity(0.28))
                        .lineStyle(StrokeStyle(lineWidth: 1))
                } else {
                    RuleMark(x: .value("Selected", selected.start, unit: barUnit))
                        .foregroundStyle(Color.primary.opacity(0.28))
                        .lineStyle(StrokeStyle(lineWidth: 1))
                }
            }
        }
        .chartXScale(domain: series.interval.start...series.interval.end)
        .chartYScale(domain: -Double(domain.max)...(-Double(domain.min)))
        .chartXAxis { ChartAxisStyle.xAxis(ChartAxisStyle.xMarks(range: series.range, interval: series.interval, calendar: calendar), range: series.range) }
        .chartYAxis {
            AxisMarks(position: .trailing, values: domain.ticks.map { -Double($0) }) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                    .foregroundStyle(Color.primary.opacity(ChartAxisStyle.gridOpacity))
                AxisValueLabel {
                    if let v = value.as(Double.self) {
                        Text(SleepChart.clockText(-v, calendar: calendar, minutes: false))
                    }
                }
                .foregroundStyle(Color.secondary)
            }
        }
        .chartOverlay { proxy in
            ChartScrubOverlay(proxy: proxy, points: plotted, date: { ChartAxisStyle.mid($0) }, selected: $selected, onTap: onTap, persistent: true) { point in
                ChartCallout(
                    value: HealthUnitFormatting.durationText(seconds: point.value ?? 0),
                    caption: "\(ChartAxisStyle.bucketTitle(point, range: series.range)) · \(SleepChart.clockText(point.min ?? 0, calendar: calendar))–\(SleepChart.clockText(point.max ?? 0, calendar: calendar))"
                )
            }
        }
        .animation(ChartAxisStyle.revealAnimation, value: series)
        .frame(height: ChartAxisStyle.plotHeight)
        .padding(.top, 8)
        .clipped()
        .accessibilityIdentifier("sleep.nights")
        .accessibilityLabel(Text("Sleep"))
    }
}
