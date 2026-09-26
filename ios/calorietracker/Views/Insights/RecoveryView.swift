import SwiftUI

/// Daily Recovery: score gauge, label and suggestion, the signals behind it, yesterday's training load and the
/// last 30 days. Every number comes from `RecoveryEngine`.
struct RecoveryView: View {
    @Environment(InsightsStore.self) private var store

    var body: some View {
        InsightsScreen(title: "Recovery", topic: .recovery, disclaimers: ["general", "background"],
                       inputs: { Self.inputRows(store.recovery) }) {
            if let recovery = store.recovery {
                RecoveryHeaderCard(recovery: recovery)
                if recovery.isReady {
                    signalsCard(recovery)
                }
                componentsCard(recovery)
                if let load = recovery.load {
                    loadCard(load)
                }
                historyCard
                if recovery.isReady {
                    InsightsExplainSection(kind: .recovery, summary: store.report?.summary())
                }
            }
        }
    }

    // MARK: Cards

    private func signalsCard(_ recovery: RecoveryResult) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            AyuvoSectionHeader("Signals")
            if recovery.positives.isEmpty && recovery.negatives.isEmpty {
                Text("Every signal was close to your baseline.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            ForEach(recovery.positives) { signal in
                signalRow(signal, positive: true)
            }
            ForEach(recovery.negatives) { signal in
                signalRow(signal, positive: false)
            }
        }
        .ayuvoCard()
        .accessibilityIdentifier("recovery.signals")
    }

    private func signalRow(_ signal: RecoverySignal, positive: Bool) -> some View {
        HStack {
            Image(systemName: positive ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .foregroundStyle(positive ? AyuvoPalette.nutrition : AyuvoPalette.heart)
            Text(signal.text)
                .font(.system(.subheadline, design: .rounded))
            Spacer()
            InsightsChip(text: InsightsFormat.signed(signal.impact, 1), tint: positive ? AyuvoPalette.nutrition : AyuvoPalette.heart)
        }
        .accessibilityElement(children: .combine)
    }

    private func componentsCard(_ recovery: RecoveryResult) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            AyuvoSectionHeader("Compared with your baseline")
            ForEach(recovery.components) { component in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(Self.label(component.id))
                            .font(.system(.subheadline, design: .rounded, weight: .medium))
                        Spacer()
                        Text(InsightsText.value(component.value, metric: component.id))
                            .font(.ayuvoNumber(.subheadline))
                            .foregroundStyle(component.value == nil ? .secondary : .primary)
                    }
                    Text(Self.componentDetail(component))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .ayuvoCard()
    }

    private func loadCard(_ load: RecoveryLoad) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("Yesterday's training", systemImage: "figure.run")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                Spacer()
                InsightsChip(text: load.label, tint: load.category == "high" ? AyuvoPalette.heart : AyuvoPalette.activity)
            }
            if load.modifier != 0 {
                Text("High compared with your 28-day average, so \(abs(load.modifier)) points were taken off.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .ayuvoCard()
    }

    @ViewBuilder
    private var historyCard: some View {
        let points = (store.report?.recoveryHistory ?? []).compactMap { result -> InsightsTrendChart.Point? in
            guard let score = result.score, result.isReady, let date = InsightsDay.date(result.day) else { return nil }
            return InsightsTrendChart.Point(day: result.day, date: date, value: Double(score))
        }
        VStack(alignment: .leading, spacing: 8) {
            AyuvoSectionHeader("Last 30 days")
            if points.isEmpty {
                Text("Scores appear here once your baseline is ready.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                InsightsTrendChart(points: points, tint: AyuvoPalette.insights, bars: true, yDomain: 0...100) { "\(Int($0))" }
            }
        }
        .ayuvoCard()
    }

    // MARK: Text

    static func label(_ id: String) -> String {
        InsightsConfig.shared.metric(id)?.label ?? id
    }

    static func componentDetail(_ c: RecoveryComponent) -> String {
        guard c.available else {
            if c.value == nil { return String(localized: "No reading last night") }
            return String(localized: "Learning (\(c.baselineN)/14 nights)")
        }
        var parts: [String] = []
        if let baseline = c.baseline {
            parts.append(String(localized: "Baseline \(InsightsText.value(baseline, metric: c.id))"))
        }
        if let pct = c.pct { parts.append(InsightsText.percent(pct)) }
        if let impact = c.impact { parts.append(String(localized: "\(InsightsFormat.signed(impact, 1)) points")) }
        if c.fallback { parts.append(String(localized: "daily value, no reading during sleep")) }
        if parts.isEmpty { return InsightsText.missing }
        return parts.joined(separator: " · ")
    }

    /// "Your inputs today" for the ⓘ sheet.
    static func inputRows(_ recovery: RecoveryResult?) -> [InsightsInputRow] {
        guard let recovery else { return [] }
        var rows = recovery.components.map { c in
            InsightsInputRow(
                id: c.id, title: label(c.id),
                value: c.available ? InsightsText.value(c.value, metric: c.id) : String(localized: "Missing"),
                detail: c.available
                    ? String(localized: "Baseline \(InsightsText.value(c.baseline, metric: c.id)) · weight \(c.weight.formatted()) · sub-score \(c.subscore.map { InsightsFormat.number($0) } ?? InsightsText.missing)")
                    : componentDetail(c),
                missing: !c.available
            )
        }
        if let load = recovery.load {
            rows.append(InsightsInputRow(id: "load", title: String(localized: "Yesterday's training load"), value: load.label,
                                         detail: load.modifier == 0 ? nil : String(localized: "\(load.modifier) points")))
        }
        return rows
    }
}

/// Gauge, label, suggestion and confidence (also the top of the Summary card's destination).
struct RecoveryHeaderCard: View {
    let recovery: RecoveryResult

    var body: some View {
        switch recovery.status {
        case "collecting":
            InsightsCollectingView(
                title: String(localized: "Learning your baseline (\(recovery.collecting?.have ?? 0)/\(recovery.collecting?.need ?? 14) nights)"),
                detail: String(localized: "Recovery needs about two weeks of sleep with HRV or resting heart rate. Keep wearing your watch to bed."),
                collecting: recovery.collecting
            )
        case "no_sleep":
            InsightsCollectingView(title: String(localized: "Waiting for last night's sleep"),
                                   detail: String(localized: "Recovery is ready once last night's sleep has synced from Apple Health."),
                                   collecting: nil)
        case "no_heart_data":
            InsightsCollectingView(title: String(localized: "No heart reading for last night"),
                                   detail: String(localized: "Recovery needs HRV or resting heart rate from last night."),
                                   collecting: nil)
        default:
            HStack(spacing: 18) {
                InsightsScoreRing(score: recovery.score, tint: InsightsText.recoveryTint(recovery.label), size: 104, lineWidth: 12)
                VStack(alignment: .leading, spacing: 6) {
                    Text(recovery.labelText ?? InsightsText.missing)
                        .font(.system(.title3, design: .rounded, weight: .bold))
                        .foregroundStyle(InsightsText.recoveryTint(recovery.label))
                    if let recommendation = recovery.recommendation {
                        Text(recommendation)
                            .font(.system(.subheadline, design: .rounded))
                    }
                    if let confidence = InsightsText.confidence(recovery.confidence) {
                        InsightsChip(text: confidence, tint: AyuvoPalette.other)
                    }
                }
                Spacer(minLength: 0)
            }
            .ayuvoCard()
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("recovery.header")
        }
    }
}
