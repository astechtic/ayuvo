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
    @Environment(MedicationStore.self) private var medicationStore
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

                Section {
                    let totals = NutrientTotals(foodStore: foodStore, medicationStore: medicationStore).totals(NutrientCatalog.allDetailKeys, on: date)
                    let keys = NutrientCatalog.detailRowKeys(activeNutrientKeys: medicationStore.activeNutrientKeys, totals: totals)
                    ForEach(keys, id: \.self) { key in
                        NavigationLink(value: MetricRoute.detail(.nutrient(key))) {
                            nutrientRow(key, total: totals[key] ?? .empty)
                        }
                        .accessibilityIdentifier("nutritionDetail.row.\(key)")
                    }
                } header: {
                    Text("Detailed Nutrition")
                } footer: {
                    Text("Includes supplement doses you marked as taken. Tap a nutrient for its chart.")
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Nutrition Details")
            .navigationBarTitleDisplayMode(.inline)
            .metricRouteDestinations()
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

    /// Food + supplements for the day; "—" when nothing recorded it (never 0 for missing data).
    private func nutrientRow(_ key: String, total: NutrientsReference.DayTotal) -> some View {
        let goal = NutrientCatalog.detailGoal(key, profile: userProfile, goals: optionalNutrientGoals)
            .flatMap { $0 > 0 ? "\($0)" : nil }
        // The food log does not record app_tracked: false nutrients: the whole value is supplements.
        let subline = !NutrientCatalog.foodTracked(key)
            ? String(localized: "Supplements only")
            : total.supplements.flatMap { $0 > 0 ? String(localized: "incl. \(NutrientCatalog.text($0, key: key)) from supplements") : nil }
        return NutritionDetailRow(
            icon: NutrientCatalog.iconName(key),
            label: NutrientCatalog.rowTitle(key),
            value: NutrientCatalog.number(total.total),
            unit: NutrientCatalog.unit(key),
            goal: goal,
            subline: subline
        )
    }
}

struct NutritionDetailRow: View {
    var icon: String? = nil
    let label: String
    let value: String
    let unit: String
    var goal: String? = nil
    /// Secondary line under the label, e.g. "incl. 1,500 mcg from supplements".
    var subline: String? = nil

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
            VStack(alignment: .leading, spacing: 2) {
                Text(LocalizedDisplayText.text(label))
                    .font(.system(.body, design: .rounded))
                if let subline {
                    Text(subline)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
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
