import SwiftUI

/// Home dashboard card: today's medication summary and the next dose. Renders nothing while the
/// user has no active or paused medicines, so the dashboard stays untouched for everyone else.
struct HomeMedicationsCard: View {
    let store: MedicationStore
    var onOpen: () -> Void

    private var nextDose: (MedicationTodayTimeline.Item, Medication)? {
        for slot in store.today.slots {
            for item in slot.items where item.kind == .scheduled && (item.status == .scheduled || item.status == .due || item.status == .snoozed) {
                if let medication = store.today.medication(item.medicationID) { return (item, medication) }
            }
        }
        return nil
    }

    private var detail: String {
        let summary = store.today.summary
        if let (item, medication) = nextDose {
            return String(localized: "Next \(medication.displayName) at \(MedicationFormatting.timeText(ms: item.scheduledAtMs))")
        }
        if summary.missed > 0 {
            return String(localized: "\(summary.missed) missed doses", comment: "Home medications card: missed doses today")
        }
        if summary.total == 0 { return String(localized: "No doses scheduled today") }
        return String(localized: "Taken \(summary.taken) of \(summary.total)")
    }

    var body: some View {
        if store.activeCount + store.pausedCount > 0 {
            let summary = store.today.summary
            Button(action: onOpen) {
                SummaryTile(
                    title: String(localized: "Medications"),
                    systemImage: "pills.fill",
                    tint: AyuvoPalette.medications,
                    trailing: String(localized: "Today"),
                    value: summary.total > 0 ? "\(summary.taken)/\(summary.total)" : InsightsText.missing,
                    detail: detail,
                    chart: summary.total > 0 ? .ring(progress: Double(summary.taken) / Double(summary.total), text: nil) : .none
                )
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("home.medicationsCard")
            .task(id: store.revision) {
                if !store.hasLoadedOnce { await store.reload() }
            }
        }
    }
}
