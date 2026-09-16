import SwiftUI
import UIKit

/// "What will be shared" (docs/health-records.md §34): page selection, summary field toggles,
/// redaction toggles and warnings, then a page-1 preview of every produced file and a final
/// confirm that hands the files to `UIActivityViewController`.
struct ShareRecordScreen: View {
    let recordIDs: [String]
    var onFinished: (() -> Void)?

    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var plan: RecordSharePlan
    @State private var details: [RecordDetail] = []
    @State private var summaryPreview = ""
    @State private var isLoading = true
    @State private var isBuilding = false
    @State private var bundle: RecordShareBundle?
    @State private var expandedPages: Set<String> = []

    init(recordIDs: [String], onFinished: (() -> Void)? = nil) {
        self.recordIDs = recordIDs
        self.onFinished = onFinished
        _plan = State(initialValue: RecordSharePlan(recordIDs: recordIDs))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let bundle {
                    previewList(bundle)
                } else {
                    planList
                }
            }
            .background(AppColors.appBackground)
            .navigationTitle(bundle == nil ? "What will be shared" : "Ready to share")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if bundle != nil { bundle = nil } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if let bundle {
                        Button("Share \(bundle.items.count) items") { share(bundle) }
                            .disabled(bundle.items.isEmpty)
                            .accessibilityIdentifier("records.share.confirm")
                    } else {
                        Button("Preview") { Task { await build() } }
                            .disabled(plan.isEmpty || isBuilding || isLoading)
                            .accessibilityIdentifier("records.share.preview")
                    }
                }
            }
            .accessibilityIdentifier("records.share.screen")
        }
        .task { await load() }
    }

    // MARK: - Plan

    private var planList: some View {
        List {
            Section {
                ForEach(details, id: \.record.id) { detail in
                    recordRow(detail)
                }
            } header: {
                Text(details.count == 1 ? "Record" : "\(details.count) records")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Toggle("Original document", isOn: Binding(get: { plan.includeOriginal }, set: { plan.includeOriginal = $0 }))
                    .accessibilityIdentifier("records.share.includeOriginal")
                if plan.includeOriginal, plan.isRedacting {
                    Text("Redacted pages are shared as images, so the text can't be copied or searched.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                } else if plan.includeOriginal, hasPageSubset {
                    Text("Selected pages are shared as a new PDF that keeps the text layer.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Original")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Toggle("Structured summary", isOn: Binding(get: { plan.includeSummary }, set: { plan.includeSummary = $0 }))
                    .accessibilityIdentifier("records.share.includeSummary")
                if plan.includeSummary {
                    ForEach(RecordSummaryField.allCases) { field in
                        Toggle(field.title, isOn: binding(for: field))
                            .accessibilityIdentifier("records.share.field.\(field.rawValue)")
                    }
                    Toggle("AI highlights", isOn: Binding(get: { plan.includeHighlights }, set: { plan.includeHighlights = $0 }))
                        .accessibilityIdentifier("records.share.field.highlights")
                    Toggle("Notes", isOn: Binding(get: { plan.includeNotes }, set: { plan.includeNotes = $0 }))
                        .accessibilityIdentifier("records.share.field.notes")
                }
            } header: {
                Text("Summary")
            } footer: {
                if plan.includeSummary, !summaryPreview.isEmpty {
                    Text(summaryPreview)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(12)
                }
            }
            .listRowBackground(AppColors.appCard)

            Section {
                ForEach(RecordRedactionClass.allCases) { redaction in
                    Toggle(redaction.title, isOn: binding(for: redaction))
                        .accessibilityIdentifier("records.share.redaction.\(redaction.rawValue)")
                }
            } header: {
                Text("Redact identifiers")
            } footer: {
                Text("Redaction is best-effort: Ayuvo paints over the lines it recognised on each page. Always check the preview before sending.")
            }
            .listRowBackground(AppColors.appCard)
        }
        .font(.system(.body, design: .rounded))
        .scrollContentBackground(.hidden)
        .overlay {
            if isBuilding {
                ProgressView("Building files…")
                    .padding(20)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
    }

    private func recordRow(_ detail: RecordDetail) -> some View {
        let record = detail.record
        let pageCount = max(record.pageCount, detail.pages.isEmpty ? 1 : detail.pages.count)
        return VStack(alignment: .leading, spacing: 6) {
            Text(record.title)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .lineLimit(2)
            Text("\(RecordFormatting.dateText(record)) · \(pageCount == 1 ? String(localized: "1 page") : String(localized: "\(pageCount) pages"))")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
            if pageCount > 1 {
                Button(pageSummary(record.id, pageCount: pageCount)) {
                    if expandedPages.contains(record.id) { expandedPages.remove(record.id) } else { expandedPages.insert(record.id) }
                }
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .accessibilityIdentifier("records.share.pages.\(record.id)")
                if expandedPages.contains(record.id) {
                    pageChips(recordID: record.id, pageCount: pageCount)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func pageSummary(_ recordID: String, pageCount: Int) -> String {
        let selected = plan.pages(for: recordID).resolved(pageCount: pageCount)
        if selected.count == pageCount { return String(localized: "All pages") }
        return String(localized: "\(selected.count) of \(pageCount) pages")
    }

    private func pageChips(recordID: String, pageCount: Int) -> some View {
        let selected = Set(plan.pages(for: recordID).resolved(pageCount: pageCount))
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(0..<pageCount, id: \.self) { index in
                    Button {
                        toggle(page: index, recordID: recordID, pageCount: pageCount)
                    } label: {
                        Text("\(index + 1)")
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .frame(minWidth: 26)
                            .padding(.vertical, 6)
                            .padding(.horizontal, 6)
                            .background(selected.contains(index) ? AppColors.calorie.opacity(0.22) : Color.secondary.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func toggle(page: Int, recordID: String, pageCount: Int) {
        var selected = Set(plan.pages(for: recordID).resolved(pageCount: pageCount))
        if selected.contains(page) { selected.remove(page) } else { selected.insert(page) }
        if selected.isEmpty { selected = [page] }
        plan.pages[recordID] = selected.count == pageCount ? .all : .pages(selected.sorted())
        Task { await refreshSummary() }
    }

    private var hasPageSubset: Bool {
        details.contains { detail in
            let pageCount = max(detail.record.pageCount, 1)
            return plan.pages(for: detail.record.id).resolved(pageCount: pageCount).count < pageCount
        }
    }

    private func binding(for field: RecordSummaryField) -> Binding<Bool> {
        Binding(
            get: { plan.summaryFields.contains(field) },
            set: { on in
                if on { plan.summaryFields.insert(field) } else { plan.summaryFields.remove(field) }
                Task { await refreshSummary() }
            }
        )
    }

    private func binding(for redaction: RecordRedactionClass) -> Binding<Bool> {
        Binding(
            get: { plan.redactions.contains(redaction) },
            set: { on in
                if on { plan.redactions.insert(redaction) } else { plan.redactions.remove(redaction) }
            }
        )
    }

    // MARK: - Preview

    private func previewList(_ bundle: RecordShareBundle) -> some View {
        List {
            if !bundle.warnings.isEmpty {
                Section {
                    ForEach(bundle.warnings, id: \.self) { warning in
                        Label(warning, systemImage: "exclamationmark.triangle.fill")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.orange)
                    }
                } header: {
                    Text("Check before sending")
                }
                .listRowBackground(AppColors.appCard)
                .accessibilityIdentifier("records.share.warnings")
            }
            Section {
                ForEach(bundle.items) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.title)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Text("\(item.url.lastPathComponent) · \(RecordFormatting.sizeText(item.byteCount))")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                        if item.kind == .summary, let text = bundle.summaryText {
                            Text(text)
                                .font(.system(.caption2, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(8)
                                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .accessibilityIdentifier("records.share.summaryPreview")
                        } else if let preview = item.previewPath, let image = UIImage(contentsOfFile: preview.path) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .frame(maxHeight: 260)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Color.secondary.opacity(0.25)))
                                .accessibilityIdentifier("records.share.previewImage")
                        }
                    }
                    .padding(.vertical, 4)
                }
            } header: {
                Text("\(bundle.items.count) files")
            }
            .listRowBackground(AppColors.appCard)
        }
        .font(.system(.body, design: .rounded))
        .scrollContentBackground(.hidden)
    }

    // MARK: - Actions

    private func load() async {
        details = await store.shareDetails(ids: recordIDs)
        isLoading = false
        await refreshSummary()
    }

    private func refreshSummary() async {
        guard plan.includeSummary else {
            summaryPreview = ""
            return
        }
        summaryPreview = await store.shareSummaryText(plan: plan)
    }

    private func build() async {
        isBuilding = true
        let planned = await store.shareWarnings(plan: plan)
        var built = await store.buildShare(plan: plan)
        // Reference warnings first (§34), then anything the builder hit while writing files.
        built.warnings = planned + built.warnings.filter { !planned.contains($0) }
        isBuilding = false
        if built.items.isEmpty, built.warnings.isEmpty {
            store.showBanner(String(localized: "Nothing to share."), systemImage: "exclamationmark.triangle.fill")
            return
        }
        bundle = built
    }

    private func share(_ bundle: RecordShareBundle) {
        let ids = bundle.sharedRecordIDs
        // §38: the summary text goes to the sheet as its own item, next to the files.
        var items: [Any] = bundle.items.map(\.url)
        if let text = bundle.summaryText, !text.isEmpty { items.insert(text, at: 0) }
        ShareSheetPresenter.present(items: items) { completed in
            Task {
                // §34: only a finished share counts towards `shared_count` / `last_shared_ms`.
                if completed { await store.markShared(recordIDs: ids) }
                onFinished?()
                dismiss()
            }
        }
    }
}
