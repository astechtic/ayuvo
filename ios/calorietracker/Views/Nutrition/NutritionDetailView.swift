import SwiftUI
import Photos
import PhotosUI
import PDFKit
import UIKit
import HealthKit
import StoreKit
import WidgetKit
import AVFoundation
import Speech
import UniformTypeIdentifiers

// MARK: - Nutrition Detail View
struct NutritionDetailView: View {
    let date: Date
    @Binding var homeTopNutrientsRaw: String
    @Environment(FoodStore.self) private var foodStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage(OptionalNutrientGoals.storageKey) private var optionalNutrientGoalsData = Data()
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) private var waterDailyGoal = WaterSettings.defaultDailyGoalMl
    @AppStorage(WaterSettings.unitKey) private var waterUnitRaw = WaterUnit.defaultUnit.rawValue
    @State private var showHomeNutrientPicker = false

    private var userProfile: UserProfile { profileStore.profile }
    private var optionalNutrientGoals: OptionalNutrientGoals { OptionalNutrientGoals.decoded(from: optionalNutrientGoalsData) }
    private var homeTopNutrients: [HomeTopNutrient] { HomeTopNutrient.selection(from: homeTopNutrientsRaw) }
    private var waterUnit: WaterUnit { WaterUnit(rawValue: waterUnitRaw) ?? .defaultUnit }
    private var homeTopNutrientNames: String {
        let nutrientNames = (waterTrackingEnabled ? Array(homeTopNutrients.prefix(3)) : homeTopNutrients)
            .map(\.displayName)
        return (waterTrackingEnabled ? nutrientNames + ["Water"] : nutrientNames)
            .joined(separator: ", ")
    }

    var body: some View {
        let _ = profileStore.profile
        return NavigationStack {
            List {
                Section {
                    Button {
                        showHomeNutrientPicker = true
                    } label: {
                        HStack(spacing: 12) {
                            Label("Home Nutrient Cards", systemImage: "square.grid.3x1.fill")
                                .foregroundStyle(.primary)
                            Spacer()
                            Text(homeTopNutrientNames)
                                .font(.system(.footnote, design: .rounded))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .listRowBackground(AppColors.appCard)

                if waterTrackingEnabled {
                    Section {
                        NutritionDetailRow(
                            icon: "drop.fill",
                            label: "Water",
                            value: waterUnit.displayValue(forMilliliters: waterStore.total(on: date)),
                            unit: waterUnit.symbol,
                            goal: waterUnit.displayValue(forMilliliters: waterDailyGoal)
                        )
                    } header: {
                        Text("Water")
                    } footer: {
                        Text("Water is shown after your selected nutrients while Water Tracking is enabled.")
                    }
                    .listRowBackground(AppColors.appCard)
                }

                Section("Macros") {
                    NutritionDetailRow(icon: "flame.fill", label: "Calories", value: "\(foodStore.calories(for: date))", unit: "kcal", goal: "\(userProfile.effectiveCalories)")
                    NutritionDetailRow(icon: "p.circle.fill", label: "Protein", value: MacroValueFormatter.string(foodStore.protein(for: date)), unit: "g", goal: "\(userProfile.effectiveProtein)")
                    NutritionDetailRow(icon: "c.circle.fill", label: "Carbs", value: MacroValueFormatter.string(foodStore.carbs(for: date)), unit: "g", goal: "\(userProfile.effectiveCarbs)")
                    NutritionDetailRow(icon: "f.circle.fill", label: "Fat", value: MacroValueFormatter.string(foodStore.fat(for: date)), unit: "g", goal: "\(userProfile.effectiveFat)")
                }
                .listRowBackground(AppColors.appCard)

                Section("Detailed Nutrition") {
                    optionalNutritionRow(.sugar, value: foodStore.sugar(for: date))
                    optionalNutritionRow(.addedSugar, value: foodStore.addedSugar(for: date))
                    optionalNutritionRow(.fiber, value: foodStore.fiber(for: date))
                    optionalNutritionRow(.saturatedFat, value: foodStore.saturatedFat(for: date))
                    NutritionDetailRow(icon: "drop", label: "Mono Unsat. Fat", value: formatMicro(foodStore.monounsaturatedFat(for: date)), unit: "g")
                    NutritionDetailRow(icon: "drop.halffull", label: "Poly Unsat. Fat", value: formatMicro(foodStore.polyunsaturatedFat(for: date)), unit: "g")
                    optionalNutritionRow(.cholesterol, value: foodStore.cholesterol(for: date))
                    optionalNutritionRow(.caffeine, value: foodStore.caffeine(for: date))
                    optionalNutritionRow(.sodium, value: foodStore.sodium(for: date))
                    optionalNutritionRow(.potassium, value: foodStore.potassium(for: date))
                    optionalNutritionRow(.transFat, value: foodStore.transFat(for: date))
                    optionalNutritionRow(.calcium, value: foodStore.calcium(for: date))
                    optionalNutritionRow(.iron, value: foodStore.iron(for: date))
                    optionalNutritionRow(.magnesium, value: foodStore.magnesium(for: date))
                    optionalNutritionRow(.zinc, value: foodStore.zinc(for: date))
                    optionalNutritionRow(.vitaminA, value: foodStore.vitaminA(for: date))
                    optionalNutritionRow(.vitaminC, value: foodStore.vitaminC(for: date))
                    optionalNutritionRow(.vitaminD, value: foodStore.vitaminD(for: date))
                    optionalNutritionRow(.vitaminB12, value: foodStore.vitaminB12(for: date))
                    optionalNutritionRow(.vitaminE, value: foodStore.vitaminE(for: date))
                    optionalNutritionRow(.vitaminK, value: foodStore.vitaminK(for: date))
                    optionalNutritionRow(.folate, value: foodStore.folate(for: date))
                    optionalNutritionRow(.omega3, value: foodStore.omega3(for: date))
                    ForEach(SupplementalNutrient.allCases) { nutrient in
                        optionalNutritionRow(
                            nutrient.optionalNutrient,
                            value: foodStore.supplementalNutrient(nutrient, for: date)
                        )
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Nutrition Details")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showHomeNutrientPicker) {
                HomeNutrientPickerSheet(
                    selectionRawValue: $homeTopNutrientsRaw,
                    waterTrackingEnabled: waterTrackingEnabled
                )
            }
            .onChange(of: homeTopNutrientsRaw) { _, _ in
                refreshWidgetSnapshot()
            }
            .onChange(of: optionalNutrientGoalsData) { _, _ in
                refreshWidgetSnapshot()
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .tint(AppColors.calorie)
                }
            }
        }
    }

    private func refreshWidgetSnapshot() {
        WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: userProfile)
    }

    private func formatMicro(_ value: Double) -> String {
        value == 0 ? "—" : String(format: "%.1f", value)
    }

    private func optionalNutritionRow(_ nutrient: OptionalNutrient, value: Double) -> some View {
        NutritionDetailRow(
            icon: nutrient.iconName,
            label: nutrient.displayName,
            value: formatMicro(value),
            unit: nutrient.unit,
            goal: "\(optionalNutrientGoals.goal(for: nutrient))"
        )
    }
}

struct NutritionDetailRow: View {
    var icon: String? = nil
    let label: String
    let value: String
    let unit: String
    var goal: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .frame(width: 24)
            }
            Text(LocalizedDisplayText.text(label))
                .font(.system(.body, design: .rounded))
            Spacer()
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .contentTransition(.numericText())
                Text(unit)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if let goal {
                Text("/ \(goal)")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.tertiary)
            }
        }
    }
}

struct NativeSheetToolbarButton: View {
    let title: LocalizedStringKey
    var isEmphasized = false
    var isDisabled = false
    let action: () -> Void

    var body: some View {
        button
    }

    private var button: some View {
        Button(action: action) {
            Text(title)
                .fixedSize()
                .foregroundStyle(AppColors.calorie)
        }
        .fontWeight(isEmphasized ? .semibold : .regular)
        .disabled(isDisabled)
    }
}
