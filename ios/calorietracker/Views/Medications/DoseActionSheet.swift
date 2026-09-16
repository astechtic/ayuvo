import SwiftUI

/// Actions on one scheduled dose (docs §11): Taken now / Taken at… / Skip / Snooze / note, plus
/// Undo for the user's own taken and skipped rows. A missed dose can still be taken (late) or skipped.
struct DoseActionSheet: View {
    let item: MedicationTodayTimeline.Item
    let medication: Medication
    @Environment(MedicationStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var takenAt = Date()
    @State private var useCustomTime = false
    @State private var snoozeMinutes = MedicationSettings.defaultSnoozeMinutes
    @State private var note = ""
    @State private var errorText: String?
    @State private var isWorking = false

    private var isResolved: Bool { item.status == .taken || item.status == .skipped }
    private var canSnooze: Bool { item.status == .due || item.status == .snoozed }
    private var takeTitle: String {
        if item.status == .missed { return String(localized: "Take (late)") }
        return useCustomTime ? String(localized: "Taken at \(takenAt.formatted(date: .omitted, time: .shortened))") : String(localized: "Taken now")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        MedicationIconBubble(medication: medication)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(medication.displayName)
                                .font(.system(.headline, design: .rounded))
                            Text("\(MedicationFormatting.doseText(medication)) · \(MedicationFormatting.timeText(ms: item.scheduledAtMs))")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                            DoseStatusBadge(status: item.status, isLate: item.isLate)
                        }
                    }
                    .listRowBackground(AppColors.appCard)
                }

                if isResolved {
                    Section {
                        Button(role: .destructive) {
                            Task { await run(.undo) }
                        } label: {
                            Label("Undo", systemImage: "arrow.uturn.backward")
                        }
                        .accessibilityIdentifier("medications.dose.undo")
                    } footer: {
                        Text("Removes this entry so the dose shows as due again.")
                    }
                    .listRowBackground(AppColors.appCard)
                } else {
                    Section {
                        Toggle(isOn: $useCustomTime) {
                            Label("Taken at a different time", systemImage: "clock.arrow.circlepath")
                        }
                        .tint(AppColors.calorie)
                        if useCustomTime {
                            DatePicker("Time", selection: $takenAt, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                        }
                        Button {
                            Task { await run(.taken) }
                        } label: {
                            Label(takeTitle, systemImage: "checkmark.circle")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.calorie)
                        .controlSize(.large)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                        .accessibilityIdentifier("medications.dose.take")
                    }
                    .listRowBackground(AppColors.appCard)

                    Section {
                        Button {
                            Task { await run(.skipped) }
                        } label: {
                            Label("Skip this dose", systemImage: "forward")
                        }
                        .accessibilityIdentifier("medications.dose.skip")
                        if canSnooze {
                            Picker("Snooze for", selection: $snoozeMinutes) {
                                ForEach(MedicationSettings.snoozeOptions, id: \.self) { minutes in
                                    Text(String(localized: "\(minutes) min")).tag(minutes)
                                }
                            }
                            .pickerStyle(.segmented)
                            Button {
                                Task { await run(.snoozed) }
                            } label: {
                                Label("Snooze", systemImage: "clock")
                            }
                            .accessibilityIdentifier("medications.dose.snooze")
                        }
                    } footer: {
                        if item.status == .missed {
                            Text("This dose is past its window. Ayuvo never marks a dose taken on its own; if you're unsure what to do, ask your doctor or pharmacist.")
                        } else if !canSnooze {
                            Text("Snoozing becomes available once the dose is due.")
                        }
                    }
                    .listRowBackground(AppColors.appCard)

                    Section("Note") {
                        TextField(String(localized: "Optional note"), text: $note, axis: .vertical)
                            .lineLimit(1...3)
                    }
                    .listRowBackground(AppColors.appCard)
                }

                if let errorText {
                    Section {
                        Label(errorText, systemImage: "exclamationmark.circle")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.orange)
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Dose")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .disabled(isWorking)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { snoozeMinutes = store.snoozeMinutes }
        .accessibilityIdentifier("medications.doseSheet")
    }

    private func run(_ action: DoseAction) async {
        isWorking = true
        defer { isWorking = false }
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let outcome = await store.act(
            action,
            on: item.occurrence,
            note: trimmedNote.isEmpty ? nil : trimmedNote,
            takenAt: (action == .taken && useCustomTime) ? takenAt : nil,
            snoozeMinutes: action == .snoozed ? snoozeMinutes : nil
        )
        if outcome.ok {
            dismiss()
        } else {
            errorText = MedicationFormatting.actionErrorText(outcome.error ?? "unknown")
        }
    }
}

/// Logs an as-needed dose (docs §11 `log_prn_dose`): time (default now), quantity, note.
struct PRNLogSheet: View {
    let medication: Medication
    @Environment(MedicationStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var takenAt = Date()
    @State private var quantityText = ""
    @State private var note = ""
    @State private var errorText: String?
    @State private var isWorking = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        MedicationIconBubble(medication: medication)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(medication.displayName)
                                .font(.system(.headline, design: .rounded))
                            Text(String(localized: "As needed · usual dose \(MedicationFormatting.doseText(medication))"))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
                Section {
                    DatePicker("Taken at", selection: $takenAt, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                    HStack {
                        Text("Quantity")
                        Spacer()
                        TextField(MedicationFormatting.quantityText(medication.doseQuantity), text: $quantityText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 100)
                            .accessibilityLabel(Text("Quantity"))
                        Text(medication.doseUnit.title.lowercased())
                            .foregroundStyle(.secondary)
                    }
                    TextField(String(localized: "Optional note"), text: $note, axis: .vertical)
                        .lineLimit(1...3)
                } footer: {
                    Text("Leave the quantity empty to log the usual dose.")
                }
                .listRowBackground(AppColors.appCard)
                if let errorText {
                    Section {
                        Label(errorText, systemImage: "exclamationmark.circle")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.orange)
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Log dose")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Log") { Task { await log() } }
                        .disabled(isWorking)
                        .accessibilityIdentifier("medications.prn.confirm")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func log() async {
        isWorking = true
        defer { isWorking = false }
        let quantity = Double(quantityText.replacingOccurrences(of: ",", with: "."))
        if !quantityText.trimmingCharacters(in: .whitespaces).isEmpty, quantity == nil || (quantity ?? 0) <= 0 {
            errorText = String(localized: "Enter a quantity greater than 0.")
            return
        }
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let outcome = await store.logPRN(medicationID: medication.id, at: takenAt, quantity: quantity,
                                         note: trimmedNote.isEmpty ? nil : trimmedNote)
        if outcome.ok {
            store.showBanner(String(localized: "Dose logged"))
            dismiss()
        } else {
            errorText = MedicationFormatting.actionErrorText(outcome.error ?? "unknown")
        }
    }
}
