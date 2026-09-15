import SwiftUI

/// "Data Sources & Access": apps / devices contributing rows to one type.
struct HealthDataSourcesView: View {
    let typeID: String
    @Environment(HealthDataStore.self) private var store
    @State private var usages: [HealthDatabase.SourceUsage] = []
    @State private var loaded = false

    private var type: HealthMetricType { store.metricType(for: typeID) }

    var body: some View {
        List {
            if loaded, usages.isEmpty {
                ContentUnavailableView {
                    Label("No sources yet", systemImage: "square.stack.3d.up")
                } description: {
                    Text("Sources appear once records for this data type have been mirrored.")
                }
                .listRowBackground(Color.clear)
            }
            Section {
                ForEach(usages) { usage in
                    HStack(spacing: 12) {
                        HealthIconBubble(
                            systemImage: usage.source.id == Bundle.main.bundleIdentifier ? "fork.knife" : (usage.source.deviceModel?.localizedCaseInsensitiveContains("watch") == true ? "applewatch" : "app.badge"),
                            tint: type.category.tint
                        )
                        VStack(alignment: .leading, spacing: 2) {
                            Text(usage.source.id == Bundle.main.bundleIdentifier ? "Ayuvo" : usage.source.name)
                                .font(.system(.body, design: .rounded, weight: .medium))
                            if let model = usage.source.deviceModel {
                                Text(model)
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                            Text(usage.lastMs.map {
                                String(localized: "\(usage.rowCount) records · last \(HealthUnitFormatting.relativeText(Date(timeIntervalSince1970: Double($0) / 1000)))")
                            } ?? String(localized: "\(usage.rowCount) records"))
                                .font(.system(.caption2, design: .rounded))
                                .foregroundStyle(.tertiary)
                        }
                        Spacer()
                    }
                }
            } footer: {
                Text("Manage what Ayuvo can read in Health › Sharing › Apps › Ayuvo. Records stay read-only in Ayuvo.")
                    .font(.system(.caption2, design: .rounded))
            }
            .listRowBackground(AppColors.appCard)

            Section {
                OpenHealthAppButton()
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Data Sources & Access")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            usages = await store.sources(typeID: typeID)
            loaded = true
        }
    }
}

/// "Manage Apple Health access": opens the Health app (Sharing › Apps › Ayuvo lives there);
/// falls back to this app's page in Settings when the Health URL scheme cannot open.
struct OpenHealthAppButton: View {
    var title: LocalizedStringKey = "Manage Apple Health Access"
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            open()
        } label: {
            Label(title, systemImage: "heart.text.square")
                .font(.system(.body, design: .rounded, weight: .medium))
                .foregroundStyle(AppColors.calorie)
        }
        .buttonStyle(.plain)
    }

    private func open() {
        let candidates = [URL(string: "x-apple-health://"), URL(string: UIApplication.openSettingsURLString)].compactMap { $0 }
        for url in candidates where UIApplication.shared.canOpenURL(url) {
            openURL(url)
            return
        }
        if let settings = candidates.last {
            openURL(settings)
        }
    }
}
