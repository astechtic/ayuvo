import Foundation

/// Water, weight, body fat, tape measurements, body composition, profile and goals.
extension ActionExecutor {
    // MARK: - Water

    private func waterFields(_ status: ActionMath.WaterStatus) -> [String: ActionField] {
        [
            "value": .int(status.intakeMl), "unit": .string("ml"), "intake_ml": .int(status.intakeMl),
            "goal_ml": .optional(status.goalMl), "remaining_ml": .optional(status.remainingMl), "percent": .optional(status.percent),
        ]
    }

    func waterGet(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let intake = env.waterStore().entries.filter { r.contains($0.date) }.reduce(0) { $0 + $1.milliliters }
        let status = ActionMath.waterStatus(intakeMl: intake, goalMl: r.isSingleDay ? env.waterGoalMl : nil)
        var dialog = String(localized: "Water \(Self.rangeText(v.string("range"))): \(waterText(intake)).")
        if let remaining = status.remainingMl, let goal = status.goalMl {
            dialog += " " + (remaining > 0
                ? String(localized: "\(waterText(remaining)) to go of \(waterText(goal)).")
                : String(localized: "Goal of \(waterText(goal)) reached."))
        }
        return ActionResult(actionID: v.actionID, fields: waterFields(status), dialog: dialog)
    }

    func waterLog(_ v: ActionValidation) throws -> ActionResult {
        guard env.defaults.bool(forKey: WaterSettings.enabledKey) else {
            throw ActionError.unavailable(String(localized: "Water tracking is off. Turn it on in Ayuvo Settings → Hydration."))
        }
        let ml = Int(ActionMath.toCanonical(v.double("amount") ?? 0, family: volumeFamily, unit: v.string("unit")).rounded())
        let store = env.waterStore()
        guard store.add(milliliters: ml, on: env.nowDate) != nil else {
            throw ActionError.unavailable(String(localized: "Your water log can't be changed right now. Open Ayuvo and try again."))
        }
        if env.sideEffects {
            WaterStore.postExternalChangeNotification()
            publishWidgets()
        }
        let today = ActionMath.resolveDateRange("today", nowMs: env.nowMs, zone: env.zone, weekStart: env.weekStart)
        let intake = store.entries.filter { today?.contains($0.date) ?? false }.reduce(0) { $0 + $1.milliliters }
        let status = ActionMath.waterStatus(intakeMl: intake, goalMl: env.waterGoalMl)
        var fields = waterFields(status)
        fields["added_ml"] = .int(ml)
        let remaining = status.remainingMl ?? 0
        let dialog = remaining > 0
            ? String(localized: "Logged \(waterText(ml)) of water. \(waterText(remaining)) to go today.")
            : String(localized: "Logged \(waterText(ml)) of water. Today's goal is reached.")
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    // MARK: - Weight

    func weightGet(_ v: ActionValidation) throws -> ActionResult {
        guard let latest = env.weightStore().latestEntry else {
            throw ActionError.notFound(String(localized: "You haven't logged a weight yet."))
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .number(ActionMath.roundTo(latest.weightKg, 2)), "unit": .string("kg"),
            "t_ms": .int(Int(ActionEnvironment.ms(latest.date))),
        ], dialog: String(localized: "Your latest weight is \(weightText(latest.weightKg)), \(latest.date.formatted(date: .abbreviated, time: .omitted))."))
    }

    func weightHistory(_ v: ActionValidation) throws -> ActionResult {
        let r = try range(v)
        let entries = env.weightStore().entries.filter { r.contains($0.date) }
        let change = ActionMath.weightChange(entries.map { (tMs: ActionEnvironment.ms($0.date), kg: $0.weightKg) })
        let items: [[String: ActionField]] = entries.sorted { $0.date < $1.date }.map {
            ["t_ms": .int(Int(ActionEnvironment.ms($0.date))), "value": .number(ActionMath.roundTo($0.weightKg, 2)), "unit": .string("kg")]
        }
        let dialog: String
        if let first = change.firstKg, let last = change.lastKg, let delta = change.changeKg, change.count > 1 {
            let direction = delta < 0 ? String(localized: "down") : (delta > 0 ? String(localized: "up") : String(localized: "unchanged"))
            dialog = String(localized: "\(change.count) weigh-ins \(Self.rangeText(v.string("range"))): \(weightText(first)) → \(weightText(last)), \(direction) \(weightText(abs(delta))).")
        } else if let last = change.lastKg {
            dialog = String(localized: "One weigh-in \(Self.rangeText(v.string("range"))): \(weightText(last)).")
        } else {
            dialog = String(localized: "No weigh-ins \(Self.rangeText(v.string("range"))).")
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .optional(change.changeKg), "unit": .string("kg"), "first_kg": .optional(change.firstKg),
            "last_kg": .optional(change.lastKg), "change_kg": .optional(change.changeKg), "count": .int(change.count),
        ], items: items, dialog: dialog)
    }

    func weightLog(_ v: ActionValidation) throws -> ActionResult {
        let kg = ActionMath.toCanonical(v.double("value") ?? 0, family: massFamily, unit: v.string("unit"))
        let store = env.weightStore()
        guard !store.isPersistenceBlocked else {
            throw ActionError.unavailable(String(localized: "Your weight history can't be changed right now. Open Ayuvo and try again."))
        }
        let entry = WeightEntry(date: env.nowDate, weightKg: kg)
        store.addEntry(entry)
        if env.sideEffects {
            HealthKitManager().writeWeight(for: entry)
            if let profile = env.profile { rescheduleNotifications(foodStore: env.foodStore(), weightStore: store, profile: profile) }
            WeightStore.postExternalChangeNotification()
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .number(ActionMath.roundTo(kg, 2)), "unit": .string("kg"), "t_ms": .int(Int(ActionEnvironment.ms(entry.date))),
        ], dialog: String(localized: "Logged \(weightText(kg))."))
    }

    // MARK: - Body fat, measurements, composition

    func bodyFatLog(_ v: ActionValidation) throws -> ActionResult {
        let percent = v.double("percent") ?? 0
        let entry = BodyFatEntry(date: env.nowDate, bodyFatFraction: percent / 100)
        let store = env.bodyFatStore()
        store.addEntry(entry)
        if env.sideEffects {
            HealthKitManager().writeBodyFat(for: entry)
            BodyFatStore.postExternalChangeNotification()
        }
        return ActionResult(actionID: v.actionID, fields: [
            "value": .number(percent), "unit": .string("%"), "t_ms": .int(Int(ActionEnvironment.ms(entry.date))),
        ], dialog: String(localized: "Logged body fat \(Self.number(percent, digits: 1))%."))
    }

    func measurementLog(_ v: ActionValidation) throws -> ActionResult {
        let siteRaw = v.string("site") ?? ""
        let site: BodyMeasurement.Site? = siteRaw == "upper_arm" ? .upperArm : BodyMeasurement.Site(rawValue: siteRaw)
        guard let site else { throw ActionError.invalid(code: "bad_enum", param: "site") }
        let cm = ActionMath.toCanonical(v.double("value") ?? 0, family: lengthFamily, unit: v.string("unit"))
        let store = env.measurementStore()
        store.setValue(site, cm: ActionMath.roundTo(cm, 1))
        if env.sideEffects { BodyMeasurementStore.postExternalChangeNotification() }
        let shown = env.lengthUnit == "in"
            ? "\(Self.number(ActionMath.convert(cm, family: lengthFamily, from: "cm", to: "in"), digits: 1)) in"
            : "\(Self.number(cm, digits: 1)) cm"
        return ActionResult(actionID: v.actionID, fields: [
            "value": .number(ActionMath.roundTo(cm, 1)), "unit": .string("cm"), "t_ms": .int(Int(env.nowMs)),
        ], dialog: String(localized: "Logged \(site.label.lowercased()) \(shown)."))
    }

    func bodyComposition(_ v: ActionValidation) -> ActionResult {
        let profile = env.profile
        let weight = env.weightStore().latestEntry?.weightKg ?? profile?.weightKg
        let bodyFat = env.bodyFatStore().latestEntry.map { $0.bodyFatPercent } ?? profile?.bodyFatPercentage.map { $0 * 100 }
        let height = profile?.heightCm
        let bmi = ActionMath.bmi(weightKg: weight, heightCm: height)
        var parts: [String] = []
        if let weight { parts.append(String(localized: "weight \(weightText(weight))")) }
        if let bodyFat { parts.append(String(localized: "body fat \(Self.number(bodyFat, digits: 1))%")) }
        if let bmi { parts.append(String(localized: "BMI \(Self.number(bmi, digits: 1))")) }
        let dialog = parts.isEmpty ? String(localized: "Log your weight in Ayuvo first.") : parts.joined(separator: ", ").capitalizedFirst + "."
        return ActionResult(actionID: v.actionID, fields: [
            "value": .optional(bmi), "unit": .string("kg/m²"),
            "weight_kg": .optional(weight.map { ActionMath.roundTo($0, 2) }), "body_fat_percent": .optional(bodyFat.map { ActionMath.roundTo($0, 1) }),
            "height_cm": .optional(height.map { ActionMath.roundTo($0, 1) }), "bmi": .optional(bmi),
        ], dialog: dialog)
    }

    // MARK: - Profile and goals

    func profileSummary(_ v: ActionValidation) throws -> ActionResult {
        guard let profile = env.profile else { throw ActionError.notFound(String(localized: "Set up your profile in Ayuvo first.")) }
        return ActionResult(actionID: v.actionID, fields: [
            "age": .int(profile.age), "height_cm": .number(ActionMath.roundTo(profile.heightCm, 1)),
            "weight_kg": .number(ActionMath.roundTo(profile.weightKg, 2)), "mass_unit": .string(env.massUnit),
            "volume_unit": .string(env.volumeUnit),
        ], dialog: String(localized: "Age \(profile.age), height \(Self.number(profile.heightCm)) cm, weight \(weightText(profile.weightKg))."))
    }

    func goalsGet(_ v: ActionValidation) -> ActionResult {
        let profile = env.profile
        let calories = Self.nutrientTarget("calories", profile: profile)
        let protein = Self.nutrientTarget("protein", profile: profile)
        let water = env.waterGoalMl
        let steps = ActivitySettings.dailyStepGoal(defaults: env.defaults)
        var progress: [String: ActionField] = [:]
        if let today = ActionMath.resolveDateRange("today", nowMs: env.nowMs, zone: env.zone, weekStart: env.weekStart) {
            let foods = env.foodStore().entries.filter { today.contains($0.timestamp) }
            let eaten = Double(foods.reduce(0) { $0 + $1.calories })
            let proteinEaten = foods.reduce(0) { $0 + $1.protein }
            let drunk = env.waterStore().entries.filter { today.contains($0.date) }.reduce(0) { $0 + $1.milliliters }
            progress["calories_percent"] = .optional(ActionMath.progress(value: eaten, target: calories).percent)
            progress["protein_percent"] = .optional(ActionMath.progress(value: proteinEaten, target: protein).percent)
            progress["water_percent"] = .optional(ActionMath.waterStatus(intakeMl: drunk, goalMl: water).percent)
        }
        let dialog = String(localized: "Daily goals: \(Self.number(calories ?? 0)) kcal, \(Self.number(protein ?? 0)) g protein, \(waterText(water)) water, \(steps.formatted()) steps.")
        return ActionResult(actionID: v.actionID, fields: [
            "calories": .optional(calories), "protein_g": .optional(protein),
            "carbs_g": .optional(Self.nutrientTarget("carbs", profile: profile)), "fat_g": .optional(Self.nutrientTarget("fat", profile: profile)),
            "water_ml": .int(water), "steps": .int(steps), "goal_weight_kg": .optional(profile?.goalWeightKg),
            "progress": .object(progress),
        ], dialog: dialog)
    }

    func goalsUpdate(_ v: ActionValidation) throws -> ActionResult {
        let goal = v.string("goal") ?? ""
        let value = Int((v.double("value") ?? 0).rounded())
        var previous: Int?
        var applied = value
        switch goal {
        case "water":
            previous = env.waterGoalMl
            env.defaults.set(value, forKey: WaterSettings.dailyGoalKey)
        case "steps":
            previous = ActivitySettings.dailyStepGoal(defaults: env.defaults)
            applied = min(max(value, ActivitySettings.stepGoalRange.lowerBound), ActivitySettings.stepGoalRange.upperBound)
            env.defaults.set(applied, forKey: ActivitySettings.dailyStepGoalKey)
        default:
            guard var profile = env.profile else { throw ActionError.notFound(String(localized: "Set up your profile in Ayuvo first.")) }
            switch goal {
            case "calories": previous = profile.effectiveCalories; profile.customCalories = value
            case "protein": previous = profile.effectiveProtein; profile.customProtein = value
            case "carbs": previous = profile.effectiveCarbs; profile.customCarbs = value
            case "fat": previous = profile.effectiveFat; profile.customFat = value
            default: throw ActionError.invalid(code: "bad_enum", param: "goal")
            }
            profile.save()
        }
        if env.sideEffects { publishWidgets() }
        return ActionResult(actionID: v.actionID, fields: [
            "goal": .string(goal), "value": .int(applied), "previous": .optional(previous),
        ], dialog: String(localized: "Your daily \(goal) goal is now \(applied.formatted())."))
    }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
