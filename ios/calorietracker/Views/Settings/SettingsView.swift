import SwiftUI

/// Settings tab root (plan §1.2, §6): a profile header that opens Personal Info, then one
/// inset-grouped section per `SettingsGroup`. Every pane is pushed on this single stack.
struct SettingsView: View {
    @Environment(AppNavigator.self) private var navigator
    @Binding var updateState: AppUpdateState
    let refreshUpdateState: () async -> Void

    var body: some View {
        @Bindable var navigator = navigator
        NavigationStack(path: $navigator.settingsPath) {
            List {
                Section {
                    NavigationLink(value: SettingsPane.personalInfo) {
                        SettingsProfileHeader()
                    }
                    .accessibilityIdentifier("settings.profile")
                }
                .listRowBackground(AppColors.appCard)

                ForEach(SettingsGroup.allCases) { group in
                    Section {
                        ForEach(group.panes) { pane in
                            SettingsPaneRow(pane: pane, badge: pane == .appUpdates && updateState.isUpdateAvailable)
                        }
                    } header: {
                        if let title = group.title {
                            Text(title)
                        }
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Settings")
            .navigationDestination(for: SettingsPane.self) { pane in
                if pane == .notifications {
                    NotificationSettingsView()
                } else {
                    SettingsPaneView(
                        updateState: $updateState,
                        refreshUpdateState: refreshUpdateState,
                        pane: pane
                    )
                }
            }
            .metricRouteDestinations()
        }
    }
}

/// Initials badge, name and Age · Height · Weight, in the user's units.
struct SettingsProfileHeader: View {
    @Environment(ProfileStore.self) private var profileStore
    @AppStorage(HeightUnit.storageKey) private var heightUnitRaw = HeightUnit.ftin.rawValue
    @AppStorage(WeightUnit.storageKey) private var weightUnitRaw = WeightUnit.lbs.rawValue
    @ScaledMetric(relativeTo: .title) private var badgeSize: CGFloat = 56

    private var profile: UserProfile { profileStore.profile }

    private var hasName: Bool { !(profile.name ?? "").trimmingCharacters(in: .whitespaces).isEmpty }

    private var heightText: String {
        if heightUnitRaw == HeightUnit.cm.rawValue { return "\(Int(profile.heightCm.rounded())) cm" }
        let inches = Int((profile.heightCm / 2.54).rounded())
        return "\(inches / 12)′\(inches % 12)″"
    }

    private var weightText: String {
        weightUnitRaw == WeightUnit.kg.rawValue
            ? String(format: "%.1f kg", profile.weightKg)
            : String(format: "%.1f lbs", profile.weightKg * 2.20462)
    }

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(AyuvoPalette.body.gradient)
                if hasName {
                    Text(profile.initials)
                        .font(.system(.title2, design: .rounded, weight: .semibold))
                        .foregroundStyle(.white)
                } else {
                    Image(systemName: "person.fill")
                        .font(.title2)
                        .foregroundStyle(.white)
                }
            }
            .frame(width: badgeSize, height: badgeSize)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(hasName ? profile.displayName : String(localized: "Your Profile"))
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                Text("Age \(profile.age) · \(heightText) · \(weightText)")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}
