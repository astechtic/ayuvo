import ImageIO
import SwiftUI
import UIKit

/// Exercise media: a user's own photo, or the catalogue's 180×180 thumbnail / animated GIF
/// (© Gym visual) downloaded through `ExerciseMediaStore`. Falls back to a symbol card.
struct AnimatedExerciseVisual: View {
    var exerciseName: String?
    /// User-exercise photo filenames stored in `FoodImageStore`.
    var imagePaths: [String] = []
    var imageURL: URL? = nil
    var gifURL: URL? = nil
    var height: CGFloat = 170
    var fillsWidth = true
    /// When true and a GIF exists, it plays; otherwise the static thumbnail is shown.
    var animatesFrames = true
    /// When set, decoded images are capped at this pixel dimension. Nil sizes to the cell.
    var maxPixelSize: Int? = nil
    var fallbackSystemImage = "figure.strengthtraining.traditional"
    var fallbackTitle = String(localized: "Exercise")
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var animate = false

    var body: some View {
        ZStack {
            if let source = mediaSource {
                ExerciseMediaView(
                    source: source,
                    maxPixelSize: effectiveMaxPixelSize,
                    placeholder: AnyView(fallbackVisual)
                )
            } else {
                fallbackVisual
            }
        }
        .frame(maxWidth: fillsWidth ? .infinity : nil)
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color(uiColor: .separator).opacity(0.35), lineWidth: 0.5)
        )
    }

    private var mediaSource: ExerciseMediaSource? {
        if let photo = imagePaths.first(where: { UserExercise.isUserPhotoFilename($0) }),
           let url = FoodImageStore.shared.fileURL(for: photo) {
            return .localPhoto(url)
        }
        // Reduce Motion shows the still thumbnail instead of the demonstration GIF.
        if animatesFrames, !reduceMotion, let gifURL {
            return .remote(gifURL, animated: true)
        }
        if let imageURL {
            return .remote(imageURL, animated: false)
        }
        if let gifURL {
            // No thumbnail: the GIF's first frame stands in as a still.
            return .remote(gifURL, animated: false)
        }
        return nil
    }

    private var effectiveMaxPixelSize: Int {
        if let maxPixelSize, maxPixelSize > 0 {
            return maxPixelSize
        }
        return max(Int(ceil(height * UIScreen.main.scale)), 1)
    }

    private var fallbackVisual: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color.workoutPanel,
                    Color.workoutCard,
                    Color.workoutAccent.opacity(animate ? 0.20 : 0.10)
                ],
                startPoint: animate ? .topLeading : .bottomLeading,
                endPoint: animate ? .bottomTrailing : .topTrailing
            )
            .animation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true), value: animate)

            VStack(spacing: 12) {
                Image(systemName: fallbackSystemImage)
                    .font(.system(size: height < 80 ? 22 : 36, weight: .semibold))
                    .symbolEffect(.pulse, options: .repeating, value: animate)
                if height >= 80 {
                    Text(fallbackTitle.uppercased())
                        .font(.caption.weight(.bold))
                        .tracking(1.2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            .foregroundStyle(Color.workoutCharcoal)
        }
        .onAppear { animate = true }
    }
}

nonisolated enum ExerciseMediaSource: Hashable, Sendable {
    case localPhoto(URL)
    case remote(URL, animated: Bool)

    var isRemote: Bool {
        if case .remote = self { return true }
        return false
    }
}

private struct ExerciseMediaView: View {
    let source: ExerciseMediaSource
    let maxPixelSize: Int
    let placeholder: AnyView
    @State private var image: UIImage?
    @State private var unavailable = false

    private var taskID: ExerciseMediaTaskID {
        ExerciseMediaTaskID(source: source, maxPixelSize: maxPixelSize)
    }

    var body: some View {
        ZStack {
            if source.isRemote {
                // Dataset media is drawn on a white studio background; keep it framed
                // on white in dark mode rather than as a floating white square.
                Color.white
            } else {
                Color.workoutBackground
            }

            if let image {
                if source.isRemote {
                    ExerciseAnimatedImageView(image: image)
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .clipped()
                }
            } else if unavailable {
                placeholder
            }
        }
        .task(id: taskID) {
            image = nil
            unavailable = false
            var attempt = 0
            while !Task.isCancelled {
                if let loaded = await load() {
                    guard !Task.isCancelled else { return }
                    image = loaded
                    unavailable = false
                    return
                }
                guard !Task.isCancelled else { return }
                unavailable = true
                // Only remote media can appear later (offline, rate limited); keep retrying
                // with backoff while the view is on screen.
                guard source.isRemote else { return }
                try? await Task.sleep(for: ExerciseMediaRetryPolicy.delay(attempt: attempt))
                attempt += 1
            }
        }
    }

    private func load() async -> UIImage? {
        let source = source
        let maxPixelSize = maxPixelSize
        let animated: Bool
        let fileURL: URL
        switch source {
        case .localPhoto(let url):
            fileURL = url
            animated = false
        case .remote(let url, let wantsAnimation):
            guard let local = await ExerciseMediaStore.shared.localURL(for: url) else { return nil }
            fileURL = local
            animated = wantsAnimation
        }
        return await Task.detached(priority: .userInitiated) {
            ExerciseMediaImageCache.shared.image(fileURL: fileURL, animated: animated, maxPixelSize: maxPixelSize)
        }.value
    }
}

/// `UIImageView` plays `UIImage.animatedImage` frames, which SwiftUI's `Image` does not.
private struct ExerciseAnimatedImageView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.clipsToBounds = true
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        guard view.image !== image else { return }
        view.image = image
        if image.images != nil {
            view.startAnimating()
        }
    }
}

/// Backoff between UI-driven retries of unavailable exercise media (matches Android's
/// `mediaRetryDelayMillis`): 15 s, 30 s, then 60 s for as long as the view stays on screen.
nonisolated enum ExerciseMediaRetryPolicy {
    static let baseDelay: Duration = .seconds(15)
    static let maximumDelay: Duration = .seconds(60)

    static func delay(attempt: Int) -> Duration {
        let exponent = min(max(attempt, 0), 2)
        return min(baseDelay * (1 << exponent), maximumDelay)
    }
}

private struct ExerciseMediaTaskID: Equatable {
    let source: ExerciseMediaSource
    let maxPixelSize: Int
}

/// Decoded-image memory cache. NSCache is thread-safe, so this stays off the main actor.
nonisolated final class ExerciseMediaImageCache: @unchecked Sendable {
    static let shared = ExerciseMediaImageCache()

    private let images: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 160
        cache.totalCostLimit = 64 * 1_024 * 1_024
        return cache
    }()

    private init() {}

    func image(fileURL: URL, animated: Bool, maxPixelSize: Int) -> UIImage? {
        let key = "\(fileURL.standardizedFileURL.path)|\(animated)|\(maxPixelSize)" as NSString
        if let cached = images.object(forKey: key) {
            return cached
        }
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil) else { return nil }
        let decoded = animated
            ? Self.animatedImage(from: source, maxPixelSize: maxPixelSize)
            : Self.thumbnail(from: source, index: 0, maxPixelSize: maxPixelSize).map { UIImage(cgImage: $0) }
        guard let decoded else { return nil }
        images.setObject(decoded, forKey: key, cost: decoded.estimatedMemoryCost)
        return decoded
    }

    static func animatedImage(from source: CGImageSource, maxPixelSize: Int) -> UIImage? {
        let count = CGImageSourceGetCount(source)
        guard count > 1 else {
            return thumbnail(from: source, index: 0, maxPixelSize: maxPixelSize).map { UIImage(cgImage: $0) }
        }

        var frames: [UIImage] = []
        var duration: TimeInterval = 0
        frames.reserveCapacity(count)
        for index in 0..<count {
            guard let frame = thumbnail(from: source, index: index, maxPixelSize: maxPixelSize) else { continue }
            frames.append(UIImage(cgImage: frame))
            duration += frameDelay(source: source, index: index)
        }
        guard !frames.isEmpty else { return nil }
        return UIImage.animatedImage(with: frames, duration: duration)
    }

    /// GIF frame delay; browsers treat delays under 20 ms as 100 ms, and so do we.
    static func frameDelay(source: CGImageSource, index: Int) -> TimeInterval {
        guard
            let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
            let gif = properties[kCGImagePropertyGIFDictionary] as? [CFString: Any]
        else { return 0.1 }
        let delay = (gif[kCGImagePropertyGIFUnclampedDelayTime] as? Double)
            ?? (gif[kCGImagePropertyGIFDelayTime] as? Double)
            ?? 0.1
        return delay < 0.02 ? 0.1 : delay
    }

    private static func thumbnail(from source: CGImageSource, index: Int, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: max(maxPixelSize, 1),
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary)
    }
}

nonisolated private extension UIImage {
    var estimatedMemoryCost: Int {
        let frameCount = max(images?.count ?? 1, 1)
        let pixelWidth = max(Int(size.width * scale), 1)
        let pixelHeight = max(Int(size.height * scale), 1)
        return pixelWidth * pixelHeight * 4 * frameCount
    }
}
