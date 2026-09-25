import Foundation

/// Reads an `ayuvo-portable-data` file (docs/portable-data.md, written by either platform) into
/// this iPhone's persisted state. `parse` and the `merge…` functions are pure; `apply` writes through
/// `PersistedBlobGuard`/`UserDefaults` only. It never calls the HealthKit write callbacks (the
/// samples came from a Health app), so the caller posts `.appBackupDidRestore` afterwards and every
/// store reloads what was written.
enum PortableDataImport {
    enum Failure: LocalizedError, Equatable {
        case notPortableData
        case newerVersion
        case couldNotSave

        var errorDescription: String? {
            switch self {
            case .notPortableData: String(localized: "This isn't an Ayuvo profile and logs file.")
            case .newerVersion: String(localized: "This file was made by a newer version of Ayuvo. Update the app to import it.")
            case .couldNotSave: String(localized: "Couldn't save the profile and logs. Nothing was changed.")
            }
        }
    }

    struct Workouts {
        var preferences: StrengthWorkoutPreferences?
        var savedExerciseIDs: Set<String> = []
        var userExercises: [StrengthPlannedExercise] = []
        var customActivities: [StrengthPlannedExercise] = []
        var dayPlans: [String: [StrengthPlannedExercise]] = [:]
        var sessions: [StrengthWorkoutSession] = []
    }

    /// The file as validated iPhone values. Sections the file lacks stay empty/nil (the device is left as it is).
    struct Document {
        var profile: UserProfile?
        /// A `profile` object was present but incomplete or invalid.
        var profileRejected = false
        var settings: [(key: String, value: PortableData.SettingValue)] = []
        var mealSchedule: MealSchedule?
        var summaryFavourites: [String]?
        var nutrientGoals: [OptionalNutrient: Int] = [:]
        var weights: [WeightEntry] = []
        var bodyFat: [BodyFatEntry] = []
        var bodyMeasurements: [BodyMeasurement] = []
        var fastingSessions: [FastingSession] = []
        var workouts: Workouts?
    }

    struct Summary: Equatable {
        var profileApplied = false
        var profileRejected = false
        /// Settings written (each shared preference, unit, and the meal schedule / favourites / nutrient goals groups).
        var settings = 0
        var weights = 0
        var bodyFat = 0
        var bodyMeasurements = 0
        var fastingSessions = 0
        var workoutSessions = 0
        var workoutExercises = 0
        var workoutPlans = 0
        var workoutSavedExercises = 0
        var workoutPreferencesReplaced = false
        /// Groups that couldn't be written (a blob this build must not overwrite).
        var failed: [String] = []

        var addedLogs: Int {
            weights + bodyFat + bodyMeasurements + fastingSessions + workoutSessions + workoutExercises + workoutPlans
                + workoutSavedExercises
        }

        /// Whether anything on the device changed, i.e. whether stores need to reload.
        var didApplyAnything: Bool {
            profileApplied || settings > 0 || addedLogs > 0 || workoutPreferencesReplaced
        }

        var text: String {
            var parts: [String] = []
            if profileApplied { parts.append(String(localized: "Profile replaced.")) }
            if profileRejected { parts.append(String(localized: "The profile in the file was incomplete and was skipped.")) }
            if settings > 0 { parts.append(String(localized: "\(settings) settings applied.")) }
            if addedLogs > 0 {
                parts.append(String(localized: "\(addedLogs) log entries added."))
            } else if didApplyAnything {
                parts.append(String(localized: "No new log entries."))
            }
            if !failed.isEmpty { parts.append(String(localized: "Couldn't update: \(failed.joined(separator: ", ")).")) }
            return parts.isEmpty ? String(localized: "Nothing to import.") : parts.joined(separator: " ")
        }
    }

    // MARK: - Entry point

    /// Parses `data` and applies it. Throws before touching anything when the file is refused.
    @discardableResult
    static func restore(
        data: Data,
        defaults: UserDefaults = .standard,
        calendar: Calendar = .current,
        backupDirectory: URL? = nil
    ) throws -> Summary {
        let document = try parse(data, calendar: calendar)
        let summary = apply(document, to: defaults, backupDirectory: backupDirectory)
        if !summary.didApplyAnything, !summary.failed.isEmpty { throw Failure.couldNotSave }
        return summary
    }

    // MARK: - Parse

    static func parse(_ data: Data, calendar: Calendar = .current) throws -> Document {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? PortableData.JSON,
              root["format"] as? String == PortableData.format,
              let version = PortableData.int(root["format_version"])
        else { throw Failure.notPortableData }
        guard version <= PortableData.formatVersion else { throw Failure.newerVersion }

        var document = Document()
        if let raw = root["profile"], !(raw is NSNull) {
            document.profile = (raw as? PortableData.JSON).flatMap { PortableData.profile(from: $0, calendar: calendar) }
            document.profileRejected = document.profile == nil
        }

        let units = root["units"] as? PortableData.JSON
        let preferences = root["preferences"] as? PortableData.JSON
        let water = (root["water"] as? PortableData.JSON)?["settings"] as? PortableData.JSON
        let fastingRoot = root["fasting"] as? PortableData.JSON
        let fasting = fastingRoot?["settings"] as? PortableData.JSON
        for setting in PortableData.settings {
            let group: PortableData.JSON? = switch setting.group {
            case .units: units
            case .preferences: preferences
            case .water: water
            case .fasting: fasting
            }
            if let value = setting.validated(group?[setting.json]) {
                document.settings.append((setting.native, value))
            }
        }
        if let preferences {
            document.mealSchedule = mealSchedule(preferences["meal_start_minutes"])
            if let raw = preferences["summary_favourites"] as? [Any] {
                let ids = raw.compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
                document.summaryFavourites = Array(ids.prefix(PortableData.maxSummaryFavourites))
            }
            if let goals = preferences["optional_nutrient_goals"] as? PortableData.JSON {
                for nutrient in OptionalNutrient.allCases {
                    if let value = PortableData.int(goals[nutrient.rawValue]), (0...OptionalNutrientGoals.maximumCustomGoal).contains(value) {
                        document.nutrientGoals[nutrient] = value
                    }
                }
            }
        }

        document.weights = rows(root["weights"], PortableData.weight)
        document.bodyFat = rows(root["body_fat"], PortableData.bodyFat)
        document.bodyMeasurements = rows(root["body_measurements"], PortableData.measurement)
        document.fastingSessions = rows(fastingRoot?["sessions"], PortableData.fasting)

        if let raw = root["workouts"] as? PortableData.JSON {
            var workouts = Workouts()
            workouts.preferences = (raw["preferences"] as? PortableData.JSON).map(PortableData.workoutPreferences)
            workouts.savedExerciseIDs = Set(PortableData.strings(raw["saved_exercise_ids"]).filter { !$0.isEmpty })
            workouts.userExercises = rows(raw["user_exercises"], PortableData.exercise).map(asTemplate)
            workouts.customActivities = rows(raw["custom_activities"], PortableData.exercise).map(asTemplate)
            if let plans = raw["day_plans"] as? PortableData.JSON {
                for (key, value) in plans where PortableData.day(fromString: key, calendar: calendar) != nil {
                    let exercises = rows(value, PortableData.exercise)
                    if !exercises.isEmpty { workouts.dayPlans[key] = exercises }
                }
            }
            workouts.sessions = rows(raw["sessions"]) { PortableData.session(from: $0, calendar: calendar) }
            document.workouts = workouts
        }
        return document
    }

    private static func rows<Element>(_ raw: Any?, _ parse: (PortableData.JSON) -> Element?) -> [Element] {
        (raw as? [Any])?.compactMap { ($0 as? PortableData.JSON).flatMap(parse) } ?? []
    }

    /// Saved templates carry no sets or timer (same as `StrengthWorkoutStore.saveUserExercise`).
    private static func asTemplate(_ exercise: StrengthPlannedExercise) -> StrengthPlannedExercise {
        var template = exercise
        template.sets = []
        template.timer = nil
        return template
    }

    private static func mealSchedule(_ raw: Any?) -> MealSchedule? {
        guard let json = raw as? PortableData.JSON else { return nil }
        let minutes = PortableData.mealJSONKeys.compactMap { PortableData.int(json[$0]) }
        guard minutes.count == 4 else { return nil }
        let schedule = MealSchedule(
            breakfastStartMinutes: minutes[0], lunchStartMinutes: minutes[1],
            dinnerStartMinutes: minutes[2], snackStartMinutes: minutes[3]
        )
        return schedule.isValid ? schedule : nil
    }

    // MARK: - Merge (pure)

    private struct Stamp: Hashable {
        var second: Int64
        var value: Int
    }

    private static func stamp(_ date: Date, _ value: Double, scale: Double) -> Stamp {
        Stamp(second: Int64(date.timeIntervalSince1970.rounded(.down)), value: Int((value * scale).rounded()))
    }

    /// Skips an id already on the device (case-insensitive, `UUID` compares by value) and a row with
    /// the same second and value: Health Connect and HealthKit ids differ per platform.
    static func merge(weights existing: [WeightEntry], incoming: [WeightEntry]) -> (merged: [WeightEntry], added: Int) {
        var ids = Set(existing.map(\.id))
        var stamps = Set(existing.map { stamp($0.date, $0.weightKg, scale: 1_000) })
        var added: [WeightEntry] = []
        for entry in incoming.sorted(by: { $0.date < $1.date }) {
            guard !ids.contains(entry.id), stamps.insert(stamp(entry.date, entry.weightKg, scale: 1_000)).inserted else { continue }
            ids.insert(entry.id)
            added.append(entry)
        }
        return (existing + added, added.count)
    }

    static func merge(bodyFat existing: [BodyFatEntry], incoming: [BodyFatEntry]) -> (merged: [BodyFatEntry], added: Int) {
        var ids = Set(existing.map(\.id))
        var stamps = Set(existing.map { stamp($0.date, $0.bodyFatFraction, scale: 10_000) })
        var added: [BodyFatEntry] = []
        for entry in incoming.sorted(by: { $0.date < $1.date }) {
            guard !ids.contains(entry.id), stamps.insert(stamp(entry.date, entry.bodyFatFraction, scale: 10_000)).inserted else { continue }
            ids.insert(entry.id)
            added.append(entry)
        }
        return (existing + added, added.count)
    }

    static func merge(bodyMeasurements existing: [BodyMeasurement], incoming: [BodyMeasurement]) -> (merged: [BodyMeasurement], added: Int) {
        var ids = Set(existing.map(\.id))
        let added = incoming.sorted { $0.date < $1.date }.filter { ids.insert($0.id).inserted }
        return (existing + added, added.count)
    }

    /// Never creates a second active fast or an overlap: such a row is dropped. The result is sorted
    /// by start, the way `FastingStore` keeps it.
    static func merge(fasting existing: [FastingSession], incoming: [FastingSession]) -> (merged: [FastingSession], added: Int) {
        var merged = existing
        var ids = Set(existing.map(\.id))
        var added = 0
        for session in incoming.sorted(by: { $0.startedAt < $1.startedAt }) {
            guard !ids.contains(session.id) else { continue }
            if session.isActive, merged.contains(where: \.isActive) { continue }
            let end = session.endedAt ?? .distantFuture
            let overlaps = merged.contains { other in
                session.startedAt < (other.endedAt ?? .distantFuture) && other.startedAt < end
            }
            guard !overlaps else { continue }
            ids.insert(session.id)
            merged.append(session)
            added += 1
        }
        return (merged.sorted { $0.startedAt < $1.startedAt }, added)
    }

    struct WorkoutMerge {
        var state: StrengthWorkoutStore.PersistedState
        var sessions = 0
        var exercises = 0
        var plans = 0
        var saved = 0
        var preferencesReplaced = false
    }

    /// Sessions and templates by id (templates also by `item_id`, the store's identity for them),
    /// saved ids as a union, a day already planned here is kept, preferences replace. A calculated
    /// burn snapshot for a day that already has one is skipped, so a day's burn is never doubled.
    static func merge(workouts state: StrengthWorkoutStore.PersistedState, incoming: Workouts) -> WorkoutMerge {
        var result = WorkoutMerge(state: state)

        var sessionIDs = Set(state.completedSessions.map(\.id))
        var burnDays = Set(state.completedSessions.filter { $0.caloriesBurned != nil }.map(\.stableDiaryDateKey))
        for session in incoming.sessions.sorted(by: { $0.startedAt < $1.startedAt }) {
            guard !sessionIDs.contains(session.id) else { continue }
            if session.caloriesBurned != nil, !burnDays.insert(session.stableDiaryDateKey).inserted { continue }
            sessionIDs.insert(session.id)
            result.state.completedSessions.append(session)
            result.sessions += 1
        }

        func mergeTemplates(_ existing: [StrengthPlannedExercise]?, _ new: [StrengthPlannedExercise]) -> [StrengthPlannedExercise] {
            var all = existing ?? []
            var ids = Set(all.map(\.id))
            var itemIDs = Set(all.map(\.itemID))
            for exercise in new where !ids.contains(exercise.id) && !itemIDs.contains(exercise.itemID) {
                ids.insert(exercise.id)
                itemIDs.insert(exercise.itemID)
                all.append(exercise)
                result.exercises += 1
            }
            return all
        }
        if !incoming.userExercises.isEmpty {
            result.state.userExercises = mergeTemplates(state.userExercises, incoming.userExercises)
        }
        if !incoming.customActivities.isEmpty {
            result.state.customActivities = mergeTemplates(state.customActivities, incoming.customActivities)
        }

        result.state.savedExerciseIDs.formUnion(incoming.savedExerciseIDs)
        result.saved = result.state.savedExerciseIDs.count - state.savedExerciseIDs.count
        for (key, exercises) in incoming.dayPlans where (state.dayPlans[key]?.exercises.isEmpty ?? true) {
            result.state.dayPlans[key] = StrengthWorkoutDayPlan(dateKey: key, exercises: exercises)
            result.plans += 1
        }
        if let preferences = incoming.preferences, preferences != state.preferences {
            result.state.preferences = preferences
            result.preferencesReplaced = true
        }
        return result
    }

    // MARK: - Apply

    /// Writes `document` to `defaults`: settings, the logs, then the profile last (so its weight and
    /// body fat are what the device ends up with). A group whose blob can't be replaced safely is
    /// reported in `failed` and the rest still applies.
    static func apply(_ document: Document, to defaults: UserDefaults = .standard, backupDirectory: URL? = nil) -> Summary {
        var summary = Summary()
        summary.profileRejected = document.profileRejected

        summary.settings = applySettings(document, to: defaults)

        func merged<Element: Codable>(
            _ type: Element.Type, key: String, name: String,
            _ merge: ([Element]) -> (merged: [Element], added: Int)
        ) -> Int {
            let blob = PersistedBlobGuard(defaults: defaults, key: key, backupDirectory: backupDirectory)
            let existing: [Element]
            switch blob.loadList(type) {
            case .missing, .corrupt: existing = []
            case .decoded(let items, _): existing = items
            }
            let result = merge(existing)
            guard result.added > 0 else { return 0 }
            guard blob.save(result.merged) else {
                summary.failed.append(name)
                return 0
            }
            return result.added
        }
        if !document.weights.isEmpty {
            summary.weights = merged(WeightEntry.self, key: WeightStore.storageKey, name: "weights") { merge(weights: $0, incoming: document.weights) }
        }
        if !document.bodyFat.isEmpty {
            summary.bodyFat = merged(BodyFatEntry.self, key: "bodyFatEntries", name: "body fat") { merge(bodyFat: $0, incoming: document.bodyFat) }
        }
        if !document.bodyMeasurements.isEmpty {
            summary.bodyMeasurements = merged(BodyMeasurement.self, key: "bodyMeasurementEntries", name: "body measurements") {
                merge(bodyMeasurements: $0, incoming: document.bodyMeasurements)
            }
        }
        if !document.fastingSessions.isEmpty {
            summary.fastingSessions = merged(FastingSession.self, key: FastingSettings.sessionsKey, name: "fasting") {
                merge(fasting: $0, incoming: document.fastingSessions)
            }
        }
        if let workouts = document.workouts {
            applyWorkouts(workouts, to: defaults, backupDirectory: backupDirectory, summary: &summary)
        }

        if let profile = document.profile {
            let blob = PersistedBlobGuard(defaults: defaults, key: UserProfile.storageKey, backupDirectory: backupDirectory)
            _ = blob.loadValue(UserProfile.self)
            if blob.save(profile) {
                summary.profileApplied = true
            } else {
                summary.failed.append("profile")
            }
        }
        return summary
    }

    private static func applySettings(_ document: Document, to defaults: UserDefaults) -> Int {
        var count = 0
        for (key, value) in document.settings {
            value.write(to: defaults, key: key)
            count += 1
        }
        if let schedule = document.mealSchedule {
            let minutes = [schedule.breakfastStartMinutes, schedule.lunchStartMinutes, schedule.dinnerStartMinutes, schedule.snackStartMinutes]
            for (key, value) in zip(PortableData.mealKeys, minutes) { defaults.set(value, forKey: key) }
            count += 1
        }
        if let favourites = document.summaryFavourites {
            defaults.set(favourites, forKey: MetricPins.key)
            count += 1
        }
        if !document.nutrientGoals.isEmpty {
            var goals = defaults.data(forKey: OptionalNutrientGoals.storageKey).map(OptionalNutrientGoals.decoded(from:)) ?? .defaults
            for (nutrient, value) in document.nutrientGoals { goals.setGoal(value, for: nutrient) }
            defaults.set(goals.encodedData, forKey: OptionalNutrientGoals.storageKey)
            count += 1
        }
        return count
    }

    private static func applyWorkouts(_ workouts: Workouts, to defaults: UserDefaults, backupDirectory: URL?, summary: inout Summary) {
        let blob = PersistedBlobGuard(defaults: defaults, key: StrengthWorkoutStore.defaultStorageKey, backupDirectory: backupDirectory)
        let existing: StrengthWorkoutStore.PersistedState
        switch blob.loadValue(StrengthWorkoutStore.PersistedState.self) {
        case .missing, .corrupt:
            existing = StrengthWorkoutStore.PersistedState()
        case .decoded(let state, _):
            // Written by another schema version: the store blocks writes for it, so must the importer.
            guard state.version == StrengthWorkoutStore.currentStateVersion else {
                summary.failed.append("workouts")
                return
            }
            existing = state
        }
        let result = merge(workouts: existing, incoming: workouts)
        guard result.state != existing else { return }
        guard blob.save(result.state) else {
            summary.failed.append("workouts")
            return
        }
        summary.workoutSessions = result.sessions
        summary.workoutExercises = result.exercises
        summary.workoutPlans = result.plans
        summary.workoutSavedExercises = result.saved
        summary.workoutPreferencesReplaced = result.preferencesReplaced
    }
}
