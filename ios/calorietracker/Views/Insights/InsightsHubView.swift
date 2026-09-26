import SwiftUI

/// Browse › Insights: Recovery, Health Age, Daily Review, Trends and Patterns.
struct InsightsHubView: View {
    @Environment(InsightsStore.self) private var store

    var body: some View {
        InsightsScreen(title: "Insights", topic: .baselines, inputs: { InsightsTrendsView.inputRows(store.baselines) }) {
            VStack(spacing: 0) {
                row(.recovery, icon: "bolt.heart.fill", title: String(localized: "Recovery"), subtitle: recoverySubtitle)
                Divider().padding(.leading, 56)
                row(.healthAge, icon: "hourglass", title: String(localized: "Health Age"), subtitle: healthAgeSubtitle)
                Divider().padding(.leading, 56)
                row(.review(nil), icon: "checklist", title: String(localized: "Daily Review"), subtitle: reviewSubtitle)
                Divider().padding(.leading, 56)
                row(.trends, icon: "chart.line.uptrend.xyaxis", title: String(localized: "Trends"),
                    subtitle: String(localized: "Your baselines, ranges and changes"))
                Divider().padding(.leading, 56)
                row(.patterns, icon: "point.3.connected.trianglepath.dotted", title: String(localized: "Patterns"),
                    subtitle: patternsSubtitle)
            }
            .ayuvoCard(padding: 0)
        }
    }

    private func row(_ route: InsightsRoute, icon: String, title: String, subtitle: String?) -> some View {
        NavigationLink(value: route) {
            HStack {
                MetricRow(systemImage: icon, tint: AyuvoPalette.insights, title: title, subtitle: subtitle)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("insights.hub.\(String(describing: route).components(separatedBy: "(").first ?? "")")
    }

    private var recoverySubtitle: String? {
        guard let recovery = store.recovery else { return nil }
        if let score = recovery.score, recovery.isReady { return "\(score) · \(recovery.labelText ?? "")" }
        if let collecting = recovery.collecting {
            return String(localized: "Learning your baseline (\(collecting.have)/\(collecting.need) nights)")
        }
        return String(localized: "Waiting for last night's data")
    }

    private var healthAgeSubtitle: String? {
        guard let result = store.healthAge else { return nil }
        if let age = result.healthAge, result.isReady {
            return String(localized: "\(age.formatted(.number.precision(.fractionLength(1)))) · Ayuvo's own estimate")
        }
        if let collecting = result.collecting { return String(localized: "Collecting data (\(collecting.have)/\(collecting.need) days)") }
        return nil
    }

    private var reviewSubtitle: String? {
        guard let today = store.report?.today, let review = store.review(for: today) else { return nil }
        return review.dayScore.map { String(localized: "Today's Day Score \($0)") } ?? String(localized: "Nothing logged today yet")
    }

    private var patternsSubtitle: String {
        let count = store.patterns.filter(\.surfaced).count
        return count == 0 ? String(localized: "No clear patterns yet") : (count == 1 ? String(localized: "1 pattern found") : String(localized: "\(count) patterns found"))
    }
}
