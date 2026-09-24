import SwiftUI
import UniformTypeIdentifiers

/// Settings › Data & Privacy › Backup & Export › Import All Data. Picks an `Ayuvo-Export-….zip`
/// (`AllDataExport`, either platform), shows what it holds and what importing does, then hands
/// each part to its existing importer in `AllDataImport.Section` order. A failing part doesn't
/// stop the others; temp copies are deleted when the sheet closes.
struct ImportAllDataView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(HealthDataStore.self) private var healthDataStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(AppBackupService.self) private var appBackup
    @Environment(CoachStore.self) private var chatStore
    @Environment(\.dismiss) private var dismiss

    @State private var showPicker = false
    @State private var workDirectory: URL?
    @State private var archiveURL: URL?
    @State private var plan: AllDataImport.Plan?
    @State private var isLoading = false
    @State private var isImporting = false
    @State private var current: AllDataImport.Section?
    @State private var results: [SectionResult]?
    @State private var errorMessage: String?

    struct SectionResult: Identifiable {
        enum Outcome {
            case imported(String)
            case skipped(String)
            case failed(String)
        }

        var section: AllDataImport.Section
        var outcome: Outcome
        var id: String { section.rawValue }
    }

    var body: some View {
        NavigationStack {
            List {
                if let results {
                    resultsSection(results)
                } else if let plan {
                    previewSections(plan)
                } else {
                    chooseSection
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.red)
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .font(.system(.body, design: .rounded))
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Import All Data")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(results == nil ? "Cancel" : "Done") { dismiss() }
                        .disabled(isImporting)
                }
            }
            .interactiveDismissDisabled(isImporting)
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.zip]) { result in
                load(result)
            }
            .onDisappear(perform: cleanUp)
        }
    }

    // MARK: - Sections

    private var chooseSection: some View {
        Section {
            Button {
                errorMessage = nil
                showPicker = true
            } label: {
                HStack {
                    Label {
                        Text(isLoading ? "Reading…" : "Choose Export File")
                    } icon: {
                        SettingsIcon("square.and.arrow.down.on.square.fill", tint: SettingsTint.importData)
                    }
                    Spacer()
                    if isLoading { ProgressView() }
                }
            }
            .buttonStyle(.plain)
            .disabled(isLoading)
            .accessibilityIdentifier("settings.importAll.choose")
        } footer: {
            Text("Pick an Ayuvo-Export zip made with Export All Data on an iPhone or Android phone. You'll see what's inside before anything changes.")
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    private func previewSections(_ plan: AllDataImport.Plan) -> some View {
        Section {
            LabeledContent("Created", value: createdText(plan.manifest.createdAt))
            LabeledContent("From", value: plan.isFromThisPlatform ? String(localized: "iPhone") : String(localized: "Android"))
        }
        .listRowBackground(AppColors.appCard)

        Section {
            ForEach(plan.items, id: \.section) { item in
                HStack(alignment: .top, spacing: 12) {
                    SettingsIcon(symbol(item.section), tint: tint(item.section))
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(title(item.section))
                            Spacer()
                            if isImporting, current == item.section { ProgressView() }
                        }
                        let counts = AllDataImport.countsText(item.counts)
                        if !counts.isEmpty {
                            Text(counts)
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Text(effect(item))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(item.disposition == .otherPlatform ? .orange : .secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text("In this file")
        } footer: {
            if plan.items.isEmpty {
                Text("This export has nothing Ayuvo can import.")
            }
        }
        .listRowBackground(AppColors.appCard)

        Section {
            Button {
                run(plan)
            } label: {
                HStack {
                    Label {
                        Text(isImporting ? "Importing…" : "Import")
                    } icon: {
                        SettingsIcon("square.and.arrow.down.fill", tint: SettingsTint.importData)
                    }
                    Spacer()
                    if isImporting { ProgressView() }
                }
            }
            .buttonStyle(.plain)
            .disabled(isImporting || !plan.items.contains { $0.disposition != .otherPlatform })
            .accessibilityIdentifier("settings.importAll.import")
        } footer: {
            Text("API keys are never in an export; add them again in Settings › AI Providers if needed.")
        }
        .listRowBackground(AppColors.appCard)
    }

    private func resultsSection(_ results: [SectionResult]) -> some View {
        Section {
            ForEach(results) { result in
                HStack(alignment: .top, spacing: 12) {
                    SettingsIcon(symbol(result.section), tint: tint(result.section))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title(result.section))
                        switch result.outcome {
                        case .imported(let text):
                            Text(text).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                        case .skipped(let text):
                            Text(text).font(.system(.caption, design: .rounded)).foregroundStyle(.orange)
                        case .failed(let text):
                            Text(text).font(.system(.caption, design: .rounded)).foregroundStyle(.red)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text("Import finished")
        }
        .listRowBackground(AppColors.appCard)
    }

    // MARK: - Labels

    private func title(_ section: AllDataImport.Section) -> LocalizedStringKey {
        switch section {
        case .appBackup: "Settings, profile & logs"
        case .foodDiary: "Food diary"
        case .healthData: "Health data"
        case .medications: "Medications"
        case .healthRecords: "Health Records"
        case .coachChats: "Coach chats"
        }
    }

    private func symbol(_ section: AllDataImport.Section) -> String {
        switch section {
        case .appBackup: "gearshape.fill"
        case .foodDiary: "fork.knife"
        case .healthData: "heart.text.square.fill"
        case .medications: "pills.fill"
        case .healthRecords: "doc.text.fill"
        case .coachChats: "bubble.left.and.bubble.right.fill"
        }
    }

    private func tint(_ section: AllDataImport.Section) -> Color {
        switch section {
        case .appBackup: AyuvoPalette.other
        case .foodDiary: AyuvoPalette.nutrition
        case .healthData: AyuvoPalette.vitals
        case .medications: AyuvoPalette.medications
        case .healthRecords: AyuvoPalette.records
        case .coachChats: AyuvoPalette.other
        }
    }

    /// What importing does to each part — kept in step with the importers' real behaviour.
    private func effect(_ item: AllDataImport.Item) -> String {
        switch (item.section, item.disposition) {
        case (_, .otherPlatform):
            String(localized: "Skipped: settings from an Android export can't be applied on iPhone.")
        case (_, .coveredByAppBackup):
            String(localized: "Restored with settings, profile & logs.")
        case (.appBackup, _):
            String(localized: "Replaces this iPhone's settings, profile, food diary, water, weight, workouts, fasting and meal photos with the ones in the file.")
        case (.foodDiary, _):
            String(localized: "Adds entries that aren't on this iPhone and updates matching ones. Nothing is deleted.")
        case (.healthData, _):
            String(localized: "Adds new samples and updates matching ones. Nothing is deleted.")
        case (.medications, _):
            String(localized: "Adds new medications, schedules and doses and keeps the newer copy of each. Nothing is deleted.")
        case (.healthRecords, _):
            String(localized: "Adds records that aren't on this iPhone yet. Nothing is removed.")
        case (.coachChats, _):
            String(localized: "Adds chats that aren't on this iPhone and keeps the newer copy of each. A chat you deleted here stays deleted.")
        }
    }

    private func createdText(_ raw: String) -> String {
        let formatter = ISO8601DateFormatter()
        guard let date = formatter.date(from: raw) else { return raw }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    // MARK: - Loading

    private func load(_ result: Result<URL, Error>) {
        errorMessage = nil
        guard case .success(let picked) = result else {
            if case .failure(let error) = result { errorMessage = error.localizedDescription }
            return
        }
        isLoading = true
        Task {
            do {
                cleanUp()
                let work = try AllDataImport.makeWorkDirectory()
                workDirectory = work
                let copy = work.appendingPathComponent("export.zip")
                let accessed = picked.startAccessingSecurityScopedResource()
                defer { if accessed { picked.stopAccessingSecurityScopedResource() } }
                try await Task.detached(priority: .userInitiated) {
                    try FileManager.default.copyItem(at: picked, to: copy)
                }.value
                let loaded = try await Task.detached(priority: .userInitiated) {
                    try AllDataImport.plan(url: copy)
                }.value
                archiveURL = copy
                plan = loaded
            } catch {
                cleanUp()
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            isLoading = false
        }
    }

    private func cleanUp() {
        guard !isImporting, let workDirectory else { return }
        try? FileManager.default.removeItem(at: workDirectory)
        self.workDirectory = nil
        archiveURL = nil
    }

    // MARK: - Import

    private func run(_ plan: AllDataImport.Plan) {
        guard !isImporting, let archiveURL, let workDirectory else { return }
        isImporting = true
        errorMessage = nil
        Task {
            var done: [SectionResult] = []
            var appBackupRestored = false
            let reader: ZipArchiveReader
            do {
                reader = try ZipArchiveReader(url: archiveURL)
            } catch {
                errorMessage = AllDataImport.ImportError.damaged.errorDescription
                isImporting = false
                return
            }
            for item in plan.items {
                current = item.section
                guard AllDataImport.shouldImport(item, appBackupRestored: appBackupRestored) else {
                    let reason = item.disposition == .otherPlatform
                        ? String(localized: "Settings from an Android export can't be applied on iPhone.")
                        : String(localized: "Already restored with settings, profile & logs.")
                    done.append(SectionResult(section: item.section, outcome: .skipped(reason)))
                    continue
                }
                let file = workDirectory.appendingPathComponent("part-\(item.section.rawValue)-\((item.entryName as NSString).lastPathComponent)")
                do {
                    try await Task.detached(priority: .userInitiated) {
                        try AllDataImport.extract(entryNamed: item.entryName, from: reader, to: file)
                    }.value
                    defer { try? FileManager.default.removeItem(at: file) }
                    let text = try await importPart(item.section, file: file)
                    if item.section == .appBackup { appBackupRestored = true }
                    done.append(SectionResult(section: item.section, outcome: .imported(text)))
                } catch {
                    done.append(SectionResult(section: item.section, outcome: .failed(failureText(error))))
                }
            }
            current = nil
            isImporting = false
            results = done
            cleanUp()
        }
    }

    private func failureText(_ error: Error) -> String {
        if case MedicationStoreError.archive(let code) = error {
            return code == "unsupported_version"
                ? String(localized: "Made by a newer version of Ayuvo.")
                : String(localized: "The medications file couldn't be read.")
        }
        return (error as? LocalizedError)?.errorDescription ?? String(localized: "Couldn't import this part.")
    }

    /// Runs one part through its existing importer and describes the outcome.
    private func importPart(_ section: AllDataImport.Section, file: URL) async throws -> String {
        switch section {
        case .appBackup:
            try appBackup.restore(zip: Data(contentsOf: file))
            return String(localized: "Restored settings, profile and logs.")

        case .foodDiary:
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size > DiaryImporter.maximumFileSize { throw DiaryImportError.fileTooLarge }
            let preview = try DiaryImporter.parse(Data(contentsOf: file, options: [.mappedIfSafe]))
            foodStore.replaceEntriesFromImport(DiaryImporter.applying(preview, to: foodStore.entries, mode: .merge))
            if preview.includesWater {
                waterStore.replaceEntriesFromImport(DiaryImporter.applyingWater(preview, to: waterStore.entries, mode: .merge))
            }
            return String(localized: "Merged \(preview.entryCount) food and \(preview.waterEntries.count) water entries.")

        case .healthData:
            let preview = try HealthImporter.preview(url: file)
            let result = try await healthDataStore.importHealthData(preview: preview, mode: .merge)
            return String(localized: "\(result.inserted) added, \(result.updated) updated.")

        case .medications:
            let result = try await medicationStore.importArchive(Data(contentsOf: file))
            return String(localized: "\(result.insertedMedications) medications added, \(result.updatedMedications) updated.")

        case .healthRecords:
            switch await recordsStore.importArchive(url: file, mode: .merge, progress: { _ in }) {
            case .success(let summary):
                return String(localized: "\(summary.importedRecords) records added, \(summary.skippedRecords) already here.")
            case .failure(let error):
                throw error
            }

        case .coachChats:
            // Merge, never delete: a chat the user removed here stays removed (docs/coach.md §11).
            guard let repository = await chatStore.repositoryIfOpen(), let files = await chatStore.fileStore() else {
                throw AllDataImport.ImportError.damaged
            }
            let result = try await CoachChatArchiveReader(repository: repository, files: files).import(from: file)
            guard result.ok else {
                throw result.error == "newer_version"
                    ? AllDataImport.ImportError.newerVersion
                    : AllDataImport.ImportError.damaged
            }
            await chatStore.reloadConversations()
            let added = result.counts["conversations_inserted"] ?? 0
            let messages = (result.counts["messages_inserted"] ?? 0) + (result.counts["messages_updated"] ?? 0)
            return String(localized: "\(added) chats added, \(messages) messages merged.")
        }
    }
}
