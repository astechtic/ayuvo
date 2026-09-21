import SwiftUI

/// Progress ring. Flat when `gradientColors` holds one colour twice; `showsEndCap` draws the
/// glowing end dot of the original design. The fill animates when the view first appears and
/// again whenever `animationEpoch` changes (the Summary bumps it once per scene activation), so
/// scrolling a lazy stack does not replay it.
struct ActivityRingView: View {
    let progress: Double
    let ringWidth: CGFloat
    let gradientColors: [Color]
    var showsEndCap = true
    var animationEpoch = 0

    @State private var animatedProgress: Double = 0
    @State private var animatedEpoch: Int?

    private var clamped: Double { min(max(progress, 0), 1) }

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let radius = (size - ringWidth) / 2

            ZStack {
                Circle()
                    .stroke(gradientColors.first?.opacity(0.18) ?? Color.gray.opacity(0.15), lineWidth: ringWidth)

                Circle()
                    .trim(from: 0, to: animatedProgress)
                    .stroke(
                        AngularGradient(
                            colors: gradientColors + [gradientColors.first ?? .clear],
                            center: .center,
                            startAngle: .degrees(0),
                            endAngle: .degrees(360 * animatedProgress)
                        ),
                        style: StrokeStyle(lineWidth: ringWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))

                if showsEndCap, animatedProgress > 0.01 {
                    Circle()
                        .fill(gradientColors.last ?? .white)
                        .frame(width: ringWidth, height: ringWidth)
                        .shadow(color: gradientColors.last?.opacity(0.6) ?? .clear, radius: 6)
                        .offset(y: -radius)
                        .rotationEffect(.degrees(360 * animatedProgress - 90))
                }
            }
            .frame(width: size, height: size)
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
        }
        .aspectRatio(1, contentMode: .fit)
        .onAppear {
            if animatedEpoch == animationEpoch {
                animatedProgress = clamped
                return
            }
            animatedEpoch = animationEpoch
            animatedProgress = 0
            withAnimation(.spring(response: 1.2, dampingFraction: 0.75).delay(0.15)) {
                animatedProgress = clamped
            }
        }
        .onChange(of: animationEpoch) { _, newValue in
            animatedEpoch = newValue
            animatedProgress = 0
            withAnimation(.spring(response: 1.2, dampingFraction: 0.75).delay(0.15)) {
                animatedProgress = clamped
            }
        }
        .onChange(of: progress) { _, _ in
            withAnimation(.spring(response: 0.6, dampingFraction: 0.85)) {
                animatedProgress = clamped
            }
        }
    }
}
