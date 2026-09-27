import Foundation

/// Decoded `nutrient_reference.json` (bundled copy of `shared/nutrients/nutrient_reference.json`,
/// docs/nutrients.md §3). Pure data; safe to read from any isolation domain. Keys are decoded
/// explicitly (no snake-case strategy) because several dictionaries are keyed by contract ids.
nonisolated struct NutrientReferenceData: Decodable, Sendable {
    struct AgeBand: Decodable, Sendable {
        let id: String
        let min: Int
        let max: Int?
    }

    struct Recommended: Decodable, Sendable {
        let kind: String
        let male: [String: Double]
        let female: [String: Double]
    }

    struct UpperLimit: Decodable, Sendable {
        let male: [String: Double]
        let female: [String: Double]
        let scope: String
        let note: String?
    }

    struct Limit: Decodable, Sendable {
        let kind: String
        let value: Double?
        let pct: Double?
        let kcalPerUnit: Double?
        let basis: String?

        enum CodingKeys: String, CodingKey {
            case kind, value, pct, basis
            case kcalPerUnit = "kcal_per_unit"
        }
    }

    struct IU: Decodable, Sendable {
        let unit: String
        let mcgPerIU: Double?
        let forms: [String: Double]?

        enum CodingKeys: String, CodingKey {
            case unit, forms
            case mcgPerIU = "mcg_per_iu"
        }
    }

    struct Source: Decodable, Sendable {
        let title: String
        let publisher: String
        let url: String
        let checked: String
        let pageUpdated: String?

        enum CodingKeys: String, CodingKey {
            case title, publisher, url, checked
            case pageUpdated = "page_updated"
        }
    }

    struct Nutrient: Decodable, Sendable {
        let key: String
        let slug: String
        let name: String
        let unit: String
        let category: String
        let style: String
        let summary: String
        let recommended: Recommended?
        let upperLimit: UpperLimit?
        let limit: Limit?
        let iu: IU?
        let massForms: [String: Double]?
        let notes: [String]
        let sourceIDs: [String]
        /// True for the nutrients the food log tracks (`OptionalNutrient` keys plus mono / poly fats). Only these
        /// get a `nutrient:<key>` metric, can be supplement nutrients and appear in the AI label prompt.
        let appTracked: Bool
        /// `metric_registry.json` id of the same nutrient (`dietary_vitamin_d`), or nil when the registry has none.
        let healthType: String?

        enum CodingKeys: String, CodingKey {
            case key, slug, name, unit, category, style, summary, recommended, limit, iu, notes
            case upperLimit = "upper_limit"
            case massForms = "mass_forms"
            case sourceIDs = "source_ids"
            case appTracked = "app_tracked"
            case healthType = "health_type"
        }
    }

    struct Sports: Decodable, Sendable {
        let key: String
        let unit: String
    }

    let version: Int
    let checked: String
    let population: String
    let populationNote: String
    let ageBands: [AgeBand]
    let defaultBand: String
    let units: [String]
    let unitAliases: [String: String]
    let amountPerUnitMax: [String: Double]
    let sportsSupplements: [Sports]
    let sources: [String: Source]
    let nutrients: [Nutrient]

    enum CodingKeys: String, CodingKey {
        case version, checked, population, units, sources, nutrients
        case populationNote = "population_note"
        case ageBands = "age_bands"
        case defaultBand = "default_band"
        case unitAliases = "unit_aliases"
        case amountPerUnitMax = "amount_per_unit_max"
        case sportsSupplements = "sports_supplements"
    }

    static let resourceName = "nutrient_reference"

    /// The bundled reference. A missing or corrupt resource is caught by `NutrientsVectorTests`; at
    /// runtime it degrades to an empty reference (no lines, no defaults) instead of crashing.
    static let shared: NutrientReferenceData = load() ?? .empty

    static func load() -> NutrientReferenceData? {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: resourceName, withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let decoded = decode(data) {
                return decoded
            }
        }
        return nil
    }

    static func decode(_ data: Data) -> NutrientReferenceData? {
        try? JSONDecoder().decode(NutrientReferenceData.self, from: data)
    }

    private final class BundleMarker {}

    static let empty = NutrientReferenceData(
        version: 0, checked: "", population: "", populationNote: "", ageBands: [], defaultBand: "31-50",
        units: ["g", "mg", "mcg"], unitAliases: [:], amountPerUnitMax: [:], sportsSupplements: [], sources: [:], nutrients: []
    )

    // MARK: - Lookups

    var byKey: [String: Nutrient] {
        Dictionary(nutrients.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// The `app_tracked` nutrients in display order (the food log's reference nutrients).
    var trackedNutrients: [Nutrient] { nutrients.filter(\.appTracked) }

    /// Registry health type id → reference nutrient.
    var byHealthType: [String: Nutrient] {
        Dictionary(nutrients.compactMap { n in n.healthType.map { ($0, n) } }, uniquingKeysWith: { first, _ in first })
    }

    var sportsByKey: [String: Sports] {
        Dictionary(sportsSupplements.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
    }
}
