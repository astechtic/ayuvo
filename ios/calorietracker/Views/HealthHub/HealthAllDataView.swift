import SwiftUI

/// "Show All Data": the five newest records up front, five more per "Load more"
/// (keyset paged, never OFFSET), with a record sheet.
struct HealthAllDataView: View {
    let typeID: String
    @Environment(HealthDataStore.self) private var store
    @State private var rows: [HealthSampleRow] = []
    @State private var isLoading = false
    @State private var reachedEnd = false
    @State private var selectedRow: HealthSampleRow?
    @State private var sourceNames: [String: String] = [:]

    private var type: HealthMetricType { store.metricType(for: typeID) }
    private let pageSize = 5

    var body: some View {
        List {
            if rows.isEmpty, !isLoading {
                ContentUnavailableView {
                    Label("No records", systemImage: type.category.systemImage)
                } description: {
                    Text("Nothing has been mirrored for this data type yet.")
                }
                .listRowBackground(Color.clear)
            }
            ForEach(rows) { row in
                Button {
                    selectedRow = row
                } label: {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(valueText(row))
                                .font(.system(.body, design: .rounded, weight: .medium))
                                .foregroundStyle(.primary)
                            Text(dateText(row))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(sourceName(row.sourceID))
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
            if isLoading {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(Color.clear)
            } else if !reachedEnd, !rows.isEmpty {
                Button {
                    Task { await loadMore() }
                } label: {
                    HStack {
                        Spacer()
                        Label("Load more", systemImage: "arrow.down.circle")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(AppColors.calorie)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
                .listRowBackground(AppColors.appCard)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle(type.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if rows.isEmpty {
                await loadSources()
                await loadMore()
            }
        }
        .sheet(item: $selectedRow) { row in
            HealthRecordDetailSheet(row: row, type: type, sourceName: sourceName(row.sourceID))
        }
    }

    private func loadSources() async {
        let usage = await store.sources(typeID: typeID)
        sourceNames = Dictionary(uniqueKeysWithValues: usage.map { ($0.source.id, $0.source.name) })
    }

    private func loadMore() async {
        guard !isLoading, !reachedEnd else { return }
        isLoading = true
        let key = rows.last.map { (endMs: $0.endMs, id: $0.id) }
        let page = await store.samplesPage(typeID: typeID, before: key, limit: pageSize)
        rows.append(contentsOf: page)
        reachedEnd = page.count < pageSize
        isLoading = false
    }

    private func sourceName(_ id: String) -> String {
        if id == Bundle.main.bundleIdentifier { return "Ayuvo" }
        return sourceNames[id] ?? id
    }

    func valueText(_ row: HealthSampleRow) -> String {
        if type.isBloodPressure {
            return HealthUnitFormatting.bloodPressureText(systolic: row.value, diastolic: row.value2) + " mmHg"
        }
        if type.isSleep {
            return "\(row.valueText ?? "") · \(HealthUnitFormatting.durationText(seconds: row.durationSeconds))"
        }
        if type.id == "workout" {
            return "\(row.title ?? "Workout") · \(HealthUnitFormatting.durationText(seconds: row.durationSeconds))"
        }
        if type.kind == .category {
            return row.valueText ?? row.title ?? String(localized: "Logged")
        }
        if let value = row.value {
            if row.count > 1, let low = row.value2, let high = row.value3 {
                return "\(HealthUnitFormatting.display(value, type: type).text) (\(HealthUnitFormatting.display(low, type: type).value)–\(HealthUnitFormatting.display(high, type: type).value))"
            }
            return HealthUnitFormatting.display(value, type: type).text
        }
        return row.valueText ?? "—"
    }

    private func dateText(_ row: HealthSampleRow) -> String {
        if row.endMs - row.startMs < 60_000 {
            return row.startDate.formatted(date: .abbreviated, time: .shortened)
        }
        let sameDay = store.calendar.isDate(row.startDate, inSameDayAs: row.endDate)
        let end = sameDay ? row.endDate.formatted(date: .omitted, time: .shortened) : row.endDate.formatted(date: .abbreviated, time: .shortened)
        return "\(row.startDate.formatted(date: .abbreviated, time: .shortened)) – \(end)"
    }
}

struct HealthRecordDetailSheet: View {
    let row: HealthSampleRow
    let type: HealthMetricType
    let sourceName: String
    @Environment(\.dismiss) private var dismiss

    private var prettyExtra: String? {
        guard let extraJSON = row.extraJSON, let data = extraJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: pretty, encoding: .utf8)
        else { return nil }
        return text
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Value", value: valueText)
                    if let text = row.valueText, !(type.kind == .category) {
                        LabeledContent("Label", value: text)
                    }
                    if let title = row.title {
                        LabeledContent("Title", value: title)
                    }
                    LabeledContent("Start", value: row.startDate.formatted(date: .abbreviated, time: .shortened))
                    LabeledContent("End", value: row.endDate.formatted(date: .abbreviated, time: .shortened))
                    if row.count > 1 {
                        LabeledContent("Samples", value: row.count.formatted())
                    }
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    LabeledContent("Source", value: sourceName)
                    if let device = row.device {
                        LabeledContent("Device", value: device)
                    }
                    LabeledContent("Method", value: row.recordingMethod == 3 ? String(localized: "Manual entry") : String(localized: "Automatic"))
                    if row.origin == HealthRowOrigin.fileImport.rawValue {
                        LabeledContent("Origin", value: String(localized: "Imported file"))
                    }
                    LabeledContent("Record ID") {
                        Text(row.id)
                            .font(.system(.caption2, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                .listRowBackground(AppColors.appCard)

                if let prettyExtra {
                    Section("Metadata") {
                        Text(prettyExtra)
                            .font(.system(.caption2, design: .monospaced))
                            .textSelection(.enabled)
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle(type.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var valueText: String {
        if type.isBloodPressure {
            return HealthUnitFormatting.bloodPressureText(systolic: row.value, diastolic: row.value2) + " mmHg"
        }
        if type.unit == "s" {
            return HealthUnitFormatting.durationText(seconds: row.value ?? row.durationSeconds)
        }
        if type.kind == .category {
            return row.valueText ?? row.categoryValue.map(String.init) ?? "—"
        }
        return HealthUnitFormatting.text(row.value, type: type)
    }
}
