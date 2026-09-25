import Foundation
import Testing
@testable import calorietracker

/// The five test obligations of docs/portable-data.md: fixture import, round trip, merge rules,
/// refusals and the cross-platform plan dispositions.
@MainActor
struct PortableDataTests {
    private typealias State = StrengthWorkoutStore.PersistedState

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        return calendar
    }

    private static var fixtureURL: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/portable/fixtures/portable-sample.json")
    }

    private func fixtureData() throws -> Data {
        try Data(contentsOf: Self.fixtureURL)
    }

    private func fixtureRoot() throws -> [String: Any] {
        try #require(try JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
    }

    /// An isolated suite plus a scratch directory for the guard's corrupt-blob backups.
    private struct Sandbox {
        let name = "PortableDataTests.\(UUID().uuidString)"
        let defaults: UserDefaults
        let backups = FileManager.default.temporaryDirectory.appendingPathComponent("portable-tests-\(UUID().uuidString)")

        init() throws {
            defaults = try #require(UserDefaults(suiteName: name))
        }

        func cleanUp() {
            defaults.removePersistentDomain(forName: name)
            try? FileManager.default.removeItem(at: backups)
        }

        var isUntouched: Bool { (defaults.persistentDomain(forName: name) ?? [:]).isEmpty }

        func decode<T: Decodable>(_ type: T.Type, _ key: String) throws -> T {
            try JSONDecoder().decode(type, from: try #require(defaults.data(forKey: key)))
        }

        func seed(_ value: some Encodable, _ key: String) throws {
            defaults.set(try JSONEncoder().encode(value), forKey: key)
        }
    }

    private func date(_ text: String) throws -> Date {
        try #require(PortableData.date(fromTimestamp: text))
    }

    // MARK: - 1. Fixture

    @Test func fixtureImportsIntoAnEmptyStore() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)

        #expect(summary.profileApplied)
        #expect(summary.failed.isEmpty)
        #expect(summary.weights == 3 && summary.bodyFat == 2 && summary.bodyMeasurements == 1 && summary.fastingSessions == 2)
        #expect(summary.workoutSessions == 2)

        // Profile: the birthday is the local calendar day, enums map straight across.
        let profile = try box.decode(UserProfile.self, UserProfile.storageKey)
        #expect(profile.name == "Sample User")
        #expect(profile.gender == .female)
        #expect(profile.birthday == calendar.date(from: DateComponents(year: 1994, month: 3, day: 17)))
        #expect(profile.heightCm == 168.5 && profile.weightKg == 64.2)
        #expect(profile.activityLevel == .veryActive && profile.goal == .lose)
        #expect(profile.bodyFatPercentage == 0.27 && profile.goalBodyFatPercentage == 0.22)
        #expect(profile.weeklyChangeKg == 0.5 && profile.goalWeightKg == 58)
        #expect(profile.customCalories == 1850 && profile.customProtein == 130 && profile.customFat == 60 && profile.customCarbs == 190)
        #expect(profile.autoBalanceMacro == .carbs)
        #expect(profile.allergenSensitivities == ["peanut", "shellfish"])
        #expect(profile.caloriesLocked == true && profile.lockedMacros == [.protein])
        #expect(profile.useBodyFatInBMR == nil)

        // Units and preferences land under the iPhone's own keys.
        let d = box.defaults
        #expect(d.string(forKey: "heightUnit") == "cm" && d.string(forKey: "weightUnit") == "kg")
        #expect(d.string(forKey: "waterUnit") == "floz" && d.string(forKey: "healthGlucoseUnit") == "mmol/L")
        #expect(d.object(forKey: "weekStartsOnMonday") as? Bool == false)
        #expect(d.integer(forKey: "dailyStepGoal") == 8500)
        #expect(d.string(forKey: "appearanceMode") == "dark" && d.string(forKey: "appThemeColor") == "teal")
        #expect(d.object(forKey: "adaptiveGoalsEnabled") as? Bool == false)
        #expect(d.object(forKey: "foodMeasurementPreferGramsByDefault") as? Bool == true)
        #expect(d.integer(forKey: "mealBreakfastStartMinutes") == 360 && d.integer(forKey: "mealLunchStartMinutes") == 780)
        #expect(d.integer(forKey: "mealDinnerStartMinutes") == 1140 && d.integer(forKey: "mealSnackStartMinutes") == 1320)
        #expect(d.stringArray(forKey: "summaryFavourites") == ["app:calories", "app:water", "steps"])
        let goals = OptionalNutrientGoals.decoded(from: try #require(d.data(forKey: "optionalNutrientGoals")))
        #expect(goals.goal(for: .fiber) == 35 && goals.goal(for: .sodium) == 2000 && goals.goal(for: .creatine) == 5)
        #expect(goals.goal(for: .calcium) == OptionalNutrient.calcium.defaultGoal, "keys the file lacks keep their default")
        #expect(d.object(forKey: "waterTrackingEnabled") as? Bool == true && d.integer(forKey: "waterDailyGoalMl") == 2500)
        #expect(d.object(forKey: "fastingTrackingEnabled") as? Bool == true && d.integer(forKey: "fastingDefaultGoalMinutes") == 960)
        #expect(d.object(forKey: "fastingGoalNotificationEnabled") as? Bool == false)

        // Logs
        let weights = try box.decode([WeightEntry].self, "weightEntries")
        #expect(weights.map(\.weightKg) == [65.4, 64.9, 64.2])
        #expect(weights[0].id.uuidString == "11111111-0000-4000-8000-000000000001")
        #expect(weights[1].date == (try date("2026-09-15T07:31:10.000Z")))
        let bodyFat = try box.decode([BodyFatEntry].self, "bodyFatEntries")
        #expect(bodyFat.map(\.bodyFatFraction) == [0.275, 0.27])
        let measurements = try box.decode([BodyMeasurement].self, "bodyMeasurementEntries")
        #expect(measurements.count == 1)
        #expect(measurements[0].waistCm == 74.5 && measurements[0].hipsCm == 98 && measurements[0].neckCm == 31.5 && measurements[0].chestCm == nil)
        let fasts = try box.decode([FastingSession].self, "fastingSessions")
        #expect(fasts.count == 2)
        #expect(fasts[0].id.uuidString == "0B1C2D3E-0000-4000-8000-000000000001")
        #expect(fasts[0].endedAt == (try date("2026-09-21T10:15:00.000Z")) && fasts[0].goalMinutes == 960)
        #expect(fasts[1].isActive && fasts[1].goalMinutes == 840)

        // Workouts
        let state = try box.decode(State.self, StrengthWorkoutStore.defaultStorageKey)
        #expect(state.version == 2)
        let preferences = state.preferences
        #expect(preferences.targetMuscles == ["Chest", "Back"])
        #expect(preferences.issues == [.lowerBack, .knee])
        #expect(preferences.additionalIssues == "", "iPhone keeps the free text only together with the Other issue")
        #expect(preferences.frequencyDays == 4 && preferences.duration == .seventyFive)
        #expect(preferences.split == .upperLower && preferences.rpeScale == .cr10)
        #expect(preferences.equipment == ["Barbell", "Dumbbell"])
        #expect(preferences.strength == StrengthWorkoutNumbers(benchPressKg: 60, squatKg: 85.5, deadliftKg: 105, overheadPressKg: 40))
        #expect(state.savedExerciseIDs == ["0001", "user_exercise_44444444-0000-4000-8000-000000000001"])
        let user = try #require(state.userExercises?.first)
        #expect(user.itemID == "user_exercise_44444444-0000-4000-8000-000000000001" && user.name == "Sandbag carry")
        #expect(user.primaryMuscles == ["Core"] && user.sets.isEmpty && user.instructions == ["Hug the bag", "Walk 20 m"])
        let activity = try #require(state.customActivities?.first)
        #expect(activity.name == "Padel" && activity.isCardio && activity.rawEquipment == "Unspecified")
        let plan = try #require(state.dayPlans["2026-09-26"]).exercises
        #expect(plan.count == 1)
        #expect(plan[0].itemID == "0001" && plan[0].rawEquipment == "Body Weight" && plan[0].bodyPart == "Waist")
        #expect(plan[0].imageURL?.absoluteString == "https://example.com/0001.png")
        #expect(plan[0].sets.map(\.weight) == ["0", ""] && plan[0].sets.map(\.reps) == ["15", "12"] && plan[0].sets.map(\.rpe) == ["6", "7.5"])
        #expect(plan[0].sets.map(\.weightUnit) == ["kg", nil] && plan[0].sets.map(\.rpeScale) == [.cr10, nil])
        #expect(state.completedSessions.count == 2)
        let logged = try #require(state.completedSessions.first { $0.stableDiaryDateKey == "2026-09-24" })
        #expect(logged.durationSeconds == 3150 && logged.caloriesBurned == nil && logged.healthSyncVersion == nil)
        #expect(logged.diaryDate == calendar.date(from: DateComponents(year: 2026, month: 9, day: 24)))
        #expect(logged.exercises[0].durationSeconds == 240.5 && logged.exercises[0].intensity == .vigorous)
        #expect(logged.exercises[0].sets.map(\.weight) == ["20", "22.5"] && logged.exercises[0].sets.map(\.weightUnit) == ["lbs", "lbs"])
        #expect(logged.exercises[0].sets.map(\.rpeScale) == [.borg, nil] && logged.exercises[0].sets.map(\.setNumber) == [1, 2])
        let burn = try #require(state.completedSessions.first { $0.stableDiaryDateKey == "2026-09-25" })
        #expect(burn.caloriesBurned == 420 && burn.exercises.isEmpty && burn.healthSyncVersion == nil)
    }

    @Test func iPhoneVariantOfTheFixtureImportsTheSame() throws {
        var root = try fixtureRoot()
        root["platform"] = "ios"
        let data = try JSONSerialization.data(withJSONObject: root)
        let box = try Sandbox()
        defer { box.cleanUp() }
        let summary = try PortableDataImport.restore(data: data, defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        #expect(summary.profileApplied && summary.weights == 3)
    }

    // MARK: - 2. Round trip

    private func sampleProfile() throws -> UserProfile {
        var profile = UserProfile(
            name: "Round Trip", gender: .other,
            birthday: try #require(calendar.date(from: DateComponents(year: 1990, month: 2, day: 28))),
            heightCm: 180.5, weightKg: 82.25, activityLevel: .extraActive, goal: .gain,
            bodyFatPercentage: 0.18, goalBodyFatPercentage: 0.15, useBodyFatInBMR: nil,
            weeklyChangeKg: 0.25, goalWeightKg: 88,
            customCalories: 3100, customProtein: 180, customFat: 90, customCarbs: 380,
            autoBalanceMacro: .fat, allergenSensitivities: ["milk", "tree nuts"]
        )
        profile.caloriesLocked = true
        profile.lockedMacros = [.protein, .carbs]
        return profile
    }

    private func benchExercise() -> StrengthPlannedExercise {
        var exercise = StrengthPlannedExercise(item: ExerciseLibraryItem(
            id: "0002", name: "Bench press", bodyPart: "chest", rawEquipment: "barbell",
            primaryMuscles: ["pectorals"], secondaryMuscles: ["triceps", "delts"], instructions: ["Lie down", "Press"],
            imageURL: URL(string: "https://example.com/a.png"), gifURL: URL(string: "https://example.com/a.gif")
        ))
        exercise.sets = [
            StrengthPlannedSet(weight: "80", weightUnit: "kg", reps: "8", rpe: "7.5", rpeScale: .strength),
            StrengthPlannedSet(weight: "", weightUnit: nil, reps: "", rpe: "", rpeScale: nil),
        ]
        return exercise
    }

    private func templateExercise(_ itemID: String, _ name: String, bodyPart: String, equipment: String?) -> StrengthPlannedExercise {
        var template = StrengthPlannedExercise(item: ExerciseLibraryItem(
            id: itemID, name: name, bodyPart: bodyPart, rawEquipment: equipment, primaryMuscles: ["core"], instructions: ["Go"]
        ))
        template.sets = []
        return template
    }

    private func sampleState() throws -> State {
        var plan = benchExercise()
        plan.timer = StrengthExerciseTimer(accumulatedSeconds: 30, runningSince: nil, savedDurationSeconds: 30, intensity: .light)
        var user = templateExercise("user_exercise_\(UUID().uuidString)", "Sandbag carry", bodyPart: "cardio", equipment: "sandbag")
        user.imagePaths = ["photo.jpg"]
        let custom = templateExercise("custom_activity_\(UUID().uuidString)", "Padel", bodyPart: "cardio", equipment: nil)
        let logged = StrengthWorkoutSession(
            id: UUID(), diaryDate: try #require(calendar.date(from: DateComponents(year: 2026, month: 10, day: 1))), diaryDateKey: "2026-10-01",
            startedAt: Date(timeIntervalSince1970: 1_790_000_000), completedAt: Date(timeIntervalSince1970: 1_790_001_800), durationSeconds: 1800,
            exercises: [StrengthCompletedExercise(
                id: UUID(), itemID: "0002", name: "Bench press", targetMuscles: ["Pectorals"], equipment: "Barbell",
                sets: [StrengthCompletedSet(id: UUID(), setNumber: 1, weight: "80", weightUnit: "kg", reps: "8", rpe: "7", rpeScale: .strength)],
                durationSeconds: 90.5, intensity: .light
            )]
        )
        let burn = StrengthWorkoutSession(
            id: UUID(), diaryDate: try #require(calendar.date(from: DateComponents(year: 2026, month: 10, day: 2))), diaryDateKey: "2026-10-02",
            startedAt: Date(timeIntervalSince1970: 1_790_090_000), completedAt: Date(timeIntervalSince1970: 1_790_090_000), durationSeconds: 0,
            exercises: [], caloriesBurned: 350, healthSyncVersion: 3
        )
        var state = State()
        state.dayPlans["2026-10-03"] = StrengthWorkoutDayPlan(dateKey: "2026-10-03", exercises: [plan])
        state.completedSessions = [logged, burn]
        state.savedExerciseIDs = ["0002", user.itemID]
        state.userExercises = [user]
        state.customActivities = [custom]
        state.preferences = StrengthWorkoutPreferences(
            targetMuscles: ["Chest", "Back"], issues: [.knee, .other], additionalIssues: "note", frequencyDays: 5,
            duration: .ninety, split: .antagonistSplit, customSplit: "", equipment: ["Barbell", "Body Weight"],
            rpeScale: .borg, strength: StrengthWorkoutNumbers(benchPressKg: 100, squatKg: 140.5, deadliftKg: nil, overheadPressKg: 60)
        )
        return state
    }

    @Test func exportThenImportRoundTripsEveryCarriedField() throws {
        let source = try Sandbox()
        let target = try Sandbox()
        defer { source.cleanUp(); target.cleanUp() }

        let profile = try sampleProfile()
        try source.seed(profile, UserProfile.storageKey)
        let weights = [
            WeightEntry(id: UUID(), date: Date(timeIntervalSince1970: 1_790_000_000), weightKg: 82.25),
            WeightEntry(id: UUID(), date: Date(timeIntervalSince1970: 1_790_100_000), weightKg: 81.9),
        ]
        let bodyFat = [BodyFatEntry(id: UUID(), date: Date(timeIntervalSince1970: 1_790_000_100), bodyFatFraction: 0.18)]
        let measurements = [BodyMeasurement(id: UUID(), date: Date(timeIntervalSince1970: 1_790_000_200), neckCm: 38, waistCm: 84.5, wristCm: 17)]
        let fasts = [
            FastingSession(id: UUID(), startedAt: Date(timeIntervalSince1970: 1_789_000_000), endedAt: Date(timeIntervalSince1970: 1_789_060_000), goalMinutes: 960),
            FastingSession(id: UUID(), startedAt: Date(timeIntervalSince1970: 1_789_500_000), endedAt: nil, goalMinutes: 720),
        ]
        try source.seed(weights, "weightEntries")
        try source.seed(bodyFat, "bodyFatEntries")
        try source.seed(measurements, "bodyMeasurementEntries")
        try source.seed(fasts, "fastingSessions")
        let state = try sampleState()
        try source.seed(state, StrengthWorkoutStore.defaultStorageKey)
        let sd = source.defaults
        sd.set("ftin", forKey: "heightUnit"); sd.set("lbs", forKey: "weightUnit"); sd.set("ml", forKey: "waterUnit")
        sd.set(true, forKey: "weekStartsOnMonday"); sd.set(12_000, forKey: "dailyStepGoal")
        sd.set("light", forKey: "appearanceMode"); sd.set("indigo", forKey: "appThemeColor")
        sd.set(true, forKey: "adaptiveGoalsEnabled"); sd.set(false, forKey: "foodMeasurementPreferGramsByDefault")
        sd.set(420, forKey: "mealBreakfastStartMinutes"); sd.set(700, forKey: "mealLunchStartMinutes")
        sd.set(1100, forKey: "mealDinnerStartMinutes"); sd.set(1300, forKey: "mealSnackStartMinutes")
        sd.set(["app:weight", "sleep_analysis"], forKey: "summaryFavourites")
        sd.set(OptionalNutrientGoals.defaults.settingGoal(45, for: .fiber).settingGoal(6, for: .taurine).encodedData, forKey: "optionalNutrientGoals")
        sd.set(true, forKey: "waterTrackingEnabled"); sd.set(3000, forKey: "waterDailyGoalMl")
        sd.set(true, forKey: "fastingTrackingEnabled"); sd.set(1080, forKey: "fastingDefaultGoalMinutes"); sd.set(true, forKey: "fastingGoalNotificationEnabled")
        sd.set("mg/dL", forKey: "healthGlucoseUnit")

        let output = try #require(PortableDataExport.build(defaults: sd, now: try date("2026-09-25T10:30:00.000Z"), appVersion: "9.9", calendar: calendar))
        #expect(output.counts == [
            "weights": 2, "body_fat": 1, "body_measurements": 1, "fasting_sessions": 2,
            "workout_sessions": 2, "user_exercises": 1, "settings": 18,
        ])

        let summary = try PortableDataImport.restore(data: output.data, defaults: target.defaults, calendar: calendar, backupDirectory: target.backups)
        #expect(summary.profileApplied && summary.failed.isEmpty)

        #expect(try target.decode(UserProfile.self, UserProfile.storageKey) == profile)
        #expect(try target.decode([WeightEntry].self, "weightEntries").map(\.id) == weights.map(\.id))
        #expect(try target.decode([WeightEntry].self, "weightEntries").map(\.weightKg) == weights.map(\.weightKg))
        #expect(try target.decode([WeightEntry].self, "weightEntries").map(\.date) == weights.map(\.date))
        #expect(try target.decode([BodyFatEntry].self, "bodyFatEntries").map(\.bodyFatFraction) == [0.18])
        #expect(try target.decode([BodyMeasurement].self, "bodyMeasurementEntries") == measurements)
        #expect(try target.decode([FastingSession].self, "fastingSessions") == fasts)

        // Not carried: timers, user-photo filenames, health-sync bookkeeping.
        var expected = state
        expected.dayPlans["2026-10-03"]?.exercises[0].timer = nil
        expected.userExercises?[0].imagePaths = []
        expected.completedSessions[1].healthSyncVersion = nil
        #expect(try target.decode(State.self, StrengthWorkoutStore.defaultStorageKey) == expected)

        let td = target.defaults
        for key in ["heightUnit", "weightUnit", "waterUnit", "appearanceMode", "appThemeColor", "healthGlucoseUnit"] {
            #expect(td.string(forKey: key) == sd.string(forKey: key), Comment(rawValue: key))
        }
        for key in ["dailyStepGoal", "mealBreakfastStartMinutes", "mealLunchStartMinutes", "mealDinnerStartMinutes", "mealSnackStartMinutes", "waterDailyGoalMl", "fastingDefaultGoalMinutes"] {
            #expect(td.integer(forKey: key) == sd.integer(forKey: key), Comment(rawValue: key))
        }
        for key in ["weekStartsOnMonday", "adaptiveGoalsEnabled", "foodMeasurementPreferGramsByDefault", "waterTrackingEnabled", "fastingTrackingEnabled", "fastingGoalNotificationEnabled"] {
            #expect(td.object(forKey: key) as? Bool == sd.object(forKey: key) as? Bool, Comment(rawValue: key))
        }
        #expect(td.stringArray(forKey: "summaryFavourites") == ["app:weight", "sleep_analysis"])
        let sourceGoals = OptionalNutrientGoals.decoded(from: try #require(sd.data(forKey: "optionalNutrientGoals")))
        let targetGoals = OptionalNutrientGoals.decoded(from: try #require(td.data(forKey: "optionalNutrientGoals")))
        #expect(OptionalNutrient.allCases.allSatisfy { sourceGoals.goal(for: $0) == targetGoals.goal(for: $0) })
        #expect(targetGoals.goal(for: .fiber) == 45 && targetGoals.goal(for: .taurine) == 6)
    }

    @Test func exportUsesTheContractsEncodings() throws {
        let source = try Sandbox()
        defer { source.cleanUp() }
        // 1990-02-28 in Kolkata is still 02-27 in UTC: the file carries the local day.
        try source.seed(try sampleProfile(), UserProfile.storageKey)
        try source.seed([WeightEntry(id: UUID(uuidString: "ABCDEF00-0000-4000-8000-000000000001")!, date: try date("2026-09-25T07:29:00.000Z"), weightKg: 64.2)], "weightEntries")
        var state = try sampleState()
        state.preferences.split = .broSplit
        try source.seed(state, StrengthWorkoutStore.defaultStorageKey)

        let output = try #require(PortableDataExport.build(defaults: source.defaults, now: try date("2026-09-25T10:30:00.000Z"), appVersion: "1.0", calendar: calendar))
        let json = try #require(try JSONSerialization.jsonObject(with: output.data) as? [String: Any])
        #expect(json["app"] as? String == "Ayuvo" && json["format"] as? String == "ayuvo-portable-data")
        #expect(json["format_version"] as? Int == 1 && json["platform"] as? String == "ios" && json["app_version"] as? String == "1.0")
        #expect(json["created_at"] as? String == "2026-09-25T10:30:00.000Z")
        let profile = try #require(json["profile"] as? [String: Any])
        #expect(profile["birthday"] as? String == "1990-02-28")
        #expect(profile["locked_macros"] as? [String] == ["protein", "carbs"] && profile["calories_locked"] as? Bool == true)
        let weight = try #require((json["weights"] as? [[String: Any]])?.first)
        #expect(weight["id"] as? String == "abcdef00-0000-4000-8000-000000000001")
        #expect(weight["date"] as? String == "2026-09-25T07:29:00.000Z")
        let workouts = try #require(json["workouts"] as? [String: Any])
        let preferences = try #require(workouts["preferences"] as? [String: Any])
        #expect(preferences["split"] as? String == "body_part" && preferences["issues"] as? [String] == ["knee", "other"])
        #expect(preferences["target_muscles"] as? [String] == ["back", "chest"] && preferences["rpe_scale"] as? String == "borg")
        let plans = try #require(workouts["day_plans"] as? [String: [[String: Any]]])
        let exercise = try #require(plans["2026-10-03"]?.first)
        #expect(exercise["body_part"] as? String == "chest" && exercise["equipment"] as? String == "barbell")
        #expect(exercise["timer"] == nil && exercise["image_paths"] == nil)
        let text = String(decoding: output.data, as: UTF8.self)
        #expect(!text.contains("health_sync") && !text.contains("photo.jpg"))
        let sessions = try #require(workouts["sessions"] as? [[String: Any]])
        #expect(sessions.contains { $0["calories_burned"] as? Int == 350 && $0["diary_date_key"] as? String == "2026-10-02" })
    }

    @Test func exportIsSkippedWhenThereIsNothingToCarry() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        #expect(PortableDataExport.build(defaults: box.defaults, appVersion: "1", calendar: calendar) == nil)
        // A default, untouched workout diary carries nothing either.
        try box.seed(State(), StrengthWorkoutStore.defaultStorageKey)
        #expect(PortableDataExport.build(defaults: box.defaults, appVersion: "1", calendar: calendar) == nil)
        box.defaults.set("kg", forKey: "weightUnit")
        #expect(PortableDataExport.build(defaults: box.defaults, appVersion: "1", calendar: calendar)?.counts == ["settings": 1])
    }

    // MARK: - 3. Merge

    @Test func importingTwiceAddsNothing() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let data = try fixtureData()
        try PortableDataImport.restore(data: data, defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        let keys = ["weightEntries", "bodyFatEntries", "bodyMeasurementEntries", "fastingSessions", StrengthWorkoutStore.defaultStorageKey]
        let before = keys.map { box.defaults.data(forKey: $0) }

        let second = try PortableDataImport.restore(data: data, defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        #expect(second.addedLogs == 0)
        #expect(!second.workoutPreferencesReplaced)
        #expect(keys.map { box.defaults.data(forKey: $0) } == before)
    }

    @Test func aRowWithTheSameIdOrTheSameSecondAndValueIsNotDuplicated() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let sameSecond = WeightEntry(id: UUID(), date: try date("2026-09-01T07:30:00.400Z"), weightKg: 65.4)
        // Uppercase on disk, lowercase in the file: ids compare case-insensitively.
        let sameID = WeightEntry(id: UUID(uuidString: "11111111-0000-4000-8000-000000000002")!, date: try date("2026-01-01T00:00:00.000Z"), weightKg: 70)
        let sameSecondOtherValue = WeightEntry(id: UUID(), date: try date("2026-09-25T07:29:00.000Z"), weightKg: 63)
        try box.seed([sameSecond, sameID, sameSecondOtherValue], "weightEntries")
        try box.seed([BodyFatEntry(id: UUID(), date: try date("2026-09-10T08:00:00.900Z"), bodyFatFraction: 0.275)], "bodyFatEntries")

        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        let weights = try box.decode([WeightEntry].self, "weightEntries")
        #expect(summary.weights == 1, "only the 64.2 kg row is new (a different value in the same second is a different row)")
        #expect(weights.count == 4)
        #expect(weights.filter { $0.weightKg == 65.4 }.count == 1 && weights.filter { $0.weightKg == 64.9 }.isEmpty)
        #expect(summary.bodyFat == 1)
        #expect(try box.decode([BodyFatEntry].self, "bodyFatEntries").count == 2)
    }

    @Test func aSecondActiveFastIsDropped() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let local = FastingSession(id: UUID(), startedAt: try date("2026-09-22T08:00:00.000Z"), endedAt: nil, goalMinutes: 960)
        try box.seed([local], "fastingSessions")

        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        let fasts = try box.decode([FastingSession].self, "fastingSessions")
        #expect(summary.fastingSessions == 1)
        #expect(fasts.count == 2)
        #expect(fasts.filter(\.isActive).map(\.id) == [local.id])
        #expect(fasts.map(\.startedAt) == fasts.map(\.startedAt).sorted())
    }

    @Test func overlappingFastsAreDropped() {
        let base = Date(timeIntervalSince1970: 1_790_000_000)
        let existing = [FastingSession(startedAt: base, endedAt: base.addingTimeInterval(3600 * 16), goalMinutes: 960)]
        let overlapping = FastingSession(startedAt: base.addingTimeInterval(3600 * 10), endedAt: base.addingTimeInterval(3600 * 20), goalMinutes: 600)
        let touching = FastingSession(startedAt: base.addingTimeInterval(3600 * 16), endedAt: base.addingTimeInterval(3600 * 30), goalMinutes: 600)
        let result = PortableDataImport.merge(fasting: existing, incoming: [overlapping, touching])
        #expect(result.added == 1 && result.merged.count == 2)
        #expect(result.merged.last?.id == touching.id)
    }

    @Test func aSameDayCalorieSnapshotIsNotDoubledAndALocalDayPlanWins() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        var local = State()
        local.completedSessions = [StrengthWorkoutSession(
            id: UUID(), diaryDate: try #require(calendar.date(from: DateComponents(year: 2026, month: 9, day: 25))), diaryDateKey: "2026-09-25",
            startedAt: Date(timeIntervalSince1970: 1_790_000_000), completedAt: Date(timeIntervalSince1970: 1_790_000_000), durationSeconds: 0,
            exercises: [], caloriesBurned: 300, healthSyncVersion: 2
        )]
        local.dayPlans["2026-09-26"] = StrengthWorkoutDayPlan(dateKey: "2026-09-26", exercises: [benchExercise()])
        local.userExercises = [templateExercise("user_exercise_44444444-0000-4000-8000-000000000001", "Local twin", bodyPart: "cardio", equipment: nil)]
        local.savedExerciseIDs = ["0999"]
        local.preferences.frequencyDays = 2
        try box.seed(local, StrengthWorkoutStore.defaultStorageKey)

        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        let state = try box.decode(State.self, StrengthWorkoutStore.defaultStorageKey)
        #expect(summary.workoutSessions == 1, "the 09-24 session is new, the 09-25 snapshot is not")
        #expect(state.completedSessions.filter { $0.caloriesBurned != nil }.map(\.caloriesBurned) == [300])
        #expect(state.completedSessions.first { $0.caloriesBurned != nil }?.healthSyncVersion == 2)
        #expect(state.dayPlans["2026-09-26"]?.exercises.first?.name == "Bench press", "a day already planned here is kept")
        #expect(state.userExercises?.map(\.name) == ["Local twin"], "the same item id is the same template")
        #expect(state.customActivities?.map(\.name) == ["Padel"])
        #expect(state.savedExerciseIDs == ["0999", "0001", "user_exercise_44444444-0000-4000-8000-000000000001"])
        #expect(state.preferences.frequencyDays == 4, "workout preferences are replaced")
    }

    @Test func aProfileInTheFileReplacesTheDeviceProfileAfterTheLogs() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        try box.seed(try sampleProfile(), UserProfile.storageKey)
        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        let profile = try box.decode(UserProfile.self, UserProfile.storageKey)
        #expect(summary.profileApplied)
        #expect(profile.name == "Sample User" && profile.weightKg == 64.2 && profile.bodyFatPercentage == 0.27)
    }

    @Test func missingSectionsLeaveTheDeviceAsItIs() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let profile = try sampleProfile()
        try box.seed(profile, UserProfile.storageKey)
        box.defaults.set("dark", forKey: "appearanceMode")
        let file = #"{"format":"ayuvo-portable-data","format_version":1,"weights":[{"id":"11111111-0000-4000-8000-000000000001","date":"2026-09-01T07:30:00Z","weight_kg":65.4}]}"#

        let summary = try PortableDataImport.restore(data: Data(file.utf8), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        #expect(!summary.profileApplied && summary.weights == 1 && summary.settings == 0)
        #expect(try box.decode(UserProfile.self, UserProfile.storageKey) == profile)
        #expect(box.defaults.string(forKey: "appearanceMode") == "dark")
        #expect(box.defaults.data(forKey: StrengthWorkoutStore.defaultStorageKey) == nil)
    }

    @Test func badValuesAreIgnoredOneByOne() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let file = """
        {"format":"ayuvo-portable-data","format_version":1,
         "profile":{"name":"No gender","birthday":"1990-01-01","height_cm":170,"weight_kg":70,"activity_level":"light","goal":"lose"},
         "units":{"height_unit":"cm","weight_unit":"stone","water_unit":7},
         "preferences":{"daily_step_goal":500,"appearance_mode":"neon","app_theme_color":"teal","week_starts_on_monday":1,
           "meal_start_minutes":{"breakfast":700,"lunch":600,"dinner":1140,"snack":1320},
           "summary_favourites":["a","","b",7,"c","d","e","f","g","h","i","j","k","l","m"],
           "optional_nutrient_goals":{"fiber":40,"sodium":-5,"iron":1.5,"unknown":3}},
         "fasting":{"settings":{"default_goal_minutes":20},"sessions":[{"id":"not-a-uuid","started_at":"2026-09-20T18:00:00Z","goal_minutes":960},{"id":"0b1c2d3e-0000-4000-8000-000000000009","started_at":"2026-09-20T18:00:00Z","ended_at":"garbage","goal_minutes":960}]},
         "weights":[{"id":"11111111-0000-4000-8000-000000000001","date":"yesterday","weight_kg":65},{"id":"11111111-0000-4000-8000-000000000002","date":"2026-09-01T07:30:00+05:30","weight_kg":-3},{"id":"11111111-0000-4000-8000-000000000003","date":"2026-09-01T07:30:00+05:30","weight_kg":66}]}
        """
        let summary = try PortableDataImport.restore(data: Data(file.utf8), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        let d = box.defaults
        #expect(summary.profileRejected && !summary.profileApplied && d.data(forKey: UserProfile.storageKey) == nil)
        #expect(d.string(forKey: "heightUnit") == "cm" && d.string(forKey: "weightUnit") == nil && d.string(forKey: "waterUnit") == nil)
        #expect(d.object(forKey: "dailyStepGoal") == nil && d.object(forKey: "appearanceMode") == nil && d.object(forKey: "weekStartsOnMonday") == nil)
        #expect(d.string(forKey: "appThemeColor") == "teal")
        #expect(d.object(forKey: "mealBreakfastStartMinutes") == nil, "a schedule that isn't strictly increasing is ignored as a group")
        #expect(d.stringArray(forKey: "summaryFavourites") == ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"])
        let goals = OptionalNutrientGoals.decoded(from: try #require(d.data(forKey: "optionalNutrientGoals")))
        #expect(goals.goal(for: .fiber) == 40 && goals.goal(for: .sodium) == OptionalNutrient.sodium.defaultGoal && goals.goal(for: .iron) == OptionalNutrient.iron.defaultGoal)
        #expect(d.object(forKey: "fastingDefaultGoalMinutes") == nil)
        #expect(d.data(forKey: "fastingSessions") == nil)
        let weights = try box.decode([WeightEntry].self, "weightEntries")
        #expect(weights.map(\.weightKg) == [66], "an offset timestamp is read as that instant")
        #expect(weights[0].date == (try date("2026-09-01T02:00:00.000Z")))
    }

    // MARK: - 4. Refusals

    @Test func refusedFilesLeaveEverythingUntouched() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        let local = [WeightEntry(id: UUID(), date: Date(timeIntervalSince1970: 1_790_000_000), weightKg: 70)]
        try box.seed(local, "weightEntries")
        let snapshot = box.defaults.persistentDomain(forName: box.name)

        var wrongFormat = try fixtureRoot()
        wrongFormat["format"] = "ayuvo-something-else"
        var newer = try fixtureRoot()
        newer["format_version"] = 2
        var noVersion = try fixtureRoot()
        noVersion["format_version"] = nil

        let cases: [(Data, PortableDataImport.Failure)] = [
            (try JSONSerialization.data(withJSONObject: wrongFormat), .notPortableData),
            (try JSONSerialization.data(withJSONObject: noVersion), .notPortableData),
            (try JSONSerialization.data(withJSONObject: newer), .newerVersion),
            (Data("this is not json".utf8), .notPortableData),
            (Data("[1,2,3]".utf8), .notPortableData),
            (Data(), .notPortableData),
        ]
        for (data, expected) in cases {
            #expect(throws: expected) {
                try PortableDataImport.restore(data: data, defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
            }
        }
        #expect(NSDictionary(dictionary: box.defaults.persistentDomain(forName: box.name) ?? [:]).isEqual(to: snapshot ?? [:]))

        let empty = try Sandbox()
        defer { empty.cleanUp() }
        #expect(throws: PortableDataImport.Failure.newerVersion) {
            try PortableDataImport.restore(data: JSONSerialization.data(withJSONObject: newer), defaults: empty.defaults, calendar: calendar, backupDirectory: empty.backups)
        }
        #expect(empty.isUntouched)
    }

    @Test func aWorkoutStateFromAnotherSchemaIsNeverOverwritten() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        var newer = State()
        newer.version = 3
        newer.savedExerciseIDs = ["keep"]
        try box.seed(newer, StrengthWorkoutStore.defaultStorageKey)
        let before = box.defaults.data(forKey: StrengthWorkoutStore.defaultStorageKey)

        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        #expect(summary.failed == ["workouts"])
        #expect(summary.profileApplied && summary.weights == 3, "the rest of the file still applies")
        #expect(box.defaults.data(forKey: StrengthWorkoutStore.defaultStorageKey) == before)
    }

    @Test func aCorruptLogIsBackedUpBeforeItIsReplaced() throws {
        let box = try Sandbox()
        defer { box.cleanUp() }
        box.defaults.set(Data("{broken".utf8), forKey: "weightEntries")
        let summary = try PortableDataImport.restore(data: fixtureData(), defaults: box.defaults, calendar: calendar, backupDirectory: box.backups)
        #expect(summary.weights == 3)
        let backups = try FileManager.default.contentsOfDirectory(atPath: box.backups.path)
        #expect(backups.count == 1 && backups[0].hasPrefix("weightEntries.corrupt-"))
    }

    // MARK: - 5. Cross-platform plan

    private func manifest(platform: String, sections: [String]) -> (Data, Set<String>) {
        let names: [String: String] = [
            "app_backup": "app-backup/ayuvo-backup.zip",
            "portable_data": "portable-data/ayuvo-portable-data.json",
            "food_diary": "food-diary/d.json",
            "coach_chats": "coach-chats/c.zip",
        ]
        let files = sections.map { #"{"name":"\#(names[$0]!)","section":"\#($0)","format":"x","bytes":1,"counts":{"weights":3}}"# }
        let json = #"{"app":"Ayuvo","format":"ayuvo-all-data","format_version":1,"platform":"\#(platform)","app_version":"1","created_at":"2026-09-25T10:00:00Z","files":[\#(files.joined(separator: ","))],"skipped":[]}"#
        return (Data(json.utf8), Set(sections.map { names[$0]! } + ["manifest.json"]))
    }

    @Test func aZipFromTheOtherPlatformImportsThePortablePart() throws {
        let (data, names) = manifest(platform: "android", sections: ["coach_chats", "food_diary", "portable_data", "app_backup"])
        let plan = try AllDataImport.plan(manifestData: data, entryNames: names)
        #expect(plan.items.map(\.section) == [.appBackup, .portableData, .foodDiary, .coachChats])
        #expect(plan.items.first { $0.section == .appBackup }?.disposition == .otherPlatform)
        let portable = try #require(plan.items.first { $0.section == .portableData })
        #expect(portable.disposition == .importIt && portable.entryName == "portable-data/ayuvo-portable-data.json")
        #expect(portable.counts == ["weights": 3])
        #expect(AllDataImport.shouldImport(portable, appBackupRestored: false))
        #expect(!plan.isFromThisPlatform)
    }

    @Test func aSamePlatformZipLetsTheAppBackupCoverThePortablePart() throws {
        let (data, names) = manifest(platform: "ios", sections: ["portable_data", "app_backup", "food_diary"])
        let plan = try AllDataImport.plan(manifestData: data, entryNames: names)
        let portable = try #require(plan.items.first { $0.section == .portableData })
        #expect(portable.disposition == .coveredByAppBackup)
        #expect(!AllDataImport.shouldImport(portable, appBackupRestored: true))
        #expect(AllDataImport.shouldImport(portable, appBackupRestored: false), "a failed backup restore falls back to the portable part")
    }

    @Test func aSamePlatformZipWithoutAnAppBackupImportsThePortablePart() throws {
        let (data, names) = manifest(platform: "ios", sections: ["portable_data"])
        let plan = try AllDataImport.plan(manifestData: data, entryNames: names)
        #expect(plan.items.first?.disposition == .importIt)
        // Seen from an Android build (the other platform) the same file is also imported.
        let (androidData, androidNames) = manifest(platform: "ios", sections: ["portable_data", "app_backup"])
        let seenFromAndroid = try AllDataImport.plan(manifestData: androidData, entryNames: androidNames, platform: "android")
        #expect(seenFromAndroid.items.first { $0.section == .portableData }?.disposition == .importIt)
    }
}
