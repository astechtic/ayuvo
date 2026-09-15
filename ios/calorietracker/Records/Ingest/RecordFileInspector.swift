import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// What the background basics stage learns from a stored original (contract §4.6).
nonisolated struct RecordInspection: Sendable, Equatable {
    var pageCount: Int?
    var thumbnailJPEG: Data?
    /// `yyyy-MM-dd` from PDF `/CreationDate` or EXIF `DateTimeOriginal`.
    var metadataDate: String?
    var pdfState: RecordPDFState?
    var pixelWidth: Int?
    var pixelHeight: Int?
}

nonisolated enum RecordPDFState: String, Sendable, Equatable {
    case readable
    case locked
    case unreadable
}

/// PDF (CGPDF / PDFKit) and image (ImageIO) inspection. Safe off the main thread; never loads
/// a full-resolution image into memory.
nonisolated enum RecordFileInspector {
    static let thumbnailMaxPixels = 320
    static let thumbnailQuality: CGFloat = 0.76

    static func inspect(url: URL, fileType: RecordFileType, timeZone: TimeZone = .current) -> RecordInspection {
        switch fileType {
        case .pdf:
            return inspectPDF(url: url, timeZone: timeZone)
        case .image:
            return inspectImage(url: url, timeZone: timeZone)
        case .text, .other:
            return RecordInspection()
        }
    }

    // MARK: - PDF

    static func inspectPDF(url: URL, timeZone: TimeZone) -> RecordInspection {
        var result = RecordInspection()
        guard let document = CGPDFDocument(url as CFURL) else {
            result.pdfState = .unreadable
            return result
        }
        if document.isEncrypted && !document.isUnlocked {
            // Try the empty user password (owner-password-only PDFs open without prompting).
            if !document.unlockWithPassword("") {
                result.pdfState = .locked
                result.pageCount = document.numberOfPages > 0 ? document.numberOfPages : nil
                return result
            }
        }
        result.pdfState = .readable
        result.pageCount = document.numberOfPages
        if let page = document.page(at: 1) {
            result.thumbnailJPEG = renderThumbnail(page: page)
        }
        // Info dictionary `/CreationDate` (what PDFDocument exposes as `creationDateAttribute`).
        if let info = document.info, let raw = pdfInfoString(info, key: "CreationDate") {
            result.metadataDate = parsePDFDate(raw).map { RecordDates.dayString(from: $0, timeZone: timeZone) }
        }
        return result
    }

    static func renderThumbnail(page: CGPDFPage) -> Data? {
        let box = page.getBoxRect(.cropBox)
        guard box.width > 0, box.height > 0 else { return nil }
        let rotation = ((page.rotationAngle % 360) + 360) % 360
        let rotated = rotation == 90 || rotation == 270
        let sourceWidth = rotated ? box.height : box.width
        let sourceHeight = rotated ? box.width : box.height
        let scale = CGFloat(thumbnailMaxPixels) / max(sourceWidth, sourceHeight)
        let width = max(1, Int((sourceWidth * scale).rounded()))
        let height = max(1, Int((sourceHeight * scale).rounded()))
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        let target = CGRect(x: 0, y: 0, width: width, height: height)
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(target)
        context.interpolationQuality = .high
        context.concatenate(page.getDrawingTransform(.cropBox, rect: target, rotate: 0, preserveAspectRatio: true))
        context.drawPDFPage(page)
        guard let image = context.makeImage() else { return nil }
        return jpegData(image)
    }

    private static func pdfInfoString(_ info: CGPDFDictionaryRef, key: String) -> String? {
        var stringRef: CGPDFStringRef?
        guard CGPDFDictionaryGetString(info, key, &stringRef), let stringRef,
              let value = CGPDFStringCopyTextString(stringRef) else { return nil }
        return value as String
    }

    /// `D:YYYYMMDDHHmmSS…` → Date (time zone suffix ignored; the day is what matters).
    static func parsePDFDate(_ raw: String) -> Date? {
        var text = raw
        if text.hasPrefix("D:") { text.removeFirst(2) }
        let digits = text.prefix { $0.isNumber }
        guard digits.count >= 8,
              let year = Int(digits.prefix(4)),
              let month = Int(digits.dropFirst(4).prefix(2)),
              let day = Int(digits.dropFirst(6).prefix(2)),
              RecordFilenameDate.validDay(year: year, month: month, day: day) != nil
        else { return nil }
        return RecordDates.date(fromDay: String(format: "%04d-%02d-%02d", year, month, day))
    }

    // MARK: - Images

    static func inspectImage(url: URL, timeZone: TimeZone) -> RecordInspection {
        var result = RecordInspection()
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else { return result }
        result.pageCount = 1
        if let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any] {
            result.pixelWidth = properties[kCGImagePropertyPixelWidth] as? Int
            result.pixelHeight = properties[kCGImagePropertyPixelHeight] as? Int
            if let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any],
               let original = exif[kCGImagePropertyExifDateTimeOriginal] as? String {
                result.metadataDate = parseEXIFDate(original)
            }
        }
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: thumbnailMaxPixels,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        if let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) {
            result.thumbnailJPEG = jpegData(thumbnail)
        }
        return result
    }

    /// EXIF `yyyy:MM:dd HH:mm:ss` → `yyyy-MM-dd` (local wall-clock date as recorded by the camera).
    static func parseEXIFDate(_ raw: String) -> String? {
        let parts = raw.prefix(10).split(separator: ":").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return RecordFilenameDate.validDay(year: parts[0], month: parts[1], day: parts[2])
    }

    static func jpegData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: thumbnailQuality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    /// UTF-8 text of a text original, capped at `maxBytes` (contract §4.3: 2 MB).
    static func readText(url: URL, maxBytes: Int = 2 * 1_024 * 1_024) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard var data = try? handle.read(upToCount: maxBytes) else { return nil }
        if let text = String(data: data, encoding: .utf8) { return text }
        // Cut inside a multi-byte sequence: drop up to 3 trailing bytes.
        for _ in 0..<3 where !data.isEmpty {
            data.removeLast()
            if let text = String(data: data, encoding: .utf8) { return text }
        }
        return nil
    }
}
