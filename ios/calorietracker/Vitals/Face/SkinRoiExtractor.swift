import CoreGraphics
import Foundation

/// Landmarks of one face in pixel coordinates of the upright frame (origin top-left, y down). Groups follow Vision's
/// `VNFaceLandmarks2D` regions; `leftEye` / `rightEye` are the eyes on the image's left / right.
nonisolated struct FaceLandmarkSet: Sendable, Equatable {
    var leftEye: [CGPoint]
    var rightEye: [CGPoint]
    var leftBrow: [CGPoint] = []
    var rightBrow: [CGPoint] = []
    var nose: [CGPoint] = []
    var outerLips: [CGPoint] = []
    var faceContour: [CGPoint] = []

    var all: [CGPoint] { leftEye + rightEye + leftBrow + rightBrow + nose + outerLips + faceContour }

    static func centroid(_ points: [CGPoint]) -> CGPoint? {
        guard !points.isEmpty else { return nil }
        var x = 0.0, y = 0.0
        for p in points { x += p.x; y += p.y }
        return CGPoint(x: x / Double(points.count), y: y / Double(points.count))
    }

    /// Eye centres ordered left → right in the image.
    var eyeCentres: (left: CGPoint, right: CGPoint)? {
        guard let a = Self.centroid(leftEye), let b = Self.centroid(rightEye) else { return nil }
        return a.x <= b.x ? (a, b) : (b, a)
    }

    var interOcularDistance: Double? {
        guard let eyes = eyeCentres else { return nil }
        return hypot(eyes.right.x - eyes.left.x, eyes.right.y - eyes.left.y)
    }

    /// Every point moved by the affine map that takes `from` to `to` (translation + per-axis scale). Used between
    /// full landmark detections, when `VNTrackObjectRequest` only tracks the face box.
    func mapped(from: CGRect, to: CGRect) -> FaceLandmarkSet {
        guard from.width > 0, from.height > 0 else { return self }
        let sx = to.width / from.width, sy = to.height / from.height
        func m(_ ps: [CGPoint]) -> [CGPoint] {
            ps.map { CGPoint(x: to.minX + ($0.x - from.minX) * sx, y: to.minY + ($0.y - from.minY) * sy) }
        }
        return FaceLandmarkSet(leftEye: m(leftEye), rightEye: m(rightEye), leftBrow: m(leftBrow), rightBrow: m(rightBrow),
                               nose: m(nose), outerLips: m(outerLips), faceContour: m(faceContour))
    }
}

/// Skin ROIs of the face (docs/camera-vitals.md §7.1 Acquisition): forehead (above the eyebrows), cheeks (between the
/// lower eyelid, the side of the nose and the mouth corner), nose and chin, as 4-point polygons built in the face's own
/// axes (eye line and its perpendicular), so head roll keeps them on the same skin. Points are smoothed with an EMA
/// (α 0.3) and each ROI value is the mean RGB of the pixels passing the YCbCr skin mask (Cb 77–127, Cr 133–173).
nonisolated struct SkinRoiExtractor {
    static let alpha = 0.3
    static let roiNames = ["forehead", "left_cheek", "right_cheek", "nose", "chin"]

    private(set) var smoothed: [String: [CGPoint]] = [:]

    mutating func reset() { smoothed = [:] }

    /// Raw polygons from the landmarks, then the EMA over earlier frames.
    mutating func polygons(_ lm: FaceLandmarkSet) -> [String: [CGPoint]]? {
        guard let raw = Self.rawPolygons(lm) else { return nil }
        var out: [String: [CGPoint]] = [:]
        for (name, poly) in raw {
            if let prev = smoothed[name], prev.count == poly.count {
                out[name] = zip(prev, poly).map { p, n in
                    CGPoint(x: Self.alpha * n.x + (1 - Self.alpha) * p.x, y: Self.alpha * n.y + (1 - Self.alpha) * p.y)
                }
            } else {
                out[name] = poly
            }
        }
        smoothed = out
        return out
    }

    static func rawPolygons(_ lm: FaceLandmarkSet) -> [String: [CGPoint]]? {
        guard let eyes = lm.eyeCentres else { return nil }
        let l = eyes.left, r = eyes.right
        let d = hypot(r.x - l.x, r.y - l.y)
        guard d > 1 else { return nil }
        // Face axes: ex along the eye line (image left → right), ey perpendicular, pointing down the face.
        let ex = CGPoint(x: (r.x - l.x) / d, y: (r.y - l.y) / d)
        let ey = CGPoint(x: -ex.y, y: ex.x)
        let mid = CGPoint(x: (l.x + r.x) / 2, y: (l.y + r.y) / 2)
        func at(_ o: CGPoint, _ u: Double, _ v: Double) -> CGPoint {
            CGPoint(x: o.x + (ex.x * u + ey.x * v) * d, y: o.y + (ex.y * u + ey.y * v) * d)
        }
        /// Local coordinates (u along ex, v along ey, in eye distances) relative to the eye midpoint.
        func local(_ p: CGPoint) -> (u: Double, v: Double) {
            let dx = p.x - mid.x, dy = p.y - mid.y
            return ((dx * ex.x + dy * ex.y) / d, (dx * ey.x + dy * ey.y) / d)
        }
        func extreme(_ ps: [CGPoint], _ key: (Double, Double) -> Double, max: Bool) -> CGPoint? {
            ps.max { a, b in
                let la = local(a), lb = local(b)
                return max ? key(la.u, la.v) < key(lb.u, lb.v) : key(la.u, la.v) > key(lb.u, lb.v)
            }
        }
        // Brow line (fallback: a quarter eye distance above the eyes).
        let browTop = (lm.leftBrow + lm.rightBrow).map { local($0).v }.min() ?? -0.25
        // Nose wings and tip.
        let noseLeft = extreme(lm.nose, { u, _ in u }, max: false).map(local) ?? (u: -0.2, v: 0.6)
        let noseRight = extreme(lm.nose, { u, _ in u }, max: true).map(local) ?? (u: 0.2, v: 0.6)
        // Mouth corners and lower lip.
        let mouthLeft = extreme(lm.outerLips, { u, _ in u }, max: false).map(local) ?? (u: -0.4, v: 1.05)
        let mouthRight = extreme(lm.outerLips, { u, _ in u }, max: true).map(local) ?? (u: 0.4, v: 1.05)
        let lipBottom = lm.outerLips.map { local($0).v }.max() ?? 1.2
        let chinBottom = lm.faceContour.map { local($0).v }.max().map { Swift.max($0, lipBottom + 0.3) } ?? (lipBottom + 0.5)
        let eyeLow = 0.28  // below the lower eyelid

        var out: [String: [CGPoint]] = [:]
        out["forehead"] = [at(mid, -0.55, browTop - 0.12), at(mid, 0.55, browTop - 0.12),
                           at(mid, 0.45, browTop - 0.6), at(mid, -0.45, browTop - 0.6)]
        out["left_cheek"] = [at(mid, -0.75, eyeLow), at(mid, -0.35, eyeLow),
                             at(mid, noseLeft.u - 0.06, noseLeft.v), at(mid, mouthLeft.u - 0.06, mouthLeft.v - 0.1)]
        out["right_cheek"] = [at(mid, 0.35, eyeLow), at(mid, 0.75, eyeLow),
                              at(mid, mouthRight.u + 0.06, mouthRight.v - 0.1), at(mid, noseRight.u + 0.06, noseRight.v)]
        out["nose"] = [at(mid, -0.1, 0.2), at(mid, 0.1, 0.2),
                       at(mid, noseRight.u * 0.6, noseRight.v - 0.08), at(mid, noseLeft.u * 0.6, noseLeft.v - 0.08)]
        let chinTop = lipBottom + 0.08, chinLow = chinBottom - 0.08
        out["chin"] = [at(mid, -0.3, chinTop), at(mid, 0.3, chinTop), at(mid, 0.2, chinLow), at(mid, -0.2, chinLow)]
        return out
    }

    // MARK: Pixels

    /// BT.601 full-range chroma skin test (Cb 77–127, Cr 133–173).
    @inline(__always)
    static func isSkin(r: Double, g: Double, b: Double) -> Bool {
        let cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        let cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173
    }

    @inline(__always)
    static func luma(r: Double, g: Double, b: Double) -> Double { 0.299 * r + 0.587 * g + 0.114 * b }

    /// Even-odd point-in-polygon test.
    static func contains(_ poly: [CGPoint], _ x: Double, _ y: Double) -> Bool {
        var inside = false
        var j = poly.count - 1
        for i in 0..<poly.count {
            let a = poly[i], b = poly[j]
            if (a.y > y) != (b.y > y), x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x { inside.toggle() }
            j = i
        }
        return inside
    }

    /// `[r, g, b, skin_frac]`: mean RGB of the skin pixels inside the polygon (sampled every `step` px) and the share
    /// of sampled pixels that are skin. `[0, 0, 0, 0]` when the polygon covers no pixel.
    static func measure(_ poly: [CGPoint], in image: BGRAImage, step: Int = 2) -> [Double] {
        guard poly.count >= 3 else { return [0, 0, 0, 0] }
        var minX = Double.infinity, maxX = -Double.infinity, minY = Double.infinity, maxY = -Double.infinity
        for p in poly {
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        let x0 = max(0, Int(minX.rounded(.down))), x1 = min(image.width - 1, Int(maxX.rounded(.up)))
        let y0 = max(0, Int(minY.rounded(.down))), y1 = min(image.height - 1, Int(maxY.rounded(.up)))
        guard x0 <= x1, y0 <= y1 else { return [0, 0, 0, 0] }
        var sr = 0.0, sg = 0.0, sb = 0.0
        var total = 0, skin = 0
        var y = y0
        while y <= y1 {
            var x = x0
            while x <= x1 {
                if contains(poly, Double(x) + 0.5, Double(y) + 0.5) {
                    total += 1
                    let c = image.rgb(x, y)
                    if isSkin(r: c.r, g: c.g, b: c.b) {
                        skin += 1
                        sr += c.r; sg += c.g; sb += c.b
                    }
                }
                x += step
            }
            y += step
        }
        guard total > 0 else { return [0, 0, 0, 0] }
        guard skin > 0 else { return [0, 0, 0, 0] }
        let n = Double(skin)
        return [sr / n, sg / n, sb / n, Double(skin) / Double(total)]
    }

    /// Mean luma (BT.601) over a rectangle, sampled every `step` px.
    static func meanLuma(_ rect: CGRect, in image: BGRAImage, step: Int = 4) -> Double {
        let x0 = max(0, Int(rect.minX)), x1 = min(image.width - 1, Int(rect.maxX))
        let y0 = max(0, Int(rect.minY)), y1 = min(image.height - 1, Int(rect.maxY))
        guard x0 <= x1, y0 <= y1 else { return 0 }
        var acc = 0.0, n = 0
        var y = y0
        while y <= y1 {
            var x = x0
            while x <= x1 {
                let c = image.rgb(x, y)
                acc += luma(r: c.r, g: c.g, b: c.b)
                n += 1
                x += step
            }
            y += step
        }
        return n > 0 ? acc / Double(n) : 0
    }
}

/// Face motion for the gates: mean landmark displacement since the previous frame divided by the inter-ocular
/// distance (docs/camera-vitals.md §2.1).
nonisolated struct MotionCompensator {
    private var previous: [CGPoint]?

    mutating func reset() { previous = nil }

    /// 0 for the first frame of a track (or when the landmark count changes).
    mutating func motion(_ points: [CGPoint], interOcular: Double) -> Double {
        defer { previous = points }
        guard let prev = previous, prev.count == points.count, !points.isEmpty, interOcular > 0 else { return 0 }
        var acc = 0.0
        for i in 0..<points.count { acc += hypot(points[i].x - prev[i].x, points[i].y - prev[i].y) }
        return acc / Double(points.count) / interOcular
    }
}
