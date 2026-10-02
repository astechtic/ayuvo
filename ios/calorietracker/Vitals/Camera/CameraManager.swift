import AVFoundation
import CoreMedia
import Foundation

/// What `camera_json` records for a scan (docs/camera-vitals.md §7.1 Saved record).
nonisolated struct CameraConfiguration: Sendable, Equatable {
    var position: String          // back | front
    var lens: String              // wide | replay
    var width: Int
    var height: Int
    var targetFps: Double
    var achievedFps: Double?
    var exposureMs: Double?
    var iso: Double?
    var whiteBalanceLocked: Bool
    var torch: Bool

    var json: RJ {
        .obj(["position": .str(position), "lens": .str(lens), "width": .int(width), "height": .int(height),
              "target_fps": .num(targetFps), "achieved_fps": .f(achievedFps.map { VitalsMath.roundTo($0, 2) }),
              "exposure_ms": .f(exposureMs.map { VitalsMath.roundTo($0, 3) }), "iso": .f(iso.map { VitalsMath.roundTo($0, 1) }),
              "white_balance_locked": .bool(whiteBalanceLocked), "torch": .bool(torch)])
    }

    /// Mean frame rate over frame timestamps (ms); nil with fewer than two frames.
    static func achievedFps(_ tMs: [Double]) -> Double? {
        guard tMs.count >= 2, let first = tMs.first, let last = tMs.last, last > first else { return nil }
        return Double(tMs.count - 1) / ((last - first) / 1000)
    }
}

/// Torch for finger scans (`setTorchModeOn(level:)`).
nonisolated enum TorchController {
    static func on(_ device: AVCaptureDevice, level: Float = 1.0) -> Bool {
        guard device.hasTorch, device.isTorchAvailable else { return false }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            try device.setTorchModeOn(level: min(level, AVCaptureDevice.maxAvailableTorchLevel))
            return true
        } catch {
            return false
        }
    }

    static func off(_ device: AVCaptureDevice) {
        guard device.hasTorch, device.torchMode != .off else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = .off
            device.unlockForConfiguration()
        } catch {}
    }
}

/// `AVCaptureSession` for camera vitals: 640×480 preset, BGRA video data output with late frames discarded on its own
/// serial queue, 30 fps through `activeVideoMin/MaxFrameDuration` (a format that supports it is chosen when the
/// preset's does not), the rear wide camera with the torch for finger scans and the front camera for face scans.
/// Configuration runs on `sessionQueue`; frames arrive on `videoQueue`.
nonisolated final class CameraManager: @unchecked Sendable {
    enum SetupError: Error { case noCamera, cannotAddInput, cannotAddOutput }

    static let targetFps = 30.0

    let mode: VitalsMode
    let session = AVCaptureSession()
    let sessionQueue = DispatchQueue(label: "ayuvo.vitals.session")
    let videoQueue = DispatchQueue(label: "ayuvo.vitals.video", qos: .userInitiated)
    private let output = AVCaptureVideoDataOutput()
    private let lock = NSLock()
    private var device: AVCaptureDevice?
    private var _configuration: CameraConfiguration

    init(mode: VitalsMode) {
        self.mode = mode
        _configuration = CameraConfiguration(position: mode == .finger ? "back" : "front", lens: "wide", width: 640, height: 480,
                                             targetFps: Self.targetFps, achievedFps: nil, exposureMs: nil, iso: nil,
                                             whiteBalanceLocked: false, torch: false)
    }

    var configuration: CameraConfiguration {
        lock.lock(); defer { lock.unlock() }
        return _configuration
    }

    private func update(_ change: (inout CameraConfiguration) -> Void) {
        lock.lock(); defer { lock.unlock() }
        change(&_configuration)
    }

    /// Builds the session. Call on `sessionQueue`.
    func configure(delegate: AVCaptureVideoDataOutputSampleBufferDelegate) throws {
        let position: AVCaptureDevice.Position = mode == .finger ? .back : .front
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            throw SetupError.noCamera
        }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.vga640x480) { session.sessionPreset = .vga640x480 }
        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else { throw SetupError.cannotAddInput }
        session.addInput(input)
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(delegate, queue: videoQueue)
        guard session.canAddOutput(output) else { throw SetupError.cannotAddOutput }
        session.addOutput(output)
        if let connection = output.connection(with: .video), mode == .face {
            // Upright portrait buffers so Vision runs with `.up`; not mirrored (the preview layer mirrors itself).
            if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
        }
        try configureDevice(camera)
        device = camera
    }

    private func configureDevice(_ camera: AVCaptureDevice) throws {
        try camera.lockForConfiguration()
        defer { camera.unlockForConfiguration() }
        let frameDuration = CMTime(value: 1, timescale: CMTimeScale(Self.targetFps))
        if !Self.supports(camera.activeFormat, fps: Self.targetFps), let format = Self.bestFormat(camera.formats, fps: Self.targetFps) {
            camera.activeFormat = format
        }
        if Self.supports(camera.activeFormat, fps: Self.targetFps) {
            camera.activeVideoMinFrameDuration = frameDuration
            camera.activeVideoMaxFrameDuration = frameDuration
        }
        if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
        if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
        if camera.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { camera.whiteBalanceMode = .continuousAutoWhiteBalance }
        if camera.isLowLightBoostSupported { camera.automaticallyEnablesLowLightBoostWhenAvailable = false }
    }

    static func supports(_ format: AVCaptureDevice.Format, fps: Double) -> Bool {
        format.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= fps && fps <= $0.maxFrameRate }
    }

    /// The smallest format (≥ 640 wide) that supports `fps`.
    static func bestFormat(_ formats: [AVCaptureDevice.Format], fps: Double) -> AVCaptureDevice.Format? {
        formats.filter { f in
            let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            return d.width >= 640 && supports(f, fps: fps)
        }
        .min { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return Int(da.width) * Int(da.height) < Int(db.width) * Int(db.height)
        }
    }

    func start() {
        sessionQueue.async { [self] in
            if !session.isRunning { session.startRunning() }
            if mode == .finger, let device {
                let lit = TorchController.on(device)
                update { $0.torch = lit }
            }
        }
    }

    func stop() {
        sessionQueue.async { [self] in
            if let device { TorchController.off(device) }
            if session.isRunning { session.stopRunning() }
        }
    }

    /// Records the delivered buffer size (after rotation).
    func recordDimensions(width: Int, height: Int) {
        update { $0.width = width; $0.height = height }
    }

    /// After the settle: lock exposure and white balance and record exposure / ISO.
    func lockExposureAndWhiteBalance() {
        sessionQueue.async { [self] in
            guard let device else { return }
            do {
                try device.lockForConfiguration()
                if device.isExposureModeSupported(.locked) { device.exposureMode = .locked }
                var wbLocked = false
                if device.isWhiteBalanceModeSupported(.locked) {
                    device.whiteBalanceMode = .locked
                    wbLocked = true
                }
                device.unlockForConfiguration()
                let exposure = CMTimeGetSeconds(device.exposureDuration) * 1000
                let iso = Double(device.iso)
                update {
                    $0.whiteBalanceLocked = wbLocked
                    $0.exposureMs = exposure.isFinite ? exposure : nil
                    $0.iso = iso
                }
            } catch {}
        }
    }

    /// `vital_device_profiles.capability_json`: what this camera can do.
    func capabilityJSON() -> RJ {
        guard let device else { return .obj([:]) }
        let maxFps = device.formats.flatMap { $0.videoSupportedFrameRateRanges.map(\.maxFrameRate) }.max() ?? 0
        let format = device.activeFormat
        return .obj([
            "lens": .str("wide"),
            "has_torch": .bool(device.hasTorch),
            "max_fps": .num(maxFps),
            "supports_30fps": .bool(Self.supports(format, fps: Self.targetFps)),
            "exposure_lock": .bool(device.isExposureModeSupported(.locked)),
            "white_balance_lock": .bool(device.isWhiteBalanceModeSupported(.locked)),
            "min_iso": .num(Double(format.minISO)),
            "max_iso": .num(Double(format.maxISO)),
            "min_exposure_ms": .num(CMTimeGetSeconds(format.minExposureDuration) * 1000),
            "max_exposure_ms": .num(CMTimeGetSeconds(format.maxExposureDuration) * 1000),
        ])
    }
}
