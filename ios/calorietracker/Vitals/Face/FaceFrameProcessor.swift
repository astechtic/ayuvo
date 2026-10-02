import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import Vision

/// One face frame of the engine encoding (docs/camera-vitals.md §2.1): `rois[name] = [r, g, b, skin_frac]`.
nonisolated struct FaceFrameSample: Sendable, Equatable {
    var tMs: Double
    var motion: Double
    var yaw: Double
    var pitch: Double
    var luma: Double
    var faceCount: Double
    var faceFraction: Double
    var rois: [String: [Double]]

    /// A frame with no usable face (every gate value neutral, ROIs empty).
    static func noFace(tMs: Double, count: Double, luma: Double, rois: [String]) -> FaceFrameSample {
        var empty: [String: [Double]] = [:]
        for name in rois { empty[name] = [0, 0, 0, 0] }
        return FaceFrameSample(tMs: tMs, motion: 0, yaw: 0, pitch: 0, luma: luma, faceCount: count, faceFraction: 0, rois: empty)
    }
}

/// Front-camera face statistics on the capture queue (never stores a frame). A full
/// `VNDetectFaceLandmarksRequest` runs every `detectEvery` frames; in between `VNTrackObjectRequest` tracks the face
/// box and the last landmarks follow it. Frames arrive upright (the capture connection rotates them to portrait), so
/// Vision runs with `.up`.
nonisolated final class FaceFrameProcessor: @unchecked Sendable {
    static let detectEvery = 5

    private let roiNames: [String]
    private let sequenceHandler = VNSequenceRequestHandler()
    private var tracking: VNDetectedObjectObservation?
    private var landmarks: FaceLandmarkSet?
    /// Face box in pixels (top-left origin) that `landmarks` belong to.
    private var landmarkBox: CGRect = .zero
    private var faceCount = 0
    private var yaw = 0.0
    private var pitch = 0.0
    private var frameIndex = 0
    private var rois = SkinRoiExtractor()
    private var motion = MotionCompensator()

    init(roiNames: [String] = VitalsConfig.shared.face.rois) {
        self.roiNames = roiNames
    }

    func process(_ buffer: CVPixelBuffer, tMs: Double) -> FaceFrameSample? {
        let width = Double(CVPixelBufferGetWidth(buffer)), height = Double(CVPixelBufferGetHeight(buffer))
        let size = CGSize(width: width, height: height)
        let full = frameIndex % Self.detectEvery == 0 || tracking == nil
        frameIndex += 1
        if full {
            detect(buffer, size: size)
        } else if !track(buffer, size: size) {
            detect(buffer, size: size)
        }
        return BGRAImage.withPixelBuffer(buffer) { image in
            sample(image, tMs: tMs, frameWidth: width)
        }
    }

    // MARK: Vision

    private func detect(_ buffer: CVPixelBuffer, size: CGSize) {
        let request = VNDetectFaceLandmarksRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
        do {
            try handler.perform([request])
        } catch {
            reset(count: 0)
            return
        }
        let faces = request.results ?? []
        faceCount = faces.count
        guard let face = faces.max(by: { $0.boundingBox.width < $1.boundingBox.width }),
              let set = Self.landmarkSet(face, size: size) else {
            reset(count: faces.count)
            return
        }
        landmarks = set
        landmarkBox = Self.pixelRect(face.boundingBox, size: size)
        yaw = (face.yaw?.doubleValue ?? 0) * 180 / .pi
        pitch = (face.pitch?.doubleValue ?? 0) * 180 / .pi
        tracking = VNDetectedObjectObservation(boundingBox: face.boundingBox)
    }

    /// false when tracking was lost (a full detection then runs on the same frame).
    private func track(_ buffer: CVPixelBuffer, size: CGSize) -> Bool {
        guard let tracking, let current = landmarks else { return false }
        let request = VNTrackObjectRequest(detectedObjectObservation: tracking)
        request.trackingLevel = .fast
        do {
            try sequenceHandler.perform([request], on: buffer, orientation: .up)
        } catch {
            return false
        }
        guard let next = request.results?.first as? VNDetectedObjectObservation, next.confidence >= 0.3 else { return false }
        self.tracking = next
        let box = Self.pixelRect(next.boundingBox, size: size)
        landmarks = current.mapped(from: landmarkBox, to: box)
        landmarkBox = box
        return true
    }

    private func reset(count: Int) {
        faceCount = count
        tracking = nil
        landmarks = nil
        rois.reset()
        motion.reset()
    }

    // MARK: Statistics

    private func sample(_ image: BGRAImage, tMs: Double, frameWidth: Double) -> FaceFrameSample {
        let frameLuma = SkinRoiExtractor.meanLuma(CGRect(x: 0, y: 0, width: image.width, height: image.height), in: image, step: 8)
        guard let lm = landmarks, faceCount > 0, let iod = lm.interOcularDistance,
              let polygons = rois.polygons(lm) else {
            return .noFace(tMs: tMs, count: Double(faceCount), luma: frameLuma, rois: roiNames)
        }
        var values: [String: [Double]] = [:]
        for name in roiNames {
            values[name] = polygons[name].map { SkinRoiExtractor.measure($0, in: image) } ?? [0, 0, 0, 0]
        }
        return FaceFrameSample(
            tMs: tMs, motion: motion.motion(lm.all, interOcular: iod), yaw: yaw, pitch: pitch,
            luma: SkinRoiExtractor.meanLuma(landmarkBox, in: image), faceCount: Double(faceCount),
            faceFraction: frameWidth > 0 ? landmarkBox.width / frameWidth : 0, rois: values)
    }

    /// Vision's normalised bottom-left rect → pixels, top-left origin.
    static func pixelRect(_ r: CGRect, size: CGSize) -> CGRect {
        CGRect(x: r.minX * size.width, y: (1 - r.maxY) * size.height, width: r.width * size.width, height: r.height * size.height)
    }

    static func landmarkSet(_ face: VNFaceObservation, size: CGSize) -> FaceLandmarkSet? {
        guard let lm = face.landmarks, let le = lm.leftEye, let re = lm.rightEye else { return nil }
        func pts(_ region: VNFaceLandmarkRegion2D?) -> [CGPoint] {
            guard let region else { return [] }
            return region.pointsInImage(imageSize: size).map { CGPoint(x: $0.x, y: size.height - $0.y) }
        }
        return FaceLandmarkSet(leftEye: pts(le), rightEye: pts(re), leftBrow: pts(lm.leftEyebrow), rightBrow: pts(lm.rightEyebrow),
                               nose: pts(lm.nose), outerLips: pts(lm.outerLips), faceContour: pts(lm.faceContour))
    }
}
