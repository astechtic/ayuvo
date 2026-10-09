import SwiftUI

/// Summary › Insights (after the rings): Recovery, Health Age and Daily Review cards, or one "Learning your
/// baseline" card while Recovery collects nights. Hidden while Health Sync or Insights is off.
struct InsightsSummarySection: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(InsightsStore.self) private var store
    @Environment(HealthDataStore.self) private var healthDataStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @AppStorage(InsightsSettings.enabledKey) private var insightsEnabled = true
    @State private var infoTopic: InsightsTopic?

    private var refreshKey: String {
        "\(healthKitEnabled)|\(insightsEnabled)|\(healthDataStore.snapshotRevision)|\(foodStore.revision)|\(waterStore.revision)|\(workoutStore.revision)"
    }

    var body: some View {
        // A zero-height anchor so the refresh task runs even while the section itself is hidden.
        VStack(alignment: .leading, spacing: 0) {
            Color.clear.frame(height: 0)
            if healthKitEnabled, insightsEnabled, store.isAvailable, let report = store.report {
                VStack(alignment: .leading, spacing: 12) {
                    AyuvoSectionHeader("Insights") {
                        Button("See All") { navigator.openInsights(nil) }
                            .accessibilityIdentifier("summary.insights.all")
                    }
                    if let collecting = store.collecting {
                        learningCard(collecting)
                    } else {
                        recoveryCard(report.recovery)
                        healthAgeCard(report.healthAge)
                        reviewCard(report.reviews[report.today])
                    }
                }
            }
        }
        .task(id: refreshKey) { await store.refresh() }
        .sheet(item: $infoTopic) { topic in
            InsightMethodologySheet(topic: topic, inputs: inputs(for: topic))
        }
    }

    private func inputs(for topic: InsightsTopic) -> [InsightsInputRow] {
        switch topic {
        case .recovery: RecoveryView.inputRows(store.recovery)
        case .healthAge: HealthAgeView.inputRows(store.healthAge)
        case .dailyReview: DailyReviewView.inputRows(store.report.flatMap { store.review(for: $0.today) })
        default: []
        }
    }

    private func open(_ route: InsightsRoute) {
        navigator.summaryPath.append(route)
    }

    private func infoButton(_ topic: InsightsTopic) -> some View {
        Button {
            infoTopic = topic
        } label: {
            Image(systemName: "info.circle")
                .foregroundStyle(.secondary)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(Text("How we calculate this"))
    }

    private func learningCard(_ collecting: InsightsCollecting) -> some View {
        Button { open(.recovery) } label: {
            HStack(alignment: .top) {
                InsightsCollectingView(
                    title: String(localized: "Learning your baseline (\(collecting.have)/\(collecting.need) nights)"),
                    detail: String(localized: "Recovery, Health Age and your Daily Review start once Ayuvo knows your usual sleep and heart readings."),
                    collecting: collecting
                )
            }
        }
        .buttonStyle(.plain)
        .overlay(alignment: .topTrailing) { infoButton(.recovery).padding(12) }
        .accessibilityIdentifier("summary.card.insightsLearning")
    }

    private func recoveryCard(_ recovery: RecoveryResult) -> some View {
        let label = recovery.isReady ? InsightsConfig.shared.bandLabel(recovery.label, english: recovery.labelText) : nil
        return Button { open(.recovery) } label: {
            SummaryTile(
                title: String(localized: "Recovery"),
                systemImage: "bolt.heart.fill",
                tint: AyuvoPalette.insights,
                trailing: String(localized: "Today"),
                value: label ?? InsightsText.missing,
                detail: recovery.isReady ? topSignals(recovery).first.map { InsightsConfig.shared.signalText($0, in: recovery) } : Self.waitingText(recovery),
                chart: .ring(progress: Double(recovery.isReady ? recovery.score ?? 0 : 0) / 100,
                             text: recovery.isReady ? recovery.score.map { "\($0)" } : nil)
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("summary.card.recovery")
    }

    private func healthAgeCard(_ result: HealthAgeResult) -> some View {
        let ready = result.isReady && result.healthAge != nil
        return Button { open(.healthAge) } label: {
            SummaryTile(
                title: String(localized: "Health Age"),
                systemImage: "hourglass",
                tint: AyuvoPalette.insights,
                value: ready ? (result.healthAge ?? 0).formatted(.number.precision(.fractionLength(1))) : InsightsText.missing,
                detail: ready
                    ? result.actualAge.map { String(localized: "Actual \($0.formatted(.number.precision(.fractionLength(1))))") }
                    : (result.collecting.map { String(localized: "Collecting data (\($0.have)/\($0.need) days)") }
                        ?? String(localized: "Not enough data yet"))
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("summary.card.healthAge")
    }

    private func reviewCard(_ review: DailyReviewResult?) -> some View {
        let score = review?.dayScore
        return Button { open(.review(nil)) } label: {
            SummaryTile(
                title: String(localized: "Daily Review"),
                systemImage: "checklist",
                tint: AyuvoPalette.insights,
                trailing: String(localized: "Today"),
                value: score.map { "\($0)" } ?? InsightsText.missing,
                detail: score == nil ? String(localized: "Nothing logged yet") : String(localized: "Day Score"),
                chart: score.map { .ring(progress: Double($0) / 100, text: nil) } ?? .none
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("summary.card.review")
    }

    private func topSignals(_ recovery: RecoveryResult) -> [RecoverySignal] {
        Array((recovery.positives + recovery.negatives).sorted { abs($0.impact) > abs($1.impact) }.prefix(2))
    }

    static func waitingText(_ recovery: RecoveryResult) -> String {
        switch recovery.status {
        case "no_sleep": String(localized: "Waiting for last night's sleep")
        case "no_heart_data": String(localized: "No heart reading for last night")
        default: String(localized: "Not ready yet")
        }
    }
}

extension InsightsTopic: Identifiable {
    var id: String { rawValue }
}
