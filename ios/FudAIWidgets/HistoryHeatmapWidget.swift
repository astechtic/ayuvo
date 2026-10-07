import SwiftUI
import WidgetKit

/// Workout history and Food history (docs/widgets.md "History widgets"): one square per day, the
/// last column is this week, as many weeks as fit. Levels come from the app's snapshot; days it does
/// not cover (after midnight, before the app ran again) are empty. Nothing is invented.
struct HistoryHeatmapEntry: TimelineEntry {
    let date: Date
    let snapshot: HistoryHeatmapSnapshot?
}

struct HistoryHeatmapProvider: TimelineProvider {
    func placeholder(in context: Context) -> HistoryHeatmapEntry {
        HistoryHeatmapEntry(date: Date(), snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (HistoryHeatmapEntry) -> Void) {
        completion(HistoryHeatmapEntry(date: Date(), snapshot: HistoryHeatmapSnapshot.read()))
    }

    /// Now and next midnight (the grid moves to the new day on its own); the app reloads on changes.
    func getTimeline(in context: Context, completion: @escaping (Timeline<HistoryHeatmapEntry>) -> Void) {
        let now = Date()
        let snapshot = HistoryHeatmapSnapshot.read()
        var entries = [HistoryHeatmapEntry(date: now, snapshot: snapshot)]
        let calendar = Calendar.current
        if let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) {
            entries.append(HistoryHeatmapEntry(date: midnight, snapshot: snapshot))
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}

struct WorkoutHistoryWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: HistoryHeatmapSnapshot.workoutKind, provider: HistoryHeatmapProvider()) { entry in
            HistoryHeatmapView(kind: .workout, entry: entry)
                .widgetURL(HistoryHeatmapStyle.url(.workout))
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Workout History")
        .description("Every day of training at a glance, shaded by time trained.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct FoodHistoryWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: HistoryHeatmapSnapshot.foodKind, provider: HistoryHeatmapProvider()) { entry in
            HistoryHeatmapView(kind: .food, entry: entry)
                .widgetURL(HistoryHeatmapStyle.url(.food))
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Food History")
        .description("Every day of food logging at a glance, shaded by meals logged.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

enum HistoryHeatmapStyle {
    static let empty = Color.gray.opacity(0.22)
    static let opacities: [Double] = [0.32, 0.55, 0.78, 1]

    static func tint(_ kind: HistoryHeatmapSnapshot.Kind) -> Color {
        kind == .workout ? DashboardPalette.move : DashboardPalette.eat
    }

    static func fill(level: Int, tint: Color) -> Color {
        guard level > 0 else { return empty }
        return tint.opacity(opacities[min(level, opacities.count) - 1])
    }

    static func url(_ kind: HistoryHeatmapSnapshot.Kind) -> URL {
        kind == .workout ? URL(string: "ayuvo://metric/app:workout_minutes")! : URL(string: "ayuvo://open/nutrition")!
    }

    static func title(_ kind: HistoryHeatmapSnapshot.Kind) -> String {
        kind == .workout ? String(localized: "Workouts") : String(localized: "Food log")
    }

    static func byline(_ kind: HistoryHeatmapSnapshot.Kind) -> String {
        kind == .workout ? String(localized: "by time trained") : String(localized: "by meals logged")
    }

    static func legend(_ kind: HistoryHeatmapSnapshot.Kind) -> (less: String, more: String) {
        kind == .workout
            ? (String(localized: "Less time"), String(localized: "More time"))
            : (String(localized: "Fewer meals"), String(localized: "More meals"))
    }

    static func systemImage(_ kind: HistoryHeatmapSnapshot.Kind) -> String {
        kind == .workout ? "figure.run" : "fork.knife"
    }
}

/// Cell size, column count and offsets for a content size.
struct HistoryHeatmapLayout {
    static let gapRatio: CGFloat = 0.28
    static let monthLabelHeight: CGFloat = 11

    let cell: CGFloat
    let gap: CGFloat
    let columns: Int
    let originX: CGFloat
    let gridHeight: CGFloat

    init(width: CGFloat, height: CGFloat) {
        let gridHeight = max(14, height - Self.monthLabelHeight)
        let cell = gridHeight / (7 + 6 * Self.gapRatio)
        let gap = cell * Self.gapRatio
        let columns = max(1, min(53, Int((width + gap) / (cell + gap))))
        self.cell = cell
        self.gap = gap
        self.columns = columns
        self.gridHeight = gridHeight
        originX = max(0, (width - (CGFloat(columns) * (cell + gap) - gap)) / 2)
    }

    /// Whole months the grid reaches back, at least 1.
    var months: Int { max(1, Int((Double(columns * 7) / 30.44).rounded())) }
}

struct HistoryHeatmapView: View {
    @Environment(\.widgetFamily) private var family
    let kind: HistoryHeatmapSnapshot.Kind
    let entry: HistoryHeatmapEntry

    private var tint: Color { HistoryHeatmapStyle.tint(kind) }
    private var isSmall: Bool { family == .systemSmall }

    var body: some View {
        if let snapshot = entry.snapshot {
            GeometryReader { geo in
                let headerHeight: CGFloat = 16
                let legendHeight: CGFloat = isSmall ? 0 : 12
                let spacing: CGFloat = 5
                let canvasHeight = geo.size.height - headerHeight - legendHeight - spacing * (isSmall ? 1 : 2)
                let layout = HistoryHeatmapLayout(width: geo.size.width, height: canvasHeight)
                VStack(alignment: .leading, spacing: spacing) {
                    header(months: layout.months)
                        .frame(height: headerHeight)
                    HistoryHeatmapGrid(snapshot: snapshot, kind: kind, today: entry.date, layout: layout, tint: tint)
                        .frame(width: geo.size.width, height: canvasHeight)
                    if !isSmall {
                        legend.frame(height: legendHeight)
                    }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText(snapshot))
        } else {
            VStack(spacing: 6) {
                Image(systemName: HistoryHeatmapStyle.systemImage(kind))
                    .font(.title2)
                    .foregroundStyle(tint)
                Text("Open Ayuvo to see your history")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func header(months: Int) -> some View {
        if isSmall {
            Label(HistoryHeatmapStyle.title(kind), systemImage: HistoryHeatmapStyle.systemImage(kind))
                .font(.system(.caption, design: .rounded, weight: .bold))
                .foregroundStyle(tint)
                .lineLimit(1)
        } else {
            (Text(HistoryHeatmapStyle.title(kind)).foregroundStyle(.primary)
                + Text(verbatim: " — ").foregroundStyle(.secondary)
                + Text("last \(months) months").foregroundStyle(.secondary)
                + Text(verbatim: " · ").foregroundStyle(.tertiary)
                + Text(HistoryHeatmapStyle.byline(kind)).foregroundStyle(.tertiary))
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }

    private var legend: some View {
        let words = HistoryHeatmapStyle.legend(kind)
        return HStack(spacing: 3) {
            Spacer(minLength: 0)
            Text(words.less)
            RoundedRectangle(cornerRadius: 2).fill(HistoryHeatmapStyle.empty).frame(width: 9, height: 9)
            ForEach(1...HistoryHeatmap.levels, id: \.self) { level in
                RoundedRectangle(cornerRadius: 2)
                    .fill(HistoryHeatmapStyle.fill(level: level, tint: tint))
                    .frame(width: 9, height: 9)
            }
            Text(words.more)
        }
        .font(.system(size: 10, weight: .medium, design: .rounded))
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }

    private func accessibilityText(_ snapshot: HistoryHeatmapSnapshot) -> Text {
        let days = snapshot.series(kind).activeDays
        return kind == .workout
            ? Text("Workout history: \(days) days with a workout in the last year")
            : Text("Food history: \(days) days with food logged in the last year")
    }
}

/// Month labels and the day squares, drawn in one Canvas.
struct HistoryHeatmapGrid: View {
    let snapshot: HistoryHeatmapSnapshot
    let kind: HistoryHeatmapSnapshot.Kind
    let today: Date
    let layout: HistoryHeatmapLayout
    let tint: Color

    var body: some View {
        Canvas { context, _ in
            let calendar = Calendar.current
            let grid = HistoryHeatmap.grid(today: today, weekStart: snapshot.weekStart, columns: layout.columns, calendar: calendar)
            let pitch = layout.cell + layout.gap
            let top = HistoryHeatmapLayout.monthLabelHeight
            let radius = layout.cell * 0.22
            let todayStart = calendar.startOfDay(for: today)
            var labelEnd: CGFloat = -.greatestFiniteMagnitude

            for column in 0..<grid.columns {
                let x = layout.originX + CGFloat(column) * pitch
                guard let columnStart = calendar.date(byAdding: .day, value: column * 7, to: grid.firstDay) else { continue }

                // A month label above the column holding the 1st of a month (and the first column).
                var labelDate: Date?
                // The first column is labelled only when the next month's label is not right next to it.
                if column == 0, calendar.component(.day, from: columnStart) <= 15 { labelDate = columnStart }
                for row in 0..<7 {
                    if let day = calendar.date(byAdding: .day, value: row, to: columnStart), day <= todayStart,
                       calendar.component(.day, from: day) == 1 {
                        labelDate = day
                    }
                }
                if let labelDate {
                    let label = context.resolve(
                        Text(labelDate.formatted(.dateTime.month(.abbreviated)))
                            .font(.system(size: 9, weight: .medium, design: .rounded))
                            .foregroundStyle(.secondary)
                    )
                    let size = label.measure(in: CGSize(width: 60, height: top))
                    if x >= labelEnd + 3, x + size.width <= layout.originX + CGFloat(grid.columns) * pitch + 2 {
                        context.draw(label, at: CGPoint(x: x, y: 0), anchor: .topLeading)
                        labelEnd = x + size.width
                    }
                }

                for row in 0..<7 {
                    let isLast = column == grid.columns - 1
                    if isLast, row > grid.todayRow { continue }
                    guard let day = calendar.date(byAdding: .day, value: row, to: columnStart) else { continue }
                    let rect = CGRect(x: x, y: top + CGFloat(row) * pitch, width: layout.cell, height: layout.cell)
                    let path = Path(roundedRect: rect, cornerRadius: radius)
                    let level = snapshot.level(kind, on: day, calendar: calendar)
                    context.fill(path, with: .color(HistoryHeatmapStyle.fill(level: level, tint: tint)))
                    if isLast, row == grid.todayRow {
                        let ring = Path(roundedRect: rect.insetBy(dx: -0.5, dy: -0.5), cornerRadius: radius + 0.5)
                        context.stroke(ring, with: .color(tint), lineWidth: 1.4)
                    }
                }
            }
        }
    }
}

#if DEBUG
private extension HistoryHeatmapSnapshot {
    static var preview: HistoryHeatmapSnapshot {
        let calendar = Calendar.current
        let now = Date()
        var workouts: [HistoryHeatmapBuilderPreview.Day] = []
        for back in 0..<HistoryHeatmap.days where back % 3 != 1 && back < 140 {
            workouts.append(.init(back: back, level: (back * 7) % 5))
        }
        let digits = String((0..<HistoryHeatmap.days).map { offset -> Character in
            let back = HistoryHeatmap.days - 1 - offset
            return Character(String(workouts.first { $0.back == back }?.level ?? 0))
        })
        return HistoryHeatmapSnapshot(
            generatedAt: now, lastDay: HistoryHeatmap.dayKey(now, calendar: calendar), weekStart: .monday,
            workout: .init(levels: digits, activeDays: workouts.filter { $0.level > 0 }.count, totalSeconds: 0),
            food: .init(levels: digits, activeDays: workouts.filter { $0.level > 0 }.count, totalSeconds: nil)
        )
    }
}

private enum HistoryHeatmapBuilderPreview {
    struct Day { let back: Int; let level: Int }
}

#Preview("Workout history medium", as: .systemMedium) {
    WorkoutHistoryWidget()
} timeline: {
    HistoryHeatmapEntry(date: .now, snapshot: .preview)
    HistoryHeatmapEntry(date: .now, snapshot: nil)
}

#Preview("Food history small", as: .systemSmall) {
    FoodHistoryWidget()
} timeline: {
    HistoryHeatmapEntry(date: .now, snapshot: .preview)
}
#endif
