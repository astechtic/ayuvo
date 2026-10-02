import SwiftUI

/// Colours and small shared pieces of the cycle screens (docs/cycle-tracking.md §5). The domain colour marks periods;
/// a calm teal marks the estimated fertile window. Every mark also has a shape or text, never colour alone.
enum CycleStyle {
    static let period = AyuvoPalette.cycle
    static let fertile = AyuvoPalette.mindfulness
    static let neutral = Color.secondary.opacity(0.18)

    static func flowSymbol(_ key: String?) -> String {
        switch key {
        case "spotting": "drop"
        case "light": "drop.fill"
        case "medium": "drop.halffull"
        case "heavy", "very_heavy": "drop.triangle.fill"
        default: "circle.dotted"
        }
    }

    /// 1…5 dots for a flow level (spotting = 1, very heavy = 5).
    static func flowRank(_ key: String?) -> Int {
        CycleConfig.shared.flowLevel(key)?.rank ?? 0
    }
}

/// Tap-to-toggle chip used by the flow, symptom, mood and pain-location selectors.
struct CycleChip: View {
    let title: String
    var systemImage: String?
    let selected: Bool
    var tint: Color = CycleStyle.period
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let systemImage { Image(systemName: systemImage).font(.caption) }
                Text(title).font(.system(.subheadline, design: .rounded, weight: selected ? .semibold : .regular))
                if selected { Image(systemName: "checkmark").font(.caption2.weight(.bold)) }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .foregroundStyle(selected ? Color.white : Color.primary)
            .background(selected ? tint : AyuvoPalette.panel, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }
}

/// Wrapping layout for chips.
struct CycleFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            x += size.width + spacing
            row = max(row, size.height)
        }
        return CGSize(width: width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += row + spacing
                row = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}

/// Basis badge: "Based on your history" etc.
struct CycleBasisBadge: View {
    let basis: String

    var body: some View {
        Text(CycleText.basisTitle(basis))
            .font(.system(.caption, design: .rounded, weight: .semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(.secondary)
            .background(AyuvoPalette.panel, in: Capsule())
            .accessibilityLabel(Text("Estimate basis: \(CycleText.basisTitle(basis))"))
    }
}

/// Flow per period day as small drop glyphs (history rows, cycle detail).
struct CycleFlowStrip: View {
    let flows: [String?]

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(flows.enumerated()), id: \.offset) { _, flow in
                Image(systemName: CycleStyle.flowSymbol(flow))
                    .font(.caption2)
                    .foregroundStyle(flow == nil ? Color.secondary.opacity(0.5) : CycleStyle.period)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(flows.map { $0.map(CycleText.flow) ?? String(localized: "no flow logged") }.joined(separator: ", ")))
    }
}

/// The disclaimer footnote under every estimate screen.
struct CycleDisclaimer: View {
    var body: some View {
        Text(CycleText.disclaimer)
            .font(.system(.caption, design: .rounded))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("cycle.disclaimer")
    }
}

/// Circular cycle visualisation: one segment per cycle day, coloured and patterned by phase, with today's marker and
/// the cycle day + phase in the middle (also the accessibility label).
struct CycleRingView: View {
    /// Phases for cycle day 1…n.
    let phases: [String]
    /// 1-based index of today in `phases` (nil = not in this cycle).
    let todayIndex: Int?
    let centerTitle: String
    let centerSubtitle: String
    var showFertility = true

    var body: some View {
        ZStack {
            Canvas { context, size in
                let n = max(phases.count, 1)
                let lineWidth: CGFloat = min(size.width, size.height) * 0.09
                let radius = min(size.width, size.height) / 2 - lineWidth / 2 - 6
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let gap = 0.012
                for (i, phase) in phases.enumerated() {
                    let start = Angle.degrees(-90 + 360 * (Double(i) / Double(n) + gap / 2))
                    let end = Angle.degrees(-90 + 360 * (Double(i + 1) / Double(n) - gap / 2))
                    var path = Path()
                    path.addArc(center: center, radius: radius, startAngle: start, endAngle: end, clockwise: false)
                    let style = StrokeStyle(lineWidth: lineWidth, lineCap: .butt)
                    switch phase {
                    case "period":
                        context.stroke(path, with: .color(CycleStyle.period), style: style)
                    case "predicted_period", "late":
                        context.stroke(path, with: .color(CycleStyle.period.opacity(0.35)), style: style)
                    case "fertile" where showFertility:
                        context.stroke(path, with: .color(CycleStyle.fertile.opacity(0.55)), style: style)
                    case "ovulation" where showFertility:
                        context.stroke(path, with: .color(CycleStyle.fertile), style: style)
                    default:
                        context.stroke(path, with: .color(Color.secondary.opacity(0.16)), style: style)
                    }
                }
                if let todayIndex, todayIndex >= 1, todayIndex <= n {
                    let angle = Angle.degrees(-90 + 360 * ((Double(todayIndex) - 0.5) / Double(n)))
                    let point = CGPoint(x: center.x + radius * cos(angle.radians), y: center.y + radius * sin(angle.radians))
                    let dot = CGRect(x: point.x - lineWidth * 0.62, y: point.y - lineWidth * 0.62, width: lineWidth * 1.24, height: lineWidth * 1.24)
                    context.fill(Path(ellipseIn: dot), with: .color(Color(uiColor: .systemBackground)))
                    context.stroke(Path(ellipseIn: dot), with: .color(.primary), lineWidth: 2.5)
                }
            }
            VStack(spacing: 4) {
                Text(centerTitle)
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Text(centerSubtitle)
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 44)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(centerTitle), \(centerSubtitle)"))
        .accessibilityIdentifier("cycle.ring")
    }
}

/// Legend for the ring and the calendar (shape + colour + words).
struct CycleLegend: View {
    var showFertility = true

    var body: some View {
        CycleFlowLayout(spacing: 12) {
            item(String(localized: "Period"), symbol: AnyView(Circle().fill(CycleStyle.period)))
            item(CycleText.phase("predicted_period"),
                 symbol: AnyView(Circle().strokeBorder(CycleStyle.period, style: StrokeStyle(lineWidth: 1.5, dash: [3, 2]))))
            if showFertility {
                item(CycleText.phase("fertile"), symbol: AnyView(RoundedRectangle(cornerRadius: 3).fill(CycleStyle.fertile.opacity(0.3))))
                item(CycleText.phase("ovulation"), symbol: AnyView(Circle().strokeBorder(CycleStyle.fertile, lineWidth: 2)
                    .overlay(Circle().fill(CycleStyle.fertile).frame(width: 4, height: 4))))
            }
            item(String(localized: "Logged"), symbol: AnyView(Circle().fill(Color.secondary).frame(width: 5, height: 5)))
        }
        .accessibilityElement(children: .combine)
    }

    private func item(_ title: String, symbol: AnyView) -> some View {
        HStack(spacing: 5) {
            symbol.frame(width: 14, height: 14)
            Text(title).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
        }
    }
}
