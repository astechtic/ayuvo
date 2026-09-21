import SwiftUI

/// Chooses and orders the Summary favourites (`summaryFavourites`, cloud-backed, max 12).
struct FavouritesEditorView: View {
    @Environment(HealthDataStore.self) private var store

    private var pinned: [String] { store.pinnedTypeIDs }

    private var healthCandidates: [HealthMetricType] {
        store.typeSummaries.filter { $0.count > 0 }
            .map { store.metricType(for: $0.typeID) }
            .filter { !store.isPinned($0.id) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    private var appCandidates: [MetricDescriptor] {
        MetricCatalog.appMetrics.filter { !store.isPinned($0.key.id) }
    }

    private var isFull: Bool { pinned.count >= MetricPins.max }

    var body: some View {
        List {
            Section {
                if pinned.isEmpty {
                    Text("No favourites yet. Add metrics below.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(pinned, id: \.self) { id in
                    row(for: id)
                }
                .onMove { from, to in
                    var ids = pinned
                    ids.move(fromOffsets: from, toOffset: to)
                    store.setPinnedTypeIDs(ids)
                }
                .onDelete { offsets in
                    var ids = pinned
                    ids.remove(atOffsets: offsets)
                    store.setPinnedTypeIDs(ids)
                }
            } header: {
                Text("Favourites")
            } footer: {
                Text("Up to \(MetricPins.max) metrics appear on Summary in this order.")
            }

            if !appCandidates.isEmpty {
                Section("Logged in Ayuvo") {
                    ForEach(appCandidates) { descriptor in
                        addButton(id: descriptor.key.id, descriptor: descriptor)
                    }
                }
            }

            Section("Apple Health") {
                if healthCandidates.isEmpty {
                    Text("Data types appear here once they have records.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(healthCandidates) { type in
                    addButton(id: type.id, descriptor: MetricCatalog.descriptor(for: .health(type.id)))
                }
            }
        }
        .listStyle(.insetGrouped)
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Edit Favourites")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(for id: String) -> some View {
        let descriptor = MetricKey(pinID: id).map { MetricCatalog.descriptor(for: $0) }
        return MetricRow(
            systemImage: descriptor?.systemImage ?? "questionmark",
            tint: descriptor?.tint ?? AyuvoPalette.other,
            title: descriptor?.title ?? id
        )
    }

    private func addButton(id: String, descriptor: MetricDescriptor) -> some View {
        Button {
            store.togglePin(id)
        } label: {
            HStack {
                MetricRow(systemImage: descriptor.systemImage, tint: descriptor.tint, title: descriptor.title)
                Image(systemName: "plus.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityHidden(true)
            }
        }
        .buttonStyle(.plain)
        .disabled(isFull)
        .accessibilityLabel(Text("Add \(descriptor.title)"))
    }
}
