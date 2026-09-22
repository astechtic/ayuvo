import SwiftUI

/// Fixed row colours for Settings (iOS-Settings style: white glyph on a coloured rounded square).
/// Rows that belong to a health domain use the domain colour (`AyuvoPalette`); everything else
/// uses a system-like colour. The theme accent (`AppColors.calorie`) is never an icon colour, so
/// a row keeps its colour whatever theme the user picks.
nonisolated enum SettingsTint {
    // Domains
    static let nutrition = AyuvoPalette.nutrition
    static let hydration = AyuvoPalette.hydration
    static let fasting = AyuvoPalette.fasting
    static let body = AyuvoPalette.body
    static let activity = AyuvoPalette.activity
    static let heart = AyuvoPalette.heart
    static let sleep = AyuvoPalette.sleep
    static let vitals = AyuvoPalette.vitals
    static let medications = AyuvoPalette.medications
    static let records = AyuvoPalette.records
    static let other = AyuvoPalette.other

    // System-like
    static let notifications = AyuvoPalette.dynamic(0xFF3B30, 0xFF453A)
    static let privacy = AyuvoPalette.dynamic(0x007AFF, 0x0A84FF)
    static let appearance = AyuvoPalette.dynamic(0x007AFF, 0x0A84FF)
    static let ai = AyuvoPalette.dynamic(0xAF52DE, 0xBF5AF2)
    static let speech = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let instructions = AyuvoPalette.dynamic(0x5E5CE6, 0x7D7AFF)
    static let units = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let keys = AyuvoPalette.dynamic(0x8E8E93, 0x98989D)
    static let network = AyuvoPalette.dynamic(0x30B0C7, 0x40CBE0)
    static let fallback = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let about = AyuvoPalette.dynamic(0x8E8E93, 0x98989D)
    static let update = AyuvoPalette.dynamic(0x007AFF, 0x0A84FF)
    static let support = AyuvoPalette.dynamic(0x34C759, 0x30D158)
    static let rating = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let legal = AyuvoPalette.dynamic(0x8E8E93, 0x98989D)
    static let calendar = AyuvoPalette.dynamic(0xFF3B30, 0xFF453A)
    static let destructive = AyuvoPalette.dynamic(0xFF3B30, 0xFF453A)
    static let warning = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let success = AyuvoPalette.dynamic(0x34C759, 0x30D158)
    static let backup = AyuvoPalette.dynamic(0x007AFF, 0x0A84FF)
    static let export = AyuvoPalette.dynamic(0x007AFF, 0x0A84FF)
    static let importData = AyuvoPalette.dynamic(0x34C759, 0x30D158)
    static let storage = AyuvoPalette.dynamic(0x8E8E93, 0x98989D)
    static let time = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let favourite = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let theme = AyuvoPalette.dynamic(0xFF2D55, 0xFF375F)
    static let language = AyuvoPalette.dynamic(0x30B0C7, 0x40CBE0)
    static let feedback = AyuvoPalette.dynamic(0xFFCC00, 0xFFD60A)
    static let photos = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let quickActions = AyuvoPalette.dynamic(0xFF9500, 0xFF9F0A)
    static let debug = AyuvoPalette.dynamic(0x8E8E93, 0x98989D)
}

/// The one Settings row icon: `CategoryIconView` filled at the hub-row size (29 pt, Dynamic Type
/// scaled), so hub rows and pane rows line up. Use it as a `Label` icon or at the start of an HStack.
struct SettingsIcon: View {
    let systemImage: String
    let tint: Color
    @ScaledMetric(relativeTo: .body) private var size: CGFloat = 29

    init(_ systemImage: String, tint: Color) {
        self.systemImage = systemImage
        self.tint = tint
    }

    var body: some View {
        CategoryIconView(systemImage: systemImage, tint: tint, style: .filled, size: size)
    }
}

/// `Label(title, systemImage:)` with a `SettingsIcon`.
struct SettingsLabel: View {
    let title: Text
    let systemImage: String
    let tint: Color

    init(_ title: LocalizedStringKey, systemImage: String, tint: Color) {
        self.title = Text(title)
        self.systemImage = systemImage
        self.tint = tint
    }

    @_disfavoredOverload
    init<S: StringProtocol>(_ title: S, systemImage: String, tint: Color) {
        self.title = Text(title)
        self.systemImage = systemImage
        self.tint = tint
    }

    init(_ title: Text, systemImage: String, tint: Color) {
        self.title = title
        self.systemImage = systemImage
        self.tint = tint
    }

    var body: some View {
        Label {
            title
        } icon: {
            SettingsIcon(systemImage, tint: tint)
        }
    }
}
