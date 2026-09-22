import SwiftUI

/// Data & Privacy › Health Records, Backup & Export and Delete All Data.
extension SettingsPaneView {
    @ViewBuilder
    var healthRecordsPane: some View {
        HealthRecordsSettingsSection()
    }

    @ViewBuilder
    var backupExportPane: some View {
        CloudBackupSettingsSection()

        Section {
            // One zip with every export below plus medications, Health Records and settings.
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
        } footer: {
            Text("One zip with your food diary, health data, medications, Health Records and settings. Saved only where you choose.")
        }
        .listRowBackground(AppColors.appCard)

        Section {
            // Export Food Diary
            Button {
                showExportDiary = true
            } label: {
                Label {
                    Text("Export Food Diary")
                } icon: {
                    SettingsIcon("square.and.arrow.up", tint: SettingsTint.nutrition)
                }
            }
            .buttonStyle(.plain)

            Button {
                showImportDiary = true
            } label: {
                Label {
                    Text("Import Food Diary")
                } icon: {
                    SettingsIcon("square.and.arrow.down", tint: SettingsTint.nutrition)
                }
            }
            .buttonStyle(.plain)

            // Health Data export / import (ayuvo-health-data zip, cross-platform)
            Button {
                showExportHealthData = true
            } label: {
                Label {
                    Text("Export Health Data")
                } icon: {
                    SettingsIcon("square.and.arrow.up", tint: SettingsTint.vitals)
                }
            }
            .buttonStyle(.plain)

            Button {
                showImportHealthData = true
            } label: {
                Label {
                    Text("Import Health Data")
                } icon: {
                    SettingsIcon("square.and.arrow.down", tint: SettingsTint.vitals)
                }
            }
            .buttonStyle(.plain)
        } header: {
            Text("Export & Import")
        } footer: {
            Text("Health Records have their own archive in Health Records › Backup & restore. Medications export from Browse › Medications.")
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
