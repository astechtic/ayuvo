import Foundation

/// The panes of the Health tab: the owner's own progress charts, the Health Data hub and
/// Workouts (moved out of the tab bar when Records took its place).
enum ProgressOverviewMode: String, CaseIterable, Identifiable {
    case food
    case progress
    case healthData
    case workouts

    var id: Self { self }

    var title: String {
        switch self {
        case .food: String(localized: "Food")
        case .progress: String(localized: "Progress")
        case .healthData: String(localized: "Health Data")
        case .workouts: String(localized: "Workouts")
        }
    }

    var icon: String {
        switch self {
        case .food: "fork.knife"
        case .progress: "chart.line.uptrend.xyaxis"
        case .healthData: "heart.text.square.fill"
        case .workouts: "figure.strengthtraining.traditional"
        }
    }
}
