import Foundation
import Testing
@testable import calorietracker

struct AppMetricSeriesProviderTests {
    private typealias F = HealthTestFixtures
    private typealias E = MetricsReference.Entry

    private func e(_ date: Date, _ value: Double?) -> E { E(tMs: F.ms(date), value: value) }

    @Test func weekCaloriesSumPerDayAndLeaveEmptyDaysNil() {
        let entries = [e(F.date(2026, 9, 7, 8), 500), e(F.date(2026, 9, 7, 13), 700), e(F.date(2026, 9, 9, 19), 900)]
        let series = AppMetricSeriesProvider.build(entries: entries, aggregation: .sum, range: .week, anchor: F.date(2026, 9, 9), calendar: F.calendar, weekStart: .monday)
        #expect(series.points.map(\.value) == [1200, nil, 900, nil, nil, nil, nil])
        #expect(series.highlights.total == 2100)
        #expect(series.headline?.kind == .average)
        #expect(series.headline?.value == 1050, "average over logged days only")
        #expect(series.highlights.latest == 900)
    }

    @Test func dayCaloriesAreHourly() {
        let entries = [e(F.date(2026, 9, 9, 8, 10), 300), e(F.date(2026, 9, 9, 8, 50), 200)]
        let series = AppMetricSeriesProvider.build(entries: entries, aggregation: .sum, range: .day, anchor: F.date(2026, 9, 9), calendar: F.calendar, weekStart: .monday)
        #expect(series.points.count == 24)
        #expect(series.points[8].value == 500)
        #expect(series.headline?.kind == .total)
        #expect(series.headline?.value == 500)
    }

    @Test func weightUsesLastValuePerDayWithRange() {
        let entries = [e(F.date(2026, 9, 7, 7), 81), e(F.date(2026, 9, 7, 21), 80), e(F.date(2026, 9, 8, 7), 79.5)]
        let series = AppMetricSeriesProvider.build(entries: entries, aggregation: .last, range: .week, anchor: F.date(2026, 9, 9), calendar: F.calendar, weekStart: .monday)
        #expect(series.points[0].value == 80)
        #expect(series.points[0].min == 80)
        #expect(series.points[0].max == 81)
        #expect(series.headline?.kind == .latest)
        #expect(series.headline?.value == 79.5)
    }

    @Test func sixMonthSumIsTheMeanOfLoggedDays() {
        let entries = [e(F.date(2026, 9, 7, 8), 1000), e(F.date(2026, 9, 8, 8), 3000)]
        let series = AppMetricSeriesProvider.build(entries: entries, aggregation: .sum, range: .sixMonths, anchor: F.date(2026, 9, 9), calendar: F.calendar, weekStart: .monday)
        #expect(series.points.compactMap(\.value) == [2000])
        #expect(series.interval.start == F.calendar.date(from: DateComponents(year: 2026, month: 4, day: 1)))
    }

    @Test func yearHasTwelveMonths() {
        let series = AppMetricSeriesProvider.build(entries: [e(F.date(2026, 2, 3), 2)], aggregation: .sum, range: .year, anchor: F.date(2026, 9, 9), calendar: F.calendar, weekStart: .monday)
        #expect(series.points.count == 12)
        #expect(series.points[1].value == 2)
        #expect(!series.isEmpty)
    }

    @Test func emptySeriesIsEmpty() {
        let series = AppMetricSeriesProvider.build(entries: [], aggregation: .sum, range: .month, anchor: F.date(2026, 9, 9), calendar: F.calendar, weekStart: .monday)
        #expect(series.points.count == 30)
        #expect(series.isEmpty)
        #expect(series.headline?.value == nil)
        #expect(series.highlights.total == nil)
    }

    @Test func fastingSplitsAcrossMidnight() {
        let zone = MetricsReference.Zone(calendar: F.calendar)
        let days = MetricsReference.fastingSecondsPerDay(
            sessions: [.init(startedAtMs: F.ms(F.date(2026, 9, 8, 22)), endedAtMs: F.ms(F.date(2026, 9, 9, 14)))],
            nowMs: F.ms(F.date(2026, 9, 10)), zone: zone
        )
        #expect(days.map(\.seconds) == [7_200, 50_400])
    }

    @MainActor
    @Test func extractorReadsStoresAndRevisionsBump() {
        let suite = "AppMetricSeriesProviderTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let water = WaterStore(defaults: defaults)
        let before = water.revision
        _ = water.add(milliliters: 250, on: F.date(2026, 9, 9, 9))
        #expect(water.revision != before)
        let sources = MetricDataSources(
            food: FoodStore(observesExternalChanges: false, defaults: defaults), water: water,
            fasting: FastingStore(defaults: defaults),
            weight: WeightStore(observesExternalChanges: false, defaults: defaults), bodyFat: BodyFatStore(),
            workouts: StrengthWorkoutStore(defaults: defaults), importedWorkouts: ImportedHealthWorkoutStore(defaults: defaults),
            health: HealthDataStore(defaults: defaults), profile: ProfileStore()
        )
        let entries = AppMetricSampleExtractor.entries(for: .water, sources: sources, calendar: F.calendar)
        #expect(entries.compactMap(\.value) == [250])
        #expect(entries.first?.tMs == F.ms(F.date(2026, 9, 9, 9)))
    }
}

struct MetricTileBuilderTests {
    private typealias F = HealthTestFixtures

    @Test func summedTileShowsTodayAndSevenDaySparkline() {
        let entries = [
            MetricsReference.Entry(tMs: F.ms(F.date(2026, 9, 8, 9)), value: 1500),
            MetricsReference.Entry(tMs: F.ms(F.date(2026, 9, 9, 9)), value: 400),
            MetricsReference.Entry(tMs: F.ms(F.date(2026, 9, 9, 12)), value: 600),
        ]
        let tile = MetricTileMath.appTile(entries: entries, aggregation: .sum, now: F.date(2026, 9, 9, 18), calendar: F.calendar)
        #expect(tile.value == 1000)
        #expect(tile.sparkline == [1500, 1000])
        #expect(tile.at == F.date(2026, 9, 9, 12))
    }

    @Test func noDataTodayIsNilNotZero() {
        let entries = [MetricsReference.Entry(tMs: F.ms(F.date(2026, 9, 1, 9)), value: 1500)]
        let tile = MetricTileMath.appTile(entries: entries, aggregation: .sum, now: F.date(2026, 9, 9, 18), calendar: F.calendar)
        #expect(tile.value == nil)
        #expect(tile.sparkline.isEmpty)
    }

    @Test func latestTileUsesNewestReading() {
        let entries = [
            MetricsReference.Entry(tMs: F.ms(F.date(2026, 8, 1)), value: 82),
            MetricsReference.Entry(tMs: F.ms(F.date(2026, 9, 1)), value: 80),
        ]
        let tile = MetricTileMath.appTile(entries: entries, aggregation: .last, now: F.date(2026, 9, 9), calendar: F.calendar)
        #expect(tile.value == 80)
        #expect(tile.at == F.date(2026, 9, 1))
    }

    @Test func pinsMigrationRules() {
        let known = ["steps", "sleep", "heart_rate", "active_energy"]
        let fresh = MetricsReference.favouritePinsMigrate(newRaw: nil, legacyRaw: nil, knownHealthIDs: known, max: 12)
        #expect(fresh.source == .default)
        #expect(fresh.favourites.first == "app:calories")
        let legacy = MetricsReference.favouritePinsMigrate(newRaw: nil, legacyRaw: "sleep,steps,bogus", knownHealthIDs: known, max: 12)
        #expect(Array(legacy.favourites.prefix(2)) == ["sleep", "steps"])
        #expect(!legacy.favourites.contains("bogus"))
        #expect(legacy.source == .migrated)
        #expect(MetricsReference.favouritePinsMigrate(newRaw: nil, legacyRaw: "", knownHealthIDs: known, max: 12).favourites.isEmpty)
        let stored = MetricsReference.favouritePinsMigrate(newRaw: "app:water,steps", legacyRaw: "sleep", knownHealthIDs: known, max: 12)
        #expect(stored.favourites == ["app:water", "steps"])
        #expect(stored.source == .new)
    }

    @Test func pinsLoadIsIdempotent() {
        let suite = "MetricTileBuilderTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(["steps"], forKey: MetricPins.legacyKey)
        let first = MetricPins.load(defaults: defaults)
        defaults.set(["sleep"], forKey: MetricPins.legacyKey)
        let second = MetricPins.load(defaults: defaults)
        #expect(first == second, "the legacy key is read only once")
        #expect(first.first == "steps")
    }
}
