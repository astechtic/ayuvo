import SwiftUI

/// "Add from prescription" (docs §13): the medicines the Records pipeline found in a record become
/// editable drafts; nothing is saved until the user confirms.
struct MedicationImportFromRecordView: View {
    let recordID: String
    @Environment(MedicationStore.self) private var store
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(\.dismiss) private var dismiss
    @State private var record: HealthRecord?
    @State private var drafts: [MedicationDraft] = []
    @State private var selected: Set<Int> = []
    @State private var editingIndex: Int?
    @State private var isLoading = true
    @State private var isSaving = false
    @State private var errorText: String?
    @State private var didCreate = 0

    var body: some View {
        Group {
            if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if didCreate > 0 {
                doneState
            } else if record == nil {
                ContentUnavailableView("Record not found", systemImage: "doc.text")
            } else if let record, record.isProcessing, drafts.isEmpty {
                ContentUnavailableView {
                    Label("Still reading this record", systemImage: "doc.text.magnifyingglass")
                } description: {
                    Text("Ayuvo is still finding details in this record. Try again in a moment.")
                } actions: {
                    Button("Try again") { Task { await load() } }
                        .buttonStyle(.bordered)
                        .tint(AppColors.calorie)
                }
            } else if drafts.isEmpty {
                ContentUnavailableView {
                    Label("No medicines detected", systemImage: "pills")
                } description: {
                    Text("Ayuvo didn't find medicines in this record. You can add one manually and link it to the record.")
                } actions: {
                    manualButton
                }
            } else {
                candidates
            }
        }
        .background(AppColors.appBackground)
        .navigationTitle("Add from prescription")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .sheet(item: Binding(get: { editingIndex.map(IndexBox.init) }, set: { editingIndex = $0?.index })) { box in
            MedicationFormView(mode: .candidate(drafts[box.index], onSave: { updated in
                if drafts.indices.contains(box.index) { drafts[box.index] = updated }
                selected.insert(box.index)
            }))
        }
        .sheet(isPresented: $showManual) {
            MedicationFormView(mode: .create(recordID: recordID))
        }
        .accessibilityIdentifier("medications.import")
    }

    @State private var showManual = false

    private var manualButton: some View {
        Button {
            showManual = true
        } label: {
            Label("Add manually", systemImage: "plus")
        }
        .buttonStyle(.borderedProminent)
        .tint(AppColors.calorie)
        .accessibilityIdentifier("medications.import.manual")
    }

    private var candidates: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let record {
                    RecordsCard {
                        RecordsSectionTitle(title: "Prescription", systemImage: "doc.text")
                        RecordRow(record: record, compact: true)
                    }
                }
                RecordsCard(padding: 12) {
                    RecordsSectionTitle(title: "Medicines found", systemImage: "pills.fill", trailing: "\(drafts.count)")
                    ForEach(Array(drafts.enumerated()), id: \.offset) { index, draft in
                        candidateRow(index: index, draft: draft)
                        if index < drafts.count - 1 { Divider() }
                    }
                }
                Text("Check each medicine against the prescription before saving. Times and doses are suggestions you can change.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                if let errorText {
                    Label(errorText, systemImage: "exclamationmark.circle")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.orange)
                }
                Button {
                    Task { await create() }
                } label: {
                    Label(String(localized: "Add \(MedicationFormatting.medicationCount(selected.count))"), systemImage: "checkmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.large)
                .disabled(selected.isEmpty || isSaving)
                .accessibilityIdentifier("medications.import.confirm")
                manualButton
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
    }

    private func candidateRow(index: Int, draft: MedicationDraft) -> some View {
        let isSelected = selected.contains(index)
        let errors = draft.validationErrors
        return HStack(alignment: .top, spacing: 12) {
            Button {
                if isSelected { selected.remove(index) } else { selected.insert(index) }
            } label: {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? AppColors.calorie : Color.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(isSelected ? "Selected" : "Not selected"))
            .accessibilityIdentifier("medications.import.select.\(index)")
            VStack(alignment: .leading, spacing: 3) {
                Text(draft.name.isEmpty ? String(localized: "Unnamed medicine") : draft.name + (draft.strength.isEmpty ? "" : " \(draft.strength)"))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text(summary(draft))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                if !errors.isEmpty {
                    Label(String(localized: "Needs details"), systemImage: "exclamationmark.circle")
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(.orange)
                } else if let confidence = draft.hintConfidence, confidence < 0.6 {
                    Label(String(localized: "Schedule guessed"), systemImage: "questionmark.circle")
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 4)
            Button {
                editingIndex = index
            } label: {
                Text("Edit")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
            }
            .buttonStyle(.bordered)
            .tint(AppColors.calorie)
            .controlSize(.small)
            .accessibilityIdentifier("medications.import.edit.\(index)")
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
    }

    private var doneState: some View {
        ContentUnavailableView {
            Label(String(localized: "\(MedicationFormatting.medicationCount(didCreate)) added"), systemImage: "checkmark.circle")
        } description: {
            Text("Each medicine is linked to this prescription. Reminders follow your Notifications settings.")
        } actions: {
            Button {
                store.requestTab()
                dismiss()
            } label: {
                Label("Open Medications", systemImage: "pills")
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .accessibilityIdentifier("medications.import.open")
        }
    }

    private func summary(_ draft: MedicationDraft) -> String {
        var parts: [String] = []
        if let quantity = draft.doseQuantity {
            parts.append(MedicationFormatting.doseText(quantity: quantity, unit: draft.doseUnit))
        }
        if draft.isPRN {
            parts.append(String(localized: "As needed"))
        } else {
            switch draft.frequency {
            case .daily: parts.append(MedicationFormatting.dailyLabel(count: draft.normalizedTimes.count) + " · " + draft.normalizedTimes.map { MedicationFormatting.timeText(hhmm: $0) }.joined(separator: ", "))
            case .weekly: parts.append(draft.normalizedDays.map { MedicationFormatting.weekdayName(iso: $0) }.joined(separator: ", "))
            case .interval: parts.append(String(localized: "Every \(draft.intervalHours) hours"))
            }
        }
        if let end = draft.endDate { parts.append(String(localized: "until \(MedicationFormatting.dateText(iso: end))")) }
        if draft.foodRelation != .anytime { parts.append(draft.foodRelation.title.lowercased()) }
        return parts.joined(separator: " · ")
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        guard let detail = await recordsStore.detail(id: recordID) else {
            record = nil
            return
        }
        record = detail.record
        drafts = store.candidates(fromRecordID: recordID, fields: detail.fields)
        selected = Set(drafts.indices.filter { drafts[$0].isValid })
    }

    private func create() async {
        isSaving = true
        defer { isSaving = false }
        let chosen = selected.sorted().compactMap { drafts.indices.contains($0) ? drafts[$0] : nil }
        do {
            let created = try await store.createFromCandidates(chosen)
            didCreate = created.count
            store.showBanner(String(localized: "\(MedicationFormatting.medicationCount(created.count)) added"))
        } catch MedicationStoreError.validation {
            errorText = String(localized: "One of the selected medicines needs more details. Tap Edit to complete it.")
        } catch {
            errorText = MedicationFormatting.actionErrorText("unknown")
        }
    }
}

private struct IndexBox: Identifiable {
    let index: Int
    var id: Int { index }
}
