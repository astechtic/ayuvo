import SwiftUI
import UIKit

enum AppThemeColor: String, CaseIterable, Identifiable {
    case rose
    case red
    case orange
    case green
    case mint
    case teal
    case blue
    case purple
    case yellow
    case coral
    case roseGold
    case mochaBrown
    case indigo
    case lavender
    case skyCyan
    case graphite
    case babyPink
    case lime

    static let storageKey = "appThemeColor"
    /// Theme for anyone who has never picked one (nothing stored under `storageKey`). Blue since
    /// the Apple-flat redesign; a stored choice — including Rose, the old default — is kept.
    static let defaultColor: AppThemeColor = .blue

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .rose: return LocalizedDisplayText.text("Rose", polish: "Róż")
        case .red: return LocalizedDisplayText.text("Red", polish: "Czerwony")
        case .orange: return LocalizedDisplayText.text("Orange", polish: "Pomarańczowy")
        case .green: return LocalizedDisplayText.text("Green", polish: "Zielony")
        case .mint: return LocalizedDisplayText.text("Mint", polish: "Miętowy")
        case .teal: return LocalizedDisplayText.text("Teal", polish: "Turkusowy")
        case .blue: return LocalizedDisplayText.text("Blue", polish: "Niebieski")
        case .purple: return LocalizedDisplayText.text("Purple", polish: "Fioletowy")
        case .yellow: return LocalizedDisplayText.text("Yellow", polish: "Żółty")
        case .coral: return LocalizedDisplayText.text("Coral", polish: "Koralowy")
        case .roseGold: return LocalizedDisplayText.text("Rose Gold", polish: "Różowe złoto")
        case .mochaBrown: return LocalizedDisplayText.text("Mocha Brown", polish: "Brąz mokka")
        case .indigo: return LocalizedDisplayText.text("Indigo", polish: "Indygo")
        case .lavender: return LocalizedDisplayText.text("Lavender", polish: "Lawendowy")
        case .skyCyan: return LocalizedDisplayText.text("Sky Cyan", polish: "Błękit")
        case .graphite: return LocalizedDisplayText.text("Graphite", polish: "Grafitowy")
        case .babyPink: return LocalizedDisplayText.text("Baby Pink", polish: "Pastelowy róż")
        case .lime: return LocalizedDisplayText.text("Lime", polish: "Limonkowy")
        }
    }

    var color: Color {
        Color(hex: startHex)
    }

    var gradientColors: [Color] {
        [Color(hex: startHex), Color(hex: endHex)]
    }

    var alternateIconName: String? {
        switch self {
        case .rose: return nil
        case .red: return "AppIconRed"
        case .orange: return "AppIconOrange"
        case .green: return "AppIconGreen"
        case .mint: return "AppIconMint"
        case .teal: return "AppIconTeal"
        case .blue: return "AppIconBlue"
        case .purple: return "AppIconPurple"
        case .yellow: return "AppIconYellow"
        case .coral: return "AppIconCoral"
        case .roseGold: return "AppIconRoseGold"
        case .mochaBrown: return "AppIconMochaBrown"
        case .indigo: return "AppIconIndigo"
        case .lavender: return "AppIconLavender"
        case .skyCyan: return "AppIconSkyCyan"
        case .graphite: return "AppIconGraphite"
        case .babyPink: return "AppIconBabyPink"
        case .lime: return "AppIconLime"
        }
    }

    static var current: AppThemeColor {
        guard let rawValue = UserDefaults.standard.string(forKey: storageKey),
              let themeColor = AppThemeColor(rawValue: rawValue) else {
            return defaultColor
        }
        return themeColor
    }

    static func color(for rawValue: String) -> AppThemeColor {
        AppThemeColor(rawValue: rawValue) ?? defaultColor
    }

    // Menu (dropdown) rows render SwiftUI colors as monochrome templates, so the
    // swatch has to be a pre-rendered UIImage marked alwaysOriginal to keep its color.
    var menuSwatchImage: UIImage {
        let size = CGSize(width: 22, height: 22)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            let rect = CGRect(origin: .zero, size: size)
            UIBezierPath(ovalIn: rect).addClip()
            let colors = [UIColor(color), UIColor(gradientColors.last ?? color)].map(\.cgColor)
            if let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: colors as CFArray,
                locations: [0, 1]
            ) {
                context.cgContext.drawLinearGradient(
                    gradient,
                    start: .zero,
                    end: CGPoint(x: size.width, y: size.height),
                    options: []
                )
            }
        }
        return image.withRenderingMode(.alwaysOriginal)
    }

    @MainActor
    static func applyAppIconIfNeeded(for themeColor: AppThemeColor, defaults: UserDefaults = .standard) {
        let application = UIApplication.shared
        // The default theme without an explicit choice keeps the primary icon: switching icons
        // raises a system alert, which a first launch must not show. Picking a colour (the
        // picker stores it) switches the icon as before.
        if defaults.string(forKey: storageKey) == nil, themeColor == defaultColor { return }
        guard application.supportsAlternateIcons,
              application.alternateIconName != themeColor.alternateIconName else {
            return
        }

        application.setAlternateIconName(themeColor.alternateIconName)
    }

    // Internal (not private) so WidgetSnapshotWriter can ship the raw hexes to
    // the Watch, which has no AppThemeColor and rebuilds Colors from the values.
    var startHex: UInt {
        switch self {
        case .rose: return 0xFF375F
        case .red: return 0xFF3B30
        case .orange: return 0xFF9500
        case .green: return 0x34C759
        case .mint: return 0x00C7BE
        case .teal: return 0x30B0C7
        case .blue: return 0x0A84FF
        case .purple: return 0xAF52DE
        case .yellow: return 0xFFCC00
        case .coral: return 0xFF7F50
        case .roseGold: return 0xC9807C
        case .mochaBrown: return 0xA2845E
        case .indigo: return 0x5856D6
        case .lavender: return 0xB57EDC
        case .skyCyan: return 0x32ADE6
        case .graphite: return 0x8E8E93
        case .babyPink: return 0xFF8FAB
        case .lime: return 0xA0D911
        }
    }

    var endHex: UInt {
        switch self {
        case .rose: return 0xFF6B8A
        case .red: return 0xFF6961
        case .orange: return 0xFFB340
        case .green: return 0x62D46F
        case .mint: return 0x66D4CF
        case .teal: return 0x64D2FF
        case .blue: return 0x5EAEFF
        case .purple: return 0xBF5AF2
        case .yellow: return 0xFFD60A
        case .coral: return 0xFFA382
        case .roseGold: return 0xE8B4B0
        case .mochaBrown: return 0xC9A57E
        case .indigo: return 0x7D7AFF
        case .lavender: return 0xD0A9F5
        case .skyCyan: return 0x70CFFF
        case .graphite: return 0xB8B8BE
        case .babyPink: return 0xFFB3C6
        case .lime: return 0xC3E956
        }
    }
}

enum AppColors {
    /// The user's theme accent. Apple-flat design: used for primary actions, the selected tab and
    /// chart scrub markers only (docs/ui-structure.md §1). Gradients are flat pairs of the accent.
    static var calorieGradient: [Color] { [calorie, calorie] }
    static var calorie: Color { AppThemeColor.current.color }

    // Macros have fixed colours (shared catalog `macro_colours`).
    static var proteinGradient: [Color] { [protein, protein] }
    static var protein: Color { AyuvoPalette.protein }

    static var carbsGradient: [Color] { [carbs, carbs] }
    static var carbs: Color { AyuvoPalette.carbs }

    static var fatGradient: [Color] { [fat, fat] }
    static var fat: Color { AyuvoPalette.fat }

    static var fiber: Color { AyuvoPalette.fiber }

    // Neutral grouped backgrounds (system colours; the asset colours match them for old call sites).
    static let appBackground = Color(uiColor: .systemGroupedBackground)
    static let appCard = Color(uiColor: .secondarySystemGroupedBackground)
}

extension Color {
    init(hex: UInt, opacity: Double = 1.0) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
