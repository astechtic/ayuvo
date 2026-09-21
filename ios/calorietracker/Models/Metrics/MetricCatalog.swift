import SwiftUI

/// The 17 Browse domains, in catalog order (docs/ui-structure.md §3). Raw values are catalog ids.
enum BrowseCategory: String, CaseIterable, Identifiable, Hashable {
    case nutrition, hydration, fasting, body, activity, heart, sleep, vitals, respiratory,
         cycle, mindfulness, mobility, hearing, symptoms, medications, records, other

    var id: String { rawValue }

    private var domain: MetricCatalogData.Domain? { MetricCatalogData.shared.domain(rawValue) }

    var title: String {
        String(localized: String.LocalizationValue(domain?.title ?? rawValue.capitalized))
    }

    var systemImage: String { domain?.icon.ios ?? "square.grid.2x2.fill" }

    var tint: Color { AyuvoPalette.domain(rawValue) }

    /// Catalog target: `screen:<id>`, `metric:<key>`, `category:<registry category>` or `tab:records`.
    var target: String { domain?.target ?? "category:other" }

    var browseOrder: Int { domain?.browseOrder ?? Int.max }

    /// Registry category behind a `category:` target.
    var healthCategory: HealthCategory? {
        guard target.hasPrefix("category:") else { return nil }
        return HealthCategory(rawValue: String(target.dropFirst("category:".count)))
    }

    static var ordered: [BrowseCategory] { allCases.sorted { $0.browseOrder < $1.browseOrder } }

    init?(healthCategory: HealthCategory) {
        guard let id = MetricCatalogData.shared.health.categoryDomains[healthCategory.rawValue] else { return nil }
        self.init(rawValue: id)
    }
}

enum MetricChartKind: String {
    case bar, line, range
}

/// Presentation facts for one metric key (catalog + registry), used by tiles, rows and detail.
struct MetricDescriptor: Identifiable {
    let key: MetricKey
    let title: String
    let domainID: String
    let systemImage: String
    let unitLabel: String
    let chartKind: MetricChartKind
    let aggregation: MetricsReference.Aggregation
    let ranges: [HealthDetailRange]
    let about: String
    let goalSource: String
    let browseHidden: Bool
    /// Canonical unit decimals for display.
    let decimals: Int

    var id: String { key.id }
    var tint: Color { AyuvoPalette.domain(domainID) }
    var supportsLog: Bool {
        if case .app = key { return true }
        return false
    }
}

enum MetricCatalog {
    static var appMetrics: [MetricDescriptor] {
        AppMetric.allCases.map { descriptor(for: .app($0)) }
    }

    static func descriptor(for key: MetricKey) -> MetricDescriptor {
        let resolved = MetricsReference.resolveMetric(key.id)
        let aggregation = MetricsReference.Aggregation(rawValue: resolved.aggregation) ?? .last
        let chart = MetricChartKind(rawValue: resolved.chartKind) ?? .line
        switch key {
        case .app(let metric):
            let entry = MetricCatalogData.shared.appMetricsByKey[metric.key]
            return MetricDescriptor(
                key: key,
                title: String(localized: String.LocalizationValue(entry?.title ?? metric.rawValue)),
                domainID: resolved.domain,
                systemImage: resolved.iconIOS,
                unitLabel: appUnitLabel(metric),
                chartKind: chart,
                aggregation: aggregation,
                ranges: (entry?.ranges ?? ["W", "M", "6M", "Y"]).compactMap(HealthDetailRange.init(rawValue:)),
                about: String(localized: String.LocalizationValue(entry?.about ?? "")),
                goalSource: resolved.goalSource,
                browseHidden: false,
                decimals: entry?.unit.decimals ?? 0
            )
        case .health(let typeID):
            let type = HealthMetricRegistry.resolve(typeID: typeID)
            return MetricDescriptor(
                key: key,
                title: type.displayName,
                domainID: resolved.domain,
                systemImage: resolved.iconIOS,
                unitLabel: HealthUnitFormatting.unitLabel(for: type),
                chartKind: chart,
                aggregation: aggregation,
                ranges: HealthDetailRange.allCases,
                about: String(localized: "\(type.displayName) readings shared with Ayuvo from Apple Health. Edit or delete them in the Health app."),
                goalSource: resolved.goalSource,
                browseHidden: resolved.browseHidden,
                decimals: 1
            )
        }
    }

    /// Display unit for an app metric, following the user's unit preferences.
    static func appUnitLabel(_ metric: AppMetric) -> String {
        switch metric {
        case .calories, .workoutBurn: return String(localized: "kcal")
        case .protein, .carbs, .fat, .fiber: return String(localized: "g")
        case .water: return waterUnit.symbol
        case .fasting, .workoutMinutes: return ""
        case .weight: return WeightUnit.current.rawValue
        case .bodyFat: return "%"
        case .workouts: return String(localized: "workouts")
        }
    }

    static var waterUnit: WaterUnit {
        WaterUnit(rawValue: UserDefaults.standard.string(forKey: WaterSettings.unitKey) ?? "") ?? .defaultUnit
    }

    /// App metrics and health types whose title matches `query` (case-insensitive).
    static func search(_ query: String, health: HealthDataStore) -> [MetricDescriptor] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return [] }
        let app = appMetrics.filter { $0.title.localizedCaseInsensitiveContains(needle) }
        let healthMatches = health.knownTypes
            .filter { $0.displayName.localizedCaseInsensitiveContains(needle) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            .map { descriptor(for: .health($0.id)) }
        return app + healthMatches
    }
}
