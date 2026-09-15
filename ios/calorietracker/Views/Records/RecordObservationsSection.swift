import SwiftUI

extension RecordResultFlag {
    var tint: Color {
        switch self {
        case .criticalLow, .criticalHigh: .red
        case .low, .high, .abnormal: .orange
        case .normal: .green
        case .unknown: .secondary
        }
    }

    var shortTitle: String? {
        switch self {
        case .low: String(localized: "Low")
        case .high: String(localized: "High")
        case .criticalLow: String(localized: "Critical low")
        case .criticalHigh: String(localized: "Critical high")
        case .abnormal: String(localized: "Abnormal")
        case .normal: String(localized: "Normal")
        case .unknown: nil
        }
    }
}

/// "Health data points" (§19, §21, §24): one row per observation with mapping, value, flag, range,
/// confirmed badge and an inline mini trend; tap to edit; "Add value".
struct RecordObservationsSection: View {
    let detail: RecordDetail
    let onSource: (RecordSourceTarget) -> Void
    @Environment(RecordsStore.self) private var store
    @State private var editing: RecordObservation?
    @State private var showAdd = false

    private var catalog: AnalyteCatalog { .shared }

    var body: some View {
        let observations = detail.observations
        if !observations.isEmpty || detail.record.recordType == .labReport || detail.record.recordType == .diagnosticReport {
            RecordsCard {
                RecordsSectionTitle(title: "Health data points", systemImage: "chart.xyaxis.line", trailing: observations.isEmpty ? nil : "\(observations.count)")
                if observations.isEmpty {
                    Text("No values found on this record yet. You can add one yourself.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(observations) { observation in
                    VStack(alignment: .leading, spacing: 6) {
                        Button {
                            editing = observation
                        } label: {
                            RecordObservationRow(observation: observation, catalog: catalog)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("records.observation.\(observation.rawName)")
                        if let trend = detail.miniTrends[observation.id], !observation.excludedFromTrends {
                            RecordMiniTrendRow(trend: trend)
                        }
                    }
                    if observation.id != observations.last?.id { Divider() }
                }
                Button {
                    showAdd = true
                } label: {
                    Label("Add value", systemImage: "plus.circle")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                }
                .accessibilityIdentifier("records.observation.add")
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("records.detail.observations")
            .sheet(item: $editing) { observation in
                RecordObservationEditSheet(observation: observation, record: detail.record, onSource: { target in
                    editing = nil
                    onSource(target)
                })
            }
            .sheet(isPresented: $showAdd) {
                RecordObservationAddSheet(record: detail.record)
            }
        }
    }
}

struct RecordObservationRow: View {
    let observation: RecordObservation
    var catalog: AnalyteCatalog = .shared
    var showsRecord: HealthRecord? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(observation.displayName(catalog: catalog))
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                    if observation.analyteID == nil {
                        Text("Not mapped")
                            .font(.system(.caption2, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.secondary.opacity(0.12), in: Capsule())
                    }
                }
                if observation.analyteID != nil, observation.displayName(catalog: catalog) != observation.rawName {
                    Text(observation.rawName)
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if let ref = observation.refText, !ref.isEmpty {
                    Text("Range \(ref)")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if let record = showsRecord {
                    Text("\(record.title) · \(RecordFormatting.dateText(record))")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 4) {
                    Text(observation.valueWithUnit.isEmpty ? "—" : observation.valueWithUnit)
                        .font(.system(.subheadline, design: .rounded, weight: .bold))
                        .foregroundStyle(observation.flag.isAbnormal ? observation.flag.tint : .primary)
                        .monospacedDigit()
                    if let symbol = observation.flag.symbol {
                        Text(symbol)
                            .font(.system(.subheadline, design: .rounded, weight: .bold))
                            .foregroundStyle(observation.flag.tint)
                    }
                }
                HStack(spacing: 4) {
                    if let title = observation.flag.shortTitle, observation.flag != .normal {
                        Text(title)
                            .font(.system(.caption2, design: .rounded, weight: .semibold))
                            .foregroundStyle(observation.flag.tint)
                    }
                    if observation.isConfirmed {
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption2)
                            .foregroundStyle(.green)
                            .accessibilityLabel(Text("Confirmed"))
                    }
                    if observation.excludedFromTrends {
                        Image(systemName: "chart.line.downtrend.xyaxis")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(Text("Not in trends"))
                    }
                }
            }
            Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct RecordMiniTrendRow: View {
    let trend: RecordMiniTrend

    var body: some View {
        NavigationLink(value: RecordsRoute.trend(trend.analyteID)) {
            HStack(spacing: 6) {
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.caption)
                    .foregroundStyle(AppColors.calorie)
                VStack(alignment: .leading, spacing: 1) {
                    Text(trend.text)
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(.primary)
                    if let change = trend.change {
                        Text(change)
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 4)
                Text("View full trend")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(AppColors.calorie.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("records.miniTrend.\(trend.analyteID)")
    }
}

// MARK: - Edit

/// §24 editor: value, unit, date, analyte mapping, range, exclude from trends, remove, source jump.
struct RecordObservationEditSheet: View {
    let observation: RecordObservation
    let record: HealthRecord
    var onSource: ((RecordSourceTarget) -> Void)?
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var valueText: String
    @State private var unit: String
    @State private var refText: String
    @State private var date: Date
    @State private var analyteID: String?
    @State private var includeInTrends: Bool
    @State private var rememberAlias = true
    @State private var applyToOthers = false
    @State private var othersCount = 0
    @State private var showRemove = false
    @State private var pages: [RecordPage] = []

    init(observation: RecordObservation, record: HealthRecord, onSource: ((RecordSourceTarget) -> Void)? = nil) {
        self.observation = observation
        self.record = record
        self.onSource = onSource
        _valueText = State(initialValue: observation.valueText)
        _unit = State(initialValue: observation.unit ?? "")
        _refText = State(initialValue: observation.refText ?? "")
        _date = State(initialValue: observation.observedDate.flatMap { RecordDates.date(fromDay: $0) } ?? record.displayDate ?? Date())
        _analyteID = State(initialValue: observation.analyteID)
        _includeInTrends = State(initialValue: !observation.excludedFromTrends)
    }

    private var catalog: AnalyteCatalog { .shared }
    private var analyte: AnalyteDefinition? { analyteID.flatMap { catalog.analyte(id: $0) } }
    private var mappingChanged: Bool { analyteID != observation.analyteID }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        RecordAnalytePicker(selection: $analyteID, rawName: observation.rawName)
                    } label: {
                        LabeledContent("Test") {
                            Text(analyte?.displayName ?? String(localized: "Not mapped"))
                                .foregroundStyle(analyte == nil ? .secondary : .primary)
                        }
                    }
                    .accessibilityIdentifier("records.observationEdit.analyte")
                    if mappingChanged, analyteID != nil {
                        Toggle("Remember for “\(observation.rawName)”", isOn: $rememberAlias)
                            .accessibilityIdentifier("records.observationEdit.remember")
                        if rememberAlias, othersCount > 0 {
                            Toggle(othersCount == 1 ? String(localized: "Also map 1 other value with this name") : String(localized: "Also map \(othersCount) other values with this name"), isOn: $applyToOthers)
                        }
                    }
                } header: {
                    Text("Printed as “\(observation.rawName)”")
                        .textCase(nil)
                } footer: {
                    if observation.analyteID == nil {
                        Text("Choose the test so this value appears in trends.")
                    }
                }

                Section("Value") {
                    TextField("Value", text: $valueText)
                        .keyboardType(.decimalPad)
                        .accessibilityIdentifier("records.observationEdit.value")
                    HStack {
                        TextField("Unit", text: $unit)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .accessibilityIdentifier("records.observationEdit.unit")
                        if let analyte, !unitChoices(analyte).isEmpty {
                            Menu {
                                ForEach(unitChoices(analyte), id: \.self) { choice in
                                    Button(choice) { unit = choice }
                                }
                            } label: {
                                Image(systemName: "chevron.up.chevron.down")
                            }
                            .accessibilityLabel(Text("Choose unit"))
                        }
                    }
                    TextField("Reference range, e.g. 13.0 - 17.0", text: $refText)
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("records.observationEdit.range")
                    DatePicker("Date", selection: $date, in: ...Date.now, displayedComponents: .date)
                }

                Section {
                    Toggle("Show in trends", isOn: $includeInTrends)
                        .accessibilityIdentifier("records.observationEdit.includeInTrends")
                } footer: {
                    Text("Turn off for a value that shouldn't be compared with others, like a repeat test or a different method.")
                }

                if let page = observation.sourcePage {
                    Section("Source") {
                        if let evidence = observation.evidence {
                            Text(evidence)
                                .font(.system(.footnote, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                        Button {
                            let box = observation.bbox ?? RecordSourceLocator.box(evidence: observation.evidence, page: page, pages: pages)
                            onSource?(RecordSourceTarget(page: page, box: box, label: observation.evidence ?? observation.rawName))
                        } label: {
                            Label("View on page \(page + 1)", systemImage: "doc.text.magnifyingglass")
                        }
                        .accessibilityIdentifier("records.observationEdit.source")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showRemove = true
                    } label: {
                        Label("Remove value", systemImage: "trash")
                    }
                    .accessibilityIdentifier("records.observationEdit.remove")
                } footer: {
                    Text("Removing hides this value from the record and its trends. The original document isn't changed.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle(observation.displayName(catalog: catalog))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .fontWeight(.semibold)
                        .disabled(valueText.trimmingCharacters(in: .whitespaces).isEmpty)
                        .accessibilityIdentifier("records.observationEdit.save")
                }
            }
            .confirmationDialog("Remove this value?", isPresented: $showRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    Task { await store.removeObservation(observation) }
                    dismiss()
                }
            }
            .task {
                othersCount = await store.unmappedCount(sameNameAs: observation)
                pages = await store.detail(id: record.id)?.pages ?? []
            }
        }
    }

    private func unitChoices(_ analyte: AnalyteDefinition) -> [String] {
        var seen = Set<String>()
        return ([analyte.canonicalUnit].compactMap { $0 } + analyte.units.map(\.unit)).filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private func save() {
        var edit = RecordObservationEdit()
        let trimmedValue = valueText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedValue != observation.valueText { edit.valueText = trimmedValue }
        let trimmedUnit = unit.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedUnit != (observation.unit ?? "") { edit.unit = .some(trimmedUnit.isEmpty ? nil : trimmedUnit) }
        let trimmedRef = refText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedRef != (observation.refText ?? "") { edit.refText = .some(trimmedRef.isEmpty ? nil : trimmedRef) }
        let day = RecordDates.dayString(from: date)
        if day != observation.observedDate { edit.observedDate = day }
        if mappingChanged {
            edit.analyteID = .some(analyteID)
            edit.rememberAlias = analyteID != nil && rememberAlias
            edit.applyAliasToExisting = edit.rememberAlias && applyToOthers
        }
        if includeInTrends == observation.excludedFromTrends { edit.excludedFromTrends = !includeInTrends }
        let observation = observation
        if edit != RecordObservationEdit() {
            Task { await store.editObservation(observation, edit: edit) }
        }
        dismiss()
    }
}

/// Searchable catalog picker with "Not mapped".
struct RecordAnalytePicker: View {
    @Binding var selection: String?
    var rawName: String?
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [AnalyteDefinition] = []

    var body: some View {
        List {
            if query.isEmpty {
                Button {
                    selection = nil
                    dismiss()
                } label: {
                    row(title: String(localized: "Not mapped"), subtitle: String(localized: "Keep the printed name only"), selected: selection == nil)
                }
            }
            ForEach(results) { analyte in
                Button {
                    selection = analyte.id
                    dismiss()
                } label: {
                    row(title: analyte.displayName, subtitle: subtitle(analyte), selected: selection == analyte.id)
                }
                .accessibilityIdentifier("records.analytePicker.\(analyte.id)")
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search tests"))
        .navigationTitle("Choose test")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: query) {
            let text = query.isEmpty ? (rawName ?? "") : query
            let catalog = AnalyteCatalog.shared
            let found = await Task.detached(priority: .userInitiated) { catalog.search(text) }.value
            results = found.isEmpty && query.isEmpty ? catalog.search("") : found
        }
    }

    private func subtitle(_ analyte: AnalyteDefinition) -> String {
        let aliases = analyte.aliases.prefix(3).joined(separator: ", ")
        return [aliases, analyte.canonicalUnit ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func row(title: String, subtitle: String, selected: Bool) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                if !subtitle.isEmpty {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(AppColors.calorie) }
        }
        .contentShape(Rectangle())
    }
}

// MARK: - Add

struct RecordObservationAddSheet: View {
    let record: HealthRecord
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var analyteID: String?
    @State private var name = ""
    @State private var valueText = ""
    @State private var unit = ""
    @State private var refText = ""
    @State private var date: Date

    init(record: HealthRecord) {
        self.record = record
        _date = State(initialValue: record.displayDate ?? Date())
    }

    private var analyte: AnalyteDefinition? { analyteID.flatMap { AnalyteCatalog.shared.analyte(id: $0) } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        RecordAnalytePicker(selection: $analyteID)
                    } label: {
                        LabeledContent("Test") {
                            Text(analyte?.displayName ?? String(localized: "Choose"))
                                .foregroundStyle(analyte == nil ? .secondary : .primary)
                        }
                    }
                    .accessibilityIdentifier("records.observationAdd.analyte")
                    if analyte == nil {
                        TextField("Test name", text: $name)
                            .accessibilityIdentifier("records.observationAdd.name")
                    }
                }
                Section("Value") {
                    TextField("Value", text: $valueText)
                        .keyboardType(.decimalPad)
                        .accessibilityIdentifier("records.observationAdd.value")
                    TextField("Unit", text: $unit)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("records.observationAdd.unit")
                    TextField("Reference range (optional)", text: $refText)
                        .textInputAutocapitalization(.never)
                    DatePicker("Date", selection: $date, in: ...Date.now, displayedComponents: .date)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Add value")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: analyteID) { _, _ in
                if unit.isEmpty, let canonical = analyte?.canonicalUnit { unit = canonical }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let draft = RecordObservationDraft(
                            analyteID: analyteID, name: analyte?.displayName ?? name, valueText: valueText,
                            unit: unit.isEmpty ? nil : unit, observedDate: RecordDates.dayString(from: date), refText: refText.isEmpty ? nil : refText
                        )
                        let recordID = record.id
                        Task { await store.addObservation(recordID: recordID, draft: draft) }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(valueText.trimmingCharacters(in: .whitespaces).isEmpty || (analyte == nil && name.trimmingCharacters(in: .whitespaces).isEmpty))
                    .accessibilityIdentifier("records.observationAdd.save")
                }
            }
        }
    }
}
