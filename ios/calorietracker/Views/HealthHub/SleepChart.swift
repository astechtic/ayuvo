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
            case .core: return String(localized: "Core")
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
        return String(localized: String.LocalizationValue(stage.englishName))
    }
}

// MARK: - Day

/// Apple Health's Sleep › D chart (docs/charts.md): four equal lanes (Awake, REM, Core, Deep from
/// the top) over the night's own window, lane titles inside on the leading edge, stage capsules
/// centred in their lane and gradient "waterfall" stems at every stage change. No filled background.
private struct SleepHypnogram: View {
    let window: MetricsReference.SleepWindow
    let stages: [HealthChartPoint]
    @State private var inspected: HealthChartPoint?

    private static let laneSeparator = 0.14
    /// Leaves room above each capsule for the lane title, which sits at the top of the band.
    private static let capsuleRatio = 0.36
    private static let stemWidth: CGFloat = 4

    private var lanes: [SleepChart.Lane] { SleepChart.Lane.allCases }
    private var laneTitles: [String] { lanes.map(\.title) }
    private var staged: [HealthChartPoint] { stages.filter { SleepChart.Lane(stage: $0.stage) != nil }.sorted { $0.start < $1.start } }
    private var inBed: [HealthChartPoint] { stages.filter { $0.stage == HealthSleepStage.inBed.rawValue } }

    private var domain: ClosedRange<Date> {
        ChartAxisStyle.date(window.domainStartMs)...ChartAxisStyle.date(window.domainEndMs)
    }

    /// A stage shorter than this would draw thinner than ~2 pt, so it is widened to stay visible.
    private var minDuration: TimeInterval {
        max(60, domain.lowerBound.distance(to: domain.upperBound) / 170)
    }

    private struct Stem: Identifiable {
        let id: Int
        let at: Date
        let from: Int
        let to: Int
        let fromColor: Color
        let toColor: Color
    }

    /// One stem per stage change, at the boundary between the two segments.
    private var stems: [Stem] {
        var out: [Stem] = []
        for (index, pair) in zip(staged, staged.dropFirst()).enumerated() {
            guard let a = SleepChart.laneIndex(pair.0.stage), let b = SleepChart.laneIndex(pair.1.stage), a != b,
                  pair.1.start.timeIntervalSince(pair.0.end) < 300 else { continue }
            let at = pair.0.end.addingTimeInterval(pair.1.start.timeIntervalSince(pair.0.end) / 2)
            out.append(Stem(id: index, at: at, from: a, to: b,
                            fromColor: AyuvoPalette.sleepStage(pair.0.stage), toColor: AyuvoPalette.sleepStage(pair.1.stage)))
        }
        return out
    }

    var body: some View {
        Chart {
            ForEach(staged) { point in
                BarMark(
                    xStart: .value("Start", point.start),
                    xEnd: .value("End", max(point.end, point.start.addingTimeInterval(minDuration))),
                    y: .value("Stage", SleepChart.Lane(stage: point.stage)?.title ?? ""),
                    height: .ratio(Self.capsuleRatio)
                )
                .foregroundStyle(AyuvoPalette.sleepStage(point.stage))
                .clipShape(Capsule())
                .opacity(inspected == nil || inspected == point ? 1 : ChartAxisStyle.fadedOpacity)
                .accessibilityLabel(Text(SleepChart.stageTitle(point.stage)))
                .accessibilityValue(Text("\(point.start.formatted(.dateTime.hour().minute())) – \(point.end.formatted(.dateTime.hour().minute()))"))
            }
            if let inspected {
                RuleMark(x: .value("Selected", inspected.start.addingTimeInterval(inspected.end.timeIntervalSince(inspected.start) / 2)))
                    .foregroundStyle(Color.primary.opacity(0.28))
                    .lineStyle(StrokeStyle(lineWidth: 1))
            }
        }
        .chartXScale(domain: domain)
        .chartYScale(domain: laneTitles)
        .chartXAxis {
            AxisMarks(values: window.ticks.map { ChartAxisStyle.date($0) }) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [2, 3]))
                    .foregroundStyle(Color.primary.opacity(ChartAxisStyle.gridOpacity))
                // Apple leading-aligns each label to its gridline; the last tick would overflow the
                // trailing edge, so only that one is anchored inward.
                AxisValueLabel(format: .dateTime.hour(),
                               anchor: value.index == value.count - 1 ? .topTrailing : .topLeading,
                               collisionResolution: .disabled)
                    .foregroundStyle(Color.secondary)
            }
        }
        .chartYAxis(.hidden)
        .chartBackground { proxy in
            GeometryReader { geometry in
                if let plot = proxy.plotFrame {
                    laneScaffold(frame: geometry[plot], proxy: proxy)
                }
            }
        }
        .chartOverlay { proxy in
            ChartScrubOverlay(
                proxy: proxy, points: staged,
                date: { $0.start.addingTimeInterval($0.end.timeIntervalSince($0.start) / 2) },
                selected: $inspected, persistent: true,
                match: { at in staged.first { $0.start <= at && at < $0.end } ?? inBed.first { $0.start <= at && at < $0.end } }
            ) { point in
                ChartCallout(
                    value: "\(SleepChart.stageTitle(point.stage)) · \(HealthUnitFormatting.durationText(seconds: point.end.timeIntervalSince(point.start)))",
                    caption: "\(point.start.formatted(.dateTime.hour().minute())) – \(point.end.formatted(.dateTime.hour().minute()))"
                )
            }
        }
        .animation(ChartAxisStyle.revealAnimation, value: window)
        .frame(height: ChartAxisStyle.plotHeight)
        .padding(.top, 8)
        .accessibilityIdentifier("sleep.hypnogram")
        .accessibilityLabel(Text("Sleep stages"))
    }

    /// Lane separators, the leading axis line, the gradient stems and the lane titles.
    @ViewBuilder
    private func laneScaffold(frame: CGRect, proxy: ChartProxy) -> some View {
        let band = frame.height / CGFloat(lanes.count)
        let center: (Int) -> CGFloat = { frame.minY + band * (CGFloat($0) + 0.5) }
        ZStack(alignment: .topLeading) {
            Path { path in
                for index in 0...lanes.count {
                    let y = frame.minY + band * CGFloat(index)
                    path.move(to: CGPoint(x: frame.minX, y: y))
                    path.addLine(to: CGPoint(x: frame.maxX, y: y))
                }
                path.move(to: CGPoint(x: frame.minX, y: frame.minY))
                path.addLine(to: CGPoint(x: frame.minX, y: frame.maxY))
            }
            .stroke(Color.primary.opacity(Self.laneSeparator), lineWidth: 0.5)
            ForEach(stems) { stem in
                if let x = proxy.position(forX: stem.at) {
                    let top = min(center(stem.from), center(stem.to))
                    let bottom = max(center(stem.from), center(stem.to))
                    let upper = stem.from < stem.to ? stem.fromColor : stem.toColor
                    let lower = stem.from < stem.to ? stem.toColor : stem.fromColor
                    // Apple's trail: bright where it leaves each capsule, dim in between.
                    Capsule()
                        .fill(LinearGradient(stops: [
                            .init(color: upper.opacity(0.9), location: 0),
                            .init(color: upper.opacity(0.45), location: 0.35),
                            .init(color: lower.opacity(0.45), location: 0.65),
                            .init(color: lower.opacity(0.9), location: 1)
                        ], startPoint: .top, endPoint: .bottom))
                        .frame(width: Self.stemWidth, height: bottom - top)
                        .position(x: frame.minX + x, y: (top + bottom) / 2)
                }
            }
            ForEach(Array(lanes.enumerated()), id: \.offset) { index, lane in
                Text(lane.title)
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
                    .offset(x: frame.minX + 6, y: frame.minY + band * CGFloat(index) + 2)
            }
        }
        .accessibilityHidden(true)
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
                            .fill(AyuvoPalette.sleepStage(row.code))
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
