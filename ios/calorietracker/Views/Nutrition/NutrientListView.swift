import SwiftUI

/// Browse › Nutrition › All Nutrients: every nutrient metric grouped by its catalog browse section
/// (Carbohydrates, Fats, Minerals, Vitamins, Other Nutrients, Sports Supplements), with today's food +
/// supplement total. Hidden entries (`browse_hidden`, e.g. fiber, which sits under Macronutrients) are left
/// out. Every row opens its chart (docs/ui-structure.md §4).
struct NutrientListView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(ProfileStore.self) private var profileStore
    @AppStorage(OptionalNutrientGoals.storageKey) private var optionalNutrientGoalsData = Data()

    private struct Group: Identifiable {
        let id: String
        let title: String
        let keys: [String]
    }

    private var groups: [Group] {
        let catalog = MetricCatalogData.shared
        let visible = catalog.nutrientMetrics.filter { !$0.browseHidden }
        return catalog.browseSections
            .filter { $0.domain == "nutrition" }
            .sorted { $0.order < $1.order }
            .compactMap { section in
                let keys = visible.filter { $0.browseSection == section.id }.sorted { $0.browseOrder < $1.browseOrder }.map(\.key)
                return keys.isEmpty ? nil : Group(id: section.id, title: String(localized: String.LocalizationValue(section.title)), keys: keys)
            }
    }

    var body: some View {
        let totals = NutrientTotals(foodStore: foodStore, medicationStore: medicationStore)
            .totals(MetricCatalogData.shared.nutrientMetrics.map(\.key), on: Date())
        let goals = OptionalNutrientGoals.decoded(from: optionalNutrientGoalsData)
        let profile = NutrientCatalog.profile(profileStore.profile)
        List {
            ForEach(groups) { group in
                Section(group.title) {
                    ForEach(group.keys, id: \.self) { key in
                        NavigationLink(value: MetricRoute.detail(.nutrient(key))) {
                            row(key, total: totals[key] ?? .empty, goal: NutrientCatalog.optionalNutrient(key).map { goals.goal(for: $0, profile: profile) })
                        }
                        .accessibilityIdentifier("nutrients.row.\(key)")
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("All Nutrients")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("nutrients.list")
    }

    private func row(_ key: String, total: NutrientsReference.DayTotal, goal: Int?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: NutrientCatalog.iconName(key))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AyuvoPalette.nutrition)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(NutrientCatalog.title(key))
                    .font(.system(.body, design: .rounded))
                if let supplements = total.supplements, supplements > 0 {
                    Text(String(localized: "incl. \(NutrientCatalog.text(supplements, key: key)) from supplements"))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(NutrientCatalog.text(total.total, key: key))
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(total.total == nil ? .secondary : .primary)
                if let goal, goal > 0 {
                    Text("/ \(goal) \(NutrientCatalog.unit(key))")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
