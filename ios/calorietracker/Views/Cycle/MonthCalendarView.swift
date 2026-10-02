import SwiftUI

/// A month grid of local days (reusable; docs/cycle-tracking.md §5). Days are engine ordinals. Each day shows its
/// number plus shapes for the cycle phase (filled circle = logged period, dashed circle = estimated period, tinted
/// background = fertile window, ring with dot = ovulation, bold outline = today, small dot = something logged), and
/// reads out a full sentence to VoiceOver.
struct MonthCalendarView: View {
    /// Any day in the month to show.
    let month: Int
    let statuses: [Int: CycleDayStatus]
    let today: Int
    var loggedDays: Set<Int> = []
    var flows: [Int: String] = [:]
    var showFertility = true
    var calendar: Calendar = .current
    let onSelect: (Int) -> Void

    private var monthStart: Int {
        let text = CycleDay.string(month)
        return CycleDay.o(String(text.prefix(8)) + "01")
    }

    private var daysInMonth: Int {
        let date = CycleDates.date(ordinal: monthStart, calendar: calendar)
        return calendar.range(of: .day, in: .month, for: date)?.count ?? 30
    }

    /// Blank cells before day 1 for the calendar's first weekday.
    private var leading: Int {
        let date = CycleDates.date(ordinal: monthStart, calendar: calendar)
        let weekday = calendar.component(.weekday, from: date)
        return (weekday - calendar.firstWeekday + 7) % 7
    }

    private var weekdaySymbols: [String] {
        let symbols = calendar.veryShortStandaloneWeekdaySymbols
        let first = calendar.firstWeekday - 1
        return Array(symbols[first...] + symbols[..<first])
    }

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 7)
        VStack(spacing: 6) {
            LazyVGrid(columns: columns, spacing: 2) {
                ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, symbol in
                    Text(symbol)
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .accessibilityHidden(true)
                }
            }
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(0..<(leading + daysInMonth), id: \.self) { index in
                    if index < leading {
                        Color.clear.frame(height: 44).accessibilityHidden(true)
                    } else {
                        let day = monthStart + index - leading
                        dayCell(day)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func dayCell(_ day: Int) -> some View {
        let status = statuses[day]
        let phase = status?.phase ?? "unknown"
        let isToday = day == today
        let future = day > today
        let number = Int(CycleDay.string(day).suffix(2)) ?? 0
        Button {
            onSelect(day)
        } label: {
            ZStack {
                if showFertility && (phase == "fertile" || phase == "ovulation") {
                    RoundedRectangle(cornerRadius: 8, style: .continuous).fill(CycleStyle.fertile.opacity(0.18))
                }
                if phase == "period" {
                    Circle().fill(CycleStyle.period).padding(4)
                } else if phase == "predicted_period" || phase == "late" {
                    Circle().strokeBorder(CycleStyle.period, style: StrokeStyle(lineWidth: 1.5, dash: [3, 2])).padding(4)
                } else if showFertility && phase == "ovulation" {
                    Circle().strokeBorder(CycleStyle.fertile, lineWidth: 2).padding(4)
                }
                VStack(spacing: 1) {
                    Text("\(number)")
                        .font(.system(.callout, design: .rounded, weight: isToday ? .bold : .regular))
                        .foregroundStyle(phase == "period" ? Color.white : (future ? Color.secondary : Color.primary))
                    Circle()
                        .fill(loggedDays.contains(day) ? (phase == "period" ? Color.white : Color.secondary) : Color.clear)
                        .frame(width: 4, height: 4)
                }
                if isToday {
                    RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.primary, lineWidth: 2)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(accessibilityText(day: day, status: status, isToday: isToday)))
        .accessibilityAddTraits(isToday ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("cycle.calendar.day.\(CycleDay.string(day))")
    }

    private func accessibilityText(day: Int, status: CycleDayStatus?, isToday: Bool) -> String {
        var parts = [CycleDates.long(CycleDay.string(day))]
        if isToday { parts.append(String(localized: "today")) }
        if let status {
            var phase = status.phase
            if !showFertility && (phase == "fertile" || phase == "ovulation") { phase = "unknown" }
            if phase != "unknown" {
                if let pd = status.periodDay, phase == "period" || phase == "predicted_period" {
                    parts.append("\(CycleText.phase(phase)), \(String(localized: "day \(pd)", comment: "Cycle calendar: period day number"))")
                } else {
                    parts.append(CycleText.phase(phase))
                }
            }
        }
        if let flow = flows[day] { parts.append(String(localized: "\(CycleText.flow(flow)) flow", comment: "Cycle calendar: flow of a day, e.g. 'Heavy flow'")) }
        if loggedDays.contains(day) { parts.append(String(localized: "logged")) }
        return parts.joined(separator: ", ")
    }
}
