import ImageIO
import PDFKit
import UIKit
import UniformTypeIdentifiers

/// Builds the files a `RecordSharePlan` produces (docs/health-records.md §34, §38):
/// the plain-text structured summary, the original (whole file, a PDF page subset that keeps the
/// text layer, or a rasterized+redacted PDF) and a page-1 preview of each produced PDF. Everything
/// is written under `tmp/records-share/<uuid>/`, which is deleted on the next launch.
nonisolated enum RecordShareBuilder {
    static let pageSize = CGSize(width: 612, height: 792)
    /// §34: redacted pages are rasterized with this long side.
    static let rasterLongSide: CGFloat = 2000
    /// §34: the black box is inflated by 2 % of the page width.
    static let boxInflation: CGFloat = 0.02
    static let previewWidth: CGFloat = 700

    /// Everything the builder needs about one record; assembled by `RecordsStore`.
    nonisolated struct Source: Sendable {
        var record: HealthRecord
        var pages: [RecordPage]
        var originalURL: URL?
        /// Reference `redaction_targets` output for this record (§34), already inflated and clamped.
        var redaction: RJ = .null
        /// 0-based page indexes the plan selected (already resolved from `.all`).
        var selectedPages: [Int]

        /// `{page_index: [box]}` of the pages that can be redacted.
        var redactionBoxes: [Int: [[Double]]] {
            var out: [Int: [[Double]]] = [:]
            for page in redaction["pages"].array ?? [] {
                guard let index = page["page_index"].double.map({ Int($0) }) else { continue }
                out[index] = (page["lines"].array ?? []).compactMap { line in
                    let box = (line["box"].array ?? []).compactMap(\.double)
                    return box.count == 4 ? box : nil
                }
            }
            return out
        }

        var excludedPages: Set<Int> {
            Set((redaction["excluded_pages"].array ?? []).compactMap { $0.double.map { Int($0) } })
        }
    }

    // MARK: - Entry point

    static func build(plan: RecordSharePlan, summary: String, sources: [Source], directory: URL) -> RecordShareBundle {
        var bundle = RecordShareBundle()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        // §38: the summary is plain text — a `<title> summary.txt` file plus the same text as the
        // share sheet's text item. No PDF summary.
        if plan.includeSummary, !summary.isEmpty {
            let base = sources.count == 1 ? safeName(sources[0].record) : String(localized: "Health records")
            let url = directory.appendingPathComponent("\(base) summary.txt")
            do {
                try Data(summary.utf8).write(to: url, options: .atomic)
                bundle.summaryText = summary
                bundle.items.append(makeItem(url: url, kind: .summary, recordID: nil, title: String(localized: "Structured summary")))
            } catch {
                bundle.warnings.append(String(localized: "The summary couldn't be created."))
            }
        }

        if plan.includeOriginal {
            for source in sources {
                let result = buildOriginal(plan: plan, source: source, directory: directory)
                bundle.items.append(contentsOf: result.items)
                bundle.warnings.append(contentsOf: result.warnings)
            }
        }

        if !bundle.items.isEmpty {
            let withFiles = Set(bundle.items.compactMap(\.recordID))
            bundle.sharedRecordIDs = sources.map(\.record.id).filter { plan.includeSummary || withFiles.contains($0) }
        }
        return bundle
    }

    private static func makeItem(url: URL, kind: RecordShareItem.Kind, recordID: String?, title: String) -> RecordShareItem {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        return RecordShareItem(url: url, kind: kind, recordID: recordID, title: title, previewPath: makePreview(for: url), byteCount: size)
    }

    // MARK: - Originals

    private static func buildOriginal(plan: RecordSharePlan, source: Source, directory: URL) -> (items: [RecordShareItem], warnings: [String]) {
        let record = source.record
        guard let originalURL = source.originalURL, FileManager.default.fileExists(atPath: originalURL.path) else {
            return ([], [String(localized: "\(record.title): the original file is missing.")])
        }
        let base = safeName(record)
        if plan.isRedacting {
            let url = directory.appendingPathComponent("\(base)-redacted.pdf")
            let outcome = writeRedactedPDF(source: source, classes: plan.redactionValues, originalURL: originalURL, to: url)
            guard outcome.wrotePages > 0 else {
                return ([], outcome.warnings.isEmpty ? [String(localized: "\(record.title) can't be redacted and was left out.")] : outcome.warnings)
            }
            let item = makeItem(url: url, kind: .redacted, recordID: record.id, title: String(localized: "\(record.title) (redacted)"))
            return ([item], outcome.warnings)
        }

        // A split child shares its own page range of the parent's file, never the whole document.
        let wholeDocument = !record.isSplitChild && source.selectedPages.count >= max(record.pageCount, 1)
        if !wholeDocument, record.fileType == .pdf {
            let offset = record.pageStart ?? 0
            let url = directory.appendingPathComponent("\(base)-pages.pdf")
            if writePageSubsetPDF(originalURL: originalURL, pages: source.selectedPages.map { $0 + offset }, to: url) {
                let item = makeItem(url: url, kind: .pageSubset, recordID: record.id, title: String(localized: "\(record.title) (selected pages)"))
                return ([item], [])
            }
            return ([], [String(localized: "\(record.title): the selected pages couldn't be copied.")])
        }

        let url = directory.appendingPathComponent(base).appendingPathExtension(originalURL.pathExtension.isEmpty ? "bin" : originalURL.pathExtension)
        try? FileManager.default.removeItem(at: url)
        do {
            try FileManager.default.copyItem(at: originalURL, to: url)
        } catch {
            return ([], [String(localized: "\(record.title): the original file couldn't be copied.")])
        }
        return ([makeItem(url: url, kind: .original, recordID: record.id, title: record.title)], [])
    }

    static func safeName(_ record: HealthRecord) -> String {
        var base = record.title
            .components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>"))
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty { base = "record" }
        return String(base.prefix(60))
    }

    // MARK: - Page subset (PDFKit keeps the text layer)

    static func writePageSubsetPDF(originalURL: URL, pages: [Int], to url: URL) -> Bool {
        guard let document = PDFDocument(url: originalURL) else { return false }
        let output = PDFDocument()
        var index = 0
        for page in pages.sorted() where page >= 0 && page < document.pageCount {
            guard let copy = document.page(at: page)?.copy() as? PDFPage else { continue }
            output.insert(copy, at: index)
            index += 1
        }
        guard index > 0 else { return false }
        try? FileManager.default.removeItem(at: url)
        return output.write(to: url)
    }

    // MARK: - Redaction

    struct RedactionOutcome {
        var wrotePages = 0
        var warnings: [String] = []
    }

    /// Rasterizes each selected page and paints the reference's redaction boxes black (§34).
    /// The boxes come from `RR.redactionTargets`, already inflated by 2 % and clamped to 0…1.
    static func writeRedactedPDF(source: Source, classes: [String], originalURL: URL, to url: URL) -> RedactionOutcome {
        var outcome = RedactionOutcome()
        let boxesByPage = source.redactionBoxes
        let excluded = source.excludedPages
        var rendered: [(image: UIImage, boxes: [CGRect])] = []
        for pageIndex in source.selectedPages.sorted() {
            guard !excluded.contains(pageIndex), let boxes = boxesByPage[pageIndex] else {
                outcome.warnings.append(String(localized: "Page \(pageIndex + 1) can't be redacted and was left out"))
                continue
            }
            guard let image = rasterize(originalURL: originalURL, record: source.record, pageIndex: pageIndex) else {
                outcome.warnings.append(String(localized: "Page \(pageIndex + 1) can't be redacted and was left out"))
                continue
            }
            rendered.append((image, boxes.map { CGRect(x: $0[0], y: $0[1], width: $0[2], height: $0[3]) }))
        }
        guard !rendered.isEmpty else { return outcome }

        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize))
        do {
            try renderer.writePDF(to: url) { context in
                for page in rendered {
                    let rect = fittedRect(for: page.image.size)
                    context.beginPage(withBounds: CGRect(origin: .zero, size: rect.size), pageInfo: [:])
                    page.image.draw(in: CGRect(origin: .zero, size: rect.size))
                    UIColor.black.setFill()
                    for box in page.boxes {
                        let painted = CGRect(
                            x: box.minX * rect.width,
                            y: box.minY * rect.height,
                            width: box.width * rect.width,
                            height: box.height * rect.height
                        )
                        context.cgContext.fill(painted)
                    }
                }
            }
        } catch {
            outcome.warnings.append(String(localized: "The redacted copy couldn't be created."))
            return outcome
        }
        outcome.wrotePages = rendered.count
        return outcome
    }

    /// A PDF page box at most 792 pt on its long side, keeping the raster's aspect ratio.
    static func fittedRect(for size: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0 else { return CGRect(origin: .zero, size: pageSize) }
        let scale = min(pageSize.height / max(size.width, size.height), 1)
        return CGRect(x: 0, y: 0, width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
    }

    /// Page image at long side `rasterLongSide` — PDF page via PDFKit, image records via ImageIO.
    static func rasterize(originalURL: URL, record: HealthRecord, pageIndex: Int) -> UIImage? {
        switch record.fileType {
        case .pdf:
            guard let document = PDFDocument(url: originalURL) else { return nil }
            let absolute = (record.pageStart ?? 0) + pageIndex
            guard absolute >= 0, absolute < document.pageCount, let page = document.page(at: absolute) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            guard bounds.width > 0, bounds.height > 0 else { return nil }
            let scale = rasterLongSide / max(bounds.width, bounds.height)
            let size = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
            return page.thumbnail(of: size, for: .mediaBox)
        case .image:
            guard pageIndex == 0, let source = CGImageSourceCreateWithURL(originalURL as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: Int(rasterLongSide),
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        default:
            return nil
        }
    }

    // MARK: - Previews

    /// Page-1 PNG next to the produced file (§34 "a page-1 preview of each produced file").
    static func makePreview(for url: URL) -> URL? {
        let previewURL = url.deletingPathExtension().appendingPathExtension("preview.png")
        let image: UIImage?
        if url.pathExtension.lowercased() == "pdf" {
            guard let document = PDFDocument(url: url), let page = document.page(at: 0) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            guard bounds.width > 0 else { return nil }
            let scale = previewWidth / bounds.width
            image = page.thumbnail(of: CGSize(width: previewWidth, height: (bounds.height * scale).rounded()), for: .mediaBox)
        } else if let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                      kCGImageSourceCreateThumbnailFromImageAlways: true,
                      kCGImageSourceCreateThumbnailWithTransform: true,
                      kCGImageSourceThumbnailMaxPixelSize: Int(previewWidth),
                  ] as CFDictionary) {
            image = UIImage(cgImage: cg)
        } else {
            image = nil
        }
        guard let image, let data = image.pngData() else { return nil }
        try? data.write(to: previewURL, options: .atomic)
        return previewURL
    }
}
