import Foundation
import SwiftUI

/// Apple Health "Browse" categories, in Apple's order. Mirrors
/// `shared/health/metric_registry.json` — the raw values are the contract.
nonisolated enum HealthCategory: String, CaseIterable, Sendable, Hashable, Codable, Identifiable {
    var id: String { rawValue }

    case activity
    case body
    case cycleTracking = "cycle_tracking"
    case hearing
    case heart
    case mentalWellbeing = "mental_wellbeing"
    case mobility
    case nutrition
    case respiratory
    case sleep
    case symptoms
    case vitals
    case other

    /// English display name; a literal `Localizable.xcstrings` entry exists for each.
    var englishName: String {
        switch self {
        case .activity: return "Activity"
        case .body: return "Body Measurements"
        case .cycleTracking: return "Cycle Tracking"
        case .hearing: return "Hearing"
        case .heart: return "Heart"
        case .mentalWellbeing: return "Mental Wellbeing"
        case .mobility: return "Mobility"
        case .nutrition: return "Nutrition"
        case .respiratory: return "Respiratory"
        case .sleep: return "Sleep"
        case .symptoms: return "Symptoms"
        case .vitals: return "Vitals"
        case .other: return "Other Data"
        }
    }

    var displayName: String {
        String(localized: String.LocalizationValue(englishName))
    }

    var systemImage: String {
        switch self {
        case .activity: return "flame.fill"
        case .body: return "figure.stand"
        case .cycleTracking: return "calendar"
        case .hearing: return "ear.fill"
        case .heart: return "heart.fill"
        case .mentalWellbeing: return "brain.head.profile"
        case .mobility: return "figure.walk.motion"
        case .nutrition: return "fork.knife"
        case .respiratory: return "lungs.fill"
        case .sleep: return "bed.double.fill"
        case .symptoms: return "stethoscope"
        case .vitals: return "waveform.path.ecg"
        case .other: return "square.grid.2x2.fill"
        }
    }

    /// Fixed domain colour (shared catalog `health.category_domains` → domain colour).
    var tint: Color {
        switch self {
        case .activity: return AyuvoPalette.activity
        case .body: return AyuvoPalette.body
        case .cycleTracking: return AyuvoPalette.cycle
        case .hearing: return AyuvoPalette.hearing
        case .heart: return AyuvoPalette.heart
        case .mentalWellbeing: return AyuvoPalette.mindfulness
        case .mobility: return AyuvoPalette.mobility
        case .nutrition: return AyuvoPalette.nutrition
        case .respiratory: return AyuvoPalette.respiratory
        case .sleep: return AyuvoPalette.sleep
        case .symptoms: return AyuvoPalette.symptoms
        case .vitals: return AyuvoPalette.vitals
        case .other: return AyuvoPalette.other
        }
    }
}

nonisolated enum HealthMetricKind: String, Sendable, Hashable, Codable {
    case cumulative
    case discrete
    case duration
    case category
    case session
    case series
}

nonisolated enum HealthAggregation: String, Sendable, Hashable, Codable {
    case sum = "SUM"
    case duration = "DURATION"
    case average = "AVERAGE"
    case minMax = "MIN_MAX"
    case latest = "LATEST"
    case count = "COUNT"
}

nonisolated enum HealthDayAttribution: String, Sendable, Hashable, Codable {
    case start
    case end
    case span
}

/// Which HealthKit object family a registry entry is read from.
nonisolated enum HealthObjectKind: String, Sendable, Hashable {
    case quantity
    case category
    case workout
    case correlation
    case stateOfMind
    case activitySummary
    /// Android-only / rollup-only; nothing to read on iOS.
    case virtual
}

nonisolated enum HealthMetricTier: Int, Sendable, Comparable {
    case t1 = 1
    case t2 = 2
    case t3 = 3

    static func < (lhs: HealthMetricTier, rhs: HealthMetricTier) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// One entry of the shared metric registry. `id` is the cross-platform slug used as
/// `health_samples.type_id`; `hkIdentifiers` are raw HealthKit identifier strings so
/// the catalog never needs `#available` gating — unknown identifiers resolve to nil at
/// runtime and are skipped.
nonisolated struct HealthMetricType: Hashable, Sendable, Identifiable {
    let id: String
    let category: HealthCategory
    let kind: HealthMetricKind
    let aggregation: HealthAggregation
    /// Canonical display / export unit string (`count`, `m`, `kcal`, `%`, ...).
    let unit: String
    /// HealthKit unit key, resolved through `HealthKitUnits.unit(for:)` (typed API, never `HKUnit(from:)`).
    let hkUnit: String
    let dayAttribution: HealthDayAttribution
    let hkIdentifiers: [String]
    let objectKind: HealthObjectKind
    /// False for `nutrition` / `dietary_*` (the food-diary export covers intake).
    let exported: Bool
    let englishName: String

    init(
        id: String,
        category: HealthCategory,
        kind: HealthMetricKind,
        aggregation: HealthAggregation,
        unit: String,
        hkUnit: String? = nil,
        dayAttribution: HealthDayAttribution = .start,
        hkIdentifiers: [String],
        objectKind: HealthObjectKind,
        exported: Bool = true,
        englishName: String
    ) {
        self.id = id
        self.category = category
        self.kind = kind
        self.aggregation = aggregation
        self.unit = unit
        self.hkUnit = hkUnit ?? HealthMetricType.defaultHKUnit(for: unit)
        self.dayAttribution = dayAttribution
        self.hkIdentifiers = hkIdentifiers
        self.objectKind = objectKind
        self.exported = exported
        self.englishName = englishName
    }

    var displayName: String {
        String(localized: String.LocalizationValue(englishName))
    }

    /// True when this platform has nothing to read (Android-only record types).
    var isAndroidOnly: Bool { objectKind == .virtual }

    var isSleep: Bool { id == "sleep" }
    var isBloodPressure: Bool { id == "blood_pressure" }
    var isPercent: Bool { unit == "%" }

    /// Canonical unit → HealthKit unit string. Percent is the only unit whose stored
    /// convention differs from HealthKit (HK stores fractions, we store 0–100).
    static func defaultHKUnit(for unit: String) -> String {
        switch unit {
        case "mmol/L": return "mmol<180.1558800000541>/L"
        default: return unit
        }
    }

    /// Canonical unit strings shared with Android. `days` and `none` are Android-only
    /// pseudo-units and never appear on an iOS entry.
    static let canonicalUnits: [String] = [
        "count", "m", "kcal", "kcal/d", "count/min", "ms", "%", "degC", "mmHg", "mmol/L", "kg", "s", "L", "mL",
        "g", "mg", "mcg", "m/s", "W", "dBASPL", "mL/min·kg", "kcal/hr·kg", "count/hr", "IU", "L/min", "mcS",
        "days", "none",
    ]
}
