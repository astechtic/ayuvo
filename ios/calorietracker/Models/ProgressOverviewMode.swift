import Foundation

/// The panes of the Health tab: the food diary, the owner's own progress charts, the Health Data
/// hub, Workouts (moved out of the tab bar when Records took its place) and Medications.
enum ProgressOverviewMode: String, CaseIterable, Identifiable {
    case food
    case progress
    case healthData
    case workouts
    case medications

    var id: Self { self }

    var title: String {
        switch self {
        case .food: String(localized: "Food")
        case .progress: String(localized: "Progress")
        case .healthData: String(localized: "Health Data")
        case .workouts: String(localized: "Workouts")
        case .medications: String(localized: "Meds")
        }
    }

    /// Short title for the selected segment when five panes share the selector track
    /// (the full `title` stays the accessibility label).
    var compactTitle: String {
        switch self {
        case .healthData: String(localized: "Data")
        default: title
        }
    }

    var icon: String {
        switch self {
        case .food: "fork.knife"
        case .progress: "chart.line.uptrend.xyaxis"
        case .healthData: "heart.text.square.fill"
        case .workouts: "figure.strengthtraining.traditional"
        case .medications: "pills.fill"
        }
    }
}
