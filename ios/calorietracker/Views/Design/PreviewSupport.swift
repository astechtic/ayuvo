#if DEBUG
import SwiftUI

/// Injects every `@Observable` store the app provides (calorietrackerApp) so previews of any
/// screen never trap on a missing `@Environment` object.
private struct AyuvoPreviewEnvironment: ViewModifier {
    @State private var foodStore = FoodStore()
    @State private var weightStore = WeightStore()
    @State private var bodyFatStore = BodyFatStore()
    @State private var bodyMeasurementStore = BodyMeasurementStore()
    @State private var notificationManager = NotificationManager()
    @State private var healthKitManager = HealthKitManager()
    @State private var profileStore = ProfileStore()
    @State private var chatStore = CoachStore()
    @State private var waterStore = WaterStore()
    @State private var fastingStore = FastingStore()
    @State private var strengthWorkoutStore = StrengthWorkoutStore()
    @State private var importedHealthWorkoutStore = ImportedHealthWorkoutStore()
    @State private var appBackupService = AppBackupService()
    @State private var healthDataStore = HealthDataStore()
    @State private var recordsStore = RecordsStore()
    @State private var medicationStore = MedicationStore()

    func body(content: Content) -> some View {
        content
            .environment(foodStore)
            .environment(weightStore)
            .environment(bodyFatStore)
            .environment(bodyMeasurementStore)
            .environment(notificationManager)
            .environment(healthKitManager)
            .environment(profileStore)
            .environment(chatStore)
            .environment(waterStore)
            .environment(fastingStore)
            .environment(strengthWorkoutStore)
            .environment(importedHealthWorkoutStore)
            .environment(appBackupService)
            .environment(healthDataStore)
            .environment(recordsStore)
            .environment(medicationStore)
    }
}

extension View {
    func ayuvoPreviewEnvironment() -> some View {
        modifier(AyuvoPreviewEnvironment())
    }
}
#endif
