import Foundation

// Heart rate during and after a workout, and strength-session windows detected from minute heart rate. Port of the
// "Heart rate during a workout" and "Strength-session windows" sections of `scripts/workout_reference.py`.

nonisolated enum HeartRateWorkout {
    /// `[t_ms, bpm]` in time order.
    struct Sample: Sendable {
        var tMs: Int64
        var bpm: Double
    }

    static func zone(_ hr: Double, hrMax: Double, rhr: Double?, _ th: WorkoutConfig.Thresholds) -> Int {
        let x: Double
        let cuts: [Double]
        if let rhr, hrMax > rhr {
            x = (hr - rhr) / (hrMax - rhr)
            cuts = th.hrrZones
        } else {
            x = hr / hrMax
            cuts = th.hrmaxZones
        }
        var z = 0
        for c in cuts where x >= c { z += 1 }
        return z
    }

    /// `table.get(sex or "other", table["other"])`.
    static func bySex<T>(_ table: [String: T], _ sex: String?) -> T? {
        let key = (sex?.isEmpty ?? true) ? "other" : sex!
        return table[key] ?? table["other"]
    }

    // MARK: Workout heart rate

    struct WorkoutInput: Sendable {
        var samples: [Sample]
        var startMs: Int64
        var endMs: Int64
        var hrMax: Double
        var rhr: Double?
        var sex: String?
        var age: Double?
        var weightKg: Double?
    }

    struct WorkoutResult: DerivedOutput {
        var avgHr: Double?
        var maxHr: Double?
        var coveragePct: Double
        var zoneSeconds: [Double]
        var trimp: Double?
        var kcal: Double?
        var zoneMethod: String

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["avg_hr": j(avgHr), "max_hr": WorkoutMath.bpmJSON(maxHr), "coverage_pct": coveragePct,
                    "zone_seconds": zoneSeconds, "trimp": j(trimp), "kcal": j(kcal), "zone_method": zoneMethod]
        }
    }

    /// Each sample stands for the time until the next one, capped at max_sample_gap_s and clipped to the window.
    static func hrWorkout(_ inp: WorkoutInput, config: WorkoutConfig) -> WorkoutResult {
        let th = config.thresholds
        let s0 = inp.startMs, s1 = inp.endMs
        let samples = inp.samples.filter { s0 <= $0.tMs && $0.tMs < s1 && th.hrValidMin <= $0.bpm && $0.bpm <= th.hrValidMax }
        let cap = Int64(th.maxSampleGapS * 1000)
        var covered: Int64 = 0
        var zones = [0.0, 0.0, 0.0, 0.0, 0.0]
        var weighted = 0.0, trimp = 0.0, kcal = 0.0
        var peak: Double?
        let hrMax = inp.hrMax, rhr = inp.rhr
        let hrr = rhr != nil && hrMax > rhr!
        let k = bySex(th.trimpK, inp.sex) ?? 0
        let weight: Double? = (inp.weightKg == nil || inp.weightKg == 0) ? nil : inp.weightKg
        for (i, x) in samples.enumerated() {
            let t = x.tMs, bpm = x.bpm
            let nxt = i + 1 < samples.count ? samples[i + 1].tMs : s1
            let dur = min(nxt, t + cap, s1) - t
            if dur <= 0 { continue }
            covered += dur
            let minutes = Double(dur) / 60000.0
            weighted += bpm * Double(dur)
            zones[zone(bpm, hrMax: hrMax, rhr: rhr, th)] += Double(dur) / 1000.0
            if hrr {
                let xr = (bpm - rhr!) / (hrMax - rhr!)
                if xr > 0 { trimp += minutes * xr * 0.64 * exp(k * xr) }
            }
            if let weight, let age = inp.age, let kc = bySex(th.keytel, inp.sex) {
                let perMin = (kc[0] + kc[1] * bpm + kc[2] * weight + kc[3] * age) / 4.184
                kcal += max(0.0, perMin) * minutes
            }
            if peak == nil || bpm > peak! { peak = bpm }
        }
        let window = s1 - s0
        let coverage = window > 0 ? Double(covered) / Double(window) : 0.0
        return WorkoutResult(
            avgHr: covered != 0 ? DerivedMath.roundTo(weighted / Double(covered), 1) : nil, maxHr: peak,
            coveragePct: DerivedMath.roundTo(coverage * 100.0, 1), zoneSeconds: zones.map { DerivedMath.roundTo($0, 0) },
            trimp: rhr != nil && covered != 0 ? DerivedMath.roundTo(trimp, 1) : nil,
            kcal: covered != 0 && coverage >= th.keytelMinCoverage && weight != nil ? DerivedMath.roundTo(kcal, 0) : nil,
            zoneMethod: hrr ? "hrr" : "hrmax")
    }

    // MARK: Recovery (HRR1)

    struct RecoveryResult: DerivedOutput {
        var hrr1: Double?
        var flagLow: Bool
        var confidence: String?

        var jsonObject: [String: Any] {
            ["hrr1": DerivedMath.json(hrr1), "flag_low": flagLow, "confidence": DerivedMath.json(confidence)]
        }
    }

    /// Highest HR in the last 60 s before `endMs` − HR nearest to end + 60 s (within ± recovery_tolerance_s).
    static func hrRecovery(samples: [Sample], endMs end: Int64, config: WorkoutConfig) -> RecoveryResult {
        let th = config.thresholds
        let before = samples.filter { end - 60000 <= $0.tMs && $0.tMs <= end }.map(\.bpm)
        let tol = Int64(th.recoveryToleranceS * 1000)
        // Python sorts the tuples (abs_diff, t, bpm) and takes the first: their lexicographic minimum.
        let after = samples.map { (abs($0.tMs - (end + 60000)), $0.tMs, $0.bpm) }.filter { $0.0 <= tol }
        let first = after.min { a, b in
            if a.0 != b.0 { return a.0 < b.0 }
            if a.1 != b.1 { return a.1 < b.1 }
            return a.2 < b.2
        }
        guard let peak = before.max(), let first else {
            return RecoveryResult(hrr1: nil, flagLow: false, confidence: nil)
        }
        let drop = peak - first.2
        var maxGap: Int64?
        for i in 0..<max(0, samples.count - 1) {
            let g = samples[i + 1].tMs - samples[i].tMs
            if maxGap == nil || g > maxGap! { maxGap = g }
        }
        let dense = maxGap != nil && maxGap! <= 10000
        return RecoveryResult(hrr1: DerivedMath.roundTo(drop, 0), flagLow: drop < th.hrr1AbnormalBelow,
                              confidence: dense ? "medium" : "low")
    }

    // MARK: Strength-session windows

    struct Window: Sendable {
        var startMs: Int64
        var endMs: Int64
        var minutes: Int
        var avgHr: Double
        var maxHr: Double
    }

    struct WindowsResult: DerivedOutput {
        var levelBpm: Double
        var windows: [Window]

        var jsonObject: [String: Any] {
            ["level_bpm": levelBpm,
             "windows": windows.map {
                 ["start_ms": $0.startMs, "end_ms": $0.endMs, "minutes": $0.minutes, "avg_hr": $0.avgHr,
                  "max_hr": WorkoutMath.bpmJSON($0.maxHr)] as [String: Any]
             }]
        }
    }

    /// Minutes at HR ≥ min(detect_bpm, rhr + detect_hrr × (hr_max − rhr)) (detect_bpm alone without a usable rhr),
    /// merged while `t − last_end ≤ (merge_gap_min + 1) min`; windows of at least min_window_min minutes.
    static func workoutWindows(hr series: DerivedMinuteSeries, rhr: Double?, hrMax: Double, config: WorkoutConfig) -> WindowsResult {
        let th = config.thresholds
        var level = th.detectBpm
        if let rhr, hrMax > rhr { level = min(level, rhr + th.detectHrr * (hrMax - rhr)) }
        let t0 = series.startMs
        var active: [Int64] = []
        for (i, v) in series.values.enumerated() {
            if let v, v >= level { active.append(t0 + Int64(i) * 60000) }
        }
        var windows: [(Int64, Int64)] = []
        for t in active {
            if let last = windows.last, Double(t - last.1) <= (th.mergeGapMin + 1) * 60000 {
                windows[windows.count - 1].1 = t
            } else {
                windows.append((t, t))
            }
        }
        var out: [Window] = []
        for (s, e) in windows {
            let minutes = Int(DerivedMath.floorDiv(e - s, 60000)) + 1
            if Double(minutes) < th.minWindowMin { continue }
            var vals: [Double] = []
            var t = s
            while t < e + 60000 {
                if let v = series.values[Int(DerivedMath.floorDiv(t - t0, 60000))] { vals.append(v) }
                t += 60000
            }
            out.append(Window(startMs: s, endMs: e + 60000, minutes: minutes,
                              avgHr: DerivedMath.roundTo(DerivedMath.mean(vals), 1), maxHr: vals.max()!))
        }
        return WindowsResult(levelBpm: DerivedMath.roundTo(level, 1), windows: out)
    }
}
