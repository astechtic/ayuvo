import ActivityKit
import AppIntents
import CoreGraphics
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
        /// Simplified route for the Live Activity map; nil for strength or before two fixes.
        var route: WorkoutRouteSketch?
        /// Active seconds of the most recent completed laps, oldest first (at most `maxLapSplits`).
        var lapSplits: [Double]?

        static let maxLapSplits = 3

        var isPaused: Bool { state == "paused" }
        var isGPS: Bool { kind == "gps" }
    }

    var title: String
    var useMetric: Bool
}

/// A GPS route squeezed for the Live Activity payload (ActivityKit caps the state at 4 KB): at most
/// `maxPoints` Douglas–Peucker points, north up, quantized to 0...255 in a square box that keeps the
/// route's aspect ratio. `xy` holds x, y pairs; `lapStart` is the first point of the current lap.
nonisolated struct WorkoutRouteSketch: Codable, Hashable, Sendable {
    static let maxPoints = 48
    static let scale = 255.0
    /// Routes smaller than this (standing still, GPS jitter) are drawn at this size, so noise stays small.
    static let minExtentM = 60.0

    var xy: [UInt8]
    var lapStart: Int?

    var count: Int { xy.count / 2 }

    /// Point `i` in the unit square, y growing downwards (south).
    func point(_ i: Int) -> CGPoint {
        CGPoint(x: Double(xy[2 * i]) / Self.scale, y: Double(xy[2 * i + 1]) / Self.scale)
    }

    /// nil when there are fewer than two points. `lapStartIndex` indexes into `coordinates`.
    static func make(coordinates: [(lat: Double, lon: Double)], lapStartIndex: Int? = nil,
                     maxPoints: Int = maxPoints) -> WorkoutRouteSketch? {
        guard coordinates.count >= 2, maxPoints >= 2 else { return nil }
        // Local equirectangular metres around the first fix; plenty for a workout-sized area.
        let lat0 = coordinates[0].lat * .pi / 180
        let metresPerDegree = 111_320.0
        let planar = coordinates.map {
            CGPoint(x: ($0.lon - coordinates[0].lon) * metresPerDegree * cos(lat0),
                    y: -($0.lat - coordinates[0].lat) * metresPerDegree)
        }
        let minX = planar.map(\.x).min()!, maxX = planar.map(\.x).max()!
        let minY = planar.map(\.y).min()!, maxY = planar.map(\.y).max()!
        let extent = max(maxX - minX, maxY - minY, minExtentM)

        var forced: Set<Int> = [0, planar.count - 1]
        if let lapStartIndex, planar.indices.contains(lapStartIndex) { forced.insert(lapStartIndex) }
        var tolerance = extent / 400
        var kept = simplify(planar, tolerance: tolerance, forced: forced)
        while kept.count > maxPoints {
            tolerance *= 1.6
            kept = simplify(planar, tolerance: tolerance, forced: forced)
        }

        let midX = (minX + maxX) / 2, midY = (minY + maxY) / 2
        var xy: [UInt8] = []
        xy.reserveCapacity(kept.count * 2)
        for index in kept {
            let p = planar[index]
            let x = 0.5 + (p.x - midX) / extent, y = 0.5 + (p.y - midY) / extent
            xy.append(UInt8(min(scale, max(0, (x * scale).rounded()))))
            xy.append(UInt8(min(scale, max(0, (y * scale).rounded()))))
        }
        let lapStart = lapStartIndex.flatMap { start in kept.firstIndex { $0 >= start } }
        return WorkoutRouteSketch(xy: xy, lapStart: lapStart)
    }

    /// Iterative Douglas–Peucker; returns the kept indices in order, always including `forced`.
    static func simplify(_ points: [CGPoint], tolerance: Double, forced: Set<Int>) -> [Int] {
        guard points.count > 2 else { return Array(points.indices) }
        var keep = [Bool](repeating: false, count: points.count)
        for index in forced where points.indices.contains(index) { keep[index] = true }
        let anchors = forced.filter { points.indices.contains($0) }.sorted()
        var stack: [(Int, Int)] = zip(anchors, anchors.dropFirst()).map { ($0, $1) }
        while let segment = stack.popLast() {
            let (a, b) = segment
            guard b - a > 1 else { continue }
            var worst = -1.0, worstIndex = a
            for i in (a + 1)..<b {
                let d = distance(points[i], points[a], points[b])
                if d > worst { worst = d; worstIndex = i }
            }
            if worst > tolerance {
                keep[worstIndex] = true
                stack.append((a, worstIndex))
                stack.append((worstIndex, b))
            }
        }
        return keep.indices.filter { keep[$0] }
    }

    private static func distance(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) -> Double {
        let dx = b.x - a.x, dy = b.y - a.y
        let length2 = dx * dx + dy * dy
        guard length2 > 0 else { return hypot(p.x - a.x, p.y - a.y) }
        let t = max(0, min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / length2))
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
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
