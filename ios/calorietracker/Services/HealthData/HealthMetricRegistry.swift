import Foundation
import HealthKit

/// The iOS mirror of `shared/health/metric_registry.json`. Keyed by cross-platform slug
/// and by raw HealthKit identifier string. Object types are resolved through the
/// `HKObjectType` factories at runtime so an identifier unknown to the running OS
/// simply resolves to nil — no `#available`-gated literal catalogs.
nonisolated enum HealthMetricRegistry {
    static let all: [HealthMetricType] = entries

    static let byID: [String: HealthMetricType] = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })

    /// First registry entry claiming each HealthKit identifier.
    static let byHKIdentifier: [String: HealthMetricType] = {
        var map: [String: HealthMetricType] = [:]
        for type in all {
            for identifier in type.hkIdentifiers where map[identifier] == nil {
                map[identifier] = type
            }
        }
        return map
    }()

    static let quantityPrefix = "HKQuantityTypeIdentifier"
    static let categoryPrefix = "HKCategoryTypeIdentifier"
    static let characteristicPrefix = "HKCharacteristicTypeIdentifier"
    static let workoutIdentifier = "HKWorkoutTypeIdentifier"
    static let bloodPressureCorrelationIdentifier = "HKCorrelationTypeIdentifierBloodPressure"
    static let activitySummaryIdentifier = "HKActivitySummaryTypeIdentifier"
    static let stateOfMindIdentifier = "HKStateOfMindTypeIdentifier"

    /// Correlation types cannot be authorized; these two quantity types carry the grant.
    static let bloodPressureAuthorizationIdentifiers = [
        "HKQuantityTypeIdentifierBloodPressureSystolic",
        "HKQuantityTypeIdentifierBloodPressureDiastolic",
    ]

    /// Characteristics the hub reads in addition to the manager's date of birth / sex.
    static let characteristicIdentifiers = [
        "HKCharacteristicTypeIdentifierBloodType",
        "HKCharacteristicTypeIdentifierFitzpatrickSkinType",
        "HKCharacteristicTypeIdentifierWheelchairUse",
        "HKCharacteristicTypeIdentifierActivityMoveMode",
    ]

    static func type(id: String) -> HealthMetricType? { byID[id] }

    static func type(forHKIdentifier identifier: String) -> HealthMetricType? { byHKIdentifier[identifier] }

    /// Registry entries this platform can read (Android-only records excluded).
    static var iOSTypes: [HealthMetricType] { all.filter { !$0.isAndroidOnly } }

    /// Entries with at least one sample type the running OS knows — the sync plan.
    /// Activity summaries and state of mind have no `HKSample` representation and are
    /// handled separately (Phase 5), so they are not part of the anchored sync.
    static func syncableTypes() -> [HealthMetricType] {
        iOSTypes.filter { type in
            switch type.objectKind {
            case .quantity, .category, .workout, .correlation:
                return !sampleTypes(for: type).isEmpty
            case .stateOfMind, .activitySummary, .virtual:
                return false
            }
        }
    }

    // MARK: - Resolution

    static func objectType(forRawIdentifier raw: String) -> HKObjectType? {
        if raw.hasPrefix(quantityPrefix) {
            return HKObjectType.quantityType(forIdentifier: HKQuantityTypeIdentifier(rawValue: raw))
        }
        if raw.hasPrefix(categoryPrefix) {
            return HKObjectType.categoryType(forIdentifier: HKCategoryTypeIdentifier(rawValue: raw))
        }
        if raw.hasPrefix(characteristicPrefix) {
            return HKObjectType.characteristicType(forIdentifier: HKCharacteristicTypeIdentifier(rawValue: raw))
        }
        switch raw {
        case workoutIdentifier:
            return HKObjectType.workoutType()
        case bloodPressureCorrelationIdentifier:
            return HKObjectType.correlationType(forIdentifier: .bloodPressure)
        case activitySummaryIdentifier:
            return HKObjectType.activitySummaryType()
        case stateOfMindIdentifier:
            if #available(iOS 18, *) {
                return HKObjectType.stateOfMindType()
            }
            return nil
        default:
            return nil
        }
    }

    static func objectTypes(for type: HealthMetricType) -> [HKObjectType] {
        type.hkIdentifiers.compactMap(objectType(forRawIdentifier:))
    }

    /// Sample types queried for `type` (quantity, category, workout, correlation).
    static func sampleTypes(for type: HealthMetricType) -> [HKSampleType] {
        objectTypes(for: type).compactMap { $0 as? HKSampleType }
    }

    static func quantityTypes(for type: HealthMetricType) -> [HKQuantityType] {
        objectTypes(for: type).compactMap { $0 as? HKQuantityType }
    }

    /// Everything the hub asks permission to read: every resolvable registry object type
    /// (the blood-pressure correlation replaced by its two quantity types), the extra
    /// characteristics, minus anything that needs per-object authorization.
    static func readObjectTypes() -> Set<HKObjectType> {
        var types = Set<HKObjectType>()
        for type in iOSTypes {
            for identifier in type.hkIdentifiers where identifier != bloodPressureCorrelationIdentifier {
                if let object = objectType(forRawIdentifier: identifier) {
                    types.insert(object)
                }
            }
        }
        for identifier in bloodPressureAuthorizationIdentifiers + characteristicIdentifiers {
            if let object = objectType(forRawIdentifier: identifier) {
                types.insert(object)
            }
        }
        return types.filter { !$0.requiresPerObjectAuthorization() }
    }

    // MARK: - Tiers

    private static let tier1IDs: Set<String> = [
        "steps", "active_energy", "sleep", "weight", "resting_heart_rate", "exercise_minutes", "stand_hours", "body_fat",
        "height", "hrv_sdnn", "blood_oxygen", "respiratory_rate", "workout", "mindfulness_session", "hydration", "dietary_energy",
    ]

    private static let tier3IDs: Set<String> = [
        "heart_rate", "environmental_audio_exposure", "headphone_audio_exposure", "environmental_sound_reduction", "physical_effort",
    ]

    static func tier(of type: HealthMetricType) -> HealthMetricTier {
        if tier1IDs.contains(type.id) { return .t1 }
        if tier3IDs.contains(type.id) || type.id.hasPrefix("distance_") || type.id.hasPrefix("running_") || type.id.hasPrefix("cycling_") {
            return .t3
        }
        return .t2
    }

    /// Types with pinned-tile defaults are synced first so Home renders within seconds.
    static let defaultHomeTileIDs = ["steps", "active_energy", "heart_rate", "sleep"]

    // MARK: - Fallback for unknown identifiers / imported slugs

    /// Registers a raw native identifier (or an imported unknown slug) under `other`.
    static func fallback(id: String, unit: String, kind: HealthMetricKind = .discrete, aggregation: HealthAggregation = .latest, category: HealthCategory = .other, displayName: String? = nil) -> HealthMetricType {
        HealthMetricType(
            id: id,
            category: category,
            kind: kind,
            aggregation: aggregation,
            unit: unit,
            hkUnit: unit,
            dayAttribution: .start,
            hkIdentifiers: id.hasPrefix("HK") ? [id] : [],
            objectKind: id.hasPrefix(quantityPrefix) ? .quantity : (id.hasPrefix(categoryPrefix) ? .category : .virtual),
            exported: true,
            englishName: displayName ?? humanize(id)
        )
    }

    static func type(fromMeta meta: HealthTypeMetaRow) -> HealthMetricType {
        fallback(
            id: meta.typeID,
            unit: meta.unit,
            kind: HealthMetricKind(rawValue: meta.kind) ?? .discrete,
            aggregation: HealthAggregation(rawValue: meta.aggregation) ?? .latest,
            category: HealthCategory(rawValue: meta.category) ?? .other,
            displayName: meta.displayName
        )
    }

    static func humanize(_ id: String) -> String {
        var name = id
        for prefix in [quantityPrefix, categoryPrefix, characteristicPrefix] where name.hasPrefix(prefix) {
            name.removeFirst(prefix.count)
        }
        if name.contains("_") {
            return name.split(separator: "_").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
        }
        var words = ""
        for (index, character) in name.enumerated() {
            if index > 0, character.isUppercase { words.append(" ") }
            words.append(character)
        }
        return words
    }

    /// Resolves a `type_id` stored in the database to a registry entry or a fallback.
    static func resolve(typeID: String, metaRows: [String: HealthTypeMetaRow] = [:], unit: String = "count") -> HealthMetricType {
        if let known = byID[typeID] { return known }
        if let meta = metaRows[typeID] { return type(fromMeta: meta) }
        return fallback(id: typeID, unit: unit)
    }
}
