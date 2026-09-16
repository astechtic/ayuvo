import Foundation
import ImageIO
import UIKit

/// Optional medicine photos under `Application Support/Ayuvo/Medications/photos/<medicationID>.jpg`
/// (+ `<medicationID>-thumb.jpg`). A separate store from `FoodImageStore` so these never enter the
/// cloud backup; the directory is backup-excluded through `MedicationsLocation.prepareDirectory`.
/// `medications.photo_path` stores the path relative to the Medications directory (`photos/<id>.jpg`).
nonisolated struct MedicationPhotoStore: Sendable {
    static let maxPixelSize = 1024
    static let thumbnailMaxPixelSize = 320
    static let jpegQuality: CGFloat = 0.85
    static let thumbnailJPEGQuality: CGFloat = 0.76

    let directory: URL
    private var fileManager: FileManager { .default }

    init(directory: URL = MedicationsLocation.photosDirectory()) {
        self.directory = directory
    }

    static func fileName(medicationID: String) -> String { "\(medicationID).jpg" }
    static func thumbnailFileName(medicationID: String) -> String { "\(medicationID)-thumb.jpg" }

    /// Relative path stored in `medications.photo_path`.
    static func relativePath(medicationID: String) -> String {
        "\(MedicationsLocation.photosDirectoryName)/\(fileName(medicationID: medicationID))"
    }

    /// Downsamples to ≤ 1024 px, writes the JPEG and a 320 px thumbnail, returns the relative path.
    @discardableResult
    func save(_ data: Data, medicationID: String) throws -> String {
        try MedicationsLocation.prepareDirectory(directory, fileManager: fileManager)
        guard let image = Self.downsample(data, maxPixelSize: Self.maxPixelSize),
              let jpeg = image.jpegData(compressionQuality: Self.jpegQuality) else {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSLocalizedDescriptionKey: "The photo could not be decoded."])
        }
        try jpeg.write(to: url(medicationID: medicationID), options: .atomic)
        if let thumbnail = Self.downsample(jpeg, maxPixelSize: Self.thumbnailMaxPixelSize),
           let thumbnailData = thumbnail.jpegData(compressionQuality: Self.thumbnailJPEGQuality) {
            try? thumbnailData.write(to: thumbnailURL(medicationID: medicationID), options: .atomic)
        }
        return Self.relativePath(medicationID: medicationID)
    }

    func url(medicationID: String) -> URL {
        directory.appendingPathComponent(Self.fileName(medicationID: medicationID), isDirectory: false)
    }

    func thumbnailURL(medicationID: String) -> URL {
        directory.appendingPathComponent(Self.thumbnailFileName(medicationID: medicationID), isDirectory: false)
    }

    /// Resolves a stored relative path (`photos/<id>.jpg`) when the file exists.
    func url(for relativePath: String) -> URL? {
        let url = directory.appendingPathComponent((relativePath as NSString).lastPathComponent, isDirectory: false)
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    func hasPhoto(medicationID: String) -> Bool {
        fileManager.fileExists(atPath: url(medicationID: medicationID).path)
    }

    func loadThumbnail(medicationID: String) -> UIImage? {
        let thumbnail = thumbnailURL(medicationID: medicationID)
        if fileManager.fileExists(atPath: thumbnail.path), let image = UIImage(contentsOfFile: thumbnail.path) {
            return image
        }
        let full = url(medicationID: medicationID)
        guard fileManager.fileExists(atPath: full.path), let data = try? Data(contentsOf: full) else { return nil }
        return Self.downsample(data, maxPixelSize: Self.thumbnailMaxPixelSize)
    }

    func loadImage(medicationID: String) -> UIImage? {
        let full = url(medicationID: medicationID)
        guard fileManager.fileExists(atPath: full.path) else { return nil }
        return UIImage(contentsOfFile: full.path)
    }

    /// Best-effort delete of the photo and its thumbnail.
    func delete(medicationID: String) {
        try? fileManager.removeItem(at: url(medicationID: medicationID))
        try? fileManager.removeItem(at: thumbnailURL(medicationID: medicationID))
    }

    /// Wipes the photos directory (Delete All Data).
    func deleteAll() {
        try? fileManager.removeItem(at: directory)
    }

    func totalSizeBytes() -> Int64 {
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else { return 0 }
        return names.reduce(into: Int64(0)) { total, name in
            let path = directory.appendingPathComponent(name).path
            if let size = (try? fileManager.attributesOfItem(atPath: path)[.size]) as? NSNumber {
                total += size.int64Value
            }
        }
    }

    /// ImageIO bounded decode with EXIF orientation applied.
    static func downsample(_ data: Data, maxPixelSize: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return UIImage(data: data)
        }
        return UIImage(cgImage: cgImage)
    }
}
