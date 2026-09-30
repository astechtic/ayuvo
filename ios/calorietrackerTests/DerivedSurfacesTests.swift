import Foundation
import Testing
@testable import calorietracker

/// Phase 1 surfaces of derived metrics (docs/derived-metrics.md): pin ids, native-wins merge, bucketing and the Coach
/// summary / Insights fallback over a real in-memory mirror.
@MainActor
struct DerivedSurfacesTests {
    private typealias F = HealthTestFixtures

    private static func row(_ metric: String, _ day: String, _ value: Double, quality: Double = 1) -> DerivedDailyValueRow {
        DerivedDailyValueRow(metricID: metric, day: day, value: value, quality: quality, algoVersion: 1, computedMs: 0)
    }

    @Test func derivedPinIDsRoundTripOnlyForCatalogMetrics() {
        #expect(MetricKey(pinID: "derived:resting_hr_derived") == .derived("resting_hr_derived"))
        #expect(MetricKey.derived("vo2max_estimate").id == "derived:vo2max_estimate")
        #expect(MetricKey(pinID: "derived:not_a_metric") == nil)
        #expect(MetricKey(pinID: "derived:") == nil)
    }

    @Test func favouritePinsKeepDerivedIDs() {
        let result = MetricsReference.favouritePinsMigrate(
            newRaw: "steps,derived:resting_hr_derived,derived:bogus", legacyRaw: nil,
            knownHealthIDs: MetricPins.knownHealthIDs, max: MetricPins.max
        )
        #expect(result.favourites == ["steps", "derived:resting_hr_derived"])
    }

    @Test func descriptorUsesCatalogFacts() throws {
        let info = try #require(DerivedCatalog.shared.byID["cardio_minutes"])
        let d = MetricCatalog.descriptor(for: .derived(info.id))
        #expect(d.domainID == "heart")
        #expect(d.aggregation == .sum)
        #expect(d.chartKind == .bar)
        #expect(d.ranges == [.week, .month, .sixMonths, .year])
        #expect(MetricCatalog.descriptor(for: .derived("tdee")).domainID == "activity")
    }

    @Test func nativeValueWinsPerDay() {
        let derived = [Self.row("resting_hr_derived", "2026-09-01", 58), Self.row("resting_hr_derived", "2026-09-02", 57)]
        let native = ["2026-09-02": DerivedPriority.Native(value: 55, source: "Apple Watch"),
                      "2026-09-03": DerivedPriority.Native(value: 54, source: "Apple Watch")]
        let merged = DerivedMetricSeries.merge(derived: derived, native: native, enabled: true)
        #expect(merged.map(\.day) == ["2026-09-01", "2026-09-02", "2026-09-03"])
        #expect(merged.map(\.value) == [58, 55, 54])
        #expect(merged.map(\.isNative) == [false, true, true])
        #expect(merged[1].source == "Apple Watch")
        // Switched off: only native days remain.
        #expect(DerivedMetricSeries.merge(derived: derived, native: native, enabled: false).map(\.day) == ["2026-09-02", "2026-09-03"])
        #expect(DerivedMetricFormat.sourceText(merged) == "From Apple Watch · Estimated by Ayuvo")
        #expect(DerivedMetricFormat.sourceText([merged[0]]) == "Estimated by Ayuvo")
    }

    @Test func nativeDaysIgnoreAyuvoWrittenAndDeletedRows() throws {
        let type = try #require(HealthMetricRegistry.type(id: "resting_heart_rate"))
        var deleted = F.row(type: type, start: F.date(2026, 9, 2), value: 40, source: "watch")
        deleted.deleted = 1
        let rows = [
            F.row(type: type, start: F.date(2026, 9, 1), value: 60, source: "watch"),
            F.row(type: type, start: F.date(2026, 9, 1), value: 62, source: "watch"),
            F.row(type: type, start: F.date(2026, 9, 2), value: 50, source: "com.ayuvo.health"),
            deleted,
        ]
        let days = DerivedMetricSeries.nativeDays(rows: rows, sourceNames: ["watch": "Apple Watch"], ownBundleID: "com.ayuvo.health")
        #expect(days.keys.sorted() == [rows[0].localDay])
        #expect(days[rows[0].localDay]?.value == 61)
        #expect(days[rows[0].localDay]?.source == "Apple Watch")
    }

    @Test func maxAggregationTakesBucketMaximum() throws {
        let info = try #require(DerivedCatalog.shared.byID["observed_max_hr"])
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let days = ["2026-06-01": 150.0, "2026-06-02": 170.0, "2026-06-03": 160.0].map {
            DerivedDayValue(day: $0.key, value: $0.value, isNative: false)
        }.sorted { $0.day < $1.day }
        let anchor = calendar.date(from: DateComponents(year: 2026, month: 6, day: 20, hour: 12))!
        let series = DerivedMetricSeries.build(days: days, info: info, range: .year, anchor: anchor, calendar: calendar, weekStart: .monday)
        let june = series.points.compactMap(\.value)
        #expect(june == [170])
    }

    @Test func coachDerivedSummaryAppliesNativeWins() async throws {
        let db = try await HealthDatabase.inMemory()
        let type = try #require(HealthMetricRegistry.type(id: "resting_heart_rate"))
        let native = F.row(type: type, start: F.date(2026, 9, 2, 8), value: 52, source: "watch")
        try await db.upsertSamples([native])
        try await db.replaceDerivedValues(
            [Self.row("resting_hr_derived", "2026-09-01", 58), Self.row("resting_hr_derived", native.localDay, 57)],
            metricIDs: ["resting_hr_derived"], days: ["2026-09-01", native.localDay]
        )
        let defaults = try #require(UserDefaults(suiteName: "DerivedSurfacesTests.\(UUID().uuidString)"))
        let summary = try #require(await HealthCoachQueryBuilder.derivedSummary(
            reader: db, dataType: "derived:resting_hr_derived", from: "2026-08-30", to: "2026-09-05", limit: 30, defaults: defaults
        ))
        #expect(summary.days.map(\.avg) == [58, 52])
        #expect(summary.days.map(\.sourceKind) == ["derived", "native"])
        #expect(summary.latest == 52)
        let json = summary.days[1].jsonObject
        #expect(json["source_kind"] as? String == "native")

        let types = await HealthCoachQueryBuilder.derivedDataTypes(reader: db, calendar: F.calendar, defaults: defaults)
        #expect(types.map(\.dataType) == ["derived:resting_hr_derived"])
        #expect(types.first?.jsonObject["derived"] as? Bool == true)

        defaults.set(["resting_hr_derived"], forKey: DerivedSettings.disabledKey)
        #expect(await HealthCoachQueryBuilder.derivedDataTypes(reader: db, calendar: F.calendar, defaults: defaults).isEmpty)
    }

    @Test func insightsFallBackToDerivedOnlyWithoutNative() async throws {
        let db = try await HealthDatabase.inMemory()
        try await db.replaceDerivedValues(
            [Self.row("resting_hr_derived", "2026-09-01", 58), Self.row("vo2max_estimate", "2026-09-01", 44)],
            metricIDs: ["resting_hr_derived", "vo2max_estimate"], days: ["2026-09-01"]
        )
        var off = InsightsInputs()
        await InsightsDataSource.healthInputs(into: &off, database: db, from: "2026-08-25", through: "2026-09-02", calendar: F.calendar)
        #expect(off.series["resting_heart_rate"] == nil)

        var on = InsightsInputs()
        await InsightsDataSource.healthInputs(into: &on, database: db, from: "2026-08-25", through: "2026-09-02", calendar: F.calendar,
                                              derivedFallback: ["resting_hr_derived", "vo2max_estimate"])
        #expect(on.series["resting_heart_rate"]?["2026-09-01"] == 58)
        #expect(on.series["vo2_max"]?["2026-09-01"] == 44)
    }

    @Test func valuesShowUpToTwoDecimals() throws {
        let dose = try #require(DerivedCatalog.shared.byID["sound_dose"])
        #expect(DerivedMetricFormat.display(0.386, info: dose).value == 0.39.formatted(.number.precision(.fractionLength(0...2))))
        let rhr = try #require(DerivedCatalog.shared.byID["resting_hr_derived"])
        #expect(DerivedMetricFormat.display(57.6, info: rhr).value == 57.6.formatted(.number.precision(.fractionLength(0...2))))
        #expect(DerivedMetricFormat.display(58, info: rhr).value == 58.0.formatted(.number.precision(.fractionLength(0...2))))
    }
}
