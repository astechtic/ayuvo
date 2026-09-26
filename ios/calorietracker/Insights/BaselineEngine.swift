import Foundation

// Personal baselines, trends, overnight values and sleep helpers. Port of the "Baseline engine" and "Sleep helpers"
// sections of `scripts/insights_reference.py` (docs/insights.md §2–§3).

nonisolated enum BaselineEngine {
    /// Unrounded baseline, shared by Recovery and the Daily Review.
    struct Raw: Sendable {
        var status: String
        var n: Int
        var needed: Int
        var coverage: Double
        var recent: Double?
        var mean: Double?
        var sd: Double?
        var sdUsed: Double?
        var low: Double?
        var high: Double?
        var delta: Double?
        var pct: Double?
        var z: Double?
        var confidence: String
        var isOK: Bool { status == "ok" }
    }

    /// [(offset, value)] for days day+first … day+last (inclusive, chronological), present values only.
    static func values(_ series: [String: Double], _ day: String, _ firstOffset: Int, _ lastOffset: Int) -> [(Int, Double)] {
        guard let d = InsightsDay.parse(day), firstOffset <= lastOffset else { return [] }
        var out: [(Int, Double)] = []
        for k in firstOffset...lastOffset {
            if let v = series[InsightsDay.format(d + k)] { out.append((k, v)) }
        }
        return out
    }

    static func baselineRaw(_ series: [String: Double], _ day: String, _ m: InsightsConfig.Metric) -> Raw {
        let window = m.windowDays
        let vals = values(series, day, -window, -1).map(\.1)
        let n = vals.count
        var recent: Double?
        if m.recent == "mean7" {
            let last7 = values(series, day, -6, 0).map(\.1)
            recent = last7.isEmpty ? nil : InsightsMath.mean(last7)
        } else {
            recent = series[day]
        }
        var b = Raw(status: "insufficient", n: n, needed: m.minPoints, coverage: Double(n) / Double(window),
                    recent: recent, confidence: "low")
        if n < m.minPoints { return b }
        let mu = InsightsMath.mean(vals)
        let sd = InsightsMath.sampleSD(vals, mu)
        let used = max(sd, m.sdFloor)
        b.status = "ok"
        b.mean = mu
        b.sd = sd
        b.sdUsed = used
        b.low = mu - used
        b.high = mu + used
        b.confidence = b.coverage >= m.highConfidenceCoverage && n >= m.highConfidenceMinN ? "high" : "medium"
        if let recent {
            b.delta = recent - mu
            b.pct = mu == 0 ? nil : (recent - mu) / mu * 100.0
            b.z = (recent - mu) / used
        }
        return b
    }

    /// Personal baseline of one metric on `day`: prior window (day excluded), mean ± max(SD, sd_floor).
    static func baseline(_ series: [String: Double], day: String, metric: InsightsConfig.Metric) -> BaselineResult {
        let b = baselineRaw(series, day, metric)
        return BaselineResult(
            status: b.status, n: b.n, needed: b.needed, coverage: InsightsMath.roundTo(b.coverage, 2),
            mean: InsightsMath.roundTo(b.mean, 2), sd: InsightsMath.roundTo(b.sd, 2),
            sdFloored: b.sd.map { $0 < metric.sdFloor } ?? false,
            low: InsightsMath.roundTo(b.low, 2), high: InsightsMath.roundTo(b.high, 2),
            recent: InsightsMath.roundTo(b.recent, 2), delta: InsightsMath.roundTo(b.delta, 2),
            pct: InsightsMath.roundTo(b.pct, 1), z: InsightsMath.roundTo(b.z, 2), confidence: b.confidence)
    }

    /// Least-squares slope over the last trend_days (day included) as % of the baseline mean per week.
    static func trend(_ series: [String: Double], day: String, metric m: InsightsConfig.Metric) -> TrendResult {
        let b = baselineRaw(series, day, m)
        let pts = values(series, day, -(m.trendDays - 1), 0)
        var out = TrendResult(status: "insufficient", n: pts.count, needed: m.trendMinPoints)
        guard b.isOK, pts.count >= m.trendMinPoints, let mean = b.mean, mean != 0,
              let slope = InsightsMath.olsSlope(pts.map { (Double($0.0), $0.1) }) else { return out }
        let pct = slope * 7.0 / mean * 100.0
        let direction: String
        if abs(pct) < m.trendStablePctPerWeek {
            direction = "stable"
        } else if m.direction == "band" {
            direction = "changing"
        } else if (pct > 0) == (m.direction == "higher_better") {
            direction = "improving"
        } else {
            direction = "declining"
        }
        out.status = "ok"
        out.slopePctPerWeek = InsightsMath.roundTo(pct, 2)
        out.direction = direction
        return out
    }

    /// Mean of samples inside the night window (inclusive); otherwise the daily-rollup fallback, flagged.
    static func overnightValue(samples: [InsightsSample], night: InsightsNight?, fallback: Double?) -> OvernightValue {
        if let night {
            let inside = samples.filter { night.startMs <= $0.tMs && $0.tMs <= night.endMs }.map(\.value)
            if !inside.isEmpty {
                return OvernightValue(value: InsightsMath.roundTo(InsightsMath.mean(inside), 2), n: inside.count, fallback: false)
            }
        }
        return OvernightValue(value: InsightsMath.roundTo(fallback, 2), n: 0, fallback: true)
    }

    // MARK: Sleep helpers

    /// Nights long enough to count (shorter ones are incomplete recordings).
    static func validNights(_ inputs: InsightsInputs, config: InsightsConfig) -> [String: InsightsNight] {
        inputs.sleep.filter { $0.value.asleepMin >= config.sleep.minNightMinutes }
    }

    static func sleepSeries(_ inputs: InsightsInputs, config: InsightsConfig) -> [String: Double] {
        validNights(inputs, config: config).mapValues(\.asleepMin)
    }
}
