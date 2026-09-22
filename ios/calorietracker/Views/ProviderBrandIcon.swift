import SwiftUI

/// Provider logo. `.plain` is the bare glyph (menu items); `.settings` is the Settings row icon —
/// the white logo on the AI (or Speech) rounded square, sized like `SettingsIcon`.
enum ProviderBrandIconStyle {
    case plain
    case settings
}

struct AIProviderBrandIcon: View {
    let provider: AIProvider
    var size: CGFloat = 18
    var style: ProviderBrandIconStyle = .plain

    var body: some View {
        ProviderBrandIcon(
            assetName: provider.logoAssetName,
            fallbackSystemImage: provider.fallbackSystemImage,
            size: size,
            style: style,
            tint: SettingsTint.ai
        )
    }
}

struct SpeechProviderBrandIcon: View {
    let provider: SpeechProvider
    var size: CGFloat = 18
    var style: ProviderBrandIconStyle = .plain

    var body: some View {
        ProviderBrandIcon(
            assetName: provider.logoAssetName,
            fallbackSystemImage: provider.fallbackSystemImage,
            size: size,
            style: style,
            tint: SettingsTint.speech
        )
    }
}

private struct ProviderBrandIcon: View {
    let assetName: String?
    let fallbackSystemImage: String
    let size: CGFloat
    let style: ProviderBrandIconStyle
    let tint: Color
    @ScaledMetric(relativeTo: .body) private var settingsSize: CGFloat = 29

    var body: some View {
        switch style {
        case .plain:
            glyph
                .frame(width: size, height: size)
                .foregroundStyle(AppColors.calorie)
                .accessibilityHidden(true)
        case .settings:
            glyph
                .frame(width: settingsSize * 0.56, height: settingsSize * 0.56)
                .foregroundStyle(Color.white)
                .frame(width: settingsSize, height: settingsSize)
                .background(tint, in: RoundedRectangle(cornerRadius: settingsSize * 0.26, style: .continuous))
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var glyph: some View {
        if let assetName {
            Image(assetName)
                .resizable()
                .renderingMode(.template)
                .scaledToFit()
        } else {
            Image(systemName: fallbackSystemImage)
                .resizable()
                .scaledToFit()
        }
    }
}
