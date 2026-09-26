import SwiftUI

extension Notification.Name {
    /// An action route or a deep-link request is pending (`ActionRouteCoordinator`); `ContentView` consumes it.
    static let actionRouteRequested = Notification.Name("Ayuvo.actionRouteRequested")
}

/// A deep-link write waiting for the user's OK in the app.
nonisolated struct ActionPendingConfirmation: Identifiable, Equatable, Sendable {
    let id = UUID()
    let validation: ActionValidation
    let summary: String
}

/// Pending OPEN routes and `ayuvo://action/…` requests, same pattern as `WidgetRouteCoordinator`:
/// stored until `ContentView` consumes them, so a cold launch never loses the request.
enum ActionRouteCoordinator {
    private static let routeKey = "actionRoute.pending"
    private static let linkKey = "actionLink.pending"

    @MainActor
    static func request(_ route: ActionRoute, defaults: UserDefaults = .standard) {
        defaults.set(route.storageValue, forKey: routeKey)
        NotificationCenter.default.post(name: .actionRouteRequested, object: nil)
    }

    @MainActor
    static func request(link url: String, defaults: UserDefaults = .standard) {
        defaults.set(url, forKey: linkKey)
        NotificationCenter.default.post(name: .actionRouteRequested, object: nil)
    }

    static func consumeRoute(defaults: UserDefaults = .standard) -> ActionRoute? {
        guard let raw = defaults.string(forKey: routeKey) else { return nil }
        defaults.removeObject(forKey: routeKey)
        return ActionRoute(storageValue: raw)
    }

    static func consumeLink(defaults: UserDefaults = .standard) -> String? {
        guard let raw = defaults.string(forKey: linkKey) else { return nil }
        defaults.removeObject(forKey: linkKey)
        return raw
    }

    /// What an `ayuvo://action/…` or `ayuvo://open/…` link should do in the app (docs/actions.md §Deep links):
    /// OPEN and app-opening actions route, GET / SEARCH open the action's screen, SET asks first.
    enum LinkOutcome: Equatable {
        case route(ActionRoute)
        case confirm(ActionPendingConfirmation)
        case invalid(String)
    }

    @MainActor
    static func resolve(link url: String, executor: ActionExecutor) async -> LinkOutcome {
        switch ActionDeepLink.parse(url) {
        case .notActionLink, .failure:
            return .invalid(String(localized: "This Ayuvo link isn't valid."))
        case .request(let request):
            let params = request.params.mapValues { ActionRawValue.string($0) }
            switch executor.validate(request.id, params, source: .deeplink) {
            case .failure(let error):
                return .invalid(error.message)
            case .success(let validation):
                guard let action = executor.catalog.action(validation.actionID) else { return .invalid(String(localized: "This Ayuvo link isn't valid.")) }
                if action.kind == .set, !action.opensApp {
                    return .confirm(ActionPendingConfirmation(validation: validation, summary: executor.summary(of: validation)))
                }
                if action.kind == .open || action.opensApp {
                    let result = try? await executor.perform(validation, source: .deeplink, confirmed: false)
                    return result?.route.map(LinkOutcome.route) ?? .invalid(String(localized: "This Ayuvo link isn't valid."))
                }
                return executor.screenRoute(for: action, params: validation.params).map(LinkOutcome.route)
                    ?? .invalid(String(localized: "This Ayuvo link isn't valid."))
            }
        }
    }
}

extension AppNavigator {
    /// Lands an action route (catalog `screen` vocabulary) on the right tab / stack.
    func apply(_ route: ActionRoute, recordsStore: RecordsStore, medicationStore: MedicationStore, chatStore: CoachStore) {
        switch route {
        case .coach(let prompt):
            if let prompt, !prompt.isEmpty {
                chatStore.requestHandoff(records: [], prompt: prompt)
            }
            selectedTab = .coach
        case .target(let target):
            let parts = target.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { return }
            let (kind, name) = (parts[0], parts[1])
            switch kind {
            case "metric":
                guard let key = MetricKey(pinID: name) else { return }
                selectedTab = .summary
                var path = NavigationPath()
                path.append(MetricRoute.detail(key))
                summaryPath = path
            case "record":
                recordsStore.openRecordFromCoach(name)
                selectedTab = .records
            default:
                openPlace(name, medicationStore: medicationStore)
            }
        }
    }

    private func openPlace(_ name: String, medicationStore: MedicationStore) {
        switch name {
        case "summary":
            selectedTab = .summary
            summaryPath = NavigationPath()
        case "browse", "health":
            selectedTab = .browse
            resetBrowse()
        case "nutrition": openBrowse([.nutrition])
        case "water":
            selectedTab = .summary
            var path = NavigationPath()
            path.append(MetricRoute.detail(.app(.water)))
            summaryPath = path
        case "fasting": openBrowse([.fasting])
        case "body": openBrowse([.body])
        case "activity": openBrowse([.activity])
        case "workouts": openWorkouts()
        case "workout_log": openWorkoutLogging()
        case "medications": openMedications()
        case "records": selectedTab = .records
        case "coach": selectedTab = .coach
        case "settings": openSettings()
        default:
            selectedTab = .summary
        }
    }
}
