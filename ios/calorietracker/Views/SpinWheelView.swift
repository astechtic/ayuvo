import SwiftUI

struct SpinWheelView: View {
    let onComplete: (Int) -> Void

    @State private var scratchPoints: [CGPoint] = []
    @State private var scratchedFraction: Double = 0
    @State private var revealed = false
    @State private var showButton = false

    private let cardWidth: CGFloat = 300
    private let cardHeight: CGFloat = 200
    private let scratchRadius: CGFloat = 30
    private let revealThreshold: Double = 0.35

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 16) {
                Text(revealed ? "Congratulations!" : "Scratch to reveal your\nspecial discount!")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)

                if revealed {
                    Text("You got **27% off** your yearly plan!")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    Text("Drag your finger across the card")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 24)

            Spacer()

            // Scratch card
            ZStack {
                // Hidden layer — the prize
                prizeLayer

                // Scratch overlay
                if !revealed {
                    scratchOverlay
                        .transition(.opacity)
                }
            }
            .frame(width: cardWidth, height: cardHeight)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .shadow(color: .black.opacity(0.3), radius: 12, y: 6)

            Spacer()

            if showButton {
                Button {
                    onComplete(27)
                } label: {
                    Text("Claim My Discount")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(Color(.systemBackground))
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(Color.primary, in: Capsule())
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 36)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                Color.clear
                    .frame(height: 54)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 36)
            }
        }
    }

    // MARK: - Prize Layer

    private var prizeLayer: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(hex: 0x8B2942),
                    Color(hex: 0xFF375F),
                    Color(hex: 0x8B2942)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            // Confetti decorations
            confettiDots

            VStack(spacing: 8) {
                Text("27%")
                    .font(.system(size: 64, weight: .black, design: .rounded))
                    .foregroundStyle(.white)

                Text("OFF")
                    .font(.system(size: 32, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white.opacity(0.9))
                    .tracking(8)
            }
        }
        .frame(width: cardWidth, height: cardHeight)
    }

    private var confettiDots: some View {
        Canvas { context, size in
            let dots: [(x: CGFloat, y: CGFloat, r: CGFloat, o: Double)] = [
                (0.1, 0.15, 4, 0.4), (0.85, 0.2, 5, 0.35),
                (0.15, 0.8, 3, 0.3), (0.9, 0.85, 4, 0.4),
                (0.05, 0.5, 3, 0.25), (0.95, 0.5, 3, 0.25),
                (0.3, 0.1, 3, 0.3), (0.7, 0.9, 4, 0.35),
                (0.5, 0.05, 3, 0.2), (0.5, 0.95, 3, 0.2),
                (0.2, 0.4, 2, 0.2), (0.8, 0.6, 2, 0.2),
            ]
            for dot in dots {
                let rect = CGRect(
                    x: size.width * dot.x - dot.r,
                    y: size.height * dot.y - dot.r,
                    width: dot.r * 2,
                    height: dot.r * 2
                )
                context.fill(
                    Circle().path(in: rect),
                    with: .color(.white.opacity(dot.o))
                )
            }
        }
    }

    // MARK: - Scratch Overlay

    private var scratchOverlay: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(hex: 0x2A2A2A),
                    Color(hex: 0x1A1A1A),
                    Color(hex: 0x333333),
                    Color(hex: 0x1A1A1A)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            // Metallic shimmer lines
            ForEach(0..<5, id: \.self) { i in
                Rectangle()
                    .fill(.white.opacity(0.03))
                    .frame(height: 1)
                    .rotationEffect(.degrees(-30))
                    .offset(x: CGFloat(i) * 60 - 120)
            }

            VStack(spacing: 12) {
                Image(systemName: "hand.draw.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(.white.opacity(0.6))

                Text("SCRATCH HERE")
                    .font(.system(size: 18, weight: .bold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.7))
                    .tracking(3)
            }
        }
        .frame(width: cardWidth, height: cardHeight)
        .mask(
            Canvas { context, size in
                // Fill entire rect first
                context.fill(
                    Rectangle().path(in: CGRect(origin: .zero, size: size)),
                    with: .color(.white)
                )
                // Cut out scratched areas with clear blend mode
                context.blendMode = .clear
                for point in scratchPoints {
                    let rect = CGRect(
                        x: point.x - scratchRadius,
                        y: point.y - scratchRadius,
                        width: scratchRadius * 2,
                        height: scratchRadius * 2
                    )
                    context.fill(
                        Circle().path(in: rect),
                        with: .color(.white)
                    )
                }
            }
            .frame(width: cardWidth, height: cardHeight)
        )
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    let point = value.location
                    // Only add points within card bounds
                    guard point.x >= 0, point.x <= cardWidth,
                          point.y >= 0, point.y <= cardHeight else { return }
                    scratchPoints.append(point)
                    updateScratchFraction()
                }
        )
    }

    // MARK: - Logic

    private func updateScratchFraction() {
        // Estimate scratched area using a grid
        let gridSize: CGFloat = 10
        let cols = Int(cardWidth / gridSize)
        let rows = Int(cardHeight / gridSize)
        let totalCells = cols * rows
        var scratchedCells = 0

        for row in 0..<rows {
            for col in 0..<cols {
                let cellCenter = CGPoint(
                    x: CGFloat(col) * gridSize + gridSize / 2,
                    y: CGFloat(row) * gridSize + gridSize / 2
                )
                for sp in scratchPoints {
                    let dx = cellCenter.x - sp.x
                    let dy = cellCenter.y - sp.y
                    if dx * dx + dy * dy <= scratchRadius * scratchRadius {
                        scratchedCells += 1
                        break
                    }
                }
            }
        }

        scratchedFraction = Double(scratchedCells) / Double(totalCells)

        if scratchedFraction >= revealThreshold && !revealed {
            withAnimation(.easeOut(duration: 0.5)) {
                revealed = true
            }
            withAnimation(.spring(response: 0.5).delay(0.3)) {
                showButton = true
            }
        }
    }
}
