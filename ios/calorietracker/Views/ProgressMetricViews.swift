import SwiftUI

enum ProgressHistoryCountText {
    static func localized(_ count: Int) -> String {
        String(localized: "\(count) entry · tap to view or delete")
    }
}
