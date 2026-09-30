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
                .activityBackgroundTint(Color.black.opacity(0.75))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label {
                        Text(context.attributes.title).font(.caption.weight(.semibold)).lineLimit(1)
                    } icon: {
                        Image(systemName: WorkoutActivityStyle.icon(state.sport)).foregroundStyle(WorkoutActivityStyle.accent)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    WorkoutElapsedText(state: state)
                        .font(.title3.monospacedDigit().weight(.semibold))
                        .multilineTextAlignment(.trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        WorkoutMetricsRow(state: state, useMetric: context.attributes.useMetric)
                        WorkoutControlsRow(state: state)
                    }
                }
            } compactLeading: {
                Image(systemName: WorkoutActivityStyle.icon(state.sport)).foregroundStyle(WorkoutActivityStyle.accent)
            } compactTrailing: {
                WorkoutElapsedText(state: state)
                    .monospacedDigit()
                    .frame(maxWidth: 56)
            } minimal: {
                Image(systemName: state.isPaused ? "pause.fill" : WorkoutActivityStyle.icon(state.sport))
                    .foregroundStyle(WorkoutActivityStyle.accent)
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

private struct WorkoutMetricsRow: View {
    let state: WorkoutActivityAttributes.ContentState
    let useMetric: Bool

    var body: some View {
        HStack(spacing: 12) {
            if state.isGPS {
                metric("Distance", WorkoutActivityStyle.distance(state.distanceM, metric: useMetric))
                if state.sport == "cycle" {
                    metric("Speed", WorkoutActivityStyle.speed(state.speedMps, metric: useMetric))
                } else {
                    metric("Pace", WorkoutActivityStyle.pace(state.paceSecondsPerKm, metric: useMetric))
                }
            }
            metric("Heart", state.heartRate.map { String(localized: "\($0) bpm", comment: "Workout Live Activity heart rate value") } ?? "--")
            if state.source == "watch" {
                Image(systemName: "applewatch").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func metric(_ title: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.subheadline.monospacedDigit().weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct WorkoutControlsRow: View {
    let state: WorkoutActivityAttributes.ContentState

    var body: some View {
        if state.state == "recovery" {
            Text("Measuring heart-rate recovery…").font(.caption).foregroundStyle(.secondary)
        } else {
            HStack(spacing: 8) {
                if state.isGPS {
                    if state.isPaused {
                        Button(intent: WorkoutResumeIntent()) { Label("Resume", systemImage: "play.fill") }
                            .tint(WorkoutActivityStyle.accent)
                    } else {
                        Button(intent: WorkoutPauseIntent()) { Label("Pause", systemImage: "pause.fill") }
                            .tint(.orange)
                    }
                    Button(intent: WorkoutLapIntent()) { Label("Lap", systemImage: "flag.fill") }
                        .tint(.blue)
                }
                Button(intent: WorkoutEndIntent()) {
                    Label(state.isGPS ? "End" : "Finish", systemImage: "stop.fill")
                }
                .tint(.red)
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(.borderedProminent)
            .labelStyle(.titleAndIcon)
        }
    }
}

private struct WorkoutLockScreenView: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: WorkoutActivityStyle.icon(state.sport)).foregroundStyle(WorkoutActivityStyle.accent)
                Text(attributes.title).font(.headline).foregroundStyle(.white)
                if state.isPaused {
                    Text("Paused").font(.caption.weight(.bold)).foregroundStyle(.orange)
                }
                Spacer()
                WorkoutElapsedText(state: state)
                    .font(.title2.monospacedDigit().weight(.bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.trailing)
            }
            WorkoutMetricsRow(state: state, useMetric: attributes.useMetric)
                .foregroundStyle(.white)
            WorkoutControlsRow(state: state)
        }
        .padding(14)
    }
}
