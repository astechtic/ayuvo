import Foundation

// Daily workout aggregation shared by the metric engine and the workout history views.

struct WorkoutBurnDay: Identifiable, Equatable {
    let date: Date
    let calories: Int
    var id: Date { date }
}

enum WorkoutBurnAggregation {
    static let reliableCalories = 1...5_000

    static func isReliable(_ calories: Int?) -> Bool {
        guard let calories else { return false }
        return reliableCalories.contains(calories)
    }

    /// The burn calculator owns one estimate per diary day. Older data or a
    /// restore race can still contain duplicates, so choose the newest/highest
    /// sync-version record instead of summing and overstating the workout.
    static func daily(
        sessions: [StrengthWorkoutSession],
        in range: ClosedRange<Date>,
        calendar: Calendar = .current
    ) -> [WorkoutBurnDay] {
        var preferredByDay: [String: StrengthWorkoutSession] = [:]
        for session in sessions {
            guard isReliable(session.caloriesBurned) else { continue }
            let day = calendar.startOfDay(for: session.calendarDiaryDate)
            guard range.contains(day) else { continue }
            let key = session.stableDiaryDateKey
            if let current = preferredByDay[key], !shouldPrefer(session, over: current) { continue }
            preferredByDay[key] = session
        }
        return preferredByDay.values.compactMap { session in
            guard let calories = session.caloriesBurned else { return nil }
            return WorkoutBurnDay(
                date: calendar.startOfDay(for: session.calendarDiaryDate),
                calories: calories
            )
        }
        .sorted { $0.date < $1.date }
    }

    private static func shouldPrefer(
        _ candidate: StrengthWorkoutSession,
        over current: StrengthWorkoutSession
    ) -> Bool {
        let candidateVersion = candidate.healthSyncVersion ?? 0
        let currentVersion = current.healthSyncVersion ?? 0
        if candidateVersion != currentVersion { return candidateVersion > currentVersion }
        return candidate.completedAt > current.completedAt
    }
}

struct ImportedHealthWorkoutDay: Identifiable, Equatable {
    let date: Date
    let sessionCount: Int
    let totalCalories: Int
    var id: Date { date }
}

enum ImportedHealthWorkoutAggregation {
    static func daily(
        workouts: [ImportedHealthWorkout],
        in range: ClosedRange<Date>,
        calendar: Calendar = .current
    ) -> [ImportedHealthWorkoutDay] {
        var grouped: [String: (date: Date, count: Int, calories: Int)] = [:]
        for workout in workouts {
            let day = calendar.startOfDay(for: workout.calendarDiaryDate)
            guard range.contains(day) else { continue }
            var bucket = grouped[workout.diaryDateKey] ?? (day, 0, 0)
            bucket.count += 1
            bucket.calories += workout.totalEnergyBurned ?? 0
            grouped[workout.diaryDateKey] = bucket
        }
        return grouped.values.map {
            ImportedHealthWorkoutDay(date: $0.date, sessionCount: $0.count, totalCalories: $0.calories)
        }
        .sorted { $0.date < $1.date }
    }
}
