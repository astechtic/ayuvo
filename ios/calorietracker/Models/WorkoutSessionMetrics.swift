import Foundation

/// Heart-rate statistics for a workout with a real start/end window (`HeartRateWorkout.hrWorkout`).
/// Stored on `StrengthWorkoutSession`; every field is optional in JSON so older builds decode.
struct WorkoutHeartRateSummary: Codable, Equatable, Hashable {
    var avgHr: Double?
    var maxHr: Double?
    /// Share of the window covered by heart-rate samples, 0–100.
    var coveragePct: Double
    /// Seconds in zones 0–5 (index = zone).
    var zoneSeconds: [Double]
    var trimp: Double?
    /// Keytel estimate, present only when coverage reached `keytel_min_coverage`.
    var keytelKcal: Double?
    /// "hrr" or "hrmax".
    var zoneMethod: String
    /// Heart-rate recovery one minute after the end (outdoor workouts with post-workout samples).
    var hrr1: Double?
    var hrr1FlagLow: Bool?

    init(_ result: HeartRateWorkout.WorkoutResult) {
        avgHr = result.avgHr
        maxHr = result.maxHr
        coveragePct = result.coveragePct
        zoneSeconds = result.zoneSeconds
        trimp = result.trimp
        keytelKcal = result.kcal
        zoneMethod = result.zoneMethod
    }

    var hasSamples: Bool { avgHr != nil }
}

/// One kilometre split of a GPS workout.
struct OutdoorWorkoutSplit: Codable, Equatable, Hashable {
    var km: Int
    var seconds: Double
}

/// Summary of a GPS (or Apple Watch) outdoor workout. The full route lives in
/// `OutdoorRouteStore` under the session id, not in the diary blob.
struct OutdoorWorkoutSummary: Codable, Equatable, Hashable {
    /// "walk", "run", "cycle" or "hike" (keys of `workout_config.json` sports).
    var sport: String
    var distanceM: Double
    var movingSeconds: Double
    var elapsedSeconds: Double
    var avgSpeedMps: Double?
    var avgPaceSecondsPerKm: Double?
    var maxSpeedMps: Double?
    var splits: [OutdoorWorkoutSplit]
    var elevationGainM: Double
    var elevationLossM: Double
    /// Lap marks relative to the start, in seconds of elapsed time.
    var lapOffsets: [Double]?
    /// ACSM steady-segment estimate (`CardioFitness.vo2maxGps`).
    var vo2max: Double?
    var vo2maxSegments: Int?
    /// Cooper 12-minute test result, when the workout was started in test mode.
    var cooperVO2max: Double?
    var cooperDistanceM: Double?
    /// "phone" or "watch".
    var recordedOn: String?
    /// True when the route file exists for this session.
    var hasRoute: Bool?
    /// Calories method: "keytel" or "met".
    var energyMethod: String?

    var sportTitle: String {
        OutdoorSport(rawValue: sport)?.title ?? sport.capitalized
    }

    /// Best available VO₂max from this workout (Cooper test beats steady segments).
    var bestVO2max: Double? { cooperVO2max ?? vo2max }
}

/// The four GPS sports of `workout_config.json`.
enum OutdoorSport: String, CaseIterable, Identifiable, Codable, Sendable {
    case walk, run, cycle, hike

    var id: String { rawValue }

    var title: String {
        switch self {
        case .walk: return "Outdoor Walk"
        case .run: return "Outdoor Run"
        case .cycle: return "Outdoor Cycle"
        case .hike: return "Hike"
        }
    }

    var shortTitle: String {
        switch self {
        case .walk: return "Walk"
        case .run: return "Run"
        case .cycle: return "Cycle"
        case .hike: return "Hike"
        }
    }

    var systemImage: String {
        switch self {
        case .walk: return "figure.walk"
        case .run: return "figure.run"
        case .cycle: return "figure.outdoor.cycle"
        case .hike: return "figure.hiking"
        }
    }

    /// Cycling shows speed; the others show pace.
    var showsSpeed: Bool { self == .cycle }

    /// ACSM walking/running equations apply to walk, run and hike, not cycling.
    var supportsVO2max: Bool { self != .cycle }
}

extension StrengthWorkoutSession {
    /// A session recorded with a real start and end (Start/Finish, a confirmed heart-rate window or GPS).
    var hasRealInterval: Bool { durationSeconds > 0 && completedAt > startedAt }

    var isOutdoor: Bool { outdoor != nil }
}

/// Formatting shared by the GPS screens.
enum WorkoutFormat {
    static func duration(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "--:--" }
        let total = Int(seconds.rounded(.down))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    static func distance(_ meters: Double, useMetric: Bool = Locale.current.measurementSystem == .metric) -> String {
        if useMetric { return String(format: "%.2f km", meters / 1000) }
        return String(format: "%.2f mi", meters / 1609.344)
    }

    static func pace(secondsPerKm: Double?, useMetric: Bool = Locale.current.measurementSystem == .metric) -> String {
        guard let secondsPerKm, secondsPerKm.isFinite, secondsPerKm > 0, secondsPerKm < 3600 else { return "--:--" }
        let value = useMetric ? secondsPerKm : secondsPerKm * 1.609344
        return duration(value) + (useMetric ? " /km" : " /mi")
    }

    static func speed(mps: Double?, useMetric: Bool = Locale.current.measurementSystem == .metric) -> String {
        guard let mps, mps.isFinite, mps >= 0 else { return "--" }
        return useMetric ? String(format: "%.1f km/h", mps * 3.6) : String(format: "%.1f mph", mps * 2.236_936)
    }
}
