import SwiftUI

/// Editable list of `HH:mm` dose times: one compact hour-and-minute `DatePicker` per row
/// (the `hour/minute ⇄ Date` binding of `NotificationTimeRow`), swipe to delete, "Add time".
struct MedicationTimesEditor: View {
    @Binding var times: [String]

    var body: some View {
        ForEach(Array(times.enumerated()), id: \.offset) { index, _ in
            HStack {
                Label {
                    Text(index == 0 ? String(localized: "Time") : String(localized: "Time \(index + 1)"))
                } icon: {
                    Image(systemName: "clock").foregroundStyle(AppColors.calorie)
                }
                Spacer()
                DatePicker("", selection: binding(for: index), displayedComponents: .hourAndMinute)
                    .datePickerStyle(.compact)
                    .labelsHidden()
                    .accessibilityLabel(Text("Time \(index + 1)"))
                    .accessibilityIdentifier("medications.form.time.\(index)")
            }
        }
        .onDelete { offsets in
            times.remove(atOffsets: offsets)
        }
        if times.count < MR.maxTimes {
            Button {
                addTime()
            } label: {
                Label("Add time", systemImage: "plus.circle")
            }
            .accessibilityIdentifier("medications.form.addTime")
        }
    }

    private func binding(for index: Int) -> Binding<Date> {
        Binding(
            get: {
                guard times.indices.contains(index) else { return Date() }
                return MedicationFormatting.date(hhmm: times[index]) ?? Date()
            },
            set: { newDate in
                guard times.indices.contains(index) else { return }
                times[index] = MedicationFormatting.hhmm(from: newDate)
            }
        )
    }

    /// Next free slot four hours after the last one (wrapping), so consecutive taps do not collide.
    private func addTime() {
        let last = times.compactMap(MR.parseHHMM).max { ($0.0, $0.1) < ($1.0, $1.1) }
        var hour = (last?.0 ?? 4) + 4
        let minute = last?.1 ?? 0
        var candidate = MR.fmtHHMM(hour % 24, minute)
        var attempts = 0
        while times.contains(candidate), attempts < 24 {
            hour += 1
            candidate = MR.fmtHHMM(hour % 24, minute)
            attempts += 1
        }
        times.append(candidate)
    }
}

/// Seven toggles (Mon…Sun) for weekly schedules.
struct MedicationWeekdayPicker: View {
    @Binding var days: [Int]
    private let calendar = Calendar.current

    var body: some View {
        HStack(spacing: 6) {
            ForEach(1...7, id: \.self) { day in
                let selected = days.contains(day)
                Button {
                    if selected { days.removeAll { $0 == day } } else { days.append(day) }
                    days.sort()
                } label: {
                    Text(MedicationFormatting.weekdayName(iso: day, calendar: calendar).prefix(2))
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(selected ? AppColors.calorie : AppColors.calorie.opacity(0.12), in: Capsule())
                        .foregroundStyle(selected ? Color.white : AppColors.calorie)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(MedicationFormatting.weekdayName(iso: day, calendar: calendar)))
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("medications.form.day.\(day)")
            }
        }
    }
}
