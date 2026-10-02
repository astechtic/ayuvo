import AVFoundation
import CoreMedia
import Foundation

/// One frame of per-frame statistics, never an image (docs/camera-vitals.md §1 rule 3). `t_ms` is relative to the
/// source's first frame.
nonisolated enum VitalsFrame: Sendable {
    case finger([Double])
    case face(FaceFrameSample)

    var tMs: Double {
        switch self {
        case .finger(let f): f[0]
        case .face(let s): s.tMs
        }
    }
}

/// Where scan frames come from: the camera or the synthetic replay (`-AyuvoVitalsReplay`).
@MainActor
protocol VitalsFrameSource: AnyObject {
    var mode: VitalsMode { get }
    var isReplay: Bool { get }
    /// The front-camera session for the face preview; nil for finger scans and the replay.
    var previewSession: AVCaptureSession? { get }
    /// Starts delivering frames. `onFrame` is called on the main queue, in order.
    func start(onFrame: @escaping @MainActor (VitalsFrame) -> Void) async throws
    func stop()
    /// Face scans: 2 s after the gates first pass. Finger scans lock on their own after a 2 s settle.
    func lockExposureAndWhiteBalance()
    func cameraConfiguration() -> CameraConfiguration
    /// `vital_device_profiles.capability_json`, nil when there is no camera.
    func capabilityJSON() -> RJ?
}

enum VitalsSourceError: Error, Equatable {
    case permissionDenied
    case cameraUnavailable
}

// MARK: - Camera

/// Runs on the capture queue: turns each sample buffer into the frame encoding and hands it to the main queue.
nonisolated final class VitalsCaptureProcessor: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    private let mode: VitalsMode
    private let deliver: @Sendable (VitalsFrame) -> Void
    private let onFirstFrame: @Sendable (Int, Int) -> Void
    private let onSettled: @Sendable () -> Void
    private let face: FaceFrameProcessor?
    private var firstPTS: CMTime?
    private var settled = false

    static let settleMs = 2000.0

    init(mode: VitalsMode, deliver: @escaping @Sendable (VitalsFrame) -> Void,
         onFirstFrame: @escaping @Sendable (Int, Int) -> Void, onSettled: @escaping @Sendable () -> Void) {
        self.mode = mode
        self.deliver = deliver
        self.onFirstFrame = onFirstFrame
        self.onSettled = onSettled
        face = mode == .face ? FaceFrameProcessor() : nil
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if firstPTS == nil {
            firstPTS = pts
            onFirstFrame(CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer))
        }
        let tMs = CMTimeGetSeconds(CMTimeSubtract(pts, firstPTS!)) * 1000
        if mode == .finger, !settled, tMs >= Self.settleMs {
            settled = true
            onSettled()
        }
        let frame: VitalsFrame?
        switch mode {
        case .finger:
            frame = BGRAImage.withPixelBuffer(buffer) { .finger(FingerFrameProcessor.frame(tMs: tMs, $0)) }
        case .face:
            frame = face?.process(buffer, tMs: tMs).map { .face($0) }
        }
        if let frame { deliver(frame) }
    }
}

@MainActor
final class CameraFrameSource: VitalsFrameSource {
    let mode: VitalsMode
    let isReplay = false
    private let camera: CameraManager
    private var processor: VitalsCaptureProcessor?

    init(mode: VitalsMode) {
        self.mode = mode
        camera = CameraManager(mode: mode)
    }

    var previewSession: AVCaptureSession? { mode == .face ? camera.session : nil }

    /// Asks for camera access when it has not been decided yet.
    static func requestAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    func start(onFrame: @escaping @MainActor (VitalsFrame) -> Void) async throws {
        guard await Self.requestAccess() else { throw VitalsSourceError.permissionDenied }
        let camera = camera
        let processor = VitalsCaptureProcessor(
            mode: mode,
            deliver: { frame in DispatchQueue.main.async { MainActor.assumeIsolated { onFrame(frame) } } },
            onFirstFrame: { w, h in camera.recordDimensions(width: w, height: h) },
            onSettled: { camera.lockExposureAndWhiteBalance() })
        self.processor = processor
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            camera.sessionQueue.async {
                do {
                    try camera.configure(delegate: processor)
                    cont.resume()
                } catch {
                    cont.resume(throwing: VitalsSourceError.cameraUnavailable)
                }
            }
        }
        camera.start()
    }

    func stop() { camera.stop() }

    func lockExposureAndWhiteBalance() { camera.lockExposureAndWhiteBalance() }

    func cameraConfiguration() -> CameraConfiguration { camera.configuration }

    func capabilityJSON() -> RJ? { camera.capabilityJSON() }
}

// MARK: - Replay

/// Feeds `synth_finger` / `synth_face` frames (the contract's deterministic generators) in real time at about 30 fps,
/// so the whole flow runs without a camera: the simulator, UI tests and `-AyuvoVitalsReplay finger|face`.
@MainActor
final class ReplayFrameSource: VitalsFrameSource {
    let mode: VitalsMode
    let isReplay = true
    let previewSession: AVCaptureSession? = nil
    private let frames: [VitalsFrame]
    private var timer: Timer?
    private var startedAt: Date?
    private var index = 0
    /// Replay speed (1 = real time).
    private let speed: Double

    init(mode: VitalsMode, durationS: Double = 120, speed: Double = 1) {
        self.mode = mode
        self.speed = speed
        frames = Self.frames(mode: mode, durationS: durationS)
    }

    /// The replay signal: a clean resting pulse (the contract's `resting_72` / `still_70` vector specs).
    static func spec(mode: VitalsMode, durationS: Double) -> VitalsSynth.Spec {
        var spec = VitalsSynth.Spec(fs: 30, durationS: durationS, hrBpm: mode == .finger ? 72 : 70)
        spec.jitterMs = mode == .finger ? 3 : 4
        spec.respBpm = mode == .finger ? 15 : 14
        spec.rsaMs = 30
        spec.seed = mode == .finger ? 7 : 11
        if mode == .face {
            spec.illum = 0.01
            spec.illumHz = 0.3
            for name in VitalsConfig.shared.face.rois { spec.rois[name] = VitalsSynth.Roi(ac: 0.004, noise: 0.002) }
        }
        return spec
    }

    static func frames(mode: VitalsMode, durationS: Double) -> [VitalsFrame] {
        let spec = spec(mode: mode, durationS: durationS)
        switch mode {
        case .finger:
            return VitalsSynth.synthFinger(spec).map { .finger($0) }
        case .face:
            let f = VitalsSynth.synthFace(spec)
            return (0..<f.tMs.count).map { i in
                var rois: [String: [Double]] = [:]
                for (name, rows) in f.rois { rois[name] = rows[i] }
                return .face(FaceFrameSample(tMs: f.tMs[i], motion: f.motion[i], yaw: f.yaw[i], pitch: f.pitch[i], luma: f.luma[i],
                                             faceCount: f.faceCount[i], faceFraction: f.faceFraction[i], rois: rois))
            }
        }
    }

    func start(onFrame: @escaping @MainActor (VitalsFrame) -> Void) async throws {
        startedAt = Date()
        index = 0
        timer?.invalidate()
        let timer = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick(onFrame) }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func tick(_ onFrame: @MainActor (VitalsFrame) -> Void) {
        guard let startedAt else { return }
        let elapsedMs = Date().timeIntervalSince(startedAt) * 1000 * speed
        while index < frames.count, frames[index].tMs <= elapsedMs {
            onFrame(frames[index])
            index += 1
        }
        if index >= frames.count { stop() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    func lockExposureAndWhiteBalance() {}

    func cameraConfiguration() -> CameraConfiguration {
        CameraConfiguration(position: mode == .finger ? "back" : "front", lens: "replay", width: 0, height: 0,
                            targetFps: CameraManager.targetFps, achievedFps: nil, exposureMs: nil, iso: nil,
                            whiteBalanceLocked: false, torch: false)
    }

    func capabilityJSON() -> RJ? { nil }
}
