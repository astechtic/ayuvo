import SwiftUI
import UIKit

/// Browse › Fasting: the active fast, a trend link and the fasting history.
struct FastingView: View {
    @Environment(FastingStore.self) private var fastingStore
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(AppNavigator.self) private var navigator
    @AppStorage(FastingSettings.enabledKey) private var fastingTrackingEnabled = false
    @AppStorage(FastingSettings.defaultGoalMinutesKey) private var fastingDefaultGoalMinutes = FastingSettings.defaultGoalMinutes
    @AppStorage(FastingSettings.notificationEnabledKey) private var fastingGoalNotificationEnabled = true
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false

    @State private var showStart = false
    @State private var editingSession: FastingSession?
    @State private var pendingDeletion: FastingSession?

    private var history: [FastingSession] {
        fastingStore.sessions.filter { !$0.isActive }.sorted { $0.startedAt > $1.startedAt }
    }

    var body: some View {
        List {
            if !fastingTrackingEnabled && history.isEmpty && fastingStore.activeSession == nil {
                Section {
                    EmptyStateView(
                        "Fasting tracking is off",
                        systemImage: "timer",
                        description: "Turn on Fasting Tracking in Settings to start and log fasts."
                    )
                    Button("Open Settings") { navigator.openSettings(.fasting) }
                        .frame(maxWidth: .infinity)
                }
            } else {
                Section {
                    if let active = fastingStore.activeSession {
                        Button {
                            editingSession = active
                        } label: {
                            ActiveFastingRow(session: active)
                        }
                        .buttonStyle(.plain)
                        Button {
                            endFast()
                        } label: {
                            Label("End Fast", systemImage: "stop.fill")
                        }
                        .tint(AyuvoPalette.fasting)
                        Button(role: .destructive) {
                            fastingStore.cancelActive()
                            notificationManager.cancelFastingGoal()
                        } label: {
                            Label("Cancel Fast", systemImage: "xmark")
                        }
                    } else if fastingTrackingEnabled {
                        Button {
                            showStart = true
                        } label: {
                            Label("Start Fast", systemImage: "timer")
                        }
                        .tint(AyuvoPalette.fasting)
                        .accessibilityIdentifier("fasting.start")
                    }
                } header: {
                    Text("Now")
                }

                Section {
                    NavigationLink(value: MetricRoute.detail(.app(.fasting))) {
                        let descriptor = MetricCatalog.descriptor(for: .app(.fasting))
                        MetricRow(systemImage: descriptor.systemImage, tint: descriptor.tint, title: String(localized: "Fasting Trend"))
                    }
                    .accessibilityIdentifier("browse.metric.app:fasting")
                }

                Section {
                    if history.isEmpty {
                        Text("No completed fasts yet")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(history) { session in
                            Button {
                                editingSession = session
                            } label: {
                                CompletedFastingRow(session: session)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button {
                                    pendingDeletion = session
                                } label: {
                                    Label("Delete", systemImage: "trash.fill")
                                }
                                .tint(.red)
                            }
                        }
                    }
                } header: {
                    Text("History")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Fasting")
        .navigationBarTitleDisplayMode(.large)
        .sheet(isPresented: $showStart) {
            FastingStartSheet(defaultGoalMinutes: fastingDefaultGoalMinutes) { goalMinutes in
                guard fastingStore.start(goalMinutes: goalMinutes) != nil else { return }
                refreshGoalNotification()
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        }
        .sheet(item: $editingSession) { session in
            FastingSessionEditorView(
                session: session,
                onSave: { updated in
                    let saved = fastingStore.update(updated)
                    if saved { refreshGoalNotification() }
                    return saved
                },
                onEndNow: { updated in
                    guard fastingStore.update(updated) else { return false }
                    return endFast()
                },
                onDelete: { removed in
                    fastingStore.delete(id: removed.id)
                    refreshGoalNotification()
                }
            )
        }
        .alert("Delete Fast?", isPresented: Binding(get: { pendingDeletion != nil }, set: { if !$0 { pendingDeletion = nil } })) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let session = pendingDeletion { fastingStore.delete(id: session.id) }
                pendingDeletion = nil
            }
        } message: {
            Text("This removes the fast from your history.")
        }
    }

    private func refreshGoalNotification() {
        notificationManager.scheduleFastingGoal(
            enabled: notificationsEnabled && fastingTrackingEnabled && fastingGoalNotificationEnabled,
            session: fastingStore.activeSession
        )
    }

    @discardableResult
    private func endFast() -> Bool {
        guard fastingStore.endActive() != nil else { return false }
        notificationManager.cancelFastingGoal()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        return true
    }
}
