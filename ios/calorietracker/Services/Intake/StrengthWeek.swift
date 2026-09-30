import Foundation

// One week of strength training: hard sets and volume per primary muscle, push/pull/legs shares and estimated 1RM
// (Epley). Port of `strength_week` in `scripts/intake_reference.py`.

nonisolated enum StrengthWeek {
    struct WorkSet: Sendable {
        var reps: Double?
        var weightKg: Double?
    }

    struct Exercise: Sendable {
        var name: String
        var primaryMuscles: [String]
        var sets: [WorkSet]
    }

    struct Session: Sendable {
        var day: String
        var exercises: [Exercise]
    }

    struct Result: DerivedOutput {
        var sessions: Int
        var planned: Int?
        var setsByMuscle: [String: Int]
        var volumeByMuscle: [String: Double]
        var belowRange: [String]
        var patternPct: [String: Double?]
        var e1rm: [String: Double]

        var jsonObject: [String: Any] {
            ["sessions": sessions, "planned": DerivedMath.json(planned), "sets_by_muscle": setsByMuscle,
             "volume_by_muscle": volumeByMuscle, "below_range": belowRange,
             "pattern_pct": patternPct.mapValues { DerivedMath.json($0) }, "e1rm": e1rm]
        }
    }

    static let patterns = ["push", "pull", "legs"]

    static func epley(_ weight: Double, _ reps: Double) -> Double { weight * (1.0 + reps / 30.0) }

    static func strengthWeek(sessions: [Session], plannedSessions: Int?, config: IntakeConfig) -> Result {
        let th = config.thresholds
        let groups = config.muscleGroups
        var setsBy: [String: Int] = [:]
        var volBy: [String: Double] = [:]
        var pattern: [String: Double] = ["push": 0, "pull": 0, "legs": 0]
        var best: [String: Double] = [:]
        for s in sessions {
            for e in s.exercises {
                for st in e.sets {
                    let reps = st.reps ?? 0
                    let w = st.weightKg ?? 0.0
                    if reps < 1 { continue }
                    for m in e.primaryMuscles {
                        setsBy[m, default: 0] += 1
                        volBy[m, default: 0.0] += reps * w
                        for g in patterns where (groups[g] ?? []).contains(m) { pattern[g, default: 0] += 1 }
                    }
                    if reps >= 1, reps <= th.e1rmMaxReps, w > 0 {
                        let v = epley(w, reps)
                        if best[e.name] == nil || v > best[e.name]! { best[e.name] = v }
                    }
                }
            }
        }
        let totalPattern = (pattern["push"] ?? 0) + (pattern["pull"] ?? 0) + (pattern["legs"] ?? 0)
        let below = setsBy.keys.filter { Double(setsBy[$0] ?? 0) < th.weeklySetsMin }.sorted(by: DerivedMath.pyLess)
        var pct: [String: Double?] = [:]
        for g in patterns {
            pct[g] = totalPattern != 0 ? DerivedMath.roundTo((pattern[g] ?? 0) * 100.0 / totalPattern, 1) : nil
        }
        return Result(sessions: sessions.count, planned: plannedSessions, setsByMuscle: setsBy,
                      volumeByMuscle: volBy.mapValues { DerivedMath.roundTo($0, 0) }, belowRange: below,
                      patternPct: pct, e1rm: best.mapValues { DerivedMath.roundTo($0, 1) })
    }
}
