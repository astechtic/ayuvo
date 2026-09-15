import SwiftUI

/// Settings › Health Records: AI processing, the privacy explainer and storage used.
struct HealthRecordsSettingsSection: View {
    @Environment(RecordsStore.self) private var store
    @State private var storageBytes: Int64?

    var body: some View {
        Section {
            ForEach(RecordsAIMode.allCases) { mode in
                RecordsAIModeOptionRow(
                    mode: mode,
                    isSelected: store.aiMode == mode,
                    note: RecordsAIModeNotes.note(for: mode, environment: store.aiEnvironment)
                ) {
                    store.setAIMode(mode)
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("AI processing")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(aiFooter)
                if store.aiMode == nil {
                    Text("Not chosen yet: Ayuvo asks before using AI on a record.")
                }
            }
        }
        .listRowBackground(AppColors.appCard)
        .onAppear { store.refreshAIEnvironment() }

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
            Text("Backup archives and storage tools arrive in later updates. Delete All Data in Data Management removes every record and file.")
        }
        .font(.system(.body, design: .rounded))
        .listRowBackground(AppColors.appCard)
        .task(id: store.revision) {
            store.refreshAIEnvironment()
            if !store.hasLoadedOnce { await store.reload() }
            storageBytes = await store.storageBytes()
        }
    }

    private var aiFooter: String {
        let environment = store.aiEnvironment
        var parts: [String] = []
        parts.append(environment.localAvailable
            ? String(localized: "On-device AI is available on this iPhone.")
            : String(localized: "On-device AI isn't set up. Apple Intelligence or the Gemma 4 model in Settings › AI Providers enables it."))
        if let provider = environment.cloudProviderName {
            parts.append(String(localized: "Online AI uses \(provider). Page text is sent only when details are missing; images only for pages without text."))
        } else {
            parts.append(String(localized: "Add an AI provider to use online AI."))
        }
        return parts.joined(separator: " ")
    }
}
