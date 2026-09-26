import Charts
import SwiftUI

/// Pushed Insights screens. Registered on the Summary and Browse stacks (`insightsRouteDestinations`).
enum InsightsRoute: Hashable {
    case recovery, healthAge, trends, patterns
    /// Daily Review of a day key ("2026-09-20"); nil → today.
    case review(String?)
}

extension View {
    func insightsRouteDestinations() -> some View {
        navigationDestination(for: InsightsRoute.self) { route in
            switch route {
            case .recovery: RecoveryView()
            case .healthAge: HealthAgeView()
            case .review(let day): DailyReviewView(initialDay: day)
            case .trends: InsightsTrendsView()
            case .patterns: PatternsView()
            }
        }
    }
}

// MARK: - Formatting

/// Display text for engine values. Missing values are "—", never 0.
enum InsightsText {
    static let missing = "—"

    static func value(_ value: Double?, metric: String) -> String {
        guard let value else { return missing }
        switch metric {
        case "sleep", "workout": return InsightsFormat.duration(value)
        case "hrv": return String(localized: "\(Int(value.rounded())) ms")
        case "resting_heart_rate": return String(localized: "\(Int(value.rounded())) bpm")
        case "respiratory_rate": return String(localized: "\(value.formatted(.number.precision(.fractionLength(1)))) br/min")
        case "blood_oxygen", "body_fat": return "\(value.formatted(.number.precision(.fractionLength(1))))%"
        case "vo2_max": return String(localized: "\(value.formatted(.number.precision(.fractionLength(1)))) mL/kg/min")
        case "steps": return InsightsFormat.groupInt(Int(value.rounded()))
        case "active_energy": return String(localized: "\(Int(value.rounded()).formatted()) kcal")
        case "weight":
            let unit = WeightUnit(rawValue: UserDefaults.standard.string(forKey: WeightUnit.storageKey) ?? "") ?? .lbs
            let shown = unit == .kg ? value : value / 0.45359237
            return "\(shown.formatted(.number.precision(.fractionLength(1)))) \(unit == .kg ? "kg" : "lb")"
        case "bmi": return value.formatted(.number.precision(.fractionLength(1)))
        default: return value.formatted(.number.precision(.fractionLength(0...1)))
        }
    }

    /// "+8%" / "−3%" / "0%".
    static func percent(_ pct: Double?) -> String {
        guard let pct else { return missing }
        return InsightsFormat.signed(pct, 0) + "%"
    }

    /// Signed years ("−2.1 years").
    static func years(_ value: Double?) -> String {
        guard let value else { return missing }
        return String(localized: "\(InsightsFormat.signed(value, 1)) years")
    }

    static func dayTitle(_ day: String, today: String?) -> String {
        if day == today { return String(localized: "Today") }
        if let today, InsightsDay.between(day, today) == 1 { return String(localized: "Yesterday") }
        guard let date = InsightsDay.date(day) else { return day }
        return date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
    }

    static func confidence(_ value: String?) -> String? {
        switch value {
        case "high": String(localized: "High confidence")
        case "medium": String(localized: "Medium confidence")
        case "low": String(localized: "Low confidence")
        default: nil
        }
    }

    /// Label colour of a Recovery band.
    static func recoveryTint(_ label: String?) -> Color {
        switch label {
        case "good": AyuvoPalette.nutrition
        case "moderate": AyuvoPalette.activity
        case "low": AyuvoPalette.heart
        default: AyuvoPalette.other
        }
    }

    static func scoreTint(_ score: Int?) -> Color {
        guard let score else { return AyuvoPalette.other }
        return score >= 67 ? AyuvoPalette.nutrition : (score >= 34 ? AyuvoPalette.activity : AyuvoPalette.heart)
    }
}

// MARK: - Pieces

/// Score ring with the number in the middle ("—" while there is no score).
struct InsightsScoreRing: View {
    let score: Int?
    let tint: Color
    var size: CGFloat = 88
    var lineWidth: CGFloat = 10

    var body: some View {
        ZStack {
            ActivityRingView(progress: Double(score ?? 0) / 100, ringWidth: lineWidth, gradientColors: [tint, tint], showsEndCap: false)
            Text(score.map { "\($0)" } ?? InsightsText.missing)
                .font(.system(size: size * 0.32, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.primary)
        }
        .frame(width: size, height: size)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(score.map { String(localized: "Score \($0) out of 100") } ?? String(localized: "No score yet")))
    }
}

/// Small capsule ("+8", "High confidence", "Improving").
struct InsightsChip: View {
    let text: String
    var tint: Color = AyuvoPalette.insights

    var body: some View {
        Text(text)
            .font(.system(.caption, design: .rounded, weight: .semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(tint.opacity(0.14), in: Capsule())
            .lineLimit(1)
    }
}

/// Toolbar ⓘ that opens "How we calculate this".
struct InsightsInfoButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "info.circle")
        }
        .accessibilityLabel(Text("How we calculate this"))
        .accessibilityIdentifier("insights.info")
    }
}

/// Disclaimer footer: the general text plus any feature disclaimers, straight from the config.
struct InsightsDisclaimerFooter: View {
    var keys: [String] = ["general"]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(keys, id: \.self) { key in
                Text(InsightsConfig.shared.disclaimer(key))
            }
        }
        .font(.system(.footnote, design: .rounded))
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 4)
        .accessibilityIdentifier("insights.disclaimer")
    }
}

/// "Learning your baseline (n/14 nights)" / "Collecting data (x/30 days)".
struct InsightsCollectingView: View {
    let title: String
    let detail: String
    let collecting: InsightsCollecting?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: "hourglass")
                .font(.system(.headline, design: .rounded))
                .foregroundStyle(AyuvoPalette.insights)
            if let collecting, collecting.need > 0 {
                ProgressView(value: Double(min(collecting.have, collecting.need)), total: Double(collecting.need))
                    .tint(AyuvoPalette.insights)
            }
            Text(detail)
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .ayuvoCard()
    }
}

/// Wraps an Insights screen: scrolling cards, the disclaimer footer, the ⓘ sheet and a refresh on appear.
struct InsightsScreen<Content: View>: View {
    let title: LocalizedStringKey
    let topic: InsightsTopic
    var disclaimers: [String] = ["general"]
    let inputs: () -> [InsightsInputRow]
    @ViewBuilder let content: () -> Content
    @Environment(InsightsStore.self) private var store
    @State private var showInfo = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !store.isAvailable, store.report == nil, !store.isLoading {
                    InsightsUnavailableCard()
                } else if store.report == nil {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 40)
                } else {
                    content()
                }
                InsightsDisclaimerFooter(keys: disclaimers)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .ayuvoScreenBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                InsightsInfoButton { showInfo = true }
            }
        }
        .sheet(isPresented: $showInfo) {
            InsightMethodologySheet(topic: topic, inputs: inputs())
        }
        .task { await store.refresh() }
        .refreshable { await store.refresh(force: true) }
    }
}

/// Health sync or Insights is off.
struct InsightsUnavailableCard: View {
    @Environment(AppNavigator.self) private var navigator

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Insights need Apple Health", systemImage: "heart.text.square")
                .font(.system(.headline, design: .rounded))
            Text("Turn on Health Sync and Insights in Settings. Scores are calculated from your own synced data.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
            Button("Open Settings") { navigator.openSettings(.insights) }
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
        }
        .ayuvoCard()
    }
}

// MARK: - Chart

/// One value per day on the shared chart axes (`ChartAxisStyle`, `ChartScrubOverlay`, `ChartCallout`):
/// the 30-day Recovery chart and the 12-week Health Age chart. Days without a value are simply absent.
struct InsightsTrendChart: View {
    struct Point: Identifiable, Equatable {
        let day: String
        let date: Date
        let value: Double
        var id: String { day }
    }

    let points: [Point]
    let tint: Color
    var bars = true
    var yDomain: ClosedRange<Double>?
    let valueText: (Double) -> String
    @State private var selected: Point?

    var body: some View {
        let ticks = ChartAxisStyle.yTicks(points.map(\.value) + (yDomain.map { [$0.lowerBound, $0.upperBound] } ?? []), includeZero: bars)
        Chart {
            ForEach(points) { point in
                if bars {
                    BarMark(x: .value("Day", point.date, unit: .day), y: .value("Value", point.value), width: ChartAxisStyle.barWidth)
                        .foregroundStyle(tint.opacity(selected == nil || selected == point ? 1 : ChartAxisStyle.fadedOpacity))
                        .clipShape(ChartAxisStyle.barShape)
                } else {
                    LineMark(x: .value("Day", point.date), y: .value("Value", point.value))
                        .interpolationMethod(.monotone)
                        .foregroundStyle(tint)
                    PointMark(x: .value("Day", point.date), y: .value("Value", point.value))
                        .foregroundStyle(tint)
                        .symbolSize(selected == point ? 60 : 24)
                }
            }
        }
        .chartYScale(domain: ticks.min...ticks.max)
        .chartYAxis { ChartAxisStyle.yAxis(ticks.ticks) }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine().foregroundStyle(.secondary.opacity(ChartAxisStyle.gridOpacity))
                AxisValueLabel(format: .dateTime.month(.abbreviated).day())
            }
        }
        .chartOverlay { proxy in
            ChartScrubOverlay(proxy: proxy, points: points, date: { $0.date }, selected: $selected, persistent: true) { point in
                ChartCallout(value: valueText(point.value), caption: point.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
            }
        }
        .frame(height: ChartAxisStyle.plotHeight)
        .padding(.top, 8)
        .accessibilityIdentifier("insights.chart")
    }
}

// MARK: - AI

/// "Explain with AI" (on tap only), with the route status line, or the "Set up AI in Settings" hint.
struct InsightsExplainSection: View {
    let kind: InsightsAIKind
    let summary: InsightsSummary?
    @State private var explainer = InsightsExplainer()
    @Environment(AppNavigator.self) private var navigator

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch explainer.state {
            case .explained(let output, let status):
                Label(output.headline, systemImage: "sparkles")
                    .font(.system(.headline, design: .rounded))
                ForEach(Array(output.bullets.enumerated()), id: \.offset) { _, bullet in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(verbatim: "•")
                        Text(bullet)
                    }
                    .font(.system(.subheadline, design: .rounded))
                }
                Text(status)
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("insights.ai.status")
                Text(InsightsConfig.shared.disclaimer("ai"))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            case .failed(let message):
                Text(message)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                button
            case .running:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Explaining…").foregroundStyle(.secondary)
                }
                .font(.system(.subheadline, design: .rounded))
            case .idle:
                if explainer.isConfigured {
                    button
                } else {
                    Button {
                        navigator.openSettings(.aiProviders)
                    } label: {
                        Label("Set up AI in Settings to get an explanation", systemImage: "sparkles")
                    }
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .accessibilityIdentifier("insights.ai.setup")
                }
            }
        }
        .ayuvoCard()
    }

    private var button: some View {
        Button {
            guard let summary else { return }
            Task { await explainer.explain(kind: kind, summary: summary) }
        } label: {
            Label("Explain with AI", systemImage: "sparkles")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
        }
        .disabled(summary == nil)
        .accessibilityIdentifier("insights.ai.explain")
    }
}
