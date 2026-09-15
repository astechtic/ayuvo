import SwiftUI
import UIKit

/// Export the food diary as JSON / Markdown / CSV over a chosen date range,
/// then hand the file to the system share sheet.
struct ExportDiaryView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(\.dismiss) private var dismiss

    @State private var range: DiaryExportRange = .thisWeek
    @State private var format: DiaryExportFormat = .json
    @State private var customStart: Date = Calendar.current.date(byAdding: .day, value: -7, to: .now) ?? .now
    @State private var customEnd: Date = .now

    @State private var emptyNotice = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Range") {
                    Picker("Range", selection: $range) {
                        ForEach(DiaryExportRange.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .tint(AppColors.calorie)

                    if range == .custom {
                        DatePicker("From", selection: $customStart, displayedComponents: .date)
                            .tint(AppColors.calorie)
                        DatePicker("To", selection: $customEnd, displayedComponents: .date)
                            .tint(AppColors.calorie)
                    }
                }

                Section("Format") {
                    Picker("Format", selection: $format) {
                        ForEach(DiaryExportFormat.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                    Button {
                        exportNow()
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                            .font(.system(.body, design: .rounded, weight: .semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                } footer: {
                    Text("Exports your logged meals, nutrition totals, and water intake as a file you can save or send to another app.")
                }
            }
            .navigationTitle("Export Food Diary")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .alert("Nothing to export", isPresented: $emptyNotice) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("There are no food or water entries in the selected range.")
            }
        }
    }

    private func exportNow() {
        let (start, end) = DiaryExporter.resolve(range, customStart: customStart, customEnd: customEnd, foodStore: foodStore, waterEntries: waterStore.entries)
        guard let (name, data) = DiaryExporter.build(
            from: start, to: end, format: format, foodStore: foodStore, profile: profileStore.profile, waterEntries: waterStore.entries
        ) else {
            emptyNotice = true
            return
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
            ShareSheetPresenter.present(url: url)
        } catch {
            emptyNotice = true
        }
    }
}
