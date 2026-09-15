import SwiftUI

/// All data types of one category, data-first, with a "nothing shared yet" state.
struct HealthCategoryView: View {
    let category: HealthCategory
    @Environment(HealthDataStore.self) private var store

    private var types: [HealthMetricType] { store.types(in: category) }
    private var withData: [HealthMetricType] { types.filter { (store.summary(for: $0.id)?.count ?? 0) > 0 } }
    private var withoutData: [HealthMetricType] { types.filter { (store.summary(for: $0.id)?.count ?? 0) == 0 } }

    var body: some View {
        List {
            if withData.isEmpty {
                Section {
                    ContentUnavailableView {
                        Label("Nothing shared yet", systemImage: category.systemImage)
                    } description: {
                        Text(store.isEnabled
                            ? "Data appears here after your iPhone or watch writes it to Apple Health and Ayuvo is allowed to read it (Health › Sharing › Apps › Ayuvo)."
                            : "Turn on Apple Health in Settings › Health & Data to sync this category.")
                    }
                    .listRowBackground(Color.clear)
                }
            } else {
                Section {
                    ForEach(withData) { type in
                        NavigationLink(value: HealthRoute.metric(type.id)) {
                            HealthMetricRow(type: type, summary: store.summary(for: type.id))
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }

            if !withoutData.isEmpty {
                Section {
                    ForEach(withoutData) { type in
                        NavigationLink(value: HealthRoute.metric(type.id)) {
                            HealthMetricRow(type: type, summary: nil)
                        }
                        .opacity(0.6)
                    }
                } header: {
                    Text("No data yet")
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle(category.displayName)
        .navigationBarTitleDisplayMode(.large)
    }
}
