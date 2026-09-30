import ActivityKit
import AppIntents
import Foundation

// Compiled into BOTH the app and FudAIWidgetsExtension (synchronized-folder membership exception in
// project.pbxproj). Keep it free of app-only types: the widget only renders the state, and the
// Live Activity buttons' intents run in the app process, where `WorkoutLiveActivityBridge.handler`
// is installed by the recorder.

/// Live Activity for a GPS workout or a strength session.
nonisolated struct WorkoutActivityAttributes: ActivityAttributes {
    nonisolated struct ContentState: Codable, Hashable, Sendable {
        /// "gps" or "strength".
        var kind: String
        /// "walk", "run", "cycle", "hike" or "strength".
        var sport: String
        /// "running", "paused" or "recovery" (collecting heart-rate recovery after End).
        var state: String
        /// Running: elapsed = now − timerStart (pauses already shifted out), for `Text(timerInterval:)`.
        var timerStart: Date
        /// Frozen elapsed seconds while paused.
        var pausedElapsed: Double?
        var distanceM: Double?
        var paceSecondsPerKm: Double?
        var speedMps: Double?
        var heartRate: Int?
        var lapCount: Int = 0
        /// "watch" when the metrics come from a mirrored Apple Watch workout.
        var source: String?

        var isPaused: Bool { state == "paused" }
        var isGPS: Bool { kind == "gps" }
    }

    var title: String
    var useMetric: Bool
}

/// Commands the Live Activity buttons send to the app.
nonisolated enum WorkoutLiveActivityCommand: String, Sendable {
    case pause, resume, lap, end
}

/// Installed by the app; nil inside the widget extension, where the intents do nothing.
@MainActor
enum WorkoutLiveActivityBridge {
    static var handler: ((WorkoutLiveActivityCommand) async -> Void)?

    static func send(_ command: WorkoutLiveActivityCommand) async {
        await handler?(command)
    }
}

struct WorkoutPauseIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Pause Workout"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await WorkoutLiveActivityBridge.send(.pause)
        return .result()
    }
}

struct WorkoutResumeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Resume Workout"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await WorkoutLiveActivityBridge.send(.resume)
        return .result()
    }
}

struct WorkoutLapIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Mark Lap"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await WorkoutLiveActivityBridge.send(.lap)
        return .result()
    }
}

struct WorkoutEndIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "End Workout"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await WorkoutLiveActivityBridge.send(.end)
        return .result()
    }
}
