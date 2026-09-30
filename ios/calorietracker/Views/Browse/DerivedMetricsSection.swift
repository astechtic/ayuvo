import SwiftUI

/// "Estimated by Ayuvo" rows of a Browse domain (docs/derived-metrics.md): derived metrics of `categories` that
/// are switched on and have at least one value. Nothing is shown otherwise.
struct DerivedMetricsSection: View {
    let categories: [String]
    @Environment(HealthDataStore.self) private var healthStore

    private var store: DerivedMetricStore { DerivedMetricStore.shared }

    /// Derived categories listed under a Browse health category.
    static func categories(for category: HealthCategory) -> [String] {
        switch category {
        case .heart: ["heart"]
        case .sleep: ["sleep"]
        case .mobility: ["mobility"]
        case .hearing: ["hearing"]
        case .activity: ["activity", "energy"]
        case .body: ["body"]
        default: []
        }
    }

    var body: some View {
        let infos = store.infos(inCategories: categories)
        Group {
            if !infos.isEmpty {
                Section {
                    ForEach(infos) { info in
                        let key = MetricKey.derived(info.id)
                        let descriptor = MetricCatalog.descriptor(for: key)
                        NavigationLink(value: MetricRoute.detail(key)) {
                            MetricRow(systemImage: descriptor.systemImage, tint: descriptor.tint, title: descriptor.title)
                        }
                        .accessibilityIdentifier("browse.metric.\(key.id)")
                    }
                } header: {
                    Text("Estimated by Ayuvo")
                } footer: {
                    Text("Calculated on this iPhone from your Apple Health data. Apple Health's own value is shown instead whenever it has one.")
                }
            }
        }
        .task(id: DerivedMetricsService.shared.revision) {
            await store.refresh(pinned: healthStore.pinnedTypeIDs, calendar: healthStore.calendar)
        }
    }
}
