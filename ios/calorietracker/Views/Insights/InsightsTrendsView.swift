import SwiftUI

/// Personal baselines: for each metric the 60-day baseline, the usual range, today (or the 7-day mean), the
/// percentage change and the 28-day trend.
struct InsightsTrendsView: View {
    @Environment(InsightsStore.self) private var store

    var body: some View {
        InsightsScreen(title: "Trends", topic: .baselines, inputs: { Self.inputRows(store.baselines) }) {
            if store.baselines.isEmpty {
                InsightsCollectingView(title: String(localized: "No metrics yet"),
                                       detail: String(localized: "Baselines appear once Apple Health has synced sleep, heart or activity data."),
                                       collecting: nil)
            }
            ForEach(store.baselines) { row in
                BaselineRowCard(row: row)
            }
        }
    }

    static func inputRows(_ rows: [InsightsMetricBaseline]) -> [InsightsInputRow] {
        rows.map { row in
            InsightsInputRow(id: row.id, title: row.label,
                             value: "\(row.baseline.n)/\(row.baseline.needed)",
                             detail: String(localized: "Readings in the last 60 days"),
                             missing: !row.baseline.isReady)
        }
    }
}

struct BaselineRowCard: View {
    let row: InsightsMetricBaseline

    private var b: BaselineResult { row.baseline }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(row.label)
                    .font(.system(.headline, design: .rounded))
                Spacer()
                trendChip
            }
            if b.isReady {
                HStack(alignment: .firstTextBaseline) {
                    Text(String(localized: "Baseline \(InsightsText.value(b.mean, metric: row.id)) · range \(InsightsText.value(b.low, metric: row.id))–\(InsightsText.value(b.high, metric: row.id))"))
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text("\(Self.recentTitle(row.id)) \(InsightsText.value(b.recent, metric: row.id))")
                        .font(.ayuvoNumber(.subheadline))
                }
                Text(String(localized: "Change \(InsightsText.percent(b.pct))"))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                Text("Learning your baseline (\(b.n)/\(b.needed) days)")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .ayuvoCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("trends.metric.\(row.id)")
    }

    static func recentTitle(_ id: String) -> String {
        InsightsConfig.shared.metric(id)?.recent == "mean7" ? String(localized: "7-day avg") : String(localized: "Today")
    }

    @ViewBuilder
    private var trendChip: some View {
        switch row.trend.direction {
        case "improving": InsightsChip(text: "↑ " + String(localized: "Improving"), tint: AyuvoPalette.nutrition)
        case "declining": InsightsChip(text: "↓ " + String(localized: "Declining"), tint: AyuvoPalette.heart)
        case "stable": InsightsChip(text: "→ " + String(localized: "Stable"), tint: AyuvoPalette.other)
        case "changing":
            let up = (row.trend.slopePctPerWeek ?? 0) > 0
            InsightsChip(text: (up ? "↑ " : "↓ ") + String(localized: "Changing"), tint: AyuvoPalette.activity)
        default: EmptyView()
        }
    }
}
