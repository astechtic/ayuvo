import WidgetKit
import SwiftUI

@main
struct AyuvoWidgetsBundle: WidgetBundle {
    var body: some Widget {
        CalorieWidget()
        ProteinWidget()
        WaterWidget()
        LogFoodWidget()
    }
}
