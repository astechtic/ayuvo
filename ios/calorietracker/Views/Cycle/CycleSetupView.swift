import SwiftUI

/// First-run setup (docs/cycle-tracking.md §5): only the last period start and, optionally, typical lengths,
/// reminders and Health sync. Every question can be skipped.
struct CycleSetupView: View {
    var onDone: (() -> Void)?
    @State private var store = CycleStore.shared
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false

    @State private var lastStart = Date()
    @State private var dontRemember = false
    @State private var knowsLengths = false
    @State private var cycleLength = CycleConfig.shared.defaults.cycleLength
    @State private var periodLength = CycleConfig.shared.defaults.periodLength
    @State private var periodSoon = true
    @State private var healthSync = false
    @State private var saving = false

    private let config = CycleConfig.shared

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: "calendar.circle.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(CycleStyle.period)
                        .accessibilityHidden(true)
                    Text("Track your cycle")
                        .font(.system(.title2, design: .rounded, weight: .bold))
                    Text("Log periods, symptoms and how you feel. Ayuvo estimates your next period from your own history.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
            }

            Section {
                Toggle("I don't remember", isOn: $dontRemember)
                    .tint(CycleStyle.period)
                    .accessibilityIdentifier("cycle.setup.dontRemember")
                if !dontRemember {
                    DatePicker("Started", selection: $lastStart, in: ...Date(), displayedComponents: .date)
                        .accessibilityIdentifier("cycle.setup.lastStart")
                }
            } header: {
                Text("When did your last period start?")
            }

            Section {
                Toggle("I know my usual lengths", isOn: $knowsLengths)
                    .tint(CycleStyle.period)
                    .accessibilityIdentifier("cycle.setup.knowsLengths")
                if knowsLengths {
                    Stepper(value: $cycleLength, in: config.limits.settingCycleMin...config.limits.settingCycleMax) {
                        LabeledContent("Cycle length", value: String(localized: "\(cycleLength) days"))
                    }
                    Stepper(value: $periodLength, in: config.limits.settingPeriodMin...min(config.limits.settingPeriodMax, cycleLength - 1)) {
                        LabeledContent("Period length", value: String(localized: "\(periodLength) days"))
                    }
                }
            } header: {
                Text("Optional")
            } footer: {
                Text("Skip this and Ayuvo starts from \(config.defaults.cycleLength)-day cycles and \(config.defaults.periodLength)-day periods, then learns from what you log.")
            }

            Section {
                Toggle("Remind me before my period", isOn: $periodSoon)
                    .tint(CycleStyle.period)
                if CycleHealthKitWriter.isAvailable {
                    Toggle("Sync with Apple Health", isOn: $healthSync)
                        .tint(CycleStyle.period)
                        .accessibilityIdentifier("cycle.setup.healthSync")
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if periodSoon && !notificationsEnabled {
                        Text("Reminders also need notifications turned on in Settings › Notifications.")
                    }
                    Text("Reminders are discreet: they say \"Time to check your tracker\" unless you choose to show details.")
                }
            }

            Section {
                Label {
                    Text("Your cycle data is stored on this iPhone, kept out of iCloud backups and never used for analytics. It leaves the device only if you export it, sync it with Apple Health, or let Coach read a summary.")
                        .font(.system(.footnote, design: .rounded))
                } icon: {
                    Image(systemName: "lock.fill").foregroundStyle(.secondary)
                }
                CycleDisclaimer()
            }

            Section {
                Button {
                    Task { await finish() }
                } label: {
                    Text("Start tracking")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(CycleStyle.period)
                .disabled(saving)
                .listRowBackground(Color.clear)
                .accessibilityIdentifier("cycle.setup.start")
            }
        }
        .navigationTitle("Period tracker")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func finish() async {
        saving = true
        defer { saving = false }
        var options = CycleSettingsOptions()
        options.periodSoon = periodSoon
        if healthSync {
            options.healthSync = await CycleHealthSync.shared.requestAuthorization()
        }
        await store.completeSetup(lastStart: dontRemember ? nil : CycleDates.string(lastStart),
                                  cycleLength: knowsLengths ? cycleLength : nil,
                                  periodLength: knowsLengths ? periodLength : nil,
                                  options: options)
        onDone?()
    }
}
