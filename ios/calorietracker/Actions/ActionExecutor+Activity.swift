import Foundation

/// Fasting and workout actions (`FastingStore`, `StrengthWorkoutStore`).
extension ActionExecutor {
    // MARK: - Fasting

    private func fastingFields(_ session: FastingSession?) -> [String: ActionField] {
        guard let session, session.isActive else {
            return ["active": .bool(false), "started_ms": .null, "elapsed_s": .null, "goal_s": .null,
                    "remaining_s": .null, "percent": .null, "reached": .bool(false), "value": .number(0), "unit": .string("h")]
        }
        let p = ActionMath.fastingProgress(startedMs: ActionEnvironment.ms(session.startedAt), goalMinutes: session.goalMinutes, nowMs: env.nowMs)
        return [
            "active": .bool(true), "started_ms": .int(Int(ActionEnvironment.ms(session.startedAt))),
            "elapsed_s": .int(Int(p.elapsedS)), "goal_s": .int(Int(p.goalS)), "remaining_s": .int(Int(p.remainingS)),
            "percent": .optional(p.percent), "reached": .bool(p.reached),
            "value": .number(ActionMath.roundTo(Double(p.elapsedS) / 3600, 2)), "unit": .string("h"),
        ]
    }

    static func sessionFields(_ session: FastingSession, now: Date) -> [String: ActionField] {
        let duration = Int(session.duration(at: now))
        return [
            "id": .string(session.id.uuidString), "started_ms": .int(Int(ActionEnvironment.ms(session.startedAt))),
            "ended_ms": .optional(session.endedAt.map { Int(ActionEnvironment.ms($0)) }), "duration_s": .int(duration),
            "goal_s": .int(session.goalMinutes * 60), "reached": .bool(duration >= session.goalMinutes * 60),
        ]
    }

    func fastingStatus(_ v: ActionValidation) -> ActionResult {
        let session = env.fastingStore().activeSession
        let fields = fastingFields(session)
        guard let session else {
            return ActionResult(actionID: v.actionID, fields: fields, dialog: String(localized: "You're not fasting right now."))
        }
        let elapsed = HealthUnitFormatting.durationText(seconds: fields["elapsed_s"]?.double ?? 0)
        let remaining = fields["remaining_s"]?.double ?? 0
        let dialog = remaining > 0
            ? String(localized: "Fasting for \(elapsed). \(HealthUnitFormatting.durationText(seconds: remaining)) to your \(FastingDurationFormatter.goal(minutes: session.goalMinutes)) goal.")
            : String(localized: "Fasting for \(elapsed). You've reached your goal.")
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    func fastingHistory(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let limit = v.int("limit") ?? 50
        let sessions = env.fastingStore().sessions
            .filter { !$0.isActive && r.contains($0.endedAt ?? $0.startedAt) }
            .sorted { ($0.endedAt ?? $0.startedAt) > ($1.endedAt ?? $1.startedAt) }
            .prefix(limit)
        let items = sessions.map { Self.sessionFields($0, now: env.nowDate) }
        let reached = items.filter { $0["reached"]?.bool == true }.count
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count), "reached_count": .int(reached)], items: Array(items),
                            dialog: String(localized: "\(items.count) fasts \(Self.rangeText(v.string("range"))), \(reached) reached the goal."))
    }

    func fastingStart(_ v: ActionValidation) throws -> ActionResult {
        guard env.defaults.bool(forKey: FastingSettings.enabledKey) else {
            throw ActionError.unavailable(String(localized: "Fasting is off. Turn it on in Ayuvo Settings → Fasting."))
        }
        let minutes = min(max(Int(((v.double("goal_hours") ?? 16) * 60).rounded()), FastingSettings.minimumGoalMinutes), FastingSettings.maximumGoalMinutes)
        let store = env.fastingStore()
        if store.activeSession != nil {
            throw ActionError.conflict(String(localized: "A fast is already running."))
        }
        guard let session = store.start(goalMinutes: minutes, at: env.nowDate) else {
            throw ActionError.unavailable(String(localized: "Ayuvo couldn't start the fast. Open the app and try again."))
        }
        if env.sideEffects { FastingStore.postExternalChangeNotification() }
        return ActionResult(actionID: v.actionID, fields: fastingFields(session),
                            dialog: String(localized: "Started a \(FastingDurationFormatter.goal(minutes: minutes)) fast."))
    }

    func fastingStop(_ v: ActionValidation) throws -> ActionResult {
        let store = env.fastingStore()
        guard store.activeSession != nil else { throw ActionError.notFound(String(localized: "There's no fast running.")) }
        guard let session = store.endActive(at: env.nowDate) else {
            throw ActionError.unavailable(String(localized: "Ayuvo couldn't end the fast. Open the app and try again."))
        }
        if env.sideEffects { FastingStore.postExternalChangeNotification() }
        var fields = Self.sessionFields(session, now: env.nowDate)
        fields["value"] = .number(ActionMath.roundTo(session.duration(at: env.nowDate) / 3600, 2))
        fields["unit"] = .string("h")
        return ActionResult(actionID: v.actionID, fields: fields,
                            dialog: String(localized: "Fast ended after \(HealthUnitFormatting.durationText(seconds: session.duration(at: env.nowDate)))."))
    }

    // MARK: - Workouts

    /// Stored set weight → kg ("" when not a number).
    static func kilograms(_ weight: String, unit: String?, fallback: WeightUnit) -> Double {
        guard let value = Double(weight.replacingOccurrences(of: ",", with: ".")) else { return 0 }
        let resolved = unit.flatMap(WeightUnit.init(rawValue:)) ?? fallback
        return resolved == .kg ? value : value * 0.45359237
    }

    /// Locale-free diary text ("62.5", "60").
    static func plain(_ value: Double) -> String {
        if value.rounded() == value, abs(value) < 1e9 { return String(Int(value)) }
        var text = String(format: "%.2f", value)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }

    private var storedWeightUnit: WeightUnit { env.massUnit == "kg" ? .kg : .lbs }

    private func todayVolume(_ exercises: [StrengthPlannedExercise]) -> ActionMath.SetVolume {
        let sets = exercises.flatMap { exercise in
            exercise.sets.compactMap { set -> (weightKg: Double, reps: Int)? in
                guard let reps = Int(set.reps), reps > 0 else { return nil }
                return (Self.kilograms(set.weight, unit: set.weightUnit, fallback: storedWeightUnit), reps)
            }
        }
        return ActionMath.setVolume(sets)
    }

    private func workoutTodayFields(_ store: StrengthWorkoutStore) -> [String: ActionField] {
        let exercises = store.exercises(for: env.nowDate)
        let volume = todayVolume(exercises)
        return [
            "exercise_count": .int(exercises.count), "sets_done": .int(volume.sets), "reps_done": .int(volume.reps),
            "volume_kg": .number(volume.volumeKg), "completed": .bool(store.latestSession(on: env.nowDate) != nil),
            "value": .number(volume.volumeKg), "unit": .string("kg"),
        ]
    }

    func workoutToday(_ v: ActionValidation) -> ActionResult {
        let store = env.workoutStore()
        let fields = workoutTodayFields(store)
        let count = store.exercises(for: env.nowDate).count
        let dialog = count == 0
            ? String(localized: "No workout planned for today.")
            : String(localized: "Today: \(count) exercises, \(fields["sets_done"]?.double.map { Int($0) } ?? 0) sets done, \(weightText(fields["volume_kg"]?.double ?? 0)) total volume.")
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    static func sessionVolume(_ session: StrengthWorkoutSession, fallback: WeightUnit) -> Double {
        let sets = session.exercises.flatMap(\.sets).compactMap { set -> (weightKg: Double, reps: Int)? in
            guard set.isPerformed, let reps = Int(set.reps) else { return nil }
            return (kilograms(set.weight, unit: set.weightUnit, fallback: fallback), reps)
        }
        return ActionMath.setVolume(sets).volumeKg
    }

    func workoutHistory(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let limit = v.int("limit") ?? 50
        let sessions = env.workoutStore().sortedCompletedSessions
            .filter { r.contains($0.calendarDiaryDate) }
            .prefix(limit)
        let items: [[String: ActionField]] = sessions.map { session in
            [
                "id": .string(session.id.uuidString), "date": .string(session.stableDiaryDateKey),
                "exercise_count": .int(session.exerciseCount), "sets": .int(session.performedSetCount),
                "volume_kg": .number(Self.sessionVolume(session, fallback: storedWeightUnit)), "duration_s": .int(session.durationSeconds),
            ]
        }
        return ActionResult(actionID: v.actionID, fields: ["count": .int(items.count)], items: items,
                            dialog: String(localized: "\(items.count) workouts \(Self.rangeText(v.string("range")))."))
    }

    /// Catalogue or user exercise by id, else by (case-insensitive) name.
    func exerciseItem(_ idOrName: String, store: StrengthWorkoutStore) -> ExerciseLibraryItem? {
        let library = store.exerciseLibrary.exercises
        if let byID = library.first(where: { $0.id == idOrName }) { return byID }
        let wanted = StrengthExerciseLiftHistory.normalizedName(idOrName.replacingOccurrences(of: "_", with: " "))
        return library.first { StrengthExerciseLiftHistory.normalizedName($0.name) == wanted }
    }

    func exerciseStats(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let store = env.workoutStore()
        let requested = v.string("exercise") ?? ""
        let item = exerciseItem(requested, store: store)
        let itemID = item?.id ?? requested
        let name = item?.name ?? requested.replacingOccurrences(of: "_", with: " ")
        var days = Set<String>()
        var sets: [(weightKg: Double, reps: Int)] = []
        var best = 0.0
        for session in store.completedSessions where r.contains(session.calendarDiaryDate) {
            for exercise in session.exercises where StrengthExerciseLiftHistory.matches(itemID: itemID, name: name, candidateItemID: exercise.itemID, candidateName: exercise.name) {
                for set in exercise.sets where set.isPerformed {
                    guard let reps = Int(set.reps) else { continue }
                    let kg = Self.kilograms(set.weight, unit: set.weightUnit, fallback: storedWeightUnit)
                    sets.append((kg, reps))
                    best = max(best, kg)
                    days.insert(session.stableDiaryDateKey)
                }
            }
        }
        let volume = ActionMath.setVolume(sets)
        let dialog = sets.isEmpty
            ? String(localized: "No \(name) sets \(Self.rangeText(v.string("range"))).")
            : String(localized: "\(name) \(Self.rangeText(v.string("range"))): \(days.count) sessions, \(volume.sets) sets, best \(weightText(best)).")
        return ActionResult(actionID: v.actionID, fields: [
            "sessions": .int(days.count), "sets": .int(volume.sets), "reps": .int(volume.reps), "volume_kg": .number(volume.volumeKg),
            "best_weight_kg": .number(ActionMath.roundTo(best, 2)), "value": .number(ActionMath.roundTo(best, 2)), "unit": .string("kg"),
        ], dialog: dialog)
    }

    func workoutStart(_ v: ActionValidation) -> ActionResult {
        ActionResult(actionID: v.actionID, dialog: String(localized: "Opening today's workout."), route: .target("screen:workout_log"))
    }

    func workoutFinish(_ v: ActionValidation) throws -> ActionResult {
        let store = env.workoutStore()
        let exercises = store.exercises(for: env.nowDate)
        guard let estimate = StrengthWorkoutBurnEstimator.estimate(
            exercises: exercises,
            bodyWeightKg: env.weightStore().latestEntry?.weightKg ?? env.profile?.weightKg ?? 70,
            defaultWeightUnit: storedWeightUnit,
            defaultRPEScale: store.preferences.rpeScale
        ), let session = store.upsertCalculatedWorkout(on: env.nowDate, caloriesBurned: estimate.calories, weightUnit: storedWeightUnit, calculatedAt: env.nowDate)
        else {
            throw ActionError.notFound(String(localized: "Log at least one set or timed exercise today first."))
        }
        if env.sideEffects {
            HealthKitManager().updateWorkoutBurn(for: session)
            StrengthWorkoutStore.postExternalChangeNotification()
        }
        var fields = workoutTodayFields(store)
        fields["completed"] = .bool(true)
        fields["calories_burned"] = .int(estimate.calories)
        return ActionResult(actionID: v.actionID, fields: fields,
                            dialog: String(localized: "Workout saved: \(estimate.performedSetCount) sets, about \(estimate.calories) kcal burned."))
    }

    func workoutSetLog(_ v: ActionValidation) throws -> ActionResult {
        let store = env.workoutStore()
        guard !store.isPersistenceBlocked else { throw ActionError.unavailable(StrengthWorkoutStore.persistenceBlockedMessage) }
        let requested = v.string("exercise") ?? ""
        guard let item = exerciseItem(requested, store: store) else {
            throw ActionError.notFound(String(localized: "Ayuvo couldn't find the exercise “\(requested)”."))
        }
        let day = env.nowDate
        if !store.containsExercise(item.id, on: day) { store.toggleExercise(item, on: day) }
        guard var exercise = store.exercises(for: day).first(where: { $0.itemID == item.id }) else {
            throw ActionError.unavailable(String(localized: "Ayuvo couldn't add the exercise to today's workout."))
        }
        var index = exercise.sets.firstIndex { $0.reps.trimmingCharacters(in: .whitespaces).isEmpty }
        if index == nil {
            // Every set is logged: append one (the store copies the previous set's values into it).
            let count = exercise.sets.count
            store.setSetCount(count + 1, exerciseID: exercise.id, on: day)
            exercise = store.exercises(for: day).first(where: { $0.id == exercise.id }) ?? exercise
            if exercise.sets.count > count { index = exercise.sets.count - 1 }
        }
        guard let index else {
            throw ActionError.unavailable(String(localized: "This exercise already has the most sets Ayuvo allows today."))
        }
        let unit: WeightUnit = v.string("unit") == "kg" ? .kg : .lbs
        let weight = v.double("weight") ?? 0
        let reps = v.int("reps") ?? 0
        store.updateSet(
            exerciseID: exercise.id, setID: exercise.sets[index].id, on: day,
            weight: weight > 0 ? Self.plain(weight) : "",
            weightUnit: unit, reps: String(reps), rpe: v.double("rpe").map { Self.plain($0) }
        )
        if env.sideEffects { StrengthWorkoutStore.postExternalChangeNotification() }
        let updated = store.exercises(for: day).first(where: { $0.id == exercise.id }) ?? exercise
        let volume = todayVolume([updated])
        let kg = ActionMath.toCanonical(weight, family: massFamily, unit: v.string("unit"))
        let load = weight > 0 ? String(localized: " at \(weightText(kg))") : ""
        return ActionResult(actionID: v.actionID, fields: [
            "exercise": .string(item.name), "set_number": .int(index + 1), "reps": .int(reps),
            "weight_kg": .number(ActionMath.roundTo(kg, 2)), "sets_today": .int(volume.sets), "volume_kg": .number(volume.volumeKg),
            "value": .number(volume.volumeKg), "unit": .string("kg"),
        ], dialog: String(localized: "Logged set \(index + 1) of \(item.name): \(reps) reps\(load)."))
    }
}
