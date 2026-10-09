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
                            SummaryTile(
                                title: item.record.title,
                                systemImage: "doc.text.fill",
                                tint: AyuvoPalette.records,
                                detail: item.highlight.displayText
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("summary.card.records")
                    }

                    if let weightTrendText {
                        Button {
                            navigator.summaryPath.append(MetricRoute.detail(.app(.weight)))
                        } label: {
                            SummaryTile(
                                title: String(localized: "Weight trend"),
                                systemImage: "scalemass.fill",
                                tint: AyuvoPalette.body,
                                detail: weightTrendText
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("summary.card.weightTrend")
                    }

                    if let latestWorkout {
                        Button {
                            navigator.openWorkouts()
                        } label: {
                            SummaryTile(
                                title: String(localized: "Last workout"),
                                systemImage: "dumbbell.fill",
                                tint: AyuvoPalette.activity,
                                trailing: HealthUnitFormatting.relativeText(latestWorkout.completedAt),
                                detail: latestWorkout.completedAt.formatted(date: .abbreviated, time: .shortened)
                            )
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
