import SwiftUI
import HealthKit

/// Data & Privacy › Health Sync: Apple Health connection, sync status, Coach access and storage.
extension SettingsPaneView {
    @ViewBuilder
    var healthSyncPane: some View {
        Section {
            // Apple Health
            HStack {
                Label {
                    Text("Apple Health")
                } icon: {
                    SettingsIcon("heart.fill", tint: SettingsTint.vitals)
                }
                Spacer()
                Toggle("", isOn: $healthKitEnabled)
                    .labelsHidden()
                    .onChange(of: healthKitEnabled) { _, enabled in
                        handleHealthKitToggle(enabled)
                    }
            }

            // Browse replaces the old Health Data hub.
            Button {
                navigator.resetBrowse()
                navigator.selectedTab = .browse
            } label: {
                Label {
                    Text("Browse Health Data")
                        .foregroundStyle(.primary)
                } icon: {
                    SettingsIcon("heart.text.square.fill", tint: SettingsTint.vitals)
                }
            }

            HStack {
                Label {
                    Text("Last synced")
                } icon: {
                    SettingsIcon("clock.arrow.2.circlepath", tint: SettingsTint.other)
                }
                Spacer()
                Text(healthDataStore.isSyncing
                    ? String(localized: "Syncing…")
                    : (healthDataStore.lastSyncAt.map { HealthUnitFormatting.relativeText($0) } ?? String(localized: "Never")))
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            Button {
                Task { _ = await healthDataStore.sync(.manual) }
            } label: {
                Label {
                    Text("Sync Now")
                } icon: {
                    SettingsIcon("arrow.triangle.2.circlepath", tint: SettingsTint.update)
                }
            }
            .buttonStyle(.plain)
            .disabled(!healthKitEnabled || healthDataStore.isSyncing)

            // Summary Favourites replaced the old "Health tiles on Home" toggle.
            NavigationLink(value: MetricRoute.favourites) {
                Label {
                    Text("Edit Favourites")
                } icon: {
                    SettingsIcon("star.fill", tint: SettingsTint.favourite)
                }
            }

            Toggle(isOn: Binding(
                get: { healthDataStore.coachHealthDataEnabled },
                set: { healthDataStore.setCoachAccess($0) }
            )) {
                Label {
                    Text("Let Coach use Health data")
                } icon: {
                    SettingsIcon("bubble.left.and.text.bubble.right.fill", tint: SettingsTint.ai)
                }
            }
            .tint(AppColors.calorie)
            .disabled(!healthKitEnabled)

            Button {
                Task { await healthDataStore.rebuildRollups() }
            } label: {
                Label {
                    Text("Rebuild Summaries")
                } icon: {
                    SettingsIcon("wand.and.stars", tint: SettingsTint.storage)
                }
            }
            .buttonStyle(.plain)
            .disabled(!healthDataStore.hasAnyData)

            HStack {
                Label {
                    Text("Storage used")
                } icon: {
                    SettingsIcon("internaldrive.fill", tint: SettingsTint.storage)
                }
                Spacer()
                Text(HealthUnitFormatting.byteCountText(healthDataStore.databaseSizeBytes))
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }

        } footer: {
            Text("Reads every Apple Health category you allow into a local database on this iPhone for Browse, and keeps weight, nutrition, energy and workouts in sync. The mirror is never stored in iCloud backup and leaves the device only when Coach answers a question with it. Turning Apple Health off keeps the mirrored history read-only until you clear it.")
        }
        .listRowBackground(AppColors.appCard)
    }

    func handleHealthKitToggle(_ enabled: Bool) {
        if enabled {
            Task {
                let authorized = await healthKitManager.requestAuthorization()
                if authorized {
                    healthKitManager.writeWeight(kg: profile.weightKg, date: .now)
                    healthKitManager.writeHeight(cm: profile.heightCm)
                    if let bf = profile.bodyFatPercentage {
                        healthKitManager.writeBodyFat(fraction: bf)
                    }
                    let measurements = await healthKitManager.fetchLatestBodyMeasurements()
                    if let kg = measurements.weight, abs(profile.weightKg - kg) > 0.01 {
                        profile.weightKg = kg
                    }
                    if let cm = measurements.height, abs(profile.heightCm - cm) > 0.1 {
                        profile.heightCm = cm
                    }
                    if let bf = measurements.bodyFat {
                        profile.bodyFatPercentage = bf
                    }
                    if let dob = measurements.dob {
                        profile.birthday = dob
                    }
                    if let sex = measurements.sex {
                        switch sex {
                        case .male: profile.gender = .male
                        case .female: profile.gender = .female
                        default: break
                        }
                    }
                    saveProfile()
                    healthKitManager.startBodyMeasurementObserver()
                    healthKitManager.backfillNutritionIfNeeded(
                        entries: foodStore.entries,
                        currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
                    )
                    healthKitManager.synchronizeWorkoutBurnsWithHealthKit(
                        existing: { strengthWorkoutStore.workoutBurnSessions },
                        mergeBatch: { sessions in
                            strengthWorkoutStore.importWorkoutBurnSessions(sessions)
                        }
                    )
                    healthKitManager.synchronizeImportedWorkoutsWithHealthKit { workouts, queryStart in
                        importedHealthWorkoutStore.synchronize(with: workouts, queryStart: queryStart)
                    }
                    // Health Data hub: start the anchored mirror sync for every granted type.
                    healthDataStore.authorizationDidChange()
                } else {
                    healthKitEnabled = false
                }
            }
        } else {
            healthKitManager.stopObserver()
            // Rows stay (read-only) until "Clear synced health data" / Delete All Data.
            healthDataStore.disableSync()
        }
    }
}
