import Foundation
import SwiftUI

/// The stores app metrics are derived from (docs/ui-structure.md §4 "Entry adapters").
struct MetricDataSources {
    let food: FoodStore
    let water: WaterStore
    let fasting: FastingStore
    let weight: WeightStore
    let bodyFat: BodyFatStore
    let workouts: StrengthWorkoutStore
    let importedWorkouts: ImportedHealthWorkoutStore
    let health: HealthDataStore
    let profile: ProfileStore
    /// Supplement contributions for nutrient metrics (docs/nutrients.md §5); nil = food only.
    var medications: MedicationStore? = nil

    /// Food + supplement totals for nutrient metrics.
    var nutrientTotals: NutrientTotals { NutrientTotals(foodStore: food, medicationStore: medications) }

    /// Nutrient series change with the diary and with taken doses / supplement nutrients.
    var nutrientRevision: Int { food.revision &* 31 &+ (medications?.nutritionRevision ?? 0) }

    func revision(for metric: AppMetric) -> Int {
        switch metric {
        case .calories, .protein, .carbs, .fat, .fiber: return food.revision
        case .water: return water.revision
        case .fasting: return fasting.revision
        case .weight: return weight.revision
        case .bodyFat: return bodyFat.revision
        case .workouts, .workoutMinutes, .workoutBurn: return workouts.revision &* 31 &+ importedWorkouts.revision
        }
    }

    /// Goal line for a catalog `goal_source`, in the metric's canonical unit.
    func goal(for source: String, defaults: UserDefaults = .standard) -> Double? {
        let profile = profile.profile
        switch source {
        case "profile.calories": return Double(profile.effectiveCalories)
        case "profile.protein": return Double(profile.effectiveProtein)
        case "profile.carbs": return Double(profile.effectiveCarbs)
        case "profile.fat": return Double(profile.effectiveFat)
        case "profile.goalWeight": return profile.goalWeightKg
        case "profile.goalBodyFat": return profile.goalBodyFatPercentage.map { $0 * 100 }
        case "prefs.waterDailyGoalMl":
            let stored = defaults.integer(forKey: WaterSettings.dailyGoalKey)
            return Double(stored > 0 ? stored : WaterSettings.defaultDailyGoalMl)
        case "prefs.dailyStepGoal": return Double(ActivitySettings.dailyStepGoal(defaults: defaults))
        default: return nil
        }
    }
}

/// Store rows → shared-contract entries (`[{t_ms, value}]`), all in canonical units.
enum AppMetricSampleExtractor {
    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    static func entries(for metric: AppMetric, sources: MetricDataSources, now: Date = Date(), calendar: Calendar = .current) -> [MetricsReference.Entry] {
        entries(
            for: metric, food: sources.food, water: sources.water, fasting: sources.fasting, weight: sources.weight,
            bodyFat: sources.bodyFat, workouts: sources.workouts, importedWorkouts: sources.importedWorkouts,
            now: now, calendar: calendar
        )
    }

    /// Store-level form for callers without a full `MetricDataSources` (Siri / Shortcuts actions).
    static func entries(
        for metric: AppMetric,
        food: FoodStore,
        water: WaterStore,
        fasting: FastingStore,
        weight: WeightStore,
        bodyFat: BodyFatStore,
        workouts: StrengthWorkoutStore,
        importedWorkouts: ImportedHealthWorkoutStore,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> [MetricsReference.Entry] {
        let zone = MetricsReference.Zone(calendar: calendar)
        switch metric {
        case .calories:
            return food.entries.map { .init(tMs: ms($0.timestamp), value: Double($0.calories)) }
        case .protein:
            return food.entries.map { .init(tMs: ms($0.timestamp), value: $0.protein) }
        case .carbs:
            return food.entries.map { .init(tMs: ms($0.timestamp), value: $0.carbs) }
        case .fat:
            return food.entries.map { .init(tMs: ms($0.timestamp), value: $0.fat) }
        case .fiber:
            return food.entries.map { .init(tMs: ms($0.timestamp), value: $0.fiber) }
        case .water:
            return water.entries.map { .init(tMs: ms($0.date), value: Double($0.milliliters)) }
        case .weight:
            return weight.entries.map { .init(tMs: ms($0.date), value: $0.weightKg) }
        case .bodyFat:
            return bodyFat.entries.map { .init(tMs: ms($0.date), value: $0.bodyFatFraction * 100) }
        case .fasting:
            let inputs = fasting.sessions.map {
                MetricsReference.FastingInput(startedAtMs: ms($0.startedAt), endedAtMs: $0.endedAt.map(ms))
            }
            return MetricsReference.fastingSecondsPerDay(sessions: inputs, nowMs: ms(now), zone: zone)
                .map { .init(tMs: zone.midnight($0.day), value: Double($0.seconds)) }
        case .workouts, .workoutMinutes:
            let strength = workouts.completedSessions.map { session in
                (key: session.stableDiaryDateKey, started: session.startedAt, duration: session.durationSeconds)
            }
            let imported = importedWorkouts.workouts.map { workout in
                (key: workout.diaryDateKey, started: workout.startedAt, duration: workout.durationSeconds)
            }
            return (strength + imported).map { item in
                let day = MetricsReference.LocalDay.parse(item.key) ?? zone.day(of: ms(item.started))
                let value = metric == .workouts ? 1.0 : Double(item.duration)
                return .init(tMs: zone.midnight(day), value: value)
            }
        case .workoutBurn:
            return WorkoutBurnAggregation.daily(
                sessions: workouts.completedSessions,
                in: Date.distantPast...Date.distantFuture,
                calendar: calendar
            ).map { .init(tMs: ms($0.date), value: Double($0.calories)) }
        }
    }
}

/// Entries → chart series with one point per shared bucket (empty buckets have a nil value).
nonisolated enum AppMetricSeriesProvider {
    static func build(
        entries: [MetricsReference.Entry],
        aggregation: MetricsReference.Aggregation,
        range: HealthDetailRange,
        anchor: Date,
        calendar: Calendar,
        weekStart: MetricsReference.WeekStart
    ) -> HealthChartSeries {
        let zone = MetricsReference.Zone(calendar: calendar)
        let anchorMs = Int64((anchor.timeIntervalSince1970 * 1000).rounded())
        let buckets = MetricsReference.bucketSeries(entries: entries, range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, aggregation: aggregation)
        let headline = MetricsReference.headline(entries: entries, range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, aggregation: aggregation)
        let bounds = MetricsReference.bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs)
        let interval = DateInterval(start: date(bounds.startMs), end: date(bounds.endMs))
        let points = buckets.map {
            HealthChartPoint(start: date($0.startMs), end: date($0.endMs), value: $0.value, min: $0.min, max: $0.max, value2: nil, count: $0.count, stage: nil)
        }

        let inside = entries.filter { $0.value != nil && $0.tMs >= bounds.startMs && $0.tMs < bounds.endMs }
        var highlights = HealthHighlights()
        highlights.count = inside.count
        let values = points.compactMap(\.value)
        switch aggregation {
        case .sum, .duration, .count:
            let total = inside.reduce(0.0) { $0 + (aggregation == .count ? 1 : ($1.value ?? 0)) }
            highlights.total = inside.isEmpty ? nil : MetricsReference.round3(total)
            if headline.kind == .average {
                highlights.average = headline.value
            } else {
                highlights.average = values.isEmpty ? nil : MetricsReference.round3(values.reduce(0, +) / Double(values.count))
            }
            highlights.min = values.min()
            highlights.max = values.max()
        case .avg, .last:
            let raw = inside.compactMap(\.value)
            highlights.average = raw.isEmpty ? nil : MetricsReference.round3(raw.reduce(0, +) / Double(raw.count))
            highlights.min = raw.min()
            highlights.max = raw.max()
        }
        if let newest = inside.enumerated().max(by: { ($0.element.tMs, $0.offset) < ($1.element.tMs, $1.offset) })?.element {
            highlights.latest = newest.value
            highlights.latestAt = date(newest.tMs)
        }

        var series = HealthChartSeries(range: range, interval: interval, points: points, stagePoints: [], highlights: highlights)
        series.headline = headline
        return series
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
}

/// A nutrient metric's chart series plus the extras its detail screen shows (docs/nutrients.md §5).
nonisolated struct NutrientSeriesExtras: Sendable, Equatable {
    /// Average per logged day over the shown interval (`logged_day_average`).
    var average: NutrientsReference.LoggedDayAverage
    /// Food and supplement parts over the shown interval; nil when that part has no value.
    var food: Double?
    var supplements: Double?

    static let empty = NutrientSeriesExtras(average: .init(average: nil, loggedDays: 0), food: nil, supplements: nil)
}

nonisolated enum NutrientSeriesProvider {
    /// Food entries of the field then supplement entries, bucketed with the shared `bucket_series` (sum), plus the
    /// average per logged day and the food / supplement split for the bucket interval.
    /// `key` names the nutrient: an `app_tracked: false` nutrient counts only the days with a taken dose of it.
    static func build(food: [MetricsReference.Entry], supplements: [MetricsReference.Entry], loggedDays: [String],
                      range: HealthDetailRange, anchor: Date, calendar: Calendar,
                      weekStart: MetricsReference.WeekStart, key: String? = nil) -> (series: HealthChartSeries, extras: NutrientSeriesExtras) {
        let series = AppMetricSeriesProvider.build(entries: food + supplements, aggregation: .sum, range: range, anchor: anchor,
                                                   calendar: calendar, weekStart: weekStart)
        let zone = MetricsReference.Zone(calendar: calendar)
        let anchorMs = Int64((anchor.timeIntervalSince1970 * 1000).rounded())
        let bounds = MetricsReference.bucketBounds(range: range, anchorMs: anchorMs, zone: zone, weekStart: weekStart, nowMs: anchorMs)
        let average = NutrientsReference.loggedDayAverage(entries: food + supplements, loggedDays: loggedDays,
                                                          startMs: bounds.startMs, endMs: bounds.endMs, zone: zone, key: key)
        func part(_ entries: [MetricsReference.Entry]) -> Double? {
            let inside = entries.compactMap { $0.tMs >= bounds.startMs && $0.tMs < bounds.endMs ? $0.value : nil }
            guard !inside.isEmpty else { return nil }
            return NutrientsReference.roundTo(inside.reduce(0, +), NutrientsReference.amountDecimals)
        }
        return (series, NutrientSeriesExtras(average: average, food: part(food), supplements: part(supplements)))
    }
}

/// Small LRU of computed app series, keyed by metric, range, anchor day and store revision.
@MainActor
final class MetricSeriesCache {
    static let shared = MetricSeriesCache()

    private var storage: [String: HealthChartSeries] = [:]
    private var nutrientStorage: [String: (HealthChartSeries, NutrientSeriesExtras)] = [:]
    private var order: [String] = []
    private let capacity = 32

    /// A nutrient metric (`nutrient:<key>`): food + supplement entries; the key includes the medications revision.
    func nutrientSeries(for nutrientKey: String, range: HealthDetailRange, anchor: Date, sources: MetricDataSources,
                        calendar: Calendar = .current) async -> (series: HealthChartSeries, extras: NutrientSeriesExtras) {
        let weekStart = ActivitySettings.weekStart()
        let dayKey = Int(calendar.startOfDay(for: anchor).timeIntervalSince1970)
        let key = "nutrient:\(nutrientKey)|\(range.rawValue)|\(dayKey)|\(sources.nutrientRevision)|\(weekStart.rawValue)|\(calendar.timeZone.identifier)"
        if let cached = nutrientStorage[key] {
            touch(key)
            return cached
        }
        let totals = NutrientTotals(foodStore: sources.food, medicationStore: sources.medications, calendar: calendar)
        let parts = totals.entries(nutrientKey)
        let loggedDays = totals.loggedDays
        let result = await Task.detached(priority: .userInitiated) {
            NutrientSeriesProvider.build(food: parts.food, supplements: parts.supplements, loggedDays: loggedDays, range: range,
                                         anchor: anchor, calendar: calendar, weekStart: weekStart, key: nutrientKey)
        }.value
        nutrientStorage[key] = result
        touch(key)
        while order.count > capacity {
            let removed = order.removeFirst()
            storage.removeValue(forKey: removed)
            nutrientStorage.removeValue(forKey: removed)
        }
        return result
    }

    func series(for metric: AppMetric, range: HealthDetailRange, anchor: Date, sources: MetricDataSources, calendar: Calendar = .current) async -> HealthChartSeries {
        let weekStart = ActivitySettings.weekStart()
        let dayKey = Int(calendar.startOfDay(for: anchor).timeIntervalSince1970)
        let key = "\(metric.rawValue)|\(range.rawValue)|\(dayKey)|\(sources.revision(for: metric))|\(weekStart.rawValue)|\(calendar.timeZone.identifier)"
        if let cached = storage[key] {
            touch(key)
            return cached
        }
        let entries = AppMetricSampleExtractor.entries(for: metric, sources: sources, calendar: calendar)
        let aggregation = MetricCatalog.descriptor(for: .app(metric)).aggregation
        let series = await Task.detached(priority: .userInitiated) {
            AppMetricSeriesProvider.build(entries: entries, aggregation: aggregation, range: range, anchor: anchor, calendar: calendar, weekStart: weekStart)
        }.value
        storage[key] = series
        touch(key)
        while order.count > capacity {
            let removed = order.removeFirst()
            storage.removeValue(forKey: removed)
            nutrientStorage.removeValue(forKey: removed)
        }
        return series
    }

    private func touch(_ key: String) {
        order.removeAll { $0 == key }
        order.append(key)
    }
}

// MARK: - Formatting

enum AppMetricFormat {
    /// Canonical value → (display value, unit) following the user's unit preferences.
    static func display(_ value: Double?, metric: AppMetric) -> (value: String, unit: String) {
        guard let value else { return ("—", MetricCatalog.appUnitLabel(metric)) }
        switch metric {
        case .calories, .workoutBurn:
            return (Int(value.rounded()).formatted(), String(localized: "kcal"))
        case .protein, .carbs, .fat, .fiber:
            return (HealthUnitFormatting.number(value, fractionDigits: value < 10 ? 1 : 0), String(localized: "g"))
        case .water:
            let unit = MetricCatalog.waterUnit
            return (unit.displayValue(forMilliliters: Int(value.rounded())), unit.symbol)
        case .fasting, .workoutMinutes:
            return (HealthUnitFormatting.durationText(seconds: value), "")
        case .weight:
            let unit = WeightUnit.current
            let shown = unit == .lbs ? value * 2.20462 : value
            return (HealthUnitFormatting.number(shown, fractionDigits: 1), unit.rawValue)
        case .bodyFat:
            return (HealthUnitFormatting.number(value, fractionDigits: 1), "%")
        case .workouts:
            let count = Int(value.rounded())
            return (count.formatted(), count == 1 ? String(localized: "workout") : String(localized: "workouts"))
        }
    }

    /// Canonical → chart axis value (lbs, fl oz, hours / minutes).
    static func chartValue(_ value: Double?, metric: AppMetric) -> Double? {
        guard let value else { return nil }
        switch metric {
        case .water: return MetricCatalog.waterUnit.displayAmount(forMilliliters: Int(value.rounded()))
        case .weight: return WeightUnit.current == .lbs ? value * 2.20462 : value
        case .fasting: return value / 3600
        case .workoutMinutes: return value / 60
        default: return value
        }
    }

    static func chartUnit(_ metric: AppMetric) -> String {
        switch metric {
        case .fasting: return String(localized: "h")
        case .workoutMinutes: return String(localized: "min")
        default: return MetricCatalog.appUnitLabel(metric)
        }
    }

    static func text(_ value: Double?, metric: AppMetric) -> String {
        let d = display(value, metric: metric)
        return d.unit.isEmpty || d.value == "—" ? d.value : "\(d.value) \(d.unit)"
    }
}

// MARK: - Tiles

nonisolated enum MetricTileMath {
    /// Tile value: today's total for summed metrics, the newest reading for avg / last.
    static func appTile(entries: [MetricsReference.Entry], aggregation: MetricsReference.Aggregation, now: Date, calendar: Calendar) -> (value: Double?, at: Date?, sparkline: [Double]) {
        let zone = MetricsReference.Zone(calendar: calendar)
        let nowMs = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let spark = MetricsReference.sparkline7d(entries: entries, nowMs: nowMs, zone: zone, aggregation: aggregation)
        let sparkValues = spark.values.compactMap { $0 }
        switch aggregation {
        case .sum, .duration, .count:
            let today = spark.values.last ?? nil
            let todayStart = zone.midnight(zone.day(of: nowMs))
            let latestToday = entries.filter { $0.value != nil && $0.tMs >= todayStart && $0.tMs <= nowMs }.map(\.tMs).max()
            return (today, latestToday.map { Date(timeIntervalSince1970: Double($0) / 1000) }, sparkValues)
        case .avg, .last:
            let newest = entries.enumerated()
                .filter { $0.element.value != nil && $0.element.tMs <= nowMs }
                .max { ($0.element.tMs, $0.offset) < ($1.element.tMs, $1.offset) }?.element
            return (newest?.value, newest.map { Date(timeIntervalSince1970: Double($0.tMs) / 1000) }, sparkValues)
        }
    }
}

enum MetricTileBuilder {
    /// Tiles in pin order. Water / fasting tiles are hidden while their tracker is off.
    static func tiles(pinIDs: [String], sources: MetricDataSources, now: Date = Date(), calendar: Calendar = .current, defaults: UserDefaults = .standard) -> [MetricTileModel] {
        var tiles: [MetricTileModel] = []
        for pin in pinIDs {
            guard let key = MetricKey(pinID: pin) else { continue }
            let descriptor = MetricCatalog.descriptor(for: key)
            switch key {
            case .app(let metric):
                if metric == .water, !defaults.bool(forKey: WaterSettings.enabledKey) { continue }
                if metric == .fasting, !defaults.bool(forKey: FastingSettings.enabledKey) { continue }
                let entries = AppMetricSampleExtractor.entries(for: metric, sources: sources, now: now, calendar: calendar)
                let tile = MetricTileMath.appTile(entries: entries, aggregation: descriptor.aggregation, now: now, calendar: calendar)
                let display = AppMetricFormat.display(tile.value, metric: metric)
                tiles.append(MetricTileModel(
                    key: pin, title: descriptor.title, systemImage: descriptor.systemImage, tint: descriptor.tint,
                    valueText: display.value, unitText: display.unit, at: tile.at,
                    sparkline: tile.sparkline.map { AppMetricFormat.chartValue($0, metric: metric) ?? $0 },
                    caption: tile.value == nil ? String(localized: "No data") : String(localized: "Today")
                ))
            case .nutrient(let nutrientKey):
                let parts = sources.nutrientTotals.entries(nutrientKey)
                let tile = MetricTileMath.appTile(entries: parts.food + parts.supplements, aggregation: .sum, now: now, calendar: calendar)
                tiles.append(MetricTileModel(
                    key: pin, title: descriptor.title, systemImage: descriptor.systemImage, tint: descriptor.tint,
                    valueText: NutrientCatalog.number(tile.value), unitText: NutrientCatalog.unit(nutrientKey), at: tile.at,
                    sparkline: tile.sparkline,
                    caption: tile.value == nil ? String(localized: "No data") : String(localized: "Today")
                ))
            case .derived(let metricID):
                guard let info = DerivedCatalog.shared.byID[metricID] else { continue }
                let snapshot = DerivedMetricStore.shared.tiles[pin]
                let latest = snapshot?.latest
                let display = DerivedMetricFormat.display(latest?.value, info: info)
                let when = latest.flatMap { InsightsDay.date($0.day, calendar: calendar) }.map { HealthUnitFormatting.relativeText($0) }
                let caption: String
                if let latest, let when {
                    caption = latest.isNative ? when : String(localized: "Estimated · \(when)")
                } else {
                    caption = String(localized: "No data")
                }
                tiles.append(MetricTileModel(
                    key: pin, title: descriptor.title, systemImage: descriptor.systemImage, tint: descriptor.tint,
                    valueText: display.value, unitText: display.unit, at: nil, sparkline: snapshot?.sparkline ?? [],
                    caption: caption
                ))
            case .health(let typeID):
                let model = sources.health.homeSnapshot?.tiles.first { $0.typeID == typeID }
                let type = sources.health.metricType(for: typeID)
                tiles.append(MetricTileModel(
                    key: pin, title: type.displayName, systemImage: descriptor.systemImage, tint: descriptor.tint,
                    valueText: model?.valueText ?? "—", unitText: model?.unitText ?? HealthUnitFormatting.unitLabel(for: type),
                    at: model?.at, sparkline: model?.sparkline ?? [], caption: nil
                ))
            }
        }
        return tiles
    }
}
