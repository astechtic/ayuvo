import PhotosUI
import SwiftUI

/// Frequency presets of the Add / Edit form (spec §5); each maps onto the contract's
/// `frequency_kind` + `times` / `days` / `interval_hours` (docs §3).
enum MedicationFrequencyPreset: String, CaseIterable, Identifiable {
    case onceDaily, twiceDaily, threeDaily, fourDaily, specificTimes, everyHours, specificDays, asNeeded

    var id: String { rawValue }

    var title: String {
        switch self {
        case .onceDaily: String(localized: "Once daily")
        case .twiceDaily: String(localized: "Twice daily")
        case .threeDaily: String(localized: "Three times daily")
        case .fourDaily: String(localized: "Four times daily")
        case .specificTimes: String(localized: "Specific times")
        case .everyHours: String(localized: "Every X hours")
        case .specificDays: String(localized: "Specific days")
        case .asNeeded: String(localized: "As needed")
        }
    }

    static func current(for draft: MedicationDraft) -> MedicationFrequencyPreset {
        if draft.isPRN { return .asNeeded }
        switch draft.frequency {
        case .interval: return .everyHours
        case .weekly: return .specificDays
        case .daily:
            switch draft.times {
            case MR.slots1: return .onceDaily
            case MR.slots2: return .twiceDaily
            case MR.slots3: return .threeDaily
            case MR.slots4: return .fourDaily
            default: return .specificTimes
            }
        }
    }

    func apply(to draft: inout MedicationDraft) {
        draft.isPRN = false
        switch self {
        case .onceDaily: draft.frequency = .daily; draft.times = MR.slots1
        case .twiceDaily: draft.frequency = .daily; draft.times = MR.slots2
        case .threeDaily: draft.frequency = .daily; draft.times = MR.slots3
        case .fourDaily: draft.frequency = .daily; draft.times = MR.slots4
        case .specificTimes:
            draft.frequency = .daily
            if draft.times.isEmpty { draft.times = MR.slots1 }
        case .everyHours:
            draft.frequency = .interval
            if !ScheduleFrequency.allowedIntervalHours.contains(draft.intervalHours) { draft.intervalHours = 8 }
            if MR.parseHHMM(draft.anchorTime) == nil { draft.anchorTime = MR.intervalAnchor }
        case .specificDays:
            draft.frequency = .weekly
            if draft.times.isEmpty { draft.times = MR.slots1 }
            if draft.days.isEmpty { draft.days = MR.weeklyDefaultDays }
        case .asNeeded:
            draft.isPRN = true
        }
    }
}

/// Add / Edit medication form (docs §15). `create` and `edit` save through the store; `candidate`
/// hands the edited draft back to the prescription-import screen without saving.
struct MedicationFormView: View {
    enum Mode {
        case create(recordID: String? = nil)
        case edit(MedicationDetail)
        case candidate(MedicationDraft, onSave: (MedicationDraft) -> Void)
    }

    let mode: Mode
    @Environment(MedicationStore.self) private var store
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(\.dismiss) private var dismiss

    @State private var draft: MedicationDraft
    @State private var preset: MedicationFrequencyPreset
    @State private var quantityText: String
    @State private var hasEndDate: Bool
    @State private var errors: [DraftError] = []
    @State private var saveError: String?
    @State private var isSaving = false
    @State private var photoItem: PhotosPickerItem?
    @State private var photoPreview: UIImage?
    @State private var showRecordPicker = false
    @State private var relatedRecord: HealthRecord?
    @State private var showDiscardConfirmation = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case name, strength, genericName, brandName, quantity, instructions
    }

    private let existingMedicationID: String?
    private let existingSchedule: MedicationSchedule?

    init(mode: Mode) {
        self.mode = mode
        let today = MedicationFormatting.isoDay(Date())
        var initial: MedicationDraft
        switch mode {
        case .create(let recordID):
            initial = MedicationDraft(startDate: today)
            initial.relatedRecordID = recordID
            existingMedicationID = nil
            existingSchedule = nil
        case .edit(let detail):
            initial = MedicationDraft(from: detail.medication, schedule: detail.schedule)
            existingMedicationID = detail.medication.id
            existingSchedule = detail.schedule
        case .candidate(let candidate, _):
            initial = candidate
            existingMedicationID = nil
            existingSchedule = nil
        }
        _draft = State(initialValue: initial)
        _preset = State(initialValue: MedicationFrequencyPreset.current(for: initial))
        _quantityText = State(initialValue: initial.doseQuantity.map(MedicationFormatting.quantityText) ?? "")
        _hasEndDate = State(initialValue: initial.endDate != nil)
    }

    private var isEditing: Bool { existingMedicationID != nil }

    private var title: String {
        switch mode {
        case .create: String(localized: "Add medication")
        case .edit: String(localized: "Edit medication")
        case .candidate: String(localized: "Review medication")
        }
    }

    private var startDateBinding: Binding<Date> {
        Binding(
            get: { MedicationFormatting.dateFromISO(draft.startDate) ?? Date() },
            set: { draft.startDate = MedicationFormatting.isoDay($0) }
        )
    }

    private var endDateBinding: Binding<Date> {
        Binding(
            get: { draft.endDate.flatMap { MedicationFormatting.dateFromISO($0) } ?? (MedicationFormatting.dateFromISO(draft.startDate) ?? Date()) },
            set: { draft.endDate = MedicationFormatting.isoDay($0) }
        )
    }

    private var anchorBinding: Binding<Date> {
        Binding(
            get: { MedicationFormatting.date(hhmm: draft.anchorTime) ?? Date() },
            set: { draft.anchorTime = MedicationFormatting.hhmm(from: $0) }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                medicineSection
                doseSection
                scheduleSection
                durationSection
                detailsSection
                photoSection
                relatedRecordSection
                if !draft.isPRN { remindersSection }
                if let saveError {
                    Section {
                        Label(saveError, systemImage: "exclamationmark.circle")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.orange)
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("medications.form.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isEditing ? "Save" : (isCandidate ? "Done" : "Add")) { Task { await save() } }
                        .disabled(isSaving || draft.name.trimmingCharacters(in: .whitespaces).isEmpty)
                        .accessibilityIdentifier("medications.form.save")
                }
            }
            .task { await loadRelatedRecord() }
            .onChange(of: draft.relatedRecordID) { _, _ in Task { await loadRelatedRecord() } }
            .onChange(of: photoItem) { _, item in Task { await loadPhoto(item) } }
            .sheet(isPresented: $showRecordPicker) {
                RecordPickerSheet { record in
                    draft.relatedRecordID = record.id
                    showRecordPicker = false
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(isSaving)
        .accessibilityIdentifier("medications.form")
    }

    private var isCandidate: Bool {
        if case .candidate = mode { return true }
        return false
    }

    // MARK: Sections

    private var medicineSection: some View {
        Section {
            TextField(String(localized: "Name"), text: $draft.name)
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .name)
                .accessibilityIdentifier("medications.form.name")
            TextField(String(localized: "Strength (e.g. 500 mg)"), text: $draft.strength)
                .focused($focusedField, equals: .strength)
                .accessibilityIdentifier("medications.form.strength")
            Picker(selection: $draft.form) {
                ForEach(MedicationForm.allCases) { form in
                    Label(form.title, systemImage: form.systemImage).tag(form)
                }
            } label: {
                Label("Form", systemImage: "pills")
            }
            .dismissesKeyboardOnTap($focusedField)
            .accessibilityIdentifier("medications.form.form")
            .onChange(of: draft.form) { _, form in
                draft.doseUnit = form.defaultUnit
            }
            TextField(String(localized: "Generic name (optional)"), text: $draft.genericName)
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .genericName)
            TextField(String(localized: "Brand name (optional)"), text: $draft.brandName)
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .brandName)
        } header: {
            Text("Medicine")
        } footer: {
            errorFooter(for: ["name", "strength", "form"])
        }
        .listRowBackground(AppColors.appCard)
    }

    private var doseSection: some View {
        Section {
            HStack {
                Label("Dose", systemImage: "number")
                Spacer()
                TextField("1", text: $quantityText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 90)
                    .focused($focusedField, equals: .quantity)
                    .onChange(of: quantityText) { _, text in
                        draft.doseQuantity = Self.parseQuantity(text)
                    }
                    .accessibilityLabel(Text("Dose quantity"))
                    .accessibilityIdentifier("medications.form.quantity")
                Picker("", selection: $draft.doseUnit) {
                    ForEach(DoseUnit.allCases) { unit in
                        Text(unit.title).tag(unit)
                    }
                }
                .labelsHidden()
                .dismissesKeyboardOnTap($focusedField)
                .accessibilityLabel(Text("Dose unit"))
            }
            Picker(selection: $draft.foodRelation) {
                ForEach(FoodRelation.allCases) { relation in
                    Text(relation.title).tag(relation)
                }
            } label: {
                Label("Food", systemImage: "fork.knife")
            }
            .dismissesKeyboardOnTap($focusedField)
        } header: {
            Text("Dose")
        } footer: {
            errorFooter(for: ["dose_quantity", "dose_unit", "food_relation"])
        }
        .listRowBackground(AppColors.appCard)
    }

    private var scheduleSection: some View {
        Section {
            Picker(selection: $preset) {
                ForEach(MedicationFrequencyPreset.allCases) { preset in
                    Text(preset.title).tag(preset)
                }
            } label: {
                Label("Frequency", systemImage: "repeat")
            }
            .dismissesKeyboardOnTap($focusedField)
            .onChange(of: preset) { _, preset in
                preset.apply(to: &draft)
            }
            .accessibilityIdentifier("medications.form.frequency")

            if !draft.isPRN {
                switch draft.frequency {
                case .daily:
                    MedicationTimesEditor(times: $draft.times)
                        .onChange(of: draft.times) { _, _ in
                            if preset != .specificTimes, MedicationFrequencyPreset.current(for: draft) != preset {
                                preset = .specificTimes
                            }
                        }
                case .weekly:
                    MedicationWeekdayPicker(days: $draft.days)
                        .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                    MedicationTimesEditor(times: $draft.times)
                case .interval:
                    Picker(selection: $draft.intervalHours) {
                        ForEach(ScheduleFrequency.allowedIntervalHours, id: \.self) { hours in
                            Text(String(localized: "Every \(hours) hours")).tag(hours)
                        }
                    } label: {
                        Label("Interval", systemImage: "timer")
                    }
                    HStack {
                        Label("First dose at", systemImage: "clock")
                        Spacer()
                        DatePicker("", selection: anchorBinding, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                            .accessibilityLabel(Text("First dose time"))
                    }
                    if let slots = intervalSlotsText {
                        Text(slots)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            Text("Schedule")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if draft.isPRN {
                    Text("As-needed medicines have no reminders. Log each dose from the Medications screen.")
                } else if isEditing, draft.scheduleDiffers(from: existingSchedule) {
                    Text("Past doses stay in history; the new times start now.")
                }
                errorFooter(for: ["frequency_kind", "times", "days", "interval_hours", "anchor_time"])
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    private var intervalSlotsText: String? {
        guard draft.frequency == .interval, MR.parseHHMM(draft.anchorTime) != nil else { return nil }
        let schedule = MedicationSchedule(medicationID: "", frequency: .interval, intervalHours: draft.intervalHours,
                                          anchorTime: draft.anchorTime, activeFromMs: 0, createdMs: 0, updatedMs: 0)
        let slots = MR.scheduleSlots(schedule.rj).map { MedicationFormatting.timeText(hhmm: $0) }
        guard !slots.isEmpty else { return nil }
        return String(localized: "Doses at \(slots.joined(separator: ", "))")
    }

    private var durationSection: some View {
        Section {
            DatePicker(selection: startDateBinding, displayedComponents: .date) {
                Label("Start", systemImage: "calendar")
            }
            Toggle(isOn: $hasEndDate) {
                Label("Set an end date", systemImage: "calendar.badge.checkmark")
            }
            .tint(AppColors.calorie)
            .onChange(of: hasEndDate) { _, on in
                if on {
                    if draft.endDate == nil {
                        let start = MedicationFormatting.dateFromISO(draft.startDate) ?? Date()
                        draft.endDate = MedicationFormatting.isoDay(Calendar.current.date(byAdding: .day, value: 6, to: start) ?? start)
                    }
                } else {
                    draft.endDate = nil
                }
            }
            if hasEndDate {
                DatePicker(selection: endDateBinding, in: (MedicationFormatting.dateFromISO(draft.startDate) ?? Date())..., displayedComponents: .date) {
                    Label("End", systemImage: "calendar")
                }
            }
        } header: {
            Text("Duration")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if hasEndDate {
                    Text("The medicine completes automatically after its last day.")
                } else {
                    Text("No end date: the medicine stays active until you stop it.")
                }
                errorFooter(for: ["start_date", "end_date"])
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    private var detailsSection: some View {
        Section {
            TextField(String(localized: "Instructions (optional)"), text: $draft.instructions, axis: .vertical)
                .lineLimit(2...5)
                .focused($focusedField, equals: .instructions)
                .accessibilityIdentifier("medications.form.instructions")
        } header: {
            Text("Instructions")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if !draft.hintNotes.isEmpty {
                    Text("Pre-filled from the prescription; check every field before saving.")
                }
                errorFooter(for: ["instructions"])
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    private var photoSection: some View {
        Section("Photo") {
            HStack(spacing: 12) {
                if let photoPreview {
                    Image(uiImage: photoPreview)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityHidden(true)
                } else {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(AppColors.calorie.opacity(0.12))
                        .frame(width: 56, height: 56)
                        .overlay { Image(systemName: "photo").foregroundStyle(AppColors.calorie) }
                        .accessibilityHidden(true)
                }
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Text(photoPreview == nil ? String(localized: "Choose photo") : String(localized: "Change photo"))
                }
                .accessibilityIdentifier("medications.form.photo")
                if photoPreview != nil {
                    Spacer()
                    Button(role: .destructive) {
                        photoPreview = nil
                        photoItem = nil
                        draft.newPhotoData = nil
                        draft.removePhoto = true
                    } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel(Text("Remove photo"))
                }
            }
        }
        .listRowBackground(AppColors.appCard)
        .task { loadExistingPhoto() }
    }

    private var relatedRecordSection: some View {
        Section {
            if let relatedRecord {
                RecordRow(record: relatedRecord, compact: true)
                Button(role: .destructive) {
                    draft.relatedRecordID = nil
                    self.relatedRecord = nil
                } label: {
                    Label("Unlink record", systemImage: "link.badge.minus")
                }
            } else {
                Button {
                    showRecordPicker = true
                } label: {
                    Label(draft.relatedRecordID == nil ? String(localized: "Link a prescription") : String(localized: "Record no longer available · choose another"),
                          systemImage: "doc.text")
                }
                .accessibilityIdentifier("medications.form.linkRecord")
            }
        } header: {
            Text("Health record")
        } footer: {
            Text("Optional. Links this medicine to a prescription or medication list saved in Records.")
        }
        .listRowBackground(AppColors.appCard)
    }

    private var remindersSection: some View {
        Section {
            Toggle(isOn: $draft.reminderEnabled) {
                Label("Dose reminders", systemImage: "bell")
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("medications.form.reminders")
        } footer: {
            Text("Reminders also need Notifications turned on in Settings.")
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    private func errorFooter(for fields: [String]) -> some View {
        let relevant = errors.filter { fields.contains($0.field) }
        if !relevant.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(relevant) { error in
                    Label(error.message, systemImage: "exclamationmark.circle")
                        .foregroundStyle(.orange)
                }
            }
        }
    }

    // MARK: Data

    private static func parseQuantity(_ text: String) -> Double? {
        let trimmed = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        switch trimmed {
        case "": return nil
        case "½", "1/2": return 0.5
        case "¼", "1/4": return 0.25
        case "¾", "3/4": return 0.75
        default: return Double(trimmed)
        }
    }

    private func loadRelatedRecord() async {
        guard let id = draft.relatedRecordID else {
            relatedRecord = nil
            return
        }
        relatedRecord = await recordsStore.detail(id: id)?.record
    }

    private func loadPhoto(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self) else { return }
        draft.newPhotoData = data
        draft.removePhoto = false
        photoPreview = MedicationPhotoStore.downsample(data, maxPixelSize: 320)
    }

    private func loadExistingPhoto() {
        guard photoPreview == nil, draft.newPhotoData == nil, let id = existingMedicationID,
              case .edit(let detail) = mode, detail.medication.photoPath != nil else { return }
        photoPreview = MedicationsRuntime.shared.photos.loadThumbnail(medicationID: id)
    }

    private func save() async {
        draft.doseQuantity = Self.parseQuantity(quantityText) ?? (quantityText.isEmpty ? draft.doseQuantity : nil)
        if !hasEndDate { draft.endDate = nil }
        errors = draft.validationErrors
        guard errors.isEmpty else {
            saveError = String(localized: "Check the highlighted fields.")
            return
        }
        if case .candidate(_, let onSave) = mode {
            onSave(draft)
            dismiss()
            return
        }
        isSaving = true
        defer { isSaving = false }
        do {
            let saved = try await store.save(draft: draft, editing: existingMedicationID)
            store.showBanner(isEditing ? String(localized: "Saved") : String(localized: "\(saved.name) added"))
            dismiss()
        } catch MedicationStoreError.validation(let list) {
            errors = list
            saveError = String(localized: "Check the highlighted fields.")
        } catch MedicationStoreError.lifecycle(let code) {
            saveError = code == "invalid_transition"
                ? String(localized: "Resume the medicine before changing its schedule.")
                : MedicationFormatting.actionErrorText(code)
        } catch {
            saveError = MedicationFormatting.actionErrorText("unknown")
        }
    }
}

private extension View {
    /// Ends text editing when a menu picker is tapped. With the keyboard up, the menu otherwise
    /// opens over a keyboard whose QuickType bar keeps changing, and the menu and the form row
    /// re-layout (flicker) as the keyboard frame moves.
    func dismissesKeyboardOnTap<Value: Hashable>(_ focus: FocusState<Value?>.Binding) -> some View {
        simultaneousGesture(TapGesture().onEnded { focus.wrappedValue = nil })
    }
}
