import Foundation

// Phase 5 store surface (docs/health-records.md §34–§37): building a share, the portable archive
// and the storage screen. Every heavy step runs off the main actor and reports progress back.

/// §37 storage breakdown, computed in the background.
nonisolated struct RecordsStorageReport: Sendable, Hashable {
    var pdfBytes: Int64 = 0
    var imageBytes: Int64 = 0
    var textBytes: Int64 = 0
    var otherBytes: Int64 = 0
    var thumbnailBytes: Int64 = 0
    var renderCacheBytes: Int64 = 0
    var databaseBytes: Int64 = 0
    var archiveBytes: Int64 = 0
    var recordCount = 0
    var pageCount = 0

    var documentBytes: Int64 { pdfBytes + imageBytes + textBytes + otherBytes }
    var totalBytes: Int64 { documentBytes + thumbnailBytes + renderCacheBytes + databaseBytes + archiveBytes }
}

/// One `records_backup_state` snapshot for the Backup screen (§33).
nonisolated struct RecordsBackupStatus: Sendable, Hashable {
    var lastArchiveMs: Int64?
    var lastArchiveSize: Int64?
    var lastArchiveRecords: Int?
    var lastRestoreMs: Int64?
}

@MainActor
extension RecordsStore {
    // MARK: - §34 Sharing

    /// Summary text of the plan, for the "What will be shared" preview.
    func shareSummaryText(plan: RecordSharePlan) async -> String {
        guard let repository = await openIfNeeded() else { return "" }
        return (try? await repository.database.shareSummaryText(plan: plan)) ?? ""
    }

    /// §34 warnings the screen shows before the final confirm.
    func shareWarnings(plan: RecordSharePlan) async -> [String] {
        guard let repository = await openIfNeeded() else { return [] }
        return (try? await repository.database.sharePlanWarnings(plan: plan)) ?? []
    }

    /// Records of a plan with the data the share screen shows (page counts, page selection).
    func shareDetails(ids: [String]) async -> [RecordDetail] {
        guard let repository = await openIfNeeded() else { return [] }
        var details: [RecordDetail] = []
        for id in ids {
            if let detail = try? await repository.detail(id: id) { details.append(detail) }
        }
        return details
    }

    /// Builds every file of the plan under `tmp/records-share/<uuid>/` (§34).
    func buildShare(plan: RecordSharePlan) async -> RecordShareBundle {
        guard let repository = await openIfNeeded() else { return RecordShareBundle() }
        let database = repository.database
        let summary = plan.includeSummary ? ((try? await database.shareSummaryText(plan: plan)) ?? "") : ""
        var sources: [RecordShareBuilder.Source] = []
        for id in plan.recordIDs {
            guard let detail = try? await repository.detail(id: id) else { continue }
            let redaction = plan.isRedacting
                ? ((try? await database.redactionTargets(recordID: id, classes: plan.redactionValues)) ?? .null)
                : RJ.null
            let pageCount = max(detail.record.pageCount, detail.pages.isEmpty ? 1 : detail.pages.count)
            sources.append(RecordShareBuilder.Source(
                record: detail.record,
                pages: detail.pages,
                originalURL: repository.originalURL(for: detail.record),
                redaction: redaction,
                selectedPages: plan.pages(for: id).resolved(pageCount: pageCount)
            ))
        }
        let directory = RecordsLocation.shareTempDirectory().appendingPathComponent(UUID().uuidString, isDirectory: true)
        let built = sources
        return await Task.detached(priority: .userInitiated) {
            RecordShareBuilder.build(plan: plan, summary: summary, sources: built, directory: directory)
        }.value
    }

    /// §34: a completed share bumps `shared_count` / `last_shared_ms`.
    func markShared(recordIDs: [String]) async {
        guard !recordIDs.isEmpty, let repository = await openIfNeeded() else { return }
        try? await repository.database.markShared(ids: recordIDs)
        bumpRevision()
    }

    // MARK: - §35 Archive

    nonisolated static var appVersionString: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    func exportArchive(includeFiles: Bool, progress: @escaping @Sendable (RecordsArchiveProgress) -> Void) async -> Result<RecordsArchiveResult, Error> {
        guard let repository = await openIfNeeded() else { return .failure(RecordsArchiveError.storageUnavailable) }
        let exporter = RecordsArchiveExporter(database: repository.database, files: repository.files)
        let directory = RecordsLocation.shareTempDirectory()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = RecordsArchiveFormat.suggestedFileName(day: RecordDates.localDayString(ms: RecordDates.nowMs()))
        let url = directory.appendingPathComponent(name)
        let version = Self.appVersionString
        do {
            let result = try await exporter.export(to: url, includeFiles: includeFiles, appVersion: version, progress: progress)
            bumpRevision()
            return .success(result)
        } catch {
            return .failure(error)
        }
    }

    func importArchive(url: URL, mode: RecordsImportMode, progress: @escaping @Sendable (RecordsArchiveProgress) -> Void) async -> Result<RecordsImportSummary, Error> {
        guard let repository = await openIfNeeded() else { return .failure(RecordsArchiveError.storageUnavailable) }
        let importer = RecordsArchiveImporter(database: repository.database, files: repository.files)
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let summary = try await importer.run(url: url, mode: mode, progress: progress)
            await reloadAfterImport()
            return .success(summary)
        } catch {
            return .failure(error)
        }
    }

    func reloadAfterImport() async {
        bumpRevision()
        await reload()
    }

    func backupStatus() async -> RecordsBackupStatus {
        guard let repository = await openIfNeeded() else { return RecordsBackupStatus() }
        let raw = (try? await repository.database.backupState()) ?? [:]
        return RecordsBackupStatus(
            lastArchiveMs: raw[RecordsBackupStateKey.lastArchiveMs].flatMap(Int64.init),
            lastArchiveSize: raw[RecordsBackupStateKey.lastArchiveSize].flatMap(Int64.init),
            lastArchiveRecords: raw[RecordsBackupStateKey.lastArchiveRecords].flatMap(Int.init),
            lastRestoreMs: raw[RecordsBackupStateKey.lastRestoreMs].flatMap(Int64.init)
        )
    }

    // MARK: - §37 Storage

    func storageReport() async -> RecordsStorageReport {
        guard let repository = await openIfNeeded() else { return RecordsStorageReport() }
        let rows = (try? await repository.database.storageRows()) ?? []
        let pageCount = (try? await repository.database.pageRowCount()) ?? 0
        let databaseURL = repository.database.url
        let files = repository.files
        return await Task.detached(priority: .utility) {
            var report = RecordsStorageReport()
            report.recordCount = rows.count
            report.pageCount = pageCount
            for row in rows {
                let bytes = row.filePath.map { files.size(ofRelativePath: $0) } ?? 0
                switch row.fileType {
                case .pdf: report.pdfBytes += bytes
                case .image: report.imageBytes += bytes
                case .text: report.textBytes += bytes
                case .other: report.otherBytes += bytes
                }
                if let thumb = row.thumbnailPath { report.thumbnailBytes += files.size(ofRelativePath: thumb) }
            }
            report.renderCacheBytes = RecordsStorageMath.directorySize(RecordsLocation.renderCacheDirectory())
            report.archiveBytes = RecordsStorageMath.directorySize(RecordsLocation.shareTempDirectory())
            report.databaseBytes = databaseURL.map { HealthDatabaseLocation.totalSizeBytes(for: $0) } ?? 0
            return report
        }.value
    }

    /// §37 "Clear generated cache": render cache + share temp. Originals and thumbnails stay.
    func clearGeneratedCache() async {
        await Task.detached(priority: .utility) {
            try? FileManager.default.removeItem(at: RecordsLocation.renderCacheDirectory())
            try? FileManager.default.removeItem(at: RecordsLocation.shareTempDirectory())
        }.value
        bumpRevision()
    }

    /// §37 "Rebuild search index".
    func rebuildSearchIndex() async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.rebuildAllFTSRows()
        bumpRevision()
    }

    /// §37 "Rebuild thumbnails": drops the stored thumbnails and regenerates them per record.
    func rebuildThumbnails() async {
        guard let repository = await openIfNeeded() else { return }
        let ids = (try? await repository.database.allRecordIDs()) ?? []
        for id in ids {
            guard let record = try? await repository.record(id: id) else { continue }
            _ = await repository.processBasics(record)
        }
        bumpRevision()
        await reload()
    }

    /// §37 "Find duplicates": re-runs near-duplicate detection and opens Needs Review.
    func findDuplicates() async -> Int {
        guard let repository = await openIfNeeded() else { return 0 }
        let found = (try? await repository.database.rescanNearDuplicates()) ?? 0
        bumpRevision()
        await reload()
        return found
    }

    /// §37 "Reprocess all": every record re-enters the pipeline at `text` (user values are kept).
    func reprocessAll() async {
        guard let repository = await openIfNeeded() else { return }
        let ids = (try? await repository.database.allRecordIDs()) ?? []
        guard !ids.isEmpty else { return }
        _ = try? await repository.database.enqueueProcessing(ids: ids, restart: true)
        await processingQueue?.enqueue(ids: ids)
        bumpRevision()
    }
}

nonisolated enum RecordsStorageMath {
    static func directorySize(_ url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let item as URL in enumerator {
            total += Int64((try? item.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }
}
