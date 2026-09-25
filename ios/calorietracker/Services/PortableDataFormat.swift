import Foundation

/// The cross-platform "portable data" part of Export All Data (docs/portable-data.md): one JSON
/// file both platforms write and read. This file holds what export and import share: the ids and
/// the mapping between the persisted iPhone models and the file's normalized JSON. Lenient on read
/// (a bad value is dropped on its own), strict on write.
enum PortableData {
    static let sectionID = "portable_data"
    static let format = "ayuvo-portable-data"
    static let formatVersion = 1
    static let entryName = "portable-data/ayuvo-portable-data.json"
    static let platform = "ios"

    typealias JSON = [String: Any]

    // MARK: - Dates

    private static let fractionalFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        return formatter
    }()

    private static let plainFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        return formatter
    }()

    /// `2026-09-25T10:30:00.000Z`.
    static func timestamp(_ date: Date) -> String {
        fractionalFormatter.string(from: date)
    }

    /// Accepts milliseconds or none, `Z` or an offset.
    static func date(fromTimestamp text: String?) -> Date? {
        guard let text else { return nil }
        return fractionalFormatter.date(from: text) ?? plainFormatter.date(from: text)
    }

    /// The local calendar day as `yyyy-MM-dd`.
    static func dayString(_ date: Date, calendar: Calendar) -> String {
        StrengthWorkoutDate.key(for: date, calendar: calendar)
    }

    /// Start of the given `yyyy-MM-dd` day in `calendar`'s zone; nil for anything else (or a day that doesn't exist).
    static func day(fromString text: String?, calendar: Calendar) -> Date? {
        guard let text else { return nil }
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              let date = calendar.date(from: DateComponents(year: year, month: month, day: day))
        else { return nil }
        let check = calendar.dateComponents([.year, .month, .day], from: date)
        guard check.year == year, check.month == month, check.day == day else { return nil }
        return date
    }

    static func uuid(_ text: String?) -> UUID? {
        text.flatMap { UUID(uuidString: $0) }
    }

    static func id(_ uuid: UUID) -> String { uuid.uuidString.lowercased() }

    // MARK: - Simple settings (units, shared preferences, water and fasting settings)

    enum SettingValue: Equatable {
        case bool(Bool)
        case int(Int)
        case string(String)

        var jsonObject: Any {
            switch self {
            case .bool(let value): value
            case .int(let value): value
            case .string(let value): value
            }
        }

        func write(to defaults: UserDefaults, key: String) {
            switch self {
            case .bool(let value): defaults.set(value, forKey: key)
            case .int(let value): defaults.set(value, forKey: key)
            case .string(let value): defaults.set(value, forKey: key)
            }
        }
    }

    struct Setting {
        enum Group { case units, preferences, water, fasting }
        enum Kind {
            case bool
            case int(ClosedRange<Int>)
            case choice([String])
        }

        let group: Group
        /// Key inside the group's JSON object.
        let json: String
        /// The `UserDefaults` key (the same name both platforms use where a counterpart exists).
        let native: String
        let kind: Kind

        /// The value if it is well-formed and in range, nil otherwise (nothing is coerced).
        func validated(_ raw: Any?) -> SettingValue? {
            switch kind {
            case .bool:
                guard let value = PortableData.bool(raw) else { return nil }
                return .bool(value)
            case .int(let range):
                guard let value = PortableData.int(raw), range.contains(value) else { return nil }
                return .int(value)
            case .choice(let allowed):
                guard let value = raw as? String, allowed.contains(value) else { return nil }
                return .string(value)
            }
        }
    }

    static let settings: [Setting] = [
        Setting(group: .units, json: "height_unit", native: HeightUnit.storageKey, kind: .choice(["cm", "ftin"])),
        Setting(group: .units, json: "weight_unit", native: WeightUnit.storageKey, kind: .choice(["kg", "lbs"])),
        Setting(group: .units, json: "water_unit", native: WaterSettings.unitKey, kind: .choice(WaterUnit.allCases.map(\.rawValue))),
        Setting(group: .units, json: "glucose_unit", native: HealthGlucoseUnit.storageKey, kind: .choice(HealthGlucoseUnit.allCases.map(\.rawValue))),
        Setting(group: .preferences, json: "week_starts_on_monday", native: ActivitySettings.weekStartsOnMondayKey, kind: .bool),
        Setting(group: .preferences, json: "daily_step_goal", native: ActivitySettings.dailyStepGoalKey, kind: .int(ActivitySettings.stepGoalRange)),
        Setting(group: .preferences, json: "appearance_mode", native: "appearanceMode", kind: .choice(["system", "light", "dark"])),
        Setting(group: .preferences, json: "app_theme_color", native: AppThemeColor.storageKey, kind: .choice(AppThemeColor.allCases.map(\.rawValue))),
        Setting(group: .preferences, json: "adaptive_goals_enabled", native: AdaptiveGoalSettings.enabledKey, kind: .bool),
        Setting(group: .preferences, json: "food_measurement_prefer_grams", native: FoodMeasurementSettings.preferGramsByDefaultKey, kind: .bool),
        Setting(group: .water, json: "tracking_enabled", native: WaterSettings.enabledKey, kind: .bool),
        Setting(group: .water, json: "daily_goal_ml", native: WaterSettings.dailyGoalKey, kind: .int(100...20_000)),
        Setting(group: .fasting, json: "tracking_enabled", native: FastingSettings.enabledKey, kind: .bool),
        Setting(group: .fasting, json: "default_goal_minutes", native: FastingSettings.defaultGoalMinutesKey, kind: .int(FastingSettings.minimumGoalMinutes...FastingSettings.maximumGoalMinutes)),
        Setting(group: .fasting, json: "goal_notification_enabled", native: FastingSettings.notificationEnabledKey, kind: .bool),
    ]

    static let maxSummaryFavourites = 12
    static let mealKeys = [
        MealScheduleSettings.breakfastStartKey, MealScheduleSettings.lunchStartKey,
        MealScheduleSettings.dinnerStartKey, MealScheduleSettings.snackStartKey,
    ]
    static let mealJSONKeys = ["breakfast", "lunch", "dinner", "snack"]

    // MARK: - Lenient JSON readers

    static func bool(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    /// A whole JSON number (`5` or `5.0`); never a boolean or a fraction.
    static func int(_ raw: Any?) -> Int? {
        guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let value = number.doubleValue
        guard value.isFinite, value == value.rounded(), abs(value) < 1e15 else { return nil }
        return Int(value)
    }

    static func double(_ raw: Any?) -> Double? {
        guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let value = number.doubleValue
        return value.isFinite ? value : nil
    }

    /// Weights, reps and RPE stay strings in the file; a bare number is accepted and written back as text.
    static func text(_ raw: Any?) -> String? {
        if let string = raw as? String { return string }
        guard let value = double(raw) else { return nil }
        return value == value.rounded() && abs(value) < 1e9 ? String(Int(value)) : "\(value)"
    }

    static func strings(_ raw: Any?) -> [String] {
        (raw as? [Any])?.compactMap { $0 as? String } ?? []
    }

    static func url(_ raw: Any?) -> URL? {
        guard let text = raw as? String, let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased()) else { return nil }
        return url
    }

    // MARK: - Profile

    static func profileJSON(_ profile: UserProfile, calendar: Calendar) -> JSON {
        var json: JSON = [
            "gender": profile.gender.rawValue,
            "birthday": dayString(profile.birthday, calendar: calendar),
            "height_cm": profile.heightCm,
            "weight_kg": profile.weightKg,
            "activity_level": profile.activityLevel.rawValue,
            "goal": profile.goal.rawValue,
            "allergens": profile.configuredAllergenSensitivities,
            "calories_locked": profile.isCaloriesLocked,
            "locked_macros": AutoBalanceMacro.allCases.filter { profile.isMacroLocked($0) }.map(\.rawValue),
        ]
        json["name"] = profile.name
        json["body_fat_fraction"] = profile.bodyFatPercentage
        json["goal_body_fat_fraction"] = profile.goalBodyFatPercentage
        json["weekly_change_kg"] = profile.weeklyChangeKg
        json["goal_weight_kg"] = profile.goalWeightKg
        json["custom_calories"] = profile.customCalories
        json["custom_protein_g"] = profile.customProtein
        json["custom_fat_g"] = profile.customFat
        json["custom_carbs_g"] = profile.customCarbs
        json["auto_balance_macro"] = profile.autoBalanceMacro?.rawValue
        return json
    }

    /// nil when a required field (gender, birthday, height, weight, activity level, goal) is missing
    /// or invalid: a half-read profile must not replace a good one.
    static func profile(from json: JSON, calendar: Calendar) -> UserProfile? {
        guard let gender = (json["gender"] as? String).flatMap(Gender.init(rawValue:)),
              let birthday = day(fromString: json["birthday"] as? String, calendar: calendar),
              let height = double(json["height_cm"]), height > 0,
              let weight = double(json["weight_kg"]), weight > 0,
              let activity = (json["activity_level"] as? String).flatMap(ActivityLevel.init(rawValue:)),
              let goal = (json["goal"] as? String).flatMap(WeightGoal.init(rawValue:))
        else { return nil }

        func fraction(_ key: String) -> Double? {
            double(json[key]).flatMap { (0...1).contains($0) ? $0 : nil }
        }
        func grams(_ key: String) -> Int? {
            int(json[key]).flatMap { $0 >= 0 ? $0 : nil }
        }
        func positive(_ key: String) -> Double? {
            double(json[key]).flatMap { $0 > 0 ? $0 : nil }
        }

        var profile = UserProfile(
            name: (json["name"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            gender: gender,
            birthday: birthday,
            heightCm: height,
            weightKg: weight,
            activityLevel: activity,
            goal: goal,
            bodyFatPercentage: fraction("body_fat_fraction"),
            goalBodyFatPercentage: fraction("goal_body_fat_fraction"),
            useBodyFatInBMR: nil,
            weeklyChangeKg: double(json["weekly_change_kg"]),
            goalWeightKg: positive("goal_weight_kg"),
            customCalories: grams("custom_calories"),
            customProtein: grams("custom_protein_g"),
            customFat: grams("custom_fat_g"),
            customCarbs: grams("custom_carbs_g"),
            autoBalanceMacro: (json["auto_balance_macro"] as? String).flatMap(AutoBalanceMacro.init(rawValue:)),
            allergenSensitivities: strings(json["allergens"])
        )
        // iPhone stores "not locked" as nil.
        profile.caloriesLocked = bool(json["calories_locked"]) == true ? true : nil
        let locked = strings(json["locked_macros"]).compactMap(AutoBalanceMacro.init(rawValue:))
        let limited = Set(AutoBalanceMacro.allCases.filter { locked.contains($0) }.prefix(2))
        profile.lockedMacros = limited.isEmpty ? nil : limited
        return profile
    }

    // MARK: - Logs

    static func weightJSON(_ entry: WeightEntry) -> JSON {
        ["id": id(entry.id), "date": timestamp(entry.date), "weight_kg": entry.weightKg]
    }

    static func weight(from json: JSON) -> WeightEntry? {
        guard let id = uuid(json["id"] as? String), let date = date(fromTimestamp: json["date"] as? String),
              let kg = double(json["weight_kg"]), kg > 0, kg < 1_000
        else { return nil }
        return WeightEntry(id: id, date: date, weightKg: kg)
    }

    static func bodyFatJSON(_ entry: BodyFatEntry) -> JSON {
        ["id": id(entry.id), "date": timestamp(entry.date), "fraction": entry.bodyFatFraction]
    }

    static func bodyFat(from json: JSON) -> BodyFatEntry? {
        guard let id = uuid(json["id"] as? String), let date = date(fromTimestamp: json["date"] as? String),
              let fraction = double(json["fraction"]), fraction > 0, fraction <= 1
        else { return nil }
        return BodyFatEntry(id: id, date: date, bodyFatFraction: fraction)
    }

    private static let measurementKeys: [(json: String, keyPath: WritableKeyPath<BodyMeasurement, Double?>)] = [
        ("neck_cm", \.neckCm), ("waist_cm", \.waistCm), ("hips_cm", \.hipsCm), ("chest_cm", \.chestCm),
        ("upper_arm_cm", \.upperArmCm), ("thigh_cm", \.thighCm), ("calf_cm", \.calfCm), ("wrist_cm", \.wristCm),
    ]

    static func measurementJSON(_ entry: BodyMeasurement) -> JSON {
        var json: JSON = ["id": id(entry.id), "date": timestamp(entry.date)]
        for (key, keyPath) in measurementKeys { json[key] = entry[keyPath: keyPath] }
        return json
    }

    static func measurement(from json: JSON) -> BodyMeasurement? {
        guard let id = uuid(json["id"] as? String), let date = date(fromTimestamp: json["date"] as? String) else { return nil }
        var entry = BodyMeasurement(id: id, date: date)
        for (key, keyPath) in measurementKeys {
            entry[keyPath: keyPath] = double(json[key]).flatMap { $0 > 0 ? $0 : nil }
        }
        return entry.hasAnyValue ? entry : nil
    }

    static func fastingJSON(_ session: FastingSession) -> JSON {
        var json: JSON = ["id": id(session.id), "started_at": timestamp(session.startedAt), "goal_minutes": session.goalMinutes]
        json["ended_at"] = session.endedAt.map(timestamp)
        return json
    }

    static func fasting(from json: JSON) -> FastingSession? {
        guard let id = uuid(json["id"] as? String), let start = date(fromTimestamp: json["started_at"] as? String),
              let goal = int(json["goal_minutes"])
        else { return nil }
        var end: Date?
        if let raw = json["ended_at"], !(raw is NSNull) {
            // A present but unreadable end must not turn a finished fast into an active one.
            guard let parsed = date(fromTimestamp: raw as? String) else { return nil }
            end = max(parsed, start)
        }
        return FastingSession(id: id, startedAt: start, endedAt: end, goalMinutes: goal)
    }

    // MARK: - Workouts

    private static let splitNames: [(StrengthWorkoutSplit, String)] = [
        (.pushPullLegs, "push_pull_legs"), (.upperLower, "upper_lower"), (.broSplit, "body_part"),
        (.arnold, "arnold"), (.pushPull, "push_pull"), (.antagonistSplit, "antagonist"),
        (.hybridSplit, "hybrid"), (.fullBody, "full_body"), (.custom, "custom"),
    ]

    private static let issueNames: [(StrengthWorkoutIssue, String)] = [
        (.shoulder, "shoulder"), (.elbow, "elbow"), (.wrist, "wrist"), (.lowerBack, "lower_back"),
        (.hip, "hip"), (.knee, "knee"), (.ankle, "ankle"), (.other, "other"),
    ]

    /// The catalogue's vocabulary is lower case in the file and title case on iPhone.
    static func portableLabel(_ value: String) -> String {
        value == "Unspecified" ? "" : value.lowercased()
    }

    static func iPhoneLabel(_ value: String?) -> String {
        ExerciseLibraryItem.metadataTitle(value)
    }

    private static func labels(_ raw: Any?) -> [String] {
        strings(raw).filter { !$0.isEmpty }.map { iPhoneLabel($0) }
    }

    static func workoutPreferencesJSON(_ preferences: StrengthWorkoutPreferences) -> JSON {
        var strength: JSON = [:]
        strength["bench_press_kg"] = preferences.strength.benchPressKg
        strength["squat_kg"] = preferences.strength.squatKg
        strength["deadlift_kg"] = preferences.strength.deadliftKg
        strength["overhead_press_kg"] = preferences.strength.overheadPressKg
        return [
            "target_muscles": preferences.targetMuscles.map(portableLabel).sorted(),
            "issues": issueNames.filter { preferences.issues.contains($0.0) }.map(\.1),
            "additional_issues": preferences.additionalIssues,
            "frequency_days": preferences.frequencyDays,
            "duration_minutes": preferences.duration.rawValue,
            "split": splitNames.first { $0.0 == preferences.split }?.1 ?? "full_body",
            "custom_split": preferences.customSplit,
            "equipment": preferences.equipment.map(portableLabel).sorted(),
            "rpe_scale": preferences.rpeScale.rawValue,
            "strength": strength,
        ]
    }

    static func workoutPreferences(from json: JSON) -> StrengthWorkoutPreferences {
        var preferences = StrengthWorkoutPreferences()
        preferences.targetMuscles = Set(labels(json["target_muscles"]))
        let issues = Set(strings(json["issues"]))
        preferences.issues = Set(issueNames.filter { issues.contains($0.1) }.map(\.0))
        preferences.additionalIssues = json["additional_issues"] as? String ?? ""
        preferences.frequencyDays = int(json["frequency_days"]) ?? preferences.frequencyDays
        if let minutes = int(json["duration_minutes"]), let duration = StrengthWorkoutDuration(rawValue: minutes) {
            preferences.duration = duration
        }
        if let name = json["split"] as? String, let split = splitNames.first(where: { $0.1 == name })?.0 {
            preferences.split = split
        }
        preferences.customSplit = json["custom_split"] as? String ?? ""
        preferences.equipment = Set(labels(json["equipment"]))
        if let name = json["rpe_scale"] as? String, let scale = StrengthWorkoutRPEScale(rawValue: name) {
            preferences.rpeScale = scale
        }
        if let strength = json["strength"] as? JSON {
            preferences.strength = StrengthWorkoutNumbers(
                benchPressKg: double(strength["bench_press_kg"]),
                squatKg: double(strength["squat_kg"]),
                deadliftKg: double(strength["deadlift_kg"]),
                overheadPressKg: double(strength["overhead_press_kg"])
            )
        }
        preferences.sanitize()
        return preferences
    }

    static func plannedSetJSON(_ set: StrengthPlannedSet) -> JSON {
        var json: JSON = ["id": id(set.id), "weight": set.weight, "reps": set.reps, "rpe": set.rpe]
        json["weight_unit"] = set.weightUnit
        json["rpe_scale"] = set.rpeScale?.rawValue
        return json
    }

    static func plannedSet(from json: JSON) -> StrengthPlannedSet {
        StrengthPlannedSet(
            id: uuid(json["id"] as? String) ?? UUID(),
            weight: text(json["weight"]) ?? "",
            weightUnit: (json["weight_unit"] as? String).flatMap { ["kg", "lbs"].contains($0) ? $0 : nil },
            reps: text(json["reps"]) ?? "",
            rpe: text(json["rpe"]) ?? "",
            rpeScale: (json["rpe_scale"] as? String).flatMap(StrengthWorkoutRPEScale.init(rawValue:))
        )
    }

    /// Timers and user-photo filenames stay on this phone.
    static func exerciseJSON(_ exercise: StrengthPlannedExercise) -> JSON {
        var json: JSON = [
            "id": id(exercise.id),
            "item_id": exercise.itemID,
            "name": exercise.name,
            "body_part": portableLabel(exercise.bodyPart),
            "equipment": portableLabel(exercise.rawEquipment),
            "primary_muscles": exercise.primaryMuscles.map(portableLabel),
            "secondary_muscles": exercise.secondaryMuscles.map(portableLabel),
            "instructions": exercise.instructions,
            "sets": exercise.sets.map(plannedSetJSON),
        ]
        json["image_url"] = exercise.imageURL?.absoluteString
        json["gif_url"] = exercise.gifURL?.absoluteString
        return json
    }

    static func exercise(from json: JSON) -> StrengthPlannedExercise? {
        guard let itemID = (json["item_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !itemID.isEmpty,
              let name = (json["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty
        else { return nil }
        let item = ExerciseLibraryItem(
            id: itemID,
            name: name,
            bodyPart: json["body_part"] as? String,
            rawEquipment: json["equipment"] as? String,
            primaryMuscles: strings(json["primary_muscles"]),
            secondaryMuscles: strings(json["secondary_muscles"]),
            instructions: strings(json["instructions"]),
            imageURL: url(json["image_url"]),
            gifURL: url(json["gif_url"])
        )
        var exercise = StrengthPlannedExercise(item: item)
        exercise.id = uuid(json["id"] as? String) ?? UUID()
        if let sets = json["sets"] as? [Any] {
            exercise.sets = sets.compactMap { ($0 as? JSON).map(plannedSet(from:)) }
        }
        return exercise
    }

    static func sessionJSON(_ session: StrengthWorkoutSession) -> JSON {
        var json: JSON = [
            "id": id(session.id),
            "diary_date_key": session.stableDiaryDateKey,
            "started_at": timestamp(session.startedAt),
            "completed_at": timestamp(session.completedAt),
            "duration_seconds": session.durationSeconds,
            "exercises": session.exercises.map(completedExerciseJSON),
        ]
        json["calories_burned"] = session.caloriesBurned
        return json
    }

    private static func completedExerciseJSON(_ exercise: StrengthCompletedExercise) -> JSON {
        var json: JSON = [
            "id": id(exercise.id),
            "item_id": exercise.itemID,
            "name": exercise.name,
            "target_muscles": exercise.targetMuscles.map(portableLabel),
            "equipment": portableLabel(exercise.equipment),
            "sets": exercise.sets.map { set -> JSON in
                var setJSON: JSON = [
                    "id": id(set.id), "set_number": set.setNumber, "weight": set.weight,
                    "weight_unit": set.weightUnit, "reps": set.reps, "rpe": set.rpe,
                ]
                setJSON["rpe_scale"] = set.rpeScale?.rawValue
                return setJSON
            },
        ]
        json["duration_seconds"] = exercise.durationSeconds
        json["intensity"] = exercise.intensity?.rawValue
        return json
    }

    static func session(from json: JSON, calendar: Calendar) -> StrengthWorkoutSession? {
        guard let id = uuid(json["id"] as? String) else { return nil }
        let started = date(fromTimestamp: json["started_at"] as? String)
        let completed = date(fromTimestamp: json["completed_at"] as? String)
        guard let startedAt = started ?? completed else { return nil }
        let completedAt = completed ?? startedAt
        let rawKey = json["diary_date_key"] as? String
        let key = day(fromString: rawKey, calendar: calendar) != nil ? (rawKey ?? "") : dayString(startedAt, calendar: calendar)
        let diaryDate = day(fromString: key, calendar: calendar) ?? calendar.startOfDay(for: startedAt)
        let calories = int(json["calories_burned"]).flatMap { $0 > 0 ? min($0, 5_000) : nil }
        let exercises = (json["exercises"] as? [Any])?.compactMap { ($0 as? JSON).flatMap(completedExercise(from:)) } ?? []
        return StrengthWorkoutSession(
            id: id,
            diaryDate: diaryDate,
            diaryDateKey: key,
            startedAt: startedAt,
            completedAt: completedAt,
            durationSeconds: max(0, int(json["duration_seconds"]) ?? Int((double(json["duration_seconds"]) ?? 0).rounded())),
            exercises: exercises,
            caloriesBurned: calories
        )
    }

    private static func completedExercise(from json: JSON) -> StrengthCompletedExercise? {
        guard let itemID = json["item_id"] as? String, let name = json["name"] as? String else { return nil }
        let sets = (json["sets"] as? [Any])?.enumerated().compactMap { index, raw -> StrengthCompletedSet? in
            guard let set = raw as? JSON else { return nil }
            return StrengthCompletedSet(
                id: uuid(set["id"] as? String) ?? UUID(),
                setNumber: int(set["set_number"]) ?? index + 1,
                weight: text(set["weight"]) ?? "",
                weightUnit: (set["weight_unit"] as? String).flatMap { ["kg", "lbs"].contains($0) ? $0 : nil } ?? "kg",
                reps: text(set["reps"]) ?? "",
                rpe: text(set["rpe"]) ?? "",
                rpeScale: (set["rpe_scale"] as? String).flatMap(StrengthWorkoutRPEScale.init(rawValue:))
            )
        } ?? []
        return StrengthCompletedExercise(
            id: uuid(json["id"] as? String) ?? UUID(),
            itemID: itemID,
            name: name,
            targetMuscles: labels(json["target_muscles"]),
            equipment: iPhoneLabel(json["equipment"] as? String),
            sets: sets,
            durationSeconds: double(json["duration_seconds"]).flatMap { $0 > 0 ? $0 : nil },
            intensity: (json["intensity"] as? String).flatMap(StrengthWorkoutIntensity.init(rawValue:))
        )
    }
}
