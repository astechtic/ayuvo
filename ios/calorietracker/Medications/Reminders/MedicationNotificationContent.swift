import Foundation
import UserNotifications

/// Builds the notification for one planned reminder (docs §16): title = name + strength, body =
/// "Take 1 tablet · 8:00 PM · with food", category `medication.dose` with Taken / Skip / Snooze.
nonisolated enum MedicationNotificationContent {
    static let categoryIdentifier = "medication.dose"
    static let threadIdentifier = "medications"
    static let takenActionIdentifier = "medication.taken"
    static let skipActionIdentifier = "medication.skip"
    static let snoozeActionIdentifier = "medication.snooze"

    enum UserInfoKey {
        static let medicationID = "medicationID"
        static let scheduleID = "scheduleID"
        static let scheduledAtMs = "scheduledAtMs"
        static let snooze = "snooze"
    }

    /// "1 tablet", "½ tablet", "2 capsules", "5 mL" — quantity + unit for the body line.
    static func doseText(quantity: Double, unit: DoseUnit) -> String {
        let amount: String
        if abs(quantity - 0.5) < 0.0001 {
            amount = "½"
        } else if quantity == quantity.rounded(), abs(quantity) < 1_000_000 {
            amount = String(Int(quantity))
        } else {
            amount = quantity.formatted(.number.precision(.fractionLength(0...2)))
        }
        return "\(amount) \(unitLabel(unit, quantity: quantity))"
    }

    private static func unitLabel(_ unit: DoseUnit, quantity: Double) -> String {
        let plural = quantity > 1
        switch unit {
        case .tablet: return plural ? String(localized: "tablets") : String(localized: "tablet")
        case .capsule: return plural ? String(localized: "capsules") : String(localized: "capsule")
        case .ml: return String(localized: "mL")
        case .mg: return String(localized: "mg")
        case .g: return String(localized: "g")
        case .mcg: return String(localized: "mcg")
        case .drop: return plural ? String(localized: "drops") : String(localized: "drop")
        case .puff: return plural ? String(localized: "puffs") : String(localized: "puff")
        case .unit: return plural ? String(localized: "units") : String(localized: "unit")
        case .sachet: return plural ? String(localized: "sachets") : String(localized: "sachet")
        case .application: return plural ? String(localized: "applications") : String(localized: "application")
        case .other: return plural ? String(localized: "doses") : String(localized: "dose")
        }
    }

    static func timeText(_ ms: Int64, zone: TimeZone = .current) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        var style = Date.FormatStyle.dateTime.hour().minute()
        style.timeZone = zone
        return date.formatted(style)
    }

    /// "Take 1 tablet · 8:00 PM · with food" (food relation omitted for `anytime`).
    static func body(medication: Medication, scheduledAtMs: Int64, zone: TimeZone = .current) -> String {
        let dose = doseText(quantity: medication.doseQuantity, unit: medication.doseUnit)
        let time = timeText(scheduledAtMs, zone: zone)
        let take = String(localized: "Take \(dose) · \(time)")
        guard medication.foodRelation != .anytime else { return take }
        return take + " · " + medication.foodRelation.title.lowercased()
    }

    static func title(medication: Medication) -> String {
        medication.displayName.trimmingCharacters(in: .whitespaces)
    }

    static func content(for reminder: PlannedReminder, medication: Medication, zone: TimeZone = .current) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = title(medication: medication)
        content.body = body(medication: medication, scheduledAtMs: reminder.scheduledAtMs, zone: zone)
        content.categoryIdentifier = categoryIdentifier
        content.threadIdentifier = threadIdentifier
        content.interruptionLevel = .timeSensitive
        content.sound = .default
        var info: [String: Any] = [
            UserInfoKey.medicationID: reminder.medicationID,
            UserInfoKey.scheduledAtMs: NSNumber(value: reminder.scheduledAtMs),
            UserInfoKey.snooze: reminder.isSnooze,
        ]
        if let scheduleID = reminder.scheduleID { info[UserInfoKey.scheduleID] = scheduleID }
        content.userInfo = info
        return content
    }

    /// The three actions, in the contract's order (docs §16).
    static func category() -> UNNotificationCategory {
        let taken = UNNotificationAction(
            identifier: takenActionIdentifier,
            title: String(localized: "Taken"),
            options: [],
            icon: UNNotificationActionIcon(systemImageName: "checkmark")
        )
        let skip = UNNotificationAction(
            identifier: skipActionIdentifier,
            title: String(localized: "Skip"),
            options: [],
            icon: UNNotificationActionIcon(systemImageName: "xmark")
        )
        let snooze = UNNotificationAction(
            identifier: snoozeActionIdentifier,
            title: String(localized: "Snooze"),
            options: [],
            icon: UNNotificationActionIcon(systemImageName: "clock")
        )
        return UNNotificationCategory(
            identifier: categoryIdentifier,
            actions: [taken, skip, snooze],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
    }
}
