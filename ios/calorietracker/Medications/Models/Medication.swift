import Foundation

// Enumerations stored as the exact lowercase strings of `docs/medications.md` §3.
// Every enum has a lenient `init(raw:)` so an unknown stored value degrades to a safe default
// instead of failing the whole row (same rule as `RecordEnums.swift`).

nonisolated enum MedicationForm: String, CaseIterable, Codable, Sendable, Identifiable {
    case tablet
    case capsule
    case syrup
    case injection
    case cream
    case drops
    case inhaler
    case other

    var id: String { rawValue }

    init(raw: String?) {
        self = raw.flatMap(MedicationForm.init(rawValue:)) ?? .other
    }

    var title: String {
        switch self {
        case .tablet: String(localized: "Tablet")
        case .capsule: String(localized: "Capsule")
        case .syrup: String(localized: "Syrup")
        case .injection: String(localized: "Injection")
        case .cream: String(localized: "Cream")
        case .drops: String(localized: "Drops")
        case .inhaler: String(localized: "Inhaler")
        case .other: String(localized: "Other")
        }
    }

    var systemImage: String {
        switch self {
        case .tablet: "pills.fill"
        case .capsule: "capsule.fill"
        case .syrup: "waterbottle.fill"
        case .injection: "syringe.fill"
        // "tube.fill" is not an SF Symbol, so the Form menu showed no icon.
        case .cream: "hands.and.sparkles.fill"
        case .drops: "drop.fill"
        case .inhaler: "lungs.fill"
        case .other: "cross.case.fill"
        }
    }

    /// Default `dose_unit` per form (docs §3).
    var defaultUnit: DoseUnit {
        switch self {
        case .tablet: .tablet
        case .capsule: .capsule
        case .drops: .drop
        case .inhaler: .puff
        case .injection: .unit
        case .cream: .application
        case .syrup: .ml
        case .other: .unit
        }
    }
}

nonisolated enum FoodRelation: String, CaseIterable, Codable, Sendable, Identifiable {
    case before
    case with
    case after
    case anytime

    var id: String { rawValue }

    init(raw: String?) {
        self = raw.flatMap(FoodRelation.init(rawValue:)) ?? .anytime
    }

    var title: String {
        switch self {
        case .before: String(localized: "Before food")
        case .with: String(localized: "With food")
        case .after: String(localized: "After food")
        case .anytime: String(localized: "Anytime")
        }
    }
}

nonisolated enum MedicationStatus: String, CaseIterable, Codable, Sendable, Identifiable {
    case active
    case paused
    case completed
    case stopped

    var id: String { rawValue }

    init(raw: String?) {
        self = raw.flatMap(MedicationStatus.init(rawValue:)) ?? .active
    }

    var title: String {
        switch self {
        case .active: String(localized: "Active")
        case .paused: String(localized: "Paused")
        case .completed: String(localized: "Completed")
        case .stopped: String(localized: "Stopped")
        }
    }

    var systemImage: String {
        switch self {
        case .active: "checkmark.circle"
        case .paused: "pause.circle"
        case .completed: "flag.checkered"
        case .stopped: "stop.circle"
        }
    }

    /// Stopped and completed are final (docs §3); the UI offers "Add again" instead.
    var isFinal: Bool { self == .completed || self == .stopped }
}

nonisolated enum DoseUnit: String, CaseIterable, Codable, Sendable, Identifiable {
    case tablet
    case capsule
    case ml
    case mg
    case g
    case mcg
    case drop
    case puff
    case unit
    case sachet
    case application
    case other

    var id: String { rawValue }

    init(raw: String?) {
        self = raw.flatMap(DoseUnit.init(rawValue:)) ?? .other
    }

    var title: String {
        switch self {
        case .tablet: String(localized: "Tablet")
        case .capsule: String(localized: "Capsule")
        case .ml: String(localized: "mL")
        case .mg: String(localized: "mg")
        case .g: String(localized: "g")
        case .mcg: String(localized: "mcg")
        case .drop: String(localized: "Drop")
        case .puff: String(localized: "Puff")
        case .unit: String(localized: "Unit")
        case .sachet: String(localized: "Sachet")
        case .application: String(localized: "Application")
        case .other: String(localized: "Other")
        }
    }
}

/// Owner-driven lifecycle transitions (docs §12); `edit_schedule` is a store call, not an action.
nonisolated enum LifecycleAction: String, CaseIterable, Codable, Sendable {
    case pause
    case resume
    case stop
    case complete
}

nonisolated enum MedicationDates {
    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// One row of `medications` (schema v1). Dates are `yyyy-MM-dd` device-local days; times are epoch ms.
nonisolated struct Medication: Identifiable, Hashable, Sendable, Codable {
    var id: String
    var name: String
    var genericName: String?
    var brandName: String?
    var strength: String?
    var form: MedicationForm
    var doseQuantity: Double
    var doseUnit: DoseUnit
    var foodRelation: FoodRelation
    var instructions: String?
    var startDate: String
    var endDate: String?
    var status: MedicationStatus
    var isPRN: Bool
    var photoPath: String?
    var relatedRecordID: String?
    var createdMs: Int64
    var updatedMs: Int64

    init(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        genericName: String? = nil,
        brandName: String? = nil,
        strength: String? = nil,
        form: MedicationForm = .other,
        doseQuantity: Double = 1,
        doseUnit: DoseUnit = .tablet,
        foodRelation: FoodRelation = .anytime,
        instructions: String? = nil,
        startDate: String,
        endDate: String? = nil,
        status: MedicationStatus = .active,
        isPRN: Bool = false,
        photoPath: String? = nil,
        relatedRecordID: String? = nil,
        createdMs: Int64,
        updatedMs: Int64
    ) {
        self.id = id
        self.name = name
        self.genericName = genericName
        self.brandName = brandName
        self.strength = strength
        self.form = form
        self.doseQuantity = doseQuantity
        self.doseUnit = doseUnit
        self.foodRelation = foodRelation
        self.instructions = instructions
        self.startDate = startDate
        self.endDate = endDate
        self.status = status
        self.isPRN = isPRN
        self.photoPath = photoPath
        self.relatedRecordID = relatedRecordID
        self.createdMs = createdMs
        self.updatedMs = updatedMs
    }

    /// "Metformin 500 mg" — name plus strength when present.
    var displayName: String {
        guard let strength, !strength.isEmpty else { return name }
        return "\(name) \(strength)"
    }
}
