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

// MARK: - Privacy & open source

/// Small capsule: lock + "Private by design". Optionally links to the privacy-first page.
struct AyuvoPrivacyPill: View {
    var showsOpenSource = false
    var opensPrivacyPage = false

    private var label: Text {
        showsOpenSource ? Text("Private · Open source") : Text("Private by design")
    }

    private var pill: some View {
        HStack(spacing: 6) {
            Image(systemName: "lock.fill")
                .font(.system(size: 11, weight: .bold))
            label
                .font(.system(.footnote, design: .rounded, weight: .semibold))
        }
        .foregroundStyle(AyuvoPalette.body)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(AyuvoPalette.body.opacity(0.14), in: Capsule())
    }

    var body: some View {
        if opensPrivacyPage {
            Link(destination: AppLinks.privacyFirstURL) { pill }
                .accessibilityHint(Text("Opens the privacy page"))
        } else {
            pill.accessibilityElement(children: .combine)
        }
    }
}

/// Settings card: what "private" means in Ayuvo, and where to read the code.
struct AyuvoPrivacyOpenSourceCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(AyuvoPalette.body.gradient, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Private by design")
                        .font(.system(.headline, design: .rounded))
                    Text("Open source · MIT")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Text("Your health data is kept on this device. No account, no ads, no analytics. Ayuvo's code is open source, so anyone can check what it does.")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Link(destination: AppLinks.privacyFirstURL) {
                    Label("How we protect it", systemImage: "hand.raised.fill")
                }
                Link(destination: AppLinks.githubURL) {
                    Label("View source", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            }
            .font(.system(.footnote, design: .rounded, weight: .semibold))
            .tint(AyuvoPalette.body)
        }
        .padding(.vertical, 6)
        .accessibilityIdentifier("settings.privacyOpenSource")
    }
}

// MARK: - Section header

struct AyuvoSectionHeader<Trailing: View>: View {
    let title: Text
    let trailing: Trailing

    init(_ title: LocalizedStringKey, @ViewBuilder trailing: () -> Trailing) {
        self.title = Text(title)
        self.trailing = trailing()
    }

    /// Already-localized or data-derived titles (e.g. "September 2026").
    init(verbatim title: String, @ViewBuilder trailing: () -> Trailing) {
        self.title = Text(verbatim: title)
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            title
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

    init(verbatim title: String) {
        self.init(verbatim: title) { EmptyView() }
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

/// Mini chart on the right of a Summary tile: bars for daily totals, dots for readings, a ring for scores and goals.
enum SummaryTileChart: Equatable {
    case none
    /// Daily totals, oldest first; the newest bar takes the tint.
    case bars([Double])
    /// Readings, oldest first, one dot each; the newest dot takes the tint.
    case dots([Double])
    /// Score or goal progress (0…1), with optional text in the middle.
    case ring(progress: Double, text: String?)
}

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
    /// Daily totals read as bars, readings as dots.
    var cumulative = false

    var id: String { key }
}

/// Favourite metric on Summary, drawn as an Apple Health style tile.
struct MetricTile: View {
    let model: MetricTileModel

    var body: some View {
        SummaryTile(
            title: model.title,
            systemImage: model.systemImage,
            tint: model.tint,
            trailing: captionText,
            value: model.valueText,
            unit: model.unitText,
            chart: chart
        )
    }

    private var chart: SummaryTileChart {
        guard model.sparkline.count >= 2 else { return .none }
        return model.cumulative ? .bars(model.sparkline) : .dots(model.sparkline)
    }

    private var captionText: String {
        if let at = model.at { return HealthUnitFormatting.relativeText(at) }
        return model.caption ?? String(localized: "No data")
    }
}

/// Apple Health style card: tinted icon and title with a time and chevron on top; a large value
/// bottom-left and a small chart bottom-right. Wrap it in a Button or NavigationLink.
struct SummaryTile: View {
    let title: String
    let systemImage: String
    let tint: Color
    var trailing: String?
    /// Small grey line above the value ("Latest").
    var label: String?
    /// Large value; empty for message tiles that only show `detail`.
    var value: String = ""
    var unit: String = ""
    /// Grey line under the value ("Next Metformin at 8:00 PM").
    var detail: String?
    var chart: SummaryTileChart = .none
    var dimmed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(tint)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(tint)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if let trailing {
                    Text(trailing)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Image(systemName: "chevron.right")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            HStack(alignment: .bottom, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    if let label {
                        Text(label)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    if !value.isEmpty {
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(value)
                                .font(.ayuvoNumber(.title))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.6)
                            if !unit.isEmpty {
                                Text(unit)
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                    if let detail {
                        // Without a value the detail is the tile's message, so it reads in the primary style.
                        Text(detail)
                            .font(.system(value.isEmpty ? .body : .subheadline, design: .rounded, weight: value.isEmpty ? .medium : .regular))
                            .foregroundStyle(value.isEmpty ? .primary : .secondary)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .layoutPriority(1)
                Spacer(minLength: 0)
                SummaryTileChartView(chart: chart, tint: tint)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AyuvoPalette.card, in: RoundedRectangle(cornerRadius: AyuvoPalette.cardRadius, style: .continuous))
        .opacity(dimmed ? 0.55 : 1)
        .contentShape(RoundedRectangle(cornerRadius: AyuvoPalette.cardRadius, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: [title, label, [value, unit].filter { !$0.isEmpty }.joined(separator: " "), detail, trailing]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")))
        .accessibilityAddTraits(.isButton)
    }
}

struct SummaryTileChartView: View {
    let chart: SummaryTileChart
    let tint: Color

    var body: some View {
        switch chart {
        case .none:
            EmptyView()
        case .bars(let values):
            MiniBarChart(values: Array(values.suffix(7)), tint: tint)
                .frame(width: 92, height: 44)
        case .dots(let values):
            MiniDotChart(values: Array(values.suffix(7)), tint: tint)
                .frame(width: 92, height: 44)
        case .ring(let progress, let text):
            ZStack {
                ActivityRingView(progress: progress, ringWidth: 7, gradientColors: [tint, tint], showsEndCap: false)
                if let text {
                    Text(verbatim: text)
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .padding(.horizontal, 8)
                }
            }
            .frame(width: 56, height: 56)
        }
    }
}

/// Seven-day bars, newest in the tint, the rest grey. Zero days keep a short stub so the week reads.
struct MiniBarChart: View {
    let values: [Double]
    let tint: Color

    var body: some View {
        GeometryReader { geo in
            let count = max(values.count, 1)
            let spacing: CGFloat = 4
            let width = min(11, (geo.size.width - spacing * CGFloat(count - 1)) / CGFloat(count))
            let top = max(values.max() ?? 0, 0)
            HStack(alignment: .bottom, spacing: spacing) {
                ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                    let fraction = top > 0 ? max(value, 0) / top : 0
                    RoundedRectangle(cornerRadius: 2.5, style: .continuous)
                        .fill(index == values.count - 1 ? tint : Color(.systemGray3))
                        .frame(width: width, height: max(5, geo.size.height * fraction))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .bottomTrailing)
        }
        .accessibilityHidden(true)
    }
}

/// One dot per reading on a faint column, newest in the tint: reads like a range, not a line.
struct MiniDotChart: View {
    let values: [Double]
    let tint: Color

    var body: some View {
        GeometryReader { geo in
            let count = max(values.count, 1)
            let dot: CGFloat = 7
            let spacing = count > 1 ? (geo.size.width - dot) / CGFloat(count - 1) : 0
            let low = values.min() ?? 0
            let high = values.max() ?? 0
            let span = high - low
            ZStack(alignment: .topLeading) {
                ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                    let fraction = span > 0 ? (value - low) / span : 0.5
                    let y = (geo.size.height - dot) * (1 - fraction)
                    let x = count > 1 ? CGFloat(index) * spacing : (geo.size.width - dot) / 2
                    Capsule()
                        .fill(Color(.systemGray5))
                        .frame(width: 3, height: geo.size.height)
                        .offset(x: x + (dot - 3) / 2)
                    Circle()
                        .fill(index == values.count - 1 ? tint : Color(.systemGray2))
                        .frame(width: dot, height: dot)
                        .offset(x: x, y: y)
                }
            }
        }
        .accessibilityHidden(true)
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

// MARK: - Collapsing stack

/// A leading-aligned vertical stack that puts `spacing` only between children with a height. Cards that hide
/// themselves but keep a zero-height container (so their loading task still runs) add no gap.
struct CollapsingVStack: Layout {
    var spacing: CGFloat

    private func heights(_ subviews: Subviews, width: CGFloat?) -> [CGFloat] {
        subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        var width: CGFloat = 0
        for s in subviews { width = max(width, s.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)).width) }
        let visible = heights(subviews, width: proposal.width).filter { $0 > 0 }
        let height = visible.reduce(0, +) + spacing * CGFloat(max(0, visible.count - 1))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        var placedAny = false
        for (subview, height) in zip(subviews, heights(subviews, width: bounds.width)) {
            if height > 0 {
                if placedAny { y += spacing }
                placedAny = true
            }
            subview.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: height))
            y += height
        }
    }
}
