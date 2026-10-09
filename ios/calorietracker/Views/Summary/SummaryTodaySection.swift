import SwiftUI

/// "Today" cards: medications, cycle tracking (after setup), partners, an active fast and a logged workout. Each hides itself
/// when empty.
struct SummaryTodaySection: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedWorkoutStore
    @AppStorage(FastingSettings.enabledKey) private var fastingTrackingEnabled = false

    private var todayWorkoutSummary: String? {
        let sessions = workoutStore.latestSession(on: .now)
        let imported = importedWorkoutStore.workouts(on: .now)
        if let sessions {
            let exercises = sessions.exercises.count
            let burn = sessions.caloriesBurned
            var parts = [String(localized: "\(exercises) exercises", comment: "Summary today: exercises in the latest workout session")]
            if let burn { parts.append(String(localized: "\(burn.formatted()) kcal")) }
            return parts.joined(separator: " · ")
        }
        if !imported.isEmpty {
            return String(localized: "\(imported.count) workouts from Apple Health", comment: "Summary today: workouts imported from Apple Health")
        }
        return nil
    }

    var body: some View {
        CollapsingVStack(spacing: 12) {
            // The card keeps its own `home.medicationsCard` id; the container carries the
            // Summary id without hiding it (`children: .contain`).
            VStack(spacing: 0) {
                HomeMedicationsCard(store: medicationStore, onOpen: { navigator.openMedications() })
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("summary.card.medications")

            CycleSummaryCard()

            // Partners' shared health (read-only); hidden until someone is paired.
            PartnerSummaryCard()

            if fastingTrackingEnabled, let active = fastingStore.activeSession {
                Button {
                    navigator.openBrowse([.fasting])
                } label: {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let elapsed = active.duration(at: context.date)
                        SummaryTile(
                            title: String(localized: "Fast in progress"),
                            systemImage: "timer",
                            tint: AyuvoPalette.fasting,
                            trailing: String(localized: "Started \(active.startedAt.formatted(date: .omitted, time: .shortened))"),
                            value: FastingDurationFormatter.compact(seconds: elapsed),
                            detail: String(localized: "\(FastingDurationFormatter.goal(minutes: active.goalMinutes)) goal"),
                            chart: .ring(progress: elapsed / TimeInterval(max(active.goalMinutes, 1) * 60), text: nil)
                        )
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("summary.card.fasting")
            }

            if let todayWorkoutSummary {
                Button {
                    navigator.openWorkouts()
                } label: {
                    SummaryTile(
                        title: String(localized: "Workout today"),
                        systemImage: "figure.strengthtraining.traditional",
                        tint: AyuvoPalette.activity,
                        detail: todayWorkoutSummary
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("summary.card.workouts")
            }
        }
    }
}
