import SwiftUI

/// Settings › Tracking › Derived Metrics › Camera measurements (docs/camera-vitals.md §7.1): keep raw signals,
/// experimental (SpO₂) and research (BP) estimates, and delete all scans.
struct CameraMeasurementsSettingsSection: View {
    @AppStorage(VitalsSettings.keepSignalsKey) private var keepSignals = true
    @AppStorage(VitalsSettings.experimentalKey) private var experimentalEnabled = false
    @AppStorage(VitalsSettings.researchKey) private var researchEnabled = false
    @State private var confirmDeleteAll = false
    @State private var deleted = false

    var body: some View {
        Section {
            Toggle(isOn: $keepSignals) {
                settingLabel("Keep raw signals", detail: "Per-frame colour values and the processed pulse, so scans can be re-analysed later. Never images or video.",
                             icon: "waveform.path.ecg")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.vitals.keepSignals")
            Toggle(isOn: $experimentalEnabled) {
                settingLabel("Experimental estimates", detail: "SpO₂ from a finger scan, only against your own oximeter calibration.",
                             icon: "flask.fill")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.vitals.experimental")
            Toggle(isOn: $researchEnabled) {
                settingLabel("Research estimates", detail: "Blood pressure research model from your own cuff calibrations. Not a blood pressure measurement.",
                             icon: "testtube.2")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.vitals.research")
            Button(role: .destructive) {
                confirmDeleteAll = true
            } label: {
                Label {
                    Text(deleted ? "Camera scans deleted" : "Delete all camera scans")
                } icon: {
                    SettingsIcon("trash.fill", tint: SettingsTint.destructive)
                }
            }
            .disabled(deleted)
            .accessibilityIdentifier("settings.vitals.deleteAll")
        } header: {
            Text("Camera measurements")
        } footer: {
            Text("Finger and face scans stay in Ayuvo and are never written to Apple Health. \(VitalsText.disclaimer)")
        }
        .listRowBackground(AppColors.appCard)
        .confirmationDialog("Delete all camera scans?", isPresented: $confirmDeleteAll, titleVisibility: .visible) {
            Button("Delete All", role: .destructive) {
                Task {
                    await VitalsStore.shared.deleteAll()
                    deleted = true
                }
            }
        } message: {
            Text("Every finger and face scan and its saved signals are removed from this device. This can't be undone.")
        }
    }

    private func settingLabel(_ title: LocalizedStringKey, detail: LocalizedStringKey, icon: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            SettingsIcon(icon, tint: SettingsTint.vitals)
        }
    }
}
