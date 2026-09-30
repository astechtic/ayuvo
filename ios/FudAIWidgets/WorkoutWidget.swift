import AppIntents
import SwiftUI
import WidgetKit

/// Workout (docs/widgets.md "Workout widget"): start buttons when nothing runs; the running GPS
/// workout or strength session with its controls otherwise. Starts open Ayuvo (GPS needs When-In-Use
/// location in the foreground); Pause / Resume / Lap / End reuse the Live Activity intents, which run
/// in the app process. The timer ticks on its own (`Text(timerInterval:)`), so the app only reloads
/// the widget on phase changes and every ~30 s for distance.
struct WorkoutWidgetEntry: TimelineEntry {
    let date: Date
    /// Nil when no workout runs.
    let state: WorkoutWidgetState?
}

struct WorkoutWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> WorkoutWidgetEntry {
        WorkoutWidgetEntry(date: Date(), state: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (WorkoutWidgetEntry) -> Void) {
        completion(WorkoutWidgetEntry(date: Date(), state: WorkoutWidgetState.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WorkoutWidgetEntry>) -> Void) {
        let now = Date()
        let entry = WorkoutWidgetEntry(date: now, state: WorkoutWidgetState.read(now: now))
        // The app reloads on every change; the fallback only catches a state gone stale.
        completion(Timeline(entries: [entry], policy: .after(now.addingTimeInterval(30 * 60))))
    }
}

struct WorkoutWidget: Widget {
    let kind = WorkoutWidgetState.widgetKind

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WorkoutWidgetProvider()) { entry in
            WorkoutWidgetView(entry: entry)
                .widgetURL(WorkoutWidgetSport.openURL)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Workout")
        .description("Start a walk, run, ride, hike or strength session, then pause, mark laps and finish from your Home Screen.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
    }
}

enum WorkoutWidgetStyle {
    static let accent = DashboardPalette.move
    static let resume = Color(dashboardHex: "#34C759")
    static let lap = Color(dashboardHex: "#007AFF")
    static let end = Color(dashboardHex: "#FF3B30")

    static func phaseText(_ state: WorkoutWidgetState) -> String {
        switch state.phase {
        case .recording: return state.isGPS ? String(localized: "Recording") : String(localized: "In progress")
        case .paused: return String(localized: "Paused")
        case .recovery: return String(localized: "Measuring recovery")
        }
    }

    static func phaseTint(_ state: WorkoutWidgetState) -> Color {
        switch state.phase {
        case .recording: return accent
        case .paused: return .orange
        case .recovery: return .secondary
        }
    }
}

struct WorkoutWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WorkoutWidgetEntry

    var body: some View {
        switch family {
        case .accessoryRectangular:
            WorkoutAccessoryView(state: entry.state)
        case .systemMedium:
            if let state = entry.state { WorkoutActiveMedium(state: state) } else { WorkoutStartMedium() }
        default:
            if let state = entry.state { WorkoutActiveSmall(state: state) } else { WorkoutStartSmall() }
        }
    }
}

// MARK: - Timer

/// Ticks while recording; a frozen value while paused or measuring recovery.
private struct WorkoutWidgetTimer: View {
    let state: WorkoutWidgetState

    var body: some View {
        if state.isTicking {
            Text(timerInterval: state.timerStart...Date.distantFuture, countsDown: false)
        } else {
            Text(WorkoutWidgetState.duration(state.elapsed(at: state.updatedAt)))
        }
    }
}

// MARK: - Start (idle)

private struct WorkoutStartTile: View {
    let sport: WorkoutWidgetSport
    let compact: Bool

    var body: some View {
        VStack(spacing: compact ? 2 : 6) {
            Image(systemName: sport.systemImage)
                .font(.system(size: compact ? 17 : 20, weight: .semibold))
                .foregroundStyle(WorkoutWidgetStyle.accent)
            Text(sport.title)
                .font(.system(compact ? .caption2 : .caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WorkoutWidgetStyle.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("Start \(sport.title)"))
    }
}

/// Small: a 3 + 2 grid of start buttons. A small widget has one link, so each is an intent button
/// that opens Ayuvo (`StartWorkoutFromWidgetIntent`).
private struct WorkoutStartSmall: View {
    private let rows: [[WorkoutWidgetSport]] = [[.walk, .run, .cycle], [.hike, .strength]]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Workout", systemImage: "figure.run")
                .font(.system(.caption, design: .rounded, weight: .bold))
                .foregroundStyle(WorkoutWidgetStyle.accent)
                .lineLimit(1)
            Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                ForEach(rows.indices, id: \.self) { index in
                    GridRow {
                        ForEach(rows[index], id: \.self) { sport in
                            Button(intent: StartWorkoutFromWidgetIntent(sport: sport)) {
                                WorkoutStartTile(sport: sport, compact: true)
                            }
                            .buttonStyle(.plain)
                            .gridCellColumns(index == 1 && sport == .strength ? 2 : 1)
                        }
                    }
                }
            }
        }
    }
}

/// Medium: all five start buttons in one row, one link each.
private struct WorkoutStartMedium: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("Start a workout", systemImage: "figure.run")
                    .font(.system(.subheadline, design: .rounded, weight: .bold))
                    .foregroundStyle(WorkoutWidgetStyle.accent)
                Spacer()
                Text("GPS · Strength")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .lineLimit(1)
            HStack(spacing: 8) {
                ForEach(WorkoutWidgetSport.allCases, id: \.self) { sport in
                    Link(destination: sport.startURL) {
                        WorkoutStartTile(sport: sport, compact: false)
                    }
                }
            }
        }
    }
}

// MARK: - Active

private struct WorkoutHeader: View {
    let state: WorkoutWidgetState

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: state.fromWatch ? "applewatch" : state.sport.systemImage)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(WorkoutWidgetStyle.accent)
            VStack(alignment: .leading, spacing: 0) {
                Text(state.sport.title)
                    .font(.system(.caption, design: .rounded, weight: .bold))
                Text(WorkoutWidgetStyle.phaseText(state))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(WorkoutWidgetStyle.phaseTint(state))
            }
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
    }
}

private enum WorkoutControl {
    case pause, resume, lap, end, finish

    var title: String {
        switch self {
        case .pause: String(localized: "Pause")
        case .resume: String(localized: "Resume")
        case .lap: String(localized: "Lap")
        case .end: String(localized: "End")
        case .finish: String(localized: "Finish")
        }
    }

    var systemImage: String {
        switch self {
        case .pause: "pause.fill"
        case .resume: "play.fill"
        case .lap: "flag.fill"
        case .end, .finish: "stop.fill"
        }
    }

    var tint: Color {
        switch self {
        case .pause: .orange
        case .resume: WorkoutWidgetStyle.resume
        case .lap: WorkoutWidgetStyle.lap
        case .end, .finish: WorkoutWidgetStyle.end
        }
    }

    /// Controls for a state: GPS gets Pause/Resume, Lap and End; strength gets Finish; none while
    /// measuring heart-rate recovery (the workout already ended).
    static func controls(for state: WorkoutWidgetState) -> [WorkoutControl] {
        guard state.phase != .recovery else { return [] }
        guard state.isGPS else { return [.finish] }
        return [state.phase == .paused ? .resume : .pause, .lap, .end]
    }
}

private struct WorkoutControlButton: View {
    let control: WorkoutControl
    var showsTitle = true

    var body: some View {
        Group {
            switch control {
            case .pause: Button(intent: WorkoutPauseIntent()) { label }
            case .resume: Button(intent: WorkoutResumeIntent()) { label }
            case .lap: Button(intent: WorkoutLapIntent()) { label }
            case .end, .finish: Button(intent: WorkoutEndIntent()) { label }
            }
        }
        .buttonStyle(.plain)
    }

    private var label: some View {
        HStack(spacing: 4) {
            Image(systemName: control.systemImage)
            if showsTitle { Text(control.title).lineLimit(1).minimumScaleFactor(0.7) }
        }
        .font(.system(.caption, design: .rounded, weight: .bold))
        .foregroundStyle(control.tint)
        .frame(maxWidth: .infinity, minHeight: 32)
        .background(control.tint.opacity(0.15), in: Capsule())
        .accessibilityLabel(Text(control.title))
    }
}

/// Small: header, timer, distance (GPS) and the primary control (Pause / Resume, or Finish).
private struct WorkoutActiveSmall: View {
    let state: WorkoutWidgetState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            WorkoutHeader(state: state)
            Spacer(minLength: 0)
            WorkoutWidgetTimer(state: state)
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if state.isGPS {
                Text(state.distanceText)
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            if let primary = WorkoutControl.controls(for: state).first {
                WorkoutControlButton(control: primary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Medium: header and timer on the left, distance and pace (GPS) on the right, all controls below.
private struct WorkoutActiveMedium: View {
    let state: WorkoutWidgetState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    WorkoutHeader(state: state)
                    WorkoutWidgetTimer(state: state)
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                Spacer(minLength: 8)
                if state.isGPS {
                    VStack(alignment: .trailing, spacing: 4) {
                        metric(String(localized: "Distance"), state.distanceText)
                        metric(state.sport == .cycle ? String(localized: "Speed") : String(localized: "Pace"), state.paceText)
                        if state.lapCount > 0 {
                            Text("Lap \(state.lapCount + 1)")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            let controls = WorkoutControl.controls(for: state)
            if controls.isEmpty {
                Text("Measuring heart-rate recovery…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 32)
            } else {
                HStack(spacing: 8) {
                    ForEach(controls, id: \.self) { WorkoutControlButton(control: $0) }
                }
            }
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value)
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }
}

// MARK: - Lock Screen

/// Lock Screen rectangle: the running workout (tap opens it), or a start hint that opens the log.
private struct WorkoutAccessoryView: View {
    let state: WorkoutWidgetState?

    var body: some View {
        if let state {
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Image(systemName: state.sport.systemImage)
                    Text(state.sport.title).lineLimit(1)
                    if state.phase != .recording {
                        Text(WorkoutWidgetStyle.phaseText(state))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                WorkoutWidgetTimer(state: state)
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .widgetAccentable()
                if state.isGPS {
                    Text("\(state.distanceText) · \(state.paceText)")
                        .font(.system(size: 12, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Label("Workout", systemImage: "figure.run")
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .widgetAccentable()
                Text("Tap to start a walk, run, ride, hike or strength session")
                    .font(.system(size: 12, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

#if DEBUG
private extension WorkoutWidgetState {
    static func preview(_ sport: WorkoutWidgetSport, _ phase: Phase) -> WorkoutWidgetState {
        WorkoutWidgetState(
            activity: WorkoutActivityAttributes.ContentState(
                kind: sport.isGPS ? "gps" : "strength", sport: sport.rawValue,
                state: phase == .recording ? "running" : phase.rawValue,
                timerStart: Date().addingTimeInterval(-1_724), pausedElapsed: phase == .recording ? nil : 1_724,
                distanceM: 4_215, paceSecondsPerKm: 342, speedMps: 2.9, lapCount: 2
            ),
            useMetric: true
        )
    }
}

#Preview("Workout small", as: .systemSmall) {
    WorkoutWidget()
} timeline: {
    WorkoutWidgetEntry(date: .now, state: nil)
    WorkoutWidgetEntry(date: .now, state: .preview(.run, .recording))
    WorkoutWidgetEntry(date: .now, state: .preview(.strength, .recording))
}

#Preview("Workout medium", as: .systemMedium) {
    WorkoutWidget()
} timeline: {
    WorkoutWidgetEntry(date: .now, state: nil)
    WorkoutWidgetEntry(date: .now, state: .preview(.run, .recording))
    WorkoutWidgetEntry(date: .now, state: .preview(.cycle, .paused))
    WorkoutWidgetEntry(date: .now, state: .preview(.strength, .recording))
}

#Preview("Workout lock screen", as: .accessoryRectangular) {
    WorkoutWidget()
} timeline: {
    WorkoutWidgetEntry(date: .now, state: nil)
    WorkoutWidgetEntry(date: .now, state: .preview(.run, .recording))
}
#endif
