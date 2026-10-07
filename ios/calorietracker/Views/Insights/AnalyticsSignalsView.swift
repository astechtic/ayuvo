import SwiftUI

/// Insights › Health signals: the analytics results that are not a single score (docs/health-analytics.md):
/// signal deviations, HRV, sleep, training load, heart rate recovery, activity intensity, energy, VO2 max and,
/// when switched on and good enough, the forecasts.
struct AnalyticsSignalsView: View {
    @Environment(InsightsStore.self) private var store

    var body: some View {
        InsightsScreen(title: "Health signals", topic: .analytics, inputs: { Self.inputRows(store.analytics) }) {
            if let a = store.analytics {
                AnalyticsSignalsCard(anomaly: a.anomaly)
                AnalyticsHRVCard(hrv: a.hrv, kind: "sdnn")
                if a.hrvAyuvo.has { AnalyticsHRVCard(hrv: a.hrvAyuvo, kind: "ayuvo_rmssd") }
                AnalyticsSleepCard(sleep: a.sleep)
                AnalyticsLoadCard(load: a.load)
                if let hrr = a.hrr { AnalyticsHRRCard(row: hrr) }
                AnalyticsActivityCard(met: a.met)
                if a.energy["status"].string != "NO_DATA" { AnalyticsEnergyCard(energy: a.energy) }
                if !a.vo2max["kinds"].object.values.allSatisfy({ ($0["n"].int ?? 0) == 0 }) { AnalyticsVO2Card(vo2: a.vo2max) }
                ForEach(Self.deployedForecasts(a), id: \.0) { target, f in
                    AnalyticsForecastCard(target: target, forecast: f)
                }
            }
        }
    }

    /// Forecasts shown: only when the switch is on (they are computed only then) and the model passed its gate.
    static func deployedForecasts(_ a: AnalyticsBundle) -> [(String, AJ)] {
        a.forecasts.sorted { $0.key < $1.key }.filter { $0.value["deployed"].bool == true && $0.value["prediction"].double != nil }
    }

    /// "Your inputs today" for the ⓘ sheet: each result with its status and confidence.
    static func inputRows(_ a: AnalyticsBundle?) -> [InsightsInputRow] {
        guard let a else { return [] }
        func row(_ id: String, _ title: String, _ j: AJ) -> InsightsInputRow {
            let status = j["status"].string ?? j["baseline"]["status"].string
            let conf = j["confidence"].double ?? j["baseline"]["confidence"].double
            return InsightsInputRow(id: id, title: title, value: AnalyticsFormat.status(status) ?? AnalyticsFormat.percent(conf) ?? "—",
                                    detail: (j["classification"].string).map(AnalyticsText.classificationLabel),
                                    missing: status != "VALID" && status != "LOW_CONFIDENCE")
        }
        return [
            row("signals", String(localized: "Signals"), a.anomaly), row("hrv", String(localized: "HRV"), a.hrv),
            row("sleep", String(localized: "Sleep"), a.sleep), row("load", String(localized: "Training load"), a.load),
            row("energy", String(localized: "Energy expenditure"), a.energy),
        ]
    }
}
