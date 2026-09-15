import SwiftUI
import WidgetKit

@main
struct WatchNutritionWidgetsBundle: WidgetBundle {
    var body: some Widget {
        WatchCaloriesWidget()
        WatchProteinWidget()
        WatchMacrosWidget()
    }
}
