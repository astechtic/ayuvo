import SwiftUI

/// Icon + text capsule for a dose status (docs §16: never colour alone). Copied from
/// `RecordEpisodeBadge`; the accent stays `AppColors.calorie`, "Missed" uses the same attention
/// orange as Records' "Needs review".
struct DoseStatusBadge: View {
    let status: DoseStatus
    var isLate = false

    private var tint: Color {
        switch status {
        case .missed: .orange
        case .skipped: .secondary
        default: AppColors.calorie
        }
    }

    private var title: String {
        if status == .taken, isLate { return String(localized: "Taken late") }
        return status.title
    }

    var body: some View {
        Label(title, systemImage: status.systemImage)
            .labelStyle(.titleAndIcon)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(tint.opacity(0.12), in: Capsule())
            .lineLimit(1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityIdentifier("medications.status.\(status.rawValue)")
    }
}

/// Icon + text capsule for a medication's lifecycle status.
struct MedicationStatusBadge: View {
    let status: MedicationStatus

    private var tint: Color {
        switch status {
        case .active: AppColors.calorie
        case .paused: .orange
        case .completed, .stopped: .secondary
        }
    }

    var body: some View {
        Label(status.title, systemImage: status.systemImage)
            .labelStyle(.titleAndIcon)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(tint.opacity(0.12), in: Capsule())
            .lineLimit(1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(status.title)
            .accessibilityIdentifier("medications.medicationStatus.\(status.rawValue)")
    }
}

/// Rounded icon tile for a medicine's form (or its photo thumbnail when one is stored).
struct MedicationIconBubble: View {
    let medication: Medication
    var size: CGFloat = 40
    @State private var thumbnail: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                .fill(AppColors.calorie.opacity(0.12))
            if let thumbnail {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: medication.form.systemImage)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
        .task(id: medication.photoPath) {
            guard medication.photoPath != nil else {
                thumbnail = nil
                return
            }
            thumbnail = MedicationsRuntime.shared.photos.loadThumbnail(medicationID: medication.id)
        }
        .accessibilityHidden(true)
    }
}

/// One medicine in the "All medications" / "As needed" lists.
struct MedicationRow: View {
    let medication: Medication
    var subtitle: String
    var showsStatus = true

    var body: some View {
        HStack(spacing: 12) {
            MedicationIconBubble(medication: medication)
            VStack(alignment: .leading, spacing: 3) {
                Text(medication.displayName)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 4)
            if showsStatus {
                MedicationStatusBadge(status: medication.status)
            }
            Image(systemName: "chevron.right")
                .font(.system(.caption2, weight: .bold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// One dose of the Today timeline: name · strength · dose, the status badge and the Take / More
/// controls for open doses. Tapping the row opens the dose sheet.
struct DoseRow: View {
    let item: MedicationTodayTimeline.Item
    let medication: Medication
    var onTake: () -> Void
    var onSkip: () -> Void
    var onSnooze: (Int) -> Void
    var onSelect: () -> Void

    private var isOpen: Bool {
        item.kind == .scheduled && (item.status == .scheduled || item.status == .due || item.status == .snoozed)
    }

    private var canSnooze: Bool { item.status == .due || item.status == .snoozed }

    private var doseLine: String {
        var parts: [String] = []
        if let quantity = item.doseQuantity, let unit = item.doseUnit {
            parts.append(MedicationFormatting.doseText(quantity: quantity, unit: unit))
        } else {
            parts.append(MedicationFormatting.doseText(medication))
        }
        if item.kind == .prn {
            parts.append(String(localized: "As needed"))
        } else if medication.foodRelation != .anytime {
            parts.append(medication.foodRelation.title.lowercased())
        }
        if item.status == .snoozed, let until = item.snoozedUntilMs {
            parts.append(String(localized: "until \(MedicationFormatting.timeText(ms: until))"))
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onSelect) {
                HStack(spacing: 12) {
                    MedicationIconBubble(medication: medication)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(medication.displayName)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(doseLine)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                        DoseStatusBadge(status: item.status, isLate: item.isLate)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint(Text("Opens dose options"))

            if isOpen {
                Button(action: onTake) {
                    Text("Take")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.small)
                .accessibilityLabel(Text("Take \(medication.displayName)"))
                .accessibilityIdentifier("medications.take.\(item.id)")

                Menu {
                    Button(action: onSkip) { Label("Skip", systemImage: "forward") }
                    if canSnooze {
                        ForEach(MedicationSettings.snoozeOptions, id: \.self) { minutes in
                            Button { onSnooze(minutes) } label: {
                                Label(String(localized: "Snooze \(minutes) min"), systemImage: "clock")
                            }
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 20))
                        .foregroundStyle(AppColors.calorie)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(Text("More options for \(medication.displayName)"))
                .accessibilityIdentifier("medications.more.\(item.id)")
            }
        }
        .padding(.vertical, 4)
        // Contain, so the Take / More buttons keep their own identifiers.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("medications.dose.\(item.id)")
    }
}

/// Section header used on the Medications screens (dynamic titles, unlike `RecordsSectionTitle`).
struct MedicationSectionHeader: View {
    let title: String
    var systemImage: String?
    var trailing: String?

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage).foregroundStyle(AppColors.calorie)
            }
            Text(title)
                .font(.system(.headline, design: .rounded))
            if let trailing {
                Text(trailing)
                    .font(.system(.caption, design: .rounded, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(AppColors.calorie, in: Capsule())
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Footer disclaimer (docs §17), shown on the home, detail and history screens.
struct MedicationDisclaimerFooter: View {
    var body: some View {
        Text(MedicationFormatting.disclaimer)
            .font(.system(.caption2, design: .rounded))
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("medications.disclaimer")
    }
}
