import Foundation

/// Builds the `ayuvo-portable-data` file (docs/portable-data.md) from what this iPhone has
/// persisted. Reads `UserDefaults` directly (the stores keep nothing else) so it needs no store
/// instances and can run against an isolated suite in tests.
enum PortableDataExport {
    struct Output: Equatable {
        var data: Data
        /// Manifest counts; a count of 0 is left out.
        var counts: [String: Int]
    }

    /// nil when there is nothing to carry (no profile, no logs, no saved settings).
    static func build(
        defaults: UserDefaults = .standard,
        now: Date = Date(),
        appVersion: String,
        calendar: Calendar = .current
    ) -> Output? {
        var root: PortableData.JSON = [
            "app": "Ayuvo",
            "format": PortableData.format,
            "format_version": PortableData.formatVersion,
            "created_at": PortableData.timestamp(now),
            "platform": PortableData.platform,
            "app_version": appVersion,
        ]
        var counts: [String: Int] = [:]
        var hasContent = false

        if let data = defaults.data(forKey: UserProfile.storageKey),
           let profile = try? JSONDecoder().decode(UserProfile.self, from: data) {
            root["profile"] = PortableData.profileJSON(profile, calendar: calendar)
            hasContent = true
        }

        // Settings
        var groups: [PortableData.Setting.Group: PortableData.JSON] = [:]
        var settingCount = 0
        for setting in PortableData.settings {
            guard let value = setting.validated(defaults.object(forKey: setting.native)) else { continue }
            groups[setting.group, default: [:]][setting.json] = value.jsonObject
            settingCount += 1
        }
        var preferences = groups[.preferences] ?? [:]
        if let meals = mealScheduleJSON(defaults) {
            preferences["meal_start_minutes"] = meals
            settingCount += 1
        }
        if defaults.object(forKey: MetricPins.key) != nil {
            let ids = (defaults.stringArray(forKey: MetricPins.key) ?? [])
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            preferences["summary_favourites"] = Array(ids.prefix(PortableData.maxSummaryFavourites))
            settingCount += 1
        }
        if let data = defaults.data(forKey: OptionalNutrientGoals.storageKey) {
            let goals = OptionalNutrientGoals.decoded(from: data)
            preferences["optional_nutrient_goals"] = Dictionary(
                uniqueKeysWithValues: OptionalNutrient.allCases.map { ($0.rawValue, goals.goal(for: $0)) }
            )
            settingCount += 1
        }
        if let units = groups[.units] { root["units"] = units }
        if !preferences.isEmpty { root["preferences"] = preferences }
        if let water = groups[.water] { root["water"] = ["settings": water] }
        if settingCount > 0 {
            counts["settings"] = settingCount
            hasContent = true
        }

        // Logs
        var fasting: PortableData.JSON = [:]
        if let settings = groups[.fasting] { fasting["settings"] = settings }
        let sessions = list(FastingSession.self, key: FastingSettings.sessionsKey, defaults: defaults).sorted { $0.startedAt < $1.startedAt }
        if !sessions.isEmpty { fasting["sessions"] = sessions.map(PortableData.fastingJSON) }
        if !fasting.isEmpty { root["fasting"] = fasting }
        counts["fasting_sessions"] = sessions.count

        let weights = list(WeightEntry.self, key: WeightStore.storageKey, defaults: defaults).sorted { $0.date < $1.date }
        if !weights.isEmpty { root["weights"] = weights.map(PortableData.weightJSON) }
        counts["weights"] = weights.count

        let bodyFat = list(BodyFatEntry.self, key: "bodyFatEntries", defaults: defaults).sorted { $0.date < $1.date }
        if !bodyFat.isEmpty { root["body_fat"] = bodyFat.map(PortableData.bodyFatJSON) }
        counts["body_fat"] = bodyFat.count

        let measurements = list(BodyMeasurement.self, key: "bodyMeasurementEntries", defaults: defaults).sorted { $0.date < $1.date }
        if !measurements.isEmpty { root["body_measurements"] = measurements.map(PortableData.measurementJSON) }
        counts["body_measurements"] = measurements.count

        if let workouts = workoutsJSON(defaults, counts: &counts) {
            root["workouts"] = workouts
        }

        counts = counts.filter { $0.value > 0 }
        hasContent = hasContent || !counts.isEmpty
        guard hasContent, JSONSerialization.isValidJSONObject(root),
              var data = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        else { return nil }
        data.append(0x0A)
        return Output(data: data, counts: counts)
    }

    // MARK: - Pieces

    /// Rows that fail to decode are skipped (same leniency as the stores).
    private static func list<Element: Decodable>(_ type: Element.Type, key: String, defaults: UserDefaults) -> [Element] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return PersistedBlobGuard.decodeListLeniently(type, from: data)?.items ?? []
    }

    private static func mealScheduleJSON(_ defaults: UserDefaults) -> PortableData.JSON? {
        guard PortableData.mealKeys.contains(where: { defaults.object(forKey: $0) != nil }) else { return nil }
        let fallback = MealSchedule.defaults
        let fallbacks = [fallback.breakfastStartMinutes, fallback.lunchStartMinutes, fallback.dinnerStartMinutes, fallback.snackStartMinutes]
        let minutes = zip(PortableData.mealKeys, fallbacks).map { key, value in
            defaults.object(forKey: key) != nil ? defaults.integer(forKey: key) : value
        }
        let schedule = MealSchedule(
            breakfastStartMinutes: minutes[0], lunchStartMinutes: minutes[1],
            dinnerStartMinutes: minutes[2], snackStartMinutes: minutes[3]
        )
        guard schedule.isValid else { return nil }
        return Dictionary(uniqueKeysWithValues: zip(PortableData.mealJSONKeys, minutes).map { ($0, $1) })
    }

    /// nil when the diary is untouched. A state written by another schema version is not read.
    private static func workoutsJSON(_ defaults: UserDefaults, counts: inout [String: Int]) -> PortableData.JSON? {
        guard let data = defaults.data(forKey: StrengthWorkoutStore.defaultStorageKey),
              let state = try? JSONDecoder().decode(StrengthWorkoutStore.PersistedState.self, from: data),
              state.version == StrengthWorkoutStore.currentStateVersion
        else { return nil }
        let userExercises = state.userExercises ?? []
        let customActivities = state.customActivities ?? []
        let plans = state.dayPlans.filter { !$0.value.exercises.isEmpty }
        let isUntouched = plans.isEmpty && state.completedSessions.isEmpty && state.savedExerciseIDs.isEmpty
            && userExercises.isEmpty && customActivities.isEmpty && state.preferences == StrengthWorkoutPreferences()
        guard !isUntouched else { return nil }

        counts["workout_sessions"] = state.completedSessions.count
        counts["user_exercises"] = userExercises.count
        var json: PortableData.JSON = ["preferences": PortableData.workoutPreferencesJSON(state.preferences)]
        json["saved_exercise_ids"] = state.savedExerciseIDs.sorted()
        json["user_exercises"] = userExercises.map(PortableData.exerciseJSON)
        json["custom_activities"] = customActivities.map(PortableData.exerciseJSON)
        json["day_plans"] = Dictionary(uniqueKeysWithValues: plans.map { ($0.key, $0.value.exercises.map(PortableData.exerciseJSON)) })
        json["sessions"] = state.completedSessions
            .sorted { ($0.startedAt, $0.id.uuidString) < ($1.startedAt, $1.id.uuidString) }
            .map(PortableData.sessionJSON)
        return json
    }
}
