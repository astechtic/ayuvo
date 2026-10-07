import WidgetKit
import SwiftUI

@main
struct AyuvoWidgetsBundle: WidgetBundle {
    var body: some Widget {
        CalorieWidget()
        ProteinWidget()
        WaterWidget()
        LogFoodWidget()
        TodayWidget()
        MyMetricsWidget()
        QuickLogWidget()
        WorkoutWidget()
        WorkoutHistoryWidget()
        FoodHistoryWidget()
        WorkoutStartWidget()
        WalkStartWidget()
        WorkoutLiveActivityWidget()
        if #available(iOS 18.0, *) {
            StartWorkoutControl()
            StartWalkControl()
        }
    }
}
