import SwiftUI

/// Settings › Health Records › Storage (docs/health-records.md §37). Sizes are computed in the
/// background; every action confirms first and shows progress. Originals are never deleted.
struct RecordsStorageScreen: View {
    @Environment(RecordsStore.self) private var store

    @State private var report: RecordsStorageReport?
    @State private var runningAction: Action?
    @State private var pendingAction: Action?
    @State private var resultMessage: String?

    enum Action: String, Identifiable, CaseIterable {
        case clearCache
        case rebuildThumbnails
        case rebuildIndex
        case findDuplicates
        case reprocessAll

        var id: String { rawValue }

        var title: String {
            switch self {
            case .clearCache: String(localized: "Clear generated cache")
            case .rebuildThumbnails: String(localized: "Rebuild thumbnails")
            case .rebuildIndex: String(localized: "Rebuild search index")
            case .findDuplicates: String(localized: "Find duplicates")
            case .reprocessAll: String(localized: "Reprocess all")
            }
        }

        var systemImage: String {
            switch self {
            case .clearCache: "trash"
            case .rebuildThumbnails: "photo.on.rectangle"
            case .rebuildIndex: "magnifyingglass"
            case .findDuplicates: "doc.on.doc"
            case .reprocessAll: "arrow.clockwise"
            }
        }

        var question: String {
            switch self {
            case .clearCache: String(localized: "Clear the generated cache?")
            case .rebuildThumbnails: String(localized: "Rebuild every thumbnail?")
            case .rebuildIndex: String(localized: "Rebuild the search index?")
            case .findDuplicates: String(localized: "Look for duplicate records?")
            case .reprocessAll: String(localized: "Reprocess every record?")
            }
        }

        var explanation: String {
            switch self {
            case .clearCache: String(localized: "Removes the page render cache and any archive left in the share folder. Your documents and thumbnails are untouched.")
            case .rebuildThumbnails: String(localized: "Regenerates the small preview image of every record from its original file.")
            case .rebuildIndex: String(localized: "Rebuilds the search index from the records already on this iPhone.")
            case .findDuplicates: String(localized: "Compares every record and lists new duplicate candidates under Needs Review.")
            case .reprocessAll: String(localized: "Runs text extraction and detail finding again on every record. Details you confirmed or edited are kept.")
            }
        }
    }

    var body: some View {
        List {
            Section {
                if let report {
                    sizeRow("PDFs", report.pdfBytes)
                    sizeRow("Images", report.imageBytes)
                    sizeRow("Text", report.textBytes)
                    if report.otherBytes > 0 { sizeRow("Other files", report.otherBytes) }
                    sizeRow("Thumbnails", report.thumbnailBytes)
                    sizeRow("Render cache", report.renderCacheBytes)
                    sizeRow("Database", report.databaseBytes)
                    sizeRow("Archives", report.archiveBytes)
                    HStack {
                        Text("Total").fontWeight(.semibold)
                        Spacer()
                        Text(RecordFormatting.sizeText(report.totalBytes)).fontWeight(.semibold)
                    }
                    .accessibilityIdentifier("records.storage.total")
                } else {
                    HStack {
                        Text("Calculating…").foregroundStyle(.secondary)
                        Spacer()
                        ProgressView()
                    }
                }
            } header: {
                Text("Storage used")
            } footer: {
                if let report {
                    Text("\(report.recordCount) records · \(report.pageCount) pages")
                }
            }
            .listRowBackground(AppColors.appCard)

            Section {
                ForEach(Action.allCases) { action in
                    Button {
                        pendingAction = action
                    } label: {
                        HStack {
                            Label(action.title, systemImage: action.systemImage)
                            Spacer()
                            if runningAction == action { ProgressView() }
                        }
                    }
                    .disabled(runningAction != nil)
                    .accessibilityIdentifier("records.storage.\(action.rawValue)")
                }
            } header: {
                Text("Maintenance")
            } footer: {
                Text("None of these actions deletes an original document.")
            }
            .listRowBackground(AppColors.appCard)
        }
        .font(.system(.body, design: .rounded))
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Storage")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("records.storage.screen")
        .task(id: store.revision) { report = await store.storageReport() }
        .confirmationDialog(
            pendingAction?.question ?? "",
            isPresented: Binding(get: { pendingAction != nil }, set: { if !$0 { pendingAction = nil } }),
            titleVisibility: .visible
        ) {
            if let action = pendingAction {
                Button(action.title) {
                    pendingAction = nil
                    Task { await run(action) }
                }
                Button("Cancel", role: .cancel) { pendingAction = nil }
            }
        } message: {
            Text(pendingAction?.explanation ?? "")
        }
        .alert("Done", isPresented: Binding(get: { resultMessage != nil }, set: { if !$0 { resultMessage = nil } })) {
            Button("OK", role: .cancel) { resultMessage = nil }
        } message: {
            Text(resultMessage ?? "")
        }
    }

    private func sizeRow(_ label: LocalizedStringKey, _ bytes: Int64) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(RecordFormatting.sizeText(bytes)).foregroundStyle(.secondary)
        }
    }

    private func run(_ action: Action) async {
        runningAction = action
        switch action {
        case .clearCache:
            await store.clearGeneratedCache()
            resultMessage = String(localized: "The generated cache was cleared.")
        case .rebuildThumbnails:
            await store.rebuildThumbnails()
            resultMessage = String(localized: "Thumbnails were rebuilt.")
        case .rebuildIndex:
            await store.rebuildSearchIndex()
            resultMessage = String(localized: "The search index was rebuilt.")
        case .findDuplicates:
            let found = await store.findDuplicates()
            resultMessage = found == 0
                ? String(localized: "No new duplicates were found.")
                : String(localized: "\(found) possible duplicates were added to Needs Review.")
        case .reprocessAll:
            await store.reprocessAll()
            resultMessage = String(localized: "Every record was queued for processing again.")
        }
        runningAction = nil
        report = await store.storageReport()
    }
}
