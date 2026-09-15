import ImageIO
import PDFKit
import SwiftUI
import UIKit

/// Record detail: header, original viewer, notes, tags and actions (Phase 1).
struct RecordDetailView: View {
    let recordID: String
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var detail: RecordDetail?
    @State private var didLoad = false
    @State private var showEdit = false
    @State private var showDeleteConfirmation = false
    @State private var showFullScreenViewer = false
    @State private var exportItem: RecordExportItem?
    @State private var newTag = ""
    @State private var showReview = false
    @State private var sourceTarget: RecordSourceTarget?
    @State private var showSplit = false
    @State private var duplicatePrompt: RecordDuplicatePrompt?

    var body: some View {
        Group {
            if let detail {
                content(detail)
            } else if didLoad {
                ContentUnavailableView("Record not found", systemImage: "doc.questionmark")
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(AppColors.appBackground)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .task(id: store.revision) { await load() }
    }

    private func load() async {
        detail = await store.detail(id: recordID)
        didLoad = true
    }

    @ViewBuilder
    private func content(_ detail: RecordDetail) -> some View {
        let record = detail.record
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header(record)
                RecordOriginalViewer(record: record, pages: detail.pages, url: store.originalURL(for: record), height: 440)
                    .overlay(alignment: .topTrailing) {
                        if record.fileType == .pdf || record.fileType == .image {
                            Button {
                                showFullScreenViewer = true
                            } label: {
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                                    .font(.system(size: 14, weight: .bold))
                                    .padding(9)
                                    .background(.thinMaterial, in: Circle())
                            }
                            .padding(10)
                            .accessibilityLabel("Full screen")
                        }
                    }
                RecordDetailIntelligenceSections(
                    detail: detail,
                    onReview: { showReview = true },
                    onSource: { sourceTarget = $0 },
                    onSplit: { showSplit = true },
                    onDuplicate: { candidate in
                        Task { duplicatePrompt = await store.duplicatePrompt(for: candidate) }
                    }
                )
                notesSection(record)
                tagsSection(detail)
                if !detail.children.isEmpty { childrenSection(detail.children) }
                infoSection(record)
                actions(record)
            }
            .padding()
        }
        .navigationTitle(record.title)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    Task { await store.setFavorite(ids: [record.id], !record.favorite) }
                } label: {
                    Image(systemName: record.favorite ? "star.fill" : "star")
                        .foregroundStyle(record.favorite ? .yellow : AppColors.calorie)
                }
                .accessibilityLabel(record.favorite ? "Remove from favorites" : "Add to favorites")

                Menu {
                    Button { showEdit = true } label: { Label("Edit", systemImage: "pencil") }
                    if !detail.fields.isEmpty || record.reviewStatus == .needsReview {
                        Button { showReview = true } label: { Label("Review details", systemImage: "checklist") }
                    }
                    if record.parentID == nil, record.fileType != .other {
                        Button {
                            Task { await store.reprocess(id: record.id) }
                        } label: { Label("Find details again", systemImage: "arrow.clockwise") }
                    }
                    Button { export(record) } label: { Label("Export original", systemImage: "square.and.arrow.up") }
                    Button {
                        Task { await store.setArchived(ids: [record.id], !record.archived) }
                    } label: {
                        Label(record.archived ? "Unarchive" : "Archive", systemImage: "archivebox")
                    }
                    Button(role: .destructive) { showDeleteConfirmation = true } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("records.detail.menu")
            }
        }
        .sheet(isPresented: $showEdit) {
            RecordEditSheet(detail: detail)
        }
        .sheet(isPresented: $showReview) {
            RecordReviewSheet(recordID: record.id, onOpenSource: { field in
                showReview = false
                if let page = field.sourcePage {
                    sourceTarget = RecordSourceTarget(page: page, box: field.bbox, label: field.evidence ?? field.displayValue)
                }
            })
        }
        .fullScreenCover(item: $sourceTarget) { target in
            RecordSourceViewer(record: record, url: store.originalURL(for: record), target: target)
        }
        .fullScreenCover(isPresented: $showSplit) {
            RecordSplitReviewView(recordID: record.id)
        }
        .sheet(item: $duplicatePrompt) { prompt in
            RecordDuplicateSheet(prompt: prompt)
        }
        .sheet(item: $exportItem, onDismiss: { exportItem?.cleanUp() }) { item in
            ActivityShareSheet(activityItems: [item.url])
                .presentationDetents([.medium, .large])
        }
        .fullScreenCover(isPresented: $showFullScreenViewer) {
            NavigationStack {
                RecordOriginalViewer(record: record, pages: detail.pages, url: store.originalURL(for: record), height: nil)
                    .ignoresSafeArea(edges: .bottom)
                    .navigationTitle(record.title)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showFullScreenViewer = false }
                        }
                    }
            }
        }
        .confirmationDialog("Delete this record?", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                Task {
                    await store.delete(ids: [record.id])
                    dismiss()
                }
            }
        } message: {
            Text("The record and its original file are removed from this iPhone. This can't be undone.")
        }
    }

    private func header(_ record: HealthRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(record.title)
                .font(.system(.title2, design: .rounded, weight: .bold))
                .accessibilityIdentifier("records.detail.title")
            HStack(spacing: 8) {
                RecordTypePill(type: record.recordType)
                Text(record.category.title)
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                if record.archived {
                    Label("Archived", systemImage: "archivebox.fill")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Label(dateLine(record), systemImage: "calendar")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
            if let parent = detail?.parent, record.isSplitChild {
                NavigationLink(value: RecordsRoute.detail(parent.id)) {
                    Label("Part of \(parent.title)", systemImage: "rectangle.split.3x1")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                }
                .accessibilityIdentifier("records.detail.parentLink")
            }
            if let children = detail?.children, !children.isEmpty {
                Label("Combined document · \(children.count) records", systemImage: "square.stack.3d.up.fill")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .accessibilityIdentifier("records.detail.combinedBadge")
            }
            if let processing = RecordStatusText.processing(record) {
                HStack(spacing: 6) {
                    if record.isProcessing { ProgressView().controlSize(.mini) }
                    Text(processing)
                }
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("records.detail.processing")
            }
            Text(RecordStatusText.aiLabel(record))
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.tertiary)
                .accessibilityIdentifier("records.detail.aiStatus")
        }
    }

    private func dateLine(_ record: HealthRecord) -> String {
        let date = RecordFormatting.dateText(record)
        switch record.documentDateMethod {
        case .none:
            return String(localized: "Added \(date)")
        case .fileMetadata:
            return String(localized: "\(date) · from file details")
        case .rules, .pdfText, .ocr:
            return String(localized: "\(date) · found on the document")
        case .aiLocal, .aiCloud:
            return String(localized: "\(date) · found by AI")
        default:
            return date
        }
    }

    private func notesSection(_ record: HealthRecord) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionTitle("Notes")
            if let notes = record.notes, !notes.isEmpty {
                Text(notes)
                    .font(.system(.body, design: .rounded))
                    .textSelection(.enabled)
            } else {
                Button("Add a note") { showEdit = true }
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func tagsSection(_ detail: RecordDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Tags")
            if !detail.tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(detail.tags, id: \.self) { tag in
                            Button {
                                Task { await store.setTags(id: detail.record.id, names: detail.tags.filter { $0 != tag }) }
                            } label: {
                                HStack(spacing: 4) {
                                    Text(tag)
                                    Image(systemName: "xmark")
                                        .font(.system(size: 9, weight: .bold))
                                }
                                .font(.system(.caption, design: .rounded, weight: .semibold))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(AppColors.calorie.opacity(0.12), in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Remove tag \(tag)")
                        }
                    }
                }
            }
            HStack {
                TextField("Add tag", text: $newTag)
                    .textInputAutocapitalization(.never)
                    .onSubmit { addTag(detail) }
                Button("Add") { addTag(detail) }
                    .disabled(newTag.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .font(.system(.subheadline, design: .rounded))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func addTag(_ detail: RecordDetail) {
        let tag = newTag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tag.isEmpty else { return }
        newTag = ""
        Task { await store.setTags(id: detail.record.id, names: detail.tags + [tag]) }
    }

    private func childrenSection(_ children: [HealthRecord]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionTitle("Records in this document")
            ForEach(children) { child in
                NavigationLink(value: RecordsRoute.detail(child.id)) {
                    RecordRow(record: child, compact: true)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func infoSection(_ record: HealthRecord) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionTitle("File")
            infoRow("Name", record.originalFilename ?? "—")
            infoRow("Type", record.mimeType)
            infoRow("Size", RecordFormatting.sizeText(record.fileSize))
            if record.pageCount > 0 { infoRow("Pages", "\(record.pageCount)") }
            infoRow("Added", Date(timeIntervalSince1970: Double(record.createdMs) / 1000).formatted(date: .abbreviated, time: .shortened))
            infoRow("Source", sourceText(record))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func sourceText(_ record: HealthRecord) -> String {
        switch record.source {
        case .import: String(localized: "Files")
        case .photos: String(localized: "Photos")
        case .camera: String(localized: "Camera")
        case .scan: String(localized: "Document scan")
        case .paste: String(localized: "Pasted text")
        case .note: String(localized: "Note")
        case .shareIn: String(localized: "Shared from another app")
        case .openIn: String(localized: "Opened in Ayuvo")
        }
    }

    private func infoRow(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
        }
        .font(.system(.footnote, design: .rounded))
    }

    private func actions(_ record: HealthRecord) -> some View {
        VStack(spacing: 10) {
            Button {
                export(record)
            } label: {
                Label("Export original", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .accessibilityIdentifier("records.detail.export")

            HStack(spacing: 10) {
                Button {
                    Task { await store.setArchived(ids: [record.id], !record.archived) }
                } label: {
                    Label(record.archived ? "Unarchive" : "Archive", systemImage: "archivebox")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button(role: .destructive) {
                    showDeleteConfirmation = true
                } label: {
                    Label("Delete", systemImage: "trash")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .controlSize(.large)
        .font(.system(.subheadline, design: .rounded, weight: .semibold))
    }

    private func sectionTitle(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(.system(.caption, design: .rounded, weight: .bold))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
    }

    /// Copies the original to `tmp/records-share/<id>/<name>` so the share sheet offers a
    /// meaningful filename; the copy is removed when the sheet closes (and on next launch).
    private func export(_ record: HealthRecord) {
        guard let source = store.originalURL(for: record) else { return }
        Task {
            let url = await Task.detached(priority: .userInitiated) { () -> URL? in
                RecordExportItem.prepare(source: source, record: record)
            }.value
            if let url { exportItem = RecordExportItem(url: url) }
        }
    }
}

struct RecordExportItem: Identifiable {
    let url: URL
    var id: URL { url }

    nonisolated static func prepare(source: URL, record: HealthRecord) -> URL? {
        let directory = RecordsLocation.shareTempDirectory().appendingPathComponent(record.id, isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let ext = source.pathExtension
            var base = (record.originalFilename.map { ($0 as NSString).deletingPathExtension } ?? record.title)
                .components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>"))
                .joined(separator: "-")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if base.isEmpty { base = "record" }
            let destination = directory.appendingPathComponent(base).appendingPathExtension(ext)
            try FileManager.default.copyItem(at: source, to: destination)
            return destination
        } catch {
            return nil
        }
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }
}

/// PDF pager / zoomable image / text, with protected and unreadable states.
struct RecordOriginalViewer: View {
    let record: HealthRecord
    let pages: [RecordPage]
    let url: URL?
    /// Fixed height inline; nil fills the container (full screen).
    let height: CGFloat?

    var body: some View {
        Group {
            if let url, FileManager.default.fileExists(atPath: url.path) {
                switch record.fileType {
                case .pdf:
                    if record.processingError == "protected_pdf" {
                        unavailable(
                            title: String(localized: "Protected PDF"),
                            message: String(localized: "This PDF is password-protected. Export the original to open it in another app."),
                            icon: "lock.doc.fill"
                        )
                    } else if record.processingError == "unreadable_pdf" {
                        unavailable(title: String(localized: "Can't preview"), message: String(localized: "This PDF couldn't be read. The original file is still saved."), icon: "exclamationmark.triangle.fill")
                    } else {
                        PDFKitView(url: url, pageRange: record.pageStart.flatMap { start in record.pageEnd.map { start...$0 } })
                    }
                case .image:
                    ZoomableImageView(url: url)
                case .text:
                    ScrollView {
                        Text(textContent(url: url))
                            .font(.system(.body, design: .rounded))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                    }
                case .other:
                    unavailable(title: String(localized: "Can't preview"), message: String(localized: "This file type can't be shown here. Export the original to open it in another app."), icon: "doc.fill")
                }
            } else {
                unavailable(title: String(localized: "File missing"), message: String(localized: "The original file for this record couldn't be found."), icon: "questionmark.folder.fill")
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .frame(maxHeight: height == nil ? .infinity : nil)
        .background(AppColors.appCard)
        .clipShape(RoundedRectangle(cornerRadius: height == nil ? 0 : 16, style: .continuous))
        .accessibilityIdentifier("records.detail.viewer")
    }

    private func textContent(url: URL) -> String {
        if let text = pages.first?.text { return text }
        return RecordFileInspector.readText(url: url, maxBytes: 512 * 1_024) ?? ""
    }

    private func unavailable(title: String, message: String, icon: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 36))
                .foregroundStyle(AppColors.calorie)
            Text(title)
                .font(.system(.headline, design: .rounded))
            Text(message)
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text("Original saved")
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct PDFKitView: UIViewRepresentable {
    let url: URL
    /// Split child: only these pages (0-based, inclusive) are shown.
    var pageRange: ClosedRange<Int>? = nil
    /// Page to open at, relative to `pageRange` when set.
    var initialPage: Int? = nil
    /// `[x, y, w, h]` normalized top-left box outlined on `initialPage`.
    var highlightBox: [Double]? = nil

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var loadedKey: String?
    }

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = .secondarySystemBackground
        load(into: view, coordinator: context.coordinator)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        load(into: view, coordinator: context.coordinator)
    }

    private func load(into view: PDFView, coordinator: Coordinator) {
        let key = "\(url.path)|\(pageRange.map { "\($0)" } ?? "")|\(initialPage ?? -1)|\(highlightBox?.description ?? "")"
        guard coordinator.loadedKey != key else { return }
        coordinator.loadedKey = key
        guard let source = PDFDocument(url: url) else { return }
        var document = source
        if let pageRange {
            let restricted = PDFDocument()
            var target = 0
            for index in pageRange where index < source.pageCount {
                if let page = source.page(at: index)?.copy() as? PDFPage {
                    restricted.insert(page, at: target)
                    target += 1
                }
            }
            if restricted.pageCount > 0 { document = restricted }
        }
        view.document = document
        guard let initialPage, let page = document.page(at: min(max(0, initialPage), max(0, document.pageCount - 1))) else { return }
        if let box = highlightBox, box.count == 4 {
            let bounds = page.bounds(for: .cropBox)
            let rect = CGRect(
                x: bounds.minX + box[0] * bounds.width,
                y: bounds.maxY - (box[1] + box[3]) * bounds.height,
                width: box[2] * bounds.width,
                height: box[3] * bounds.height
            ).insetBy(dx: -4, dy: -4)
            let annotation = PDFAnnotation(bounds: rect, forType: .square, withProperties: nil)
            annotation.color = .systemOrange
            let border = PDFBorder()
            border.lineWidth = 3
            annotation.border = border
            page.addAnnotation(annotation)
            DispatchQueue.main.async {
                view.go(to: rect, on: page)
            }
        } else {
            DispatchQueue.main.async {
                view.go(to: page)
            }
        }
    }
}

/// Pinch / double-tap zoom over a downsampled image (max 2048 px, decoded off the main thread).
struct ZoomableImageView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 6
        scrollView.delegate = context.coordinator
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.backgroundColor = .secondarySystemBackground
        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.widthAnchor.constraint(equalTo: scrollView.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.heightAnchor),
            imageView.centerXAnchor.constraint(equalTo: scrollView.centerXAnchor),
            imageView.centerYAnchor.constraint(equalTo: scrollView.centerYAnchor),
        ])
        context.coordinator.imageView = imageView
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)
        context.coordinator.load(url)
        return scrollView
    }

    func updateUIView(_ scrollView: UIScrollView, context: Context) {
        context.coordinator.load(url)
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        private var loadedURL: URL?

        func load(_ url: URL) {
            guard loadedURL != url else { return }
            loadedURL = url
            let path = url.path
            Task { [weak self] in
                let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                    let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
                    let options: [CFString: Any] = [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 2048,
                    ]
                    guard let source, let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
                    return UIImage(cgImage: cgImage)
                }.value
                self?.imageView?.image = image
            }
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        @objc func doubleTapped(_ recognizer: UITapGestureRecognizer) {
            guard let scrollView = recognizer.view as? UIScrollView else { return }
            scrollView.setZoomScale(scrollView.zoomScale > 1 ? 1 : 2.5, animated: true)
        }
    }
}

/// Edit title, date, type, category and notes.
struct RecordEditSheet: View {
    let detail: RecordDetail
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var hasDate: Bool
    @State private var date: Date
    @State private var type: RecordType
    @State private var category: RecordCategory
    @State private var notes: String
    @State private var tagsText: String

    init(detail: RecordDetail) {
        self.detail = detail
        let record = detail.record
        _title = State(initialValue: record.title)
        _hasDate = State(initialValue: record.documentDate != nil)
        _date = State(initialValue: record.documentDate.flatMap { RecordDates.date(fromDay: $0) } ?? Date())
        _type = State(initialValue: record.recordType)
        _category = State(initialValue: record.category)
        _notes = State(initialValue: record.notes ?? "")
        _tagsText = State(initialValue: detail.tags.joined(separator: ", "))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Title") {
                    TextField("Title", text: $title)
                        .accessibilityIdentifier("records.edit.title")
                }
                Section {
                    Toggle("Record date", isOn: $hasDate.animation())
                    if hasDate {
                        DatePicker("Date", selection: $date, in: ...Date.now, displayedComponents: .date)
                    }
                } footer: {
                    Text("The date on the document. Without one, the record is placed by the day it was added.")
                }
                Section("Type") {
                    Picker("Type", selection: $type) {
                        ForEach(RecordType.allCases) { Text($0.title).tag($0) }
                    }
                    .onChange(of: type) { oldValue, newValue in
                        if category == oldValue.defaultCategory { category = newValue.defaultCategory }
                    }
                    Picker("Category", selection: $category) {
                        ForEach(RecordCategory.allCases) { Text($0.title).tag($0) }
                    }
                }
                Section("Notes") {
                    TextEditor(text: $notes)
                        .frame(minHeight: 120)
                }
                Section {
                    TextField("blood, 2026, Dr. Rao", text: $tagsText)
                        .textInputAutocapitalization(.never)
                } header: {
                    Text("Tags")
                } footer: {
                    Text("Separate tags with commas.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Edit Record")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func save() {
        let record = detail.record
        let trimmedTitle = RecordTitleDeriver.collapse(title)
        let newDate: String? = hasDate ? RecordDates.dayString(from: date) : nil
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let patch = RecordPatch(
            title: trimmedTitle == record.title ? nil : trimmedTitle,
            recordType: type == record.recordType ? nil : type,
            category: category == record.category ? nil : category,
            documentDate: newDate == record.documentDate ? nil : .some(newDate),
            notes: trimmedNotes == (record.notes ?? "") ? nil : .some(trimmedNotes.isEmpty ? nil : trimmedNotes)
        )
        let tags = RecordsDatabase.normalizedTagNames(tagsText.components(separatedBy: ","))
        let tagsChanged = tags != detail.tags
        Task {
            if patch != RecordPatch() {
                await store.update(id: record.id, patch: patch)
            }
            if tagsChanged {
                await store.setTags(id: record.id, names: tags)
            }
        }
        dismiss()
    }
}
