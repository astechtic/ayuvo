import SwiftUI

/// Tab shell: Summary · Browse · Records · Coach · Settings (docs/ui-structure.md §2).
/// Owns the navigator and drains pending launch routes (notification taps, shortcuts, deep links).
struct ContentView: View {
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(\.scenePhase) private var scenePhase
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(CoachStore.self) private var chatStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(HealthDataStore.self) private var healthDataStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedHealthWorkoutStore
    @Environment(ProfileStore.self) private var profileStore
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @State private var appUpdateState: AppUpdateState = .idle
    @State private var navigator = AppNavigator()
    @State private var actionAlert: ActionAlert?

    /// Deep-link confirmation or the outcome of an action (docs/actions.md §Deep links).
    private enum ActionAlert: Identifiable {
        case confirm(ActionPendingConfirmation)
        case message(String)

        var id: String {
            switch self {
            case .confirm(let pending): "confirm-\(pending.id)"
            case .message(let text): "message-\(text)"
            }
        }
    }

    var body: some View {
        tabs
            .environment(navigator)
            .environment(InsightsStore.shared)
            .tint(AppThemeColor.color(for: appThemeColorRaw).color)
            .task {
                ActionLiveContext.shared.recordsStore = recordsStore
                attachInsights()
                consumePendingLaunchRoutes()
                await refreshAppUpdateState()
            }
            .onReceive(NotificationCenter.default.publisher(for: .actionRouteRequested)) { _ in
                consumeActionRequests()
            }
            .alert(actionAlertTitle, isPresented: Binding(get: { actionAlert != nil }, set: { if !$0 { actionAlert = nil } }), presenting: actionAlert) { alert in
                switch alert {
                case .confirm(let pending):
                    Button(String(localized: "Confirm")) { confirmAction(pending) }
                    Button(String(localized: "Cancel"), role: .cancel) {}
                case .message:
                    Button(String(localized: "OK"), role: .cancel) {}
                }
            } message: { alert in
                switch alert {
                case .confirm(let pending): Text(pending.summary)
                case .message(let text): Text(text)
                }
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
            .onReceive(NotificationCenter.default.publisher(for: .widgetRouteRequested)) { _ in
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
                    InsightsBackgroundRefresh.schedule()
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

    /// Medication beats a widget route beats quick action beats log method beats a shared image,
    /// each consumed in turn so a lower-priority request is never thrown away (`LaunchRouteResolver`).
    private func consumePendingLaunchRoutes() {
        if let pending = MedicationCoordinator.consumePending() {
            navigator.apply(LaunchRouteResolver.resolve(medication: pending, action: nil, method: nil) ?? .medications(detailID: pending), medicationStore: medicationStore)
            return
        }
        if let link = WidgetRouteCoordinator.consumePending() {
            navigator.apply(WidgetRouteAction.resolve(link), medicationStore: medicationStore, recordsStore: recordsStore)
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
        consumeActionRequests()
    }

    /// Insights reads the app's live stores; the background refresh joins this Health store's sync.
    private func attachInsights() {
        let profileStore = profileStore
        let healthDataStore = healthDataStore
        InsightsStore.shared.attach(
            .live(food: foodStore, water: waterStore, fasting: fastingStore, weight: weightStore, bodyFat: bodyFatStore,
                  workouts: strengthWorkoutStore, importedWorkouts: importedHealthWorkoutStore, profile: { profileStore.profile }),
            healthRevision: { healthDataStore.snapshotRevision }
        )
        InsightsBackgroundRefresh.liveHealthStore = healthDataStore
    }

    private var actionAlertTitle: String {
        if case .confirm = actionAlert { return String(localized: "Confirm in Ayuvo") }
        return String(localized: "Ayuvo")
    }

    /// Siri / Shortcuts OPEN actions and `ayuvo://action` / `ayuvo://open` links.
    private func consumeActionRequests() {
        if let route = ActionRouteCoordinator.consumeRoute() {
            navigator.apply(route, recordsStore: recordsStore, medicationStore: medicationStore, chatStore: chatStore)
        }
        guard let link = ActionRouteCoordinator.consumeLink() else { return }
        Task {
            switch await ActionRouteCoordinator.resolve(link: link, executor: .shared) {
            case .route(let route):
                navigator.apply(route, recordsStore: recordsStore, medicationStore: medicationStore, chatStore: chatStore)
            case .confirm(let pending):
                actionAlert = .confirm(pending)
            case .invalid(let message):
                actionAlert = .message(message)
            }
        }
    }

    /// A deep link asked to change data: runs only after the user tapped Confirm.
    private func confirmAction(_ pending: ActionPendingConfirmation) {
        Task {
            do {
                let result = try await ActionExecutor.shared.perform(pending.validation, source: .deeplink, confirmed: true)
                if let route = result.route {
                    navigator.apply(route, recordsStore: recordsStore, medicationStore: medicationStore, chatStore: chatStore)
                }
                actionAlert = .message(result.dialog)
            } catch {
                actionAlert = .message((error as? ActionError)?.message ?? error.localizedDescription)
            }
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
