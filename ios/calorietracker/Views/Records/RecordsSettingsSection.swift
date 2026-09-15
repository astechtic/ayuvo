import SwiftUI

/// Settings › Health Records (Phase 1: the privacy explainer and storage used).
struct HealthRecordsSettingsSection: View {
    @Environment(RecordsStore.self) private var store
    @State private var storageBytes: Int64?

    var body: some View {
        Section {
            RecordsPrivacyExplainer()
                .padding(.vertical, 6)
        } header: {
            Text("How Ayuvo handles your health records")
        }
        .listRowBackground(AppColors.appCard)

        Section {
            HStack {
                Label {
                    Text("Records")
                } icon: {
                    Image(systemName: "list.clipboard")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Text("\(store.totalCount)")
                    .foregroundStyle(.secondary)
            }
            HStack {
                Label {
                    Text("Storage used")
                } icon: {
                    Image(systemName: "internaldrive")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Text(storageBytes.map { RecordFormatting.sizeText($0) } ?? "…")
                    .foregroundStyle(.secondary)
            }
        } footer: {
            Text("AI processing, backup archives and storage tools arrive in later updates. Delete All Data in Data Management removes every record and file.")
        }
        .font(.system(.body, design: .rounded))
        .listRowBackground(AppColors.appCard)
        .task(id: store.revision) {
            if !store.hasLoadedOnce { await store.reload() }
            storageBytes = await store.storageBytes()
        }
    }
}
