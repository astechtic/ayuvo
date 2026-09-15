import SwiftUI

/// Advanced filters (plan §2): date range, doctor, hospital, category, report type, abnormal only,
/// tags, AI processed, user confirmed. Works on a copy and applies on "Apply".
struct RecordsFilterSheet: View {
    @Binding var filters: RecordQuery
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var draft = RecordQuery()
    @State private var useFrom = false
    @State private var useTo = false
    @State private var from = Date()
    @State private var to = Date()
    @State private var doctors: [String] = []
    @State private var facilities: [String] = []
    @State private var tags: [String] = []

    static func activeCount(_ query: RecordQuery) -> Int {
        var count = 0
        if query.dateFrom != nil || query.dateTo != nil { count += 1 }
        if !(query.doctor ?? "").isEmpty { count += 1 }
        if !(query.facility ?? "").isEmpty { count += 1 }
        if !query.categories.isEmpty { count += 1 }
        if !query.recordTypes.isEmpty { count += 1 }
        if query.flags.contains(.abnormal) { count += 1 }
        if !query.tags.isEmpty { count += 1 }
        if query.aiProcessedOnly { count += 1 }
        if query.userConfirmedOnly { count += 1 }
        return count
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Date") {
                    Toggle("From", isOn: $useFrom.animation())
                    if useFrom { DatePicker("From", selection: $from, displayedComponents: .date).labelsHidden() }
                    Toggle("To", isOn: $useTo.animation())
                    if useTo { DatePicker("To", selection: $to, displayedComponents: .date).labelsHidden() }
                }
                Section("Doctor & hospital") {
                    Picker("Doctor", selection: Binding(get: { draft.doctor ?? "" }, set: { draft.doctor = $0.isEmpty ? nil : $0 })) {
                        Text("Any").tag("")
                        ForEach(doctors, id: \.self) { Text($0).tag($0) }
                    }
                    Picker("Hospital or lab", selection: Binding(get: { draft.facility ?? "" }, set: { draft.facility = $0.isEmpty ? nil : $0 })) {
                        Text("Any").tag("")
                        ForEach(facilities, id: \.self) { Text($0).tag($0) }
                    }
                }
                Section("Category") {
                    ForEach(RecordCategory.allCases) { category in
                        toggleRow(category.title, isOn: draft.categories.contains(category)) {
                            if draft.categories.contains(category) { draft.categories.remove(category) } else { draft.categories.insert(category) }
                        }
                    }
                }
                Section("Report type") {
                    ForEach(RecordType.allCases) { type in
                        toggleRow(type.title, isOn: draft.recordTypes.contains(type)) {
                            if draft.recordTypes.contains(type) { draft.recordTypes.remove(type) } else { draft.recordTypes.insert(type) }
                        }
                    }
                }
                Section {
                    Toggle("Abnormal results only", isOn: Binding(
                        get: { draft.flags.contains(.abnormal) },
                        set: { if $0 { draft.flags.insert(.abnormal) } else { draft.flags.remove(.abnormal) } }
                    ))
                    .accessibilityIdentifier("records.filters.abnormal")
                    Toggle("Processed by AI", isOn: $draft.aiProcessedOnly)
                    Toggle("Confirmed by you", isOn: $draft.userConfirmedOnly)
                }
                if !tags.isEmpty {
                    Section("Tags") {
                        ForEach(tags, id: \.self) { tag in
                            toggleRow(tag, isOn: draft.tags.contains(tag)) {
                                if draft.tags.contains(tag) { draft.tags.remove(tag) } else { draft.tags.insert(tag) }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Reset") {
                        draft = RecordQuery()
                        useFrom = false
                        useTo = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        var result = draft
                        result.dateFrom = useFrom ? RecordDates.dayString(from: from) : nil
                        result.dateTo = useTo ? RecordDates.dayString(from: to) : nil
                        filters = result
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("records.filters.apply")
                }
            }
        }
        .task {
            draft = filters
            if let value = filters.dateFrom, let date = RecordDates.date(fromDay: value) { useFrom = true; from = date }
            if let value = filters.dateTo, let date = RecordDates.date(fromDay: value) { useTo = true; to = date }
            let suggestions = await store.filterSuggestions()
            doctors = suggestions.doctors
            facilities = suggestions.facilities
            tags = suggestions.tags
        }
    }

    private func toggleRow(_ title: String, isOn: Bool, toggle: @escaping () -> Void) -> some View {
        Button(action: toggle) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if isOn { Image(systemName: "checkmark").foregroundStyle(AppColors.calorie) }
            }
        }
    }
}
