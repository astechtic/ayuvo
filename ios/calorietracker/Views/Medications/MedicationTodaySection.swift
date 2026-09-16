import SwiftUI

/// "Taken 2 / 4 · 1 upcoming · 1 missed" strip above the Today timeline (docs §8).
struct MedicationSummaryStrip: View {
    let summary: MedicationTodayTimeline.Summary

    var body: some View {
        RecordsCard {
            HStack(spacing: 14) {
                stat(String(localized: "Taken"), "\(summary.taken) / \(summary.total)", "checkmark.circle", AppColors.calorie)
                Divider().frame(height: 28)
                stat(String(localized: "Upcoming"), "\(summary.upcoming + summary.due + summary.snoozed)", "clock", AppColors.calorie)
                Divider().frame(height: 28)
                stat(String(localized: "Missed"), "\(summary.missed)", "exclamationmark.triangle", summary.missed > 0 ? .orange : .secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("medications.summary")
    }

    private func stat(_ title: String, _ value: String, _ icon: String, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(title, systemImage: icon)
                .font(.system(.caption2, design: .rounded, weight: .semibold))
                .foregroundStyle(tint)
                .lineLimit(1)
            Text(value)
                .font(.system(.title3, design: .rounded, weight: .bold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Today's doses grouped by time slot.
struct MedicationTodaySection: View {
    let timeline: MedicationTodayTimeline
    var onTake: (MedicationTodayTimeline.Item) -> Void
    var onSkip: (MedicationTodayTimeline.Item) -> Void
    var onSnooze: (MedicationTodayTimeline.Item, Int) -> Void
    var onSelect: (MedicationTodayTimeline.Item) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            MedicationSectionHeader(title: String(localized: "Today"), systemImage: "calendar",
                                    trailing: timeline.summary.total > 0 ? "\(timeline.summary.total)" : nil)
            ForEach(timeline.slots) { slot in
                RecordsCard(padding: 12) {
                    Text(MedicationFormatting.timeText(hhmm: slot.slot))
                        .font(.system(.caption, design: .rounded, weight: .bold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(slot.items) { item in
                        if let medication = timeline.medication(item.medicationID) {
                            DoseRow(
                                item: item,
                                medication: medication,
                                onTake: { onTake(item) },
                                onSkip: { onSkip(item) },
                                onSnooze: { onSnooze(item, $0) },
                                onSelect: { onSelect(item) }
                            )
                            if item.id != slot.items.last?.id { Divider() }
                        }
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("medications.slot.\(slot.slot)")
            }
        }
    }
}

/// "As needed" medicines with a Log dose action (docs §11).
struct MedicationPRNSection: View {
    let timeline: MedicationTodayTimeline
    var onLog: (Medication) -> Void
    var onSelect: (Medication) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            MedicationSectionHeader(title: String(localized: "As needed"), systemImage: "hand.raised")
            RecordsCard(padding: 12) {
                ForEach(timeline.prn) { row in
                    if let medication = timeline.medication(row.medicationID) {
                        HStack(spacing: 12) {
                            Button { onSelect(medication) } label: {
                                MedicationRow(medication: medication, subtitle: subtitle(row, medication), showsStatus: false)
                            }
                            .buttonStyle(.plain)
                            Button { onLog(medication) } label: {
                                Text("Log dose")
                                    .font(.system(.caption, design: .rounded, weight: .semibold))
                            }
                            .buttonStyle(.bordered)
                            .tint(AppColors.calorie)
                            .controlSize(.small)
                            .accessibilityLabel(Text("Log a dose of \(medication.displayName)"))
                            .accessibilityIdentifier("medications.prn.log.\(medication.id)")
                        }
                        if row.id != timeline.prn.last?.id { Divider() }
                    }
                }
            }
        }
    }

    private func subtitle(_ row: MedicationTodayTimeline.PRNRow, _ medication: Medication) -> String {
        var parts = [MedicationFormatting.doseText(medication)]
        if let last = row.lastTakenMs {
            parts.append(String(localized: "Last taken \(MedicationFormatting.relativeText(ms: last))"))
        } else {
            parts.append(String(localized: "Not taken yet"))
        }
        if row.todayCount > 0 {
            parts.append(String(localized: "\(MedicationFormatting.doseCount(row.todayCount)) today"))
        }
        return parts.joined(separator: " · ")
    }
}
