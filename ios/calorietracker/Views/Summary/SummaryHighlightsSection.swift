import SwiftUI

/// Up to three real-data highlights: a flagged record value, the weight trend and the last workout.
struct SummaryHighlightsSection: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @AppStorage(WeightUnit.storageKey) private var weightUnitRaw = WeightUnit.lbs.rawValue

    @State private var forecast: WeightForecast?

    private var weightTrendText: String? {
        guard let forecast, forecast.hasEnoughData, let weekly = forecast.observedWeeklyChangeKg else { return nil }
        let unit = WeightUnit.current
        let shown = unit == .lbs ? weekly * 2.20462 : weekly
        let magnitude = HealthUnitFormatting.number(abs(shown), fractionDigits: 1)
        if abs(shown) < 0.05 { return String(localized: "Your weight is holding steady") }
        return shown < 0
            ? String(localized: "Down \(magnitude) \(unit.rawValue) a week")
            : String(localized: "Up \(magnitude) \(unit.rawValue) a week")
    }

    private var latestWorkout: StrengthWorkoutSession? { workoutStore.sortedCompletedSessions.first }

    private var hasContent: Bool {
        recordsStore.importantHighlights.first != nil || weightTrendText != nil || latestWorkout != nil
    }

    var body: some View {
        if hasContent {
            VStack(alignment: .leading, spacing: 12) {
                AyuvoSectionHeader("Highlights")
                VStack(alignment: .leading, spacing: 12) {
                    if let item = recordsStore.importantHighlights.first {
                        Button {
                            recordsStore.openRecordFromCoach(item.record.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                RecordHighlightRow(text: item.highlight.text)
                                Text(item.record.title)
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .ayuvoCard()
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("summary.card.records")
                    }

                    if let weightTrendText {
                        Button {
                            navigator.summaryPath.append(MetricRoute.detail(.app(.weight)))
                        } label: {
                            MetricRow(
                                systemImage: "scalemass",
                                tint: AyuvoPalette.body,
                                title: String(localized: "Weight trend"),
                                subtitle: weightTrendText
                            )
                            .ayuvoCard()
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("summary.card.weightTrend")
                    }

                    if let latestWorkout {
                        Button {
                            navigator.openWorkouts()
                        } label: {
                            MetricRow(
                                systemImage: "dumbbell.fill",
                                tint: AyuvoPalette.activity,
                                title: String(localized: "Last workout"),
                                subtitle: latestWorkout.completedAt.formatted(date: .abbreviated, time: .shortened)
                            )
                            .ayuvoCard()
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("summary.card.lastWorkout")
                    }
                }
            }
            .task(id: "\(weightStore.revision)|\(foodStore.revision)") {
                forecast = WeightAnalysisService.compute(
                    weights: weightStore.entries,
                    foods: foodStore.entries,
                    profile: profileStore.profile
                )
            }
        }
    }
}
