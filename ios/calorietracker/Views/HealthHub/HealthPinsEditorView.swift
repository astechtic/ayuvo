import SwiftUI

/// Chooses which data types appear as Home tiles (`healthHomeTiles`, cloud-backed).
struct HealthPinsEditorView: View {
    @Environment(HealthDataStore.self) private var store

    private var candidates: [HealthMetricType] {
        let withData = store.typeSummaries.filter { $0.count > 0 }.map { store.metricType(for: $0.typeID) }
        let pinnedMissing = store.pinnedTypeIDs.filter { id in !withData.contains { $0.id == id } }.map { store.metricType(for: $0) }
        return (pinnedMissing + withData).sorted { lhs, rhs in
            let l = store.isPinned(lhs.id), r = store.isPinned(rhs.id)
            if l != r { return l }
            return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
        }
    }

    var body: some View {
        List {
            Section {
                Toggle(isOn: Binding(
                    get: { store.showsHomeTile },
                    set: { store.setHomeTilesVisible($0) }
                )) {
                    Label("Show Health tiles on Home", systemImage: "house")
                }
                .tint(AppColors.calorie)
            } footer: {
                Text("When hidden, Home shows the daily steps line instead.")
                    .font(.system(.caption2, design: .rounded))
            }
            .listRowBackground(AppColors.appCard)

            Section {
                if candidates.isEmpty {
                    Text("Data types appear here once they have records.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(candidates) { type in
                    Toggle(isOn: Binding(
                        get: { store.isPinned(type.id) },
                        set: { _ in store.togglePin(type.id) }
                    )) {
                        HStack(spacing: 10) {
                            HealthIconBubble(systemImage: type.category.systemImage, tint: type.category.tint)
                            Text(type.displayName)
                                .font(.system(.body, design: .rounded))
                        }
                    }
                    .tint(AppColors.calorie)
                }
            } header: {
                Text("Home Tiles")
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Edit Home Tiles")
        .navigationBarTitleDisplayMode(.inline)
    }
}
