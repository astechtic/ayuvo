import Foundation
import Testing
@testable import calorietracker

/// Tap a day → Day chart (docs/charts.md › Tap a day) and anchor persistence across ranges.
@MainActor
struct MetricDetailModelTests {
    private typealias F = HealthTestFixtures

    private func point(_ day: Int, value: Double?) -> HealthChartPoint {
        let start = F.calendar.startOfDay(for: F.date(2026, 9, day))
        return HealthChartPoint(start: start, end: start.addingTimeInterval(86_400), value: value, min: nil, max: nil, value2: nil, count: value == nil ? 0 : 1, stage: nil)
    }

    @Test func tappingAWeekDayWithDataOpensThatDay() {
        let model = MetricDetailModel(key: .health("steps"), range: .week)
        model.anchor = F.date(2026, 9, 17, 12)
        model.tap(point(15, value: 8000), ranges: HealthDetailRange.allCases, calendar: F.calendar)
        #expect(model.range == .day)
        #expect(model.anchor == F.calendar.startOfDay(for: F.date(2026, 9, 15)))
        #expect(model.selected == nil)
    }

    @Test func monthDaysDrillToo() {
        let model = MetricDetailModel(key: .app(.calories), range: .month)
        model.tap(point(3, value: 1800), ranges: HealthDetailRange.allCases, calendar: F.calendar)
        #expect(model.range == .day)
    }

    @Test func emptyDaysAndMetricsWithoutDayOnlySelect() {
        let steps = MetricDetailModel(key: .health("steps"), range: .week)
        let empty = point(16, value: nil)
        steps.tap(empty, ranges: HealthDetailRange.allCases, calendar: F.calendar)
        #expect(steps.range == .week)
        #expect(steps.selected == empty)
        steps.tap(empty, ranges: HealthDetailRange.allCases, calendar: F.calendar)
        #expect(steps.selected == nil, "tapping the selected bucket again clears it")

        let weight = MetricDetailModel(key: .app(.weight), range: .week)
        let day = point(15, value: 80)
        weight.tap(day, ranges: [.week, .month, .sixMonths, .year], calendar: F.calendar)
        #expect(weight.range == .week)
        #expect(weight.selected == day)
    }

    @Test func yearBucketsNeverDrill() {
        let model = MetricDetailModel(key: .health("steps"), range: .year)
        model.tap(point(1, value: 9000), ranges: HealthDetailRange.allCases, calendar: F.calendar)
        #expect(model.range == .year)
    }

    @Test func rangeChangeKeepsTheAnchorAndClearsSelection() {
        let model = MetricDetailModel(key: .health("steps"), range: .week)
        model.tap(point(15, value: 8000), ranges: HealthDetailRange.allCases, calendar: F.calendar)
        let drilled = model.anchor
        model.range = .week
        model.selected = point(15, value: 8000)
        model.rangeChanged(now: F.date(2026, 9, 22, 12))
        #expect(model.anchor == drilled, "W after a drill shows the drilled day's week")
        #expect(model.selected == nil)
        #expect(model.rangeTitle(calendar: F.calendar).isEmpty == false)

        model.anchor = F.date(2026, 9, 30)
        model.rangeChanged(now: F.date(2026, 9, 22, 12))
        #expect(model.anchor == F.date(2026, 9, 22, 12), "never past now")
    }
}

/// Shared x labels land on the right buckets for every range (docs/charts.md › Visual spec).
@MainActor
struct ChartAxisStyleTests {
    private typealias F = HealthTestFixtures

    @Test func sixMonthLabelsCoverEveryMonthOfTheInterval() {
        let interval = HealthDetailRange.sixMonths.interval(containing: F.date(2026, 9, 16, 12), calendar: F.calendar, weekStart: .monday)
        let marks = ChartAxisStyle.xMarks(range: .sixMonths, interval: interval, calendar: F.calendar, weekStart: .monday)
        #expect(marks.labels.count == 6)
        #expect(marks.labels.allSatisfy { interval.contains($0) })
        #expect(Set(marks.labels.map { F.calendar.component(.month, from: $0) }) == Set(4...9))
    }

    @Test func weekAndDayLabels() {
        let week = HealthDetailRange.week.interval(containing: F.date(2026, 9, 16, 12), calendar: F.calendar, weekStart: .monday)
        #expect(ChartAxisStyle.xMarks(range: .week, interval: week, calendar: F.calendar, weekStart: .monday).labels.count == 7)
        let day = HealthDetailRange.day.interval(containing: F.date(2026, 9, 16, 12), calendar: F.calendar, weekStart: .monday)
        let hours = ChartAxisStyle.xMarks(range: .day, interval: day, calendar: F.calendar, weekStart: .monday).labels
        #expect(hours.map { F.calendar.component(.hour, from: $0) } == [0, 6, 12, 18])
    }
}
