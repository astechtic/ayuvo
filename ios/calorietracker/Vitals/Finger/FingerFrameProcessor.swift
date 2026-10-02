import CoreVideo
import Foundation

/// A read-only view of one BGRA frame (8 bits per channel). Pixels are never copied or kept.
nonisolated struct BGRAImage {
    let base: UnsafeRawPointer
    let width: Int
    let height: Int
    let bytesPerRow: Int

    @inline(__always)
    func rgb(_ x: Int, _ y: Int) -> (r: Double, g: Double, b: Double) {
        let p = base.advanced(by: y * bytesPerRow + x * 4).assumingMemoryBound(to: UInt8.self)
        return (Double(p[2]), Double(p[1]), Double(p[0]))
    }

    /// Runs `body` with the locked base address of a BGRA pixel buffer; nil for any other format.
    static func withPixelBuffer<T>(_ buffer: CVPixelBuffer, _ body: (BGRAImage) -> T) -> T? {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA else { return nil }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let image = BGRAImage(base: UnsafeRawPointer(base), width: CVPixelBufferGetWidth(buffer),
                              height: CVPixelBufferGetHeight(buffer), bytesPerRow: CVPixelBufferGetBytesPerRow(buffer))
        return body(image)
    }
}

/// Finger frame encoding `[t_ms, r, g, b, r_std, sat_frac]` (docs/camera-vitals.md §2.1): mean RGB of the central 50 %
/// of the frame on a subsampled grid, the spatial SD of red and the fraction of red pixels at 250 or above.
nonisolated enum FingerFrameProcessor {
    static let saturatedRed = 250.0

    struct Stats: Equatable {
        var r: Double
        var g: Double
        var b: Double
        var rStd: Double
        var satFrac: Double
    }

    /// Grid step so the ROI is sampled on about 80 × 60 points (≈ 4 800 reads, far below a 30 fps budget).
    static func step(width: Int, height: Int) -> Int {
        max(1, min(width, height) / 2 / 60)
    }

    static func stats(_ image: BGRAImage, step: Int? = nil) -> Stats {
        let w = image.width, h = image.height
        let s = step ?? Self.step(width: w, height: h)
        let x0 = w / 4, x1 = w - w / 4
        let y0 = h / 4, y1 = h - h / 4
        var sr = 0.0, sg = 0.0, sb = 0.0, srr = 0.0
        var n = 0, sat = 0
        var y = y0
        while y < y1 {
            let row = image.base.advanced(by: y * image.bytesPerRow).assumingMemoryBound(to: UInt8.self)
            var x = x0
            while x < x1 {
                let o = x * 4
                let b = Double(row[o]), g = Double(row[o + 1]), r = Double(row[o + 2])
                sr += r; sg += g; sb += b; srr += r * r
                if r >= saturatedRed { sat += 1 }
                n += 1
                x += s
            }
            y += s
        }
        guard n > 0 else { return Stats(r: 0, g: 0, b: 0, rStd: 0, satFrac: 0) }
        let dn = Double(n)
        let mr = sr / dn
        let variance = max(0, srr / dn - mr * mr)
        return Stats(r: mr, g: sg / dn, b: sb / dn, rStd: variance.squareRoot(), satFrac: Double(sat) / dn)
    }

    static func frame(tMs: Double, _ image: BGRAImage) -> [Double] {
        let s = stats(image)
        return [tMs, s.r, s.g, s.b, s.rStd, s.satFrac]
    }
}
