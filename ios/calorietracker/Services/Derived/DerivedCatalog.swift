import Foundation

/// The derived-metric catalog from the bundled `derived_config.json` (docs/derived-metrics.md §2): what each metric
/// is called, how it is charted, which native Health type wins over it and what it depends on. Engines read the
/// thresholds through `DerivedConfig`; the UI, settings and Coach read this.
nonisolated struct DerivedMetricInfo: Sendable, Hashable, Identifiable, Decodable {
    nonisolated struct PlatformIDs: Sendable, Hashable, Decodable {
        let ios: String?
        let android: String?
    }

    let id: String
    let category: String
    let title: String
    let unit: String
    let decimals: Int
    let function: String
    let field: String
    let value2Field: String?
    let value3Field: String?
    let native: PlatformIDs?
    let requires: [String]
    let chartKind: String
    let aggregation: String
    let icon: PlatformIDs
    let about: String
    let method: String
    let citation: String
    let defaultEnabled: Bool

    enum CodingKeys: String, CodingKey {
        case id, category, title, unit, decimals, function, field, native, requires, aggregation, icon, about, method, citation
        case value2Field = "value2_field", value3Field = "value3_field", chartKind = "chart_kind", defaultEnabled = "default_enabled"
    }

    /// The Health registry type that wins over this metric on iOS, if any.
    var nativeTypeID: String? { native?.ios }
    var systemImage: String { icon.ios ?? "waveform.path.ecg" }
    /// Clock metrics store minutes after 12:00 of the day before the wake day.
    var isClock: Bool { unit == "clock" }

    /// Translated display text (table "Contracts"); `title`/`about`/`method` stay the contract English for Coach and AI.
    var displayTitle: String { ContractText.text("derived.metrics.\(id).title", title) }
    var displayAbout: String { ContractText.text("derived.metrics.\(id).about", about) }
    var displayMethod: String { ContractText.text("derived.metrics.\(id).method", method) }
}

nonisolated struct DerivedCatalog: Sendable {
    nonisolated struct File: Decodable {
        let algoVersion: Int
        let categories: [String]
        let disclaimer: String
        let metrics: [DerivedMetricInfo]

        enum CodingKeys: String, CodingKey {
            case categories, disclaimer, metrics
            case algoVersion = "algo_version"
        }
    }

    let algoVersion: Int
    let categories: [String]
    let disclaimer: String
    let metrics: [DerivedMetricInfo]
    let byID: [String: DerivedMetricInfo]

    /// Translated disclaimer for display.
    var displayDisclaimer: String { ContractText.text("derived.disclaimer", disclaimer) }

    static let shared: DerivedCatalog = load()

    static func load(bundle: Bundle = .main) -> DerivedCatalog {
        guard let url = bundle.url(forResource: "derived_config", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(File.self, from: data)
        else {
            return DerivedCatalog(algoVersion: 0, categories: [], disclaimer: "", metrics: [], byID: [:])
        }
        return DerivedCatalog(
            algoVersion: file.algoVersion, categories: file.categories, disclaimer: file.disclaimer, metrics: file.metrics,
            byID: Dictionary(uniqueKeysWithValues: file.metrics.map { ($0.id, $0) })
        )
    }

    func metrics(in category: String) -> [DerivedMetricInfo] {
        metrics.filter { $0.category == category }
    }

    /// Metrics that list `id` in `requires`, directly or through another metric.
    func dependents(of id: String) -> [DerivedMetricInfo] {
        var out: [DerivedMetricInfo] = []
        var frontier: Set<String> = [id]
        while !frontier.isEmpty {
            let next = metrics.filter { m in !m.requires.filter(frontier.contains).isEmpty && !out.contains(m) }
            out.append(contentsOf: next)
            frontier = Set(next.map(\.id))
        }
        return out
    }

    static func categoryTitle(_ category: String) -> String {
        switch category {
        case "heart": String(localized: "Heart", comment: "Estimated metrics group title")
        case "sleep": String(localized: "Sleep", comment: "Estimated metrics group title")
        case "activity": String(localized: "Activity", comment: "Estimated metrics group title")
        case "energy": String(localized: "Energy", comment: "Estimated metrics group title")
        case "mobility": String(localized: "Mobility", comment: "Estimated metrics group title")
        case "hearing": String(localized: "Hearing", comment: "Estimated metrics group title")
        case "body": String(localized: "Body", comment: "Estimated metrics group title")
        case "nutrition": String(localized: "Nutrition", comment: "Estimated metrics group title")
        default: category.capitalized
        }
    }
}
