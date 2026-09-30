import SwiftUI

/// Medication detail: last-30-day adherence, on-time share, median delay, missed doses by weekday, streak and a
/// suggested reminder time (`meds_adherence`), plus supplement daily averages over the dosing interval
/// (`supplement_daily`) with an above-upper-limit note (docs/intake-metrics.md §3).
struct MedicationIntakeCard: View {
    let detail: MedicationDetail
    @Environment(MedicationStore.self) private var store
    @State private var insights: MedicationIntakeInsights?
    @State private var isMoving = false

    var body: some View {
        Group {
            if let insights {
                if !detail.medication.isPRN, insights.adherence.scheduled > 0 {
                    adherenceCard(insights.adherence)
                }
                if !insights.nutrientAverages.isEmpty {
                    averagesCard(insights.nutrientAverages)
                }
            }
        }
        .task(id: "\(store.revision)-\(detail.medication.id)") {
            insights = await store.intakeInsights(detail)
        }
    }

    // MARK: Adherence

    private func adherenceCard(_ a: MedsAdherence.Result) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Last 30 days", systemImage: "calendar.badge.checkmark")
            if let pct = a.adherencePct {
                row(String(localized: "Doses taken"),
                    "\(percent(pct)) · " + (a.adherent == true ? String(localized: "Adherent (80% or more)") : String(localized: "Below 80%")))
                    .accessibilityIdentifier("medications.detail.intake.adherence")
            }
            if let onTime = a.onTimePct {
                row(String(localized: "On time (within 1 hour)"), percent(onTime))
            }
            if let delay = a.medianDelayMin {
                row(String(localized: "Typical delay"), delayText(delay))
            }
            row(String(localized: "Streak"), a.streak == 1 ? String(localized: "1 dose") : String(localized: "\(a.streak) doses"))
            if a.missedByWeekday.contains(where: { $0 > 0 }) {
                row(String(localized: "Missed by day"), missedText(a.missedByWeekday))
            }
            if let suggested = a.suggestedClockMin, let current = MedicationStore.singleReminderTime(detail),
               current != MedicationStore.clockText(suggested) {
                let target = MedicationStore.clockText(suggested)
                Button {
                    Task {
                        isMoving = true
                        if await store.moveReminder(detail, toClockMin: suggested) {
                            store.showBanner(String(localized: "Reminder moved to \(MedicationFormatting.timeText(hhmm: target))"))
                        }
                        isMoving = false
                    }
                } label: {
                    Label(String(localized: "Move reminder to \(MedicationFormatting.timeText(hhmm: target))?"), systemImage: "bell.badge")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(AppColors.calorie)
                .disabled(isMoving)
                .accessibilityIdentifier("medications.detail.intake.moveReminder")
                Text("You usually take it later than the reminder. Moving it changes future reminders only.")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(IntakeConfig.shared.sources["adherence"] ?? "")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Supplement averages

    private func averagesCard(_ rows: [MedicationIntakeInsights.NutrientAverage]) -> some View {
        RecordsCard {
            RecordsSectionTitle(title: "Daily average", systemImage: "divide")
            ForEach(rows) { r in
                VStack(alignment: .leading, spacing: 2) {
                    row(NutrientCatalog.labelTitle(r.key),
                        r.doses == 0 ? String(localized: "No doses") : NutrientCatalog.text(r.dailyAverage, key: r.key) + " " + String(localized: "a day"))
                    if r.aboveUpper, let upper = r.upper {
                        Label(String(localized: "Above the \(NutrientCatalog.text(upper, key: r.key)) daily upper limit. Prescribed courses can be higher; follow your prescriber."),
                              systemImage: "exclamationmark.circle")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            if let window = rows.first?.windowDays {
                Text(String(localized: "Taken doses over the last \(window) days, spread across each day of the dosing interval (a weekly dose counts one seventh per day)."))
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityIdentifier("medications.detail.intake.averages")
    }

    // MARK: Formatting

    private func percent(_ value: Double) -> String {
        value == value.rounded() ? "\(Int(value))%" : String(format: "%.1f%%", value)
    }

    private func delayText(_ minutes: Double) -> String {
        let m = Int(minutes)
        if abs(m) < 1 { return String(localized: "On schedule") }
        let magnitude = abs(m)
        let text = magnitude >= 60
            ? String(localized: "\(magnitude / 60) h \(magnitude % 60) min")
            : String(localized: "\(magnitude) min")
        return m > 0 ? String(localized: "\(text) late") : String(localized: "\(text) early")
    }

    private func missedText(_ counts: [Int]) -> String {
        counts.enumerated().filter { $0.element > 0 }.map { index, count in
            "\(MedicationFormatting.weekdayName(iso: index + 1)) \(count)"
        }.joined(separator: " · ")
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
}
