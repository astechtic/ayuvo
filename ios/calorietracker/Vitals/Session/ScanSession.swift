import Foundation
import Observation
import UIKit

/// Frames buffered for one scan, in the engine's encodings. Pure value; rebased so `t_ms` starts at 0, which makes
/// `frame_stats` reproduce the engine input exactly.
nonisolated struct VitalsFrameBuffer: Sendable {
    let mode: VitalsMode
    private(set) var finger: [[Double]] = []
    private(set) var face: [FaceFrameSample] = []

    init(mode: VitalsMode) { self.mode = mode }

    var count: Int { mode == .finger ? finger.count : face.count }
    var isEmpty: Bool { count == 0 }
    var firstTMs: Double? { mode == .finger ? finger.first?[0] : face.first?.tMs }
    var tMs: [Double] { mode == .finger ? finger.map { $0[0] } : face.map(\.tMs) }

    mutating func append(_ frame: VitalsFrame) {
        switch frame {
        case .finger(let f): if mode == .finger { finger.append(f) }
        case .face(let s): if mode == .face { face.append(s) }
        }
    }

    mutating func removeAll() {
        finger.removeAll()
        face.removeAll()
    }

    /// Finger frames with `t_ms` relative to the first frame.
    var fingerFrames: [[Double]] {
        guard let t0 = finger.first?[0] else { return [] }
        return finger.map { f in var r = f; r[0] = f[0] - t0; return r }
    }

    /// The face encoding with `t_ms` relative to the first frame; ROIs in config order.
    func faceFrames(rois: [String]) -> VitalsEngine.FaceFrames {
        let t0 = face.first?.tMs ?? 0
        var out = VitalsEngine.FaceFrames(tMs: [], rois: [:], motion: [], yaw: [], pitch: [], luma: [], faceCount: [], faceFraction: [])
        var columns: [String: [[Double]]] = [:]
        for name in rois { columns[name] = [] }
        for s in face {
            out.tMs.append(s.tMs - t0)
            out.motion.append(s.motion)
            out.yaw.append(s.yaw)
            out.pitch.append(s.pitch)
            out.luma.append(s.luma)
            out.faceCount.append(s.faceCount)
            out.faceFraction.append(s.faceFraction)
            for name in rois { columns[name]!.append(s.rois[name] ?? [0, 0, 0, 0]) }
        }
        out.rois = columns
        return out
    }
}

/// Inputs of an analysis besides the frames (settings and calibrations).
nonisolated struct VitalsAnalysisOptions: Sendable {
    var experimentalEnabled = false
    var researchEnabled = false
    var spo2Calibrations: [VitalsEngine.Spo2Calibration] = []
    var bpCalibrations: [VitalsEngine.BpCalibration] = []
    var nowMs: Double = 0
}

nonisolated enum VitalsAnalyzer {
    /// Runs the engine on the buffered frames (with signals, for the waveform and `vital_scan_signals`).
    static func analyze(_ buffer: VitalsFrameBuffer, options: VitalsAnalysisOptions, _ cfg: VitalsConfig) -> VitalScanResult {
        switch buffer.mode {
        case .finger:
            var input = VitalsEngine.FingerScanInput(frames: buffer.fingerFrames)
            input.experimentalEnabled = options.experimentalEnabled
            input.researchEnabled = options.researchEnabled
            input.spo2Calibrations = options.spo2Calibrations
            input.bpCalibrations = options.bpCalibrations
            input.nowMs = options.nowMs
            input.includeSignals = true
            if input.frames.isEmpty { return VitalsEngine.emptyResult(cfg, buffer.mode.rawValue, 0) }
            return VitalsEngine.analyzeFinger(input, cfg)
        case .face:
            var input = VitalsEngine.FaceScanInput(face: buffer.faceFrames(rois: cfg.face.rois))
            input.experimentalEnabled = options.experimentalEnabled
            input.researchEnabled = options.researchEnabled
            input.includeSignals = true
            return VitalsEngine.analyzeFace(input, cfg)
        }
    }

    /// The live waveform: the last `seconds` of the raw channel, resampled to fs, normalised, inverted and band-passed
    /// with the engine's own functions. Empty below 3 s of frames.
    static func liveWaveform(t: [Double], values: [Double], seconds: Double = 8, _ cfg: VitalsConfig) -> [Double] {
        guard let last = t.last, let first = t.first, last - first >= 3000 else { return [] }
        var start = 0
        while start < t.count - 1, last - t[start] > seconds * 1000 { start += 1 }
        let ts = Array(t[start...]), vs = Array(values[start...])
        let valid = [Bool](repeating: true, count: ts.count)
        let fs = cfg.signal.fs
        let grid = VitalsEngine.resampleUniform(ts, vs, valid, fs, .greatestFiniteMagnitude).values
        guard grid.count >= 3 else { return [] }
        return VitalsEngine.preprocess(grid, fs, cfg, invert: true)
    }
}

/// One scan's live flow (docs/camera-vitals.md §7.1 Scan flow): gates per frame, the clock that starts once the gates
/// have passed for 2 s and pauses while they fail for more than 3 s, the live waveform, the analysis at `target_s` with
/// `scan_control` deciding finish or extend (re-checked every 5 s up to `max_s`), and cancel. The scan clock follows
/// frame timestamps, so tests drive it with injected frames.
@Observable
@MainActor
final class ScanSession {
    enum Phase: Equatable {
        case ready, starting, live, analysing, finished, cancelled
        case failed(VitalsSourceError)
    }

    static let gateSettleMs = 2000.0
    static let pauseAfterMs = 3000.0
    static let recheckS = 5.0

    let mode: VitalsMode
    let config: VitalsConfig
    let sessionID: String?
    var context = "resting"
    var options = VitalsAnalysisOptions()

    private(set) var phase: Phase = .ready
    /// Guidance key of the first failing gate, or "ok".
    private(set) var guidanceKey: String
    private(set) var clockS = 0.0
    private(set) var clockStarted = false
    private(set) var isPaused = false
    private(set) var isExtending = false
    private(set) var isChecking = false
    private(set) var waveform: [Double] = []
    /// Share of the last 5 s of frames passing the gates (0–1).
    private(set) var signalShare = 0.0
    /// Score of the latest analysis (at target and every re-check).
    private(set) var lastQuality: Double?
    private(set) var result: VitalScanResult?
    /// The frames the final result was computed from (for `frame_stats`).
    private(set) var analysedBuffer: VitalsFrameBuffer?
    /// Wall-clock time of the first analysed frame.
    private(set) var startedAt: Date?
    private(set) var cameraConfiguration: CameraConfiguration?

    @ObservationIgnored let source: VitalsFrameSource?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var buffer: VitalsFrameBuffer
    @ObservationIgnored private var bufferStartWall: Date?
    @ObservationIgnored private var passStartMs: Double?
    @ObservationIgnored private var failStartMs: Double?
    @ObservationIgnored private var lastTMs: Double?
    @ObservationIgnored private var nextCheckS: Double
    @ObservationIgnored private var exposureLocked = false
    @ObservationIgnored private var recent: [(t: Double, v: Double, ok: Bool)] = []
    @ObservationIgnored private var framesSinceWave = 0
    @ObservationIgnored private(set) var analysisTask: Task<Void, Never>?
    @ObservationIgnored private var sourceStopped = false

    init(mode: VitalsMode, source: VitalsFrameSource?, config: VitalsConfig = .shared, sessionID: String? = nil,
         now: @escaping () -> Date = Date.init) {
        self.mode = mode
        self.source = source
        self.config = config
        self.sessionID = sessionID
        self.now = now
        buffer = VitalsFrameBuffer(mode: mode)
        guidanceKey = mode == .finger ? "no_finger" : "face_none"
        nextCheckS = config.scan.targetS
    }

    var targetS: Double { config.scan.targetS }
    var maxS: Double { config.scan.maxS }
    var bufferedFrameCount: Int { buffer.count }

    // MARK: Control

    func start() async {
        guard phase == .ready, let source else { return }
        phase = .starting
        UIApplication.shared.isIdleTimerDisabled = true
        do {
            try await source.start { [weak self] frame in self?.ingest(frame) }
            if phase == .starting { phase = .live }
        } catch let error as VitalsSourceError {
            phase = .failed(error)
            UIApplication.shared.isIdleTimerDisabled = false
        } catch {
            phase = .failed(.cameraUnavailable)
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    /// Live without a source (tests feed frames with `ingest`).
    func beginWithoutSource() {
        guard phase == .ready else { return }
        phase = .live
    }

    func cancel() {
        analysisTask?.cancel()
        analysisTask = nil
        stopSource()
        phase = .cancelled
    }

    private func stopSource() {
        guard !sourceStopped else { return }
        sourceStopped = true
        cameraConfiguration = source?.cameraConfiguration()
        source?.stop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    // MARK: Frames

    /// The guidance key of one frame: "ok" when every gate passes.
    func gate(_ frame: VitalsFrame) -> String {
        switch frame {
        case .finger(let f):
            return VitalsEngine.fingerDetect(f, config)
        case .face(let s):
            var rows: [String: [[Double]]] = [:]
            for name in config.face.rois { rows[name] = [s.rois[name] ?? [0, 0, 0, 0]] }
            let one = VitalsEngine.FaceFrames(tMs: [s.tMs], rois: rows, motion: [s.motion], yaw: [s.yaw], pitch: [s.pitch],
                                              luma: [s.luma], faceCount: [s.faceCount], faceFraction: [s.faceFraction])
            return VitalsEngine.faceFrameGate(one, 0, config.face.rois, config) ?? "ok"
        }
    }

    func ingest(_ frame: VitalsFrame) {
        guard phase == .live || phase == .analysing else { return }
        let t = frame.tMs
        let key = gate(frame)
        let ok = key == "ok"
        guidanceKey = key
        let dt = lastTMs.map { max(0, t - $0) } ?? 0
        lastTMs = t
        trackRecent(frame, t: t, ok: ok)

        if ok {
            failStartMs = nil
            if passStartMs == nil { passStartMs = t }
        } else {
            passStartMs = clockStarted ? passStartMs : nil
            if failStartMs == nil { failStartMs = t }
        }

        if !clockStarted {
            if ok, let ps = passStartMs {
                if bufferStartWall == nil || buffer.isEmpty { bufferStartWall = now() }
                buffer.append(frame)
                if t - ps >= Self.gateSettleMs {
                    clockStarted = true
                    if mode == .face, !exposureLocked {
                        exposureLocked = true
                        source?.lockExposureAndWhiteBalance()
                    }
                }
            } else {
                // Gates failed before the clock started: start over.
                buffer.removeAll()
                bufferStartWall = nil
            }
            return
        }

        buffer.append(frame)
        let failingFor = failStartMs.map { t - $0 } ?? 0
        isPaused = !ok && failingFor > Self.pauseAfterMs
        if !isPaused { clockS += dt / 1000 }
        checkProgress()
    }

    private func trackRecent(_ frame: VitalsFrame, t: Double, ok: Bool) {
        let v: Double
        switch frame {
        case .finger(let f): v = f[1]
        case .face(let s):
            var acc = 0.0, n = 0.0
            for name in config.face.rois { if let r = s.rois[name], r[3] > 0 { acc += r[1]; n += 1 } }
            v = n > 0 ? acc / n : (recent.last?.v ?? 0)
        }
        recent.append((t, v, ok))
        while let first = recent.first, t - first.t > 8000 { recent.removeFirst() }
        var okCount = 0, n = 0
        for r in recent where t - r.t <= 5000 {
            n += 1
            if r.ok { okCount += 1 }
        }
        signalShare = n > 0 ? Double(okCount) / Double(n) : 0
        framesSinceWave += 1
        if framesSinceWave >= 3 {
            framesSinceWave = 0
            waveform = VitalsAnalyzer.liveWaveform(t: recent.map(\.t), values: recent.map(\.v), config)
        }
    }

    private func checkProgress() {
        guard analysisTask == nil else { return }
        if clockS >= maxS {
            runAnalysis(final: true)
        } else if clockS >= nextCheckS {
            runAnalysis(final: false)
        }
    }

    private func runAnalysis(final: Bool) {
        let snapshot = buffer
        let elapsed = clockS
        let options = options
        let cfg = config
        isChecking = true
        if final {
            stopSource()
            phase = .analysing
        } else if !isExtending {
            phase = .analysing
        }
        analysisTask = Task { [weak self] in
            let result = await Task.detached(priority: .userInitiated) {
                VitalsAnalyzer.analyze(snapshot, options: options, cfg)
            }.value
            guard let self, !Task.isCancelled else { return }
            self.analysisDone(result, buffer: snapshot, elapsedS: elapsed, final: final)
        }
    }

    private func analysisDone(_ analysed: VitalScanResult, buffer snapshot: VitalsFrameBuffer, elapsedS: Double, final: Bool) {
        analysisTask = nil
        isChecking = false
        guard phase == .live || phase == .analysing else { return }
        lastQuality = analysed.qualityScore
        let decision = final ? "finish" : VitalsEngine.scanControl(elapsedS: elapsedS, quality: analysed.qualityScore, config)
        if decision == "finish" {
            finish(analysed, buffer: snapshot)
        } else {
            isExtending = true
            nextCheckS = elapsedS + Self.recheckS
            phase = .live
            checkProgress()
        }
    }

    private func finish(_ analysed: VitalScanResult, buffer snapshot: VitalsFrameBuffer) {
        stopSource()
        result = analysed
        analysedBuffer = snapshot
        startedAt = bufferStartWall ?? now()
        phase = .finished
    }
}
