import Charts
import SwiftUI

// MARK: - Surfaces

struct AyuvoCardModifier: ViewModifier {
    var padding: CGFloat?

    func body(content: Content) -> some View {
        content
            .padding(padding ?? 0)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AyuvoPalette.card, in: RoundedRectangle(cornerRadius: AyuvoPalette.cardRadius, style: .continuous))
    }
}

extension View {
    /// Plain grouped card: secondary grouped background, 16 pt continuous corners, no stroke or shadow.
    func ayuvoCard(padding: CGFloat? = 16) -> some View {
        modifier(AyuvoCardModifier(padding: padding))
    }

    /// Grouped screen background behind scroll content.
    func ayuvoScreenBackground() -> some View {
        background(AyuvoPalette.screenBackground.ignoresSafeArea())
    }
}

// MARK: - Section header

struct AyuvoSectionHeader<Trailing: View>: View {
    let title: LocalizedStringKey
    let trailing: Trailing

    init(_ title: LocalizedStringKey, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.system(.title3, design: .rounded, weight: .bold))
                .accessibilityAddTraits(.isHeader)
            Spacer()
            trailing
                .font(.system(.subheadline, design: .rounded, weight: .medium))
        }
        .padding(.horizontal, 4)
    }
}

extension AyuvoSectionHeader where Trailing == EmptyView {
    init(_ title: LocalizedStringKey) {
        self.init(title) { EmptyView() }
    }
}

// MARK: - Icons and rows

struct CategoryIconView: View {
    enum Style { case tinted, filled }

    let systemImage: String
    let tint: Color
    var style: Style = .tinted
    var size: CGFloat = 32

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(style == .filled ? Color.white : tint)
            .frame(width: size, height: size)
            .background(
                style == .filled ? tint : tint.opacity(0.14),
                in: RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            )
            .accessibilityHidden(true)
    }
}

struct MetricRow: View {
    let systemImage: String
    let tint: Color
    let title: String
    var subtitle: String?
    var value: String?
    var dimmed = false

    var body: some View {
        HStack(spacing: 12) {
            CategoryIconView(systemImage: systemImage, tint: tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            if let value {
                Text(value)
                    .font(.ayuvoNumber(.subheadline))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .opacity(dimmed ? 0.55 : 1)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Tiles

struct MetricTileModel: Identifiable, Equatable {
    let key: String
    let title: String
    let systemImage: String
    let tint: Color
    let valueText: String
    let unitText: String
    let at: Date?
    let sparkline: [Double]
    /// Caption when `at` is nil ("Today", "No data").
    var caption: String?

    var id: String { key }
}

struct MetricTile: View {
    let model: MetricTileModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 5) {
                Image(systemName: model.systemImage)
                    .font(.system(.caption, weight: .semibold))
                Text(model.title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(model.tint)
            Spacer(minLength: 2)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(model.valueText)
                    .font(.ayuvoNumber(.title2))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                if !model.unitText.isEmpty {
                    Text(model.unitText)
                        .font(.system(.caption, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            HStack(alignment: .bottom) {
                Text(captionText)
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if model.sparkline.count >= 2 {
                    SparklineView(values: model.sparkline, tint: model.tint)
                        .frame(width: 48, height: 18)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 104, alignment: .topLeading)
        .ayuvoCard(padding: 12)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: "\(model.title): \(model.valueText) \(model.unitText)"))
    }

    private var captionText: String {
        if let at = model.at { return HealthUnitFormatting.relativeText(at) }
        return model.caption ?? String(localized: "No data")
    }
}

struct SparklineView: View {
    let values: [Double]
    let tint: Color

    var body: some View {
        Chart(Array(values.enumerated()), id: \.offset) { item in
            LineMark(x: .value("Index", item.offset), y: .value("Value", item.element))
                .interpolationMethod(.catmullRom)
                .lineStyle(StrokeStyle(lineWidth: 1.5, lineCap: .round))
                .foregroundStyle(tint)
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartLegend(.hidden)
        .accessibilityHidden(true)
    }
}

// MARK: - Rings

struct RingSpec: Identifiable {
    enum State { case value, connect, noData, noGoal }

    let id: String
    let title: String
    let progress: Double
    let tint: Color
    let valueText: String
    let goalText: String
    let state: State
    var action: () -> Void = {}
}

/// Concentric Eat · Move · Drink rings with legend lines (Apple Fitness style, flat colours).
struct RingTrioView: View {
    let rings: [RingSpec]
    var size: CGFloat = 150
    var ringWidth: CGFloat = 16
    var animationEpoch = 0

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            ZStack {
                ForEach(Array(rings.enumerated()), id: \.element.id) { index, ring in
                    let inset = CGFloat(index) * (ringWidth + 4)
                    ActivityRingView(
                        progress: ring.state == .value ? ring.progress : 0,
                        ringWidth: ringWidth,
                        gradientColors: [ring.tint, ring.tint],
                        showsEndCap: false,
                        animationEpoch: animationEpoch
                    )
                    .frame(width: size - inset * 2, height: size - inset * 2)
                    .onTapGesture { ring.action() }
                    .accessibilityHidden(true)
                }
            }
            .frame(width: size, height: size)

            VStack(alignment: .leading, spacing: 10) {
                ForEach(rings) { ring in
                    Button(action: ring.action) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(ring.title)
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                .foregroundStyle(ring.tint)
                            legend(for: ring)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("summary.ring.\(ring.id)")
                }
            }
        }
    }

    @ViewBuilder
    private func legend(for ring: RingSpec) -> some View {
        switch ring.state {
        case .connect:
            Text("Connect Health")
                .font(.system(.footnote, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
        case .value, .noData, .noGoal:
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(ring.valueText)
                    .font(.ayuvoNumber(.title3))
                    .foregroundStyle(.primary)
                if !ring.goalText.isEmpty {
                    Text(verbatim: "/\(ring.goalText)")
                        .font(.system(.footnote, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
    }
}

// MARK: - Headline, range picker, empty state

struct HeadlineStat: View {
    let label: String
    let value: String
    let unit: String
    let caption: String

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label.uppercased())
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.ayuvoNumber(.largeTitle))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.system(.headline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Text(caption)
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("metric.headline")
    }
}

struct RangePicker: View {
    @Binding var selection: HealthDetailRange
    var options: [HealthDetailRange] = HealthDetailRange.allCases

    var body: some View {
        Picker("Range", selection: $selection) {
            ForEach(options) { option in
                Text(option.rawValue)
                    .tag(option)
                    .accessibilityIdentifier("metric.range.\(option.rawValue)")
            }
        }
        .pickerStyle(.segmented)
    }
}

struct EmptyStateView: View {
    let title: LocalizedStringKey
    let systemImage: String
    var description: LocalizedStringKey?

    init(_ title: LocalizedStringKey, systemImage: String, description: LocalizedStringKey? = nil) {
        self.title = title
        self.systemImage = systemImage
        self.description = description
    }

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            if let description {
                Text(description)
            }
        }
    }
}
