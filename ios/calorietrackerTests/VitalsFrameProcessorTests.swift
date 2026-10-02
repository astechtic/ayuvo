import CoreGraphics
import CoreVideo
import Foundation
import Testing
@testable import calorietracker

/// Per-frame statistics on synthetic BGRA buffers: the finger ROI, the face ROI polygons, the YCbCr skin mask, the
/// EMA and the motion score (docs/camera-vitals.md §7.1 Acquisition).
struct VitalsFrameProcessorTests {
    /// A BGRA buffer filled by `color(x, y) -> (r, g, b)`.
    static func buffer(width: Int, height: Int, _ color: (Int, Int) -> (UInt8, UInt8, UInt8)) throws -> CVPixelBuffer {
        var out: CVPixelBuffer?
        let status = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, nil, &out)
        let buffer = try #require(out)
        #expect(status == kCVReturnSuccess)
        CVPixelBufferLockBaseAddress(buffer, [])
        let base = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
        let bpr = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<height {
            for x in 0..<width {
                let (r, g, b) = color(x, y)
                let p = base + y * bpr + x * 4
                p[0] = b; p[1] = g; p[2] = r; p[3] = 255
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return buffer
    }

    static let skin: (UInt8, UInt8, UInt8) = (200, 150, 120)
    static let blue: (UInt8, UInt8, UInt8) = (30, 60, 200)

    // MARK: Finger

    @Test func fingerStatsUseTheCentralHalf() throws {
        // Centre 50 % red-ish fingertip, a bright white border that must be ignored.
        let buf = try Self.buffer(width: 640, height: 480) { x, y in
            (160...479).contains(x) && (120...359).contains(y) ? (x % 2 == 0 ? (220, 50, 20) : (252, 50, 20)) : (255, 255, 255)
        }
        let stats = try #require(BGRAImage.withPixelBuffer(buf) { FingerFrameProcessor.stats($0, step: 1) })
        #expect(abs(stats.r - 236) < 0.01 && stats.g == 50 && stats.b == 20)
        #expect(abs(stats.rStd - 16) < 0.01)
        #expect(abs(stats.satFrac - 0.5) < 0.01)
        let frame = try #require(BGRAImage.withPixelBuffer(buf) { FingerFrameProcessor.frame(tMs: 33.3, $0) })
        #expect(frame.count == 6 && frame[0] == 33.3)
        // The default grid (every 4th pixel) only reads the 220 columns: no saturation, a finger.
        #expect(frame[5] == 0 && VitalsEngine.fingerDetect(frame, VitalsConfig.shared) == "ok")
        let full = [0, stats.r, stats.g, stats.b, stats.rStd, stats.satFrac]
        #expect(VitalsEngine.fingerDetect(full, VitalsConfig.shared) == "pressure")
    }

    // MARK: Face

    /// Eyes at (200, 260) and (280, 260): inter-ocular distance 80 px, upright face.
    static func landmarks(dx: Double = 0, dy: Double = 0) -> FaceLandmarkSet {
        func around(_ x: Double, _ y: Double, _ r: Double = 6) -> [CGPoint] {
            [CGPoint(x: x - r + dx, y: y + dy), CGPoint(x: x + dx, y: y - r / 2 + dy), CGPoint(x: x + r + dx, y: y + dy),
             CGPoint(x: x + dx, y: y + r / 2 + dy)]
        }
        return FaceLandmarkSet(
            leftEye: around(200, 260), rightEye: around(280, 260),
            leftBrow: [CGPoint(x: 180 + dx, y: 240 + dy), CGPoint(x: 215 + dx, y: 236 + dy)],
            rightBrow: [CGPoint(x: 265 + dx, y: 236 + dy), CGPoint(x: 300 + dx, y: 240 + dy)],
            nose: [CGPoint(x: 226 + dx, y: 310 + dy), CGPoint(x: 240 + dx, y: 316 + dy), CGPoint(x: 254 + dx, y: 310 + dy)],
            outerLips: [CGPoint(x: 212 + dx, y: 345 + dy), CGPoint(x: 240 + dx, y: 338 + dy), CGPoint(x: 268 + dx, y: 345 + dy),
                        CGPoint(x: 240 + dx, y: 356 + dy)],
            faceContour: [CGPoint(x: 160 + dx, y: 280 + dy), CGPoint(x: 240 + dx, y: 410 + dy), CGPoint(x: 320 + dx, y: 280 + dy)])
    }

    @Test func roiPolygonsSitOnTheRightFeatures() throws {
        let polys = try #require(SkinRoiExtractor.rawPolygons(Self.landmarks()))
        #expect(Set(polys.keys) == Set(SkinRoiExtractor.roiNames))
        #expect(Set(polys.keys) == Set(VitalsConfig.shared.face.rois))
        func centre(_ name: String) -> CGPoint { FaceLandmarkSet.centroid(polys[name]!)! }
        for (name, poly) in polys { #expect(poly.count == 4, "\(name)") }
        #expect(centre("forehead").y < 236, "forehead above the brows")
        #expect(abs(centre("forehead").x - 240) < 1)
        #expect(centre("left_cheek").x < 226 && centre("left_cheek").y > 266 && centre("left_cheek").y < 345)
        #expect(centre("right_cheek").x > 254 && centre("right_cheek").y > 266)
        #expect(abs(centre("nose").x - 240) < 1 && centre("nose").y > 260 && centre("nose").y < 316)
        #expect(centre("chin").y > 356 && centre("chin").y < 410)
        // Head roll: the polygons follow the eye line.
        let rolled = FaceLandmarkSet(leftEye: [CGPoint(x: 200, y: 240)], rightEye: [CGPoint(x: 280, y: 280)])
        let rp = try #require(SkinRoiExtractor.rawPolygons(rolled))
        #expect(rp["forehead"]![0].x > rp["forehead"]![3].x || rp["forehead"]![0].y > rp["forehead"]![3].y)
        #expect(SkinRoiExtractor.rawPolygons(FaceLandmarkSet(leftEye: [], rightEye: [])) == nil)
    }

    @Test func skinMaskMeanAndFraction() throws {
        #expect(SkinRoiExtractor.isSkin(r: 200, g: 150, b: 120))
        #expect(SkinRoiExtractor.isSkin(r: 120, g: 80, b: 60))
        #expect(!SkinRoiExtractor.isSkin(r: 30, g: 60, b: 200))
        #expect(!SkinRoiExtractor.isSkin(r: 128, g: 128, b: 128))
        // Left half skin, right half blue.
        let buf = try Self.buffer(width: 200, height: 100) { x, _ in x < 100 ? Self.skin : Self.blue }
        let inside: [CGPoint] = [CGPoint(x: 10, y: 10), CGPoint(x: 90, y: 10), CGPoint(x: 90, y: 90), CGPoint(x: 10, y: 90)]
        let all = try #require(BGRAImage.withPixelBuffer(buf) { SkinRoiExtractor.measure(inside, in: $0) })
        #expect(all == [200, 150, 120, 1])
        let half: [CGPoint] = [CGPoint(x: 50, y: 10), CGPoint(x: 150, y: 10), CGPoint(x: 150, y: 90), CGPoint(x: 50, y: 90)]
        let mixed = try #require(BGRAImage.withPixelBuffer(buf) { SkinRoiExtractor.measure(half, in: $0) })
        #expect(Array(mixed.prefix(3)) == [200, 150, 120], "mean over skin pixels only")
        #expect(abs(mixed[3] - 0.5) < 0.03)
        let none: [CGPoint] = [CGPoint(x: 120, y: 10), CGPoint(x: 190, y: 10), CGPoint(x: 190, y: 90)]
        #expect(BGRAImage.withPixelBuffer(buf) { SkinRoiExtractor.measure(none, in: $0) } == [0, 0, 0, 0])
        let luma = try #require(BGRAImage.withPixelBuffer(buf) { SkinRoiExtractor.meanLuma(CGRect(x: 0, y: 0, width: 99, height: 99), in: $0, step: 1) })
        #expect(abs(luma - (0.299 * 200 + 0.587 * 150 + 0.114 * 120)) < 0.01)
    }

    @Test func polygonsAreSmoothedWithEMA() throws {
        var extractor = SkinRoiExtractor()
        let firstPolys = extractor.polygons(Self.landmarks())
        let secondPolys = extractor.polygons(Self.landmarks(dx: 10))
        let first = try #require(firstPolys)
        let second = try #require(secondPolys)
        let raw = try #require(SkinRoiExtractor.rawPolygons(Self.landmarks(dx: 10)))
        for name in SkinRoiExtractor.roiNames {
            for k in 0..<4 {
                let expected = 0.3 * raw[name]![k].x + 0.7 * first[name]![k].x
                #expect(abs(second[name]![k].x - expected) < 1e-9)
                #expect(abs(second[name]![k].x - first[name]![k].x - 3) < 1e-9)
            }
        }
        extractor.reset()
        let afterReset = extractor.polygons(Self.landmarks(dx: 10))
        #expect(afterReset == raw)
    }

    @Test func motionIsDisplacementOverInterOcularDistance() {
        var m = MotionCompensator()
        let a = Self.landmarks(), b = Self.landmarks(dx: 8)
        #expect(a.interOcularDistance == 80)
        #expect(m.motion(a.all, interOcular: 80) == 0)
        #expect(abs(m.motion(b.all, interOcular: 80) - 0.1) < 1e-12)
        #expect(m.motion(b.all, interOcular: 80) == 0)
    }

    @Test func trackedLandmarksFollowTheFaceBox() {
        let lm = Self.landmarks()
        let moved = lm.mapped(from: CGRect(x: 160, y: 220, width: 160, height: 200), to: CGRect(x: 170, y: 230, width: 320, height: 400))
        #expect(moved.leftEye[0] == CGPoint(x: 170 + (194 - 160) * 2, y: 230 + (260 - 220) * 2))
        #expect(moved.interOcularDistance == 160)
    }

    @Test func faceSampleOnAFrameWithoutFaces() throws {
        // A uniform frame: Vision finds no face, so the sample says so and every gate fails with face_none.
        let buf = try Self.buffer(width: 480, height: 640) { _, _ in Self.blue }
        let processor = FaceFrameProcessor()
        let sample = try #require(processor.process(buf, tMs: 0))
        #expect(sample.faceCount == 0 && sample.faceFraction == 0)
        #expect(Set(sample.rois.keys) == Set(VitalsConfig.shared.face.rois))
        #expect(sample.luma > 0)
    }

    @Test func liveWaveformNeedsThreeSeconds() {
        let cfg = VitalsConfig.shared
        let t = (0..<60).map { Double($0) * 33.3 }
        #expect(VitalsAnalyzer.liveWaveform(t: t, values: t.map { 200 + sin($0 / 150) }, cfg).isEmpty)
        let long = (0..<300).map { Double($0) * 33.3 }
        let wave = VitalsAnalyzer.liveWaveform(t: long, values: long.map { 200 + sin($0 / 150) }, cfg)
        #expect(wave.count >= 200 && wave.count <= 245)
    }
}
