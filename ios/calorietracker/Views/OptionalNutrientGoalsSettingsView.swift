import SwiftUI

struct OptionalNutrientGoalsSettingsView: View {
    let profile: UserProfile

    @AppStorage(OptionalNutrientGoals.storageKey) private var storedGoalsData = Data()
    @State private var goals: OptionalNutrientGoals = .current
    @State private var editingNutrient: OptionalNutrient?

    var body: some View {
        List {
            Section {
                ForEach(OptionalNutrient.allCases) { nutrient in
                    Button {
                        editingNutrient = nutrient
                    } label: {
                        HStack(spacing: 12) {
                            SettingsIcon(nutrient.iconName, tint: SettingsTint.nutrition)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(nutrient.displayName)
                                    .foregroundStyle(.primary)
                                Text(subtitle(for: nutrient))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(goalText(for: nutrient))
                                .foregroundStyle(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Other Nutrients")
            } footer: {
                Text("Separate from calorie, protein, carb, and fat goals. Unless you set your own, goals follow the reference amounts for your age and sex (NIH Office of Dietary Supplements, National Academies). \(NutrientReferenceData.shared.populationNote)")
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Other Nutrients")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            goals = OptionalNutrientGoals.decoded(from: storedGoalsData)
        }
        .onChange(of: storedGoalsData) { _, newData in
            goals = OptionalNutrientGoals.decoded(from: newData)
        }
        .sheet(item: $editingNutrient) { nutrient in
            NutritionPickerSheet(
                label: nutrient.displayName,
                unit: nutrient.unit,
                currentValue: goals.goal(for: nutrient, profile: referenceProfile),
                range: nutrient.range,
                step: nutrient.step,
                allowsCustomValue: true,
                guidanceUpperLimit: nutrient.referenceUpperLimit(for: referenceProfile),
                customValueDetail: nutrient.customValueDetail,
                onSave: { value in
                    save(goals.settingGoal(value, for: nutrient, profile: referenceProfile))
                },
                onResetToAuto: goals.isCustomized(nutrient) ? { save(goals.resettingGoal(for: nutrient)) } : nil,
                resetLabel: nutrient.personalizedDefaultGoal(for: referenceProfile) == nil
                    ? String(localized: "Clear my goal")
                    : String(localized: "Use the recommended amount")
            )
        }
    }

    private var referenceProfile: NutrientsReference.Profile { NutrientCatalog.profile(profile) }

    private func goalText(for nutrient: OptionalNutrient) -> String {
        let value = goals.goal(for: nutrient, profile: referenceProfile)
        if value == 0, !goals.isCustomized(nutrient) { return String(localized: "Not set") }
        return "\(value) \(nutrient.unit)"
    }

    private func subtitle(for nutrient: OptionalNutrient) -> String {
        if goals.isCustomized(nutrient) { return "\(nutrient.localizedGoalStyle) · \(String(localized: "Your goal"))" }
        guard nutrient.personalizedDefaultGoal(for: referenceProfile) != nil else { return nutrient.localizedGoalStyle }
        return "\(nutrient.localizedGoalStyle) · \(String(localized: "Recommended for you"))"
    }

    private func save(_ newGoals: OptionalNutrientGoals) {
        let normalized = newGoals.mergedWithDefaults()
        goals = normalized
        storedGoalsData = normalized.encodedData
    }
}
