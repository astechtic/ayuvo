import Foundation

/// Recovery Indicator v2 (`ayuvo.recovery@2`, shared/analytics) in the shape the Insights screens, the Daily
/// Review, Patterns, notifications, widgets, actions and the AI explanation already read (`RecoveryResult`).
/// The v1 engine (`RecoveryEngine`) and its vectors stay for reference; the app shows v2.
nonisolated enum RecoveryV2Adapter {
    /// v2 for `day`, adapted. `a` is the engine input; only that day's camera-scan fallbacks are passed as
    /// `scan_fallback`.
    static func recovery(_ a: AInputs, inputs: InsightsInputs, day: String, cfg: AnalyticsConfig = .shared) -> (RecoveryResult, AJ) {
        var dayInputs = a
        dayInputs.scanFallback = inputs.scanFallback.filter { $0.value.contains(day) }.map(\.key).sorted()
        let j = AnalyticsRecovery.recovery(dayInputs, day: day, cfg)
        return (result(j, overnightFallback: Set(a.overnightFallback)), j)
    }

    static func result(_ j: AJ, overnightFallback: Set<String> = []) -> RecoveryResult {
        let status: String
        switch j["status"].string {
        case "VALID", "LOW_CONFIDENCE": status = "ok"
        case "INSUFFICIENT_HISTORY": status = "collecting"
        default: status = j["reason"].string ?? "no_heart_data"
        }
        let collecting = j["collecting"].has
            ? InsightsCollecting(have: j["collecting"]["have"].int ?? 0, need: j["collecting"]["need"].int ?? 7) : nil
        let label = j["label"].string
        return RecoveryResult(
            day: j["day"].string ?? "", status: status, score: j["score"].int, label: label,
            labelText: label.map { AnalyticsText.band($0, english: j["label_text"].string ?? $0) },
            recommendation: label.map { AnalyticsText.recommendation($0, english: j["recommendation"].string ?? "") },
            confidence: status == "ok" ? j["confidence_band"].string : nil, collecting: collecting,
            components: j["components"].array.map { component($0, fallback: overnightFallback) },
            positives: signals(j, "positive"), negatives: signals(j, "negative"), load: load(j), v2: j
        )
    }

    private static func component(_ c: AJ, fallback: Set<String>) -> RecoveryComponent {
        let value = c["value"].double, baseline = c["baseline"].double
        let delta = value.flatMap { v in baseline.map { v - $0 } }
        let pct = delta.flatMap { d in baseline.flatMap { $0 == 0 ? nil : d / abs($0) * 100 } }
        let conf = c["confidence"].double ?? 0
        let id = c["id"].string ?? ""
        return RecoveryComponent(
            id: id, weight: c["weight"].double ?? 0, available: c["available"].bool ?? false, value: value,
            baseline: baseline, delta: delta.map { AMath.roundTo($0, 2) }, pct: pct.map { AMath.roundTo($0, 1) }, z: c["z"].double,
            subscore: c["subscore"].double, impact: c["impact"].double, baselineN: c["n"].int ?? 0,
            baselineConfidence: conf >= 0.75 ? "high" : (conf >= 0.5 ? "medium" : "low"),
            fallback: fallback.contains(metricFor(id)), consistencyDeviationMin: nil, consistencySubscore: c["parts"]["regularity"].double
        )
    }

    /// Insights series id of a v2 component.
    static func metricFor(_ componentID: String) -> String {
        componentID == "sleep" ? "sleep" : componentID
    }

    private static func signals(_ j: AJ, _ direction: String) -> [RecoverySignal] {
        j["drivers"].array.filter { $0["direction"].string == direction }.map {
            let id = $0["id"].string ?? ""
            return RecoverySignal(id: id, impact: $0["impact"].double ?? 0, text: AnalyticsText.driverText($0))
        }
    }

    private static func load(_ j: AJ) -> RecoveryLoad? {
        let l = j["load"]
        guard l.has, let state = l["state"].string else { return nil }
        let category: String = switch state {
        case "LOAD_SPIKE", "LOAD_HIGH": "high"
        case "LOAD_REDUCED": "light"
        default: "moderate"
        }
        return RecoveryLoad(day: l["day"].string ?? "", load: l["acute"].double ?? 0, mean28d: l["chronic"].double ?? 0,
                            ratio: l["ratio"].double, category: category,
                            label: AnalyticsText.loadState(state),
                            modifier: l["modifier"].int ?? 0)
    }
}
