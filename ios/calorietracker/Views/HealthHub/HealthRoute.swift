import SwiftUI

/// Navigation values for the Health Data hub. Registered with
/// `navigationDestination(for: HealthRoute.self)`; `.metric` opens the unified `MetricDetailView`.
enum HealthRoute: Hashable {
    case category(HealthCategory)
    case metric(String)
    case allData(String)
    case sources(String)
    case unit(String)
    case pins
}

struct HealthRouteDestination: View {
    let route: HealthRoute

    var body: some View {
        switch route {
        case .category(let category):
            HealthCategoryView(category: category)
        case .metric(let typeID):
            MetricDetailView(key: .health(typeID))
        case .allData(let typeID):
            HealthAllDataView(typeID: typeID)
        case .sources(let typeID):
            HealthDataSourcesView(typeID: typeID)
        case .unit(let typeID):
            HealthUnitPickerView(typeID: typeID)
        case .pins:
            FavouritesEditorView()
        }
    }
}

extension View {
    /// Attaches the hub destinations (and the metric destinations the detail screen pushes)
    /// to a `NavigationStack` content view.
    func healthRouteDestinations() -> some View {
        navigationDestination(for: HealthRoute.self) { route in
            HealthRouteDestination(route: route)
        }
        .metricRouteDestinations()
    }
}
