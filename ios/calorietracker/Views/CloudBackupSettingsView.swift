import SwiftUI

struct CloudBackupSettingsSection: View {
    @Environment(CloudBackupService.self) private var backup
    @State private var showEnableConfirm = false
    @State private var showRestoreChoice = false
    @State private var showDeleteConfirm = false
    @State private var errorMessage: String?

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { backup.enabled },
                set: { on in
                    if on { showEnableConfirm = true }
                    else { backup.enabled = false }
                }
            )) {
                SettingsLabel("iCloud Backup", systemImage: "icloud.fill", tint: SettingsTint.backup)
            }
            .accessibilityIdentifier("settings.cloudBackup.toggle")
            .disabled(backup.busy)

            if let last = backup.lastAt {
                (Text("Last backup: ") + Text(shortDate(last)))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("settings.cloudBackup.lastBackup")
            }

            if backup.enabled {
                Button { Task { await run { try await backup.backupNow() } } } label: {
                    SettingsLabel("Back up now", systemImage: "icloud.and.arrow.up.fill", tint: SettingsTint.backup)
                }
                .accessibilityIdentifier("settings.cloudBackup.backupNow")
                .disabled(backup.busy)
                Button { Task { await run { try await backup.restoreNow() } } } label: {
                    SettingsLabel("Restore now", systemImage: "icloud.and.arrow.down.fill", tint: SettingsTint.importData)
                }
                .accessibilityIdentifier("settings.cloudBackup.restoreNow")
                .disabled(backup.busy)
                Button(role: .destructive) { showDeleteConfirm = true } label: {
                    SettingsLabel("Delete cloud backup", systemImage: "trash.fill", tint: SettingsTint.destructive)
                }
                .accessibilityIdentifier("settings.cloudBackup.delete")
                .disabled(backup.busy)
            }
        } footer: {
            Text("Off until you turn it on. Uses the iCloud account on this iPhone — change Apple ID in iOS Settings if you need a different account. API keys stay on the device.")
        }
        .listRowBackground(AppColors.appCard)
        .alert("iCloud Backup", isPresented: $showEnableConfirm) {
            Button("Turn On") {
                Task { await turnOn() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Uploads your diary, workouts, fasting, photos, and settings to your iCloud. API keys stay on the phone.")
        }
        .alert("Restore backup?", isPresented: $showRestoreChoice) {
            Button("Restore") {
                Task { await run { try await backup.restoreNow() } }
            }
            Button("Keep this phone") {
                Task { await run { try await backup.backupNow() } }
            }
        } message: {
            Text("Restore it, or keep this phone.")
        }
        .alert("Delete cloud backup?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                Task { await run { try await backup.deleteCloudBackup() } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Removes the Ayuvo file from iCloud. This iPhone is unchanged.")
        }
        .alert("iCloud Backup", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func turnOn() async {
        do {
            try await backup.checkAccount()
            await backup.refreshCloudPresence()
            if backup.hasCloudBackup {
                showRestoreChoice = true
            } else {
                try await backup.backupNow()
            }
        } catch {
            backup.enabled = false
            errorMessage = error.localizedDescription
        }
    }

    private func run(_ work: () async throws -> Void) async {
        do {
            try await work()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func shortDate(_ iso: String) -> String {
        guard let date = ISO8601DateFormatter().date(from: iso) else { return iso }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
