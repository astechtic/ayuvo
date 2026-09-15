import PDFKit
import SwiftUI
import UIKit
import VisionKit

/// What the Add Record sheet asks its host to present (pickers are presented by
/// `RecordsHomeView` after this sheet closes, so system pickers never stack on a sheet).
enum AddRecordAction: String, Identifiable, CaseIterable {
    case scan, camera, photos, files, importPDF, paste, note

    var id: String { rawValue }

    var title: String {
        switch self {
        case .scan: String(localized: "Scan document")
        case .camera: String(localized: "Take photo")
        case .photos: String(localized: "Choose photos")
        case .files: String(localized: "Choose files")
        case .importPDF: String(localized: "Import PDF")
        case .paste: String(localized: "Paste text")
        case .note: String(localized: "Create note")
        }
    }

    var systemImage: String {
        switch self {
        case .scan: "doc.viewfinder"
        case .camera: "camera.fill"
        case .photos: "photo.on.rectangle.angled"
        case .files: "folder.fill"
        case .importPDF: "doc.richtext.fill"
        case .paste: "doc.on.clipboard"
        case .note: "square.and.pencil"
        }
    }

    static var isScannerSupported: Bool { VNDocumentCameraViewController.isSupported }
    static var isCameraAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }
}

struct AddRecordSheet: View {
    let onSelect: (AddRecordAction) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showReceiveHelp = false

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(AddRecordAction.allCases) { action in
                            tile(action)
                        }
                        receiveTile
                    }
                    if !AddRecordAction.isScannerSupported {
                        Label("Document scanning needs a device camera. Choose a file or photo instead.", systemImage: "info.circle")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    Text("The original is saved exactly as you add it.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .padding()
            }
            .background(AppColors.appBackground)
            .navigationTitle("Add Record")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .alert("Receive from other apps", isPresented: $showReceiveHelp) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("In Files, Mail, WhatsApp or any app, tap Share and choose Ayuvo, then Save to Health Records. PDFs and images can also be opened with \"Open in Ayuvo\".")
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func isEnabled(_ action: AddRecordAction) -> Bool {
        switch action {
        case .scan: AddRecordAction.isScannerSupported
        case .camera: AddRecordAction.isCameraAvailable
        default: true
        }
    }

    private func tile(_ action: AddRecordAction) -> some View {
        Button {
            onSelect(action)
        } label: {
            tileLabel(title: action.title, systemImage: action.systemImage)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled(action))
        .opacity(isEnabled(action) ? 1 : 0.45)
        .accessibilityIdentifier("records.add.\(action.rawValue)")
    }

    private var receiveTile: some View {
        Button {
            showReceiveHelp = true
        } label: {
            tileLabel(title: String(localized: "Receive"), systemImage: "square.and.arrow.down.fill")
        }
        .buttonStyle(.plain)
    }

    private func tileLabel(title: String, systemImage: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
            Text(title)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// VisionKit document camera; the scanned pages are combined into one PDF.
struct DocumentScannerView: UIViewControllerRepresentable {
    let onFinish: (Data?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let onFinish: (Data?) -> Void

        init(onFinish: @escaping (Data?) -> Void) {
            self.onFinish = onFinish
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            let images = (0..<scan.pageCount).map { scan.imageOfPage(at: $0) }
            onFinish(ScannedPDFBuilder.pdfData(from: images))
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onFinish(nil)
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            onFinish(nil)
        }
    }
}

enum ScannedPDFBuilder {
    /// One PDF page per scanned image, sized to the image's aspect ratio (A4-ish width in points).
    static func pdfData(from images: [UIImage]) -> Data? {
        guard !images.isEmpty else { return nil }
        let document = PDFDocument()
        for (index, image) in images.enumerated() {
            let jpeg = image.jpegData(compressionQuality: 0.85).flatMap(UIImage.init(data:)) ?? image
            if let page = PDFPage(image: jpeg) {
                document.insert(page, at: index)
            }
        }
        guard document.pageCount > 0 else { return nil }
        return document.dataRepresentation()
    }
}

/// Paste text / Create note editor.
struct RecordTextEntrySheet: View {
    enum Mode {
        case paste, note
    }

    let mode: Mode
    let onSave: (_ title: String, _ text: String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(mode == .note ? "Title (optional)" : "Title (optional)", text: $title)
                }
                Section {
                    TextEditor(text: $text)
                        .frame(minHeight: 220)
                        .focused($focused)
                        .accessibilityIdentifier("records.textEntry")
                } header: {
                    Text(mode == .paste ? "Text" : "Note")
                } footer: {
                    if mode == .paste {
                        Button {
                            if let clip = UIPasteboard.general.string { text = clip }
                        } label: {
                            Label("Paste from clipboard", systemImage: "doc.on.clipboard")
                        }
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle(mode == .paste ? "Paste text" : "New note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(title, text)
                        dismiss()
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                if mode == .paste, text.isEmpty, UIPasteboard.general.hasStrings {
                    text = UIPasteboard.general.string ?? ""
                }
                focused = true
            }
        }
    }
}

/// "This looks like an existing record" (Phase 1: Keep both · Cancel import · Open existing).
struct RecordDuplicateSheet: View {
    let prompt: RecordDuplicatePrompt
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                HStack(spacing: 16) {
                    column(record: prompt.existing, caption: String(localized: "Existing"))
                    column(record: prompt.newRecord, caption: String(localized: "New"))
                }
                Label("Identical file", systemImage: "equal.circle.fill")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                Text("This file is byte-for-byte the same as a record you already saved.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                VStack(spacing: 10) {
                    Button {
                        store.resolveDuplicateKeepBoth(prompt)
                        dismiss()
                    } label: {
                        Text("Keep both").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)

                    Button {
                        Task { await store.resolveDuplicateOpenExisting(prompt) }
                        dismiss()
                    } label: {
                        Text("Open existing").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    Button(role: .destructive) {
                        Task { await store.resolveDuplicateCancel(prompt) }
                        dismiss()
                    } label: {
                        Text("Cancel import").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    Text("Open existing and Cancel import don't keep the new copy.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .controlSize(.large)
            }
            .padding()
            .navigationTitle("This looks like an existing record")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled()
    }

    private func column(record: HealthRecord, caption: String) -> some View {
        VStack(spacing: 6) {
            RecordThumbnailView(record: record, cornerRadius: 12)
                .frame(width: 120, height: 150)
            Text(caption)
                .font(.system(.caption, design: .rounded, weight: .bold))
                .foregroundStyle(.secondary)
            Text(record.title)
                .font(.system(.caption, design: .rounded))
                .lineLimit(2)
                .multilineTextAlignment(.center)
            Text(RecordFormatting.dateText(record))
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}
