import PDFKit
import SwiftUI

/// "This PDF may contain N records" (plan §2, §3.11): page strip coloured by segment, tap a page
/// to start or join a segment, per-segment title and type, Save as separate records / Keep as one.
struct RecordSplitReviewView: View {
    let recordID: String
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var detail: RecordDetail?
    @State private var segments: [RecordSplitSegment] = []
    @State private var pageCount = 0
    @State private var thumbnails: [Int: UIImage] = [:]
    @State private var isSaving = false
    @State private var confirmSave = false

    static let palette: [Color] = [.blue, .orange, .green, .purple, .pink, .teal, .indigo, .brown]

    var body: some View {
        NavigationStack {
            Group {
                if detail != nil {
                    content
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(AppColors.appBackground)
            .navigationTitle(segments.count > 1 ? String(localized: "This PDF may contain \(segments.count) records") : String(localized: "Review boundaries"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .task { await load() }
        .confirmationDialog("Save as \(segments.count) separate records?", isPresented: $confirmSave, titleVisibility: .visible) {
            Button("Save records") { save() }
                .accessibilityIdentifier("records.split.confirm")
        } message: {
            Text("The original PDF is kept, archived and marked as a combined document. Each new record opens the same file at its pages.")
        }
    }

    private func load() async {
        detail = await store.detail(id: recordID)
        guard let detail else { return }
        pageCount = max(detail.record.pageCount, detail.pages.count)
        segments = detail.splitProposal?.segments ?? [RecordSplitSegment(pageStart: 0, pageEnd: max(0, pageCount - 1), recordType: detail.record.recordType, title: detail.record.title, confidence: 0)]
        guard let url = store.originalURL(for: detail.record) else { return }
        let count = pageCount
        for index in 0..<min(count, 200) {
            let image = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let document = PDFDocument(url: url), let page = document.page(at: index) else { return nil }
                return page.thumbnail(of: CGSize(width: 90, height: 120), for: .cropBox)
            }.value
            if let image { thumbnails[index] = image }
        }
    }

    private func segmentIndex(for page: Int) -> Int {
        segments.firstIndex { $0.pageStart <= page && page <= $0.pageEnd } ?? 0
    }

    private var content: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Tap a page to start a new record there, or tap the first page of a record to join it with the one before.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 8) {
                            ForEach(0..<pageCount, id: \.self) { page in
                                pageTile(page)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .accessibilityIdentifier("records.split.pages")
                    ForEach(Array(segments.enumerated()), id: \.offset) { index, segment in
                        segmentCard(index: index, segment: segment)
                    }
                }
                .padding()
            }
            VStack(spacing: 10) {
                Button {
                    confirmSave = true
                } label: {
                    Text("Save as separate records").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .disabled(segments.count < 2 || isSaving)
                .accessibilityIdentifier("records.split.save")
                Button {
                    Task {
                        await store.keepSplitAsOne(parentID: recordID)
                        dismiss()
                    }
                } label: {
                    Text("Keep as one document").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("records.split.keep")
            }
            .controlSize(.large)
            .padding()
            .background(.bar)
        }
    }

    private func pageTile(_ page: Int) -> some View {
        let index = segmentIndex(for: page)
        let color = Self.palette[index % Self.palette.count]
        let isStart = segments.indices.contains(index) && segments[index].pageStart == page
        return Button {
            toggleBoundary(at: page)
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6).fill(Color.white)
                    if let image = thumbnails[page] {
                        Image(uiImage: image).resizable().scaledToFit()
                    } else {
                        ProgressView().controlSize(.mini)
                    }
                }
                .frame(width: 60, height: 80)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(color, lineWidth: isStart ? 3 : 1.5))
                Text("\(page + 1)")
                    .font(.system(.caption2, design: .rounded, weight: isStart ? .bold : .regular))
                    .foregroundStyle(color)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Page \(page + 1), record \(index + 1)"))
    }

    private func toggleBoundary(at page: Int) {
        guard page > 0 else { return }
        let index = segmentIndex(for: page)
        if segments[index].pageStart == page, index > 0 {
            // Join with the previous record.
            segments[index - 1].pageEnd = segments[index].pageEnd
            segments.remove(at: index)
        } else {
            let original = segments[index]
            var head = original
            head.pageEnd = page - 1
            var tail = original
            tail.pageStart = page
            tail.title = String(localized: "Record from page \(page + 1)")
            tail.confidence = 0
            segments[index] = head
            segments.insert(tail, at: index + 1)
        }
    }

    private func segmentCard(index: Int, segment: RecordSplitSegment) -> some View {
        let color = Self.palette[index % Self.palette.count]
        return RecordsCard {
            HStack {
                Circle().fill(color).frame(width: 10, height: 10)
                Text(segment.pageStart == segment.pageEnd ? String(localized: "Page \(segment.pageStart + 1)") : String(localized: "Pages \(segment.pageStart + 1)–\(segment.pageEnd + 1)"))
                    .font(.system(.caption, design: .rounded, weight: .bold))
                    .foregroundStyle(.secondary)
                Spacer()
                Menu {
                    ForEach(RecordType.allCases) { type in
                        Button(type.title) { segments[index].recordType = type }
                    }
                } label: {
                    RecordTypePill(type: segment.recordType)
                }
            }
            TextField("Title", text: Binding(get: { segments.indices.contains(index) ? segments[index].title : "" }, set: { if segments.indices.contains(index) { segments[index].title = $0 } }))
                .font(.system(.body, design: .rounded, weight: .medium))
                .textFieldStyle(.roundedBorder)
        }
    }

    private func save() {
        isSaving = true
        let segments = segments
        Task {
            _ = await store.acceptSplit(parentID: recordID, segments: segments)
            isSaving = false
            dismiss()
        }
    }
}
