import Foundation

/// Analytics settings (docs/health-analytics.md §5.12). Only switches live here.
nonisolated enum AnalyticsSettings {
    /// Per-user ridge forecast of next-day HRV / resting heart rate (`ML_PREDICTED`), default off.
    static let forecastEnabledKey = "analyticsForecastEnabled"

    static func forecastEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: forecastEnabledKey)
    }
}

extension InsightsDataSource {
    /// Health mirror → the analytics-only inputs: per-night sleep detail (the derived `sleep_night` math on each wake
    /// day's main episode, plus naps), sleeping wrist temperature, Apple's 1-minute heart rate recovery, the Uth
    /// VO2 max estimate kept apart from the provider value, and Ayuvo RMSSD from beat-to-beat series.
    static func analyticsInputs(into inputs: inout InsightsInputs, database: HealthDatabase, sleepRows: [HealthSampleRow],
                                from: String, through today: String, calendar: Calendar) async {
        inputs.analytics.nights = analyticsNights(sleepRows: sleepRows, timeZone: inputs.timeZone, from: from, through: today)

        for (key, typeID) in [("wrist_temperature", "sleeping_wrist_temperature"), ("hrr1_provider", "heart_rate_recovery_one_minute")] {
            guard let type = HealthMetricRegistry.type(id: typeID) else { continue }
            let rollups = (try? await database.dailyRollups(type: typeID, fromDay: from, toDay: today)) ?? []
            var series: [String: Double] = [:]
            for rollup in rollups {
                if let value = HealthChartSeriesBuilder.primaryValue(rollup, type: type) { series[rollup.day] = value }
            }
            if !series.isEmpty { inputs.analytics.series[key] = series }
        }

        let uth = (try? await database.derivedValues(metric: "vo2max_estimate", fromDay: from, toDay: today)) ?? []
        var uthSeries: [String: Double] = [:]
        for row in uth { if let v = row.value { uthSeries[row.day] = v } }
        if !uthSeries.isEmpty { inputs.analytics.series["vo2_uth"] = uthSeries }

        let rr = (try? await database.analyticsResults(metric: HeartbeatHRV.metricID, algorithmVersion: HeartbeatHRV.algorithmVersion,
                                                       fromDay: from, toDay: today)) ?? []
        var rmssd: [String: Double] = [:]
        for row in rr where row.status == "VALID" {
            if let v = row.value { rmssd[row.periodStart] = v }
        }
        if !rmssd.isEmpty { inputs.analytics.series["ayuvo_rmssd"] = rmssd }
    }

    /// Main night per wake day as the analytics engine reads it (`ANight`).
    nonisolated static func analyticsNights(sleepRows: [HealthSampleRow], timeZone: String, from: String,
                                            through today: String) -> [String: ANight] {
        let config = DerivedConfig.shared
        let rows = sleepRows.filter { !$0.isDeleted && $0.categoryValue != nil }
            .map { SleepDerivation.SourceRow(startMs: $0.startMs, endMs: $0.endMs, code: $0.categoryValue!, source: $0.sourceID) }
        let nights = SleepDerivation.sleepNights(timeZone: timeZone, rows: rows, config: config).nights
        var out: [String: ANight] = [:]
        for (day, night) in nights where day >= from && day <= today {
            let r = SleepDerivation.sleepNight(timeZone: timeZone, wakeDay: day, rows: night.rows, config: config)
            guard let asleep = r.asleepMin else { continue }
            out[day] = ANight(asleepMin: asleep, inBedMin: r.inBedMin, efficiency: r.efficiency, wasoMin: r.wasoMin,
                              bedtimeClock: r.bedtimeClock.map(Double.init), wakeClock: r.wakeClock.map(Double.init),
                              midpointClock: r.midpointClock.map(Double.init), napMin: night.napMin)
        }
        return out
    }
}
