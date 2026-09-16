import SwiftUI
import UniformTypeIdentifiers

/// Settings › Health Records › Backup & restore (docs/health-records.md §35, §36).
/// iOS has no cloud counterpart by design (App Store guideline 5.1.3(ii)): export an archive
/// and keep it wherever you choose, and restore it later with Merge or Replace.
struct RecordsBackupScreen: View {
    @Environment(RecordsStore.self) private var store

    @State private var status = RecordsBackupStatus()
    @State private var includeFiles = true
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var progress: RecordsArchiveProgress?
    @State private var errorMessage: String?
    @State private var summary: RecordsImportSummary?
    @State private var showFileImporter = false
    @State private var pendingArchive: URL?
    @State private var pendingMode: RecordsImportMode = .merge
    @State private var confirmReplace = false

    var body: some View {
        List {
            statusSection
            exportSection
            restoreSection
            if let summary { summarySection(summary) }
            Section {
                Text("Archives contain every record, its details and its original files. They are saved where you choose and are never uploaded by Ayuvo. Health Records are not part of iCloud or iPhone backups.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .listRowBackground(AppColors.appCard)
        }
        .font(.system(.body, design: .rounded))
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Backup & restore")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("records.backup.screen")
        .task(id: store.revision) { status = await store.backupStatus() }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.zip, .archive, .data], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                pendingArchive = url
                if pendingMode == .replace { confirmReplace = true } else { Task { await runImport() } }
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
        .confirmationDialog("Replace everything on this iPhone?", isPresented: $confirmReplace, titleVisibility: .visible) {
            Button("Replace", role: .destructive) { Task { await runImport() } }
            Button("Cancel", role: .cancel) { pendingArchive = nil }
        } message: {
            Text("Every record and original file on this iPhone is deleted first, then the archive is imported. This can't be undone.")
        }
        .alert("Couldn't finish", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var statusSection: some View {
        Section {
            row("Records", "\(store.totalCount)")
            if let ms = status.lastArchiveMs {
                row("Last archive", Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened))
                if let size = status.lastArchiveSize { row("Archive size", RecordFormatting.sizeText(size)) }
                if let count = status.lastArchiveRecords { row("Records in archive", "\(count)") }
            } else {
                Text("No archive created yet.")
                    .foregroundStyle(.secondary)
            }
            if let ms = status.lastRestoreMs {
                row("Last restore", Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened))
            }
        } header: {
            Text("Status")
        }
        .listRowBackground(AppColors.appCard)
        .accessibilityIdentifier("records.backup.status")
    }

    private var exportSection: some View {
        Section {
            Toggle("Include original files", isOn: $includeFiles)
                .accessibilityIdentifier("records.backup.includeFiles")
            Button {
                Task { await runExport() }
            } label: {
                HStack {
                    Label("Create archive", systemImage: "square.and.arrow.up.on.square")
                    Spacer()
                    if isExporting { ProgressView() }
                }
            }
            .disabled(isExporting || isImporting)
            .accessibilityIdentifier("records.backup.export")
            if isExporting, let progress, progress.stage == .files {
                ProgressView(value: progress.fraction) {
                    Text("Adding files \(progress.done) of \(progress.total)")
                        .font(.system(.caption, design: .rounded))
                }
            }
        } header: {
            Text("Export")
        } footer: {
            Text(includeFiles
                 ? "The archive holds the records and their original documents."
                 : "Only the record details are exported; the original documents stay on this iPhone.")
        }
        .listRowBackground(AppColors.appCard)
    }

    private var restoreSection: some View {
        Section {
            Picker("Mode", selection: $pendingMode) {
                ForEach(RecordsImportMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("records.backup.mode")
            Text(pendingMode.subtitle)
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
            Button {
                showFileImporter = true
            } label: {
                HStack {
                    Label("Restore from archive", systemImage: "square.and.arrow.down")
                    Spacer()
                    if isImporting { ProgressView() }
                }
            }
            .disabled(isExporting || isImporting)
            .accessibilityIdentifier("records.backup.restore")
            if isImporting, let progress {
                ProgressView(value: progress.fraction) {
                    Text(progressLabel(progress))
                        .font(.system(.caption, design: .rounded))
                }
            }
        } header: {
            Text("Restore")
        }
        .listRowBackground(AppColors.appCard)
    }

    private func summarySection(_ summary: RecordsImportSummary) -> some View {
        Section {
            row("Imported", "\(summary.importedRecords)")
            if summary.skippedRecords > 0 { row("Already here", "\(summary.skippedRecords)") }
            row("Files restored", "\(summary.importedFiles)")
            if summary.missingFiles > 0 {
                Label("\(summary.missingFiles) records have no original file in the archive.", systemImage: "doc.questionmark")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.orange)
            }
            if !summary.checksumWarnings.isEmpty {
                Label("\(summary.checksumWarnings.count) files didn't match their checksum and may be damaged.", systemImage: "exclamationmark.triangle.fill")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.orange)
                    .accessibilityIdentifier("records.backup.checksumWarning")
            }
        } header: {
            Text("Last import")
        }
        .listRowBackground(AppColors.appCard)
        .accessibilityIdentifier("records.backup.summary")
    }

    private func progressLabel(_ progress: RecordsArchiveProgress) -> String {
        switch progress.stage {
        case .metadata: String(localized: "Reading the archive…")
        case .rows: String(localized: "Importing records \(progress.done) of \(progress.total)")
        case .files: String(localized: "Restoring files \(progress.done) of \(progress.total)")
        case .indexing: String(localized: "Rebuilding the search index…")
        case .done: String(localized: "Done")
        }
    }

    private func row(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).foregroundStyle(.secondary)
        }
    }

    // MARK: - Actions

    private func runExport() async {
        isExporting = true
        progress = nil
        let result = await store.exportArchive(includeFiles: includeFiles) { update in
            Task { @MainActor in progress = update }
        }
        isExporting = false
        progress = nil
        switch result {
        case .success(let archive):
            status = await store.backupStatus()
            ShareSheetPresenter.present(url: archive.url)
        case .failure(let error):
            errorMessage = error.localizedDescription
        }
    }

    private func runImport() async {
        guard let url = pendingArchive else { return }
        pendingArchive = nil
        isImporting = true
        progress = nil
        let result = await store.importArchive(url: url, mode: pendingMode) { update in
            Task { @MainActor in progress = update }
        }
        isImporting = false
        progress = nil
        switch result {
        case .success(let imported):
            summary = imported
            status = await store.backupStatus()
            store.showBanner(String(localized: "\(imported.importedRecords) records restored"))
        case .failure(let error):
            errorMessage = error.localizedDescription
        }
    }
}
