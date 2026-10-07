import Foundation

/// One Trends row: a metric's 28-day robust baseline and its trend (docs/health-analytics.md §5.1–5.2).
nonisolated struct AnalyticsTrendRow: Sendable, Equatable, Identifiable {
    let id: String
    /// The day compared (yesterday for steps and active energy, whose today is still partial).
    let day: String
    let baseline: AJ
    let trend: AJ
}

/// Everything the analytics screens and the Coach evidence show for one day, computed in one pure pass from the
/// Insights inputs. Every value is a reference-shaped engine result (`shared/analytics`).
nonisolated struct AnalyticsBundle: Sendable, Equatable {
    var today: String
    /// Recovery Indicator v2 by day (the last `persistDays` days, oldest first in `days`).
    var recoveryByDay: [String: AJ] = [:]
    var anomalyByDay: [String: AJ] = [:]
    var hrv: AJ = .null
    /// Ayuvo RMSSD from beat-to-beat series (iOS only), when any day has one.
    var hrvAyuvo: AJ = .null
    var sleep: AJ = .null
    var load: AJ = .null
    var trends: [AnalyticsTrendRow] = []
    /// Apple's 1-minute heart rate recovery (provider value) against its own baseline.
    var hrr: AnalyticsTrendRow?
    var energy: AJ = .null
    var vo2max: AJ = .null
    var met: AJ = .null
    var correlation: AJ = .null
    /// Personal response estimates of the surfaced correlations (`id`, `lag` added).
    var responses: [AJ] = []
    /// target → forecast result (only when the forecast switch is on).
    var forecasts: [String: AJ] = [:]
    var evidence: AJ = .null

    var recovery: AJ { recoveryByDay[today] ?? .null }
    var anomaly: AJ { anomalyByDay[today] ?? .null }
}

nonisolated enum AnalyticsSuite {
    /// Days of Recovery / anomaly results kept in `analytics_results`.
    static let persistDays = 30
    /// Metrics on the Trends screen, in order.
    static let trendMetrics = ["hrv", "resting_heart_rate", "respiratory_rate", "wrist_temperature", "blood_oxygen",
                               "sleep_duration", "sleep_efficiency", "steps", "active_energy", "weight", "vo2_max"]
    /// Cumulative metrics compared on yesterday (today is still being recorded).
    static let completeDayMetrics: Set<String> = ["steps", "active_energy"]

    static func compute(_ inputs: InsightsInputs, today: String, recoveryByDay: [String: AJ],
                        cfg: AnalyticsConfig = .shared) -> AnalyticsBundle {
        let a = AnalyticsInputsBuilder.make(inputs, today: today)
        var out = AnalyticsBundle(today: today)
        out.recoveryByDay = recoveryByDay
        for k in 0..<persistDays {
            let day = InsightsDay.add(today, -k)
            out.anomalyByDay[day] = AnalyticsRecovery.anomaly(a, day: day, cfg)
        }
        heartAndSleep(&out, a, inputs: inputs, today: today, cfg)
        out.trends = trendRows(a, inputs: inputs, today: today, cfg)
        if let hrr = inputs.analytics.series["hrr1_provider"], !hrr.isEmpty {
            out.hrr = AnalyticsTrendRow(id: "hrr1", day: today,
                                        baseline: latestBaseline(hrr, day: today, metric: "hrr1", cfg),
                                        trend: AnalyticsCore.trend(series: hrr, day: today, metric: "hrr1", cfg))
        }
        out.energy = energy(inputs, day: InsightsDay.add(today, -1), cfg)
        out.vo2max = AnalyticsStats.vo2maxTrend(readings: vo2Readings(inputs), day: today, cfg)
        out.met = AnalyticsStats.metIntensity(
            activities: inputs.analytics.workouts.map { AnalyticsStats.Activity(startMs: $0.startMs, endMs: $0.endMs, key: $0.activity) },
            day: today, timeZone: a.timeZone, cfg)
        patterns(&out, a, inputs: inputs, today: today, cfg)
        if inputs.analytics.forecastEnabled { out.forecasts = forecasts(a, today: today, cfg) }
        out.evidence = AnalyticsForecast.evidence(recovery: out.recovery, anomaly: out.anomaly, hrv: out.hrv, sleep: out.sleep,
                                                  load: out.load)
        return out
    }

    private static func heartAndSleep(_ out: inout AnalyticsBundle, _ a: AInputs, inputs: InsightsInputs, today: String,
                                      _ cfg: AnalyticsConfig) {
        out.hrv = AnalyticsHRV.hrvStatus(series: a.series["hrv"] ?? [:], day: today, kind: inputs.hrvKind,
                                         contexts: a.contexts["hrv"], sources: a.sources["hrv"], cfg)
        if let rr = inputs.analytics.series["ayuvo_rmssd"], !rr.isEmpty {
            out.hrvAyuvo = AnalyticsHRV.hrvStatus(series: rr, day: today, kind: "ayuvo_rmssd", cfg)
        }
        out.sleep = AnalyticsSleep.sleepStatus(nights: a.nights, day: today, cfg)
        out.load = AnalyticsLoad.load(workouts: a.workouts, day: today, timeZone: a.timeZone, tracking: a.trackingWorkouts, cfg).json
    }

    /// Series behind a Trends metric.
    static func series(_ metric: String, _ a: AInputs) -> [String: Double] {
        switch metric {
        case "sleep_duration": return AnalyticsSleep.validNights(a.nights, AnalyticsConfig.shared).compactMapValues(\.asleepMin)
        case "sleep_efficiency": return AnalyticsSleep.validNights(a.nights, AnalyticsConfig.shared).compactMapValues(\.efficiency)
        default: return a.series[metric] ?? [:]
        }
    }

    private static func trendRows(_ a: AInputs, inputs: InsightsInputs, today: String, _ cfg: AnalyticsConfig) -> [AnalyticsTrendRow] {
        trendMetrics.compactMap { metric in
            let s = series(metric, a)
            guard !s.isEmpty else { return nil }
            let day = completeDayMetrics.contains(metric) ? InsightsDay.add(today, -1) : today
            return AnalyticsTrendRow(
                id: metric, day: day,
                baseline: AnalyticsCore.baseline(series: s, day: day, metric: metric, window: 28, contexts: a.contexts[metric],
                                                 sources: a.sources[metric], recent: "day", cfg),
                trend: AnalyticsCore.trend(series: s, day: day, metric: metric, cfg)
            )
        }
    }

    /// Baseline of the newest value on or before `day` (event metrics such as heart rate recovery).
    private static func latestBaseline(_ s: [String: Double], day: String, metric: String, _ cfg: AnalyticsConfig) -> AJ {
        let last = s.keys.filter { $0 <= day }.max() ?? day
        return AnalyticsCore.baseline(series: s, day: last, metric: metric, window: 90, cfg)
    }

    // MARK: Energy, fitness

    static func energy(_ inputs: InsightsInputs, day: String, _ cfg: AnalyticsConfig) -> AJ {
        let tz = DerivedDay.timeZone(inputs.timeZone)
        let onDay = inputs.analytics.workouts.filter { DerivedDay.localDayOf($0.startMs, tz) == day }
        var providerKcal: Double?
        for w in onDay where w.provider { if let k = w.kcal { providerKcal = (providerKcal ?? 0) + k } }
        let p = inputs.analytics.profile
        let input = AnalyticsStats.EnergyInput(
            providerBasalKcal: inputs.analytics.series["resting_energy"]?[day],
            providerActiveKcal: inputs.series["active_energy"]?[day],
            providerWorkoutKcal: providerKcal,
            ayuvoSessions: onDay.filter { !$0.provider }.map { ($0.startMs, $0.endMs, $0.kcal) },
            providerWorkouts: onDay.filter(\.provider).map { ($0.startMs, $0.endMs) },
            weightKg: p.weightKg, heightCm: p.heightCm, age: p.age, sex: p.sex
        )
        guard case .obj(var o) = AnalyticsStats.energy(input, cfg) else { return .null }
        o["day"] = .str(day)
        return .obj(o)
    }

    static func vo2Readings(_ inputs: InsightsInputs) -> [String: [String: Double]] {
        var out: [String: [String: Double]] = [:]
        if let p = inputs.analytics.series["vo2_provider"], !p.isEmpty { out["provider"] = p }
        if let u = inputs.analytics.series["vo2_uth"], !u.isEmpty { out["uth"] = u }
        return out
    }

    // MARK: Patterns v2

    /// Exposure / outcome series of the configured correlation pairs.
    static func patternSeries(_ name: String, _ a: AInputs, load: AnalyticsLoad.Result?) -> [String: Double] {
        switch name {
        case "sleep_duration": return series("sleep_duration", a)
        case "bedtime": return AnalyticsSleep.validNights(a.nights, AnalyticsConfig.shared).compactMapValues(\.bedtimeClock)
        case "energy_intake": return a.nutrition.compactMapValues(\.calories)
        case "training_load": return dailyLoad(a, method: load?.primaryMethod)
        case "hrr1": return [:]
        default: return a.series[name] ?? [:]
        }
    }

    /// Daily training load of the primary method; days between the first session and today without a session are a
    /// true zero (workout tracking is on).
    static func dailyLoad(_ a: AInputs, method: String?) -> [String: Double] {
        guard a.trackingWorkouts, let method else { return [:] }
        let days = AnalyticsLoad.loadDays(a.workouts, timeZone: a.timeZone)
        guard let first = days.keys.min(), let last = days.keys.max() else { return [:] }
        var out: [String: Double] = [:]
        var d = first
        while d <= last {
            let x = days[d]
            switch method {
            case "trimp": out[d] = x?.trimp ?? 0
            case "rpe_load": out[d] = x?.rpeLoad ?? 0
            default: out[d] = x?.minutes ?? 0
            }
            d = InsightsDay.add(d, 1)
        }
        return out
    }

    private static func patterns(_ out: inout AnalyticsBundle, _ a: AInputs, inputs: InsightsInputs, today: String,
                                 _ cfg: AnalyticsConfig) {
        let ld = AnalyticsLoad.load(workouts: a.workouts, day: today, timeZone: a.timeZone, tracking: a.trackingWorkouts, cfg)
        var a2 = a
        if let hrr = inputs.analytics.series["hrr1_provider"] { a2.series["hrr1"] = hrr }
        let pairs = cfg["correlation"]["pairs"].array.map { p -> AnalyticsStats.Pair in
            let e = patternSeries(p["exposure"].string ?? "", a2, load: ld)
            let o = p["outcome"].string == "hrr1" ? (a2.series["hrr1"] ?? [:]) : patternSeries(p["outcome"].string ?? "", a2, load: ld)
            return AnalyticsStats.Pair(id: p["id"].string ?? "", exposure: e, outcome: o)
        }
        out.correlation = AnalyticsStats.correlation(pairs: pairs, asOf: today, cfg)
        let byID = Dictionary(uniqueKeysWithValues: pairs.map { ($0.id, $0) })
        for r in out.correlation["results"].array where r["surfaced"].bool == true {
            guard let id = r["id"].string, let pair = byID[id], let lag = r["lag"].int else { continue }
            guard case .obj(var o) = AnalyticsStats.response(exposure: pair.exposure, outcome: pair.outcome, lag: lag, asOf: today,
                                                             window: nil, cfg) else { continue }
            o["id"] = .str(id)
            out.responses.append(.obj(o))
        }
    }

    // MARK: Forecast

    static let forecastTargets = ["hrv", "resting_heart_rate"]

    private static func forecasts(_ a: AInputs, today: String, _ cfg: AnalyticsConfig) -> [String: AJ] {
        let history = cfg["forecast"]["history_days"].int ?? 365
        let feats = AnalyticsForecast.features(a, from: InsightsDay.add(today, -(history - 1)), to: today, cfg)
        var out: [String: AJ] = [:]
        for target in forecastTargets {
            out[target] = AnalyticsForecast.forecast(target: target, features: feats, targetSeries: a.series[target] ?? [:],
                                                     asOf: today, cfg)
        }
        return out
    }
}
