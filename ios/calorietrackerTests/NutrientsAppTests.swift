import Foundation
import Testing
@testable import calorietracker

/// Schema v2 (supplement nutrients), the archive round trip with nutrients, the one nutrient-totals path, the
/// `nutrient:` metrics and the personalised goal defaults (docs/nutrients.md, docs/medications.md §21).
struct MedicationNutrientSchemaTests {
    private typealias F = MedicationsTestFixtures

    @Test func migrationStatementsMatchSharedFilesVerbatim() throws {
        let directory = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/medications/migrations")
        let files = try FileManager.default.contentsOfDirectory(atPath: directory.path).filter { $0.hasSuffix(".sql") }.sorted()
        #expect(files == MedicationsSchema.migrations.map(\.fileName))
        #expect(MedicationsSchema.schemaVersion == MedicationsSchema.baseVersion + files.count)
        for (index, migration) in MedicationsSchema.migrations.enumerated() {
            #expect(migration.version == MedicationsSchema.baseVersion + index + 1)
            let sql = try String(contentsOf: directory.appendingPathComponent(migration.fileName), encoding: .utf8)
            let shared = try #require(MedicationsSchema.parseStatementsStrict(sql))
            #expect(shared == migration.statements, "\(migration.fileName) differs from the embedded copy")
        }
    }

    @Test func version1DatabaseUpgradesToVersion2AndKeepsRows() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Medications/medications.sqlite")
        let v1 = try await MedicationsDatabase.open(url: url, targetVersion: 1)
        #expect(try await v1.userVersion() == 1)
        #expect(!(try await v1.tableNames().contains("medication_nutrients")))
        let medication = F.medication(id: "med-d3", name: "Vitamin D3")
        try await v1.insertMedication(medication)
        await v1.close()

        let v2 = try await MedicationsDatabase.open(url: url)
        #expect(try await v2.userVersion() == 2)
        #expect(try await v2.meta("schema_version") == "2")
        #expect(try await v2.tableNames() == MedicationsSchema.tableNames.sorted())
        #expect(try await v2.medication(id: "med-d3")?.name == "Vitamin D3")
        try await v2.replaceNutrients(medicationID: "med-d3", with: [MedicationNutrient(medicationID: "med-d3", nutrientKey: "vitamin_d", amountPerUnit: 1500)], updatedMs: 9_000)
        #expect(try await v2.nutrients(medicationID: "med-d3").map(\.amountPerUnit) == [1500])
        #expect(try await v2.medication(id: "med-d3")?.updatedMs == 9_000)
        await v2.close()
    }

    @Test func nutrientsAreReplacedAsASetAndCascadeOnDelete() async throws {
        let db = try await MedicationsDatabase.inMemory()
        try await db.insertMedication(F.medication(id: "multi", name: "Multivitamin"))
        func row(_ key: String, _ amount: Double) -> MedicationNutrient { MedicationNutrient(medicationID: "multi", nutrientKey: key, amountPerUnit: amount) }
        try await db.replaceNutrients(medicationID: "multi", with: [row("zinc", 10), row("iron", 18)], updatedMs: nil)
        #expect(try await db.nutrients(medicationID: "multi").map(\.nutrientKey) == ["iron", "zinc"])
        try await db.replaceNutrients(medicationID: "multi", with: [row("vitamin_c", 80)], updatedMs: nil)
        #expect(try await db.nutrients(medicationID: "multi").map(\.nutrientKey) == ["vitamin_c"])
        try await db.deleteMedication(id: "multi")
        #expect(try await db.allNutrients().isEmpty)
    }
}

struct MedicationNutrientArchiveTests {
    private typealias F = MedicationsTestFixtures

    private func repository() async throws -> MedicationsRepository {
        let directory = try F.temporaryDirectory()
        return MedicationsRepository(database: try await MedicationsDatabase.inMemory(),
                                     photos: MedicationPhotoStore(directory: directory.appendingPathComponent("photos")))
    }

    @Test func archiveRoundTripCarriesNutrientsAndSupplementEntries() async throws {
        let source = try await repository()
        var draft = MedicationDraft(startDate: "2026-09-01")
        draft.name = "Vitamin D3 60,000 IU"
        draft.form = .capsule
        draft.doseUnit = .capsule
        draft.isPRN = true
        draft.nutrients = [DraftNutrient(key: "vitamin_d", amountPerUnit: 1500)]
        let d3 = try await source.create(draft: draft, nowMs: 1_000, zone: "UTC")
        let taken = try await source.logPRN(medicationID: d3.id, nowMs: 2_000, takenAtMs: 2_000, quantity: 1, note: nil)
        #expect(taken.ok)

        let first = try await source.exportArchive(nowMs: 5_000, zone: "UTC", platform: "ios", appVersion: "1.0")
        let exported = first.json["medications"].array?.first?["nutrients"]
        #expect(RJ.same(exported ?? .null, .arr([.obj(["key": .str("vitamin_d"), "amount_per_unit": .int(1500)])])))

        let target = try await repository()
        let summary = try await target.mergeArchive(try MedicationArchive(data: first.data), nowMs: 6_000)
        #expect(summary.insertedMedications == 1)
        #expect(try await target.nutrients(medicationID: d3.id).map(\.amountPerUnit) == [1500])
        let second = try await target.exportArchive(nowMs: 5_000, zone: "UTC", platform: "ios", appVersion: "1.0")
        #expect(first.data == second.data, "export → merge → export is byte-identical")

        let supplement = try await target.supplementData()
        #expect(supplement.entries == [NutrientsReference.SupplementEntry(tMs: 2_000, nutrientKey: "vitamin_d", value: 1500, medicationID: d3.id)])
        #expect(supplement.takenMs == [2_000])
    }

    @Test func draftValidationRejectsBadNutrients() {
        var draft = MedicationDraft(startDate: "2026-09-01")
        draft.name = "Multi"
        draft.isPRN = true
        draft.nutrients = [DraftNutrient(key: "niacin", amountPerUnit: 16), DraftNutrient(key: "vitamin_d", amountPerUnit: 250_000),
                           DraftNutrient(key: "zinc", amountPerUnit: 10), DraftNutrient(key: "zinc", amountPerUnit: 5)]
        #expect(draft.validationErrors.map(\.code) == ["nutrient_unknown", "nutrient_amount_invalid", "nutrient_duplicate"])
        draft.nutrients = [DraftNutrient(key: "creatine", amountPerUnit: 5)]
        #expect(draft.validationErrors.isEmpty)
    }
}

@MainActor
struct NutrientTotalsTests {
    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }

    private func date(_ day: Int, _ hour: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour))!
    }

    private func food(_ day: Int, _ hour: Int, vitaminD: Double?, calcium: Double? = nil) -> FoodEntry {
        FoodEntry(name: "Salmon", calories: 300, protein: 20, carbs: 0, fat: 10, timestamp: date(day, hour),
                  source: .manual, mealType: .lunch, calcium: calcium, vitaminD: vitaminD)
    }

    private func ms(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }

    @Test func foodPlusTakenSupplementsSkippedDosesIgnored() {
        let nutrients = [NutrientsReference.MedicationNutrientInput(medicationID: "d3", nutrientKey: "vitamin_d", amountPerUnit: 1500)]
        let logs = [
            NutrientsReference.DoseInput(medicationID: "d3", status: "taken", takenAtMs: ms(date(22, 9)), doseQuantity: 1),
            NutrientsReference.DoseInput(medicationID: "d3", status: "skipped", takenAtMs: nil, doseQuantity: 1),
            NutrientsReference.DoseInput(medicationID: "d3", status: "missed", takenAtMs: nil, doseQuantity: 1),
        ]
        let supplements = NutrientsReference.supplementEntries(nutrients: nutrients, doseLogs: logs)
        let totals = NutrientTotals(foods: [food(22, 13, vitaminD: 2.5, calcium: 300)], supplements: supplements, calendar: calendar)
        let d = totals.total("vitamin_d", on: date(22, 20))
        #expect(d == NutrientsReference.DayTotal(food: 2.5, supplements: 1500, total: 1502.5))
        let calcium = totals.total("calcium", on: date(22, 20))
        #expect(calcium.supplements == nil && calcium.total == 300)
        #expect(totals.total("iron", on: date(22, 20)) == .empty, "no data stays nil, never 0")
        #expect(totals.total("vitamin_d", on: date(23, 12)) == .empty)
    }

    @Test func weeklySixtyThousandIUIsAveragedPerLoggedDay() {
        let iu = NutrientsReference.convertAmount(60_000, unit: "IU", key: "vitamin_d")
        #expect(iu.amount == 1500 && iu.unit == "mcg")
        let supplements = NutrientsReference.supplementEntries(
            nutrients: [.init(medicationID: "d3", nutrientKey: "vitamin_d", amountPerUnit: iu.amount ?? 0)],
            doseLogs: [.init(medicationID: "d3", status: "taken", takenAtMs: ms(date(21, 9)), doseQuantity: 1)]
        )
        let foods = [21, 22, 23, 24, 25].map { food($0, 12, vitaminD: 2) }
        let totals = NutrientTotals(foods: foods, supplements: supplements, calendar: calendar)
        let parts = totals.entries("vitamin_d")
        #expect(parts.food.count == 5 && parts.supplements.count == 1)
        let zone = MetricsReference.Zone(calendar: calendar)
        let week = NutrientsReference.loggedDayAverage(entries: parts.food + parts.supplements, loggedDays: totals.loggedDays,
                                                       startMs: ms(date(21, 0)), endMs: ms(date(28, 0)), zone: zone)
        #expect(week.loggedDays == 5)
        #expect(week.average == 302, "(1500 + 5 × 2) / 5 logged days, not a daily 1,500 mcg")
    }

    @Test func monoAndPolyFatsAreIncluded() {
        var entry = food(22, 12, vitaminD: nil)
        entry.monounsaturatedFat = 4
        entry.polyunsaturatedFat = 2.5
        let totals = NutrientTotals(foods: [entry], calendar: calendar).totals(["monounsaturated_fat", "polyunsaturated_fat"], on: date(22, 12))
        #expect(totals["monounsaturated_fat"]?.total == 4)
        #expect(totals["polyunsaturated_fat"]?.total == 2.5)
    }
}

struct NutrientMetricKeyTests {
    @Test func nutrientKeysParseResolveAndPin() {
        #expect(MetricKey(pinID: "nutrient:vitamin_d") == .nutrient("vitamin_d"))
        #expect(MetricKey.nutrient("creatine").id == "nutrient:creatine")
        #expect(MetricKey(pinID: "nutrient:niacin") == nil)
        #expect(MetricCatalogData.shared.nutrientMetrics.count == 31)
        let resolved = MetricsReference.resolveMetric("nutrient:vitamin_d")
        #expect(resolved.source == "nutrient" && resolved.domain == "nutrition" && resolved.aggregation == "sum")
        #expect(MetricsReference.resolveMetric("nutrient:fiber").browseHidden)
        #expect(MetricsReference.resolveMetric("nutrient:niacin").source == "unknown")
        let pins = MetricsReference.favouritePinsMigrate(newRaw: "nutrient:vitamin_d,nutrient:niacin,app:calories", legacyRaw: nil, knownHealthIDs: [], max: 12)
        #expect(pins.favourites == ["nutrient:vitamin_d", "app:calories"])
    }

    @MainActor
    @Test func descriptorAndRoute() {
        let descriptor = MetricCatalog.descriptor(for: .nutrient("vitamin_d"))
        #expect(descriptor.title == OptionalNutrient.vitaminD.displayName)
        #expect(descriptor.unitLabel == "mcg")
        #expect(descriptor.ranges == HealthDetailRange.allCases)
        #expect(descriptor.domainID == "nutrition")
        #expect(MetricRoute.detail(.nutrient("vitamin_d")) != MetricRoute.detail(.nutrient("iron")))
        #expect(AppLinks.nutrientURL("vitamin-d").absoluteString == "https://ayuvo-health.web.app/nutrients/vitamin-d")
        #expect(NutrientCatalog.detailKeys.count == 31)
        #expect(NutrientCatalog.text(nil, key: "iron") == "—")
    }

    @Test func chartLinesFollowReferenceStyle() {
        let female40 = NutrientsReference.Profile(age: 40, sex: "female", calorieGoal: 2000)
        let d = NutrientCatalog.chartLines(NutrientsReference.referenceLines(key: "vitamin_d", profile: female40))
        #expect(d.map(\.label) == ["Recommended", "Upper limit"] && d.map(\.value) == [15, 100] && d.map(\.isWarning) == [false, true])
        let sodium = NutrientCatalog.chartLines(NutrientsReference.referenceLines(key: "sodium", profile: female40))
        #expect(sodium.map(\.label) == ["Limit"] && sodium.first?.isWarning == true)
        #expect(NutrientCatalog.chartLines(NutrientsReference.referenceLines(key: "cholesterol", profile: female40)).isEmpty)
        let goal = NutrientCatalog.chartLines(NutrientsReference.referenceLines(key: "cholesterol", profile: female40, customGoal: 250))
        #expect(goal.map(\.label) == ["Your goal"] && goal.first?.isWarning == false)
    }
}

struct PersonalizedNutrientGoalTests {
    @Test func oldFixedDefaultsFollowTheProfile() {
        let male30 = NutrientsReference.Profile(age: 30, sex: "male", calorieGoal: 2500)
        let goals = OptionalNutrientGoals.defaults
        #expect(goals.goal(for: .vitaminD, profile: male30) == 15, "stored 20 was the old fixed default")
        #expect(goals.goal(for: .iron, profile: male30) == 8)
        #expect(goals.goal(for: .saturatedFat, profile: male30) == 28)
        #expect(goals.goal(for: .sugar, profile: male30) == 0, "info style has no default goal")
        #expect(goals.goal(for: .creatine, profile: male30) == 0)
        #expect(!goals.isCustomized(.vitaminD))
    }

    @Test func customGoalsAreKept() {
        let female = NutrientsReference.Profile(age: 45, sex: "female", calorieGoal: nil)
        var goals = OptionalNutrientGoals.defaults.settingGoal(50, for: .vitaminD).settingGoal(250, for: .cholesterol)
        #expect(goals.goal(for: .vitaminD, profile: female) == 50)
        #expect(goals.customGoal(for: .cholesterol) == 250)
        goals = goals.settingGoal(18, for: .iron, profile: female)
        #expect(!goals.isCustomized(.iron), "saving the personalised default keeps following the profile")
        goals = goals.resettingGoal(for: .vitaminD)
        #expect(goals.goal(for: .vitaminD, profile: female) == 15)
        #expect(OptionalNutrient.magnesium.referenceUpperLimit(for: female) == 350)
        #expect(OptionalNutrient.sodium.referenceUpperLimit(for: female) == nil)
    }

    @Test func supplementPromptFillsEveryPlaceholder() throws {
        let prompts = try #require(NutrientsReference.bundledPrompts)
        let cloud = SupplementLabelAI.prompt(photo: true, route: .cloud(provider: .gemini), name: " D3 ", strength: "60000 IU",
                                             doseUnit: "capsule", prompts: prompts)
        #expect(cloud.system == prompts.cloud)
        #expect(cloud.user.contains("Its dose unit is capsule.") && cloud.user.contains("Name typed by the user: D3") && !cloud.user.contains("{"))
        let local = SupplementLabelAI.prompt(photo: false, route: .gemma, name: "Multi", strength: "", doseUnit: "tablet", prompts: prompts)
        #expect(local.system == prompts.local && local.user.contains("Name: Multi") && !local.user.contains("{"))
    }
}
