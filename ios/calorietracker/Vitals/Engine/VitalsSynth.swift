import Foundation

// Deterministic synthetic finger / face signals: port of the "Deterministic synthetic signals" section of
// `scripts/vitals_reference.py`. Used by the vector tests and by the replay frame source (no camera needed).

/// Numerical Recipes style LCG modulo 2^31; identical in every port (64-bit integer math).
nonisolated struct VitalsLcg: Sendable {
    private(set) var state: Int64

    init(seed: Int64) {
        let m: Int64 = 2147483648
        state = ((seed % m) + m) % m
    }

    mutating func uniform() -> Double {
        state = (1103515245 * state + 12345) % 2147483648
        return Double(state) / 2147483648.0
    }

    mutating func gauss() -> Double {
        var acc = 0.0
        for _ in 0..<4 { acc += uniform() }
        return (acc - 2.0) * 1.7320508075688772
    }
}

nonisolated enum VitalsSynth {
    private typealias M = VitalsMath

    struct Event: Sendable {
        var kind: String
        var startS: Double
        var endS: Double
        var amp: Double
    }

    struct Roi: Sendable {
        var ac: Double
        var noise: Double
    }

    /// `synth_finger` / `synth_face` spec with the reference's defaults.
    struct Spec: Sendable {
        var fs: Double
        var durationS: Double
        var hrBpm: Double
        var rsaMs = 0.0
        var ibiJitterMs = 0.0
        var hrDriftBpm = 0.0
        var respBpm = 15.0
        var jitterMs = 0.0
        var seed: Int64 = 1
        var events: [Event] = []
        // Finger
        var ac = 0.02
        var wander = 0.004
        var dc: [Double] = [210.0, 60.0, 25.0]
        var noise = 0.0005
        // Face
        var skin: [Double] = [160.0, 115.0, 95.0]
        var rois: [String: Roi] = [:]
        var illum = 0.0
        var illumHz = 0.3

        init(fs: Double, durationS: Double, hrBpm: Double) {
            self.fs = fs
            self.durationS = durationS
            self.hrBpm = hrBpm
        }

        /// From the reference's spec dict.
        init(json s: RJ) {
            self.init(fs: s["fs"].double ?? 30, durationS: s["duration_s"].double ?? 0, hrBpm: s["hr_bpm"].double ?? 60)
            if let v = s["rsa_ms"].double { rsaMs = v }
            if let v = s["ibi_jitter_ms"].double { ibiJitterMs = v }
            if let v = s["hr_drift_bpm"].double { hrDriftBpm = v }
            if let v = s["resp_bpm"].double { respBpm = v }
            if let v = s["jitter_ms"].double { jitterMs = v }
            if let v = s["seed"].double { seed = Int64(v) }
            events = (s["events"].array ?? []).map {
                Event(kind: $0["kind"].string ?? "", startS: $0["start_s"].double ?? 0, endS: $0["end_s"].double ?? 0,
                      amp: $0["amp"].double ?? 0)
            }
            if let v = s["ac"].double { ac = v }
            if let v = s["wander"].double { wander = v }
            if let v = s["dc"].array { dc = v.compactMap(\.double) }
            if let v = s["noise"].double { noise = v }
            if let v = s["skin"].array { skin = v.compactMap(\.double) }
            for (name, r) in s["rois"].object ?? [:] {
                rois[name] = Roi(ac: r["ac"].double ?? 0, noise: r["noise"].double ?? 0.001)
            }
            if let v = s["illum"].double { illum = v }
            if let v = s["illum_hz"].double { illumHz = v }
        }
    }

    static func beatTimes(_ spec: Spec, _ durationMs: Double, _ rng: inout VitalsLcg) -> [Double] {
        var beats: [Double] = []
        var t = 100.0
        let base = 60000.0 / spec.hrBpm
        let respHz = spec.respBpm / 60.0
        while t < durationMs + 3000.0 {
            beats.append(t)
            var ibi = base + spec.rsaMs * sin(M.twoPi * respHz * t / 1000.0)
            ibi += spec.ibiJitterMs * (rng.uniform() - 0.5) * 2.0
            let drift = spec.hrDriftBpm
            if drift != 0.0 {
                let hrNow = spec.hrBpm + drift * t / durationMs
                ibi = ibi * spec.hrBpm / hrNow
            }
            t += ibi
        }
        return beats
    }

    static func pulse(_ t: Double, _ beats: [Double], _ cursor: inout Int) -> Double {
        while cursor + 1 < beats.count - 1 && beats[cursor + 1] <= t { cursor += 1 }
        let span = beats[cursor + 1] - beats[cursor]
        let phi = (t - beats[cursor]) / span
        let a = (phi - 0.2) / 0.07
        let b = (phi - 0.5) / 0.09
        return exp(-a * a) + 0.35 * exp(-b * b)
    }

    static func eventAt(_ events: [Event], _ tS: Double, _ kind: String) -> Event? {
        for e in events where e.kind == kind && e.startS <= tS && tS < e.endS { return e }
        return nil
    }

    /// Finger frames `[t_ms, r, g, b, r_std, sat_frac]`.
    static func synthFinger(_ spec: Spec) -> [[Double]] {
        var rng = VitalsLcg(seed: spec.seed)
        let fs = spec.fs
        let durationMs = spec.durationS * 1000.0
        let beats = beatTimes(spec, durationMs, &rng)
        let respHz = spec.respBpm / 60.0
        let weights = [1.0, 1.5, 1.2]
        let dc = spec.dc
        let n = M.floorInt(spec.durationS * fs)
        var frames: [[Double]] = []
        frames.reserveCapacity(max(n, 0))
        var cursor = 0
        var i = 0
        while i < n {
            var t = Double(i) * 1000.0 / fs + spec.jitterMs * (rng.uniform() - 0.5) * 2.0
            if t < 0.0 { t = 0.0 }
            let p = pulse(t, beats, &cursor)
            let resp = sin(M.twoPi * respHz * t / 1000.0)
            let ac = spec.ac * (1.0 + 0.15 * resp)
            let base = 1.0 + spec.wander * resp
            var vals: [Double] = []
            for c in 0..<3 {
                let noise = spec.noise * rng.gauss()
                vals.append(dc[c] * base * (1.0 - ac * weights[c] * p) * (1.0 + noise))
            }
            var rStd = 8.0, sat = 0.02
            let tS = t / 1000.0
            if let motion = eventAt(spec.events, tS, "motion") {
                let wobble = motion.amp * sin(M.twoPi * 1.3 * tS) + motion.amp * 0.5
                for c in 0..<3 { vals[c] = vals[c] * (1.0 + wobble) }
                rStd = 18.0
            }
            if eventAt(spec.events, tS, "no_finger") != nil {
                let r = 60.0 + 5.0 * rng.gauss()
                let g = 55.0 + 5.0 * rng.gauss()
                let b = 50.0 + 5.0 * rng.gauss()
                vals = [r, g, b]
                rStd = 45.0
                sat = 0.0
            }
            if eventAt(spec.events, tS, "saturate") != nil {
                vals[0] = 255.0
                sat = 0.7
            }
            if vals[0] > 255.0 { vals[0] = 255.0 }
            frames.append([M.roundTo(t, 3), M.roundTo(vals[0], 3), M.roundTo(vals[1], 3), M.roundTo(vals[2], 3), rStd, sat])
            i += 1
        }
        return frames
    }

    /// Face frames (the reference's face encoding).
    static func synthFace(_ spec: Spec) -> VitalsEngine.FaceFrames {
        var rng = VitalsLcg(seed: spec.seed)
        let fs = spec.fs
        let durationMs = spec.durationS * 1000.0
        let beats = beatTimes(spec, durationMs, &rng)
        let pbv = [0.33, 0.77, 0.53]
        let skin = spec.skin
        let roiNames = spec.rois.keys.sorted()
        let n = M.floorInt(spec.durationS * fs)
        var out = VitalsEngine.FaceFrames(tMs: [], rois: [:], motion: [], yaw: [], pitch: [], luma: [], faceCount: [],
                                          faceFraction: [])
        var rois: [String: [[Double]]] = [:]
        for name in roiNames { rois[name] = [] }
        var cursor = 0
        var i = 0
        while i < n {
            var t = Double(i) * 1000.0 / fs + spec.jitterMs * (rng.uniform() - 0.5) * 2.0
            if t < 0.0 { t = 0.0 }
            let tS = t / 1000.0
            let p = pulse(t, beats, &cursor)
            var illum = spec.illum * sin(M.twoPi * spec.illumHz * tS)
            var motionScore = 0.005, yaw = 2.0, faces = 1.0, lumaScale = 1.0
            if let motion = eventAt(spec.events, tS, "motion") {
                illum += motion.amp * sin(M.twoPi * 1.1 * tS)
                motionScore = 0.08
            }
            if eventAt(spec.events, tS, "dark") != nil { lumaScale = 0.2 }
            if eventAt(spec.events, tS, "second_face") != nil { faces = 2 }
            if eventAt(spec.events, tS, "turn") != nil { yaw = 35.0 }
            for name in roiNames {
                let r = spec.rois[name]!
                var vals: [Double] = []
                for c in 0..<3 {
                    let noise = r.noise * rng.gauss()
                    vals.append(skin[c] * lumaScale * (1.0 + illum) * (1.0 - r.ac * pbv[c] * p) * (1.0 + noise))
                }
                rois[name]!.append([M.roundTo(vals[0], 3), M.roundTo(vals[1], 3), M.roundTo(vals[2], 3), 0.9])
            }
            out.tMs.append(M.roundTo(t, 3))
            out.motion.append(motionScore)
            out.yaw.append(yaw)
            out.pitch.append(1.0)
            out.luma.append(M.roundTo(120.0 * lumaScale, 3))
            out.faceCount.append(faces)
            out.faceFraction.append(0.4)
            i += 1
        }
        out.rois = rois
        return out
    }

    /// `synth`: {"frames": [...]} for kind "finger", else the face encoding.
    static func synth(kind: String, spec: Spec) -> RJ {
        if kind == "finger" {
            return .obj(["frames": .arr(synthFinger(spec).map { RJ.fs($0) })])
        }
        return synthFace(spec).json
    }
}
