import SwiftUI

/// The five root tabs (docs/ui-structure.md §2): Summary · Browse · Records · Coach · Settings.
enum AppTab: String, Hashable, CaseIterable {
    case summary, browse, records, coach, settings

    var title: LocalizedStringKey {
        switch self {
        case .summary: "Summary"
        case .browse: "Browse"
        case .records: "Records"
        case .coach: "Coach"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .summary: "heart.text.square.fill"
        case .browse: "square.grid.2x2.fill"
        case .records: "list.clipboard.fill"
        case .coach: "bubble.left.and.bubble.right.fill"
        case .settings: "gearshape.fill"
        }
    }

    var accessibilityID: String { "tab.\(rawValue)" }
}
