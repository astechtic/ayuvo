import SwiftUI

struct ActivityRingView: View {
    let progress: Double
    let ringWidth: CGFloat
    let gradientColors: [Color]

    @State private var animatedProgress: Double = 0

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let radius = (size - ringWidth) / 2

            ZStack {
                // Background track
                Circle()
                    .stroke(gradientColors.first?.opacity(0.15) ?? Color.gray.opacity(0.15), lineWidth: ringWidth)

                // Foreground arc with gradient
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

                // Glow dot at arc endpoint
                if animatedProgress > 0.01 {
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
            withAnimation(.spring(response: 1.2, dampingFraction: 0.75).delay(0.15)) {
                animatedProgress = progress
            }
        }
        .onChange(of: progress) { _, newValue in
            withAnimation(.spring(response: 0.6, dampingFraction: 0.85)) {
                animatedProgress = newValue
            }
        }
    }
}
