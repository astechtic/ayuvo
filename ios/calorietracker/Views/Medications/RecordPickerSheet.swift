import SwiftUI

/// Single-select picker over the user's prescriptions and medication lists
/// (`RecordsFilterChip.prescriptions`), used to link a medicine to a record and by
/// "Add from prescription".
struct RecordPickerSheet: View {
    var onPick: (HealthRecord) -> Void
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""
    @State private var records: [HealthRecord] = []
    @State private var isLoading = true
    @State private var searchTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView()
                } else if records.isEmpty {
                    ContentUnavailableView {
                        Label("No prescriptions yet", systemImage: "doc.text")
                    } description: {
                        Text(searchText.isEmpty
                             ? String(localized: "Prescriptions and medication lists saved in Records show up here.")
                             : String(localized: "No records match “\(searchText)”."))
                    }
                } else {
                    List(records) { record in
                        Button {
                            onPick(record)
                        } label: {
                            RecordRow(record: record, compact: true)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(AppColors.appCard)
                        .accessibilityIdentifier("medications.recordPicker.\(record.id)")
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .background(AppColors.appBackground)
            .navigationTitle("Choose a prescription")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search records"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task { await load() }
            .onChange(of: searchText) { _, _ in
                searchTask?.cancel()
                searchTask = Task {
                    try? await Task.sleep(nanoseconds: 150_000_000)
                    guard !Task.isCancelled else { return }
                    await load()
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func load() async {
        guard let repository = await recordsStore.openIfNeeded() else {
            records = []
            isLoading = false
            return
        }
        let query = RecordsFilterChip.prescriptions.query(text: searchText.trimmingCharacters(in: .whitespacesAndNewlines))
        records = (try? await repository.page(query: query, after: nil, limit: 100)) ?? []
        isLoading = false
    }
}
