import SwiftUI

extension SettingsPaneView {
    /// About › App & Updates, Help & Support and Legal (contents unchanged).
    @ViewBuilder
    var aboutPane: some View {
        if let aboutCategory = pane.aboutCategory {
            if aboutCategory == .appUpdates {
                AboutAppHeaderSection()
            }

            AboutSettingsSections(
                category: aboutCategory,
                updateState: $updateState,
                refreshUpdateState: refreshUpdateState
            )
        }
    }
}
