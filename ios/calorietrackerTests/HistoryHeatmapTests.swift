import Foundation
import Testing
@testable import calorietracker

/// `shared/widgets/history_heatmap.json` vectors and the snapshot builder (docs/widgets.md "History widgets").
struct HistoryHeatmapTests {
    private static var contract: [String: Any] {
        get throws {
            let url = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/widgets/history_heatmap.json")
            return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        }
    }

    private static var vectors: [String: Any] {
        get throws { try #require(try contract["vectors"] as? [String: Any]) }
    }

    private static func calendar(_ firstWeekday: Int = 2) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Berlin")!
        calendar.firstWeekday = firstWeekday
        return calendar
    }

    @Test func constantsMatchContract() throws {
        let contract = try Self.contract
        #expect(contract["format"] as? String == "ayuvo-history-heatmap")
        #expect(contract["version"] as? Int == 1)
        #expect(contract["days"] as? Int == HistoryHeatmap.days)
        #expect(contract["levels"] as? Int == HistoryHeatmap.levels)
        let workout = try #require(contract["workout"] as? [String: Any])
        #expect((workout["min_seconds_per_level"] as? [Double]) == HistoryHeatmap.workoutMinSecondsPerLevel)
        let food = try #require(contract["food"] as? [String: Any])
        #expect((food["min_meals_per_level"] as? [Int]) == HistoryHeatmap.foodMinMealsPerLevel)
    }

    @Test func workoutLevelVectors() throws {
        let cases = try #require(try Self.vectors["workout_level"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let workouts = try #require(c["workouts"] as? Int)
            let seconds = try #require(c["seconds"] as? Double)
            #expect(HistoryHeatmap.workoutLevel(workouts: workouts, seconds: seconds) == c["level"] as? Int, "\(c)")
        }
    }

    @Test func foodLevelVectors() throws {
        let cases = try #require(try Self.vectors["food_level"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let meals = try #require(c["meals"] as? [String])
            #expect(HistoryHeatmap.foodLevel(mealTypes: meals) == c["level"] as? Int, "\(c)")
        }
    }

    /// The grid ignores the calendar's own first weekday: only the app's week start counts.
    @Test(arguments: [1, 2])
    func gridVectors(calendarFirstWeekday: Int) throws {
        let calendar = Self.calendar(calendarFirstWeekday)
        let cases = try #require(try Self.vectors["grid"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let todayKey = try #require(c["today"] as? String)
            let weekStartRaw = try #require(c["week_start"] as? String)
            let columns = try #require(c["columns"] as? Int)
            let today = try #require(HistoryHeatmap.date(dayKey: todayKey, calendar: calendar))
            let weekStart = try #require(HistoryHeatmap.WeekStart(rawValue: weekStartRaw))
            let grid = HistoryHeatmap.grid(
                today: today.addingTimeInterval(15 * 3600), weekStart: weekStart, columns: columns, calendar: calendar
            )
            #expect(HistoryHeatmap.dayKey(grid.firstDay, calendar: calendar) == c["first_day"] as? String, "\(c)")
            #expect(grid.todayRow == c["today_row"] as? Int, "\(c)")
            #expect(grid.hiddenAfterToday == c["hidden_after_today"] as? Int, "\(c)")
        }
    }

    @Test func builderPlacesDaysAndCountsActiveDays() throws {
        let calendar = Self.calendar()
        let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 10, day: 7, hour: 9)))
        func day(_ back: Int, hour: Int = 12) -> Date {
            calendar.date(byAdding: .day, value: -back, to: calendar.date(bySettingHour: hour, minute: 0, second: 0, of: now)!)!
        }
        let snapshot = HistoryHeatmapBuilder.build(
            now: now, calendar: calendar, weekStart: .monday,
            workouts: [
                .init(day: day(0), seconds: 1_200), .init(day: day(0), seconds: 1_200), // 40 min → 3
                .init(day: day(2), seconds: 0), // logged without a duration → 1
                .init(day: day(400), seconds: 5_000), // outside the window
            ],
            foods: [
                .init(day: day(1, hour: 8), mealType: "breakfast"), .init(day: day(1, hour: 23), mealType: "dinner"),
                .init(day: day(370), mealType: "snack"),
            ]
        )
        #expect(snapshot.lastDay == "2026-10-07")
        #expect(snapshot.workout.levels.count == HistoryHeatmap.days)
        #expect(snapshot.food.levels.count == HistoryHeatmap.days)
        #expect(snapshot.level(.workout, on: day(0), calendar: calendar) == 3)
        #expect(snapshot.level(.workout, on: day(1), calendar: calendar) == 0)
        #expect(snapshot.level(.workout, on: day(2), calendar: calendar) == 1)
        #expect(snapshot.workout.activeDays == 2)
        #expect(snapshot.workout.totalSeconds == 2_400)
        #expect(snapshot.level(.food, on: day(1), calendar: calendar) == 2)
        #expect(snapshot.level(.food, on: day(370), calendar: calendar) == 1)
        #expect(snapshot.food.activeDays == 2)
        // Outside the covered range (future, or older than the window) reads 0.
        #expect(snapshot.level(.food, on: day(-1), calendar: calendar) == 0)
        #expect(snapshot.level(.food, on: day(371), calendar: calendar) == 0)
    }

    @Test func snapshotRoundTripsAndComparesContent() throws {
        let calendar = Self.calendar()
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        let a = HistoryHeatmapBuilder.build(now: now, calendar: calendar, weekStart: .sunday, workouts: [], foods: [])
        let data = try #require(HistoryHeatmapSnapshot.encode(a))
        let decoded = try #require(HistoryHeatmapSnapshot.decode(data))
        #expect(decoded == a)
        let b = HistoryHeatmapBuilder.build(now: now.addingTimeInterval(60), calendar: calendar, weekStart: .sunday, workouts: [], foods: [])
        #expect(a.hasSameContent(as: b))
        #expect(a != b)
    }
}
