import SwiftUI

/// Data & Privacy › Health Records, Backup & Export and Delete All Data.
extension SettingsPaneView {
    @ViewBuilder
    var healthRecordsPane: some View {
        HealthRecordsSettingsSection()
    }

    @ViewBuilder
    var backupExportPane: some View {
        Section {
            Button {
                showExportAllData = true
            } label: {
                Label {
                    Text("Export All Data")
                } icon: {
                    SettingsIcon("square.and.arrow.up.on.square.fill", tint: SettingsTint.export)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.row.exportAllData")

            Button {
                showImportAllData = true
            } label: {
                Label {
                    Text("Import All Data")
                } icon: {
                    SettingsIcon("square.and.arrow.down.on.square.fill", tint: SettingsTint.importData)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.row.importAllData")
        } footer: {
            Text("One zip with your food diary, health data, medications, Health Records and settings, saved only where you choose. Import it again on this or another phone.")
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    var deleteDataPane: some View {
        Section {
            Button(role: .destructive) {
                showClearHealthDataConfirmation = true
            } label: {
                Label {
                    Text("Clear synced health data")
                } icon: {
                    SettingsIcon("trash.fill", tint: SettingsTint.destructive)
                }
                .foregroundStyle(.red)
            }
            .buttonStyle(.plain)
            .disabled(!healthDataStore.hasAnyData && healthDataStore.databaseSizeBytes == 0)
        } footer: {
            Text("Removes the local Apple Health mirror. Your data in the Health app is untouched.")
        }
        .listRowBackground(AppColors.appCard)

        Section {
            // Clear Food Log
            Button(role: .destructive) {
                showClearFoodLogConfirmation = true
            } label: {
                Label {
                    Text("Clear Food Log")
                } icon: {
                    SettingsIcon("fork.knife", tint: SettingsTint.warning)
                }
                .foregroundStyle(.orange)
            }
            .buttonStyle(.plain)

            // Delete All Data — always visible
            Button(role: .destructive) {
                showDeleteConfirmation = true
            } label: {
                Label {
                    Text("Delete All Data")
                } icon: {
                    SettingsIcon("trash.fill", tint: SettingsTint.destructive)
                }
                .foregroundStyle(.red)
            }
            .buttonStyle(.plain)
        } footer: {
            Text("Delete All Data removes food logs, weight, workouts, health records, medications, the Apple Health mirror and your profile from this iPhone.")
        }
        .listRowBackground(AppColors.appCard)
    }
}
