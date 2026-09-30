import SwiftUI

struct QuickActionsSettingsView: View {
    @AppStorage(QuickActionSettings.storageKeys[0]) private var firstRaw = QuickActionSettings.defaults[0].rawValue
    @AppStorage(QuickActionSettings.storageKeys[1]) private var secondRaw = QuickActionSettings.defaults[1].rawValue
    @AppStorage(QuickActionSettings.storageKeys[2]) private var thirdRaw = QuickActionSettings.defaults[2].rawValue

    var body: some View {
        Form {
            Section {
                quickActionPicker(slot: 1, title: String(localized: "Quick Action 1", comment: "Quick action slot setting"), selection: $firstRaw)
                quickActionPicker(slot: 2, title: String(localized: "Quick Action 2", comment: "Quick action slot setting"), selection: $secondRaw)
                quickActionPicker(slot: 3, title: String(localized: "Quick Action 3", comment: "Quick action slot setting"), selection: $thirdRaw)
            } header: {
                Text("App Icon Shortcuts")
            } footer: {
                Text("Hold the Ayuvo app icon to use these shortcuts. Each slot opens its selected action directly. On iPhone, Quick Action 1–3 can also be assigned in Shortcuts to the Action Button or Back Tap.")
            }
        }
        .navigationTitle("Quick Actions")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: firstRaw) { _, _ in refreshShortcuts() }
        .onChange(of: secondRaw) { _, _ in refreshShortcuts() }
        .onChange(of: thirdRaw) { _, _ in refreshShortcuts() }
    }

    private func quickActionPicker(slot: Int, title: String, selection: Binding<String>) -> some View {
        Picker(selection: selection) {
            ForEach(QuickAction.allCases) { action in
                Label(action.title, systemImage: action.systemImageName)
                    .tag(action.rawValue)
            }
        } label: {
            SettingsLabel(title, systemImage: numberIcon(for: slot), tint: SettingsTint.quickActions)
        }
        .pickerStyle(.menu)
        .tint(.secondary)
    }

    private func numberIcon(for slot: Int) -> String {
        "\(slot).circle.fill"
    }

    private func refreshShortcuts() {
        QuickActionSettings.registerApplicationShortcuts()
    }
}
