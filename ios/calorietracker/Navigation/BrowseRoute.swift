import SwiftUI

/// Pushed "places" of the Browse stack (docs/ui-structure.md §2).
enum BrowseRoute: Hashable {
    case nutrition, fasting, body, activity, workouts, exerciseLibrary, medications
    /// Body › Body Measurements (also reached from Browse search).
    case bodyMeasurements
}

struct BrowseRouteDestination: View {
    let route: BrowseRoute
    @Binding var path: NavigationPath
    @Environment(AppNavigator.self) private var navigator
    @Environment(ProfileStore.self) private var profileStore

    var body: some View {
        switch route {
        case .nutrition:
            NutritionView()
        case .fasting:
            FastingView()
        case .body:
            BodyCategoryView()
        case .activity:
            ActivityCategoryView()
        case .workouts:
            WorkoutLogView(
                session: navigator.workoutLogSession,
                embedsInNavigationStack: false,
                showsNavigationBar: true,
                onShowLibrary: { path.append(BrowseRoute.exerciseLibrary) }
            )
        case .exerciseLibrary:
            ExerciseLibraryScreen()
        case .medications:
            MedicationsHomeView(path: $path)
        case .bodyMeasurements:
            BodyMeasurementsDetailView(gender: profileStore.profile.gender, heightCm: profileStore.profile.heightCm)
        }
    }
}

extension View {
    /// Registers the Browse places. Attach once, at the Browse stack root.
    func browseRouteDestinations(path: Binding<NavigationPath>) -> some View {
        navigationDestination(for: BrowseRoute.self) { route in
            BrowseRouteDestination(route: route, path: path)
        }
    }
}
