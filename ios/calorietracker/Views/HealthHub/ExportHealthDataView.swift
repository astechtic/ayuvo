import SwiftUI

/// Settings › Data Management › Export Health Data. Writes the `ayuvo-health-data` zip to a
/// temp directory, hands it to the share sheet and deletes it when sharing ends.
struct ExportHealthDataView: View {
    @Environment(HealthDataStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var isExporting = false
    @State private var exportedRows = 0
    @State private var errorMessage: String?

    private var exportableSummaries: [HealthTypeSummary] {
        store.typeSummaries.filter { $0.count > 0 && HealthExportFormat.isExportable(typeID: $0.typeID) }
    }

    private var totalRecords: Int { exportableSummaries.reduce(0) { $0 + $1.count } }

    private var rangeText: String? {
        let starts = exportableSummaries.compactMap(\.firstStartMs)
        let ends = exportableSummaries.compactMap(\.lastEndMs)
        guard let start = starts.min(), let end = ends.max() else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return "\(formatter.string(from: Date(timeIntervalSince1970: Double(start) / 1000))) – \(formatter.string(from: Date(timeIntervalSince1970: Double(end) / 1000)))"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Data types", value: exportableSummaries.count.formatted())
                    LabeledContent("Records", value: totalRecords.formatted())
                    if let rangeText {
                        LabeledContent("Range", value: rangeText)
                    }
                } footer: {
                    Text("A zip in the shared ayuvo-health-data format that Ayuvo on iPhone or Android can import. Food-diary nutrition is not included — use Export Food Diary for that.")
                        .font(.system(.caption2, design: .rounded))
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    Button {
                        export()
                    } label: {
                        HStack {
                            Label {
                                Text(isExporting ? "Exporting…" : "Export")
                            } icon: {
                                Image(systemName: "square.and.arrow.up")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            if isExporting {
                                ProgressView()
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(isExporting || totalRecords == 0)
                    if isExporting, exportedRows > 0 {
                        Text("\(exportedRows) records written")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    if totalRecords == 0 {
                        Text("Nothing to export yet.")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.red)
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Export Health Data")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                if store.typeSummaries.isEmpty {
                    await store.refreshSnapshots()
                }
            }
        }
    }

    private func export() {
        guard !isExporting else { return }
        isExporting = true
        errorMessage = nil
        exportedRows = 0
        Task {
            do {
                let summary = try await store.exportHealthData { rows in
                    Task { @MainActor in exportedRows = rows }
                }
                isExporting = false
                let directory = summary.url.deletingLastPathComponent()
                ShareSheetPresenter.present(url: summary.url) {
                    try? FileManager.default.removeItem(at: directory)
                }
                dismiss()
            } catch {
                isExporting = false
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }
}
