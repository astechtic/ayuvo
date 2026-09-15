import Foundation

/// Glucose display unit. Stored under the cloud-backed `healthGlucoseUnit` preference;
/// the locale default is mg/dL for the US and mmol/L elsewhere.
enum HealthGlucoseUnit: String, CaseIterable, Identifiable {
    case mmolPerLiter = "mmol/L"
    case mgPerDeciliter = "mg/dL"

    static let storageKey = "healthGlucoseUnit"
    static let mgPerMmol = 18.0182

    var id: String { rawValue }

    static func current(defaults: UserDefaults = .standard, locale: Locale = .current) -> HealthGlucoseUnit {
        if let raw = defaults.string(forKey: storageKey), let unit = HealthGlucoseUnit(rawValue: raw) {
            return unit
        }
        let region = locale.region?.identifier ?? locale.identifier
        return region.hasSuffix("US") ? .mgPerDeciliter : .mmolPerLiter
    }
}

/// Display formatting for canonical health values. Canonical storage units never change;
/// only presentation follows the app's preferences (`weightUnit`, `heightUnit`, `waterUnit`,
/// `healthGlucoseUnit`). Main-actor by default like the rest of the view helpers.
enum HealthUnitFormatting {
    struct Display: Equatable {
        var value: String
        var unit: String
        var text: String { unit.isEmpty ? value : "\(value) \(unit)" }
    }

    private static let lengthTypeIDs: Set<String> = ["height", "waist_circumference", "walking_step_length", "running_stride_length", "running_vertical_oscillation", "underwater_depth", "six_minute_walk_distance"]

    static var usesMetricLength: Bool { HeightUnit.current == .cm }
    static var usesMetricMass: Bool { WeightUnit.current == .kg }
    static var waterUnit: WaterUnit {
        WaterUnit(rawValue: UserDefaults.standard.string(forKey: WaterSettings.unitKey) ?? "") ?? .defaultUnit
    }

    /// Unit label shown next to values of `type` (after conversion).
    static func unitLabel(for type: HealthMetricType) -> String {
        switch type.unit {
        case "kg": return usesMetricMass ? "kg" : "lb"
        case "m":
            if lengthTypeIDs.contains(type.id) { return usesMetricLength ? "cm" : "in" }
            return usesMetricLength ? "km" : "mi"
        case "degC": return usesMetricLength ? "°C" : "°F"
        case "mmol/L": return HealthGlucoseUnit.current().rawValue
        case "mL": return type.id == "hydration" ? waterUnit.symbol : "mL"
        case "count/min": return type.category == .heart ? "bpm" : "/min"
        case "count/hr": return "/hr"
        case "count": return type.id == "steps" ? String(localized: "steps_unit", defaultValue: "steps") : ""
        case "s": return ""
        case "m/s": return usesMetricLength ? "km/h" : "mph"
        case "dBASPL": return "dB"
        case "mL/min·kg": return "mL/kg·min"
        case "mcS": return "µS"
        case "%": return "%"
        default: return type.unit
        }
    }

    /// Converts a canonical value into the display unit's scale.
    static func convertedValue(_ value: Double, type: HealthMetricType) -> Double {
        switch type.unit {
        case "kg": return usesMetricMass ? value : value * 2.20462
        case "m":
            if lengthTypeIDs.contains(type.id) { return usesMetricLength ? value * 100 : value * 39.3700787 }
            return usesMetricLength ? value / 1000 : value / 1609.344
        case "degC": return usesMetricLength ? value : value * 9 / 5 + 32
        case "mmol/L": return HealthGlucoseUnit.current() == .mmolPerLiter ? value : value * HealthGlucoseUnit.mgPerMmol
        case "mL": return type.id == "hydration" ? waterUnit.displayAmount(forMilliliters: Int(value.rounded())) : value
        case "m/s": return usesMetricLength ? value * 3.6 : value * 2.23694
        default: return value
        }
    }

    /// Value + unit for `type`. Durations render as `7 h 32 min`; heights as `5′11″` when imperial.
    static func display(_ value: Double, type: HealthMetricType) -> Display {
        if type.unit == "s" {
            return Display(value: durationText(seconds: value), unit: "")
        }
        if type.id == "height", !usesMetricLength {
            let totalInches = value * 39.3700787
            let feet = Int(totalInches / 12)
            let inches = Int((totalInches - Double(feet) * 12).rounded())
            return Display(value: "\(feet)′\(inches)″", unit: "")
        }
        let converted = convertedValue(value, type: type)
        return Display(value: number(converted, type: type), unit: unitLabel(for: type))
    }

    static func text(_ value: Double?, type: HealthMetricType) -> String {
        guard let value, value.isFinite else { return "—" }
        return display(value, type: type).text
    }

    /// Blood pressure reads as `120/80`.
    static func bloodPressureText(systolic: Double?, diastolic: Double?) -> String {
        guard let systolic else { return "—" }
        let top = number(systolic, fractionDigits: 0)
        guard let diastolic else { return top }
        return "\(top)/\(number(diastolic, fractionDigits: 0))"
    }

    static func number(_ value: Double, type: HealthMetricType) -> String {
        let digits: Int
        switch type.unit {
        case "count", "kcal", "mmHg", "ms", "dBASPL", "count/min", "W":
            digits = 0
        case "kg", "%", "degC", "m", "m/s", "mL/min·kg", "L", "L/min":
            digits = abs(value) >= 100 ? 0 : 1
        case "mmol/L":
            digits = HealthGlucoseUnit.current() == .mmolPerLiter ? 1 : 0
        default:
            digits = abs(value) >= 100 ? 0 : (abs(value) >= 10 ? 1 : 2)
        }
        return number(value, fractionDigits: digits)
    }

    static func number(_ value: Double, fractionDigits: Int) -> String {
        value.formatted(.number.precision(.fractionLength(0...fractionDigits)))
    }

    /// `7 h 32 min`, `45 min`, `30 s`.
    static func durationText(seconds: Double) -> String {
        let total = Int(seconds.rounded())
        if total < 60 { return String(localized: "\(total) s") }
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        if hours == 0 { return String(localized: "\(minutes) min") }
        if minutes == 0 { return String(localized: "\(hours) h") }
        return String(localized: "\(hours) h \(minutes) min")
    }

    static func relativeText(_ date: Date, now: Date = Date()) -> String {
        let interval = now.timeIntervalSince(date)
        if interval < 60 { return String(localized: "Just now") }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: now)
    }

    static func dayText(_ day: String, calendar: Calendar = .current) -> String {
        guard let date = HealthChartSeriesBuilder.dayDate(day, calendar: calendar) else { return day }
        return date.formatted(.dateTime.month(.abbreviated).day().year())
    }

    static func byteCountText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
