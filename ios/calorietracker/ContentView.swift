import SwiftUI

/// Tab shell: Summary · Browse · Records · Coach · Settings (docs/ui-structure.md §2).
/// Owns the navigator and drains pending launch routes (notification taps, shortcuts, deep links).
struct ContentView: View {
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(\.scenePhase) private var scenePhase
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(ChatStore.self) private var chatStore
    @Environment(MedicationStore.self) private var medicationStore
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @State private var appUpdateState: AppUpdateState = .idle
    @State private var navigator = AppNavigator()

    var body: some View {
        tabs
            .environment(navigator)
            .tint(AppThemeColor.color(for: appThemeColorRaw).color)
            .task {
                consumePendingLaunchRoutes()
                await refreshAppUpdateState()
            }
            .onChange(of: recordsStore.tabRequest) { _, _ in
                // Share extension / "Open in Ayuvo" imports and Coach "Used records" chips land on Records.
                navigator.selectedTab = .records
            }
            .onChange(of: chatStore.handoffRequest) { _, _ in
                // Records entry points (Ask about this report, Explain this trend, Ask Coach) open Coach.
                navigator.selectedTab = .coach
            }
            .onChange(of: medicationStore.tabRequest) { _, _ in
                // Notification taps, "Open Medications" after a prescription import.
                navigator.openMedications()
            }
            .onReceive(NotificationCenter.default.publisher(for: .quickActionRequested)) { _ in
                consumePendingLaunchRoutes()
            }
            .onReceive(NotificationCenter.default.publisher(for: .medicationRouteRequested)) { _ in
                consumePendingLaunchRoutes()
            }
            .onReceive(NotificationCenter.default.publisher(for: .foodLogMethodRequested)) { _ in
                consumePendingLaunchRoutes()
            }
            .onReceive(NotificationCenter.default.publisher(for: .shareImageImportRequested)) { _ in
                // The diary consumes the shared photo when it appears.
                if let route = LaunchRouteResolver.resolve(medication: nil, action: nil, method: nil, hasSharedImage: true) {
                    navigator.apply(route, medicationStore: medicationStore)
                }
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    consumePendingLaunchRoutes()
                }
            }
    }

    private var tabs: some View {
        TabView(selection: $navigator.selectedTab) {
            SummaryView()
                .tag(AppTab.summary)
                .tabItem { Label(AppTab.summary.title, systemImage: AppTab.summary.systemImage) }
                .accessibilityIdentifier(AppTab.summary.accessibilityID)

            BrowseView()
                .tag(AppTab.browse)
                .tabItem { Label(AppTab.browse.title, systemImage: AppTab.browse.systemImage) }
                .accessibilityIdentifier(AppTab.browse.accessibilityID)

            RecordsHomeView()
                .tag(AppTab.records)
                .tabItem { Label(AppTab.records.title, systemImage: AppTab.records.systemImage) }
                .accessibilityIdentifier(AppTab.records.accessibilityID)

            ChatView()
                .tag(AppTab.coach)
                .tabItem { Label(AppTab.coach.title, systemImage: AppTab.coach.systemImage) }
                .accessibilityIdentifier(AppTab.coach.accessibilityID)

            SettingsView(
                updateState: $appUpdateState,
                refreshUpdateState: {
                    await refreshAppUpdateState(force: true)
                }
            )
                .tag(AppTab.settings)
                .tabItem { Label(AppTab.settings.title, systemImage: AppTab.settings.systemImage) }
                .accessibilityIdentifier(AppTab.settings.accessibilityID)
                .badge(appUpdateState.isUpdateAvailable ? "!" : nil)
        }
    }

    /// Medication beats quick action beats log method beats a shared image, each consumed in turn
    /// so a lower-priority request is never thrown away (`LaunchRouteResolver`).
    private func consumePendingLaunchRoutes() {
        if let pending = MedicationCoordinator.consumePending() {
            navigator.apply(LaunchRouteResolver.resolve(medication: pending, action: nil, method: nil) ?? .medications(detailID: pending), medicationStore: medicationStore)
            return
        }
        if let action = QuickActionCoordinator.consumePending() {
            navigator.apply(.quickAction(action), medicationStore: medicationStore)
            return
        }
        if let method = FoodLogMethodCoordinator.consumePending() {
            navigator.apply(.logMethod(method), medicationStore: medicationStore)
            return
        }
    }

    @MainActor
    private func refreshAppUpdateState(force: Bool = false) async {
        if !force && appUpdateState.hasStartedCheck {
            return
        }

        appUpdateState = .checking
        appUpdateState = await AppUpdateChecker.check()

        // A newer version is out — fire a one-shot notification (de-duped per version, gated by the
        // "App Updates" toggle) so the user finds out even if they don't scroll to the About section.
        if case let .available(_, latest, url) = appUpdateState {
            await notificationManager.notifyUpdateAvailable(version: latest, url: url)
        }
    }
}

#if DEBUG
#Preview {
    ContentView()
        .ayuvoPreviewEnvironment()
}
#endif
