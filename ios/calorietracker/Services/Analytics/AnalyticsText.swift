import Foundation

/// Translated display text from `shared/analytics/analytics_config.json` (keys follow
/// `scripts/l10n/l10n_contracts.py`: `analytics.<path>`; list items are named by their id). A key without a
/// translation falls back to the config's English.
nonisolated enum AnalyticsText {
    private static var cfg: AnalyticsConfig { .shared }

    static func driver(_ id: String, _ direction: String) -> String {
        let english = cfg["recovery"]["drivers"][id][direction].string ?? id
        return ContractText.text("analytics.recovery.drivers.\(id).\(direction)", english)
    }

    /// A driver line from its engine result: ContractText key `analytics.recovery.drivers.<text_key>`, the engine's
    /// English `text` as fallback.
    static func driverText(_ d: AJ) -> String {
        guard let key = d["text_key"].string else {
            return driver(d["id"].string ?? "", d["direction"].string ?? "neutral")
        }
        return ContractText.text("analytics.recovery.drivers.\(key)", d["text"].string ?? key)
    }

    static func summaryTemplate(_ key: String) -> String {
        ContractText.text("analytics.recovery.summary.\(key)", cfg["recovery"]["summary"][key].string ?? "")
    }

    static func warning(_ code: String) -> String {
        ContractText.text("analytics.recovery.warnings.\(code)", cfg["recovery"]["warnings"][code].string ?? code)
    }

    static func loadState(_ state: String) -> String {
        ContractText.text("analytics.load.states.\(state)", cfg["load"]["states"][state].string ?? state)
    }

    static func anomalyState(_ state: String) -> String {
        ContractText.text("analytics.anomaly.states.\(state)", cfg["anomaly"]["states"][state].string ?? state)
    }

    static var persistentNote: String {
        ContractText.text("analytics.anomaly.persistent_note", cfg["anomaly"]["persistent_note"].string ?? "")
    }

    static func metricLabel(_ id: String) -> String {
        ContractText.text("analytics.metrics.\(id).label", cfg.metric(id)["label"].string ?? id)
    }

    static func classificationLabel(_ id: String) -> String {
        ContractText.text("analytics.classifications.\(id).label", cfg["classifications"][id]["label"].string ?? id)
    }

    static func classificationAbout(_ id: String) -> String {
        ContractText.text("analytics.classifications.\(id).about", cfg["classifications"][id]["about"].string ?? "")
    }

    static var disclaimer: String {
        ContractText.text("analytics.disclaimer", cfg["disclaimer"].string ?? "")
    }

    static func componentWhy(_ id: String) -> String {
        let english = cfg["recovery"]["components"].array.first { $0["id"].string == id }?["why"].string ?? ""
        return ContractText.text("analytics.recovery.components.\(id).why", english)
    }

    static func band(_ id: String, english: String) -> String {
        ContractText.text("analytics.recovery.bands.\(id).label", english)
    }

    static func recommendation(_ id: String, english: String) -> String {
        ContractText.text("analytics.recovery.bands.\(id).recommendation", english)
    }

    /// "Main signals pulling the score down: …. Signals supporting the score: …." from the drivers, translated.
    static func summary(_ recovery: AJ) -> String? {
        guard recovery["score"].double != nil else { return nil }
        let drivers = recovery["drivers"].array
        let neg = drivers.filter { $0["direction"].string == "negative" }.map(driverText)
        let pos = drivers.filter { $0["direction"].string == "positive" }.map(driverText)
        var parts: [String] = []
        if !neg.isEmpty { parts.append(summaryTemplate("lead_negative").replacingOccurrences(of: "{items}", with: neg.joined(separator: "; "))) }
        if !pos.isEmpty { parts.append(summaryTemplate("lead_positive").replacingOccurrences(of: "{items}", with: pos.joined(separator: "; "))) }
        return parts.isEmpty ? summaryTemplate("none") : parts.joined(separator: " ")
    }

    /// Correlation sentence with translated pieces (association wording from the config).
    static func correlation(_ r: AJ) -> String? {
        guard r["surfaced"].bool == true, let id = r["id"].string, let rho = r["spearman_rho"].double else { return nil }
        let c = cfg["correlation"]
        guard let pair = c["pairs"].array.first(where: { $0["id"].string == id }) else { return nil }
        let direction = rho > 0 ? "higher" : "lower"
        let lag = String(r["lag"].int ?? 0)
        return ContractText.text("analytics.correlation.template", c["template"].string ?? "")
            .replacingOccurrences(of: "{exposure}", with: ContractText.text("analytics.correlation.pairs.\(id).exposure_label", pair["exposure_label"].string ?? ""))
            .replacingOccurrences(of: "{direction}", with: ContractText.text("analytics.correlation.direction_words.\(direction)", c["direction_words"][direction].string ?? direction))
            .replacingOccurrences(of: "{outcome}", with: ContractText.text("analytics.correlation.pairs.\(id).outcome_label", pair["outcome_label"].string ?? ""))
            .replacingOccurrences(of: "{lag}", with: ContractText.text("analytics.correlation.lag_words.\(lag)", c["lag_words"][lag].string ?? ""))
            .replacingOccurrences(of: "{n}", with: "\(r["n"].int ?? 0)")
    }
}
