import SwiftUI

/// Appearance: light/dark mode and the theme colour (used only for buttons and primary actions).
extension SettingsPaneView {
    @ViewBuilder
    var appearancePane: some View {
        Section {
            Picker(selection: $appearanceMode) {
                Text("System").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            } label: {
                Label {
                    Text("Appearance")
                } icon: {
                    Image(systemName: "circle.lefthalf.filled")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)

            Picker(selection: $appThemeColorRaw) {
                ForEach(AppThemeColor.allCases) { themeColor in
                    Label {
                        Text(themeColor.displayName)
                    } icon: {
                        Image(uiImage: themeColor.menuSwatchImage)
                    }
                    .tag(themeColor.rawValue)
                }
            } label: {
                Label {
                    Text("Theme Color")
                } icon: {
                    Image(systemName: "paintpalette.fill")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
        } footer: {
            Text("Theme Color is used for buttons and primary actions. Health categories keep their own colours.")
        }
        .listRowBackground(AppColors.appCard)
    }
}
