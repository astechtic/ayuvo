import Foundation

/// Runs catalog actions (docs/actions.md) for every surface — Siri / Shortcuts intents, deep links and
/// Coach — against the existing stores. Surfaces only translate their input into raw parameters and
/// render the `ActionResult`; nothing here knows which surface asked.
@MainActor
final class ActionExecutor {
    static let shared = ActionExecutor()

    let catalog: ActionCatalog
    var environment: ActionEnvironment

    init(catalog: ActionCatalog = .shared, environment: ActionEnvironment? = nil) {
        self.catalog = catalog
        self.environment = environment ?? .live()
    }

    var env: ActionEnvironment { environment }

    // MARK: - Entry points

    func validate(_ id: String, _ params: [String: ActionRawValue], source: ActionSource) -> Result<ActionValidation, ActionError> {
        ActionValidator.validate(catalog: catalog, actionID: id, params: params, source: source, prefs: environment.prefs)
    }

    /// Validates and runs. Throws `.confirmationRequired` when the action needs a confirmation the
    /// caller has not collected.
    func run(_ id: String, _ params: [String: ActionRawValue] = [:], source: ActionSource, confirmed: Bool = false) async throws -> ActionResult {
        let validation = try validate(id, params, source: source).get()
        return try await perform(validation, source: source, confirmed: confirmed)
    }

    func perform(_ v: ActionValidation, source: ActionSource = .app, confirmed: Bool) async throws -> ActionResult {
        if v.confirm, !confirmed { throw ActionError.confirmationRequired }
        guard let action = catalog.action(v.actionID) else { throw ActionError.invalid(code: "unknown_action", param: nil) }
        var result = try await dispatch(v, source: source)
        if result.route == nil, action.kind != .open, !action.opensApp {
            result.route = screenRoute(for: action, params: v.params)
        }
        return result
    }

    private func dispatch(_ v: ActionValidation, source: ActionSource) async throws -> ActionResult {
        switch v.actionID {
        case "health.metric.get": return try await metricGet(v)
        case "health.metric.latest": return try await metricLatest(v)
        case "health.metric.samples": return try await metricSamplesList(v)
        case "health.sleep.lastNight": return try await sleepLastNight(v)
        case "nutrition.summary.get": return try nutritionSummary(v)
        case "nutrition.nutrient.get": return try nutrientGet(v)
        case "nutrition.meals.list": return try mealsList(v)
        case "nutrition.targets.get": return nutritionTargets(v)
        case "nutrition.food.log": return try await foodLog(v)
        case "nutrition.food.logSaved": return try foodLogSaved(v)
        case "water.get": return try waterGet(v)
        case "water.log": return try waterLog(v)
        case "weight.get": return try weightGet(v)
        case "weight.history": return try weightHistory(v)
        case "weight.log": return try weightLog(v)
        case "body.fat.log": return try bodyFatLog(v)
        case "body.measurement.log": return try measurementLog(v)
        case "body.composition.get": return bodyComposition(v)
        case "profile.summary.get": return try profileSummary(v)
        case "goals.get": return goalsGet(v)
        case "goals.update": return try goalsUpdate(v)
        case "fasting.status.get": return fastingStatus(v)
        case "fasting.history.list": return try fastingHistory(v)
        case "fasting.start": return try fastingStart(v)
        case "fasting.stop": return try fastingStop(v)
        case "workout.today.get": return workoutToday(v)
        case "workout.history.list": return try workoutHistory(v)
        case "workout.exercise.stats": return try exerciseStats(v)
        case "workout.start": return workoutStart(v)
        case "workout.finish": return try workoutFinish(v)
        case "workout.set.log": return try workoutSetLog(v)
        case "insights.recovery.get": return try await recoveryGet(v)
        case "insights.healthAge.get": return try await healthAgeGet(v)
        case "insights.dailyReview.get": return try await dailyReviewGet(v)
        case "records.search": return try await recordsSearch(v)
        case "records.latest": return try await recordsLatest(v)
        case "records.labValue.get": return try await labValue(v)
        case "medications.today.list": return try await dosesToday(v)
        case "medications.next.get": return try await doseNext(v)
        case "medications.history.list": return try await doseHistory(v)
        case "medications.adherence.get": return try await adherence(v)
        case "medication.dose.mark": return try await doseMark(v)
        case "search.universal": return try await universalSearch(v, source: source)
        case "open.section", "open.metric", "open.record", "open.coach": return openRoute(v)
        default: throw ActionError.unavailable(String(localized: "This action isn't available on iPhone yet."))
        }
    }

    // MARK: - Shared helpers

    func range(_ v: ActionValidation, _ name: String = "range") throws -> ActionMath.DateRange {
        let preset = v.string(name) ?? "today"
        guard let range = ActionMath.resolveDateRange(preset, nowMs: env.nowMs, zone: env.zone, weekStart: env.weekStart) else {
            throw ActionError.invalid(code: "bad_enum", param: name)
        }
        return range
    }

    var massFamily: ActionCatalog.UnitFamily { catalog.units["mass"] ?? .init(canonical: "kg", enumName: "mass_unit", factors: ["kg": 1, "lb": 0.45359237]) }
    var volumeFamily: ActionCatalog.UnitFamily { catalog.units["volume"] ?? .init(canonical: "ml", enumName: "volume_unit", factors: ["ml": 1]) }
    var lengthFamily: ActionCatalog.UnitFamily { catalog.units["length"] ?? .init(canonical: "cm", enumName: "length_unit", factors: ["cm": 1, "in": 2.54]) }

    static func number(_ value: Double, digits: Int = 0) -> String {
        value.formatted(.number.precision(.fractionLength(0...digits)))
    }

    /// kg → "72.4 kg" / "159.6 lb" in the user's unit.
    func weightText(_ kg: Double) -> String {
        if env.massUnit == "kg" { return "\(Self.number(kg, digits: 1)) kg" }
        return "\(Self.number(ActionMath.convert(kg, family: massFamily, from: "kg", to: "lb"), digits: 1)) lb"
    }

    func waterText(_ ml: Int) -> String {
        env.volumeUnit == "floz" ? WaterUnit.fluidOunces.formatted(milliliters: ml) : WaterUnit.milliliters.formatted(milliliters: ml)
    }

    static func rangeText(_ preset: String?) -> String {
        switch preset ?? "today" {
        case "yesterday": String(localized: "yesterday")
        case "this_week": String(localized: "this week")
        case "last_week": String(localized: "last week")
        case "last_7_days": String(localized: "in the last 7 days")
        case "last_30_days": String(localized: "in the last 30 days")
        case "this_month": String(localized: "this month")
        case "last_month": String(localized: "last month")
        default: String(localized: "today")
        }
    }

    /// Screen from the catalog `screen` template (`metric:{metric}` → `metric:steps`).
    func screenRoute(for action: ActionCatalog.Action, params: [String: ActionValue]) -> ActionRoute? {
        var target = action.screen
        for (name, value) in params {
            if let text = value.string { target = target.replacingOccurrences(of: "{\(name)}", with: text) }
        }
        guard !target.isEmpty, !target.contains("{") else { return nil }
        return .target(target)
    }

    // MARK: - Side effects

    func broadcastFoodChange(_ store: FoodStore, added: FoodEntry?) {
        guard env.sideEffects else { return }
        if let added { HealthKitManager().writeNutrition(for: added) }
        if let profile = env.profile {
            WidgetSnapshotWriter.publish(foods: store.entries, profile: profile)
            rescheduleNotifications(foodStore: store, weightStore: env.weightStore(), profile: profile)
        }
        FoodStore.postExternalChangeNotification()
    }

    func rescheduleNotifications(foodStore: FoodStore, weightStore: WeightStore, profile: UserProfile) {
        guard env.defaults.bool(forKey: "notificationsEnabled") else { return }
        NotificationManager().rescheduleDataDependentNotifications(
            foodStore: foodStore, weightStore: weightStore, bodyFatStore: env.bodyFatStore(), profile: profile
        )
    }

    func publishWidgets() {
        guard env.sideEffects, let profile = env.profile else { return }
        WidgetSnapshotWriter.publish(foods: env.foodStore().entries, profile: profile)
    }

    // MARK: - Summaries (Siri confirmations, deep-link sheet, Coach proposal cards)

    func summary(of v: ActionValidation) -> String {
        let title = catalog.action(v.actionID)?.title ?? v.actionID
        func unitAmount(_ value: String, _ unit: String) -> String {
            guard let amount = v.double(value) else { return "" }
            return "\(Self.number(amount, digits: 2)) \(v.string(unit) ?? "")".trimmingCharacters(in: .whitespaces)
        }
        switch v.actionID {
        case "water.log":
            return String(localized: "Log \(unitAmount("amount", "unit")) of water")
        case "weight.log":
            return String(localized: "Log weight \(unitAmount("value", "unit"))")
        case "body.fat.log":
            return String(localized: "Log body fat \(Self.number(v.double("percent") ?? 0, digits: 1))%")
        case "body.measurement.log":
            let site = (v.string("site") ?? "").replacingOccurrences(of: "_", with: " ")
            return String(localized: "Log \(site) \(unitAmount("value", "unit"))")
        case "fasting.start":
            return String(localized: "Start a \(Self.number(v.double("goal_hours") ?? 16, digits: 1)) hour fast")
        case "fasting.stop":
            return String(localized: "End the fast that is running")
        case "nutrition.food.log":
            if let description = v.string("description"), v.ai {
                return String(localized: "Estimate and log “\(description)” (sent to your AI provider)")
            }
            let name = v.string("name") ?? title
            return String(localized: "Log \(name), \(Self.number(v.double("calories") ?? 0)) kcal")
        case "nutrition.food.logSaved":
            return String(localized: "Log \(Self.number(v.double("servings") ?? 1, digits: 1)) serving of a saved food")
        case "workout.set.log":
            let weight = v.double("weight") ?? 0
            let load = weight > 0 ? " × \(unitAmount("weight", "unit"))" : ""
            return String(localized: "Log a set: \(v.int("reps") ?? 0) reps\(load)")
        case "medication.dose.mark":
            switch v.string("action") {
            case "taken": return String(localized: "Mark this dose as taken")
            case "skipped": return String(localized: "Mark this dose as skipped")
            default: return String(localized: "Snooze this dose for \(v.int("snooze_minutes") ?? 10) minutes")
            }
        case "goals.update":
            let goal = v.string("goal") ?? ""
            return String(localized: "Change your daily \(goal) goal to \(Self.number(v.double("value") ?? 0))")
        default:
            return title
        }
    }
}
