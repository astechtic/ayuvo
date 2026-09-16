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

    private var subtitle: String {
        let summary = store.today.summary
        var parts: [String] = []
        if summary.total > 0 {
            parts.append(String(localized: "Taken \(summary.taken) of \(summary.total)"))
        } else {
            parts.append(String(localized: "No doses scheduled today"))
        }
        if let (item, medication) = nextDose {
            parts.append(String(localized: "Next \(medication.displayName) at \(MedicationFormatting.timeText(ms: item.scheduledAtMs))"))
        } else if summary.missed > 0 {
            parts.append(summary.missed == 1 ? String(localized: "1 missed dose") : String(localized: "\(summary.missed) missed doses"))
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        if store.activeCount + store.pausedCount > 0 {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(AppColors.calorie.opacity(0.15))
                            .frame(width: 42, height: 42)
                        Image(systemName: "pills.fill")
                            .foregroundColor(AppColors.calorie)
                            .font(.system(size: 18))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Medications")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.primary)
                        Text(subtitle)
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.secondary)
                }
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(AppColors.appCard)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
                        )
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
