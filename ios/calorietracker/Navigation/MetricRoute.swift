import SwiftUI

/// Metric navigation values (docs/ui-structure.md §2), shared by the Summary and Browse stacks.
enum MetricRoute: Hashable {
    case detail(MetricKey)
    case allData(AppMetric)
    case favourites
}

extension View {
    /// Attaches the metric destinations to a `NavigationStack` content view.
    func metricRouteDestinations() -> some View {
        navigationDestination(for: MetricRoute.self) { route in
            switch route {
            case .detail(let key):
                MetricDetailView(key: key)
            case .allData(let metric):
                AppMetricAllDataView(metric: metric)
            case .favourites:
                FavouritesEditorView()
            }
        }
    }
}
