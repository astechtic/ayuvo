import AppIntents
import WidgetKit
import SwiftUI

/// Quick Log (docs/widgets.md): four user-chosen log actions. Every tap opens Ayuvo where the
/// Summary "+" menu goes for that action; the widget never logs anything by itself.
struct QuickLogIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Quick Log"
    static var description = IntentDescription("Choose four things to log from your Home Screen.")

    @Parameter(title: "Action 1", default: .foodCamera)
    var action1: QuickLogAction
    @Parameter(title: "Action 2", default: .water)
    var action2: QuickLogAction
    @Parameter(title: "Action 3", default: .weight)
    var action3: QuickLogAction
    @Parameter(title: "Action 4", default: .workout)
    var action4: QuickLogAction

    var actions: [QuickLogAction] { [action1, action2, action3, action4] }
}

struct QuickLogEntry: TimelineEntry {
    let date: Date
    let actions: [QuickLogAction]
    /// Tracker switches and the active fast, from the dashboard snapshot (nil before the first write).
    let snapshot: WidgetDashboardSnapshot?
}

struct QuickLogProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> QuickLogEntry {
        QuickLogEntry(date: Date(), actions: QuickLogAction.defaults, snapshot: nil)
    }

    func snapshot(for configuration: QuickLogIntent, in context: Context) async -> QuickLogEntry {
        QuickLogEntry(date: Date(), actions: configuration.actions, snapshot: WidgetDashboardSnapshot.read())
    }

    func timeline(for configuration: QuickLogIntent, in context: Context) async -> Timeline<QuickLogEntry> {
        let now = Date()
        let entry = QuickLogEntry(date: now, actions: configuration.actions, snapshot: WidgetDashboardSnapshot.read())
        return Timeline(entries: [entry], policy: .after(now.addingTimeInterval(30 * 60)))
    }
}

struct QuickLogWidget: Widget {
    let kind = "QuickLogWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: QuickLogIntent.self, provider: QuickLogProvider()) { entry in
            QuickLogWidgetView(entry: entry)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Quick Log")
        .description("Four shortcuts that open Ayuvo straight to logging food, water, weight, workouts and more.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct QuickLogWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: QuickLogEntry

    var body: some View {
        if family == .systemSmall {
            // Small widgets allow one link, so each cell is a button whose intent opens the app.
            Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                GridRow {
                    cell(entry.actions[0])
                    cell(entry.actions[1])
                }
                GridRow {
                    cell(entry.actions[2])
                    cell(entry.actions[3])
                }
            }
        } else {
            HStack(spacing: 8) {
                ForEach(Array(entry.actions.enumerated()), id: \.offset) { _, action in
                    cell(action)
                }
            }
        }
    }

    @ViewBuilder
    private func cell(_ action: QuickLogAction) -> some View {
        let label = QuickLogCell(action: action, title: title(for: action), enabled: isEnabled(action), compact: family == .systemSmall)
        if family == .systemSmall {
            Button(intent: OpenLogEntryIntent(action: action)) { label }
                .buttonStyle(.plain)
        } else {
            Link(destination: action.url) { label }
        }
    }

    /// Water / fasting tiles dim while their tracker is off (the tap then opens Settings).
    private func isEnabled(_ action: QuickLogAction) -> Bool {
        guard let snapshot = entry.snapshot else { return true }
        switch action {
        case .water: return snapshot.waterTrackingEnabled
        case .fasting: return snapshot.fastingTrackingEnabled
        default: return true
        }
    }

    private func title(for action: QuickLogAction) -> String {
        guard action == .fasting, let snapshot = entry.snapshot, snapshot.fastingTrackingEnabled else { return action.title }
        return snapshot.fasting.activeStartedAt == nil ? String(localized: "Start Fast") : String(localized: "End Fast")
    }
}

private struct QuickLogCell: View {
    let action: QuickLogAction
    let title: String
    let enabled: Bool
    let compact: Bool

    var body: some View {
        let tint = Color(dashboardHex: action.tintHex)
        VStack(spacing: compact ? 4 : 6) {
            Image(systemName: action.systemImage)
                .font(.system(size: compact ? 18 : 20, weight: .semibold))
                .foregroundStyle(tint)
            Text(title)
                .font(.system(.caption2, design: .rounded, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        .opacity(enabled ? 1 : 0.4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(title))
    }
}

#if DEBUG
#Preview("Quick Log small", as: .systemSmall) {
    QuickLogWidget()
} timeline: {
    QuickLogEntry(date: .now, actions: QuickLogAction.defaults, snapshot: nil)
}

#Preview("Quick Log medium", as: .systemMedium) {
    QuickLogWidget()
} timeline: {
    QuickLogEntry(date: .now, actions: QuickLogAction.defaults, snapshot: nil)
}
#endif
