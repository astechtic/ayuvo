import SwiftUI

/// Daily Recovery: score gauge, label and suggestion, the signals behind it, yesterday's training load and the
/// last 30 days. Every number comes from `RecoveryEngine`.
struct RecoveryView: View {
    @Environment(InsightsStore.self) private var store

    var body: some View {
        InsightsScreen(title: "Recovery", topic: .recovery, disclaimers: ["general", "background"],
                       inputs: { Self.inputRows(store.recovery) }) {
            if let recovery = store.recovery {
                RecoveryHeaderCard(recovery: recovery, baselineChange: Self.baselineChange(store.report?.recoveryHistory ?? [], today: recovery))
                if recovery.isReady, let v2 = recovery.v2 {
                    RecoverySummaryCard(v2: v2)
                    driversCard(v2)
                } else if recovery.isReady {
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

    /// Main drivers (v2): direction, value against the personal baseline and the robust z.
    private func driversCard(_ v2: AJ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            AyuvoSectionHeader("Main drivers")
            let drivers = v2["drivers"].array.filter { $0["direction"].string != "neutral" }
            if drivers.isEmpty {
                Text("Every signal was close to your baseline.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(drivers.enumerated()), id: \.offset) { _, d in
                driverRow(d)
            }
        }
        .ayuvoCard()
        .accessibilityIdentifier("recovery.signals")
    }

    private func driverRow(_ d: AJ) -> some View {
        let id = d["id"].string ?? ""
        let positive = d["direction"].string == "positive"
        let metric = id == "sleep" ? "sleep_duration" : id
        let detail: String? = id == "training_load" ? nil : [
            d["value"].double.map { _ in AnalyticsFormat.value(d["value"].double, metric: metric) },
            d["baseline"].double.map { _ in id == "sleep"
                ? String(localized: "need \(AnalyticsFormat.value(d["baseline"].double, metric: metric))")
                : String(localized: "baseline \(AnalyticsFormat.value(d["baseline"].double, metric: metric))") },
            AnalyticsFormat.z(d["z"].double),
        ].compactMap { $0 }.joined(separator: " · ")
        return HStack(alignment: .top) {
            Image(systemName: positive ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .foregroundStyle(positive ? AyuvoPalette.nutrition : AyuvoPalette.heart)
            VStack(alignment: .leading, spacing: 2) {
                Text(AnalyticsText.driverText(d))
                    .font(.system(.subheadline, design: .rounded))
                if let detail, !detail.isEmpty {
                    Text(detail).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                }
            }
            Spacer()
            InsightsChip(text: InsightsDisplayFormat.signed(d["impact"].double ?? 0, 1), tint: positive ? AyuvoPalette.nutrition : AyuvoPalette.heart)
        }
        .accessibilityElement(children: .combine)
    }

    /// Percent change of today's score against the median of the previous 28 scored days, nil below 7 of them.
    static func baselineChange(_ history: [RecoveryResult], today: RecoveryResult) -> Double? {
        guard let score = today.score else { return nil }
        let prior = history.filter { $0.day < today.day && $0.isReady }.suffix(28).compactMap(\.score).map(Double.init)
        guard prior.count >= 7 else { return nil }
        let median = AMath.median(prior)
        return median == 0 ? nil : (Double(score) - median) / median * 100
    }

    private func signalsCard(_ recovery: RecoveryResult) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            AyuvoSectionHeader("Signals")
            if recovery.positives.isEmpty && recovery.negatives.isEmpty {
                Text("Every signal was close to your baseline.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            ForEach(recovery.positives) { signal in
                signalRow(signal, in: recovery, positive: true)
            }
            ForEach(recovery.negatives) { signal in
                signalRow(signal, in: recovery, positive: false)
            }
        }
        .ayuvoCard()
        .accessibilityIdentifier("recovery.signals")
    }

    private func signalRow(_ signal: RecoverySignal, in recovery: RecoveryResult, positive: Bool) -> some View {
        HStack {
            Image(systemName: positive ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .foregroundStyle(positive ? AyuvoPalette.nutrition : AyuvoPalette.heart)
            Text(InsightsConfig.shared.signalText(signal, in: recovery))
                .font(.system(.subheadline, design: .rounded))
            Spacer()
            InsightsChip(text: InsightsDisplayFormat.signed(signal.impact, 1), tint: positive ? AyuvoPalette.nutrition : AyuvoPalette.heart)
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
                    if component.value != nil, store.isFromScan(Self.series(component.id), day: recovery.day) {
                        // Shown the way the overnight fallback is: a note on the component (docs/camera-vitals.md §7).
                        Label("From a finger camera scan", systemImage: VitalsMode.finger.systemImage)
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("recovery.scanFallback.\(component.id)")
                    }
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
                InsightsChip(text: InsightsConfig.shared.trainingLoadLabel(load.category, english: load.label), tint: load.category == "high" ? AyuvoPalette.heart : AyuvoPalette.activity)
            }
            if load.modifier != 0 {
                Text("Recent training load is high for you, so \(abs(load.modifier)) points were taken off.")
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
        InsightsConfig.shared.metric(id) != nil ? InsightsConfig.shared.metricLabel(id) : AnalyticsText.metricLabel(id)
    }

    /// The insights series behind a component.
    static func series(_ componentID: String) -> String {
        InsightsConfig.shared.recovery.components.first { $0.id == componentID }?.metric ?? componentID
    }

    static func componentDetail(_ c: RecoveryComponent) -> String {
        guard c.available else {
            if c.value == nil { return String(localized: "No reading last night") }
            return String(localized: "Learning (\(c.baselineN)/\(AnalyticsConfig.shared["recovery"]["min_points"].int ?? 7) nights)")
        }
        var parts: [String] = []
        if let baseline = c.baseline {
            parts.append(String(localized: "Baseline \(InsightsText.value(baseline, metric: c.id))"))
        }
        if let pct = c.pct { parts.append(InsightsText.percent(pct)) }
        if let z = AnalyticsFormat.z(c.z) { parts.append(z) }
        if let impact = c.impact { parts.append(String(localized: "\(InsightsDisplayFormat.signed(impact, 1)) points")) }
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
                    ? String(localized: "Baseline \(InsightsText.value(c.baseline, metric: c.id)) · weight \(c.weight.formatted()) · sub-score \(c.subscore.map { InsightsDisplayFormat.number($0) } ?? InsightsText.missing)")
                    : componentDetail(c),
                missing: !c.available
            )
        }
        if let load = recovery.load {
            rows.append(InsightsInputRow(id: "load", title: String(localized: "Yesterday's training load"), value: InsightsConfig.shared.trainingLoadLabel(load.category, english: load.label),
                                         detail: load.modifier == 0 ? nil : String(localized: "\(load.modifier) points")))
        }
        return rows
    }
}

/// Gauge, label, suggestion and confidence (also the top of the Summary card's destination).
struct RecoveryHeaderCard: View {
    let recovery: RecoveryResult
    /// % change against the median of the previous 28 scored days (v2 screens).
    var baselineChange: Double? = nil

    var body: some View {
        switch recovery.status {
        case "collecting":
            InsightsCollectingView(
                title: String(localized: "Learning your baseline (\(recovery.collecting?.have ?? 0)/\(recovery.collecting?.need ?? 7) nights)"),
                detail: String(localized: "Recovery needs about a week of sleep with HRV or resting heart rate. Keep wearing your watch to bed."),
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
                    Text(InsightsConfig.shared.bandLabel(recovery.label, english: recovery.labelText) ?? InsightsText.missing)
                        .font(.system(.title3, design: .rounded, weight: .bold))
                        .foregroundStyle(InsightsText.recoveryTint(recovery.label))
                    if let recommendation = InsightsConfig.shared.bandRecommendation(recovery.label, english: recovery.recommendation) {
                        Text(recommendation)
                            .font(.system(.subheadline, design: .rounded))
                    }
                    if let change = baselineChange {
                        Text(String(localized: "\(change < 0 ? "↓" : "↑") \(Int(abs(change).rounded()))% vs your 28-day baseline"))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    if let v2 = recovery.v2, let confidence = AnalyticsFormat.percent(v2["confidence"].double) {
                        InsightsChip(text: confidence, tint: (v2["confidence"].double ?? 0) >= 0.5 ? AyuvoPalette.insights : AyuvoPalette.activity)
                    } else if let confidence = InsightsText.confidence(recovery.confidence) {
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

/// Recovery v2 explanation: the summary built from the drivers, the warnings and the coverage.
struct RecoverySummaryCard: View {
    let v2: AJ

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let summary = AnalyticsText.summary(v2) {
                Text(summary)
                    .font(.system(.subheadline, design: .rounded))
                    .fixedSize(horizontal: false, vertical: true)
            }
            let codes = Array(Set(v2["warnings"].array.compactMap { $0["code"].string })).sorted()
            ForEach(codes, id: \.self) { code in
                Label(AnalyticsText.warning(code), systemImage: "exclamationmark.circle")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let coverage = v2["coverage"].double {
                Text(String(localized: "Signals used: \(Int((coverage * 100).rounded()))% of the full weight"))
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            AnalyticsSourceLine(classification: v2["classification"].string,
                                source: "\(v2["algorithm_id"].string ?? "ayuvo.recovery")@\(v2["algorithm_version"].int ?? 2)")
        }
        .ayuvoCard()
        .accessibilityIdentifier("recovery.summary")
    }
}
