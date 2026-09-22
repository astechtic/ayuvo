import AppIntents
import WidgetKit
import SwiftUI

/// My Metrics (docs/widgets.md): four user-chosen metrics as tiles. Tap a tile (medium) or the
/// widget (small, first tile) to open that metric in Ayuvo.
struct MyMetricsIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "My Metrics"
    static var description = IntentDescription("Choose four metrics to keep on your Home Screen.")

    @Parameter(title: "Metric 1", default: .calories)
    var metric1: WidgetMetricOption
    @Parameter(title: "Metric 2", default: .steps)
    var metric2: WidgetMetricOption
    @Parameter(title: "Metric 3", default: .water)
    var metric3: WidgetMetricOption
    @Parameter(title: "Metric 4", default: .weight)
    var metric4: WidgetMetricOption

    var options: [WidgetMetricOption] { [metric1, metric2, metric3, metric4] }
}

struct MyMetricsEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetDashboardSnapshot?
    let options: [WidgetMetricOption]

    var current: WidgetDashboardSnapshot? { snapshot?.display(at: date) }
}

struct MyMetricsProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> MyMetricsEntry {
        MyMetricsEntry(date: Date(), snapshot: nil, options: WidgetMetricOption.defaults)
    }

    func snapshot(for configuration: MyMetricsIntent, in context: Context) async -> MyMetricsEntry {
        MyMetricsEntry(date: Date(), snapshot: WidgetDashboardSnapshot.read(), options: configuration.options)
    }

    func timeline(for configuration: MyMetricsIntent, in context: Context) async -> Timeline<MyMetricsEntry> {
        let base = DashboardTimeline.entries()
        let entries = base.entries.map { MyMetricsEntry(date: $0.date, snapshot: $0.snapshot, options: configuration.options) }
        return Timeline(entries: entries, policy: .after(Date().addingTimeInterval(30 * 60)))
    }
}

struct MyMetricsWidget: Widget {
    let kind = "MyMetricsWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: MyMetricsIntent.self, provider: MyMetricsProvider()) { entry in
            MyMetricsWidgetView(entry: entry)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("My Metrics")
        .description("Pick any four metrics: nutrition, water, body, fasting, activity, sleep or your next dose.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

/// What one tile shows at the entry's date.
struct MetricTileContent {
    let option: WidgetMetricOption
    let title: String
    let systemImage: String
    let tint: Color
    let value: String
    let unit: String
    let caption: String?
    let progress: Double?
    /// An active fast: the value is a live timer from this date.
    let timerStart: Date?

    init(option: WidgetMetricOption, snapshot: WidgetDashboardSnapshot, now: Date) {
        self.option = option
        let metric = snapshot.metric(option.rawValue)
        title = metric?.title ?? option.title
        systemImage = metric?.systemImage ?? option.systemImage
        tint = Color(dashboardHex: metric?.tintHex ?? "#8E8E93")
        switch option {
        case .nextDose:
            if let dose = snapshot.nextDose(after: now) {
                value = dose.scheduledAt.formatted(date: .omitted, time: .shortened)
                unit = ""
                caption = dose.name
            } else {
                value = "—"
                unit = ""
                caption = snapshot.medications == nil ? nil : String(localized: "No more today")
            }
            progress = nil
            timerStart = nil
        case .fasting where snapshot.fasting.enabled && snapshot.fasting.activeStartedAt != nil:
            value = ""
            unit = ""
            caption = snapshot.fasting.goalMinutes.map { String(localized: "Goal \($0 / 60)h") }
            progress = nil
            timerStart = snapshot.fasting.activeStartedAt
        default:
            value = metric?.valueText ?? "—"
            unit = metric?.unitText ?? ""
            caption = metric?.caption
            progress = metric?.progress
            timerStart = nil
        }
    }
}

struct MyMetricsWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: MyMetricsEntry

    var body: some View {
        if let snapshot = entry.current {
            let tiles = entry.options.map { MetricTileContent(option: $0, snapshot: snapshot, now: entry.date) }
            Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                GridRow {
                    tile(tiles[0])
                    tile(tiles[1])
                }
                GridRow {
                    tile(tiles[2])
                    tile(tiles[3])
                }
            }
            .widgetURL(entry.options.first?.url)
        } else {
            DashboardEmptyView()
        }
    }

    @ViewBuilder
    private func tile(_ content: MetricTileContent) -> some View {
        if family == .systemSmall {
            MetricTileView(content: content, compact: true)
        } else {
            Link(destination: content.option.url) {
                MetricTileView(content: content, compact: false)
            }
        }
    }
}

private struct MetricTileView: View {
    let content: MetricTileContent
    let compact: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 1 : 3) {
            HStack(spacing: 4) {
                Image(systemName: content.systemImage)
                    .font(.system(size: compact ? 10 : 11, weight: .semibold))
                    .foregroundStyle(content.tint)
                if !compact {
                    Text(content.title)
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(content.tint)
                        .lineLimit(1)
                }
            }
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                if let start = content.timerStart {
                    Text(start, style: .timer)
                        .monospacedDigit()
                } else {
                    Text(content.value)
                }
                if !compact, !content.unit.isEmpty {
                    Text(content.unit)
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .font(.system(compact ? .footnote : .headline, design: .rounded, weight: .bold))
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            if !compact {
                if let progress = content.progress {
                    ProgressView(value: progress)
                        .tint(content.tint)
                        .scaleEffect(x: 1, y: 0.7, anchor: .center)
                } else if let caption = content.caption {
                    Text(caption)
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(compact ? 6 : 8)
        .background(content.tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
#Preview("My Metrics small", as: .systemSmall) {
    MyMetricsWidget()
} timeline: {
    MyMetricsEntry(date: .now, snapshot: nil, options: WidgetMetricOption.defaults)
}

#Preview("My Metrics medium", as: .systemMedium) {
    MyMetricsWidget()
} timeline: {
    MyMetricsEntry(date: .now, snapshot: nil, options: WidgetMetricOption.defaults)
}
#endif
