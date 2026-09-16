import SwiftUI

/// Medication detail (spec §8): information, schedule, dates, adherence, recent history, the linked
/// record and the lifecycle actions (Edit · Pause/Resume · Stop · Delete · Log dose).
struct MedicationDetailView: View {
    let medicationID: String
    @Environment(MedicationStore.self) private var store
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(\.dismiss) private var dismiss
    @State private var detail: MedicationDetail?
    @State private var isLoading = true
    @State private var showEdit = false
    @State private var showPRNLog = false
    @State private var showStopConfirmation = false
    @State private var showDeleteConfirmation = false
    @State private var actionError: String?
    @State private var showAddAgain = false

    var body: some View {
        Group {
            if let detail {
                content(detail)
            } else if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ContentUnavailableView("Medication not found", systemImage: "pills")
            }
        }
        .background(AppColors.appBackground)
        .navigationTitle(detail?.medication.name ?? String(localized: "Medication"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let detail {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        if !detail.medication.status.isFinal {
                            Button { showEdit = true } label: { Label("Edit", systemImage: "pencil") }
                            if detail.medication.isPRN, detail.medication.status == .active {
                                Button { showPRNLog = true } label: { Label("Log dose", systemImage: "plus.circle") }
                            }
                            if detail.medication.status == .active {
                                Button { Task { await store.pause(medicationID) } } label: { Label("Pause", systemImage: "pause.circle") }
                            } else if detail.medication.status == .paused {
                                Button { Task { await store.resume(medicationID) } } label: { Label("Resume", systemImage: "play.circle") }
                            }
                            Button(role: .destructive) { showStopConfirmation = true } label: { Label("Stop", systemImage: "stop.circle") }
                        } else {
                            Button { showAddAgain = true } label: { Label("Add again", systemImage: "plus.circle") }
                        }
                        Divider()
                        Button(role: .destructive) { showDeleteConfirmation = true } label: { Label("Delete", systemImage: "trash") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(Text("Actions"))
                    .accessibilityIdentifier("medications.detail.menu")
                }
            }
        }
        .task(id: store.revision) { await load() }
        .sheet(isPresented: $showEdit) {
            if let detail { MedicationFormView(mode: .edit(detail)) }
        }
        .sheet(isPresented: $showPRNLog) {
            if let detail { PRNLogSheet(medication: detail.medication) }
        }
        .sheet(isPresented: $showAddAgain) {
            if let detail {
                MedicationFormView(mode: .candidate(MedicationDraft(from: detail.medication, schedule: detail.scheduleHistory.last).restarted(), onSave: { draft in
                    Task {
                        _ = try? await store.save(draft: draft)
                    }
                }))
            }
        }
        .confirmationDialog(String(localized: "Stop \(detail?.medication.name ?? "")?"), isPresented: $showStopConfirmation, titleVisibility: .visible) {
            Button("Stop medicine", role: .destructive) { Task { await store.stop(medicationID) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Reminders stop and the medicine moves to Stopped. Your dose history is kept.")
        }
        .confirmationDialog(String(localized: "Delete \(detail?.medication.name ?? "")?"), isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
            Button("Delete medicine and history", role: .destructive) {
                Task {
                    await store.delete(medicationID)
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the medicine and every dose logged for it. This cannot be undone.")
        }
        .onChange(of: store.lastActionError) { _, error in
            actionError = error.map(MedicationFormatting.actionErrorText)
        }
        .alert("Couldn't change medicine", isPresented: Binding(get: { actionError != nil }, set: { if !$0 { actionError = nil; store.clearActionError() } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(actionError ?? "")
        }
        .accessibilityIdentifier("medications.detail")
    }

    private func content(_ detail: MedicationDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                headerCard(detail)
                scheduleCard(detail)
                datesCard(detail)
                adherenceCard(detail)
                historyCard(detail)
                relatedRecordCard(detail)
                if let instructions = detail.medication.instructions, !instructions.isEmpty {
                    RecordsCard {
                        RecordsSectionTitle(title: "Instructions", systemImage: "text.alignleft")
                        Text(instructions)
                            .font(.system(.subheadline, design: .rounded))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                MedicationDisclaimerFooter()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
    }

    // MARK: Cards

    private func headerCard(_ detail: MedicationDetail) -> some View {
        let medication = detail.medication
        return RecordsCard {
            HStack(alignment: .top, spacing: 12) {
                MedicationIconBubble(medication: medication, size: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(medication.displayName)
                        .font(.system(.title3, design: .rounded, weight: .bold))
                    Text(subtitleLine(medication))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                    MedicationStatusBadge(status: medication.status)
                }
                Spacer(minLength: 0)
            }
            if medication.status == .paused {
                Text("Paused: no reminders until you resume.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if medication.isPRN, medication.status == .active {
                Button {
                    showPRNLog = true
                } label: {
                    Label("Log dose", systemImage: "plus.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.large)
                .accessibilityIdentifier("medications.detail.logDose")
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func subtitleLine(_ medication: Medication) -> String {
        var parts = [medication.form.title, MedicationFormatting.doseText(medication)]
        if medication.foodRelation != .anytime { parts.append(medication.foodRelation.title) }
        if let generic = medication.genericName, !generic.isEmpty { parts.append(generic) }
        if let brand = medication.brandName, !brand.isEmpty { parts.append(brand) }
        return parts.joined(separator: " · ")
    }

    private func scheduleCard(_ detail: MedicationDetail) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Schedule", systemImage: "repeat")
            row(String(localized: "Frequency"), MedicationFormatting.scheduleText(detail.schedule, isPRN: detail.medication.isPRN))
            if let schedule = detail.schedule, !detail.medication.isPRN {
                row(String(localized: "Reminders"), schedule.reminderEnabled ? String(localized: "On") : String(localized: "Off"))
                if detail.medication.status == .active {
                    Toggle(isOn: Binding(get: { schedule.reminderEnabled }, set: { enabled in Task { await store.setReminderEnabled(medicationID, enabled: enabled) } })) {
                        Text("Remind me when a dose is due")
                            .font(.system(.subheadline, design: .rounded))
                    }
                    .tint(AppColors.calorie)
                }
            }
            if detail.scheduleHistory.count > 1 {
                Text(detail.scheduleHistory.count == 2
                     ? String(localized: "Schedule changed once; past doses keep their original times.")
                     : String(localized: "Schedule changed \(detail.scheduleHistory.count - 1) times; past doses keep their original times."))
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func datesCard(_ detail: MedicationDetail) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Dates", systemImage: "calendar")
            row(String(localized: "Start"), MedicationFormatting.dateText(iso: detail.medication.startDate))
            if let end = detail.medication.endDate {
                row(String(localized: "End"), MedicationFormatting.dateText(iso: end))
                if !detail.medication.status.isFinal {
                    Text(String(localized: "Completes automatically after \(MedicationFormatting.dateText(iso: end))."))
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            } else {
                row(String(localized: "End"), String(localized: "No end date"))
            }
        }
    }

    private func adherenceCard(_ detail: MedicationDetail) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Adherence", systemImage: "chart.bar")
            if detail.medication.isPRN {
                Text("As-needed medicines don't have an adherence score.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                Text(MedicationFormatting.adherenceText(detail.adherence))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .accessibilityIdentifier("medications.detail.adherence")
                Text("Last 7 days")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func historyCard(_ detail: MedicationDetail) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Recent history", systemImage: "clock.arrow.circlepath")
            if detail.recentLogs.isEmpty {
                Text("No doses recorded yet.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                ForEach(detail.recentLogs.prefix(5)) { log in
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(MedicationFormatting.date(ms: log.takenAtMs ?? log.scheduledAtMs).formatted(date: .abbreviated, time: .shortened))
                                .font(.system(.subheadline, design: .rounded))
                            Text(MedicationFormatting.doseText(quantity: log.doseQuantity, unit: log.doseUnit) + (log.note.map { " · \($0)" } ?? ""))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                        Spacer(minLength: 4)
                        DoseStatusBadge(status: log.status, isLate: log.status == .taken && (log.takenAtMs ?? 0) > log.scheduledAtMs + Int64(MR.graceMs))
                    }
                    .accessibilityElement(children: .combine)
                }
                NavigationLink(value: MedicationRoute.history(medicationID)) {
                    Label("View all", systemImage: "list.bullet")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                }
                .accessibilityIdentifier("medications.detail.history")
            }
        }
    }

    @ViewBuilder
    private func relatedRecordCard(_ detail: MedicationDetail) -> some View {
        if let record = detail.relatedRecord {
            RecordsCard {
                RecordsSectionTitle(title: "Health record", systemImage: "doc.text")
                NavigationLink(value: RecordsRoute.detail(record.id)) {
                    RecordRow(record: record, compact: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("medications.detail.record")
            }
        } else if detail.relatedRecordMissing {
            RecordsCard {
                RecordsSectionTitle(title: "Health record", systemImage: "doc.text")
                Text("Record no longer available")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(value)
                .font(.system(.subheadline, design: .rounded, weight: .medium))
                .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        detail = await store.detail(medicationID, records: recordsStore)
        isLoading = false
    }
}

private extension MedicationDraft {
    /// "Add again" for a completed/stopped medicine: same dosing, fresh start today, no end date.
    func restarted() -> MedicationDraft {
        var copy = self
        copy.startDate = MedicationFormatting.isoDay(Date())
        copy.endDate = nil
        copy.newPhotoData = nil
        copy.removePhoto = false
        return copy
    }
}
