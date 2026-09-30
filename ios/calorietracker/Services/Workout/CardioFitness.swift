import Foundation

// Cardio fitness: VO2max from steady GPS segments (ACSM equations, %HRR ≈ %VO2R) and the Cooper 12-minute test.
// Port of the "Cardio fitness" section of `scripts/workout_reference.py`.

nonisolated enum CardioFitness {
    struct Segment: Sendable {
        var speedMps: Double
        var grade: Double
        var hr: Double
        var durationS: Double
    }

    struct GpsResult: DerivedOutput {
        var vo2max: Double?
        var segmentsUsed: Int
        var status: String

        var jsonObject: [String: Any] {
            ["vo2max": DerivedMath.json(vo2max), "segments_used": segmentsUsed, "status": status]
        }
    }

    /// Oxygen cost (ml/kg/min) of walking or running at `speedMps` on `grade`.
    static func acsmVO2(speedMps: Double, grade: Double, mode: String) -> Double {
        let s = speedMps * 60.0
        if mode == "run" { return 0.2 * s + 0.9 * s * grade + 3.5 }
        return 0.1 * s + 1.8 * s * grade + 3.5
    }

    /// Duration-weighted mean of 3.5 + (VO2 − 3.5) ÷ %HRR over segments that are long, flat and in the HRR band.
    static func vo2maxGps(segments: [Segment], rhr: Double?, hrMax: Double?, config: WorkoutConfig) -> GpsResult {
        let th = config.thresholds
        guard let rhr, let hrMax, hrMax > rhr else {
            return GpsResult(vo2max: nil, segmentsUsed: 0, status: "no_heart_rate_reserve")
        }
        var totalW = 0.0, total = 0.0
        var used = 0
        for s in segments {
            if s.durationS < th.minSegmentS || abs(s.grade) > th.maxGrade { continue }
            let hrr = (s.hr - rhr) / (hrMax - rhr)
            if hrr < th.minHrr || hrr > th.maxHrr { continue }
            let mode = s.speedMps >= th.walkRunSplitMps ? "run" : "walk"
            let v = 3.5 + (acsmVO2(speedMps: s.speedMps, grade: s.grade, mode: mode) - 3.5) / hrr
            total += v * s.durationS
            totalW += s.durationS
            used += 1
        }
        if used == 0 { return GpsResult(vo2max: nil, segmentsUsed: 0, status: "no_steady_segment") }
        return GpsResult(vo2max: DerivedMath.roundTo(total / totalW, 1), segmentsUsed: used, status: "ok")
    }

    struct CooperResult: DerivedOutput {
        var vo2max: Double?
        var jsonObject: [String: Any] { ["vo2max": DerivedMath.json(vo2max)] }
    }

    /// Distance covered in a 12-minute all-out test: VO2max = (distance − 504.9) ÷ 44.73 (Cooper 1968).
    static func cooper(distanceM d: Double?) -> CooperResult {
        guard let d, d > 504.9 else { return CooperResult(vo2max: nil) }
        return CooperResult(vo2max: DerivedMath.roundTo((d - 504.9) / 44.73, 1))
    }
}
