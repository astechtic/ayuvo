import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// Lock Screen and Dynamic Island presentation of a GPS workout or strength session.
/// The buttons run `LiveActivityIntent`s in the app process (see `WorkoutActivityAttributes.swift`).
struct WorkoutLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WorkoutActivityAttributes.self) { context in
            WorkoutLockScreenView(attributes: context.attributes, state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.8))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            let metric = context.attributes.useMetric
            return DynamicIsland {
                // The leading region is narrow beside the camera, so the title scales down and the
                // timer stays compact; the expanded island is capped at 160 pt, hence the smaller body.
                DynamicIslandExpandedRegion(.leading) {
                    WorkoutTitleLabel(title: context.attributes.title, state: state, font: .subheadline.weight(.semibold))
                        .padding(.leading, 4)
                        .layoutPriority(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    WorkoutElapsedText(state: state)
                        .font(.title3.monospacedDigit().weight(.bold))
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 76, alignment: .trailing)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    WorkoutSummaryBody(state: state, useMetric: metric, mapHeight: 68, compact: true)
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                HStack(spacing: 4) {
                    Image(systemName: state.isPaused ? "pause.fill" : WorkoutActivityStyle.icon(state.sport))
                        .foregroundStyle(state.isPaused ? Color.orange : WorkoutActivityStyle.accent)
                    if state.isGPS, let meters = state.distanceM {
                        Text(WorkoutActivityStyle.distance(meters, metric: metric))
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(WorkoutActivityStyle.accent)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                }
                .padding(.leading, 2)
            } compactTrailing: {
                WorkoutElapsedText(state: state)
                    .font(.body.monospacedDigit().weight(.semibold))
                    .foregroundStyle(state.isPaused ? Color.orange : .white)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 58)
            } minimal: {
                Image(systemName: state.isPaused ? "pause.fill" : WorkoutActivityStyle.icon(state.sport))
                    .foregroundStyle(state.isPaused ? Color.orange : WorkoutActivityStyle.accent)
            }
            .keylineTint(WorkoutActivityStyle.accent)
        }
    }
}

enum WorkoutActivityStyle {
    static let accent = Color(red: 0.19, green: 0.82, blue: 0.35)

    static func icon(_ sport: String) -> String {
        switch sport {
        case "walk": return "figure.walk"
        case "run": return "figure.run"
        case "cycle": return "figure.outdoor.cycle"
        case "hike": return "figure.hiking"
        default: return "dumbbell.fill"
        }
    }

    static func duration(_ seconds: Double) -> String {
        let total = max(0, Int(seconds))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    static func distance(_ meters: Double?, metric: Bool) -> String {
        guard let meters else { return "--" }
        let value = (metric ? meters / 1000 : meters / 1609.344).formatted(.number.precision(.fractionLength(2)))
        return metric
            ? String(localized: "\(value) km", comment: "Workout Live Activity distance in kilometres")
            : String(localized: "\(value) mi", comment: "Workout Live Activity distance in miles")
    }

    static func pace(_ secondsPerKm: Double?, metric: Bool) -> String {
        guard let secondsPerKm, secondsPerKm > 0, secondsPerKm < 3600 else { return "--:--" }
        return duration(metric ? secondsPerKm : secondsPerKm * 1.609344) + (metric ? "/km" : "/mi")
    }

    static func speed(_ mps: Double?, metric: Bool) -> String {
        guard let mps else { return "--" }
        let value = (metric ? mps * 3.6 : mps * 2.236936).formatted(.number.precision(.fractionLength(1)))
        return metric
            ? String(localized: "\(value) km/h", comment: "Workout Live Activity speed in km per hour")
            : String(localized: "\(value) mph", comment: "Workout Live Activity speed in miles per hour")
    }
}

private struct WorkoutElapsedText: View {
    let state: WorkoutActivityAttributes.ContentState

    var body: some View {
        if let paused = state.pausedElapsed, state.isPaused || state.state == "recovery" {
            Text(WorkoutActivityStyle.duration(paused))
        } else {
            Text(timerInterval: state.timerStart...Date.distantFuture, countsDown: false)
        }
    }
}

/// Sport icon, workout title and a Paused badge.
private struct WorkoutTitleLabel: View {
    let title: String
    let state: WorkoutActivityAttributes.ContentState
    let font: Font

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: WorkoutActivityStyle.icon(state.sport))
                .foregroundStyle(WorkoutActivityStyle.accent)
            Text(title).font(font).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.75)
            if state.isPaused {
                Text("Paused", comment: "Workout Live Activity badge while the workout is paused")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.orange.opacity(0.18), in: Capsule())
                    .fixedSize()
            }
            if state.source == "watch" {
                Image(systemName: "applewatch").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// Route map on the leading side (GPS with a route); metric columns and the controls beside it.
/// Kept within the Lock Screen's 160 pt height limit.
private struct WorkoutSummaryBody: View {
    let state: WorkoutActivityAttributes.ContentState
    let useMetric: Bool
    let mapHeight: CGFloat
    /// The expanded island: tighter spacing and smaller buttons so the body fits its height.
    var compact = false

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            if state.isGPS, let route = state.route, route.count >= 2 {
                WorkoutRouteMap(route: route, paused: state.isPaused, lapCount: state.lapCount)
                    .frame(width: mapHeight * 1.25, height: mapHeight)
                    .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            VStack(alignment: .leading, spacing: compact ? 6 : 10) {
                HStack(alignment: .top, spacing: 8) {
                    ForEach(Array(columns.enumerated()), id: \.offset) { _, column in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(column.title)
                                .font(.caption2)
                                .foregroundStyle(column.accent ? WorkoutActivityStyle.accent : .secondary)
                                .lineLimit(1)
                            Text(column.value)
                                .font(.subheadline.monospacedDigit().weight(.semibold))
                                .foregroundStyle(column.accent ? WorkoutActivityStyle.accent : .white)
                                .lineLimit(1)
                                .minimumScaleFactor(0.65)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                WorkoutControlsRow(state: state, size: compact ? 30 : 36)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private struct Column {
        let title: String
        let value: String
        var accent = false
    }

    private var columns: [Column] {
        var out: [Column] = []
        if state.isGPS {
            out.append(Column(title: String(localized: "Distance", comment: "Workout Live Activity metric title"),
                              value: WorkoutActivityStyle.distance(state.distanceM, metric: useMetric)))
            if state.sport == "cycle" {
                out.append(Column(title: String(localized: "Speed", comment: "Workout Live Activity metric title"),
                                  value: WorkoutActivityStyle.speed(state.speedMps, metric: useMetric)))
            } else {
                out.append(Column(title: String(localized: "Pace", comment: "Workout Live Activity metric title"),
                                  value: WorkoutActivityStyle.pace(state.paceSecondsPerKm, metric: useMetric)))
            }
        }
        out.append(Column(title: String(localized: "Heart", comment: "Workout Live Activity metric title"),
                          value: state.heartRate.map { String(localized: "\($0) bpm", comment: "Workout Live Activity heart rate value") } ?? "--"))
        // The last completed lap, like a lap board; the map shows the current lap number.
        if state.isGPS, state.lapCount > 0, let last = state.lapSplits?.last {
            out.append(Column(title: String(localized: "Lap \(state.lapCount)", comment: "Workout Live Activity: time of the last completed lap, by its number"),
                              value: WorkoutActivityStyle.duration(last), accent: true))
        }
        return out
    }
}

/// The route as a track drawing (Live Activities cannot host MapKit): the whole route dim, the
/// current lap bright, a ring at the start and a white dot at the current position.
private struct WorkoutRouteMap: View {
    let route: WorkoutRouteSketch
    let paused: Bool
    let lapCount: Int

    var body: some View {
        GeometryReader { geometry in
            let inset: CGFloat = 10
            let side = max(1, min(geometry.size.width, geometry.size.height) - inset * 2)
            let origin = CGPoint(x: (geometry.size.width - side) / 2, y: (geometry.size.height - side) / 2)
            let place: (Int) -> CGPoint = { i in
                let p = route.point(i)
                return CGPoint(x: origin.x + p.x * side, y: origin.y + p.y * side)
            }
            let lapStart = min(route.lapStart ?? 0, route.count - 1)
            let color = paused ? Color.orange : WorkoutActivityStyle.accent
            ZStack {
                path(0..<route.count, place)
                    .stroke(color.opacity(0.35), style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
                path(lapStart..<route.count, place)
                    .stroke(color, style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
                Circle()
                    .strokeBorder(Color.yellow, lineWidth: 2.5)
                    .background(Circle().fill(Color.black))
                    .frame(width: 9, height: 9)
                    .position(place(0))
                Circle()
                    .fill(Color.white)
                    .frame(width: 9, height: 9)
                    .shadow(color: color.opacity(0.9), radius: 3)
                    .position(place(route.count - 1))
                if lapCount > 0 {
                    Text("Lap \(lapCount + 1)", comment: "Workout Live Activity map: number of the lap in progress")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(color)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(Color.black.opacity(0.55), in: Capsule())
                        .padding(4)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            }
        }
        .accessibilityLabel(Text("Route map", comment: "Workout Live Activity: accessibility label of the route drawing"))
    }

    private func path(_ range: Range<Int>, _ place: (Int) -> CGPoint) -> Path {
        Path { path in
            guard let first = range.first else { return }
            path.move(to: place(first))
            for i in range.dropFirst() { path.addLine(to: place(i)) }
        }
    }
}

/// Pause / Resume and Lap as round buttons on the leading side, End on the trailing side so the
/// destructive action is apart from the frequent ones. Same layout on the Lock Screen and island.
private struct WorkoutControlsRow: View {
    let state: WorkoutActivityAttributes.ContentState
    var size: CGFloat = 36

    var body: some View {
        if state.state == "recovery" {
            HStack(spacing: 6) {
                Image(systemName: "heart.fill").foregroundStyle(.red)
                Text("Measuring heart-rate recovery…").foregroundStyle(.secondary)
            }
            .font(.caption)
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(spacing: 10) {
                if state.isGPS {
                    if state.isPaused {
                        Button(intent: WorkoutResumeIntent()) {
                            round("play.fill", color: WorkoutActivityStyle.accent)
                        }
                        .accessibilityLabel(Text("Resume"))
                    } else {
                        Button(intent: WorkoutPauseIntent()) {
                            round("pause.fill", color: .orange)
                        }
                        .accessibilityLabel(Text("Pause"))
                    }
                    Button(intent: WorkoutLapIntent()) {
                        round("flag.fill", color: .blue)
                    }
                    .accessibilityLabel(Text("Lap"))
                }
                Spacer(minLength: 8)
                Button(intent: WorkoutEndIntent()) {
                    Label(state.isGPS ? "End" : "Finish", systemImage: "stop.fill")
                        .font(.subheadline.weight(.semibold))
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(.white)
                        .padding(.horizontal, size < 36 ? 12 : 14)
                        .frame(height: size)
                        .background(Color.red, in: Capsule())
                }
            }
            .buttonStyle(.plain)
        }
    }

    private func round(_ symbol: String, color: Color) -> some View {
        Image(systemName: symbol)
            .font(.system(size: size < 36 ? 13 : 15, weight: .bold))
            .foregroundStyle(color)
            .frame(width: size, height: size)
            .background(color.opacity(0.22), in: Circle())
    }
}

private struct WorkoutLockScreenView: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                WorkoutTitleLabel(title: attributes.title, state: state, font: .headline)
                Spacer(minLength: 8)
                WorkoutElapsedText(state: state)
                    .font(.title2.monospacedDigit().weight(.bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 120, alignment: .trailing)
            }
            WorkoutSummaryBody(state: state, useMetric: attributes.useMetric, mapHeight: 84)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}
