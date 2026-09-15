import ImageIO
import PDFKit
import SwiftUI

/// Where a field or highlight came from, for the source viewer.
struct RecordSourceTarget: Identifiable, Equatable {
    var page: Int
    var box: [Double]?
    var label: String
    var id: String { "\(page)-\(box?.description ?? "")-\(label)" }
}

/// Phase 2 detail sections: status banners, highlights, extracted information, medications,
/// recommendations (docs/health-records.md §15–§16, plan §2).
struct RecordDetailIntelligenceSections: View {
    let detail: RecordDetail
    let onReview: () -> Void
    let onSource: (RecordSourceTarget) -> Void
    let onSplit: () -> Void
    let onDuplicate: (RecordDuplicateCandidate) -> Void
    @Environment(RecordsStore.self) private var store
    @State private var waitingCount = 0

    private var record: HealthRecord { detail.record }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            banners
            highlightsCard
            extractedInformation
            listSection(title: "Medications", systemImage: "pills.fill", keys: [.medication], identifier: "records.detail.medications")
            listSection(title: "Recommendations", systemImage: "checklist", keys: [.recommendation], identifier: "records.detail.recommendations")
        }
        .task(id: detail.awaitingConsent) {
            if detail.awaitingConsent { waitingCount = await store.awaitingConsentIDs().count }
        }
    }

    // MARK: Banners

    @ViewBuilder
    private var banners: some View {
        if detail.awaitingConsent {
            RecordAIConsentBanner(recordIDs: [record.id], waitingCount: waitingCount)
        }
        if record.reviewStatus == .needsReview {
            bannerCard(
                title: String(localized: "Needs review"),
                message: String(localized: "Check the details Ayuvo found before relying on them."),
                systemImage: "exclamationmark.circle.fill",
                tint: .orange,
                button: String(localized: "Review details"),
                identifier: "records.detail.review",
                action: onReview
            )
        }
        if let proposal = detail.splitProposal, proposal.status == .pending {
            bannerCard(
                title: String(localized: "This PDF may contain \(proposal.segments.count) records"),
                message: String(localized: "Nothing is split unless you choose to."),
                systemImage: "rectangle.split.3x1.fill",
                tint: AppColors.calorie,
                button: String(localized: "Review boundaries"),
                identifier: "records.detail.split",
                action: onSplit
            )
        }
        if let candidate = detail.duplicates.first {
            bannerCard(
                title: String(localized: "This looks like an existing record"),
                message: candidate.reason.title,
                systemImage: "doc.on.doc.fill",
                tint: AppColors.calorie,
                button: String(localized: "Compare"),
                identifier: "records.detail.duplicate"
            ) { onDuplicate(candidate) }
        }
        if !detail.awaitingConsent, record.aiModeUsed == .none, detail.job?.stage == .done,
           detail.job?.requestedMode == RecordsAIRequest.none.rawValue || record.processingError == RecordProcessingError.aiUnavailable.rawValue,
           store.aiMode != .off {
            Button {
                Task {
                    store.refreshAIEnvironment()
                    let request: RecordsAIRequest = store.aiEnvironment.localAvailable && store.aiMode != .cloud ? .local : .cloud
                    await store.decideAI(ids: [record.id], request: request)
                }
            } label: {
                Label("Find more details with AI", systemImage: "sparkles")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
            }
            .disabled(!store.aiEnvironment.localAvailable && store.aiEnvironment.cloudProviderName == nil)
            .accessibilityIdentifier("records.detail.findMoreAI")
        }
    }

    private func bannerCard(title: String, message: String, systemImage: String, tint: Color, button: String, identifier: String, action: @escaping () -> Void) -> some View {
        RecordsCard {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: systemImage).foregroundStyle(tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(.subheadline, design: .rounded, weight: .semibold))
                    Text(message).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                }
            }
            Button(button, action: action)
                .buttonStyle(.borderedProminent)
                .tint(tint)
                .controlSize(.small)
                .accessibilityElement(children: .contain)
            .accessibilityIdentifier(identifier)
        }
    }

    // MARK: Highlights

    @ViewBuilder
    private var highlightsCard: some View {
        let visible = detail.highlights.filter { !$0.dismissed }
        let important = visible.filter { $0.section == .important }
        let summary = visible.first { $0.section == .summary }
        if !important.isEmpty || summary != nil {
            RecordsCard {
                RecordsSectionTitle(title: "Highlights", systemImage: "sparkles")
                ForEach(important) { highlight in
                    Button {
                        if let page = highlight.sourcePage { onSource(RecordSourceTarget(page: page, box: nil, label: highlight.text)) }
                    } label: {
                        RecordHighlightRow(text: highlight.text)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("records.detail.highlight")
                }
                if let summary {
                    Divider()
                    Text("AI summary — verify against the original report")
                        .font(.system(.caption, design: .rounded, weight: .bold))
                        .foregroundStyle(.purple)
                    Text(summary.text)
                        .font(.system(.subheadline, design: .rounded))
                        .fixedSize(horizontal: false, vertical: true)
                    if let provider = summary.provider {
                        Text(provider).font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("records.detail.highlights")
        }
    }

    // MARK: Extracted information

    @ViewBuilder
    private var extractedInformation: some View {
        let fields = detail.visibleFields.filter { $0.key != .medication && $0.key != .recommendation }
        if !fields.isEmpty {
            RecordsCard {
                RecordsSectionTitle(title: "Extracted information", systemImage: "text.viewfinder")
                ForEach(RecordFieldGroup.allCases.filter { group in fields.contains { $0.key.group == group } }) { group in
                    Text(group.title)
                        .font(.system(.caption, design: .rounded, weight: .bold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .padding(.top, 4)
                    ForEach(fields.filter { $0.key.group == group }) { field in
                        fieldRow(field)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("records.detail.extracted")
        }
    }

    private func fieldRow(_ field: RecordField) -> some View {
        Button {
            if let page = field.sourcePage {
                onSource(RecordSourceTarget(page: page, box: field.bbox, label: field.evidence ?? field.displayValue))
            }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(field.key == .testResult ? (field.testResult?.name ?? field.valueText) : field.key.title)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                    HStack(spacing: 4) {
                        Text(field.key == .testResult ? field.displayValue : field.displayValue)
                            .font(.system(.subheadline, design: .rounded, weight: .medium))
                            .foregroundStyle(field.testResult?.flag?.isAbnormal == true ? Color.orange : Color.primary)
                        if let ref = field.testResult?.refText {
                            Text("(\(ref))").font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer(minLength: 4)
                if field.state == .confirmed || field.state == .user {
                    Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(.green)
                }
                RecordMethodBadge(method: field.method)
                if field.sourcePage != nil {
                    Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("records.detail.field.\(field.key.rawValue)")
    }

    @ViewBuilder
    private func listSection(title: LocalizedStringKey, systemImage: String, keys: Set<RecordFieldKey>, identifier: String) -> some View {
        let fields = detail.visibleFields.filter { keys.contains($0.key) }
        if !fields.isEmpty {
            RecordsCard {
                RecordsSectionTitle(title: title, systemImage: systemImage)
                ForEach(fields) { field in
                    Button {
                        if let page = field.sourcePage { onSource(RecordSourceTarget(page: page, box: field.bbox, label: field.evidence ?? field.valueText)) }
                    } label: {
                        HStack(alignment: .firstTextBaseline) {
                            Text(field.displayValue)
                                .font(.system(.subheadline, design: .rounded))
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 4)
                            RecordMethodBadge(method: field.method)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(identifier)
        }
    }
}

/// Full-screen original opened at a source page with the evidence box outlined when known.
struct RecordSourceViewer: View {
    let record: HealthRecord
    let url: URL?
    let target: RecordSourceTarget
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let url {
                    switch record.fileType {
                    case .pdf:
                        PDFKitView(url: url, pageRange: record.pageStart.flatMap { start in record.pageEnd.map { start...$0 } }, initialPage: target.page, highlightBox: target.box)
                    case .image:
                        SourceImageView(url: url, box: target.box)
                    default:
                        ScrollView { Text(target.label).padding() }
                    }
                } else {
                    ContentUnavailableView("File missing", systemImage: "questionmark.folder")
                }
            }
            .ignoresSafeArea(edges: .bottom)
            .safeAreaInset(edge: .bottom) {
                Text(target.label)
                    .font(.system(.footnote, design: .rounded))
                    .lineLimit(3)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.bar)
            }
            .navigationTitle(String(localized: "Page \(target.page + 1)"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .accessibilityIdentifier("records.sourceViewer")
    }
}

private struct SourceImageView: View {
    let url: URL
    let box: [Double]?
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color(uiColor: .secondarySystemBackground)
                if let image {
                    let size = fittedSize(image.size, in: geometry.size)
                    Image(uiImage: image)
                        .resizable()
                        .frame(width: size.width, height: size.height)
                        .overlay(alignment: .topLeading) {
                            if let box, box.count == 4 {
                                Rectangle()
                                    .stroke(Color.orange, lineWidth: 3)
                                    .frame(width: box[2] * size.width, height: box[3] * size.height)
                                    .offset(x: box[0] * size.width, y: box[1] * size.height)
                            }
                        }
                } else {
                    ProgressView()
                }
            }
        }
        .task {
            let path = url.path
            image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                let options: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 2048,
                ]
                guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
                      let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
                return UIImage(cgImage: cgImage)
            }.value
        }
    }

    private func fittedSize(_ imageSize: CGSize, in container: CGSize) -> CGSize {
        guard imageSize.width > 0, imageSize.height > 0 else { return container }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        return CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
    }
}
