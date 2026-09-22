import SwiftUI

/// Tracking › Medications: shortcut into Browse › Medications and its reminder settings.
extension SettingsPaneView {
    @ViewBuilder
    var medicationsPane: some View {
        Section {
            Button {
                navigator.openMedications()
            } label: {
                Label {
                    Text("Open Medications")
                        .foregroundStyle(.primary)
                } icon: {
                    SettingsIcon("pills.fill", tint: SettingsTint.medications)
                }
            }
            .accessibilityIdentifier("settings.row.openMedications")

            NavigationLink {
                NotificationSettingsView()
            } label: {
                Label {
                    Text("Dose Reminders")
                } icon: {
                    SettingsIcon("bell.badge.fill", tint: SettingsTint.notifications)
                }
            }
            .accessibilityIdentifier("settings.row.medicationReminders")
        } footer: {
            Text("Medications, schedules and history live in Browse › Medications. Dose reminders and snooze are in Notifications.")
        }
        .listRowBackground(AppColors.appCard)
    }
}
