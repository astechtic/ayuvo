import CoreGraphics
import Foundation
import ImageIO
import PDFKit
import UniformTypeIdentifiers
import Vision

/// One recognised or text-layer line, box normalized 0–1 with a top-left origin.
nonisolated struct RecordTextLine: Hashable, Sendable {
    var text: String
    /// `[x, y, w, h]`
    var box: [Double]
    var confidence: Double?
}

/// OCR seam (Vision in the app, stubs in tests).
nonisolated protocol RecordOCREngine: Sendable {
    func recognize(_ image: CGImage) async throws -> [RecordTextLine]
}

nonisolated struct VisionOCREngine: RecordOCREngine {
    func recognize(_ image: CGImage) async throws -> [RecordTextLine] {
        try await Task.detached(priority: .utility) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
            try handler.perform([request])
            return (request.results ?? []).compactMap { observation -> RecordTextLine? in
                guard let candidate = observation.topCandidates(1).first else { return nil }
                let box = observation.boundingBox
                // Vision boxes have a bottom-left origin.
                return RecordTextLine(
                    text: candidate.string,
                    box: [Double(box.minX), Double(1 - box.maxY), Double(box.width), Double(box.height)],
                    confidence: Double(candidate.confidence)
                )
            }
        }.value
    }
}

/// §9.1 reading order and row joining.
nonisolated enum RecordTextLayout {
    struct Joined: Equatable, Sendable {
        var text: String
        var blocksJSON: String
        var confidence: Double?
    }

    /// §9.1: lines sorted by top then left; a line joins an existing row when its vertical centre is
    /// within half of the taller box height of the row's line nearest to it in x (tolerates skew);
    /// row lines join left to right with two spaces; rows join with `\n`.
    static func join(_ lines: [RecordTextLine]) -> Joined {
        let usable = lines.filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty && $0.box.count == 4 }
        let sorted = usable.stableSorted { a, b in
            if a.box[1] != b.box[1] { return a.box[1] < b.box[1] }
            return a.box[0] < b.box[0]
        }
        var rows: [[RecordTextLine]] = []
        for line in sorted {
            let centre = line.box[1] + line.box[3] / 2
            var bestRow: Int?
            var bestDelta = Double.infinity
            for (index, row) in rows.enumerated() {
                let neighbour = row.min { horizontalGap($0, line) < horizontalGap($1, line) }!
                let delta = abs(centre - (neighbour.box[1] + neighbour.box[3] / 2))
                if delta <= max(neighbour.box[3], line.box[3]) / 2, delta < bestDelta {
                    bestRow = index
                    bestDelta = delta
                }
            }
            if let bestRow { rows[bestRow].append(line) } else { rows.append([line]) }
        }
        let text = rows.map { row in
            row.stableSorted { $0.box[0] < $1.box[0] }.map { $0.text.trimmingCharacters(in: .whitespaces) }.joined(separator: "  ")
        }.joined(separator: "\n")
        let blocks = "[" + sorted.map { line in
            let box = line.box.map { RJ.number(RecordsMath.pyRound($0, 4)).compactJSON }.joined(separator: ",")
            return "{\"t\":" + RJ.str(line.text).compactJSON + ",\"b\":[" + box + "]}"
        }.joined(separator: ",") + "]"
        let confidences = sorted.compactMap(\.confidence)
        return Joined(
            text: text,
            blocksJSON: blocks,
            confidence: confidences.isEmpty ? nil : confidences.reduce(0, +) / Double(confidences.count)
        )
    }

    /// Horizontal distance between two boxes (0 when they overlap in x), tie-broken by centre distance.
    static func horizontalGap(_ a: RecordTextLine, _ b: RecordTextLine) -> Double {
        let gap = max(0, max(a.box[0], b.box[0]) - min(a.box[0] + a.box[2], b.box[0] + b.box[2]))
        let centres = abs((a.box[0] + a.box[2] / 2) - (b.box[0] + b.box[2] / 2))
        return gap * 10 + centres * 1e-3
    }
}

nonisolated struct RecordTextExtractionResult: Sendable {
    var pages: [RecordPage]
    var pageCount: Int
    var error: RecordProcessingError?
    /// dHash of the first page render / image.
    var firstPageHash: String?
}

/// §9.1 text stage: PDF text layer (PDFKit) with OCR fallback, images through Vision.
nonisolated struct RecordTextExtractor: Sendable {
    static let minimumLetters = 20
    static let renderLongSide: CGFloat = 2000
    static let imageLongSide = 3000
    static let pageCap = 1_000

    var ocr: RecordOCREngine = VisionOCREngine()
    var renderCache: URL = RecordsLocation.renderCacheDirectory()

    typealias Progress = @Sendable (_ page: Int, _ total: Int) async -> Void

    func extract(recordID: String, url: URL, fileType: RecordFileType, pageRange: ClosedRange<Int>? = nil, progress: Progress? = nil) async -> RecordTextExtractionResult {
        switch fileType {
        case .pdf:
            return await extractPDF(recordID: recordID, url: url, pageRange: pageRange, progress: progress)
        case .image:
            return await extractImage(recordID: recordID, url: url)
        case .text, .other:
            return RecordTextExtractionResult(pages: [], pageCount: 0, error: fileType == .other ? .unsupported : nil)
        }
    }

    // MARK: PDF

    func extractPDF(recordID: String, url: URL, pageRange: ClosedRange<Int>?, progress: Progress?) async -> RecordTextExtractionResult {
        guard let document = PDFDocument(url: url) else {
            return RecordTextExtractionResult(pages: [], pageCount: 0, error: .textUnavailable)
        }
        if document.isLocked, !document.unlock(withPassword: "") {
            return RecordTextExtractionResult(pages: [], pageCount: document.pageCount, error: .protectedPDF)
        }
        let total = document.pageCount
        let lower = pageRange?.lowerBound ?? 0
        let upper = min(pageRange?.upperBound ?? (total - 1), total - 1, lower + Self.pageCap - 1)
        guard total > 0, lower <= upper else {
            return RecordTextExtractionResult(pages: [], pageCount: total, error: .textUnavailable)
        }
        var pages: [RecordPage] = []
        var anyText = false
        var ocrFailures = 0
        var firstHash: String?
        for index in lower...upper {
            if Task.isCancelled { break }
            await progress?(index - lower, upper - lower + 1)
            guard let page = document.page(at: index) else { continue }
            let bounds = page.bounds(for: .cropBox)
            let rawText = page.string ?? ""
            var joined: RecordTextLayout.Joined?
            var source: RecordPageTextSource = .pdfText
            let layerLines = RecordsFold.letterCount(rawText) >= Self.minimumLetters ? textLayerLines(page: page) : []
            if !layerLines.isEmpty {
                joined = RecordTextLayout.join(layerLines)
                if index == lower, let image = autoreleasepool(invoking: { render(page: page, longSide: 256) }) {
                    firstHash = RecordNearDuplicate.dHash(image: image)
                }
            } else {
                // No text, or a text layer without usable line positions: OCR for line boxes; the
                // text layer is kept only when OCR yields fewer letters (§9.1).
                source = .ocr
                let image = cachedRender(recordID: recordID, index: index, page: page)
                if let image {
                    if index == lower { firstHash = RecordNearDuplicate.dHash(image: image) }
                    do {
                        joined = RecordTextLayout.join(try await ocr.recognize(image))
                    } catch {
                        ocrFailures += 1
                    }
                } else {
                    ocrFailures += 1
                }
                if RecordsFold.letterCount(rawText) > RecordsFold.letterCount(joined?.text ?? "") {
                    joined = RecordTextLayout.Joined(text: rawText, blocksJSON: "[]", confidence: nil)
                    source = .pdfText
                }
            }
            let text = joined?.text ?? ""
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { anyText = true }
            pages.append(RecordPage(
                recordID: recordID,
                pageIndex: index - (pageRange?.lowerBound ?? 0),
                text: text,
                textSource: source,
                ocrConfidence: source == .ocr ? joined?.confidence : nil,
                width: Int(bounds.width.rounded()),
                height: Int(bounds.height.rounded()),
                blocksJSON: joined?.blocksJSON
            ))
        }
        let error: RecordProcessingError? = anyText ? nil : (ocrFailures > 0 ? .ocrFailed : .textUnavailable)
        return RecordTextExtractionResult(pages: pages, pageCount: total, error: error, firstPageHash: firstHash)
    }

    /// Text-layer cells with top-left normalized boxes (page rotation applied), built from PDFKit
    /// character geometry: characters form words, words on the same baseline form a cell unless the
    /// gap between them is wider than about one line height (a table column gap). §9.1 then joins the
    /// cells of a row with two spaces. Falls back to `selectionsByLine()` boxes when the page has no
    /// usable character bounds; returns [] when neither gives positions (the page is then OCR'd).
    func textLayerLines(page: PDFPage) -> [RecordTextLine] {
        let bounds = page.bounds(for: .cropBox)
        guard bounds.width > 0, bounds.height > 0, let string = page.string as NSString? else { return [] }
        let rotation = ((page.rotation % 360) + 360) % 360
        func normalized(_ rect: CGRect) -> [Double]? {
            guard rect.width > 0, rect.height > 0, rect.minX.isFinite, rect.minY.isFinite else { return nil }
            let x = (rect.minX - bounds.minX) / bounds.width
            let yTop = (bounds.maxY - rect.maxY) / bounds.height
            var box = [Double(x), Double(yTop), Double(rect.width / bounds.width), Double(rect.height / bounds.height)]
            switch rotation {
            case 90: box = [1 - box[1] - box[3], box[0], box[3], box[2]]
            case 180: box = [1 - box[0] - box[2], 1 - box[1] - box[3], box[2], box[3]]
            case 270: box = [box[1], 1 - box[0] - box[2], box[3], box[2]]
            default: break
            }
            return box.map { min(1, max(0, $0)) }
        }
        struct Word {
            var text: String
            var rect: CGRect
        }
        var words: [Word] = []
        var current = ""
        var currentRect = CGRect.null
        func flushWord() {
            if !current.isEmpty, !currentRect.isNull { words.append(Word(text: current, rect: currentRect)) }
            current = ""
            currentRect = .null
        }
        _ = string
        var lastRect = CGRect.null
        for index in 0..<page.numberOfCharacters {
            // A one-character selection pairs the character with its own bounds (page.string offsets
            // can differ from character indexes).
            guard let selection = page.selection(for: NSRange(location: index, length: 1)),
                  let character = selection.string, !character.isEmpty else { continue }
            if character.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                flushWord()
                continue
            }
            let rect = selection.bounds(for: page)
            guard rect.width > 0, rect.height > 0 else { continue }
            // PDFKit repeats the previous glyph for a line-break index; skip the duplicate.
            if rect == lastRect { continue }
            lastRect = rect
            if !currentRect.isNull {
                let sameLine = abs(rect.midY - currentRect.midY) <= max(rect.height, currentRect.height) / 2
                let adjacent = rect.minX >= currentRect.maxX - rect.width && rect.minX - currentRect.maxX <= rect.height * 0.3
                if !(sameLine && adjacent) { flushWord() }
            }
            current += character
            currentRect = currentRect.union(rect)
        }
        flushWord()
        var cells: [Word] = []
        for word in words {
            if var last = cells.last {
                let sameLine = abs(word.rect.midY - last.rect.midY) <= max(word.rect.height, last.rect.height) / 2
                let gap = word.rect.minX - last.rect.maxX
                if sameLine, gap >= -word.rect.height * 0.2, gap <= max(word.rect.height, last.rect.height) * 1.0 {
                    last.text += " " + word.text
                    last.rect = last.rect.union(word.rect)
                    cells[cells.count - 1] = last
                    continue
                }
            }
            cells.append(word)
        }
        let fromCharacters = cells.compactMap { cell in normalized(cell.rect).map { RecordTextLine(text: cell.text, box: $0, confidence: nil) } }
        if !fromCharacters.isEmpty { return fromCharacters }
        guard let selection = page.selection(for: bounds) else { return [] }
        return selection.selectionsByLine().compactMap { line in
            guard let text = line.string?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty,
                  let box = normalized(line.bounds(for: page)) else { return nil }
            return RecordTextLine(text: text, box: box, confidence: nil)
        }
    }

    func cachedRender(recordID: String, index: Int, page: PDFPage) -> CGImage? {
        let directory = renderCache.appendingPathComponent(recordID, isDirectory: true)
        let file = directory.appendingPathComponent("\(index).jpg")
        if let source = CGImageSourceCreateWithURL(file as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) {
            return image
        }
        guard let image = autoreleasepool(invoking: { render(page: page, longSide: Self.renderLongSide) }) else { return nil }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let destination = CGImageDestinationCreateWithURL(file as CFURL, UTType.jpeg.identifier as CFString, 1, nil) {
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
            CGImageDestinationFinalize(destination)
        }
        return image
    }

    func render(page: PDFPage, longSide: CGFloat) -> CGImage? {
        let bounds = page.bounds(for: .cropBox)
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        let rotation = ((page.rotation % 360) + 360) % 360
        let rotated = rotation == 90 || rotation == 270
        let sourceWidth = rotated ? bounds.height : bounds.width
        let sourceHeight = rotated ? bounds.width : bounds.height
        let scale = longSide / max(sourceWidth, sourceHeight)
        let width = max(1, Int((sourceWidth * scale).rounded()))
        let height = max(1, Int((sourceHeight * scale).rounded()))
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.interpolationQuality = .high
        if let pageRef = page.pageRef {
            let target = CGRect(x: 0, y: 0, width: width, height: height)
            context.concatenate(pageRef.getDrawingTransform(.cropBox, rect: target, rotate: 0, preserveAspectRatio: true))
            context.drawPDFPage(pageRef)
        } else {
            context.scaleBy(x: scale, y: scale)
            page.draw(with: .cropBox, to: context)
        }
        return context.makeImage()
    }

    // MARK: Images

    func extractImage(recordID: String, url: URL) async -> RecordTextExtractionResult {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else {
            return RecordTextExtractionResult(pages: [], pageCount: 1, error: .unsupported)
        }
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.imageLongSide,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
            return RecordTextExtractionResult(pages: [], pageCount: 1, error: .unsupported)
        }
        let hash = RecordNearDuplicate.dHash(image: image)
        do {
            let joined = RecordTextLayout.join(try await ocr.recognize(image))
            let page = RecordPage(
                recordID: recordID, pageIndex: 0, text: joined.text, textSource: .ocr, ocrConfidence: joined.confidence,
                width: image.width, height: image.height, blocksJSON: joined.blocksJSON
            )
            let empty = joined.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            return RecordTextExtractionResult(pages: [page], pageCount: 1, error: empty ? .textUnavailable : nil, firstPageHash: hash)
        } catch {
            return RecordTextExtractionResult(pages: [], pageCount: 1, error: .ocrFailed, firstPageHash: hash)
        }
    }
}
