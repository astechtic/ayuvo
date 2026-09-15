import Foundation
import Testing
@testable import calorietracker

struct HealthChartSeriesBuilderTests {
    private typealias F = HealthTestFixtures

    private func rollup(_ type: HealthMetricType, day: String, sum: Double? = nil, avg: Double? = nil, min: Double? = nil, max: Double? = nil, count: Int = 1, last: Double? = nil) -> HealthDailyRollupRow {
        var row = HealthDailyRollupRow(typeID: type.id, day: day, tz: F.calendar.timeZone.identifier)
        row.sum = sum
        row.avg = avg
        row.min = min
        row.max = max
        row.count = count
        row.lastValue = last
        row.lastAtMs = F.ms(HealthChartSeriesBuilder.dayDate(day, calendar: F.calendar)!)
        return row
    }

    @Test func rangeIntervalsContainTheAnchor() {
        let anchor = F.date(2026, 9, 14, 15)
        for range in HealthDetailRange.allCases {
            let interval = range.interval(containing: anchor, calendar: F.calendar)
            #expect(interval.contains(anchor), Comment(rawValue: range.rawValue))
        }
        let sixMonths = HealthDetailRange.sixMonths.interval(containing: anchor, calendar: F.calendar)
        #expect(F.calendar.component(.month, from: sixMonths.start) == 4)
    }

    @Test func weekSeriesUsesDailySumsForCumulativeTypes() {
        let anchor = F.date(2026, 9, 9)
        let rollups = [
            rollup(F.steps, day: "2026-09-07", sum: 8000, count: 10, last: 800),
            rollup(F.steps, day: "2026-09-08", sum: 12000, count: 12, last: 900),
            rollup(F.steps, day: "2026-09-20", sum: 1, count: 1), // outside the week
        ]
        let series = HealthChartSeriesBuilder.build(range: .week, anchor: anchor, type: F.steps, rows: [], rollups: rollups, calendar: F.calendar)
        #expect(series.points.count == 2)
        #expect(series.points.map(\.value) == [8000, 12000])
        #expect(series.highlights.total == 20000)
        #expect(series.highlights.average == 10000)
        #expect(series.highlights.latest == 12000)
    }

    @Test func yearSeriesBucketsByMonthWithWeightedAverages() {
        let anchor = F.date(2026, 6, 1)
        let rollups = [
            rollup(F.heartRate, day: "2026-03-01", avg: 60, min: 50, max: 70, count: 1),
            rollup(F.heartRate, day: "2026-03-15", avg: 80, min: 55, max: 120, count: 3),
            rollup(F.heartRate, day: "2026-04-02", avg: 65, min: 60, max: 70, count: 2),
        ]
        let series = HealthChartSeriesBuilder.build(range: .year, anchor: anchor, type: F.heartRate, rows: [], rollups: rollups, calendar: F.calendar)
        #expect(series.points.count == 2)
        #expect(abs((series.points[0].value ?? 0) - 75) < 0.0001) // (60 + 80*3) / 4
        #expect(series.points[0].min == 50)
        #expect(series.points[0].max == 120)
        #expect(series.highlights.min == 50)
        #expect(series.highlights.max == 120)
    }

    @Test func daySeriesBucketsRowsByHour() {
        let anchor = F.date(2026, 9, 3, 12)
        let dayStart = F.calendar.startOfDay(for: anchor)
        let rows = [
            F.row(id: "a", type: F.steps, start: dayStart.addingTimeInterval(3600 * 8 + 60), value: 100),
            F.row(id: "b", type: F.steps, start: dayStart.addingTimeInterval(3600 * 8 + 900), value: 20),
            F.row(id: "c", type: F.steps, start: dayStart.addingTimeInterval(3600 * 20), value: 5),
        ]
        let series = HealthChartSeriesBuilder.build(range: .day, anchor: anchor, type: F.steps, rows: rows, rollups: [], calendar: F.calendar)
        #expect(series.points.count == 2)
        #expect(series.points[0].value == 120)
        #expect(F.calendar.component(.hour, from: series.points[1].start) == 20)
    }

    @Test func sleepSeriesProducesStageSegmentsAndNightStacks() {
        let bed = F.date(2026, 9, 9, 23, 0)
        let rows = [
            F.row(id: "a", type: F.sleep, start: bed, end: bed.addingTimeInterval(3 * 3600), categoryValue: 3),
            F.row(id: "b", type: F.sleep, start: bed.addingTimeInterval(3 * 3600), end: bed.addingTimeInterval(5 * 3600), categoryValue: 4),
            F.row(id: "c", type: F.sleep, start: bed.addingTimeInterval(5 * 3600), end: bed.addingTimeInterval(5 * 3600 + 600), categoryValue: 2),
        ]
        let day = HealthChartSeriesBuilder.build(range: .day, anchor: F.date(2026, 9, 10, 10), type: F.sleep, rows: rows, rollups: [], calendar: F.calendar)
        #expect(day.stagePoints.count == 3)
        #expect(day.points.isEmpty)
        let week = HealthChartSeriesBuilder.build(range: .week, anchor: F.date(2026, 9, 10, 10), type: F.sleep, rows: rows, rollups: [], calendar: F.calendar)
        #expect(week.stagePoints.compactMap(\.stage).sorted() == [2, 3, 4])
        #expect(abs((week.highlights.total ?? -1) - 5.0 * 3600.0) < 0.5)
        #expect(week.highlights.count == 1)
    }

    @Test func latestAggregationUsesLastValuePerBucket() {
        let anchor = F.date(2026, 9, 9)
        let rollups = [
            rollup(F.weight, day: "2026-09-07", avg: 80.5, min: 80, max: 81, count: 2, last: 81),
            rollup(F.weight, day: "2026-09-08", avg: 79.5, min: 79, max: 80, count: 2, last: 79),
        ]
        let series = HealthChartSeriesBuilder.build(range: .week, anchor: anchor, type: F.weight, rows: [], rollups: rollups, calendar: F.calendar)
        #expect(series.points.map(\.value) == [81, 79])
        #expect(series.highlights.latest == 79)
    }
}
