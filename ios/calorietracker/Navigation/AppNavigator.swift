import SwiftUI

/// Tab selection and the Summary / Browse stack paths, owned by `ContentView` and injected with
/// `.environment`. "Places" (Nutrition, Fasting, Workouts, Medications) only ever open on the
/// Browse stack, so exactly one live screen consumes each one-shot request.
@Observable
@MainActor
final class AppNavigator {
    var selectedTab: AppTab = .summary
    var summaryPath = NavigationPath()
    var browsePath = NavigationPath()
    var settingsPath = NavigationPath()
    /// One-shot food logging requests, consumed by `NutritionView`.
    var quickActionRequest: QuickActionRequest?
    var foodLogMethodRequest: FoodLogMethodRequest?
    /// Workout log timers survive popping the Workouts screen.
    let workoutLogSession = WorkoutLogSessionState()

    @ObservationIgnored private var lastBrowseRoutes: [BrowseRoute] = []

    /// Selects Browse and replaces its path with `routes` (a reset discards what was pushed there).
    func openBrowse(_ routes: [BrowseRoute]) {
        selectedTab = .browse
        if routes == lastBrowseRoutes, browsePath.count == routes.count { return }
        var path = NavigationPath()
        for route in routes { path.append(route) }
        browsePath = path
        lastBrowseRoutes = routes
    }

    /// Pops Browse to its root (re-tap on the tab, or a new reset).
    func resetBrowse() {
        browsePath = NavigationPath()
        lastBrowseRoutes = []
    }

    func openNutrition(action: QuickAction) {
        openBrowse([.nutrition])
        quickActionRequest = QuickActionRequest(action: action)
    }

    func openNutrition(method: FoodLogMethod) {
        openBrowse([.nutrition])
        foodLogMethodRequest = FoodLogMethodRequest(method: method)
    }

    func openWorkouts() {
        openBrowse([.activity, .workouts])
    }

    func openMedications() {
        openBrowse([.medications])
    }

    /// Selects Settings; with a pane, replaces the Settings path so that pane is on top.
    func openSettings(_ pane: SettingsPane? = nil) {
        selectedTab = .settings
        guard let pane else { return }
        var path = NavigationPath()
        path.append(pane)
        settingsPath = path
    }

    func apply(_ route: LaunchRoute, medicationStore: MedicationStore) {
        switch route {
        case .medications(let detailID):
            openMedications()
            if let detailID { medicationStore.navigationRequest = .detail(detailID) }
        case .quickAction(let action):
            openNutrition(action: action)
        case .logMethod(let method):
            openNutrition(method: method)
        case .nutrition:
            openBrowse([.nutrition])
        }
    }
}

extension Notification.Name {
    /// The share extension handed over a food photo (`ayuvo://import-share-image`).
    static let shareImageImportRequested = Notification.Name("Ayuvo.shareImageImportRequested")
}

/// Where a pending launch request (notification tap, shortcut, widget, deep link) lands.
enum LaunchRoute: Equatable {
    case medications(detailID: String?)
    case quickAction(QuickAction)
    case logMethod(FoodLogMethod)
    case nutrition
}

enum LaunchRouteResolver {
    /// Medication beats quick action beats log method beats a pending shared image.
    /// `medication` is `.some(nil)` for "open Medications without a detail".
    static func resolve(
        medication: String??,
        action: QuickAction?,
        method: FoodLogMethod?,
        hasSharedImage: Bool = false
    ) -> LaunchRoute? {
        if let medication { return .medications(detailID: medication) }
        if let action { return .quickAction(action) }
        if let method { return .logMethod(method) }
        if hasSharedImage { return .nutrition }
        return nil
    }
}
