import AppIntents
import Foundation

// App entities for Shortcuts (docs/actions.md "Entities"). Every query reads through
// `ActionExecutor` / the same stores the actions use; display representations never carry health
// values in type names, only in the instance title the user asked for.

@MainActor
enum ActionEntitySupport {
    static var executor: ActionExecutor { .shared }

    static func run(_ id: String, _ params: [String: ActionRawValue] = [:]) async -> ActionResult? {
        try? await executor.run(id, params, source: .app)
    }
}

// MARK: - Metric

struct HealthMetricEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Health Metric"
    static let defaultQuery = HealthMetricQuery()

    let id: String
    @Property(title: "Name") var name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }

    @MainActor
    static func make(_ id: String) -> HealthMetricEntity? {
        guard let key = MetricKey(pinID: id) else { return nil }
        switch key {
        case .app: return HealthMetricEntity(id: id, name: MetricCatalog.descriptor(for: key).title)
        case .health(let typeID):
            guard let type = HealthMetricRegistry.type(id: typeID), !type.isAndroidOnly else { return nil }
            return HealthMetricEntity(id: id, name: type.displayName)
        }
    }

    @MainActor
    static var all: [HealthMetricEntity] {
        AppMetric.allCases.compactMap { make($0.key) } + HealthMetricRegistry.iOSTypes.compactMap { make($0.id) }
    }
}

struct HealthMetricQuery: EntityStringQuery {
    static let suggestedIDs = ["steps", "heart_rate", "resting_heart_rate", "hrv_sdnn", "sleep", "active_energy", "blood_pressure",
                               "blood_glucose", "blood_oxygen", "respiratory_rate", "app:weight", "app:body_fat", "app:calories",
                               "app:protein", "app:water", "app:fasting", "app:workouts"]

    @MainActor func entities(for identifiers: [String]) async throws -> [HealthMetricEntity] {
        identifiers.compactMap(HealthMetricEntity.make)
    }

    @MainActor func entities(matching string: String) async throws -> [HealthMetricEntity] {
        let text = string.lowercased()
        return HealthMetricEntity.all.filter { $0.name.lowercased().contains(text) || $0.id.contains(text) }
    }

    @MainActor func suggestedEntities() async throws -> [HealthMetricEntity] {
        Self.suggestedIDs.compactMap(HealthMetricEntity.make)
    }
}

// MARK: - Foods

/// A favourite or recent food that can be logged again.
struct FoodEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Saved Food"
    static let defaultQuery = FoodQuery()

    let id: String
    @Property(title: "Name") var name: String
    @Property(title: "Calories") var calories: Int
    @Property(title: "Protein (g)") var protein: Double
    @Property(title: "Carbs (g)") var carbs: Double
    @Property(title: "Fat (g)") var fat: Double

    init(_ entry: FoodEntry) {
        id = entry.id.uuidString
        name = entry.name
        calories = entry.calories
        protein = entry.protein
        carbs = entry.carbs
        fat = entry.fat
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(calories) kcal")
    }
}

struct FoodQuery: EntityStringQuery {
    @MainActor private func candidates() -> [FoodEntry] {
        let store = ActionEntitySupport.executor.env.foodStore()
        var seen = Set<String>()
        return (store.favorites + store.recentEntries(days: 30)).filter { seen.insert($0.favoriteKey).inserted }
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [FoodEntity] {
        let executor = ActionEntitySupport.executor
        let store = executor.env.foodStore()
        return identifiers.compactMap { executor.savedFood(id: $0, store: store).map(FoodEntity.init) }
    }

    @MainActor func entities(matching string: String) async throws -> [FoodEntity] {
        let text = string.lowercased()
        return candidates().filter { $0.name.lowercased().contains(text) }.prefix(50).map(FoodEntity.init)
    }

    @MainActor func suggestedEntities() async throws -> [FoodEntity] {
        candidates().prefix(30).map(FoodEntity.init)
    }
}

/// A food in the diary.
struct FoodEntryEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Logged Food"
    static let defaultQuery = FoodEntryQuery()

    let id: String
    @Property(title: "Name") var name: String
    @Property(title: "Calories") var calories: Int
    @Property(title: "Protein (g)") var protein: Double
    @Property(title: "Carbs (g)") var carbs: Double
    @Property(title: "Fat (g)") var fat: Double
    @Property(title: "Meal") var meal: String
    @Property(title: "Date") var date: Date

    init(_ entry: FoodEntry) {
        id = entry.id.uuidString
        name = entry.name
        calories = entry.calories
        protein = entry.protein
        carbs = entry.carbs
        fat = entry.fat
        meal = entry.mealType.displayName
        date = entry.timestamp
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(calories) kcal · \(meal)")
    }
}

struct FoodEntryQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [FoodEntryEntity] {
        let entries = ActionEntitySupport.executor.env.foodStore().entries
        return identifiers.compactMap { id in entries.first { $0.id.uuidString == id }.map(FoodEntryEntity.init) }
    }

    @MainActor func suggestedEntities() async throws -> [FoodEntryEntity] {
        ActionEntitySupport.executor.env.foodStore().todayEntries.map(FoodEntryEntity.init)
    }
}

// MARK: - Workouts

struct ExerciseEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Exercise"
    static let defaultQuery = ExerciseQuery()

    let id: String
    @Property(title: "Name") var name: String
    @Property(title: "Body Part") var bodyPart: String

    init(_ item: ExerciseLibraryItem) {
        id = item.id
        name = item.name
        bodyPart = item.bodyPart
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)", subtitle: "\(bodyPart)") }
}

struct ExerciseQuery: EntityStringQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [ExerciseEntity] {
        let executor = ActionEntitySupport.executor
        let store = executor.env.workoutStore()
        return identifiers.compactMap { executor.exerciseItem($0, store: store).map(ExerciseEntity.init) }
    }

    @MainActor func entities(matching string: String) async throws -> [ExerciseEntity] {
        let text = string.lowercased()
        let library = ActionEntitySupport.executor.env.workoutStore().exerciseLibrary.exercises
        return library.filter { $0.searchableText.contains(text) || $0.name.lowercased().contains(text) }.prefix(40).map(ExerciseEntity.init)
    }

    @MainActor func suggestedEntities() async throws -> [ExerciseEntity] {
        let executor = ActionEntitySupport.executor
        let store = executor.env.workoutStore()
        let today = store.exercises(for: executor.env.nowDate).map(\.libraryItem)
        let saved = store.exerciseLibrary.exercises.filter { store.savedExerciseIDs.contains($0.id) }
        var seen = Set<String>()
        return (today + saved).filter { seen.insert($0.id).inserted }.map(ExerciseEntity.init)
    }
}

struct WorkoutEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Workout"
    static let defaultQuery = WorkoutQuery()

    let id: String
    @Property(title: "Date") var date: Date
    @Property(title: "Exercises") var exercises: Int
    @Property(title: "Sets") var sets: Int
    @Property(title: "Volume (kg)") var volumeKg: Double
    @Property(title: "Duration (minutes)") var minutes: Int

    init(_ session: StrengthWorkoutSession, volumeKg: Double) {
        id = session.id.uuidString
        date = session.calendarDiaryDate
        exercises = session.exerciseCount
        sets = session.performedSetCount
        self.volumeKg = volumeKg
        minutes = session.durationMinutes
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(date.formatted(date: .abbreviated, time: .omitted))", subtitle: "\(exercises) exercises · \(sets) sets")
    }
}

struct WorkoutQuery: EntityQuery {
    @MainActor private func sessions() -> [WorkoutEntity] {
        let env = ActionEntitySupport.executor.env
        let unit: WeightUnit = env.massUnit == "kg" ? .kg : .lbs
        return env.workoutStore().sortedCompletedSessions.prefix(60).map {
            WorkoutEntity($0, volumeKg: ActionExecutor.sessionVolume($0, fallback: unit))
        }
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [WorkoutEntity] {
        let all = sessions()
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }

    @MainActor func suggestedEntities() async throws -> [WorkoutEntity] { Array(sessions().prefix(20)) }
}

// MARK: - Medications

struct MedicationEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Medication"
    static let defaultQuery = MedicationQuery()

    let id: String
    @Property(title: "Name") var name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct MedicationQuery: EntityStringQuery {
    @MainActor private func medications(search: String?) async -> [MedicationEntity] {
        guard let runtime = ActionEntitySupport.executor.env.medicationsRuntime, runtime.databaseExists,
              await runtime.openIfNeeded(), let repository = runtime.repository else { return [] }
        return ((try? await repository.medications(status: nil, search: search)) ?? []).map { MedicationEntity(id: $0.id, name: $0.displayName) }
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [MedicationEntity] {
        let all = await medications(search: nil)
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }

    @MainActor func entities(matching string: String) async throws -> [MedicationEntity] { await medications(search: string) }

    @MainActor func suggestedEntities() async throws -> [MedicationEntity] { await medications(search: nil) }
}

/// One of today's scheduled doses: medication name and time only (never the strength or amount).
struct DoseEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Dose"
    static let defaultQuery = DoseQuery()

    let id: String
    @Property(title: "Medication") var medication: String
    @Property(title: "Time") var time: Date
    @Property(title: "Status") var status: String

    init(fields: [String: ActionField]) {
        id = fields["id"]?.string ?? ""
        medication = fields["medication"]?.string ?? ""
        time = Date(timeIntervalSince1970: (fields["scheduled_ms"]?.double ?? 0) / 1000)
        status = fields["status"]?.string ?? ""
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(medication)", subtitle: "\(time.formatted(date: .omitted, time: .shortened)) · \(status)")
    }
}

struct DoseQuery: EntityQuery {
    @MainActor private func today() async -> [DoseEntity] {
        guard let result = await ActionEntitySupport.run("medications.today.list") else { return [] }
        return (result.items ?? []).map(DoseEntity.init)
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [DoseEntity] {
        let all = await today()
        return identifiers.compactMap { id in
            all.first { $0.id == id } ?? ActionExecutor.occurrence(fromDoseID: id).map { _ in
                DoseEntity(fields: ["id": .string(id), "medication": .string(""), "status": .string("")])
            }
        }
    }

    @MainActor func suggestedEntities() async throws -> [DoseEntity] { await today() }
}

// MARK: - Records

struct HealthRecordEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Health Record"
    static let defaultQuery = HealthRecordQuery()

    let id: String
    @Property(title: "Title") var title: String
    @Property(title: "Kind") var kind: String
    @Property(title: "Date") var date: String

    init(fields: [String: ActionField]) {
        id = fields["id"]?.string ?? ""
        title = fields["title"]?.string ?? ""
        kind = (fields["kind"]?.string ?? "").replacingOccurrences(of: "_", with: " ")
        date = fields["date"]?.string ?? ""
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)", subtitle: "\(date)") }
}

struct HealthRecordQuery: EntityStringQuery {
    @MainActor private func search(_ text: String, limit: Int = 20) async -> [HealthRecordEntity] {
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty,
              let result = await ActionEntitySupport.run("records.search", ["query": .string(text), "limit": .number(Double(limit))]) else { return [] }
        return (result.items ?? []).map(HealthRecordEntity.init)
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [HealthRecordEntity] {
        let executor = ActionEntitySupport.executor
        var out: [HealthRecordEntity] = []
        for id in identifiers {
            guard let payload = try? await executor.recordsPayload(RecordsCoachContract.getTool, ["record_id": id]),
                  let record = payload["records"].array?.first ?? payload["record"].object.map({ RJ.obj($0) })
            else { continue }
            var fields = ActionExecutor.recordFields(record)
            if fields["id"]?.string == nil { fields["id"] = .string(id) }
            out.append(HealthRecordEntity(fields: fields))
        }
        return out
    }

    @MainActor func entities(matching string: String) async throws -> [HealthRecordEntity] { await search(string) }

    @MainActor func suggestedEntities() async throws -> [HealthRecordEntity] {
        guard let result = await ActionEntitySupport.run("records.latest") else { return [] }
        return [HealthRecordEntity(fields: result.fields)]
    }
}

/// A lab test (analyte) such as HbA1c.
struct LabTestEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Lab Test"
    static let defaultQuery = LabTestQuery()

    let id: String
    @Property(title: "Name") var name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct LabTestQuery: EntityStringQuery {
    static let common = ["hba1c", "glucose_fasting", "hemoglobin", "ldl", "hdl", "triglycerides", "total_cholesterol", "tsh",
                         "vitamin_d", "vitamin_b12", "creatinine", "ferritin"]

    func entities(for identifiers: [String]) async throws -> [LabTestEntity] {
        identifiers.map { id in LabTestEntity(id: id, name: AnalyteCatalog.shared.analyte(id: id)?.displayName ?? id) }
    }

    func entities(matching string: String) async throws -> [LabTestEntity] {
        AnalyteCatalog.shared.search(string, limit: 30).map { LabTestEntity(id: $0.id, name: $0.displayName) }
    }

    func suggestedEntities() async throws -> [LabTestEntity] {
        Self.common.compactMap { id in AnalyteCatalog.shared.analyte(id: id).map { LabTestEntity(id: $0.id, name: $0.displayName) } }
    }
}

// MARK: - Fasting

struct FastingSessionEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Fast"
    static let defaultQuery = FastingSessionQuery()

    let id: String
    @Property(title: "Start") var start: Date
    @Property(title: "End") var end: Date?
    @Property(title: "Hours") var hours: Double
    @Property(title: "Goal (hours)") var goalHours: Double
    @Property(title: "Goal Reached") var reached: Bool

    init(fields: [String: ActionField]) {
        id = fields["id"]?.string ?? ""
        start = Date(timeIntervalSince1970: (fields["started_ms"]?.double ?? 0) / 1000)
        end = fields["ended_ms"]?.double.map { Date(timeIntervalSince1970: $0 / 1000) }
        hours = ActionMath.roundTo((fields["duration_s"]?.double ?? 0) / 3600, 2)
        goalHours = ActionMath.roundTo((fields["goal_s"]?.double ?? 0) / 3600, 2)
        reached = fields["reached"]?.bool ?? false
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(start.formatted(date: .abbreviated, time: .shortened))", subtitle: "\(hours.formatted()) h")
    }
}

struct FastingSessionQuery: EntityQuery {
    @MainActor private func recent() -> [FastingSessionEntity] {
        let env = ActionEntitySupport.executor.env
        return env.fastingStore().sessions.reversed().prefix(60).map { FastingSessionEntity(fields: ActionExecutor.sessionFields($0, now: env.nowDate)) }
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [FastingSessionEntity] {
        let all = recent()
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }

    @MainActor func suggestedEntities() async throws -> [FastingSessionEntity] { Array(recent().prefix(20)) }
}

// MARK: - Search

struct SearchResultEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Ayuvo Search Result"
    static let defaultQuery = SearchResultQuery()

    let id: String
    @Property(title: "Kind") var domain: String
    @Property(title: "Title") var title: String
    @Property(title: "Detail") var subtitle: String
    @Property(title: "Item ID") var itemID: String

    init(fields: [String: ActionField]) {
        let domain = fields["domain"]?.string ?? ""
        let itemID = fields["id"]?.string ?? ""
        let title = fields["title"]?.string ?? ""
        id = "\(domain)|\(itemID)|\(title)"
        self.domain = domain
        self.itemID = itemID
        self.title = title
        subtitle = fields["subtitle"]?.string ?? ""
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)", subtitle: "\(subtitle)") }
}

struct SearchResultQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [SearchResultEntity] {
        identifiers.map { id in
            let parts = id.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
            return SearchResultEntity(fields: [
                "domain": .string(parts.first ?? ""), "id": .string(parts.count > 1 ? parts[1] : ""),
                "title": .string(parts.count > 2 ? parts[2] : ""),
            ])
        }
    }
}

// MARK: - Structured results (composable in Shortcuts: "Get … → Get <property>")

/// Snapshot entities are recomputed by id when Shortcuts re-resolves them.
@MainActor
enum SnapshotEntitySupport {
    static func result(_ actionID: String, _ params: [String: ActionRawValue] = [:]) async -> ActionResult? {
        await ActionEntitySupport.run(actionID, params)
    }
}

struct NutritionSummaryEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Nutrition Summary"
    static let defaultQuery = NutritionSummaryQuery()

    let id: String
    @Property(title: "Summary") var details: String
    @Property(title: "Calories") var calories: Double
    @Property(title: "Protein (g)") var protein: Double
    @Property(title: "Carbs (g)") var carbs: Double
    @Property(title: "Fat (g)") var fat: Double
    @Property(title: "Calorie Target") var calorieTarget: Double?
    @Property(title: "Protein Target (g)") var proteinTarget: Double?
    @Property(title: "Carbs Target (g)") var carbsTarget: Double?
    @Property(title: "Fat Target (g)") var fatTarget: Double?
    @Property(title: "Calories Remaining") var caloriesRemaining: Double?
    @Property(title: "Water (ml)") var water: Double?
    @Property(title: "Water Goal (ml)") var waterGoal: Double?
    @Property(title: "Foods Logged") var entries: Int
    // Detailed Nutrition, same rows as Nutrition Details.
    @Property(title: "Sugar (g)") var sugar: Double
    @Property(title: "Added Sugar (g)") var addedSugar: Double
    @Property(title: "Fiber (g)") var fiber: Double
    @Property(title: "Saturated Fat (g)") var saturatedFat: Double
    @Property(title: "Monounsaturated Fat (g)") var monounsaturatedFat: Double
    @Property(title: "Polyunsaturated Fat (g)") var polyunsaturatedFat: Double
    @Property(title: "Cholesterol (mg)") var cholesterol: Double
    @Property(title: "Caffeine (mg)") var caffeine: Double
    @Property(title: "Sodium (mg)") var sodium: Double
    @Property(title: "Potassium (mg)") var potassium: Double
    @Property(title: "Trans Fat (g)") var transFat: Double
    @Property(title: "Calcium (mg)") var calcium: Double
    @Property(title: "Iron (mg)") var iron: Double
    @Property(title: "Magnesium (mg)") var magnesium: Double
    @Property(title: "Zinc (mg)") var zinc: Double
    @Property(title: "Vitamin A (mcg)") var vitaminA: Double
    @Property(title: "Vitamin C (mg)") var vitaminC: Double
    @Property(title: "Vitamin D (mcg)") var vitaminD: Double
    @Property(title: "Vitamin B12 (mcg)") var vitaminB12: Double
    @Property(title: "Vitamin E (mg)") var vitaminE: Double
    @Property(title: "Vitamin K (mcg)") var vitaminK: Double
    @Property(title: "Folate (mcg)") var folate: Double
    @Property(title: "Omega-3 (g)") var omega3: Double
    @Property(title: "Creatine (g)") var creatine: Double
    @Property(title: "Beta-Alanine (g)") var betaAlanine: Double
    @Property(title: "L-Citrulline (g)") var lCitrulline: Double
    @Property(title: "L-Carnitine (g)") var lCarnitine: Double
    @Property(title: "L-Arginine (g)") var lArginine: Double
    @Property(title: "Taurine (g)") var taurine: Double
    @Property(title: "Betaine (g)") var betaine: Double
    @Property(title: "HMB (g)") var hmb: Double

    init(range: String, fields: [String: ActionField]) {
        func value(_ name: String) -> Double { fields[name]?.double ?? 0 }
        func detail(_ nutrient: OptionalNutrient) -> Double { value("\(nutrient.jsonKey)_\(nutrient.unit)") }
        id = range
        details = fields["details_text"]?.string ?? ""
        calories = value("calories")
        protein = value("protein_g")
        carbs = value("carbs_g")
        fat = value("fat_g")
        calorieTarget = fields["calorie_target"]?.double
        proteinTarget = fields["protein_target_g"]?.double
        carbsTarget = fields["carbs_target_g"]?.double
        fatTarget = fields["fat_target_g"]?.double
        caloriesRemaining = fields["calories_remaining"]?.double
        water = fields["water_ml"]?.double
        waterGoal = fields["water_goal_ml"]?.double
        entries = Int(value("entry_count"))
        sugar = detail(.sugar)
        addedSugar = detail(.addedSugar)
        fiber = detail(.fiber)
        saturatedFat = detail(.saturatedFat)
        monounsaturatedFat = value("monounsaturated_fat_g")
        polyunsaturatedFat = value("polyunsaturated_fat_g")
        cholesterol = detail(.cholesterol)
        caffeine = detail(.caffeine)
        sodium = detail(.sodium)
        potassium = detail(.potassium)
        transFat = detail(.transFat)
        calcium = detail(.calcium)
        iron = detail(.iron)
        magnesium = detail(.magnesium)
        zinc = detail(.zinc)
        vitaminA = detail(.vitaminA)
        vitaminC = detail(.vitaminC)
        vitaminD = detail(.vitaminD)
        vitaminB12 = detail(.vitaminB12)
        vitaminE = detail(.vitaminE)
        vitaminK = detail(.vitaminK)
        folate = detail(.folate)
        omega3 = detail(.omega3)
        creatine = detail(.creatine)
        betaAlanine = detail(.betaAlanine)
        lCitrulline = detail(.lCitrulline)
        lCarnitine = detail(.lCarnitine)
        lArginine = detail(.lArginine)
        taurine = detail(.taurine)
        betaine = detail(.betaine)
        hmb = detail(.hmb)
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(Int(calories)) kcal", subtitle: "\(Int(protein)) g protein · \(Int(carbs)) g carbs · \(Int(fat)) g fat")
    }
}

struct NutritionSummaryQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [NutritionSummaryEntity] {
        var out: [NutritionSummaryEntity] = []
        for id in identifiers {
            if let result = await SnapshotEntitySupport.result("nutrition.summary.get", ["range": .string(id)]) {
                out.append(NutritionSummaryEntity(range: id, fields: result.fields))
            }
        }
        return out
    }
}

struct WaterStatusEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Water"
    static let defaultQuery = WaterStatusQuery()

    let id: String
    @Property(title: "Intake (ml)") var intake: Int
    @Property(title: "Goal (ml)") var goal: Int?
    @Property(title: "Remaining (ml)") var remaining: Int?
    @Property(title: "Percent of Goal") var percent: Int?

    init(range: String, fields: [String: ActionField]) {
        id = range
        intake = Int(fields["intake_ml"]?.double ?? 0)
        goal = fields["goal_ml"]?.double.map { Int($0) }
        remaining = fields["remaining_ml"]?.double.map { Int($0) }
        percent = fields["percent"]?.double.map { Int($0) }
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(intake) ml") }
}

struct WaterStatusQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [WaterStatusEntity] {
        var out: [WaterStatusEntity] = []
        for id in identifiers {
            if let result = await SnapshotEntitySupport.result("water.get", ["range": .string(id)]) {
                out.append(WaterStatusEntity(range: id, fields: result.fields))
            }
        }
        return out
    }
}

struct FastingStatusEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Fasting Status"
    static let defaultQuery = FastingStatusQuery()

    let id: String
    @Property(title: "Fasting") var active: Bool
    @Property(title: "Hours Fasted") var hours: Double
    @Property(title: "Hours Remaining") var hoursRemaining: Double
    @Property(title: "Goal (hours)") var goalHours: Double
    @Property(title: "Percent of Goal") var percent: Int
    @Property(title: "Goal Reached") var reached: Bool

    init(fields: [String: ActionField]) {
        id = "current"
        active = fields["active"]?.bool ?? false
        hours = ActionMath.roundTo((fields["elapsed_s"]?.double ?? 0) / 3600, 2)
        hoursRemaining = ActionMath.roundTo((fields["remaining_s"]?.double ?? 0) / 3600, 2)
        goalHours = ActionMath.roundTo((fields["goal_s"]?.double ?? 0) / 3600, 2)
        percent = Int(fields["percent"]?.double ?? 0)
        reached = fields["reached"]?.bool ?? false
    }

    var displayRepresentation: DisplayRepresentation {
        active ? DisplayRepresentation(title: "Fasting \(hours.formatted()) h") : DisplayRepresentation(title: "Not fasting")
    }
}

struct FastingStatusQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [FastingStatusEntity] {
        guard let result = await SnapshotEntitySupport.result("fasting.status.get") else { return [] }
        return identifiers.map { _ in FastingStatusEntity(fields: result.fields) }
    }
}

struct BodyCompositionEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Body Composition"
    static let defaultQuery = BodyCompositionQuery()

    let id: String
    @Property(title: "Weight (kg)") var weightKg: Double?
    @Property(title: "Body Fat (%)") var bodyFat: Double?
    @Property(title: "Height (cm)") var heightCm: Double?
    @Property(title: "BMI") var bmi: Double?

    init(fields: [String: ActionField]) {
        id = "current"
        weightKg = fields["weight_kg"]?.double
        bodyFat = fields["body_fat_percent"]?.double
        heightCm = fields["height_cm"]?.double
        bmi = fields["bmi"]?.double
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "BMI \(bmi.map { $0.formatted() } ?? "—")")
    }
}

struct BodyCompositionQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [BodyCompositionEntity] {
        guard let result = await SnapshotEntitySupport.result("body.composition.get") else { return [] }
        return identifiers.map { _ in BodyCompositionEntity(fields: result.fields) }
    }
}

struct GoalsEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Daily Goals"
    static let defaultQuery = GoalsQuery()

    let id: String
    @Property(title: "Calories") var calories: Double?
    @Property(title: "Protein (g)") var protein: Double?
    @Property(title: "Carbs (g)") var carbs: Double?
    @Property(title: "Fat (g)") var fat: Double?
    @Property(title: "Water (ml)") var water: Int
    @Property(title: "Steps") var steps: Int
    @Property(title: "Goal Weight (kg)") var goalWeightKg: Double?

    init(fields: [String: ActionField]) {
        id = "current"
        calories = fields["calories"]?.double
        protein = fields["protein_g"]?.double
        carbs = fields["carbs_g"]?.double
        fat = fields["fat_g"]?.double
        water = Int(fields["water_ml"]?.double ?? Double(WaterSettings.defaultDailyGoalMl))
        steps = Int(fields["steps"]?.double ?? Double(ActivitySettings.defaultDailyStepGoal))
        goalWeightKg = fields["goal_weight_kg"]?.double
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(Int(calories ?? 0)) kcal", subtitle: "\(Int(protein ?? 0)) g protein · \(steps) steps")
    }
}

struct GoalsQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [GoalsEntity] {
        guard let result = await SnapshotEntitySupport.result("goals.get") else { return [] }
        return identifiers.map { _ in GoalsEntity(fields: result.fields) }
    }
}

struct LabResultEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Lab Result"
    static let defaultQuery = LabResultQuery()

    let id: String
    @Property(title: "Test") var name: String
    @Property(title: "Value") var value: Double?
    @Property(title: "Unit") var unit: String
    @Property(title: "Date") var date: String
    @Property(title: "Flag") var flag: String

    init(analyte: String, name: String, fields: [String: ActionField]) {
        id = analyte
        self.name = name
        value = fields["value"]?.double
        unit = fields["unit"]?.string ?? ""
        date = fields["date"]?.string ?? ""
        flag = fields["flag"]?.string ?? ""
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(value.map { $0.formatted() } ?? "—") \(unit) · \(date)")
    }
}

struct LabResultQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [LabResultEntity] {
        var out: [LabResultEntity] = []
        for id in identifiers {
            if let result = await SnapshotEntitySupport.result("records.labValue.get", ["analyte": .string(id)]) {
                out.append(LabResultEntity(analyte: id, name: AnalyteCatalog.shared.analyte(id: id)?.displayName ?? id, fields: result.fields))
            }
        }
        return out
    }
}

struct ProfileEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Profile"
    static let defaultQuery = ProfileQuery()

    let id: String
    @Property(title: "Age") var age: Int
    @Property(title: "Height (cm)") var heightCm: Double
    @Property(title: "Weight (kg)") var weightKg: Double
    @Property(title: "Weight Unit") var massUnit: String
    @Property(title: "Water Unit") var volumeUnit: String

    init(fields: [String: ActionField]) {
        id = "current"
        age = Int(fields["age"]?.double ?? 0)
        heightCm = fields["height_cm"]?.double ?? 0
        weightKg = fields["weight_kg"]?.double ?? 0
        massUnit = fields["mass_unit"]?.string ?? ""
        volumeUnit = fields["volume_unit"]?.string ?? ""
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "Profile") }
}

struct ProfileQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [ProfileEntity] {
        guard let result = await SnapshotEntitySupport.result("profile.summary.get") else { return [] }
        return identifiers.map { _ in ProfileEntity(fields: result.fields) }
    }
}
