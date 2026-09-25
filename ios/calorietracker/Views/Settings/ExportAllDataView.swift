import SwiftUI

/// Settings › Data & Privacy › Backup & Export › Export All Data. Runs every existing exporter into
/// a temp directory, packs their files into one `Ayuvo-Export-YYYY-MM-DD.zip` with a manifest
/// (`AllDataExport`), hands it to the share sheet and deletes the temp files when sharing ends.
/// Nothing is uploaded: the zip goes only where the user sends it.
struct ExportAllDataView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(HealthDataStore.self) private var healthDataStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(AppBackupService.self) private var appBackup
    @Environment(CoachStore.self) private var chatStore
    @Environment(\.dismiss) private var dismiss

    @State private var includeRecordFiles = true
    @State private var isExporting = false
    @State private var step: Step?
    @State private var detail: String?
    @State private var fraction: Double?
    @State private var errorMessage: String?

    enum Step: Int, CaseIterable {
        case foodDiary, healthData, medications, healthRecords, coachChats, appBackup, portableData, packing

        var title: LocalizedStringResource {
            switch self {
            case .foodDiary: "Food diary"
            case .healthData: "Health data"
            case .medications: "Medications"
            case .healthRecords: "Health Records"
            case .coachChats: "Coach chats"
            case .appBackup: "Settings, profile & logs"
            case .portableData: "Profile, goals & logs"
            case .packing: "Creating zip"
            }
        }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    contentRow("fork.knife", AyuvoPalette.nutrition, "Food diary", "Meals, nutrition and water (JSON)")
                    contentRow("heart.text.square.fill", AyuvoPalette.vitals, "Health data", "Synced Apple Health samples")
                    contentRow("pills.fill", AyuvoPalette.medications, "Medications", "Medications, schedules and dose log")
                    contentRow("doc.text.fill", AyuvoPalette.records, "Health Records", "Records and their details")
                    contentRow("gearshape.fill", AyuvoPalette.other, "Settings, profile & logs", "Profile, goals, workouts, weight, fasting, meal photos")
                    contentRow("person.crop.circle.fill", AyuvoPalette.other, "Profile, goals & logs", "Also readable on Android: profile, goals, weight, body fat, fasting, workouts")
                } header: {
                    Text("Included")
                } footer: {
                    Text("Sections with nothing in them are left out. Restore it with Import All Data. API keys are never included.")
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    Toggle("Include Health Records files", isOn: $includeRecordFiles)
                        .disabled(isExporting)
                        .accessibilityIdentifier("settings.exportAll.includeRecordFiles")
                } footer: {
                    Text("Adds the original PDFs and photos of your Health Records. The zip can get large.")
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    Button {
                        export()
                    } label: {
                        HStack {
                            Label {
                                Text(isExporting ? "Exporting…" : "Export All Data")
                            } icon: {
                                SettingsIcon("square.and.arrow.up.on.square", tint: SettingsTint.export)
                            }
                            Spacer()
                            if isExporting { ProgressView() }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(isExporting)
                    .accessibilityIdentifier("settings.exportAll.export")

                    if isExporting, let step {
                        VStack(alignment: .leading, spacing: 6) {
                            if let fraction {
                                ProgressView(value: fraction)
                            } else {
                                ProgressView(value: Double(step.rawValue), total: Double(Step.allCases.count))
                            }
                            Text(progressText(step))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.red)
                    }
                } footer: {
                    Text("Creates one zip on this iPhone and opens the share sheet so you can save it to Files or send it. Keep it somewhere safe — it contains your health information.")
                }
                .listRowBackground(AppColors.appCard)
            }
            .font(.system(.body, design: .rounded))
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Export All Data")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .disabled(isExporting)
                }
            }
            .interactiveDismissDisabled(isExporting)
        }
    }

    private func contentRow(_ symbol: String, _ tint: Color, _ title: LocalizedStringKey, _ subtitle: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            SettingsIcon(symbol, tint: tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(subtitle)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func progressText(_ step: Step) -> String {
        let base = String(localized: step.title)
        guard let detail else { return base }
        return "\(base) · \(detail)"
    }

    // MARK: - Export

    private func export() {
        guard !isExporting else { return }
        isExporting = true
        errorMessage = nil
        Task {
            do {
                let url = try await buildArchive()
                isExporting = false
                step = nil
                ShareSheetPresenter.present(url: url) {
                    try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
                }
            } catch {
                isExporting = false
                step = nil
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    private func advance(_ next: Step) {
        step = next
        detail = nil
        fraction = nil
    }

    /// Runs each exporter, then assembles the zip. Returns the zip URL inside its own temp directory.
    private func buildArchive() async throws -> URL {
        let now = Date()
        let work = try AllDataExport.makeWorkDirectory()
        let parts = work.appendingPathComponent("parts", isDirectory: true)
        try FileManager.default.createDirectory(at: parts, withIntermediateDirectories: true)
        var included: [AllDataExport.Part] = []
        var skipped: [String] = []
        do {
            // 1. Food diary — the Export Food Diary JSON over all time (includes water).
            advance(.foodDiary)
            let (start, end) = DiaryExporter.resolve(.allTime, customStart: now, customEnd: now, foodStore: foodStore, waterEntries: waterStore.entries)
            if let (name, data) = DiaryExporter.build(
                from: start, to: end, format: .json, foodStore: foodStore, profile: profileStore.profile, waterEntries: waterStore.entries
            ) {
                let url = parts.appendingPathComponent(name)
                try data.write(to: url, options: .atomic)
                included.append(.init(
                    section: "food_diary", format: "ayuvo-food-diary", name: "food-diary/\(name)", fileURL: url,
                    counts: ["food_entries": foodStore.entries.count, "water_entries": waterStore.entries.count]
                ))
            } else {
                skipped.append("food_diary")
            }

            // 2. Health data — the `ayuvo-health-data` zip, written to its own temp directory.
            advance(.healthData)
            if healthDataStore.hasAnyData {
                let summary = try await healthDataStore.exportHealthData { rows in
                    Task { @MainActor in detail = String(localized: "\(rows) records") }
                }
                let source = summary.url
                defer { try? FileManager.default.removeItem(at: source.deletingLastPathComponent()) }
                if summary.rowCount > 0 {
                    let url = parts.appendingPathComponent(source.lastPathComponent)
                    try FileManager.default.moveItem(at: source, to: url)
                    included.append(.init(
                        section: "health_data", format: HealthExportFormat.format, name: "health-data/\(source.lastPathComponent)", fileURL: url,
                        counts: ["samples": summary.rowCount, "types": summary.typeCount]
                    ))
                } else {
                    skipped.append("health_data")
                }
            } else {
                skipped.append("health_data")
            }

            // 3. Medications — the `ayuvo-medications.json` archive.
            advance(.medications)
            var medications: MedicationArchive?
            do {
                medications = try await medicationStore.exportArchive()
            } catch MedicationStoreError.notOpen {
                medications = nil
            }
            if let archive = medications, archive.medicationCount > 0 {
                let url = parts.appendingPathComponent(MedicationArchive.fileName)
                try archive.data.write(to: url, options: .atomic)
                included.append(.init(
                    section: "medications", format: MR.archiveFormat, name: "medications/\(MedicationArchive.fileName)", fileURL: url,
                    counts: ["medications": archive.medicationCount, "schedules": archive.scheduleCount, "dose_logs": archive.doseLogCount]
                ))
            } else {
                skipped.append("medications")
            }

            // 4. Health Records — the `ayuvo-records` archive. Written
            // straight into this run's work directory: the store's share-temp folder is swept
            // when the store first opens, which could race a first-open export.
            advance(.healthRecords)
            if let repository = await recordsStore.openIfNeeded(), (try await repository.recordCount()) > 0 {
                let exporter = RecordsArchiveExporter(database: repository.database, files: repository.files)
                let name = RecordsArchiveFormat.suggestedFileName(day: RecordDates.localDayString(ms: RecordDates.nowMs()))
                let url = parts.appendingPathComponent(name)
                let result = try await exporter.export(to: url, includeFiles: includeRecordFiles, appVersion: RecordsStore.appVersionString) { update in
                    Task { @MainActor in
                        if update.stage == .files, update.total > 0 {
                            fraction = update.fraction
                            detail = String(localized: "Adding files \(update.done) of \(update.total)")
                        }
                    }
                }
                included.append(.init(
                    section: "health_records", format: RecordsArchiveFormat.format, name: "health-records/\(name)", fileURL: result.url,
                    counts: ["records": result.manifest.recordCount, "files": result.manifest.fileCount]
                ))
            } else {
                skipped.append("health_records")
            }

            // 5. Coach chats — the `ayuvo-coach-chats` archive with the files the user attached.
            advance(.coachChats)
            if let repository = await chatStore.repositoryIfOpen(), let files = await chatStore.fileStore() {
                let url = parts.appendingPathComponent(CoachChatArchiveFormat.fileName)
                let writer = CoachChatArchiveWriter(repository: repository, files: files, appVersion: appVersion)
                let result = try await writer.export(to: url)
                if result.isEmpty {
                    try? FileManager.default.removeItem(at: url)
                    skipped.append("coach_chats")
                } else {
                    included.append(.init(
                        section: "coach_chats", format: CoachChatArchiveFormat.format,
                        name: "coach-chats/\(CoachChatArchiveFormat.fileName)", fileURL: url,
                        counts: [
                            "conversations": result.conversations, "messages": result.messages,
                            "attachments": result.attachments, "files": result.files,
                        ]
                    ))
                }
            } else {
                skipped.append("coach_chats")
            }

            // 6. Settings, profile and logs — the `ayuvo-cloud-backup` zip (AppBackupService),
            // written locally (profile, goals, workouts, weight, fasting, meal photos; no API keys).
            advance(.appBackup)
            let values = appBackup.snapshotValues()
            let photos = appBackup.snapshotPhotos()
            let backup = try CloudBackupArchive.pack(
                values: values,
                photos: photos,
                exportedAt: ISO8601DateFormatter().string(from: now),
                appVersion: appVersion
            )
            let backupURL = parts.appendingPathComponent("ayuvo-backup.zip")
            try backup.write(to: backupURL, options: .atomic)
            included.append(.init(
                section: "app_backup", format: CloudBackupPolicy.format, name: "app-backup/ayuvo-backup.zip", fileURL: backupURL,
                counts: ["settings": values.count, "meal_photos": photos.count]
            ))

            // 7. Profile, goals and logs in the format both platforms read (docs/portable-data.md).
            advance(.portableData)
            if let portable = PortableDataExport.build(now: now, appVersion: appVersion) {
                let url = parts.appendingPathComponent("ayuvo-portable-data.json")
                try portable.data.write(to: url, options: .atomic)
                included.append(.init(
                    section: PortableData.sectionID, format: PortableData.format, name: PortableData.entryName,
                    fileURL: url, counts: portable.counts
                ))
            } else {
                skipped.append(PortableData.sectionID)
            }

            // 8. The outer zip.
            advance(.packing)
            let destination = work.appendingPathComponent(AllDataExport.fileName(now: now))
            let assembled = included
            let skippedSections = skipped
            let version = appVersion
            try await Task.detached(priority: .utility) {
                _ = try AllDataExport.assemble(parts: assembled, skipped: skippedSections, to: destination, createdAt: now, appVersion: version)
            }.value
            try? FileManager.default.removeItem(at: parts)
            return destination
        } catch {
            try? FileManager.default.removeItem(at: work)
            throw error
        }
    }
}
