import SwiftUI

/// Dose history grouped by day (docs §9), for one medicine or all, with keyset paging.
struct MedicationHistoryView: View {
    let medicationID: String?
    @Environment(MedicationStore.self) private var store
    @State private var logs: [DoseLog] = []
    @State private var names: [String: Medication] = [:]
    @State private var hasMore = true
    @State private var isLoading = false

    private var grouped: [(day: String, logs: [DoseLog])] {
        let zone = store.zoneIdentifier
        var order: [String] = []
        var buckets: [String: [DoseLog]] = [:]
        for log in logs {
            let day = MR.localDateOf(Int(log.takenAtMs ?? log.scheduledAtMs), zone: zone)
            if buckets[day] == nil { order.append(day) }
            buckets[day, default: []].append(log)
        }
        return order.map { ($0, buckets[$0] ?? []) }
    }

    var body: some View {
        List {
            if logs.isEmpty, !isLoading {
                ContentUnavailableView("No doses yet", systemImage: "clock.arrow.circlepath", description: Text("Doses you take, skip or miss show up here."))
                    .listRowBackground(Color.clear)
            }
            ForEach(grouped, id: \.day) { group in
                Section(MedicationFormatting.dayTitle(iso: group.day, today: store.todayLocalDate)) {
                    ForEach(group.logs) { log in
                        row(log)
                            .onAppear {
                                if log.id == logs.last?.id { Task { await loadMore() } }
                            }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
            if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
            }
            Section {
                MedicationDisclaimerFooter()
            }
            .listRowBackground(Color.clear)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle(medicationID == nil ? String(localized: "Dose history") : (names[medicationID ?? ""]?.name ?? String(localized: "History")))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: store.revision) {
            logs = []
            hasMore = true
            await loadMore()
        }
        .accessibilityIdentifier("medications.history")
    }

    private func row(_ log: DoseLog) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(names[log.medicationID]?.displayName ?? String(localized: "Medicine"))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text(detailLine(log))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 4)
            DoseStatusBadge(status: log.status, isLate: log.status == .taken && (log.takenAtMs ?? 0) > log.scheduledAtMs + Int64(MR.graceMs))
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("medications.history.\(log.id)")
    }

    private func detailLine(_ log: DoseLog) -> String {
        var parts: [String] = []
        if log.isPRN {
            parts.append(String(localized: "As needed · \(MedicationFormatting.timeText(ms: log.takenAtMs ?? log.scheduledAtMs))"))
        } else {
            parts.append(String(localized: "Scheduled \(MedicationFormatting.timeText(ms: log.scheduledAtMs))"))
            if log.status == .taken, let taken = log.takenAtMs {
                parts.append(String(localized: "taken \(MedicationFormatting.timeText(ms: taken))"))
            }
        }
        parts.append(MedicationFormatting.doseText(quantity: log.doseQuantity, unit: log.doseUnit))
        if let note = log.note, !note.isEmpty { parts.append(note) }
        return parts.joined(separator: " · ")
    }

    private func loadMore() async {
        guard hasMore, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        let page = await store.history(medicationID: medicationID, before: logs.last)
        hasMore = page.count >= MedicationsRepository.historyPageSize
        logs.append(contentsOf: page)
        let missing = Set(page.map(\.medicationID)).subtracting(names.keys)
        if !missing.isEmpty, let repository = await store.openIfNeeded() {
            for id in missing {
                if let medication = try? await repository.medication(id: id) { names[id] = medication }
            }
        }
    }
}
