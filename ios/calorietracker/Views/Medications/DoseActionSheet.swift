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
                    DoseSheetHeader(item: item, medication: medication)
                }
                .listRowBackground(AppColors.appCard)

                if isResolved {
                    Section {
                        Text("Undo removes this entry so the dose shows as due again.")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    .listRowBackground(AppColors.appCard)
                } else {
                    Section {
                        Toggle(isOn: $useCustomTime.animation(.easeInOut(duration: 0.2))) {
                            Label("Taken at a different time", systemImage: "clock.arrow.circlepath")
                        }
                        .tint(AppColors.calorie)
                        if useCustomTime {
                            DatePicker("Time", selection: $takenAt, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                        }
                    } footer: {
                        if item.status == .missed {
                            Text("This dose is past its window. Ayuvo never marks a dose taken on its own; if you're unsure what to do, ask your doctor or pharmacist.")
                        } else if !canSnooze {
                            Text("Snoozing becomes available once the dose is due.")
                        }
                    }
                    .listRowBackground(AppColors.appCard)

                    if canSnooze {
                        Section("Snooze") {
                            Picker("Snooze for", selection: $snoozeMinutes) {
                                ForEach(MedicationSettings.snoozeOptions, id: \.self) { minutes in
                                    Text(String(localized: "\(minutes) min")).tag(minutes)
                                }
                            }
                            .pickerStyle(.segmented)
                            .labelsHidden()
                            Button {
                                Task { await run(.snoozed) }
                            } label: {
                                Label("Remind me later", systemImage: "clock")
                            }
                            .accessibilityIdentifier("medications.dose.snooze")
                        }
                        .listRowBackground(AppColors.appCard)
                    }

                    Section {
                        TextField(String(localized: "Optional note"), text: $note, axis: .vertical)
                            .lineLimit(1...3)
                    } header: {
                        Text("Note")
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
            // The actions stay pinned above the home indicator, so they never collide with the
            // list and stay reachable at the medium detent.
            .safeAreaInset(edge: .bottom, spacing: 0) { actionBar }
            .disabled(isWorking)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { snoozeMinutes = store.snoozeMinutes }
        .accessibilityIdentifier("medications.doseSheet")
    }

    @ViewBuilder private var actionBar: some View {
        VStack(spacing: 10) {
            if let errorText {
                Label(errorText, systemImage: "exclamationmark.circle")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if isResolved {
                Button(role: .destructive) {
                    Task { await run(.undo) }
                } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .accessibilityIdentifier("medications.dose.undo")
            } else {
                Button {
                    Task { await run(.taken) }
                } label: {
                    Label(takeTitle, systemImage: "checkmark.circle.fill")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.large)
                .accessibilityIdentifier("medications.dose.take")

                Button {
                    Task { await run(.skipped) }
                } label: {
                    Text("Skip this dose")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .foregroundStyle(AppColors.calorie)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
                .accessibilityIdentifier("medications.dose.skip")
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 10)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
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
                    DoseSheetHeader(
                        medication: medication,
                        subtitle: String(localized: "As needed · usual dose \(MedicationFormatting.doseText(medication))")
                    )
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

/// The medicine, its dose and the current status at the top of the dose sheets. Keeps the name to
/// two lines so a long product name cannot push the controls off a medium-detent sheet.
struct DoseSheetHeader: View {
    var item: MedicationTodayTimeline.Item?
    let medication: Medication
    var subtitle: String?

    private var detailText: String {
        if let subtitle { return subtitle }
        guard let item else { return MedicationFormatting.doseText(medication) }
        return "\(MedicationFormatting.doseText(medication)) · \(MedicationFormatting.timeText(ms: item.scheduledAtMs))"
    }

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            MedicationIconBubble(medication: medication, size: 46)
            VStack(alignment: .leading, spacing: 5) {
                Text(medication.displayName)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                Text(detailText)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                if let item {
                    DoseStatusBadge(status: item.status, isLate: item.isLate)
                        .padding(.top, 1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}
