import SwiftUI

/// Tracking › Insights: the Insights switch, the opt-in morning Recovery notification and the Daily Review time
/// (the same `dailySummary*` keys the Notifications screen uses).
extension SettingsPaneView {
    @ViewBuilder
    var insightsPane: some View {
        InsightsSettingsSection()
    }
}

struct InsightsSettingsSection: View {
    @Environment(NotificationManager.self) private var notificationManager
    @AppStorage(InsightsSettings.enabledKey) private var insightsEnabled = true
    @AppStorage(InsightsSettings.morningRecoveryKey) private var morningRecovery = false
    @AppStorage("dailySummaryEnabled") private var reviewEnabled = true
    @AppStorage("dailySummaryHour") private var reviewHour = 20
    @AppStorage("dailySummaryMinute") private var reviewMinute = 0
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false

    var body: some View {
        Section {
            Toggle(isOn: $insightsEnabled) {
                Label {
                    Text("Insights")
                } icon: {
                    SettingsIcon("gauge.with.dots.needle.67percent", tint: SettingsTint.insights)
                }
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.insights.enabled")
            .onChange(of: insightsEnabled) { _, isOn in
                InsightsStore.shared.invalidate()
                InsightsBackgroundRefresh.schedule()
                if isOn {
                    InsightsBackgroundRefresh.startSleepObserver()
                } else {
                    InsightsBackgroundRefresh.stopSleepObserver()
                }
                Task { await InsightsStore.shared.refresh(force: true) }
            }
        } footer: {
            Text(healthKitEnabled
                 ? "Recovery, Health Age, the Daily Review and Patterns, calculated from your own synced data each time you open them."
                 : "Insights use Apple Health. Turn on Health Sync to see Recovery, Health Age and Patterns.")
        }
        .listRowBackground(AppColors.appCard)

        if insightsEnabled {
            Section {
                Toggle(isOn: $morningRecovery) {
                    Label {
                        Text("Morning Recovery")
                    } icon: {
                        SettingsIcon("sunrise.fill", tint: SettingsTint.insights)
                    }
                }
                .tint(AppColors.calorie)
                .accessibilityIdentifier("settings.insights.morningRecovery")
                .onChange(of: morningRecovery) { _, isOn in
                    if isOn {
                        Task { _ = await notificationManager.requestAuthorization() }
                        InsightsBackgroundRefresh.schedule()
                    }
                }

                NotificationTimeRow(
                    label: "Daily Review",
                    icon: "checklist",
                    tint: SettingsTint.insights,
                    isEnabled: $reviewEnabled,
                    hour: $reviewHour,
                    minute: $reviewMinute
                )
                .accessibilityIdentifier("settings.insights.reviewTime")
                .onChange(of: reviewEnabled) { _, _ in rescheduleReview() }
                .onChange(of: reviewHour) { _, _ in rescheduleReview() }
                .onChange(of: reviewMinute) { _, _ in rescheduleReview() }
            } header: {
                Text("Notifications")
            } footer: {
                Text("Notifications only say that your recovery or review is ready; they never show your values. Your phone decides when the morning refresh runs, so Recovery may appear later than you expect.")
            }
            .listRowBackground(AppColors.appCard)
        }
    }

    private func rescheduleReview() {
        guard notificationsEnabled else { return }
        notificationManager.scheduleDailySummary(enabled: reviewEnabled, hour: reviewHour, minute: reviewMinute)
    }
}
