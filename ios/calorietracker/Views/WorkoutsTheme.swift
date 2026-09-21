import SwiftUI
import UIKit

// MARK: - Workouts theme bridge
// The Workouts exercise library is ported from the base project's Delts library.
// Delts styles its views through a small set of `delts*` palette tokens and view
// modifiers; this file re-implements that exact surface on top of Ayuvo's theme
// (AppColors + the user-selectable AppThemeColor accent), so the ported views render
// with Ayuvo's default look while keeping their code byte-for-byte close to Delts.

extension Color {
    // Apple-flat aliases (docs/ui-structure.md): the workout surfaces use the neutral grouped
    // palette and the Activity domain colour instead of a second colour vocabulary.

    /// Screen background.
    static var workoutBackground: Color { AyuvoPalette.screenBackground }

    /// Card surface behind rows and hero imagery.
    static var workoutCard: Color { AyuvoPalette.card }

    /// Elevated panel behind menus / pills.
    static var workoutPanel: Color { AyuvoPalette.panel }

    /// Hairline strokes.
    static var workoutHairline: Color { AyuvoPalette.separator }

    /// Primary accent — the Activity domain colour.
    static var workoutAccent: Color { AyuvoPalette.activity }

    /// Softer companion accent.
    static var workoutSecondaryAccent: Color { AyuvoPalette.activity.opacity(0.7) }

    /// Delts aliased "inferno" to its secondary accent; keep the alias.
    static var workoutInferno: Color { Color.workoutSecondaryAccent }

    /// Strong text.
    static var workoutCharcoal: Color { Color.primary }

    /// Muted/supporting text.
    static var workoutMutedText: Color { Color.secondary }

    /// Text/icons rendered on top of the accent color.
    static var workoutOnAccent: Color { Color.white }
}

struct WorkoutBackground: View {
    var body: some View {
        Color.workoutBackground
            .ignoresSafeArea()
    }
}

extension View {
    func workoutScreen() -> some View {
        background(WorkoutBackground())
            .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    func workoutLiquidBarSurface(cornerRadius: CGFloat = 32) -> some View {
        modifier(WorkoutLiquidBarSurfaceModifier(cornerRadius: cornerRadius))
    }

    func workoutPressable() -> some View {
        buttonStyle(WorkoutPressableButtonStyle())
    }
}

private struct WorkoutLiquidBarSurfaceModifier: ViewModifier {
    let cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .background(Color.workoutPanel, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

struct WorkoutPressableButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let isPressed = configuration.isPressed && isEnabled

        configuration.label
            .scaleEffect(isPressed ? 0.975 : 1)
            .opacity(isEnabled ? (isPressed ? 0.90 : 1) : 0.55)
            .animation(.easeOut(duration: 0.14), value: isPressed)
    }
}
