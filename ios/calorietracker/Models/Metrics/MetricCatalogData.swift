import Foundation

/// Decoded `metric_catalog.json` (bundled copy of `shared/metrics/metric_catalog.json`,
/// docs/ui-structure.md §4). Pure data; safe to read from any isolation domain.
nonisolated struct MetricCatalogData: Decodable, Sendable {
    struct Icon: Decodable, Sendable {
        let android: String
        let ios: String
    }

    struct Domain: Decodable, Sendable {
        let id: String
        let title: String
        let titleRes: String
        let colourHex: String
        let colourHexDark: String
        let icon: Icon
        let browseOrder: Int
        let target: String
    }

    struct Unit: Decodable, Sendable {
        let canonical: String
        let decimals: Int
        let pref: String?
    }

    struct Favourite: Decodable, Sendable {
        let enabled: Bool
        let order: Int?
    }

    struct Source: Decodable, Sendable {
        let store: String
        let field: String
    }

    struct AppMetric: Decodable, Sendable {
        let key: String
        let domain: String
        let title: String
        let titleRes: String
        let icon: Icon
        let source: Source
        let unit: Unit
        let aggregation: String
        let chartKind: String
        let dayBucket: String
        let ranges: [String]
        let goalSource: String
        let defaultFavourite: Favourite?
        let browseSection: String?
        let browseOrder: Int
        let about: String
    }

    struct Override: Decodable, Sendable {
        let id: String
        let domain: String?
        let goalSource: String?
        let defaultFavourite: Favourite?
        let browseHidden: Bool?
        /// Optional per-metric glyph (docs/ui-structure.md §4 "Icons"); falls back to the domain icon.
        let icon: Icon?
    }

    struct Health: Decodable, Sendable {
        let categoryDomains: [String: String]
        let aggregationMap: [String: String]
        let chartKindMap: [String: String]
        let overrides: [Override]
    }

    struct BrowseSection: Decodable, Sendable {
        let id: String
        let domain: String
        let order: Int
        let title: String
    }

    struct Ring: Decodable, Sendable {
        let id: String
        let metric: String
        let goalSource: String
        let domain: String
        let visibleWhen: String
        let noSourceState: String?
    }

    struct Favourites: Decodable, Sendable {
        let prefKey: String
        let legacyPrefKey: String
        let max: Int
    }

    struct IntPref: Decodable, Sendable {
        let `default`: Int
        let min: Int
        let max: Int
        let step: Int
    }

    struct Prefs: Decodable, Sendable {
        let dailyStepGoal: IntPref
    }

    let catalogVersion: Int
    let domains: [Domain]
    let metrics: [AppMetric]
    let browseSections: [BrowseSection]
    let health: Health
    let macroColours: [String: String]
    let summaryRings: [Ring]
    let favourites: Favourites
    let prefs: Prefs

    static let resourceName = "metric_catalog"

    /// The bundled catalog. A missing or corrupt resource is a build error caught by
    /// `MetricCatalogTests`; at runtime it degrades to an empty catalog instead of crashing.
    static let shared: MetricCatalogData = load() ?? .empty

    static func load() -> MetricCatalogData? {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: resourceName, withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let decoded = decode(data) {
                return decoded
            }
        }
        return nil
    }

    static func decode(_ data: Data) -> MetricCatalogData? {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try? decoder.decode(MetricCatalogData.self, from: data)
    }

    private final class BundleMarker {}

    static let empty = MetricCatalogData(
        catalogVersion: 0, domains: [], metrics: [], browseSections: [],
        health: Health(categoryDomains: [:], aggregationMap: [:], chartKindMap: [:], overrides: []),
        macroColours: [:], summaryRings: [],
        favourites: Favourites(prefKey: "summaryFavourites", legacyPrefKey: "healthHomeTiles", max: 12),
        prefs: Prefs(dailyStepGoal: IntPref(default: 10_000, min: 1_000, max: 50_000, step: 500))
    )

    // MARK: - Lookups

    var appMetricsByKey: [String: AppMetric] {
        Dictionary(metrics.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
    }

    func domain(_ id: String) -> Domain? { domains.first { $0.id == id } }

    func override(_ id: String) -> Override? { health.overrides.first { $0.id == id } }

    /// Default favourites in catalog order (app metrics and health overrides), docs §4.
    var defaultFavourites: [String] {
        var rows: [(Int, String)] = []
        for metric in metrics {
            if let fav = metric.defaultFavourite, fav.enabled, let order = fav.order { rows.append((order, metric.key)) }
        }
        for override in health.overrides {
            if let fav = override.defaultFavourite, fav.enabled, let order = fav.order { rows.append((order, override.id)) }
        }
        return rows.sorted { $0 < $1 }.map(\.1)
    }
}
