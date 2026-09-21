import SwiftUI

/// The Summary "+" menu (docs/ui-structure.md §8): the user's food methods first, then water,
/// body, activity, medications and records. Every entry opens an existing flow.
struct SummaryLogMenu: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(WaterStore.self) private var waterStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(RecordsStore.self) private var recordsStore
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.unitKey) private var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @AppStorage(FastingSettings.enabledKey) private var fastingTrackingEnabled = false

    @State private var showLogWeight = false
    @State private var showLogBodyFat = false
    @State private var showCustomWater = false

    private var waterUnit: WaterUnit { WaterUnit(rawValue: waterUnitRaw) ?? .defaultUnit }
    private var addMenu: AddMenuConfig { AddMenuSettings.load() }

    var body: some View {
        Menu {
            Section(String(localized: "Nutrition")) {
                Menu {
                    foodMethods
                } label: {
                    Label("Food", systemImage: "fork.knife")
                }
                if waterTrackingEnabled {
                    Menu {
                        ForEach([250, 500, 750], id: \.self) { amount in
                            Button(waterUnit.formatted(milliliters: amount)) {
                                _ = waterStore.add(milliliters: amount, on: Date())
                            }
                        }
                        Button("Custom…") { showCustomWater = true }
                    } label: {
                        Label("Water", systemImage: "drop.fill")
                    }
                }
                if fastingTrackingEnabled {
                    Button {
                        navigator.openNutrition(action: .fasting)
                    } label: {
                        Label("Fasting", systemImage: "timer")
                    }
                    .accessibilityIdentifier("log.entry.fasting")
                }
            }
            Section(String(localized: "Body")) {
                Button {
                    showLogWeight = true
                } label: {
                    Label("Weight", systemImage: "scalemass")
                }
                .accessibilityIdentifier("log.entry.weight")
                Button {
                    showLogBodyFat = true
                } label: {
                    Label("Body Fat", systemImage: "percent")
                }
                .accessibilityIdentifier("log.entry.bodyFat")
            }
            Section(String(localized: "Activity")) {
                Button {
                    navigator.openWorkouts()
                } label: {
                    Label("Workout", systemImage: "figure.strengthtraining.traditional")
                }
                .accessibilityIdentifier("log.entry.workout")
            }
            Section(String(localized: "More")) {
                Button {
                    navigator.openMedications()
                } label: {
                    Label("Log dose", systemImage: "pills.fill")
                }
                .accessibilityIdentifier("log.entry.medication")
                Button {
                    recordsStore.requestAddRecord()
                } label: {
                    Label("Add record", systemImage: "doc.text.fill")
                }
                .accessibilityIdentifier("log.entry.record")
            }
        } label: {
            Image(systemName: "plus")
        }
        .accessibilityLabel(Text("Log"))
        .accessibilityIdentifier("summary.add")
        .sheet(isPresented: $showLogWeight) {
            LogWeightSheet(currentWeightKg: weightStore.latestEntry?.weightKg ?? profileStore.profile.weightKg) { weightKg in
                weightStore.addEntry(WeightEntry(weightKg: weightKg))
            }
        }
        .sheet(isPresented: $showLogBodyFat) {
            let seed = bodyFatStore.latestEntry?.bodyFatFraction ?? profileStore.profile.bodyFatPercentage ?? 0.20
            LogBodyFatSheet(currentFraction: seed) { fraction in
                bodyFatStore.addEntry(BodyFatEntry(bodyFatFraction: fraction))
            }
        }
        .sheet(isPresented: $showCustomWater) {
            WaterCustomAmountSheet(unit: waterUnit) { milliliters in
                _ = waterStore.add(milliliters: milliliters, on: Date())
            }
        }
    }

    @ViewBuilder
    private var foodMethods: some View {
        let config = addMenu
        if config.usesFlatLayout {
            ForEach(config.flatMethods) { method in
                methodButton(method)
            }
        } else {
            ForEach(config.groups) { group in
                Section(group.name) {
                    ForEach(group.methods) { method in
                        methodButton(method)
                    }
                }
            }
        }
    }

    private func methodButton(_ method: FoodLogMethod) -> some View {
        Button {
            if let action = method.quickAction {
                navigator.openNutrition(action: action)
            } else {
                navigator.openNutrition(method: method)
            }
        } label: {
            Label(method.title, systemImage: method.systemImageName)
        }
        .accessibilityIdentifier("log.entry.\(method.rawValue)")
    }
}
