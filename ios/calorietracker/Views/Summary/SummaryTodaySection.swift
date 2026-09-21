import SwiftUI

/// "Today" cards: medications, an active fast and a logged workout. Each hides itself when empty.
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
            var parts = [exercises == 1 ? String(localized: "1 exercise") : String(localized: "\(exercises) exercises")]
            if let burn { parts.append(String(localized: "\(burn.formatted()) kcal")) }
            return parts.joined(separator: " · ")
        }
        if !imported.isEmpty {
            return imported.count == 1
                ? String(localized: "1 workout from Apple Health")
                : String(localized: "\(imported.count) workouts from Apple Health")
        }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The card keeps its own `home.medicationsCard` id; the container carries the
            // Summary id without hiding it (`children: .contain`).
            VStack(spacing: 0) {
                HomeMedicationsCard(store: medicationStore, onOpen: { navigator.openMedications() })
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("summary.card.medications")

            if fastingTrackingEnabled, let active = fastingStore.activeSession {
                Button {
                    navigator.openBrowse([.fasting])
                } label: {
                    ActiveFastingRow(session: active)
                        .ayuvoCard()
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("summary.card.fasting")
            }

            if let todayWorkoutSummary {
                Button {
                    navigator.openWorkouts()
                } label: {
                    MetricRow(
                        systemImage: "figure.strengthtraining.traditional",
                        tint: AyuvoPalette.activity,
                        title: String(localized: "Workout today"),
                        subtitle: todayWorkoutSummary
                    )
                    .ayuvoCard()
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("summary.card.workouts")
            }
        }
    }
}
