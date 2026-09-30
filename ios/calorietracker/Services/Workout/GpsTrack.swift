import Foundation

// GPS outdoor workouts: accuracy and speed gates, auto/manual pause, kilometre splits and elevation with hysteresis.
// Port of the "GPS track" section of `scripts/workout_reference.py` (docs/workouts-gps.md).

nonisolated enum WorkoutMath {
    /// WGS-84 mean radius.
    static let earthRadiusM = 6_371_008.8

    /// CPython `math.radians`: x × (π / 180), one rounding of the constant.
    static let degToRad = Double.pi / 180.0

    static func radians(_ deg: Double) -> Double { deg * degToRad }

    static func haversineM(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = radians(lat1), p2 = radians(lat2)
        let dp = p2 - p1
        let dl = radians(lon2 - lon1)
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * earthRadiusM * atan2(a.squareRoot(), (1.0 - a).squareRoot())
    }

    static func inPause(_ t: Int64, _ pauses: [(Int64, Int64)]) -> Bool {
        pauses.contains { $0.0 <= t && t < $0.1 }
    }

    /// A heart rate as the reference returns it: an integer when the input was one.
    static func bpmJSON(_ x: Double?) -> Any {
        guard let x else { return NSNull() }
        if x.rounded() == x, abs(x) < 1e15 { return Int(x) }
        return x
    }
}

nonisolated enum GpsTrack {
    /// `[t_ms, lat, lon, alt_m | null, h_acc_m, speed_mps | null]`.
    struct Point: Sendable {
        var tMs: Int64
        var lat: Double
        var lon: Double
        var altM: Double?
        var hAccM: Double?
        var speedMps: Double?
    }

    struct Input: Sendable {
        /// "walk", "run", "cycle" or "hike".
        var sport: String
        var points: [Point]
        /// Manual pauses [start_ms, end_ms).
        var pauses: [(Int64, Int64)] = []
        var startMs: Int64
        var endMs: Int64
    }

    struct Split: Sendable {
        var km: Int
        var seconds: Double
    }

    struct Result: DerivedOutput {
        var distanceM: Double
        var movingS: Double
        var elapsedS: Double
        var avgSpeedMps: Double?
        var avgPaceSPerKm: Double?
        var maxSpeedMps: Double?
        var splits: [Split]
        var elevationGainM: Double
        var elevationLossM: Double
        var keptPoints: Int
        var droppedPoints: Int

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["distance_m": distanceM, "moving_s": movingS, "elapsed_s": elapsedS, "avg_speed_mps": j(avgSpeedMps),
                    "avg_pace_s_per_km": j(avgPaceSPerKm), "max_speed_mps": j(maxSpeedMps),
                    "splits": splits.map { ["km": $0.km, "seconds": $0.seconds] as [String: Any] },
                    "elevation_gain_m": elevationGainM, "elevation_loss_m": elevationLossM, "kept_points": keptPoints,
                    "dropped_points": droppedPoints]
        }
    }

    /// nil for an unknown sport.
    static func gpsTrack(_ inp: Input, config: WorkoutConfig) -> Result? {
        guard let sp = config.sports[inp.sport] else { return nil }
        let th = config.thresholds
        var kept: [Point] = []
        var dropped = 0
        for p in inp.points {
            guard let acc = p.hAccM, acc <= th.maxHAccuracyM, p.tMs >= inp.startMs, p.tMs <= inp.endMs else {
                dropped += 1
                continue
            }
            if let q = kept.last {
                let dt = Double(p.tMs - q.tMs) / 1000.0
                if dt <= 0 || WorkoutMath.haversineM(q.lat, q.lon, p.lat, p.lon) / dt > sp.maxSpeedMps {
                    dropped += 1
                    continue
                }
            }
            kept.append(p)
        }
        var distance = 0.0, moving = 0.0
        var splits: [Double] = []
        var nextKm = 1000.0
        var maxSpeed = 0.0
        for i in 0..<max(0, kept.count - 1) {
            let q = kept[i], p = kept[i + 1]
            if WorkoutMath.inPause(q.tMs, inp.pauses) || WorkoutMath.inPause(p.tMs, inp.pauses) { continue }
            let dt = Double(p.tMs - q.tMs) / 1000.0
            let d = WorkoutMath.haversineM(q.lat, q.lon, p.lat, p.lon)
            let v = d / dt
            if v < sp.autoPauseSpeedMps { continue }
            while distance + d >= nextKm {
                let frac = (nextKm - distance) / d
                splits.append(DerivedMath.roundTo(moving + frac * dt, 1))
                nextKm += 1000.0
            }
            distance += d
            moving += dt
            if v > maxSpeed { maxSpeed = v }
        }
        var perKm: [Split] = []
        var prev = 0.0
        for (i, s) in splits.enumerated() {
            perKm.append(Split(km: i + 1, seconds: DerivedMath.roundTo(s - prev, 1)))
            prev = s
        }
        var gain = 0.0, loss = 0.0
        var anchor: Double?
        for p in kept {
            guard let alt = p.altM else { continue }
            if let a = anchor {
                if alt - a >= th.elevationHysteresisM {
                    gain += alt - a
                    anchor = alt
                } else if a - alt >= th.elevationHysteresisM {
                    loss += a - alt
                    anchor = alt
                }
            } else {
                anchor = alt
            }
        }
        let elapsed = Double(inp.endMs - inp.startMs) / 1000.0
        let avgSpeed: Double? = moving > 0 ? distance / moving : nil
        var pace: Double?
        if let avgSpeed, avgSpeed != 0 { pace = DerivedMath.roundTo(1000.0 / avgSpeed, 0) }
        return Result(distanceM: DerivedMath.roundTo(distance, 1), movingS: DerivedMath.roundTo(moving, 1),
                      elapsedS: DerivedMath.roundTo(elapsed, 1), avgSpeedMps: DerivedMath.roundTo(avgSpeed, 2),
                      avgPaceSPerKm: pace, maxSpeedMps: moving > 0 ? DerivedMath.roundTo(maxSpeed, 2) : nil,
                      splits: perKm, elevationGainM: DerivedMath.roundTo(gain, 1),
                      elevationLossM: DerivedMath.roundTo(loss, 1), keptPoints: kept.count, droppedPoints: dropped)
    }
}
