import Foundation
import HealthKit

/// Insights preferences. Only switches and times live here, never a score or a health value.
enum InsightsSettings {
    static let enabledKey = "insightsEnabled"
    /// Opt-in "Your recovery is ready" morning notification (default off).
    static let morningRecoveryKey = "insightsMorningRecoveryNotification"
    /// Wake day the morning notification last went out for (a date, not a value).
    static let lastRecoveryNotifiedDayKey = "insightsRecoveryNotifiedDay"

    static func isEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: enabledKey) as? Bool ?? true
    }

    static func morningRecoveryEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: morningRecoveryKey)
    }
}

/// Adapter from the existing stores to the engines' plain inputs (docs/insights.md §3.1). It builds day-keyed
/// series from the Health mirror and the diary stores on demand; nothing is written anywhere.
@MainActor
struct InsightsDataSource {
    /// Days of history read: 120 pattern days plus a 60-day baseline for each, and 12 weeks plus 90 days of
    /// Health Age, with a little slack.
    static let historyDays = 200
    /// Overnight readings come from samples inside last night's sleep window (fallback: the daily rollup).
    static let overnightTypes: [(metric: String, typeID: String)] = [
        ("hrv", "hrv_sdnn"), ("resting_heart_rate", "resting_heart_rate"),
        ("respiratory_rate", "respiratory_rate"), ("blood_oxygen", "blood_oxygen"),
    ]
    /// Plain daily rollups.
    static let rollupTypes: [(metric: String, typeID: String)] = [("vo2_max", "vo2_max"), ("bmi", "bmi")]

    var food: FoodStore
    var water: WaterStore
    var fasting: FastingStore
    var weight: WeightStore
    var bodyFat: BodyFatStore
    var workouts: StrengthWorkoutStore
    var importedWorkouts: ImportedHealthWorkoutStore
    var profile: () -> UserProfile
    var defaults: UserDefaults
    var calendar: Calendar
    /// The Health mirror reader, or nil (Health sync off, database unavailable).
    var healthDatabase: () async -> HealthDatabase?
    /// De-duplicated daily totals for `steps` / `active_energy` (the HealthKit statistics path the Summary Move
    /// ring uses). The mirror sums every source's rows, so it would count a phone and a watch twice.
    var dailyTotals: (_ typeID: String, _ from: Date, _ to: Date) async -> [String: Double]?

    // MARK: Live

    static func live(
        food: FoodStore, water: WaterStore, fasting: FastingStore, weight: WeightStore, bodyFat: BodyFatStore,
        workouts: StrengthWorkoutStore, importedWorkouts: ImportedHealthWorkoutStore, profile: @escaping () -> UserProfile,
        defaults: UserDefaults = .standard, calendar: Calendar = .current
    ) -> InsightsDataSource {
        InsightsDataSource(
            food: food, water: water, fasting: fasting, weight: weight, bodyFat: bodyFat, workouts: workouts,
            importedWorkouts: importedWorkouts, profile: profile, defaults: defaults, calendar: calendar,
            healthDatabase: {
                let runtime = HealthDataRuntime.shared
                guard defaults.bool(forKey: "healthKitEnabled"), await runtime.openIfNeeded() else { return nil }
                return runtime.reader ?? runtime.writer
            },
            dailyTotals: { typeID, from, to in
                await InsightsHealthKitTotals.daily(typeID: typeID, from: from, to: to, calendar: calendar, defaults: defaults)
            }
        )
    }

    /// Fresh, non-observing stores (background refresh, actions): the `ActionEnvironment` pattern.
    static func standalone(defaults: UserDefaults = .standard, calendar: Calendar = .current) -> InsightsDataSource {
        live(
            food: FoodStore(observesExternalChanges: false, defaults: defaults),
            water: WaterStore(defaults: defaults, observesExternalChanges: false),
            fasting: FastingStore(defaults: defaults, observesExternalChanges: false),
            weight: WeightStore(observesExternalChanges: false, defaults: defaults),
            bodyFat: BodyFatStore(defaults: defaults, observesExternalChanges: false),
            workouts: StrengthWorkoutStore(defaults: defaults, observesExternalChanges: false),
            importedWorkouts: ImportedHealthWorkoutStore(defaults: defaults),
            profile: { UserProfile.load() ?? .default },
            defaults: defaults, calendar: calendar
        )
    }

    // MARK: State

    var healthSyncEnabled: Bool { defaults.bool(forKey: "healthKitEnabled") }
    var insightsEnabled: Bool { InsightsSettings.isEnabled(defaults) }

    func today(_ now: Date = Date()) -> String { InsightsDay.key(for: now, calendar: calendar) }

    /// Changes whenever any input could have changed (the `MetricSeriesCache` pattern).
    func revisionKey(healthRevision: Int, now: Date = Date()) -> String {
        let p = profile()
        let settings = [
            defaults.bool(forKey: WaterSettings.enabledKey), defaults.bool(forKey: FastingSettings.enabledKey),
        ].map { $0 ? "1" : "0" }.joined()
        return [
            today(now), "\(food.revision)", "\(water.revision)", "\(fasting.revision)", "\(weight.revision)",
            "\(bodyFat.revision)", "\(workouts.revision)", "\(importedWorkouts.revision)", "\(healthRevision)",
            p.goalInputSignature, "\(p.effectiveCalories)", p.gender.rawValue, "\(p.birthday.timeIntervalSince1970)", settings,
            "\(defaults.integer(forKey: WaterSettings.dailyGoalKey))", "\(ActivitySettings.dailyStepGoal(defaults: defaults))",
        ].joined(separator: "|")
    }

    func profileFacts() -> InsightsProfile {
        let p = profile()
        let sex: String? = switch p.gender {
        case .male: "male"
        case .female: "female"
        case .other: nil
        }
        return InsightsProfile(birthday: InsightsDay.key(for: p.birthday, calendar: calendar), sex: sex,
                               heightCm: p.heightCm > 0 ? p.heightCm : nil)
    }

    // MARK: Inputs

    /// Everything the engines need for `today` and the history behind it.
    func inputs(today: String) async -> InsightsInputs {
        var inputs = InsightsInputs()
        inputs.timeZone = calendar.timeZone.identifier
        inputs.hrvKind = "sdnn"
        let from = InsightsDay.add(today, -Self.historyDays)
        appInputs(into: &inputs, from: from, through: today)
        if healthSyncEnabled, let database = await healthDatabase() {
            await Self.healthInputs(into: &inputs, database: database, from: from, through: today, calendar: calendar)
        }
        if healthSyncEnabled, let start = InsightsDay.date(from, calendar: calendar), let end = InsightsDay.date(today, calendar: calendar) {
            for id in ["steps", "active_energy"] {
                if let totals = await dailyTotals(id, calendar.startOfDay(for: start), end), !totals.isEmpty {
                    inputs.series[id] = totals
                }
            }
        }
        return inputs
    }

    /// Diary stores → nutrition, water, fasting, weight, body fat, strength volume and workouts.
    func appInputs(into inputs: inout InsightsInputs, from: String, through today: String) {
        func key(_ date: Date) -> String { InsightsDay.key(for: date, calendar: calendar) }
        func inWindow(_ day: String) -> Bool { day >= from && day <= today }

        // Nutrition: one total per logged day. A nutrient no entry recorded stays absent.
        var nutrition: [String: InsightsNutritionDay] = [:]
        for entry in food.entries {
            let day = key(entry.timestamp)
            guard inWindow(day) else { continue }
            var totals = nutrition[day] ?? InsightsNutritionDay()
            func add(_ value: Double?, _ path: WritableKeyPath<InsightsNutritionDay, Double?>) {
                guard let value else { return }
                totals[keyPath: path] = (totals[keyPath: path] ?? 0) + value
            }
            add(Double(entry.calories), \.calories)
            add(entry.protein, \.proteinG)
            add(entry.carbs, \.carbsG)
            add(entry.fat, \.fatG)
            add(entry.fiber, \.fiberG)
            add(entry.sugar, \.sugarG)
            add(entry.addedSugar, \.addedSugarG)
            add(entry.sodium, \.sodiumMg)
            add(entry.saturatedFat, \.saturatedFatG)
            add(entry.caffeine, \.caffeineMg)
            nutrition[day] = totals
        }
        inputs.nutrition = nutrition

        var waterByDay: [String: Double] = [:]
        for entry in water.entries where inWindow(key(entry.date)) {
            waterByDay[key(entry.date), default: 0] += Double(entry.milliliters)
        }
        inputs.waterMl = waterByDay

        // Fasting: the longest completed fast that ended on each day, in hours.
        var fastByDay: [String: Double] = [:]
        for session in fasting.sessions {
            guard let end = session.endedAt else { continue }
            let day = key(end)
            guard inWindow(day) else { continue }
            fastByDay[day] = max(fastByDay[day] ?? 0, session.duration(at: end) / 3600)
        }
        inputs.fastingHours = fastByDay

        var weightByDay: [String: Double] = [:]
        for entry in weight.entries.sorted(by: { $0.date < $1.date }) where inWindow(key(entry.date)) {
            weightByDay[key(entry.date)] = entry.weightKg
        }
        if !weightByDay.isEmpty { inputs.series["weight"] = weightByDay }
        var fatByDay: [String: Double] = [:]
        for entry in bodyFat.entries.sorted(by: { $0.date < $1.date }) where inWindow(key(entry.date)) {
            fatByDay[key(entry.date)] = entry.bodyFatPercent
        }
        if !fatByDay.isEmpty { inputs.series["body_fat"] = fatByDay }

        // Strength volume (kg lifted, `ActionMath.setVolume`) per diary day, and every session as a workout.
        let fallbackUnit: WeightUnit = (WeightUnit(rawValue: defaults.string(forKey: WeightUnit.storageKey) ?? "") ?? .lbs)
        var volume: [String: Double] = [:]
        var sessions: [InsightsWorkout] = []
        for session in workouts.completedSessions {
            let day = session.stableDiaryDateKey
            guard inWindow(day) else { continue }
            let sets = session.exercises.flatMap(\.sets).compactMap { set -> (weightKg: Double, reps: Int)? in
                guard set.isPerformed, let reps = Int(set.reps), reps > 0 else { return nil }
                return (ActionExecutor.kilograms(set.weight, unit: set.weightUnit, fallback: fallbackUnit), reps)
            }
            let kg = ActionMath.setVolume(sets).volumeKg
            if kg > 0 { volume[day, default: 0] += kg }
            let start = Self.ms(session.startedAt), end = Self.ms(session.completedAt)
            if end > start { sessions.append(InsightsWorkout(startMs: start, endMs: end, effort: nil)) }
        }
        inputs.strengthVolume = volume
        for workout in importedWorkouts.workouts where inWindow(key(workout.startedAt)) {
            let start = Self.ms(workout.startedAt), end = Self.ms(workout.endedAt)
            if end > start { sessions.append(InsightsWorkout(startMs: start, endMs: end, effort: nil)) }
        }
        inputs.workouts = sessions

        inputs.tracking = InsightsTracking(
            nutrition: true,
            water: defaults.bool(forKey: WaterSettings.enabledKey),
            workouts: !sessions.isEmpty,
            fasting: defaults.bool(forKey: FastingSettings.enabledKey)
        )
        inputs.targets = targets()
    }

    func targets() -> InsightsTargets {
        let p = profile()
        let goals = defaults.data(forKey: OptionalNutrientGoals.storageKey).map(OptionalNutrientGoals.decoded(from:)) ?? .defaults
        func positive(_ value: Int) -> Double? { value > 0 ? Double(value) : nil }
        var t = InsightsTargets()
        t.calories = positive(p.effectiveCalories)
        t.proteinG = positive(p.effectiveProtein)
        t.carbsG = positive(p.effectiveCarbs)
        t.fatG = positive(p.effectiveFat)
        t.fiberG = positive(goals.goal(for: .fiber))
        let waterGoal = defaults.integer(forKey: WaterSettings.dailyGoalKey)
        t.waterMl = Double(waterGoal > 0 ? waterGoal : WaterSettings.defaultDailyGoalMl)
        t.steps = Double(ActivitySettings.dailyStepGoal(defaults: defaults))
        let fastGoal = defaults.integer(forKey: FastingSettings.defaultGoalMinutesKey)
        t.fastingHours = Double(fastGoal > 0 ? fastGoal : FastingSettings.defaultGoalMinutes) / 60
        t.sugarMaxG = positive(goals.goal(for: .sugar))
        t.addedSugarMaxG = positive(goals.goal(for: .addedSugar))
        t.sodiumMaxMg = positive(goals.goal(for: .sodium))
        t.saturatedFatMaxG = positive(goals.goal(for: .saturatedFat))
        t.caffeineMaxMg = positive(goals.goal(for: .caffeine))
        return t
    }

    /// Health mirror → sleep nights, overnight HRV / resting heart rate / breathing rate / blood oxygen, VO2 max
    /// and BMI. Static so tests can run it against an in-memory database.
    static func healthInputs(into inputs: inout InsightsInputs, database: HealthDatabase, from: String, through today: String,
                             calendar: Calendar) async {
        // One day earlier: stages that end before midnight carry the previous local_day.
        let sleepRows = (try? await database.rowsForDays(type: "sleep", fromDay: InsightsDay.add(from, -1), toDay: today)) ?? []
        var nights: [String: InsightsNight] = [:]
        for night in HealthSleepAnalysis.nights(rows: sleepRows, calendar: calendar) where night.nightOf >= from && night.nightOf <= today {
            nights[night.nightOf] = InsightsNight(asleepMin: night.asleepS / 60, startMs: night.startMs, endMs: night.endMs)
        }
        inputs.sleep = nights

        var fallbackToday: [String] = []
        for (metric, typeID) in overnightTypes {
            guard let type = HealthMetricRegistry.type(id: typeID) else { continue }
            let rollups = (try? await database.dailyRollups(type: typeID, fromDay: from, toDay: today)) ?? []
            var daily: [String: Double] = [:]
            for rollup in rollups {
                if let value = HealthChartSeriesBuilder.primaryValue(rollup, type: type) { daily[rollup.day] = value }
            }
            var samples: [InsightsSample] = []
            if let first = nights.values.map(\.startMs).min(), let last = nights.values.map(\.endMs).max() {
                samples = ((try? await database.rows(type: typeID, startMs: first, endMs: last + 1)) ?? [])
                    .compactMap { row in row.value.map { InsightsSample(tMs: row.startMs, value: $0) } }
            }
            var series: [String: Double] = [:]
            var day = from
            while day <= today {
                let night = nights[day]
                let inside = night.map { n in samples.filter { $0.tMs >= n.startMs && $0.tMs <= n.endMs } } ?? []
                let resolved = BaselineEngine.overnightValue(samples: inside, night: night, fallback: daily[day])
                if let value = resolved.value {
                    series[day] = value
                    if day == today, resolved.fallback { fallbackToday.append(metric) }
                }
                day = InsightsDay.add(day, 1)
            }
            if !series.isEmpty { inputs.series[metric] = series }
        }
        inputs.overnightFallback = fallbackToday

        for (metric, typeID) in rollupTypes {
            guard let type = HealthMetricRegistry.type(id: typeID) else { continue }
            let rollups = (try? await database.dailyRollups(type: typeID, fromDay: from, toDay: today)) ?? []
            var series: [String: Double] = [:]
            for rollup in rollups {
                if let value = HealthChartSeriesBuilder.primaryValue(rollup, type: type) { series[rollup.day] = value }
            }
            if !series.isEmpty { inputs.series[metric] = series }
        }
    }

    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}

/// Daily steps / active energy through HealthKit statistics: the same de-duplicating `cumulativeSum` the Move
/// ring's `fetchStepsForDay` uses, one bucket per local day. Ayuvo's own tagged workout-burn estimates are
/// excluded from active energy, as in the measured-energy queries.
enum InsightsHealthKitTotals {
    static let workoutBurnSessionIDKey = "ayuvo_workout_session_id"

    static func daily(typeID: String, from: Date, to: Date, calendar: Calendar, defaults: UserDefaults) async -> [String: Double]? {
        guard defaults.bool(forKey: "healthKitEnabled"), HKHealthStore.isHealthDataAvailable() else { return nil }
        let identifier: HKQuantityTypeIdentifier
        let unit: HKUnit
        switch typeID {
        case "steps": (identifier, unit) = (.stepCount, .count())
        case "active_energy": (identifier, unit) = (.activeEnergyBurned, .kilocalorie())
        default: return nil
        }
        let start = calendar.startOfDay(for: from)
        guard let dayAfter = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: to)) else { return nil }
        let end = min(dayAfter, Date())
        guard end > start else { return nil }
        var predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        if identifier == .activeEnergyBurned {
            let tagged = HKQuery.predicateForObjects(withMetadataKey: workoutBurnSessionIDKey)
            predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [predicate, NSCompoundPredicate(notPredicateWithSubpredicate: tagged)])
        }
        let type = HKQuantityType(identifier)
        return await withCheckedContinuation { continuation in
            let query = HKStatisticsCollectionQuery(
                quantityType: type, quantitySamplePredicate: predicate, options: .cumulativeSum,
                anchorDate: start, intervalComponents: DateComponents(day: 1)
            )
            query.initialResultsHandler = { _, collection, error in
                guard error == nil, let collection else {
                    continuation.resume(returning: nil)
                    return
                }
                var totals: [String: Double] = [:]
                collection.enumerateStatistics(from: start, to: end) { statistics, _ in
                    // No samples that day → absent, never 0.
                    if let sum = statistics.sumQuantity()?.doubleValue(for: unit) {
                        totals[InsightsDay.key(for: statistics.startDate, calendar: calendar)] = sum
                    }
                }
                continuation.resume(returning: totals)
            }
            HealthKitManager.sharedHealthStore.execute(query)
        }
    }
}
