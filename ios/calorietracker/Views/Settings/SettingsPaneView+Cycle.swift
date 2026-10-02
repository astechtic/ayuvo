import SwiftUI

/// Tracking › Cycle Tracking (docs/cycle-tracking.md §5).
extension SettingsPaneView {
    @ViewBuilder
    var cyclePane: some View {
        CycleSettingsSection()
    }
}

/// Show cycle tracking, default lengths, fertility estimates, Apple Health sync, reminders, Coach access and
/// "Delete all cycle data". Estimate settings live in the cycle database; visibility and Coach access in defaults.
struct CycleSettingsSection: View {
    @Environment(AppNavigator.self) private var navigator
    @AppStorage(CycleSettings.enabledKey) private var enabled = true
    @AppStorage(CycleSettings.coachEnabledKey) private var coachEnabled = false
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @State private var store = CycleStore.shared
    @State private var showConsent = false
    @State private var confirmDelete = false
    @State private var deleted = false
    @State private var syncDenied = false

    private let config = CycleConfig.shared

    private var options: CycleSettingsOptions { store.options }

    private func update(_ change: @escaping (inout CycleSettingsOptions) -> Void) {
        Task { await store.updateOptions(change) }
    }

    private func optionBinding(_ keyPath: WritableKeyPath<CycleSettingsOptions, Bool>) -> Binding<Bool> {
        Binding(get: { options[keyPath: keyPath] }, set: { value in update { $0[keyPath: keyPath] = value } })
    }

    var body: some View {
        Group {
            Section {
                Toggle(isOn: $enabled) {
                    label("Show Period tracker", detail: "Off hides it from Browse, Summary and the + menu. Your data stays.", icon: "eye.fill")
                }
                .tint(AppColors.calorie)
                .accessibilityIdentifier("settings.cycle.enabled")
                if enabled {
                    Button {
                        navigator.openBrowse([.cycle])
                    } label: {
                        Label {
                            Text("Open Period tracker").foregroundStyle(.primary)
                        } icon: {
                            SettingsIcon("calendar.circle.fill", tint: SettingsTint.cycle)
                        }
                    }
                    .accessibilityIdentifier("settings.cycle.open")
                }
            } footer: {
                Text(CycleText.disclaimer)
            }
            .listRowBackground(AppColors.appCard)

            if enabled && store.isSetUp {
                estimatesSection
                remindersSection
                syncSection
            }
            dataSection
        }
        .task { await store.refreshIfDayChanged() }
        .sheet(isPresented: $showConsent) {
            CycleCoachConsentSheet(onAllow: {
                CycleSettings.setCoachEnabled(true)
                coachEnabled = true
                showConsent = false
            }, onNotNow: {
                showConsent = false
            })
        }
        .confirmationDialog("Delete all cycle data?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete All", role: .destructive) {
                Task {
                    await store.deleteAll()
                    deleted = true
                }
            }
        } message: {
            Text("Every period, day log, note and cycle setting is removed from this iPhone. Samples Ayuvo wrote to Apple Health are removed too. This can't be undone.")
        }
        .alert("Apple Health access needed", isPresented: $syncDenied) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Allow Ayuvo to write cycle data in the Health app › Sharing › Apps › Ayuvo, then turn sync on again.")
        }
    }

    // MARK: Sections

    private var estimatesSection: some View {
        Section {
            Stepper(value: Binding(get: { store.settings.cycleLength ?? config.defaults.cycleLength },
                                   set: { v in var r = store.settings; r.cycleLength = v; Task { await store.saveSettings(r) } }),
                    in: config.limits.settingCycleMin...config.limits.settingCycleMax) {
                LabeledContent("Usual cycle length", value: String(localized: "\(store.settings.cycleLength ?? config.defaults.cycleLength) days"))
            }
            .accessibilityIdentifier("settings.cycle.cycleLength")
            Stepper(value: Binding(get: { store.settings.periodLength ?? config.defaults.periodLength },
                                   set: { v in var r = store.settings; r.periodLength = v; Task { await store.saveSettings(r) } }),
                    in: config.limits.settingPeriodMin...config.limits.settingPeriodMax) {
                LabeledContent("Usual period length", value: String(localized: "\(store.settings.periodLength ?? config.defaults.periodLength) days"))
            }
            Stepper(value: Binding(get: { store.settings.lutealLength ?? config.defaults.lutealLength },
                                   set: { v in var r = store.settings; r.lutealLength = v; Task { await store.saveSettings(r) } }),
                    in: config.limits.lutealMin...config.limits.lutealMax) {
                LabeledContent("Days from ovulation to period", value: String(localized: "\(store.settings.lutealLength ?? config.defaults.lutealLength) days"))
            }
            Toggle(isOn: optionBinding(\.showFertility)) {
                label("Show fertility estimates", detail: "The likely fertile window and estimated ovulation in the calendar and dashboard.", icon: "sparkles")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.cycle.fertility")
        } header: {
            Text("Estimates")
        } footer: {
            Text("Ayuvo uses these until you have logged enough cycles; then it uses the median of your recent cycles.")
        }
        .listRowBackground(AppColors.appCard)
    }

    private var remindersSection: some View {
        Section {
            Toggle("Period may start soon", isOn: optionBinding(\.periodSoon))
                .tint(AppColors.calorie)
                .accessibilityIdentifier("settings.cycle.periodSoon")
            if options.periodSoon {
                Stepper(value: Binding(get: { options.daysBefore }, set: { v in update { $0.daysBefore = v } }), in: 1...3) {
                    LabeledContent("Days before", value: "\(options.daysBefore)")
                }
            }
            Toggle("Update an ongoing period", isOn: optionBinding(\.periodEnd))
                .tint(AppColors.calorie)
            Toggle("Daily log reminder", isOn: optionBinding(\.daily))
                .tint(AppColors.calorie)
            DatePicker("Time", selection: Binding(get: { timeDate }, set: { date in
                let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                update { $0.time = String(format: "%02d:%02d", c.hour ?? 9, c.minute ?? 0) }
            }), displayedComponents: .hourAndMinute)
            Toggle(isOn: optionBinding(\.lockScreenDetails)) {
                label("Show details on lock screen", detail: "Off: reminders only say \"Time to check your tracker.\"", icon: "lock.open.fill")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.cycle.lockScreen")
        } header: {
            Text("Reminders")
        } footer: {
            if !notificationsEnabled {
                Text("Reminders need notifications turned on in Settings › Notifications.")
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    private var timeDate: Date {
        let (h, m) = CycleReminderPlanner.time(options.time)
        return Calendar.current.date(bySettingHour: h, minute: m, second: 0, of: Date()) ?? Date()
    }

    private var syncSection: some View {
        Section {
            if CycleHealthKitWriter.isAvailable {
                Toggle(isOn: Binding(get: { options.healthSync }, set: { on in
                    Task {
                        if on {
                            let granted = await CycleHealthSync.shared.requestAuthorization()
                            if granted {
                                await store.updateOptions { $0.healthSync = true }
                                await CycleHealthSync.shared.syncPending()
                            } else {
                                syncDenied = true
                            }
                        } else {
                            await store.updateOptions { $0.healthSync = false }
                        }
                    }
                })) {
                    label("Sync with Apple Health", detail: "Writes your periods, flow, spotting and symptoms to Apple Health. Mood, pain and notes stay in Ayuvo.",
                          icon: "heart.fill")
                }
                .tint(AppColors.calorie)
                .accessibilityIdentifier("settings.cycle.healthSync")
            }
            Toggle(isOn: Binding(get: { coachEnabled }, set: { on in
                if on { showConsent = true } else { CycleSettings.setCoachEnabled(false); coachEnabled = false }
            })) {
                label("Coach access", detail: "Lets Coach read a summary: cycle lengths, estimates, symptoms, moods and pain. Never your notes.",
                      icon: "sparkles")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.cycle.coach")
        } header: {
            Text("Sharing")
        }
        .listRowBackground(AppColors.appCard)
    }

    private var dataSection: some View {
        Section {
            Button(role: .destructive) {
                confirmDelete = true
            } label: {
                Label {
                    Text(deleted ? "Cycle data deleted" : "Delete all cycle data")
                } icon: {
                    SettingsIcon("trash.fill", tint: SettingsTint.destructive)
                }
            }
            .disabled(deleted)
            .accessibilityIdentifier("settings.cycle.deleteAll")
        } footer: {
            Text("Cycle data is stored only on this iPhone and kept out of iCloud backups. It is included when you use Export All Data.")
        }
        .listRowBackground(AppColors.appCard)
    }

    private func label(_ title: LocalizedStringKey, detail: LocalizedStringKey, icon: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        } icon: {
            SettingsIcon(icon, tint: SettingsTint.cycle)
        }
    }
}

/// The one-time confirmation before Coach may read the cycle summary (docs/cycle-tracking.md §8).
struct CycleCoachConsentSheet: View {
    var onAllow: () -> Void
    var onNotNow: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "calendar.circle.fill")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(CycleStyle.period)
                .padding(.top, 28)
                .accessibilityHidden(true)
            Text("Let Coach use your cycle summary?")
                .font(.system(.title3, design: .rounded, weight: .semibold))
                .multilineTextAlignment(.center)
            Text("Coach sends a short summary — recent cycle lengths, estimates, top symptoms and moods, and pain levels — to your AI provider to answer you. Your notes and individual day logs are never sent.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 26)
            Text("Coach never diagnoses and treats every prediction as an estimate.")
                .font(.system(.footnote, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 26)
            Spacer(minLength: 0)
            VStack(spacing: 10) {
                Button(action: onAllow) {
                    Text("Allow")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.large)
                .accessibilityIdentifier("cycle.coach.allow")
                Button(action: onNotNow) {
                    Text("Not now")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .foregroundStyle(AppColors.calorie)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
        }
        .background(AppColors.appBackground)
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("cycle.coach.consent")
    }
}
