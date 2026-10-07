import AppIntents
import Foundation

// Workout widget state (docs/widgets.md "Workout widget"). The app writes it whenever the running
// GPS workout or strength session changes (`WorkoutWidgetPublisher`); the widget only reads it.
// This file has a byte-identical copy at FudAIWidgets/Shared/WorkoutWidgetState.swift;
// `WidgetSharedCopiesTests` fails when they differ.

/// What the Workout widget can start: the four GPS sports of `workout_config.json` and a strength session.
nonisolated enum WorkoutWidgetSport: String, CaseIterable, Codable, Sendable {
    case walk, run, cycle, hike, strength

    var isGPS: Bool { self != .strength }

    var title: String {
        switch self {
        case .walk: String(localized: "Walk")
        case .run: String(localized: "Run")
        case .cycle: String(localized: "Cycle")
        case .hike: String(localized: "Hike")
        case .strength: String(localized: "Strength")
        }
    }

    var systemImage: String {
        switch self {
        case .walk: "figure.walk"
        case .run: "figure.run"
        case .cycle: "figure.outdoor.cycle"
        case .hike: "figure.hiking"
        case .strength: "dumbbell.fill"
        }
    }

    /// `ayuvo://workout/start?sport=<id>`: opens Ayuvo and starts this workout.
    var startURL: URL { URL(string: "ayuvo://workout/start?sport=\(rawValue)")! }

    /// `ayuvo://workout/open`: opens the running workout (or the workout log when none runs).
    static let openURL = URL(string: "ayuvo://workout/open")!
}

/// The running workout as the widget shows it. Nil on disk (no file) means no workout is running.
nonisolated struct WorkoutWidgetState: Codable, Equatable, Sendable {
    static let currentVersion = 1
    static let widgetKind = "WorkoutWidget"
    /// Lock Screen "Start workout" circle (docs/widgets.md "Lock Screen starters"); shows the running timer too.
    static let startWidgetKind = "WorkoutStartWidget"
    /// Lock Screen "Start Walk" circle (the same circle fixed to Walk).
    static let startWalkWidgetKind = "WorkoutStartWalkWidget"
    /// Older than this, the state is left over from a process that died without clearing it.
    static let staleAfter: TimeInterval = 24 * 3600

    enum Phase: String, Codable, Sendable {
        case recording, paused, recovery
    }

    var version: Int = currentVersion
    var sport: WorkoutWidgetSport
    var phase: Phase
    /// While recording: elapsed = now − timerStart (pauses already shifted out), for `Text(timerInterval:)`.
    var timerStart: Date
    /// Frozen elapsed seconds while paused or measuring recovery.
    var pausedElapsed: Double?
    var distanceM: Double?
    var paceSecondsPerKm: Double?
    var speedMps: Double?
    var lapCount: Int = 0
    /// True when the metrics come from a mirrored Apple Watch workout.
    var fromWatch: Bool = false
    var useMetric: Bool
    var updatedAt: Date

    var isGPS: Bool { sport.isGPS }

    /// Maps the Live Activity state (the single source both surfaces share) to the widget state.
    init(activity: WorkoutActivityAttributes.ContentState, useMetric: Bool, updatedAt: Date = Date()) {
        let gps = activity.kind == "gps"
        let sport = WorkoutWidgetSport(rawValue: activity.sport)
        self.sport = gps ? (sport.flatMap { $0.isGPS ? $0 : nil } ?? .run) : .strength
        switch activity.state {
        case "paused": phase = .paused
        case "recovery": phase = .recovery
        default: phase = .recording
        }
        timerStart = activity.timerStart
        pausedElapsed = activity.pausedElapsed
        distanceM = gps ? activity.distanceM : nil
        paceSecondsPerKm = gps ? activity.paceSecondsPerKm : nil
        speedMps = gps ? activity.speedMps : nil
        lapCount = gps ? activity.lapCount : 0
        fromWatch = activity.source == "watch"
        self.useMetric = useMetric
        self.updatedAt = updatedAt
    }

    /// A timer that ticks on its own (recording) or a frozen value (paused, recovery).
    var isTicking: Bool { phase == .recording || pausedElapsed == nil }

    func elapsed(at now: Date) -> Double {
        if !isTicking, let pausedElapsed { return max(0, pausedElapsed) }
        return max(0, now.timeIntervalSince(timerStart))
    }

    func isStale(at now: Date) -> Bool { now.timeIntervalSince(updatedAt) > Self.staleAfter }

    /// Whether a new state must be written now: at once when what the buttons act on changes,
    /// otherwise at most every `interval` seconds for distance and pace.
    static func needsWrite(previous: WorkoutWidgetState?, next: WorkoutWidgetState, interval: TimeInterval = 30) -> Bool {
        guard let previous else { return true }
        if previous.sport != next.sport || previous.phase != next.phase || previous.lapCount != next.lapCount
            || previous.fromWatch != next.fromWatch || abs(previous.timerStart.timeIntervalSince(next.timerStart)) >= 1 {
            return true
        }
        return next.updatedAt.timeIntervalSince(previous.updatedAt) >= interval
    }

    // MARK: - Formatting (the widget shows these; the app's own screens use WorkoutFormat)

    static func duration(_ seconds: Double) -> String {
        let total = max(0, Int(seconds))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    var distanceText: String {
        guard let distanceM else { return "—" }
        let value = (useMetric ? distanceM / 1000 : distanceM / 1609.344).formatted(.number.precision(.fractionLength(2)))
        return useMetric
            ? String(localized: "\(value) km", comment: "Workout widget distance in kilometres")
            : String(localized: "\(value) mi", comment: "Workout widget distance in miles")
    }

    /// Pace for walk, run and hike; speed for cycling.
    var paceText: String {
        if sport == .cycle {
            guard let speedMps, speedMps.isFinite, speedMps >= 0 else { return "—" }
            let value = (useMetric ? speedMps * 3.6 : speedMps * 2.236936).formatted(.number.precision(.fractionLength(1)))
            return useMetric
                ? String(localized: "\(value) km/h", comment: "Workout widget speed in km per hour")
                : String(localized: "\(value) mph", comment: "Workout widget speed in miles per hour")
        }
        guard let paceSecondsPerKm, paceSecondsPerKm.isFinite, paceSecondsPerKm > 0, paceSecondsPerKm < 3600 else { return "—" }
        return Self.duration(useMetric ? paceSecondsPerKm : paceSecondsPerKm * 1.609344) + (useMetric ? " /km" : " /mi")
    }

    // MARK: - Storage (App Group file)

    private static let fileName = "workout_widget_v1.json"

    @MainActor
    private static var fileURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: WidgetSnapshot.appGroupID)?
            .appendingPathComponent("Library/Application Support/AyuvoWidgets", isDirectory: true)
            .appendingPathComponent(fileName, isDirectory: false)
    }

    /// The running workout, or nil when none runs (no file, unreadable, newer format or stale).
    @MainActor
    static func read(now: Date = Date()) -> WorkoutWidgetState? {
        guard let url = fileURL, let data = try? Data(contentsOf: url),
              let state = decode(data), !state.isStale(at: now) else { return nil }
        return state
    }

    static func decode(_ data: Data) -> WorkoutWidgetState? {
        guard let state = try? JSONDecoder().decode(WorkoutWidgetState.self, from: data),
              state.version <= currentVersion
        else { return nil }
        return state
    }

    @MainActor
    static func write(_ state: WorkoutWidgetState) {
        guard let fileURL, let data = try? JSONEncoder().encode(state) else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    @MainActor
    static func clear() {
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
    }
}

extension WorkoutWidgetSport: AppEnum {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Workout"

    static let caseDisplayRepresentations: [WorkoutWidgetSport: DisplayRepresentation] = [
        .walk: DisplayRepresentation(title: "Walk", image: .init(systemName: "figure.walk")),
        .run: DisplayRepresentation(title: "Run", image: .init(systemName: "figure.run")),
        .cycle: DisplayRepresentation(title: "Cycle", image: .init(systemName: "figure.outdoor.cycle")),
        .hike: DisplayRepresentation(title: "Hike", image: .init(systemName: "figure.hiking")),
        .strength: DisplayRepresentation(title: "Strength", image: .init(systemName: "dumbbell.fill")),
    ]
}

/// Workout widget start button (small size, where only buttons can have their own tap target).
/// Starting GPS needs When-In-Use location, so the app comes to the foreground; like the Quick Log
/// buttons, the intent leaves the route in the App Group and the app starts the workout when it
/// consumes it (`WidgetRouteCoordinator`).
struct StartWorkoutFromWidgetIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Workout from Widget"
    static var openAppWhenRun = true
    static var isDiscoverable = false

    @Parameter(title: LocalizedStringResource("Workout", comment: "Workout widget intent parameter: which workout to start"), default: .run)
    var sport: WorkoutWidgetSport

    init() {}

    init(sport: WorkoutWidgetSport) {
        self.sport = sport
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        WidgetPendingRoute.write(.startWorkout(sport))
        NotificationCenter.default.post(name: .widgetRouteRequested, object: nil)
        return .result()
    }
}
