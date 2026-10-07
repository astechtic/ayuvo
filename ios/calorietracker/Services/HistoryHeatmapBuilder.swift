import Foundation

/// Pure assembly of `HistoryHeatmapSnapshot` (docs/widgets.md "History widgets"). Workouts come
/// from the same entries as the `app:workout_minutes` metric; food from the diary entries, keyed by
/// the day the diary shows them on.
enum HistoryHeatmapBuilder {
    struct Workout {
        var day: Date
        var seconds: Double
    }

    struct Food {
        var day: Date
        var mealType: String
    }

    static func build(
        now: Date,
        calendar: Calendar,
        weekStart: HistoryHeatmap.WeekStart,
        workouts: [Workout],
        foods: [Food]
    ) -> HistoryHeatmapSnapshot {
        let today = calendar.startOfDay(for: now)
        let count = HistoryHeatmap.days
        var index: [String: Int] = [:]
        index.reserveCapacity(count)
        for offset in 0..<count {
            guard let day = calendar.date(byAdding: .day, value: offset - (count - 1), to: today) else { continue }
            index[HistoryHeatmap.dayKey(day, calendar: calendar)] = offset
        }

        var workoutCounts = [Int](repeating: 0, count: count)
        var workoutSeconds = [Double](repeating: 0, count: count)
        for workout in workouts {
            guard let i = index[HistoryHeatmap.dayKey(workout.day, calendar: calendar)] else { continue }
            workoutCounts[i] += 1
            workoutSeconds[i] += max(0, workout.seconds.isFinite ? workout.seconds : 0)
        }
        var meals = [Set<String>](repeating: [], count: count)
        for food in foods {
            guard let i = index[HistoryHeatmap.dayKey(food.day, calendar: calendar)] else { continue }
            meals[i].insert(food.mealType)
        }

        let workoutLevels = (0..<count).map { HistoryHeatmap.workoutLevel(workouts: workoutCounts[$0], seconds: workoutSeconds[$0]) }
        let foodLevels = meals.map { HistoryHeatmap.foodLevel(mealTypes: $0) }
        return HistoryHeatmapSnapshot(
            generatedAt: now,
            lastDay: HistoryHeatmap.dayKey(today, calendar: calendar),
            weekStart: weekStart,
            workout: .init(
                levels: digits(workoutLevels),
                activeDays: workoutLevels.filter { $0 > 0 }.count,
                totalSeconds: workoutSeconds.reduce(0, +)
            ),
            food: .init(levels: digits(foodLevels), activeDays: foodLevels.filter { $0 > 0 }.count, totalSeconds: nil)
        )
    }

    private static func digits(_ levels: [Int]) -> String {
        String(levels.map { Character(String(max(0, min(HistoryHeatmap.levels, $0)))) })
    }
}

extension HistoryHeatmapBuilder {
    /// From the stores, the way the app's metric and diary screens read them.
    @MainActor
    static func build(sources: MetricDataSources, now: Date, calendar: Calendar = .current,
                      defaults: UserDefaults = .standard) -> HistoryHeatmapSnapshot {
        let entries = AppMetricSampleExtractor.entries(for: .workoutMinutes, sources: sources, now: now, calendar: calendar)
        let workouts = entries.map {
            Workout(day: Date(timeIntervalSince1970: Double($0.tMs) / 1000), seconds: $0.value ?? 0)
        }
        let foods = sources.food.entries.map { Food(day: $0.timestamp, mealType: $0.mealType.rawValue) }
        let weekStart = HistoryHeatmap.WeekStart(rawValue: ActivitySettings.weekStart(defaults: defaults).rawValue) ?? .monday
        return build(now: now, calendar: calendar, weekStart: weekStart, workouts: workouts, foods: foods)
    }
}
