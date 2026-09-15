import Foundation
import HealthKit

/// Builds `HKUnit`s for the registry's unit strings through the typed API instead of
/// `HKUnit(from:)`. An unparseable string makes `HKUnit(from:)` raise an Objective-C
/// exception that Swift cannot catch, so composite spellings (`mL/min·kg`, `kcal/hr·kg`)
/// whose HealthKit grammar is unverified must never reach the parser at runtime.
nonisolated enum HealthKitUnits {
    /// Returns nil for strings this table does not know; callers store the raw
    /// quantity description instead of crashing.
    static func unit(for string: String) -> HKUnit? {
        switch string {
        case "count": return .count()
        case "m": return .meter()
        case "kcal": return .kilocalorie()
        case "kcal/d": return HKUnit.kilocalorie().unitDivided(by: .day())
        case "count/min": return HKUnit.count().unitDivided(by: .minute())
        case "count/hr": return HKUnit.count().unitDivided(by: .hour())
        case "ms": return .secondUnit(with: .milli)
        case "%": return .percent()
        case "degC": return .degreeCelsius()
        case "mmHg": return .millimeterOfMercury()
        case "mmol/L", "mmol<180.1558800000541>/L":
            return HKUnit.moleUnit(with: .milli, molarMass: HKUnitMolarMassBloodGlucose).unitDivided(by: .liter())
        case "mg/dL": return HKUnit.gramUnit(with: .milli).unitDivided(by: .literUnit(with: .deci))
        case "kg": return .gramUnit(with: .kilo)
        case "s": return .second()
        case "L": return .liter()
        case "mL": return .literUnit(with: .milli)
        case "g": return .gram()
        case "mg": return .gramUnit(with: .milli)
        case "mcg": return .gramUnit(with: .micro)
        case "m/s": return HKUnit.meter().unitDivided(by: .second())
        case "W": return .watt()
        case "dBASPL": return .decibelAWeightedSoundPressureLevel()
        // Canonical spelling and the registry's HealthKit spelling (`mL/(kg*min)`, `kcal/(kg*hr)`).
        case "mL/min·kg", "mL/(kg*min)":
            return HKUnit.literUnit(with: .milli)
                .unitDivided(by: HKUnit.minute().unitMultiplied(by: .gramUnit(with: .kilo)))
        case "kcal/hr·kg", "kcal/(kg*hr)":
            return HKUnit.kilocalorie()
                .unitDivided(by: HKUnit.hour().unitMultiplied(by: .gramUnit(with: .kilo)))
        case "IU": return .internationalUnit()
        case "L/min": return HKUnit.liter().unitDivided(by: .minute())
        case "mcS": return .siemenUnit(with: .micro)
        case "appleEffortScore":
            if #available(iOS 18, *) { return .appleEffortScore() }
            return nil
        default:
            return nil
        }
    }
}
