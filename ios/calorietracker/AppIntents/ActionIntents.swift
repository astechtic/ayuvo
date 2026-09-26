import AppIntents
import Foundation

// Siri & Shortcuts actions generated from the shared catalog (docs/actions.md). Each intent only maps
// its parameters to catalog parameters and renders the `ActionResult`; validation, confirmation
// rules and all data access live in `ActionExecutor`.

@MainActor
enum ActionIntentRunner {
    /// Validates as `shortcuts`, asks for confirmation when the catalog / validator requires it, runs,
    /// and hands app-opening routes to `ActionRouteCoordinator`.
    static func run(
        _ id: String,
        _ params: [String: ActionRawValue?],
        confirm: (String) async throws -> Void
    ) async throws -> ActionResult {
        let executor = ActionExecutor.shared
        let clean = params.compactMapValues { $0 }
        let validation = try executor.validate(id, clean, source: .shortcuts).get()
        if validation.confirm { try await confirm(executor.summary(of: validation)) }
        let result = try await executor.perform(validation, source: .shortcuts, confirmed: true)
        if let action = executor.catalog.action(id), action.kind == .open || action.opensApp, let route = result.route {
            ActionRouteCoordinator.request(route)
        }
        return result
    }
}

extension AppIntent {
    @MainActor
    func runAction(_ id: String, _ params: [String: ActionRawValue?] = [:]) async throws -> ActionResult {
        try await ActionIntentRunner.run(id, params) { summary in
            try await requestConfirmation(result: .result(dialog: IntentDialog(stringLiteral: summary)))
        }
    }
}

private func raw(_ value: String?) -> ActionRawValue? { value.map { .string($0) } }
private func raw(_ value: Double?) -> ActionRawValue? { value.map { .number($0) } }
private func raw(_ value: Int?) -> ActionRawValue? { value.map { .number(Double($0)) } }

private extension ActionResult {
    var intentDialog: IntentDialog { IntentDialog(stringLiteral: dialog) }
}

// MARK: - Health data

struct GetHealthDataIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Health Data"
    static let description = IntentDescription("A single value for any metric over a date range: total steps this week, average heart rate, latest weight.", categoryName: "Health")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Metric") var metric: HealthMetricEntity
    @Parameter(title: "Range", default: .today) var range: ActionDateRangeOption
    @Parameter(title: "Calculation") var aggregation: ActionAggregationOption?

    static var parameterSummary: some ParameterSummary {
        Summary("Get \(\.$metric) for \(\.$range)") { \.$aggregation }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("health.metric.get", ["metric": raw(metric.id), "range": raw(range.rawValue), "aggregation": raw(aggregation?.rawValue)])
        guard let value = result.value else { throw ActionError.notFound(result.dialog) }
        return .result(value: value, dialog: result.intentDialog)
    }
}

struct GetLatestHealthValueIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Latest Health Value"
    static let description = IntentDescription("The most recent reading of a metric.", categoryName: "Health")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Metric") var metric: HealthMetricEntity

    static var parameterSummary: some ParameterSummary { Summary("Get latest \(\.$metric)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("health.metric.latest", ["metric": raw(metric.id)])
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

struct GetHealthSamplesIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Health Samples"
    static let description = IntentDescription("The values of a metric in a date range, newest first — ready for Calculate Statistics.", categoryName: "Health")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Metric") var metric: HealthMetricEntity
    @Parameter(title: "Range", default: .last7Days) var range: ActionDateRangeOption
    @Parameter(title: "Limit", default: 100, inclusiveRange: (1, 500)) var limit: Int

    static var parameterSummary: some ParameterSummary { Summary("Get \(\.$metric) samples for \(\.$range)") { \.$limit } }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[Double]> & ProvidesDialog {
        let result = try await runAction("health.metric.samples", ["metric": raw(metric.id), "range": raw(range.rawValue), "limit": raw(limit)])
        return .result(value: (result.items ?? []).compactMap { $0["value"]?.double }, dialog: result.intentDialog)
    }
}

struct GetSleepIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Last Night's Sleep"
    static let description = IntentDescription("Hours asleep last night, from Apple Health.", categoryName: "Health")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("health.sleep.lastNight")
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

// MARK: - Nutrition

struct GetNutritionIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Nutrition"
    static let description = IntentDescription("Everything in Nutrition Details: calories and macros with targets, water, and every detailed nutrient (fiber, sugar, sodium, vitamins, minerals and more).", categoryName: "Nutrition")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .today) var range: ActionDateRangeOption

    static var parameterSummary: some ParameterSummary { Summary("Get nutrition for \(\.$range)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<NutritionSummaryEntity> & ProvidesDialog {
        let result = try await runAction("nutrition.summary.get", ["range": raw(range.rawValue)])
        return .result(value: NutritionSummaryEntity(range: range.rawValue, fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetNutrientIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Nutrient"
    static let description = IntentDescription("One nutrient over a date range, e.g. today's protein.", categoryName: "Nutrition")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Nutrient", requestValueDialog: "Which nutrient?") var nutrient: ActionNutrientOption
    @Parameter(title: "Range", default: .today) var range: ActionDateRangeOption
    @Parameter(title: "Calculation", default: .sum) var aggregation: ActionAggregationOption

    static var parameterSummary: some ParameterSummary { Summary("Get \(\.$nutrient) for \(\.$range)") { \.$aggregation } }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("nutrition.nutrient.get", ["nutrient": raw(nutrient.rawValue), "range": raw(range.rawValue), "aggregation": raw(aggregation.rawValue)])
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

struct GetLoggedFoodsIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Logged Foods"
    static let description = IntentDescription("Foods you logged, optionally for one meal.", categoryName: "Nutrition")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .today) var range: ActionDateRangeOption
    @Parameter(title: "Meal") var meal: ActionMealOption?

    static var parameterSummary: some ParameterSummary { Summary("Get foods logged \(\.$range)") { \.$meal } }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[FoodEntryEntity]> & ProvidesDialog {
        let result = try await runAction("nutrition.meals.list", ["range": raw(range.rawValue), "meal": raw(meal?.rawValue)])
        let entries = ActionEntitySupport.executor.env.foodStore().entries
        let ids = (result.items ?? []).compactMap { $0["id"]?.string }
        let entities = ids.compactMap { id in entries.first { $0.id.uuidString == id }.map(FoodEntryEntity.init) }
        return .result(value: entities, dialog: result.intentDialog)
    }
}

struct GetGoalsIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Goals"
    static let description = IntentDescription("Your daily calorie, macro, water and step goals.", categoryName: "Goals")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<GoalsEntity> & ProvidesDialog {
        let result = try await runAction("goals.get")
        return .result(value: GoalsEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetNutritionTargetsIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Nutrition Targets"
    static let description = IntentDescription("Your daily calorie and macro targets.", categoryName: "Nutrition")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<GoalsEntity> & ProvidesDialog {
        let targets = try await runAction("nutrition.targets.get")
        let goals = await ActionEntitySupport.run("goals.get")
        return .result(value: GoalsEntity(fields: goals?.fields ?? targets.fields), dialog: targets.intentDialog)
    }
}

// MARK: - Water, weight, body

struct GetWaterIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Water"
    static let description = IntentDescription("Water logged, your goal and how much is left.", categoryName: "Hydration")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .today) var range: ActionDateRangeOption

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<WaterStatusEntity> & ProvidesDialog {
        let result = try await runAction("water.get", ["range": raw(range.rawValue)])
        return .result(value: WaterStatusEntity(range: range.rawValue, fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetWeightIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Weight"
    static let description = IntentDescription("Your latest logged weight.", categoryName: "Body")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Measurement<UnitMass>> & ProvidesDialog {
        let result = try await runAction("weight.get")
        return .result(value: Measurement(value: result.value ?? 0, unit: .kilograms), dialog: result.intentDialog)
    }
}

struct GetWeightHistoryIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Weight History"
    static let description = IntentDescription("Weights in a date range (kg, oldest first) and the change.", categoryName: "Body")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .last30Days) var range: ActionDateRangeOption

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[Double]> & ProvidesDialog {
        let result = try await runAction("weight.history", ["range": raw(range.rawValue)])
        return .result(value: (result.items ?? []).compactMap { $0["value"]?.double }, dialog: result.intentDialog)
    }
}

struct GetBodyCompositionIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Body Composition"
    static let description = IntentDescription("Latest weight, body fat, height and BMI.", categoryName: "Body")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<BodyCompositionEntity> & ProvidesDialog {
        let result = try await runAction("body.composition.get")
        return .result(value: BodyCompositionEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetProfileIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Profile"
    static let description = IntentDescription("Age, height, weight and preferred units. No name or contact details.", categoryName: "Goals")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<ProfileEntity> & ProvidesDialog {
        let result = try await runAction("profile.summary.get")
        return .result(value: ProfileEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

// MARK: - Fasting & workouts

struct GetFastingStatusIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Fasting Status"
    static let description = IntentDescription("Whether a fast is running, hours fasted and time left.", categoryName: "Fasting")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<FastingStatusEntity> & ProvidesDialog {
        let result = try await runAction("fasting.status.get")
        return .result(value: FastingStatusEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetFastingHistoryIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Fasting History"
    static let description = IntentDescription("Completed fasts in a date range.", categoryName: "Fasting")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .last30Days) var range: ActionDateRangeOption

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[FastingSessionEntity]> & ProvidesDialog {
        let result = try await runAction("fasting.history.list", ["range": raw(range.rawValue)])
        return .result(value: (result.items ?? []).map(FastingSessionEntity.init), dialog: result.intentDialog)
    }
}

struct GetTodaysWorkoutIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Today's Workout"
    static let description = IntentDescription("Today's total training volume in kg, with sets and exercises in the result.", categoryName: "Workouts")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("workout.today.get")
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

struct GetWorkoutHistoryIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Workout History"
    static let description = IntentDescription("Completed workouts in a date range.", categoryName: "Workouts")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .last30Days) var range: ActionDateRangeOption

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[WorkoutEntity]> & ProvidesDialog {
        let result = try await runAction("workout.history.list", ["range": raw(range.rawValue)])
        let ids = (result.items ?? []).compactMap { $0["id"]?.string }
        let entities = try await WorkoutQuery().entities(for: ids)
        return .result(value: entities, dialog: result.intentDialog)
    }
}

struct GetExerciseStatsIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Exercise Stats"
    static let description = IntentDescription("Best weight (kg) for one exercise, with sessions, sets and volume in the dialog.", categoryName: "Workouts")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Exercise") var exercise: ExerciseEntity
    @Parameter(title: "Range", default: .last30Days) var range: ActionDateRangeOption

    static var parameterSummary: some ParameterSummary { Summary("Get \(\.$exercise) stats for \(\.$range)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("workout.exercise.stats", ["exercise": raw(exercise.id), "range": raw(range.rawValue)])
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

// MARK: - Insights

struct GetRecoveryIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Recovery"
    static let description = IntentDescription("This morning's Recovery score (0–100) compared with your own baseline. A wellness estimate, not a diagnosis.", categoryName: "Insights")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Int> & ProvidesDialog {
        let result = try await runAction("insights.recovery.get")
        guard let score = result.value else { throw ActionError.notFound(result.dialog) }
        return .result(value: Int(score), dialog: result.intentDialog)
    }
}

struct GetHealthAgeIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Health Age"
    static let description = IntentDescription("Your Ayuvo Health Age next to your actual age. Ayuvo's own estimate, not a clinical age.", categoryName: "Insights")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("insights.healthAge.get")
        guard let age = result.value else { throw ActionError.notFound(result.dialog) }
        return .result(value: age, dialog: result.intentDialog)
    }
}

struct GetDailyReviewIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Daily Review"
    static let description = IntentDescription("The Day Score for today or yesterday, with what went well and what needs attention.", categoryName: "Insights")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Day", default: .today) var day: ActionReviewDayOption

    static var parameterSummary: some ParameterSummary { Summary("Get the Daily Review for \(\.$day)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Int> & ProvidesDialog {
        let result = try await runAction("insights.dailyReview.get", ["day": raw(day.rawValue)])
        guard let score = result.value else { throw ActionError.notFound(result.dialog) }
        return .result(value: Int(score), dialog: result.intentDialog)
    }
}

// MARK: - Records & medications

struct SearchHealthRecordsIntent: AppIntent {
    static let title: LocalizedStringResource = "Search Health Records"
    static let description = IntentDescription("Find documents in Health Records by text.", categoryName: "Health Records")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Search", requestValueDialog: "What should I look for?") var query: String

    static var parameterSummary: some ParameterSummary { Summary("Search health records for \(\.$query)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[HealthRecordEntity]> & ProvidesDialog {
        let result = try await runAction("records.search", ["query": raw(query)])
        return .result(value: (result.items ?? []).map(HealthRecordEntity.init), dialog: result.intentDialog)
    }
}

struct GetLatestHealthRecordIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Latest Health Record"
    static let description = IntentDescription("Your most recent document in Health Records.", categoryName: "Health Records")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<HealthRecordEntity> & ProvidesDialog {
        let result = try await runAction("records.latest")
        return .result(value: HealthRecordEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetLabValueIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Lab Value"
    static let description = IntentDescription("The latest result for a lab test in your Health Records.", categoryName: "Health Records")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Lab Test") var test: LabTestEntity

    static var parameterSummary: some ParameterSummary { Summary("Get latest \(\.$test)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<LabResultEntity> & ProvidesDialog {
        let result = try await runAction("records.labValue.get", ["analyte": raw(test.id)])
        return .result(value: LabResultEntity(analyte: test.id, name: test.name, fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetTodaysDosesIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Today's Doses"
    static let description = IntentDescription("Today's scheduled doses and their status.", categoryName: "Medications")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[DoseEntity]> & ProvidesDialog {
        let result = try await runAction("medications.today.list")
        return .result(value: (result.items ?? []).map(DoseEntity.init), dialog: result.intentDialog)
    }
}

struct GetNextDoseIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Next Dose"
    static let description = IntentDescription("The next dose that is due or upcoming today.", categoryName: "Medications")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<DoseEntity> & ProvidesDialog {
        let result = try await runAction("medications.next.get")
        return .result(value: DoseEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct GetDoseHistoryIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Dose History"
    static let description = IntentDescription("How many doses were logged in a date range (missed count in the dialog).", categoryName: "Medications")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .last7Days) var range: ActionDateRangeOption
    @Parameter(title: "Medication") var medication: MedicationEntity?

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Int> & ProvidesDialog {
        let result = try await runAction("medications.history.list", ["range": raw(range.rawValue), "medication": raw(medication?.id)])
        return .result(value: result.items?.count ?? 0, dialog: result.intentDialog)
    }
}

struct GetAdherenceIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Medication Adherence"
    static let description = IntentDescription("Percent of scheduled doses taken in a date range.", categoryName: "Medications")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Range", default: .last7Days) var range: ActionDateRangeOption
    @Parameter(title: "Medication") var medication: MedicationEntity?

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("medications.adherence.get", ["range": raw(range.rawValue), "medication": raw(medication?.id)])
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

struct SearchAyuvoIntent: AppIntent {
    static let title: LocalizedStringResource = "Search Ayuvo"
    static let description = IntentDescription("Search foods, exercises, metrics, health records and medications.", categoryName: "Search")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Search", requestValueDialog: "What should I search for?") var query: String
    @Parameter(title: "Search In", default: .all) var domain: ActionSearchDomainOption

    static var parameterSummary: some ParameterSummary { Summary("Search Ayuvo for \(\.$query)") { \.$domain } }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[SearchResultEntity]> & ProvidesDialog {
        let result = try await runAction("search.universal", ["query": raw(query), "domain": raw(domain.rawValue)])
        return .result(value: (result.items ?? []).map(SearchResultEntity.init), dialog: result.intentDialog)
    }
}

// MARK: - Logging

struct LogFoodDetailsIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Food with Nutrition"
    static let description = IntentDescription("Log a food with exact calories and macros (nothing is sent to an AI provider).", categoryName: "Nutrition")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Name") var name: String
    @Parameter(title: "Calories", inclusiveRange: (0, 10000)) var calories: Double
    @Parameter(title: "Protein (g)") var protein: Double?
    @Parameter(title: "Carbs (g)") var carbs: Double?
    @Parameter(title: "Fat (g)") var fat: Double?
    @Parameter(title: "Meal") var meal: ActionMealOption?

    static var parameterSummary: some ParameterSummary {
        Summary("Log \(\.$name) with \(\.$calories) kcal") { \.$protein; \.$carbs; \.$fat; \.$meal }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<FoodEntryEntity> & ProvidesDialog {
        let result = try await runAction("nutrition.food.log", [
            "name": raw(name), "calories": raw(calories), "protein": raw(protein), "carbs": raw(carbs), "fat": raw(fat), "meal": raw(meal?.rawValue),
        ])
        let entry = ActionEntitySupport.executor.env.foodStore().entries.first { $0.id.uuidString == result.string("id") }
        guard let entry else { throw ActionError.notFound(result.dialog) }
        return .result(value: FoodEntryEntity(entry), dialog: result.intentDialog)
    }
}

struct LogSavedFoodIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Saved Food"
    static let description = IntentDescription("Log a favourite or recent food again.", categoryName: "Nutrition")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Food") var food: FoodEntity
    @Parameter(title: "Servings", default: 1, inclusiveRange: (0.1, 20)) var servings: Double
    @Parameter(title: "Meal") var meal: ActionMealOption?

    static var parameterSummary: some ParameterSummary { Summary("Log \(\.$servings) × \(\.$food)") { \.$meal } }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<FoodEntryEntity> & ProvidesDialog {
        let result = try await runAction("nutrition.food.logSaved", ["food": raw(food.id), "servings": raw(servings), "meal": raw(meal?.rawValue)])
        let entry = ActionEntitySupport.executor.env.foodStore().entries.first { $0.id.uuidString == result.string("id") }
        guard let entry else { throw ActionError.notFound(result.dialog) }
        return .result(value: FoodEntryEntity(entry), dialog: result.intentDialog)
    }
}

struct LogWaterIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Water"
    static let description = IntentDescription("Add water to today's intake.", categoryName: "Hydration")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Amount", requestValueDialog: "How much water?") var amount: Double
    @Parameter(title: "Unit") var unit: ActionVolumeUnitOption?

    static var parameterSummary: some ParameterSummary { Summary("Log \(\.$amount) \(\.$unit) of water") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<WaterStatusEntity> & ProvidesDialog {
        let result = try await runAction("water.log", ["amount": raw(amount), "unit": raw(unit?.rawValue)])
        return .result(value: WaterStatusEntity(range: "today", fields: result.fields), dialog: result.intentDialog)
    }
}

struct LogWeightValueIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Weight Value"
    static let description = IntentDescription("Log a weight number in kilograms or pounds.", categoryName: "Body")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Weight", requestValueDialog: "What is your weight?") var value: Double
    @Parameter(title: "Unit") var unit: ActionMassUnitOption?

    static var parameterSummary: some ParameterSummary { Summary("Log weight \(\.$value) \(\.$unit)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Measurement<UnitMass>> & ProvidesDialog {
        let result = try await runAction("weight.log", ["value": raw(value), "unit": raw(unit?.rawValue)])
        return .result(value: Measurement(value: result.value ?? 0, unit: .kilograms), dialog: result.intentDialog)
    }
}

struct LogBodyFatIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Body Fat"
    static let description = IntentDescription("Log your body fat percentage.", categoryName: "Body")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Body Fat (%)", inclusiveRange: (2, 75)) var percent: Double

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("body.fat.log", ["percent": raw(percent)])
        return .result(value: result.value ?? percent, dialog: result.intentDialog)
    }
}

struct LogBodyMeasurementIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Body Measurement"
    static let description = IntentDescription("Log a tape measurement such as waist or hips.", categoryName: "Body")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Measurement") var site: ActionBodySiteOption
    @Parameter(title: "Value") var value: Double
    @Parameter(title: "Unit") var unit: ActionLengthUnitOption?

    static var parameterSummary: some ParameterSummary { Summary("Log \(\.$site) \(\.$value) \(\.$unit)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("body.measurement.log", ["site": raw(site.rawValue), "value": raw(value), "unit": raw(unit?.rawValue)])
        return .result(value: result.value ?? value, dialog: result.intentDialog)
    }
}

struct StartFastIntent: AppIntent {
    static let title: LocalizedStringResource = "Start Fast"
    static let description = IntentDescription("Start a fast with a goal length.", categoryName: "Fasting")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Goal (hours)", default: 16, inclusiveRange: (1, 72)) var hours: Double

    static var parameterSummary: some ParameterSummary { Summary("Start a \(\.$hours) hour fast") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<FastingStatusEntity> & ProvidesDialog {
        let result = try await runAction("fasting.start", ["goal_hours": raw(hours)])
        return .result(value: FastingStatusEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct EndFastIntent: AppIntent {
    static let title: LocalizedStringResource = "End Fast"
    static let description = IntentDescription("End the fast that is running.", categoryName: "Fasting")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<FastingSessionEntity> & ProvidesDialog {
        let result = try await runAction("fasting.stop")
        return .result(value: FastingSessionEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct StartWorkoutIntent: AppIntent {
    static let title: LocalizedStringResource = "Start Workout"
    static let description = IntentDescription("Open today's workout log, ready to add sets.", categoryName: "Workouts")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = try await runAction("workout.start")
        return .result()
    }
}

struct FinishWorkoutIntent: AppIntent {
    static let title: LocalizedStringResource = "Finish Workout"
    static let description = IntentDescription("Save today's workout and calculate calories burned.", categoryName: "Workouts")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("workout.finish")
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

struct LogWorkoutSetIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Workout Set"
    static let description = IntentDescription("Add a set to today's workout: exercise, reps, weight and optional RPE.", categoryName: "Workouts")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Exercise") var exercise: ExerciseEntity
    @Parameter(title: "Reps", inclusiveRange: (1, 200)) var reps: Int
    @Parameter(title: "Weight") var weight: Double?
    @Parameter(title: "Unit") var unit: ActionMassUnitOption?
    @Parameter(title: "RPE", inclusiveRange: (1, 10)) var rpe: Double?

    static var parameterSummary: some ParameterSummary {
        Summary("Log \(\.$reps) reps of \(\.$exercise)") { \.$weight; \.$unit; \.$rpe }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("workout.set.log", [
            "exercise": raw(exercise.id), "reps": raw(reps), "weight": raw(weight), "unit": raw(unit?.rawValue), "rpe": raw(rpe),
        ])
        return .result(value: result.value ?? 0, dialog: result.intentDialog)
    }
}

struct MarkDoseIntent: AppIntent {
    static let title: LocalizedStringResource = "Mark Dose"
    static let description = IntentDescription("Mark one of today's scheduled doses as taken, skipped or snoozed. Never changes the medication or its dose.", categoryName: "Medications")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Dose") var dose: DoseEntity
    @Parameter(title: "Action", default: .taken) var action: ActionDoseActionOption
    @Parameter(title: "Snooze", default: .ten) var snooze: ActionSnoozeOption

    static var parameterSummary: some ParameterSummary {
        When(\.$action, .equalTo, ActionDoseActionOption.snoozed) {
            Summary("Mark \(\.$dose) as \(\.$action)") { \.$snooze }
        } otherwise: {
            Summary("Mark \(\.$dose) as \(\.$action)")
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<DoseEntity> & ProvidesDialog {
        let result = try await runAction("medication.dose.mark", ["dose": raw(dose.id), "action": raw(action.rawValue), "snooze_minutes": raw(snooze.rawValue)])
        return .result(value: DoseEntity(fields: result.fields), dialog: result.intentDialog)
    }
}

struct UpdateGoalIntent: AppIntent {
    static let title: LocalizedStringResource = "Update Goal"
    static let description = IntentDescription("Change a daily goal: calories, protein, carbs, fat, water (ml) or steps.", categoryName: "Goals")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Goal") var goal: ActionGoalOption
    @Parameter(title: "Value") var value: Double

    static var parameterSummary: some ParameterSummary { Summary("Set daily \(\.$goal) goal to \(\.$value)") }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Double> & ProvidesDialog {
        let result = try await runAction("goals.update", ["goal": raw(goal.rawValue), "value": raw(value)])
        return .result(value: result.value ?? value, dialog: result.intentDialog)
    }
}

// MARK: - Open

struct OpenAyuvoSectionIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Ayuvo"
    static let description = IntentDescription("Open a section of Ayuvo.", categoryName: "Navigation")
    static let openAppWhenRun = true

    @Parameter(title: "Section", default: .summary) var section: ActionSectionOption

    static var parameterSummary: some ParameterSummary { Summary("Open \(\.$section)") }

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = try await runAction("open.section", ["section": raw(section.rawValue)])
        return .result()
    }
}

struct OpenMetricIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Metric"
    static let description = IntentDescription("Open the chart for a metric.", categoryName: "Navigation")
    static let openAppWhenRun = true

    @Parameter(title: "Metric") var metric: HealthMetricEntity

    static var parameterSummary: some ParameterSummary { Summary("Open \(\.$metric)") }

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = try await runAction("open.metric", ["metric": raw(metric.id)])
        return .result()
    }
}

struct OpenHealthRecordIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Health Record"
    static let description = IntentDescription("Open a document in Health Records.", categoryName: "Navigation")
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Record") var record: HealthRecordEntity

    static var parameterSummary: some ParameterSummary { Summary("Open \(\.$record)") }

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = try await runAction("open.record", ["record": raw(record.id)])
        return .result()
    }
}

struct AskCoachIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask Ayuvo Coach"
    static let description = IntentDescription("Open Coach, optionally with a question typed in for you. Nothing is sent until you tap Send.", categoryName: "Navigation")
    static let openAppWhenRun = true

    @Parameter(title: "Question") var prompt: String?

    static var parameterSummary: some ParameterSummary { Summary("Ask Coach \(\.$prompt)") }

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = try await runAction("open.coach", ["prompt": raw(prompt)])
        return .result()
    }
}
