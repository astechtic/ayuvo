import Foundation

/// Pending widget deep links (`ayuvo://log/<id>`, `ayuvo://metric/<key>`, `ayuvo://summary`),
/// same pattern as `QuickActionCoordinator`: stored until `ContentView` consumes it, so a cold
/// launch never loses the tap (docs/widgets.md "Deep links").
enum WidgetRouteCoordinator {
    private static let pendingKey = "widgetRoute.pending"

    @MainActor
    static func request(_ link: WidgetDeepLink, defaults: UserDefaults = .standard) {
        defaults.set(link.storageValue, forKey: pendingKey)
        NotificationCenter.default.post(name: .widgetRouteRequested, object: nil)
    }

    /// The app-side pending route, else the one the small Quick Log widget's button intent left
    /// in the App Group.
    static func consumePending(defaults: UserDefaults = .standard) -> WidgetDeepLink? {
        if let raw = defaults.string(forKey: pendingKey) {
            defaults.removeObject(forKey: pendingKey)
            if let link = WidgetDeepLink(storageValue: raw) { return link }
        }
        return WidgetPendingRoute.consume()
    }
}

/// What a widget route does in the app, mirroring the Summary "+" menu (docs/widgets.md).
enum WidgetRouteAction: Equatable {
    case foodMenu
    case quickAction(QuickAction)
    case logMethod(FoodLogMethod)
    case summaryLog(SummaryLogRequest.Kind)
    case settings(SettingsPane)
    case workouts
    case medications
    case addRecord
    case fasting
    case metric(MetricKey)
    case summary

    static func resolve(_ link: WidgetDeepLink, defaults: UserDefaults = .standard) -> WidgetRouteAction {
        switch link {
        case .summary:
            return .summary
        case .metric(let key):
            if key == WidgetMetricOption.nextDose.rawValue { return .medications }
            if key == WidgetMetricOption.fasting.rawValue { return .fasting }
            guard let metric = MetricKey(pinID: key) else { return .summary }
            return .metric(metric)
        case .log(let action):
            if action == .foodMenu { return .foodMenu }
            if let raw = action.foodMethodRaw, let method = FoodLogMethod(rawValue: raw) {
                if let quick = method.quickAction { return .quickAction(quick) }
                return .logMethod(method)
            }
            switch action {
            case .water:
                return defaults.bool(forKey: WaterSettings.enabledKey) ? .summaryLog(.water) : .settings(.hydration)
            case .fasting:
                return defaults.bool(forKey: FastingSettings.enabledKey) ? .quickAction(.fasting) : .settings(.fasting)
            case .weight: return .summaryLog(.weight)
            case .bodyFat: return .summaryLog(.bodyFat)
            case .workout: return .workouts
            case .medication: return .medications
            case .record: return .addRecord
            default: return .foodMenu
            }
        }
    }
}

/// One-shot request for a sheet the Summary "+" menu owns.
struct SummaryLogRequest: Identifiable, Equatable {
    enum Kind: Equatable {
        case water, weight, bodyFat
    }

    let id = UUID()
    let kind: Kind
}
