import SwiftUI

/// Tracking › Nutrition, Hydration and Fasting.
extension SettingsPaneView {
    @ViewBuilder
    var nutritionTrackingPane: some View {
        Section {
            NavigationLink {
                MealTimeSettingsView()
            } label: {
                Label {
                    HStack {
                        Text("Meal Times")
                        Spacer()
                        Text("Customize")
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    SettingsIcon("clock.fill", tint: SettingsTint.time)
                }
            }

            HStack {
                Label {
                    HStack(spacing: 6) {
                        Text("Default to Grams")
                        Button {
                            showDefaultGramsInfo = true
                        } label: {
                            Image(systemName: "info.circle")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("About Default to Grams")
                    }
                } icon: {
                    SettingsIcon("scalemass.fill", tint: SettingsTint.nutrition)
                }
                Spacer()
                Toggle("Default to Grams", isOn: $preferGramsByDefault)
                    .labelsHidden()
                    .tint(AppColors.calorie)
            }

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Label {
                        Text("Save to Photos")
                    } icon: {
                        SettingsIcon("photo.on.rectangle", tint: SettingsTint.photos)
                    }
                    Spacer()
                    Toggle("Save to Photos", isOn: $saveMealPhotosToGallery)
                        .labelsHidden()
                        .tint(AppColors.calorie)
                }
                Text("Also save meal photos to your gallery when logging")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 41)
            }
        }
        .listRowBackground(AppColors.appCard)

        Section {
            NavigationLink {
                QuickActionsSettingsView()
            } label: {
                Label {
                    HStack {
                        Text("Quick Actions")
                        Spacer()
                        Text("Customize")
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                } icon: {
                    SettingsIcon("bolt.fill", tint: SettingsTint.quickActions)
                }
            }

            NavigationLink {
                AddMenuSettingsView()
            } label: {
                Label {
                    HStack {
                        Text("+ Menu")
                        Spacer()
                        Text("Customize")
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                } icon: {
                    SettingsIcon("plus", tint: SettingsTint.nutrition)
                }
            }
        } footer: {
            Text("The + Menu also orders the entries in the Summary log sheet.")
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    var hydrationPane: some View {
        Section {
            HStack {
                Label {
                    Text("Water Tracking")
                } icon: {
                    SettingsIcon("drop.fill", tint: SettingsTint.hydration)
                }
                Spacer()
                Toggle("Water Tracking", isOn: $waterTrackingEnabled)
                    .labelsHidden()
                    .tint(AppColors.calorie)
                    .onChange(of: waterTrackingEnabled) { _, isEnabled in
                        if !isEnabled {
                            notificationManager.scheduleWaterReminder(enabled: false, hour: 14, minute: 0)
                            UserDefaults.standard.set(false, forKey: WaterSettings.reminderEnabledKey)
                        }
                        WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
                    }
            }

            if waterTrackingEnabled {
                Button {
                    showWaterGoalPicker = true
                } label: {
                    HStack {
                        Label {
                            Text("Daily Water Goal")
                        } icon: {
                            SettingsIcon("target", tint: SettingsTint.hydration)
                        }
                        .foregroundStyle(.primary)
                        Spacer()
                        Text(waterUnit.formatted(milliliters: waterDailyGoal))
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)
            }
        } footer: {
            Text("Water Tracking also shows the Drink ring on Summary. Change the water unit in Units.")
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    var fastingPane: some View {
        Section {
            HStack {
                Label {
                    Text("Fasting Tracking")
                } icon: {
                    SettingsIcon("timer", tint: SettingsTint.fasting)
                }
                Spacer()
                Toggle("Fasting Tracking", isOn: $fastingTrackingEnabled)
                    .labelsHidden()
                    .tint(AppColors.calorie)
                    .onChange(of: fastingTrackingEnabled) { _, isEnabled in
                        if !isEnabled {
                            // Disabling tracking must never discard an in-progress
                            // session. Complete it now so it remains in history and
                            // no longer blocks food logging while tracking is off.
                            _ = fastingStore.endActive()
                            notificationManager.cancelFastingGoal()
                        }
                    }
            }

            if fastingTrackingEnabled {
                Button {
                    showFastingGoalPicker = true
                } label: {
                    HStack {
                        Label {
                            Text("Default Fasting Goal")
                        } icon: {
                            SettingsIcon("target", tint: SettingsTint.fasting)
                        }
                        .foregroundStyle(.primary)
                        Spacer()
                        Text(FastingDurationFormatter.goal(minutes: fastingDefaultGoalMinutes))
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .listRowBackground(AppColors.appCard)
    }
}
