import Foundation
import Testing
@testable import calorietracker

struct HealthRollupMathTests {
    private typealias F = HealthTestFixtures
    private let tz = HealthTestFixtures.calendar.timeZone.identifier

    @Test func dayKeyHonoursTheStoredOffsetAcrossDST() {
        // 2026-03-29 02:30 Berlin does not exist (DST jump); 00:30 UTC on the 29th is 01:30 CET.
        let utc = TimeZone(identifier: "UTC")!
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = utc
        let instant = cal.date(from: DateComponents(year: 2026, month: 3, day: 28, hour: 23, minute: 30))!
        let ms = F.ms(instant)
        #expect(HealthRollupMath.dayKey(ms: ms, offsetS: 0, calendar: F.calendar) == "2026-03-28")
        #expect(HealthRollupMath.dayKey(ms: ms, offsetS: 3600, calendar: F.calendar) == "2026-03-29")
        #expect(HealthRollupMath.dayKey(ms: ms, offsetS: nil, calendar: F.calendar) == "2026-03-29")
    }

    @Test func endAttributionUsesWakeDayAndMidnightBelongsToPreviousDay() {
        let start = F.ms(F.date(2026, 9, 9, 23, 0))
        let midnight = F.ms(F.date(2026, 9, 10, 0, 0))
        let morning = F.ms(F.date(2026, 9, 10, 6, 0))
        let offset = 7200
        #expect(HealthRollupMath.localDay(startMs: start, endMs: morning, startOffsetS: offset, endOffsetS: offset, attribution: .end, calendar: F.calendar) == "2026-09-10")
        #expect(HealthRollupMath.localDay(startMs: start, endMs: midnight, startOffsetS: offset, endOffsetS: offset, attribution: .end, calendar: F.calendar) == "2026-09-09")
        #expect(HealthRollupMath.localDay(startMs: start, endMs: morning, startOffsetS: offset, endOffsetS: offset, attribution: .start, calendar: F.calendar) == "2026-09-09")
    }

    @Test func cumulativeRollupSumsAndTracksOwnBurn() throws {
        let day = F.date(2026, 9, 3, 8)
        let rows = [
            F.row(id: "a", type: F.activeEnergy, start: day, value: 300),
            F.row(id: "b", type: F.activeEnergy, start: day.addingTimeInterval(3600), value: 200, source: F.bundleID, extraJSON: "{\"ayuvo_workout_session_id\":\"s1\"}"),
            F.row(id: "c", type: F.activeEnergy, start: day.addingTimeInterval(7200), value: 100, source: F.bundleID),
        ]
        let rollup = try #require(HealthRollupMath.dailyRollup(rows: rows, type: F.activeEnergy, day: rows[0].localDay, tz: tz, ownBundleID: F.bundleID, calendar: F.calendar))
        #expect(rollup.sum == 600)
        #expect(rollup.count == 3)
        #expect(rollup.min == 100)
        #expect(rollup.max == 300)
        #expect(rollup.lastValue == 100)
        #expect(rollup.ownSum == 200)
    }

    @Test func discreteRollupWeightsByCountAndUsesCondensedMinMax() throws {
        let day = F.date(2026, 9, 3, 8)
        let rows = [
            F.row(id: "a", type: F.heartRate, start: day, value: 60, value2: 50, value3: 70, count: 3),
            F.row(id: "b", type: F.heartRate, start: day.addingTimeInterval(60), value: 100, value2: 20, value3: 200, count: 1),
        ]
        let rollup = try #require(HealthRollupMath.dailyRollup(rows: rows, type: F.heartRate, day: rows[0].localDay, tz: tz, ownBundleID: F.bundleID, calendar: F.calendar))
        #expect(rollup.count == 4)
        #expect(abs((rollup.avg ?? 0) - 70) < 0.0001) // (60*3 + 100) / 4
        #expect(rollup.min == 50, "single-sample rows use value, not value2")
        #expect(rollup.max == 100)
        #expect(rollup.sum == nil)
    }

    @Test func bloodPressureFillsDiastolicStatistics() throws {
        let day = F.date(2026, 9, 3, 8)
        let rows = [
            F.row(id: "a", type: F.bloodPressure, start: day, value: 120, value2: 80),
            F.row(id: "b", type: F.bloodPressure, start: day.addingTimeInterval(60), value: 130, value2: 84),
        ]
        let rollup = try #require(HealthRollupMath.dailyRollup(rows: rows, type: F.bloodPressure, day: rows[0].localDay, tz: tz, ownBundleID: F.bundleID, calendar: F.calendar))
        #expect(rollup.avg == 125)
        #expect(rollup.min == 120)
        #expect(rollup.max == 130)
        #expect(rollup.v2Avg == 82)
        #expect(rollup.v2Min == 80)
        #expect(rollup.v2Max == 84)
    }

    @Test func durationRollupSumsSpans() throws {
        let type = HealthMetricRegistry.type(id: "mindfulness_session")!
        let day = F.date(2026, 9, 3, 8)
        let rows = [
            F.row(id: "a", type: type, start: day, end: day.addingTimeInterval(600), value: 600, categoryValue: 0),
            F.row(id: "b", type: type, start: day.addingTimeInterval(7200), end: day.addingTimeInterval(7500), value: 300, categoryValue: 0),
        ]
        let rollup = try #require(HealthRollupMath.dailyRollup(rows: rows, type: type, day: rows[0].localDay, tz: tz, ownBundleID: F.bundleID, calendar: F.calendar))
        #expect(rollup.durationS == 900)
        #expect(rollup.sum == 900)
        #expect(rollup.count == 2)
    }

    @Test func sleepRollupUsesNightAnalysis() throws {
        let bed = F.date(2026, 9, 9, 23, 0)
        let rows = [
            F.row(id: "a", type: F.sleep, start: bed, end: bed.addingTimeInterval(3600 * 8), categoryValue: 0),
            F.row(id: "b", type: F.sleep, start: bed, end: bed.addingTimeInterval(3600 * 3), categoryValue: 3),
            F.row(id: "c", type: F.sleep, start: bed.addingTimeInterval(3600 * 3), end: bed.addingTimeInterval(3600 * 4), categoryValue: 2),
            F.row(id: "d", type: F.sleep, start: bed.addingTimeInterval(3600 * 4), end: bed.addingTimeInterval(3600 * 8), categoryValue: 4),
        ]
        let rollup = try #require(HealthRollupMath.dailyRollup(rows: rows, type: F.sleep, day: "2026-09-10", tz: tz, ownBundleID: F.bundleID, calendar: F.calendar))
        let expectedSeconds = 7.0 * 3600.0
        #expect(abs((rollup.sum ?? -1) - expectedSeconds) < 0.5)
        #expect(abs((rollup.durationS ?? -1) - expectedSeconds) < 0.5)
    }

    @Test func deletedRowsAndEmptyDaysProduceNoRollup() {
        var row = F.row(type: F.steps, start: F.date(2026, 9, 3), value: 10)
        row.deleted = 1
        #expect(HealthRollupMath.dailyRollup(rows: [row], type: F.steps, day: row.localDay, tz: tz, ownBundleID: F.bundleID, calendar: F.calendar) == nil)
        #expect(HealthRollupMath.dailyRollup(rows: [], type: F.steps, day: "2026-09-03", tz: tz, ownBundleID: F.bundleID, calendar: F.calendar) == nil)
    }

    @Test func hourlyBucketsGroupByLocalHour() {
        let dayStart = F.calendar.startOfDay(for: F.date(2026, 9, 3))
        let rows = [
            F.row(id: "a", type: F.steps, start: dayStart.addingTimeInterval(3600 * 9 + 60), value: 100),
            F.row(id: "b", type: F.steps, start: dayStart.addingTimeInterval(3600 * 9 + 600), value: 50),
            F.row(id: "c", type: F.steps, start: dayStart.addingTimeInterval(3600 * 17), value: 25),
        ]
        let buckets = HealthRollupMath.hourlyBuckets(rows: rows, type: F.steps, dayStart: dayStart, calendar: F.calendar)
        #expect(buckets.map(\.hour) == [9, 17])
        #expect(buckets.first?.sum == 150)
        #expect(buckets.first?.count == 2)
    }
}
