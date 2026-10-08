import SwiftUI

/// Summary › Today: one compact card listing up to three partners (most recent sync first), each with its freshness
/// and up to three headline metrics from `summary_metrics` (docs/partner-sync.md §15). Hidden without partners.
struct PartnerSummaryCard: View {
    @Environment(AppNavigator.self) private var navigator
    @State private var manager = PartnerManager.shared
    @State private var metrics: [String: [PartnerSummaryMetric]] = [:]

    private var shown: [PartnerManager.PartnerItem] { Array(manager.partners.prefix(3)) }

    var body: some View {
        // Always present, so the task loads the partners even while the card is hidden.
        VStack(spacing: 0) {
            if !shown.isEmpty {
                card
            }
        }
        .task { await manager.reload() }
        .task(id: shown) { await loadMetrics() }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                CategoryIconView(systemImage: "person.2.fill", tint: AyuvoPalette.partner, size: 28)
                Text("Partners", comment: "Summary card title: partners' shared health data")
                    .font(.system(.headline, design: .rounded))
                Spacer()
                PartnerReadOnlyBadge()
            }
            ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                if index > 0 { Divider() }
                Button {
                    navigator.summaryPath.append(PartnerRoute.dashboard(item.id))
                } label: {
                    PartnerSummaryRow(item: item, metrics: metrics[item.id] ?? [])
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("summary.partner.\(item.id)")
            }
        }
        .ayuvoCard()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("summary.card.partner")
    }

    private func loadMetrics() async {
        guard !shown.isEmpty, let db = await manager.database() else { return }
        let items = shown
        let today = PartnerDay.string(.now)
        let yesterday = PartnerDay.adding(-1, to: today)
        let loaded = await Task.detached(priority: .userInitiated) { () -> [String: [PartnerSummaryMetric]] in
            var out: [String: [PartnerSummaryMetric]] = [:]
            for item in items {
                let rows = (try? await db.records(item.id, types: PartnerSummaryMetric.sourceTypes, dayFrom: yesterday)) ?? []
                out[item.id] = PartnerSummaryMetric.compute(rows: rows, grants: item.grantsReceived, today: today, yesterday: yesterday)
            }
            return out
        }.value
        metrics = loaded
    }
}

/// One partner on the Summary card: name, freshness and metric chips (dimmed when no longer shared).
struct PartnerSummaryRow: View {
    let item: PartnerManager.PartnerItem
    let metrics: [PartnerSummaryMetric]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                PartnerAvatar(name: item.name, size: 34)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: item.name)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    TimelineView(.periodic(from: .now, by: 60)) { context in
                        Text(verbatim: PartnerDisplay.freshness(item, now: context.date))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            if !metrics.isEmpty {
                HStack(spacing: 14) {
                    ForEach(metrics) { metric in
                        PartnerMetricChip(metric: metric)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.leading, 44)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct PartnerMetricChip: View {
    let metric: PartnerSummaryMetric

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: PartnerDisplay.metricSystemImage(metric.key))
                .font(.system(.caption2, weight: .semibold))
                .foregroundStyle(PartnerDisplay.metricTint(metric.key))
            Text(verbatim: PartnerDisplay.metricValue(metric))
                .font(.ayuvoNumber(.subheadline))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .opacity(metric.shared ? 1 : 0.45)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(PartnerDisplay.metricTitle(metric.key)): \(PartnerDisplay.metricValue(metric))"
            + (metric.shared ? "" : ", " + PartnerBadgeText.noLongerShared)))
    }
}

/// Round pink initial, used wherever a partner (never the user) is shown.
struct PartnerAvatar: View {
    let name: String
    var size: CGFloat = 40

    var body: some View {
        Text(verbatim: PartnerDisplay.initial(name))
            .font(.system(size: size * 0.45, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(AyuvoPalette.partner.gradient, in: Circle())
            .accessibilityHidden(true)
    }
}

enum PartnerBadgeText {
    static var noLongerShared: String {
        String(localized: "partner.badge.no_longer_shared", defaultValue: "No longer shared", comment: "Label on partner data that the partner stopped sharing (kept on this phone)")
    }

    static var readOnly: String {
        String(localized: "partner.badge.read_only", defaultValue: "Read-only", comment: "Badge: partner data can be viewed but not changed")
    }
}

/// "Read-only" capsule in the partner pink.
struct PartnerReadOnlyBadge: View {
    var body: some View {
        Label(PartnerBadgeText.readOnly, systemImage: "eye.fill")
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(AyuvoPalette.partner)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(AyuvoPalette.partner.opacity(0.14), in: Capsule())
    }
}

/// "No longer shared" capsule.
struct PartnerNoLongerSharedBadge: View {
    var body: some View {
        Text(verbatim: PartnerBadgeText.noLongerShared)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.secondary.opacity(0.14), in: Capsule())
    }
}
