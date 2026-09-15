import SwiftUI
import UniformTypeIdentifiers

/// Settings › Data Management › Import Health Data. Mirrors `ImportDiaryView`: pick a zip,
/// preview counts, choose Merge or Replace all, then the store rebuilds rollups and syncs.
struct ImportHealthDataView: View {
    @Environment(HealthDataStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var showFilePicker = false
    @State private var preview: HealthImportPreview?
    @State private var result: HealthImportResult?
    @State private var isImporting = false
    @State private var importedRows = 0
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        showFilePicker = true
                    } label: {
                        Label {
                            Text(preview == nil ? "Choose a health data zip" : "Choose a different file")
                        } icon: {
                            Image(systemName: "doc.zipper")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(isImporting)
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.red)
                    }
                } footer: {
                    Text("Accepts ayuvo-health-data archives exported by Ayuvo on iPhone or Android. Merge keeps your existing records and applies newer ones; Replace all clears the mirror first.")
                        .font(.system(.caption2, design: .rounded))
                }
                .listRowBackground(AppColors.appCard)

                if let preview {
                    Section {
                        LabeledContent("From", value: preview.platform == "android" ? "Android" : "iPhone")
                        LabeledContent("App version", value: preview.appVersion)
                        LabeledContent("Exported", value: exportedText(preview.exportedAt))
                        LabeledContent("Records", value: preview.recordCount.formatted())
                        LabeledContent("Data types", value: preview.typeCount.formatted())
                        if let start = preview.rangeStart, let end = preview.rangeEnd {
                            LabeledContent("Range", value: "\(exportedText(start)) – \(exportedText(end))")
                        }
                        if !preview.unknownTypes.isEmpty {
                            LabeledContent("Unknown types", value: preview.unknownTypes.count.formatted())
                        }
                    } header: {
                        Text("Preview")
                    } footer: {
                        if !preview.unknownTypes.isEmpty {
                            Text("Unknown data types are kept under Other Data.")
                                .font(.system(.caption2, design: .rounded))
                        }
                    }
                    .listRowBackground(AppColors.appCard)

                    Section {
                        Button {
                            apply(preview, mode: .merge)
                        } label: {
                            Label {
                                Text("Merge into my data")
                            } icon: {
                                Image(systemName: "arrow.triangle.merge")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(isImporting)

                        Button(role: .destructive) {
                            apply(preview, mode: .replaceAll)
                        } label: {
                            Label {
                                Text("Replace all synced health data")
                            } icon: {
                                Image(systemName: "arrow.triangle.2.circlepath")
                            }
                            .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                        .disabled(isImporting)

                        if isImporting {
                            HStack {
                                ProgressView()
                                Text(importedRows > 0 ? "\(importedRows) records imported…" : "Importing…")
                                    .font(.system(.footnote, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Import Health Data")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isImporting)
                }
            }
            .fileImporter(isPresented: $showFilePicker, allowedContentTypes: [.zip, .archive, .data]) { selection in
                loadFile(selection)
            }
            .alert("Import complete", isPresented: Binding(
                get: { result != nil },
                set: { if !$0 { result = nil; dismiss() } }
            )) {
                Button("Done") { result = nil; dismiss() }
            } message: {
                if let result {
                    Text(resultText(result))
                }
            }
        }
    }

    private func loadFile(_ selection: Result<URL, Error>) {
        do {
            let url = try selection.get()
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            // Copy into our container so the security scope can end before the import runs.
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent("ayuvo-health-import-\(UUID().uuidString).zip")
            if FileManager.default.fileExists(atPath: copy.path) {
                try FileManager.default.removeItem(at: copy)
            }
            try FileManager.default.copyItem(at: url, to: copy)
            preview = try HealthImporter.preview(url: copy)
            errorMessage = nil
        } catch {
            preview = nil
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func apply(_ preview: HealthImportPreview, mode: HealthImportMode) {
        guard !isImporting else { return }
        isImporting = true
        importedRows = 0
        errorMessage = nil
        Task {
            do {
                let outcome = try await store.importHealthData(preview: preview, mode: mode) { rows in
                    Task { @MainActor in importedRows = rows }
                }
                try? FileManager.default.removeItem(at: preview.url)
                isImporting = false
                result = outcome
            } catch {
                isImporting = false
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    private func exportedText(_ iso: String) -> String {
        guard let parsed = ISO8601Fast.parse(iso) else { return iso }
        return Date(timeIntervalSince1970: Double(parsed.ms) / 1000).formatted(date: .abbreviated, time: .omitted)
    }

    private func resultText(_ result: HealthImportResult) -> String {
        var lines = [String(localized: "\(result.inserted) added, \(result.updated) updated, \(result.unchanged) unchanged.")]
        if result.rejectedUnitMismatch > 0 {
            lines.append(String(localized: "\(result.rejectedUnitMismatch) records skipped because their unit didn't match."))
        }
        if result.rejectedInvalid > 0 {
            lines.append(String(localized: "\(result.rejectedInvalid) records could not be read."))
        }
        if !result.checksumMismatches.isEmpty {
            lines.append(String(localized: "Checksums didn't match for: \(result.checksumMismatches.joined(separator: ", ")). The data was imported anyway."))
        }
        return lines.joined(separator: "\n")
    }
}
