import Foundation

/// A recorded GPS route, stored as one JSON file per diary session under Application Support
/// (`WorkoutRoutes/<session id>.json`). Kept out of the diary blob because a one-hour route is
/// thousands of points. Heart-rate samples are stored alongside so the summary can color the route.
nonisolated struct OutdoorRoute: Codable, Sendable, Equatable {
    struct Point: Codable, Sendable, Equatable {
        var t: Int64
        var lat: Double
        var lon: Double
        var alt: Double?
        var acc: Double?
        var speed: Double?
    }

    struct HeartSample: Codable, Sendable, Equatable {
        var t: Int64
        var bpm: Double
    }

    var sport: String
    var points: [Point]
    var heartRate: [HeartSample]
    /// Manual pauses [start, end) in ms.
    var pauses: [[Int64]]

    var gpsPoints: [GpsTrack.Point] {
        points.map { GpsTrack.Point(tMs: $0.t, lat: $0.lat, lon: $0.lon, altM: $0.alt, hAccM: $0.acc, speedMps: $0.speed) }
    }
}

nonisolated enum OutdoorRouteStore {
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("WorkoutRoutes", isDirectory: true)
    }

    static func url(for sessionID: UUID) -> URL {
        directory.appendingPathComponent("\(sessionID.uuidString).json")
    }

    @discardableResult
    static func save(_ route: OutdoorRoute, sessionID: UUID) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(route)
            try data.write(to: url(for: sessionID), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }

    static func load(sessionID: UUID) -> OutdoorRoute? {
        guard let data = try? Data(contentsOf: url(for: sessionID)) else { return nil }
        return try? JSONDecoder().decode(OutdoorRoute.self, from: data)
    }

    static func delete(sessionID: UUID) {
        try? FileManager.default.removeItem(at: url(for: sessionID))
    }
}
