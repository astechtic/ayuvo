import Foundation

/// `health.*` actions: any metric Ayuvo can chart — app metrics (`app:water`) from the diary stores
/// and Health data types (`steps`) from the local HealthKit mirror. Health reads never prompt for
/// permission; with Health sync off they return `permission_required`.
extension ActionExecutor {
    struct MetricSamples {
        let key: MetricKey
        let title: String
        let unit: String
        /// The aggregation the metric charts with, in catalog vocabulary.
        let natural: String
        /// Samples are one total per local day (summed metrics), not raw readings.
        let dailyTotals: Bool
        let samples: [ActionMath.Sample]
        let healthType: HealthMetricType?
    }

    func metricKey(_ id: String) throws -> MetricKey {
        guard let key = MetricKey(pinID: id) else {
            throw ActionError.notFound(String(localized: "Ayuvo doesn't know the metric “\(id)”."))
        }
        if case .health(let typeID) = key, HealthMetricRegistry.type(id: typeID) == nil {
            throw ActionError.notFound(String(localized: "Ayuvo doesn't know the metric “\(id)”."))
        }
        return key
    }

    static func appUnit(_ metric: AppMetric) -> String {
        switch metric {
        case .calories, .workoutBurn: "kcal"
        case .protein, .carbs, .fat, .fiber: "g"
        case .water: "mL"
        case .fasting, .workoutMinutes: "s"
        case .weight: "kg"
        case .bodyFat: "%"
        case .workouts: "count"
        }
    }

    private func healthReader() async throws -> HealthDatabase {
        guard env.healthSyncEnabled else {
            throw ActionError.permissionRequired(String(localized: "Turn on Apple Health in Ayuvo (Settings → Health Sync) to use health data."))
        }
        guard let runtime = env.healthRuntime, await runtime.openIfNeeded(), let reader = runtime.reader ?? runtime.writer else {
            throw ActionError.unavailable(String(localized: "Health data isn't available right now."))
        }
        return reader
    }

    private func dayKey(_ ms: Int64) -> String { env.zone.day(of: ms).text }

    /// Samples for `id` inside `range` (all history when `range` is nil).
    func metricSamples(_ id: String, range: ActionMath.DateRange?) async throws -> MetricSamples {
        let key = try metricKey(id)
        switch key {
        case .app(let metric):
            let entries = AppMetricSampleExtractor.entries(
                for: metric, food: env.foodStore(), water: env.waterStore(), fasting: env.fastingStore(),
                weight: env.weightStore(), bodyFat: env.bodyFatStore(), workouts: env.workoutStore(),
                importedWorkouts: env.importedWorkoutStore(), now: env.nowDate, calendar: env.calendar
            )
            let aggregation = MetricCatalog.descriptor(for: key).aggregation
            var samples = entries.map { ActionMath.Sample(tMs: $0.tMs, value: $0.value) }
            if let range { samples = samples.filter { range.contains(ms: $0.tMs) } }
            let summed = aggregation.isSummed
            if summed { samples = dailyTotals(samples) }
            let natural: String
            switch aggregation {
            case .sum, .duration: natural = "sum"
            case .count: natural = "sum"
            case .avg: natural = "average"
            case .last: natural = "latest"
            }
            return MetricSamples(key: key, title: MetricCatalog.descriptor(for: key).title, unit: Self.appUnit(metric),
                                 natural: natural, dailyTotals: summed, samples: samples, healthType: nil)
        case .health(let typeID):
            let reader = try await healthReader()
            let type = HealthMetricRegistry.type(id: typeID) ?? HealthMetricRegistry.resolve(typeID: typeID)
            let summed = [.cumulative, .duration, .session, .category].contains(type.kind)
            var samples: [ActionMath.Sample] = []
            if summed {
                let fromDay = range.map { $0.firstDay.text } ?? "0000-01-01"
                let toDay = range.map { $0.firstDay.adding(days: $0.dayCount - 1).text } ?? "9999-12-31"
                let rollups = (try? await reader.dailyRollups(type: typeID, fromDay: fromDay, toDay: toDay)) ?? []
                samples = rollups.compactMap { rollup in
                    guard let day = MetricsReference.LocalDay.parse(rollup.day) else { return nil }
                    return ActionMath.Sample(tMs: env.zone.midnight(day), value: HealthChartSeriesBuilder.primaryValue(rollup, type: type))
                }
            } else {
                let start = range?.fromMs ?? 0
                let end = range?.toMs ?? env.nowMs + 86_400_000
                let rows = (try? await reader.rows(type: typeID, startMs: start, endMs: end)) ?? []
                samples = rows.map { ActionMath.Sample(tMs: $0.endMs, value: $0.value) }
            }
            let natural: String
            switch type.aggregation {
            case .sum, .duration, .count: natural = "sum"
            case .average, .minMax: natural = "average"
            case .latest: natural = "latest"
            }
            return MetricSamples(key: key, title: type.displayName, unit: type.unit, natural: natural,
                                 dailyTotals: summed, samples: samples, healthType: type)
        }
    }

    private func dailyTotals(_ samples: [ActionMath.Sample]) -> [ActionMath.Sample] {
        var totals: [MetricsReference.LocalDay: Double] = [:]
        for sample in samples {
            guard let value = sample.value else { continue }
            totals[env.zone.day(of: sample.tMs), default: 0] += value
        }
        return totals.keys.sorted().map { ActionMath.Sample(tMs: env.zone.midnight($0), value: totals[$0]) }
    }

    func metricText(_ value: Double, series: MetricSamples) -> String {
        switch series.key {
        case .app(let metric): return AppMetricFormat.text(value, metric: metric)
        case .health:
            guard let type = series.healthType else { return Self.number(value, digits: 1) }
            return HealthUnitFormatting.text(value, type: type)
        }
    }

    func metricGet(_ v: ActionValidation) async throws -> ActionResult {
        let r = try range(v)
        let series = try await metricSamples(v.string("metric") ?? "", range: r)
        let aggregation = v.string("aggregation") ?? series.natural
        let result = ActionMath.aggregate(series.samples, aggregation)
        let value = result.value ?? ((aggregation == "sum" || aggregation == "count") ? 0 : nil)
        let when = Self.rangeText(v.string("range"))
        let dialog: String
        if aggregation == "count" {
            let count = Int(value ?? 0)
            let shown = series.dailyTotals ? String(localized: "\(count) days with data") : String(localized: "\(count) readings")
            dialog = String(localized: "\(series.title) \(when): \(shown).")
        } else if let value {
            dialog = String(localized: "\(series.title) \(when): \(metricText(value, series: series)).")
        } else {
            dialog = String(localized: "No \(series.title) data \(when).")
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .optional(value), "unit": .string(aggregation == "count" ? "count" : series.unit),
            "aggregation": .string(aggregation), "sample_count": .int(result.count),
            "from_ms": .int(Int(r.fromMs)), "to_ms": .int(Int(r.toMs)),
        ], dialog: dialog)
    }

    func metricLatest(_ v: ActionValidation) async throws -> ActionResult {
        let id = v.string("metric") ?? ""
        let series = try await metricSamples(id, range: nil)
        var latest: ActionMath.Sample?
        for sample in series.samples where sample.value != nil {
            if latest == nil || sample.tMs >= latest!.tMs { latest = sample }
        }
        guard let latest, let value = latest.value else {
            throw ActionError.notFound(String(localized: "There's no \(series.title) data yet."))
        }
        let date = Date(timeIntervalSince1970: Double(latest.tMs) / 1000)
        let dialog = String(localized: "Latest \(series.title): \(metricText(value, series: series)), \(date.formatted(date: .abbreviated, time: series.dailyTotals ? .omitted : .shortened)).")
        return ActionResult(actionID: v.actionID, fields: [
            "value": .number(value), "unit": .string(series.unit), "t_ms": .int(Int(latest.tMs)),
        ], dialog: dialog)
    }

    func metricSamplesList(_ v: ActionValidation) async throws -> ActionResult {
        let r = try range(v)
        let series = try await metricSamples(v.string("metric") ?? "", range: r)
        let limit = v.int("limit") ?? 100
        let newestFirst = series.samples.enumerated()
            .filter { $0.element.value != nil }
            .sorted { ($0.element.tMs, $0.offset) > ($1.element.tMs, $1.offset) }
            .prefix(limit)
            .map(\.element)
        let items: [[String: ActionField]] = newestFirst.map {
            ["t_ms": .int(Int($0.tMs)), "value": .optional($0.value), "unit": .string(series.unit)]
        }
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count), "unit": .string(series.unit)], items: items,
                            dialog: String(localized: "\(items.count) \(series.title) values \(Self.rangeText(v.string("range")))."))
    }

    func sleepLastNight(_ v: ActionValidation) async throws -> ActionResult {
        let reader = try await healthReader()
        let from = dayKey(env.nowMs - 2 * 86_400_000)
        let to = dayKey(env.nowMs)
        let rows = (try? await reader.rowsForDays(type: "sleep", fromDay: from, toDay: to)) ?? []
        guard let night = HealthSleepAnalysis.nights(rows: rows, calendar: env.calendar).max(by: { $0.nightOf < $1.nightOf }) else {
            throw ActionError.notFound(String(localized: "There's no sleep data for last night."))
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .number(ActionMath.roundTo(night.asleepS / 3600, 2)), "unit": .string("h"),
            "asleep_s": .number(night.asleepS), "start_ms": .int(Int(night.startMs)), "end_ms": .int(Int(night.endMs)),
        ], dialog: String(localized: "You slept \(HealthUnitFormatting.durationText(seconds: night.asleepS)) last night."))
    }
}
