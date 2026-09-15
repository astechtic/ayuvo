import SwiftUI

// Coach × Health Records UI (docs/health-records.md §26–§30): consent sheet, "Analyzing" chip bar,
// "Change records" picker, "Used records" chips and the online-approval bridge.

enum CoachRecordsFormatting {
    static func dateText(_ day: String) -> String {
        RecordDates.date(fromDay: day)?.formatted(date: .abbreviated, time: .omitted) ?? day
    }

    static func chipText(_ ref: ChatRecordRef) -> String {
        "\(ref.title) — \(dateText(ref.date))"
    }

    /// The provider Coach will use, and whether it runs on this device.
    @MainActor
    static func coachProvider(override: AIProvider?) -> (name: String, onDevice: Bool) {
        let provider = override ?? AIProviderSettings.currentConfig(requiresVision: false).provider
        let onDevice = provider.apiFormat == .onDevice || provider.apiFormat == .liteRTLocal
        return (provider.displayName, onDevice)
    }

    /// The on-device provider "Use on-device Coach" switches to, when one is usable.
    @MainActor
    static func onDeviceProvider() -> AIProvider? {
        let environment = RecordsAIEnvironment.current()
        if environment.appleIntelligenceAvailable { return .appleIntelligence }
        if environment.gemmaInstalled { return .gemma4Local }
        return nil
    }
}

/// Bridges the tool loop's §30 approval request to a confirmation dialog on the chat screen.
@Observable
@MainActor
final class CoachRecordsApprovalBox {
    var isPresented = false
    var providerName = ""
    var offersOnDevice = false
    private var continuation: CheckedContinuation<CoachRecordsOnlineDecision, Never>?

    func ask(providerName: String, offersOnDevice: Bool) async -> CoachRecordsOnlineDecision {
        continuation?.resume(returning: .cancel)
        self.providerName = providerName
        self.offersOnDevice = offersOnDevice
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            self.isPresented = true
        }
    }

    /// The dialog went away; a button action (if any) answers first, otherwise this counts as Cancel.
    func dismissed() {
        isPresented = false
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard let self, !self.isPresented, self.continuation != nil else { return }
            self.answer(.cancel)
        }
    }

    func answer(_ decision: CoachRecordsOnlineDecision) {
        isPresented = false
        continuation?.resume(returning: decision)
        continuation = nil
    }
}

/// §26 consent sheet: "Let Coach read your health records?" — Allow / Not now.
struct CoachRecordsConsentSheet: View {
    let providerName: String
    let onDevice: Bool
    let onAllow: () -> Void
    let onNotNow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Image(systemName: "list.clipboard.fill")
                .font(.system(size: 34))
                .foregroundStyle(AppColors.calorie)
                .padding(.top, 8)
            Text("Let Coach read your health records?")
                .font(.system(.title3, design: .rounded, weight: .bold))
                .accessibilityIdentifier("coach.recordsConsent.title")
            Text(onDevice
                ? String(localized: "Coach reads the records on this device; nothing is sent online.")
                : String(localized: "Coach sends the details of the records it reads — test results, dates, doctors and diagnoses — to \(providerName) to answer you. Records stay stored on this device."))
                .font(.system(.body, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button {
                onAllow()
            } label: {
                Text("Allow").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .controlSize(.large)
            .accessibilityIdentifier("coach.recordsConsent.allow")
            Button {
                onNotNow()
            } label: {
                Text("Not now").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .accessibilityIdentifier("coach.recordsConsent.notNow")
        }
        .font(.system(.headline, design: .rounded))
        .padding(24)
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }
}

/// "Analyzing: ✓ CBC — Sep 12 … · Change records" above the Coach input (§27).
struct CoachRecordsChipBar: View {
    let records: [ChatRecordRef]
    let onChange: () -> Void
    let onClear: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "list.clipboard.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .accessibilityHidden(true)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Text("Analyzing:")
                        .font(.system(.caption, design: .rounded, weight: .bold))
                        .foregroundStyle(.secondary)
                    if records.count > 3 {
                        chip(String(localized: "Analyzing \(records.count) records"), identifier: "coach.records.count")
                    } else {
                        ForEach(records) { ref in
                            chip("✓ " + CoachRecordsFormatting.chipText(ref), identifier: "coach.records.chip.\(ref.recordID)")
                        }
                    }
                }
            }
            Button("Change records", action: onChange)
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .accessibilityIdentifier("coach.records.change")
            Button(action: onClear) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Stop analyzing records"))
            .accessibilityIdentifier("coach.records.clear")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 12)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("coach.records.bar")
    }

    private func chip(_ text: String, identifier: String) -> some View {
        Text(text)
            .font(.system(.caption, design: .rounded, weight: .semibold))
            .lineLimit(1)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(AppColors.calorie.opacity(0.12), in: Capsule())
            .accessibilityIdentifier(identifier)
    }
}

/// "Change records": search + recent records with checkboxes, max 10 (§27).
struct CoachRecordsPickerSheet: View {
    let initial: [ChatRecordRef]
    let onDone: ([ChatRecordRef]) -> Void
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var candidates: [HealthRecord] = []
    @State private var selection: [ChatRecordRef] = []
    @State private var isLoading = true

    var body: some View {
        NavigationStack {
            List {
                if !selection.isEmpty {
                    Section("Selected") {
                        ForEach(selection) { ref in
                            Button {
                                selection.removeAll { $0.recordID == ref.recordID }
                            } label: {
                                HStack {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(AppColors.calorie)
                                    Text(CoachRecordsFormatting.chipText(ref)).lineLimit(1)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                Section {
                    if candidates.isEmpty, !isLoading {
                        Text("No records found").foregroundStyle(.secondary)
                    }
                    ForEach(candidates) { record in
                        let isSelected = selection.contains { $0.recordID == record.id }
                        Button {
                            toggle(record)
                        } label: {
                            HStack {
                                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 20))
                                    .foregroundStyle(isSelected ? AppColors.calorie : .secondary)
                                RecordRow(record: record, compact: true)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(!isSelected && selection.count >= RecordsCoach.maxSelected)
                        .accessibilityIdentifier("coach.recordsPicker.candidate.\(record.id)")
                    }
                } header: {
                    Text(text.isEmpty ? "Recent records" : "Results")
                } footer: {
                    Text("Choose up to \(RecordsCoach.maxSelected) records for this conversation.")
                }
            }
            .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search records"))
            .navigationTitle("Change records")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        onDone(selection)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("coach.recordsPicker.done")
                }
            }
            .task(id: text) {
                if !text.isEmpty { try? await Task.sleep(nanoseconds: 150_000_000) }
                guard !Task.isCancelled else { return }
                candidates = await store.coachPickerCandidates(text: text)
                isLoading = false
            }
            .onAppear { if selection.isEmpty { selection = initial } }
        }
        .accessibilityIdentifier("coach.recordsPicker")
    }

    private func toggle(_ record: HealthRecord) {
        if let index = selection.firstIndex(where: { $0.recordID == record.id }) {
            selection.remove(at: index)
        } else if selection.count < RecordsCoach.maxSelected {
            selection.append(ChatRecordRef(record))
        }
    }
}

/// "Used records" chips under a Coach reply; each opens the record on the Records tab.
struct CoachUsedRecordsRow: View {
    let refs: [ChatRecordRef]
    let onOpen: (ChatRecordRef) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Used records")
                .font(.system(.caption2, design: .rounded, weight: .bold))
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(refs) { ref in
                        Button {
                            onOpen(ref)
                        } label: {
                            Label(CoachRecordsFormatting.chipText(ref), systemImage: "doc.text")
                                .font(.system(.caption, design: .rounded, weight: .semibold))
                                .lineLimit(1)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(AppColors.calorie.opacity(0.12), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("coach.usedRecord.\(ref.recordID)")
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("coach.usedRecords")
    }
}
