import SwiftUI
import UIKit

/// Fixed domain colours of the Apple-flat design (docs/ui-structure.md §3, shared catalog `domains`).
/// The theme accent (`AppColors.calorie`) is never used for a domain.
nonisolated enum AyuvoPalette {
    static let nutrition = dynamic(0x34C759, 0x30D158)
    static let hydration = dynamic(0x007AFF, 0x0A84FF)
    static let fasting = dynamic(0x00C7BE, 0x63E6E2)
    static let body = dynamic(0xAF52DE, 0xBF5AF2)
    static let activity = dynamic(0xFF9500, 0xFF9F0A)
    static let heart = dynamic(0xFF3B30, 0xFF453A)
    static let sleep = dynamic(0x5E5CE6, 0x7D7AFF)
    static let vitals = dynamic(0xFF2D55, 0xFF375F)
    static let respiratory = dynamic(0x5AC8FA, 0x64D2FF)
    static let cycle = dynamic(0xFF2D55, 0xFF375F)
    static let mindfulness = dynamic(0x30B0C7, 0x40CBE0)
    static let mobility = dynamic(0xFF9500, 0xFF9F0A)
    static let hearing = dynamic(0x007AFF, 0x0A84FF)
    static let symptoms = dynamic(0xAF52DE, 0xBF5AF2)
    static let medications = dynamic(0x32ADE6, 0x64D2FF)
    static let records = dynamic(0x5856D6, 0x5E5CE6)
    static let other = dynamic(0x8E8E93, 0x98989D)

    static let protein = dynamic(0x007AFF, 0x007AFF)
    static let carbs = dynamic(0xFF9F0A, 0xFF9F0A)
    static let fat = dynamic(0xBF5AF2, 0xBF5AF2)
    static let fiber = dynamic(0x30B0C7, 0x30B0C7)

    static let screenBackground = Color(uiColor: .systemGroupedBackground)
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    static let panel = Color(uiColor: .tertiarySystemGroupedBackground)
    static let separator = Color(uiColor: .separator)

    static let cardRadius: CGFloat = 16

    /// Light/dark pair as one dynamic colour.
    static func dynamic(_ light: UInt, _ dark: UInt) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }

    /// Parses `#RRGGBB` (catalog strings). Nil for anything else.
    static func hexValue(_ string: String) -> UInt? {
        var text = string
        if text.hasPrefix("#") { text.removeFirst() }
        guard text.count == 6 else { return nil }
        return UInt(text, radix: 16)
    }

    /// Domain colour by catalog domain id (`nutrition`, `hydration`, …); Other for unknown ids.
    static func domain(_ id: String) -> Color {
        switch id {
        case "nutrition": nutrition
        case "hydration": hydration
        case "fasting": fasting
        case "body": body
        case "activity": activity
        case "heart": heart
        case "sleep": sleep
        case "vitals": vitals
        case "respiratory": respiratory
        case "cycle": cycle
        case "mindfulness": mindfulness
        case "mobility": mobility
        case "hearing": hearing
        case "symptoms": symptoms
        case "medications": medications
        case "records": records
        default: other
        }
    }
}

extension Font {
    /// Rounded numerals for values and headlines (Dynamic Type aware).
    static func ayuvoNumber(_ style: Font.TextStyle, weight: Font.Weight = .semibold) -> Font {
        .system(style, design: .rounded, weight: weight)
    }
}
