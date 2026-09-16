import Foundation
import SwiftUI

/// Display helpers shared by the Medications screens (docs/medications.md §16: copy is descriptive,
/// never a recommendation).
enum MedicationFormatting {
    // MARK: Dose

    /// "1 tablet", "2 capsules", "½ tablet", "5 mL".
    static func doseText(quantity: Double, unit: DoseUnit) -> String {
        let amount = quantityText(quantity)
        switch unit {
        case .tablet, .capsule, .drop, .puff, .unit, .sachet, .application:
            if let count = integralCount(quantity) {
                return countedUnit(count, unit)
            }
            return "\(amount) \(unit.title.lowercased())"
        case .ml, .mg, .g, .mcg:
            return "\(amount) \(unit.title)"
        case .other:
            return amount
        }
    }

    static func doseText(_ medication: Medication) -> String {
        doseText(quantity: medication.doseQuantity, unit: medication.doseUnit)
    }

    static func quantityText(_ quantity: Double) -> String {
        switch quantity {
        case 0.25: return "¼"
        case 0.5: return "½"
        case 0.75: return "¾"
        default:
            let formatter = NumberFormatter()
            formatter.numberStyle = .decimal
            formatter.maximumFractionDigits = 2
            formatter.minimumFractionDigits = 0
            return formatter.string(from: NSNumber(value: quantity)) ?? String(quantity)
        }
    }

    private static func integralCount(_ quantity: Double) -> Int? {
        guard quantity == quantity.rounded(), quantity >= 0, quantity < 10_000 else { return nil }
        return Int(quantity)
    }

    private static func countedUnit(_ count: Int, _ unit: DoseUnit) -> String {
        switch unit {
        case .tablet: count == 1 ? String(localized: "1 tablet") : String(localized: "\(count) tablets")
        case .capsule: count == 1 ? String(localized: "1 capsule") : String(localized: "\(count) capsules")
        case .drop: count == 1 ? String(localized: "1 drop") : String(localized: "\(count) drops")
        case .puff: count == 1 ? String(localized: "1 puff") : String(localized: "\(count) puffs")
        case .unit: count == 1 ? String(localized: "1 unit") : String(localized: "\(count) units")
        case .sachet: count == 1 ? String(localized: "1 sachet") : String(localized: "\(count) sachets")
        case .application: count == 1 ? String(localized: "1 application") : String(localized: "\(count) applications")
        default: "\(count)"
        }
    }

    /// "1 medication" / "3 medications" (explicit variants; the string catalog can add plural rules per locale).
    static func medicationCount(_ count: Int) -> String {
        count == 1 ? String(localized: "1 medication") : String(localized: "\(count) medications")
    }

    static func doseCount(_ count: Int) -> String {
        count == 1 ? String(localized: "1 dose") : String(localized: "\(count) doses")
    }

    // MARK: Time and dates

    static func date(ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    /// "8:00 PM" in the user's locale.
    static func timeText(ms: Int64) -> String {
        date(ms: ms).formatted(date: .omitted, time: .shortened)
    }

    /// "8:00 PM" for an `HH:mm` wall-clock string.
    static func timeText(hhmm: String) -> String {
        guard let date = date(hhmm: hhmm) else { return hhmm }
        return date.formatted(date: .omitted, time: .shortened)
    }

    /// Today at `HH:mm` (used by pickers and time labels).
    static func date(hhmm: String, calendar: Calendar = .current) -> Date? {
        guard let (hour, minute) = MR.parseHHMM(hhmm) else { return nil }
        return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: Date())
    }

    static func hhmm(from date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.hour, .minute], from: date)
        return MR.fmtHHMM(components.hour ?? 0, components.minute ?? 0)
    }

    /// "Sep 16, 2026" for a `yyyy-MM-dd` day.
    static func dateText(iso: String) -> String {
        guard let date = RecordDates.date(fromDay: iso) else { return iso }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    /// "Tue, Sep 16" for a `yyyy-MM-dd` day; "Today" / "Yesterday" when that is what it is.
    static func dayTitle(iso: String, today: String) -> String {
        if iso == today { return String(localized: "Today") }
        guard let date = RecordDates.date(fromDay: iso) else { return iso }
        if let todayDate = RecordDates.date(fromDay: today),
           Calendar.current.date(byAdding: .day, value: -1, to: todayDate).map({ Calendar.current.isDate($0, inSameDayAs: date) }) == true {
            return String(localized: "Yesterday")
        }
        return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }

    static func isoDay(_ date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", components.year ?? 1970, components.month ?? 1, components.day ?? 1)
    }

    static func dateFromISO(_ iso: String, calendar: Calendar = .current) -> Date? {
        RecordDates.date(fromDay: iso)
    }

    /// "2 h ago", "just now", "in 30 min".
    static func relativeText(ms: Int64, now: Date = .now) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        let target = date(ms: ms)
        if abs(target.timeIntervalSince(now)) < 60 { return String(localized: "just now") }
        return formatter.localizedString(for: target, relativeTo: now)
    }

    static func weekdayName(iso: Int, calendar: Calendar = .current) -> String {
        let symbols = calendar.shortWeekdaySymbols // index 0 = Sunday
        let index = iso % 7
        return symbols.indices.contains(index) ? symbols[index] : String(iso)
    }

    // MARK: Schedule

    /// "Twice daily · 8:00 AM, 8:00 PM", "Mon, Wed, Fri · 8:00 AM", "Every 8 hours from 8:00 AM",
    /// "As needed".
    static func scheduleText(_ schedule: MedicationSchedule?, isPRN: Bool) -> String {
        if isPRN { return String(localized: "As needed") }
        guard let schedule else { return String(localized: "No schedule") }
        let times = schedule.times.map { timeText(hhmm: $0) }.joined(separator: ", ")
        switch schedule.frequency {
        case .daily:
            return "\(dailyLabel(count: schedule.times.count)) · \(times)"
        case .weekly:
            let days = schedule.days.map { weekdayName(iso: $0) }.joined(separator: ", ")
            return "\(days) · \(times)"
        case .interval:
            let hours = schedule.intervalHours ?? 0
            let anchor = timeText(hhmm: schedule.anchorTime ?? "08:00")
            return String(localized: "Every \(hours) hours from \(anchor)")
        }
    }

    static func dailyLabel(count: Int) -> String {
        switch count {
        case 1: String(localized: "Once daily")
        case 2: String(localized: "Twice daily")
        case 3: String(localized: "Three times daily")
        case 4: String(localized: "Four times daily")
        default: String(localized: "\(count) times daily")
        }
    }

    /// "Metformin 500 mg · 1 tablet · Twice daily"
    static func summaryLine(_ medication: Medication, schedule: MedicationSchedule?) -> String {
        [doseText(medication), scheduleText(schedule, isPRN: medication.isPRN)].joined(separator: " · ")
    }

    // MARK: Adherence

    /// "18 / 21 doses taken · 86%"
    static func adherenceText(_ adherence: MedicationAdherence) -> String {
        guard adherence.hasData else { return String(localized: "No scheduled doses in the last 7 days") }
        return String(localized: "\(adherence.taken) / \(adherence.expected) doses taken · \(adherence.percent)%")
    }

    // MARK: Errors

    /// Gentle copy for a reference error code (docs §11).
    static func actionErrorText(_ code: String) -> String {
        switch code {
        case "already_taken": String(localized: "This dose is already marked as taken.")
        case "not_due_yet": String(localized: "You can snooze a dose once it is due.")
        case "dose_missed": String(localized: "This dose is past its window, so it can't be snoozed. You can still mark it taken or skipped.")
        case "already_resolved": String(localized: "This dose has already been recorded.")
        case "taken_at_in_future": String(localized: "Choose a time that has already passed.")
        case "bad_snooze": String(localized: "Choose 10, 30 or 60 minutes.")
        case "cannot_undo_missed": String(localized: "A missed dose can't be undone. Mark it taken or skipped instead.")
        case "not_prn": String(localized: "Only as-needed medicines can be logged this way.")
        case "not_active": String(localized: "This medicine is not active.")
        case "invalid_transition": String(localized: "That change isn't available in the medicine's current state.")
        case "not_open": String(localized: "Medications couldn't be opened. Try again.")
        default: String(localized: "Something went wrong. Try again.")
        }
    }

    /// Fixed disclaimer shown on the home footer, detail and history (docs §17).
    static let disclaimer = String(localized: "Ayuvo helps you track the medicines you choose to add and reminds you when a dose is due. It does not give medical advice. If you miss a dose or are unsure about anything, ask your doctor or pharmacist.")
}
