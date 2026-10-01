import SwiftUI

/// Data & Privacy › Google Health (docs/google-health.md §5): connect, status, Sync now, the
/// two toggles, Manage data types, Reconnect and Disconnect.
extension SettingsPaneView {
    @ViewBuilder
    var googleHealthPane: some View {
        if googleHealthStore.isConnected {
            googleHealthConnectedSections
        } else {
            googleHealthDisconnectedSection
        }
    }

    // MARK: - Disconnected

    @ViewBuilder
    private var googleHealthDisconnectedSection: some View {
        Section {
            Button {
                googleHealthSetupStep = 1
                showGoogleHealthSetup = true
            } label: {
                Label {
                    Text("Connect Google Health")
                        .foregroundStyle(.primary)
                } icon: {
                    SettingsIcon("link", tint: SettingsTint.privacy)
                }
            }
            .buttonStyle(.plain)
            .disabled(!googleHealthStore.isAvailable)
            .accessibilityIdentifier("settings.row.googleHealth.connect")
        } footer: {
            Text("Brings Fitbit and Pixel Watch data from your Google account into Ayuvo, and can copy it into Apple Health. Ayuvo talks to Google directly from this iPhone; there is no Ayuvo server, and the data stays on this iPhone.")
        }
        .listRowBackground(AppColors.appCard)
    }

    // MARK: - Connected

    @ViewBuilder
    private var googleHealthConnectedSections: some View {
        Section {
            HStack {
                Label {
                    Text("Account")
                } icon: {
                    SettingsIcon("person.crop.circle.fill", tint: SettingsTint.privacy)
                }
                Spacer()
                Text(googleHealthStore.account?.email ?? String(localized: "Google account", comment: "Google Health account row when the email is unknown"))
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            HStack {
                Label {
                    Text("Last synced")
                } icon: {
                    SettingsIcon("clock.arrow.2.circlepath", tint: SettingsTint.other)
                }
                Spacer()
                Text(googleHealthStore.isSyncing
                    ? String(localized: "Syncing…")
                    : (googleHealthStore.lastSyncAt.map { HealthUnitFormatting.relativeText($0) } ?? String(localized: "Never")))
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            if googleHealthStore.isSyncing, let progress = googleHealthStore.progress {
                VStack(alignment: .leading, spacing: 6) {
                    ProgressView(value: progress.fraction)
                        .tint(AppColors.calorie)
                    Text(progress.mirroring
                        ? String(localized: "Writing to Apple Health… \(progress.mirrored)", comment: "Google Health progress; placeholder is the number of records written")
                        : String(localized: "\(progress.typesDone) of \(progress.typesTotal) data types", comment: "Google Health sync progress"))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }

            if googleHealthStore.needsReconnect {
                Button {
                    googleHealthSetupStep = 3
                    showGoogleHealthSetup = true
                } label: {
                    Label {
                        Text("Reconnect")
                            .foregroundStyle(.primary)
                    } icon: {
                        SettingsIcon("exclamationmark.arrow.circlepath", tint: SettingsTint.warning)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.row.googleHealth.reconnect")
            }

            Button {
                Task { await googleHealthStore.sync() }
            } label: {
                Label {
                    Text("Sync Now")
                } icon: {
                    SettingsIcon("arrow.triangle.2.circlepath", tint: SettingsTint.update)
                }
            }
            .buttonStyle(.plain)
            .disabled(googleHealthStore.isSyncing || googleHealthStore.needsReconnect)
            .accessibilityIdentifier("settings.row.googleHealth.syncNow")
        } footer: {
            if case .failed(let message)? = googleHealthStore.lastOutcome {
                Text(message)
            }
        }
        .listRowBackground(AppColors.appCard)

        Section {
            ForEach(googleHealthStore.map?.scopeGroups ?? []) { group in
                HStack {
                    Text(GoogleHealthSetupView.groupTitle(group))
                    Spacer()
                    googleHealthGroupStatus(group.id)
                }
            }
        } header: {
            Text("Data types")
        }
        .listRowBackground(AppColors.appCard)

        Section {
            Toggle(isOn: Binding(
                get: { googleHealthStore.autoSyncEnabled },
                set: { googleHealthStore.setAutoSync($0) }
            )) {
                Label {
                    Text("Auto-sync on app open")
                } icon: {
                    SettingsIcon("arrow.clockwise", tint: SettingsTint.update)
                }
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.row.googleHealth.autoSync")

            Toggle(isOn: Binding(
                get: { googleHealthStore.writeBackEnabled },
                set: { googleHealthStore.setWriteBack($0) }
            )) {
                Label {
                    Text("Write to Apple Health")
                } icon: {
                    SettingsIcon("heart.fill", tint: SettingsTint.vitals)
                }
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.row.googleHealth.writeBack")

            Button {
                googleHealthSetupStep = 2
                showGoogleHealthSetup = true
            } label: {
                Label {
                    Text("Manage Data Types")
                        .foregroundStyle(.primary)
                } icon: {
                    SettingsIcon("checklist", tint: SettingsTint.privacy)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.row.googleHealth.manage")

            Button(role: .destructive) {
                showGoogleHealthDisconnect = true
            } label: {
                Label {
                    Text("Disconnect")
                        .foregroundStyle(.red)
                } icon: {
                    SettingsIcon("xmark.circle.fill", tint: SettingsTint.destructive)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.row.googleHealth.disconnect")
        } footer: {
            Text("Auto-sync runs at most every 6 hours when you open Ayuvo. Write to Apple Health copies the types Apple Health supports, tagged so they are never read back twice; Google-only types such as Active Zone Minutes and ECG stay in Ayuvo. \(googleHealthStore.rowCount) Google records are stored on this iPhone, \(googleHealthStore.mirroredCount) written to Apple Health.")
        }
        .listRowBackground(AppColors.appCard)
        .task { await googleHealthStore.refreshStatus() }
    }

    @ViewBuilder
    private func googleHealthGroupStatus(_ groupID: String) -> some View {
        let status = googleHealthStore.status(ofGroup: groupID)
        Group {
            switch status {
            case .notGranted:
                Text("Not allowed", comment: "Google Health data group whose scope was not granted")
                    .foregroundStyle(.secondary)
            case .waiting:
                Text("Waiting for sync", comment: "Google Health data group not synced yet")
                    .foregroundStyle(.secondary)
            case .syncing:
                ProgressView().controlSize(.small)
            case .synced(let date):
                Text(date.map { HealthUnitFormatting.relativeText($0) } ?? String(localized: "Synced", comment: "Google Health data group status"))
                    .foregroundStyle(.secondary)
            case .partial:
                Text("No data in this account", comment: "Google Health data group whose types are unsupported for the account")
                    .foregroundStyle(.secondary)
            case .error(let message):
                Text("Error", comment: "Google Health data group status")
                    .foregroundStyle(.orange)
                    .accessibilityHint(Text(message))
            }
        }
        .font(.system(.subheadline, design: .rounded))
    }
}
