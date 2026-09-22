import WidgetKit
import SwiftUI

/// Today (docs/widgets.md): Eat · Move · Drink rings; the large size adds fasting, next dose,
/// weight and today's workout. Rows with no data are hidden; nothing is invented.
struct TodayProvider: TimelineProvider {
    func placeholder(in context: Context) -> DashboardEntry {
        DashboardEntry(date: Date(), snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (DashboardEntry) -> Void) {
        completion(DashboardEntry(date: Date(), snapshot: WidgetDashboardSnapshot.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DashboardEntry>) -> Void) {
        completion(DashboardTimeline.entries())
    }
}

struct TodayWidget: Widget {
    let kind = "TodayWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: TodayProvider()) { entry in
            TodayWidgetView(entry: entry)
                .widgetURL(WidgetDeepLink.summaryURL)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Today")
        .description("Eat, Move and Drink rings, plus fasting, your next dose, weight and today's workout.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DashboardEntry

    var body: some View {
        if let snapshot = entry.current {
            switch family {
            case .systemSmall: small(snapshot)
            case .systemLarge: large(snapshot)
            default: medium(snapshot)
            }
        } else {
            DashboardEmptyView()
        }
    }

    private func rings(_ snapshot: WidgetDashboardSnapshot) -> [(title: String, ring: WidgetDashboardSnapshot.Ring, tint: Color)] {
        var list: [(String, WidgetDashboardSnapshot.Ring, Color)] = [
            (String(localized: "Eat"), snapshot.rings.eat, DashboardPalette.eat),
            (String(localized: "Move"), snapshot.rings.move, DashboardPalette.move),
        ]
        if let drink = snapshot.rings.drink {
            list.append((String(localized: "Drink"), drink, DashboardPalette.drink))
        }
        return list.map { (title: $0.0, ring: $0.1, tint: $0.2) }
    }

    // MARK: Small: concentric rings + values

    private func small(_ snapshot: WidgetDashboardSnapshot) -> some View {
        let items = rings(snapshot)
        return HStack(spacing: 10) {
            ZStack {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    DashboardRing(progress: item.ring.progress, tint: item.tint, lineWidth: 7)
                        .padding(CGFloat(index) * 9)
                }
            }
            .frame(width: 66, height: 66)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.title)
                            .font(.system(.caption2, design: .rounded, weight: .semibold))
                            .foregroundStyle(item.tint)
                        Text(item.ring.displayValue)
                            .font(.system(.footnote, design: .rounded, weight: .bold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: Medium: three rings with value and goal

    private func ringsRow(_ snapshot: WidgetDashboardSnapshot) -> some View {
        HStack(alignment: .top, spacing: 8) {
            ForEach(Array(rings(snapshot).enumerated()), id: \.offset) { _, item in
                VStack(spacing: 4) {
                    DashboardRing(progress: item.ring.progress, tint: item.tint, lineWidth: 8)
                        .frame(width: 56, height: 56)
                    Text(item.title)
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(item.tint)
                    Text(item.ring.displayValue)
                        .font(.system(.subheadline, design: .rounded, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    if !item.ring.goalText.isEmpty, item.ring.state != .connect {
                        Text(item.ring.goalText)
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func medium(_ snapshot: WidgetDashboardSnapshot) -> some View {
        ringsRow(snapshot)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Large: rings + today's rows

    private func large(_ snapshot: WidgetDashboardSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(entry.date, format: .dateTime.weekday(.wide).day().month(.wide))
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
            ringsRow(snapshot)
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                if snapshot.fasting.enabled, let started = snapshot.fasting.activeStartedAt {
                    Link(destination: WidgetMetricOption.fasting.url) {
                        TodayRow(systemImage: "timer", tint: DashboardPalette.fasting, title: String(localized: "Fasting")) {
                            HStack(spacing: 4) {
                                Text(started, style: .timer)
                                    .monospacedDigit()
                                if let goal = snapshot.fasting.goalMinutes {
                                    Text("/ \(goal / 60)h")
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                if let dose = snapshot.nextDose(after: entry.date) {
                    Link(destination: WidgetMetricOption.nextDose.url) {
                        TodayRow(systemImage: "pills.fill", tint: DashboardPalette.medications, title: String(localized: "Next dose")) {
                            HStack(spacing: 4) {
                                Text(dose.name).lineLimit(1)
                                Text("·").foregroundStyle(.secondary)
                                Text(dose.scheduledAt, style: .time)
                            }
                        }
                    }
                }
                if let weight = snapshot.weight {
                    Link(destination: WidgetMetricOption.weight.url) {
                        TodayRow(systemImage: "scalemass", tint: DashboardPalette.body, title: weight.title) {
                            Text(weight.valueText)
                        }
                    }
                }
                if let workout = snapshot.workoutToday {
                    Link(destination: WidgetMetricOption.workouts.url) {
                        TodayRow(systemImage: "figure.strengthtraining.traditional", tint: DashboardPalette.move, title: workout.title) {
                            Text(workout.valueText).lineLimit(1)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }
}

private struct TodayRow<Value: View>: View {
    let systemImage: String
    let tint: Color
    let title: String
    @ViewBuilder let value: () -> Value

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 26, height: 26)
                .background(tint, in: RoundedRectangle(cornerRadius: 7))
            Text(title)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(.primary)
            Spacer(minLength: 8)
            value()
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.primary)
        }
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
private extension WidgetDashboardSnapshot {
    /// Preview-only sample (never shown on a device).
    static var previewSample: WidgetDashboardSnapshot {
        let now = Date()
        func ring(_ progress: Double, _ value: String, _ goal: String) -> Ring {
            Ring(progress: progress, valueText: value, goalText: goal, state: .value, emptyValueText: "0")
        }
        return WidgetDashboardSnapshot(
            version: 1, generatedAt: now, dayStart: Calendar.current.startOfDay(for: now),
            rings: Rings(eat: ring(0.62, "1,240", "2,000 kcal"), move: ring(0.65, "6,512", "10,000 steps"), drink: ring(0.5, "1,000", "2,000 ml")),
            fasting: Fasting(enabled: true, activeStartedAt: now.addingTimeInterval(-12 * 3600), goalMinutes: 960),
            medications: Medications(doses: [Dose(name: "Metformin", scheduledAt: now.addingTimeInterval(3600), status: "scheduled")], taken: 1, total: 2),
            weight: Row(title: "Weight", valueText: "72.4 kg", at: now),
            bodyFat: nil,
            workoutToday: Row(title: "Workout today", valueText: "5 exercises · 320 kcal", at: now),
            metrics: [], waterTrackingEnabled: true, fastingTrackingEnabled: true
        )
    }
}

#Preview("Today small", as: .systemSmall) {
    TodayWidget()
} timeline: {
    DashboardEntry(date: .now, snapshot: .previewSample)
    DashboardEntry(date: .now, snapshot: nil)
}

#Preview("Today medium", as: .systemMedium) {
    TodayWidget()
} timeline: {
    DashboardEntry(date: .now, snapshot: .previewSample)
}

#Preview("Today large", as: .systemLarge) {
    TodayWidget()
} timeline: {
    DashboardEntry(date: .now, snapshot: .previewSample)
}
#endif
