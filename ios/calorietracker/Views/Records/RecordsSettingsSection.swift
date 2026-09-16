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
            Toggle(isOn: Binding(get: { store.coachAccessEnabled }, set: { store.setCoachAccess($0) })) {
                Label {
                    Text("Let Coach use my health records")
                } icon: {
                    Image(systemName: "bubble.left.and.text.bubble.right")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .accessibilityIdentifier("records.settings.coachAccess")
        } header: {
            Text("Coach access")
        } footer: {
            Text(coachFooter)
        }
        .listRowBackground(AppColors.appCard)

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
            NavigationLink {
                RecordsStorageScreen()
            } label: {
                Label {
                    Text("Storage")
                } icon: {
                    Image(systemName: "externaldrive")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .accessibilityIdentifier("records.settings.storage")
            NavigationLink {
                RecordsBackupScreen()
            } label: {
                Label {
                    Text("Backup & restore")
                } icon: {
                    Image(systemName: "archivebox")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .accessibilityIdentifier("records.settings.backup")
        } footer: {
            Text("Create an ayuvo-records archive to keep a copy wherever you choose, and restore it later. Health Records are never in iCloud or iPhone backups. Delete All Data in Data Management removes every record and file.")
        }
        .font(.system(.body, design: .rounded))
        .listRowBackground(AppColors.appCard)
        .task(id: store.revision) {
            store.refreshAIEnvironment()
            if !store.hasLoadedOnce { await store.reload() }
            storageBytes = await store.storageBytes()
        }
    }

    private var coachFooter: String {
        let provider = CoachRecordsFormatting.coachProvider(override: nil)
        if provider.onDevice {
            return String(localized: "Coach reads the records on this device; nothing is sent online.")
        }
        return String(localized: "Coach sends the details of the records it reads — test results, dates, doctors and diagnoses — to \(provider.name) to answer you. Records stay stored on this device.")
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
