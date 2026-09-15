import Foundation

/// The two panes of the Health tab: the owner's own progress charts and the Health Data hub.
enum ProgressOverviewMode: String, CaseIterable, Identifiable {
    case progress
    case healthData

    var id: Self { self }

    var title: String {
        switch self {
        case .progress: String(localized: "Progress")
        case .healthData: String(localized: "Health Data")
        }
    }

    var icon: String {
        switch self {
        case .progress: "chart.line.uptrend.xyaxis"
        case .healthData: "heart.text.square.fill"
        }
    }
}
